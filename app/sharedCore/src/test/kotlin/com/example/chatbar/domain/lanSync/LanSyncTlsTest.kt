package com.example.chatbar.domain.lanSync

import java.net.InetSocketAddress
import javax.net.ssl.*
import kotlinx.coroutines.*
import kotlin.test.*

class LanSyncTlsTest {
    @Test fun actualMutualTlsStreamsAndRejectsAnUnpinnedIdentity(): Unit = runBlocking {
        val serverId = LanSyncTlsIdentity.create(); val clientId = LanSyncTlsIdentity.create()
        val server = serverId.context { it == clientId.fingerprint }.serverSocketFactory.createServerSocket() as SSLServerSocket
        server.soTimeout = 10_000
        server.needClientAuth = true; server.bind(InetSocketAddress("127.0.0.1", 0))
        try {
        val receiving = async(Dispatchers.IO) {
            server.accept().use { raw ->
                val socket = raw as SSLSocket; LanSyncTls.configure(socket); socket.startHandshake()
                LanSyncWire(socket).use { wire ->
                    assertEquals("synthetic", wire.text()); wire.sendText("authenticated")
                    wire.send(byteArrayOf(0xc3.toByte(), 0x28))
                    val bytes = ByteArray(200_000) { (it % 251).toByte() }
                    wire.sendBlob(bytes.inputStream(), bytes.size.toLong())
                }
            }
        }
        withContext(Dispatchers.IO) {
            val client = clientId.context { it == serverId.fingerprint }.socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket
            LanSyncTls.configure(client); client.startHandshake()
            LanSyncWire(client).use { wire ->
                wire.sendText("synthetic"); assertEquals("authenticated", wire.text())
                assertFailsWith<IllegalArgumentException> { wire.text() }
                val expected = ByteArray(200_000) { (it % 251).toByte() }; val destination = java.io.ByteArrayOutputStream()
                wire.receiveBlob(destination, expected.size.toLong(), lanSyncHash(expected))
                assertContentEquals(expected, destination.toByteArray())
            }
        }
        receiving.await()
        } finally { server.close() }

        val refusing = serverId.context { false }.serverSocketFactory.createServerSocket() as SSLServerSocket
        refusing.soTimeout = 10_000
        refusing.needClientAuth = true; refusing.bind(InetSocketAddress("127.0.0.1", 0))
        try {
        val refusal = async(Dispatchers.IO) {
            refusing.accept().use { raw -> val accepted = raw as SSLSocket; LanSyncTls.configure(accepted); accepted.soTimeout = 10_000
                assertFails { accepted.startHandshake() } }
        }
        withContext(Dispatchers.IO) {
            val client = clientId.context { it == serverId.fingerprint }.socketFactory.createSocket("127.0.0.1", refusing.localPort) as SSLSocket
            LanSyncTls.configure(client); client.soTimeout = 10_000
            client.use { runCatching { it.startHandshake(); it.outputStream.write(1); it.inputStream.read() } }
        }
        refusal.await()
        } finally { refusing.close() }
    }
    @Test fun numericLocalAddressPolicyRefusesDnsLoopbackAndInternet() {
        listOf("localhost", "127.0.0.1", "8.8.8.8", "https://192.168.1.1", "192.168.1.1/path", "192.168.01.1").forEach {
            assertFails { LanSyncTls.parseAddress(it) }
        }
        assertEquals("192.168.1.1", LanSyncTls.parseAddress("192.168.1.1").hostAddress)
    }
    @Test fun expiredPairCapabilityAndSecondPeerAreRefusedPersistently() {
        val root = java.nio.file.Files.createTempDirectory("ccb-lan-trust-").toRealPath()
        val store = LanSyncStateStore(root, { it }, { it })
        val identity = store.identity(); assertEquals(identity.fingerprint, LanSyncStateStore(root, { it }, { it }).identity().fingerprint)
        store.trust(LanSyncPeer("a".repeat(64), "synthetic"))
        assertFails { store.trust(LanSyncPeer("b".repeat(64), "other synthetic")) }
        store.revoke("a".repeat(64)); assertTrue(store.peers().isEmpty())
        assertFails { LanSyncPairOffer(host = "192.168.1.1", port = 39238, fingerprint = "a".repeat(64), token = "b".repeat(64), expiresAt = 1).validate() }
    }
}
