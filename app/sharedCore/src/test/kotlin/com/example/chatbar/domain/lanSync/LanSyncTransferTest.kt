package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class LanSyncTransferTest {
    private fun exchange(receiver: (LanSyncWire) -> Unit, sender: (LanSyncWire) -> Unit): Pair<Result<Unit>, Result<Unit>> {
        val left = LanSyncTlsIdentity.create(); val right = LanSyncTlsIdentity.create()
        val listener = left.context { it == right.fingerprint }.serverSocketFactory
            .createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        listener.needClientAuth = true
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<Result<Unit>> {
                runCatching { (listener.accept() as SSLSocket).use { socket ->
                    LanSyncTls.configure(socket); socket.startHandshake(); LanSyncWire(socket).use(receiver)
                } }
            }
            val sent = runCatching {
                (right.context { it == left.fingerprint }.socketFactory.createSocket("127.0.0.1", listener.localPort) as SSLSocket).use { socket ->
                    LanSyncTls.configure(socket); socket.startHandshake(); LanSyncWire(socket).use(sender)
                }
            }
            return result.get(30, TimeUnit.SECONDS) to sent
        } finally { listener.close(); pool.shutdownNow(); check(pool.awaitTermination(30, TimeUnit.SECONDS)) }
    }
    @Test fun actualTlsDisconnectResumesOnlyTheVerifiedPrefixThenReusesTheCompleteObject() {
        val root = Files.createTempDirectory("lan-transfer-resume-").toRealPath()
        try {
            val source = root.resolve("source"); Files.createDirectory(source)
            val destination = root.resolve("destination"); Files.createDirectory(destination)
            val bytes = ByteArray(3 * 1024 * 1024 + 31) { (it % 239).toByte() }
            val hash = lanSyncHash(bytes); val file = source.resolve(hash); Files.write(file, bytes)
            val reference = "ccb-lan-v1/images/11111111-1111-1111-1111-111111111111/$hash/bin"
            val card = CharacterCard("card", "续传合成卡", avatar = reference, createdAt = 1, updatedAt = 1)
            val capture = LanSyncCapture(LanSyncSnapshot(deviceId = "sender", records = listOf(
                LanSyncRecord("character_cards", card.id, lanSyncJson.encodeToJsonElement(CharacterCard.serializer(), card).jsonObject)),
                resources = mapOf(hash to bytes.size.toLong())), mapOf(hash to file), emptyMap())
            val cacheRoot = destination.resolve("resources")
            val first = exchange(receiver = { wire ->
                LanSyncTransfer.receive(wire, destination, "sender", LanSyncResourceStore(cacheRoot)) { phase, _ ->
                    if (phase == "RESOURCE_CHUNK_RECEIVED_BYTES") throw java.io.IOException("synthetic mid-transfer disconnect")
                }
            }, sender = { wire -> LanSyncTransfer.send(wire, source, capture) })
            assertTrue(first.first.isFailure); assertTrue(first.second.isFailure)
            val reopened = LanSyncResourceStore(cacheRoot)
            reopened.open(hash, bytes.size.toLong()).use { assertEquals(1024L * 1024, it.offset) }
            var transmitted = 0L
            fun round(): Pair<Result<Unit>, Result<Unit>> = exchange(receiver = { wire ->
                LanSyncTransfer.receive(wire, destination, "sender", LanSyncResourceStore(cacheRoot)).use {
                    assertEquals(hash, LanSyncResourceStore.hashFile(it.blobs.getValue(hash), bytes.size.toLong()))
                }
            }, sender = { wire ->
                LanSyncTransfer.send(wire, source, capture) { phase, count -> if (phase == "RESOURCE_CHUNK_SENT_BYTES") transmitted += count }
            })
            round().let { it.first.getOrThrow(); it.second.getOrThrow() }
            assertEquals(bytes.size.toLong() - 1024 * 1024, transmitted)
            transmitted = 0; round().let { it.first.getOrThrow(); it.second.getOrThrow() }; assertEquals(0L, transmitted)
            assertContentEquals(bytes, Files.readAllBytes(requireNotNull(reopened.completed(hash, bytes.size.toLong()))))
        } finally { check(root.toFile().deleteRecursively()) }
    }
    @Test fun metadataCrossesFrameBoundariesAndOversizedControlFramesRefuseBeforeAllocation() {
        val root = Files.createTempDirectory("lan-framed-json-").toRealPath()
        try {
            val value = List(3000) { "合成元数据" + it + "x".repeat(512) }
            val result = exchange(receiver = { wire ->
                assertEquals(value, LanSyncFramedJson.receive(wire, root, ListSerializer(String.serializer())))
            }, sender = { wire -> LanSyncFramedJson.send(wire, root, ListSerializer(String.serializer()), value) })
            result.first.getOrThrow(); result.second.getOrThrow()
            val refused = exchange(receiver = { wire ->
                assertEquals("SYNC_MESSAGE_LIMIT", assertFailsWith<IllegalArgumentException> { wire.text(1024) }.message)
            }, sender = { wire -> wire.send(ByteArray(2048)) })
            refused.first.getOrThrow()
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
