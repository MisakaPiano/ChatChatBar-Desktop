package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.*
import java.nio.file.*
import javax.net.ssl.SSLSocket
import kotlin.test.*

class LanSyncRuntimeTest {
    private class SyntheticLan : LanSyncNetwork {
        private val endpoints = java.util.concurrent.ConcurrentHashMap<String, Int>()
        override fun addresses() = listOf("192.168.1.2")
        override fun accepts(address: InetAddress) = address.isLoopbackAddress
        override val discoveryEnabled = false
        override fun listening(fingerprint: String, port: Int) { endpoints[fingerprint] = port }
        override fun discover(fingerprint: String) = endpoints[fingerprint]?.let { "192.168.1.2" to it }
        override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket {
            val plain = Socket()
            try {
                plain.connect(InetSocketAddress("127.0.0.1", peer.port), 2000)
                val socket = identity.context { it == peer.fingerprint }.socketFactory.createSocket(plain, "127.0.0.1", peer.port, true) as SSLSocket
                LanSyncTls.configure(socket); socket.startHandshake(); return socket
            } catch (failure: Throwable) { plain.close(); throw failure }
        }
    }
    private class End(val name: String, val network: SyntheticLan, private val observe: (String, String) -> Unit = { _, _ -> }) {
        val root = Files.createTempDirectory("ccb-lan-$name-").toRealPath()
        val stateRoot = Files.createTempDirectory("ccb-lan-$name-state-").toRealPath()
        val store = LanSyncStateStore(stateRoot, { it }, { it })
        val id = store.identity().fingerprint
        val workspace = LanSyncWorkspace(root, stateRoot.resolve("workspace"), { raw -> Path.of(raw) })
        val gate = LanSyncOperationGate()
        fun runtime() = LanSyncRuntime(store, workspace, object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) = gate.exclusive(work)
            override suspend fun reload(sessionIds: Set<String>) {}
        }, name, observe = observe, network = network)
        fun write(id: String, name: String) {
            val path = root.resolve("entities/character_cards/$id.json"); Files.createDirectories(path.parent)
            Files.writeString(path, lanSyncJson.encodeToJsonElement(CharacterCard.serializer(), CharacterCard(id, name, createdAt = 1, updatedAt = 1)).toString())
        }
        fun names(): Set<String> = workspace.capture(id).use { capture -> capture.snapshot.records.mapNotNull { it.text("name") }.toSet() }
    }
    private suspend fun until(reason: String, check: () -> Boolean) {
        withTimeout(30_000) { while (!check()) delay(50) }
        assertTrue(check(), reason)
    }
    private suspend fun pair(host: LanSyncRuntime, phone: LanSyncRuntime, a: End, b: End) {
        host.startHost(); until("host listening") { host.state.value.addresses.isNotEmpty() }
        host.pairOffer("192.168.1.2"); phone.pair(requireNotNull(host.state.value.offer).code())
        until("mutual pairing") { a.store.peers().size == 1 && b.store.peers().size == 1 }
        phone.startPhone()
    }
    private suspend fun completed(a: End, b: End, previous: String? = null) = until("bilateral receipts") {
        val left = a.store.baseline(b.id, a.id).revision; val right = b.store.baseline(a.id, b.id).revision
        left != null && left == right && left != previous && a.store.pending(b.id) == null && b.store.pending(a.id) == null
    }
    @Test fun actualPairSnapshotApplyRepeatDeletionAndReopenedHostAddress() = runBlocking {
        val network = SyntheticLan(); val a = End("computer", network); val b = End("phone", network)
        a.write("a", "电脑独有"); b.write("b", "手机独有")
        var host = a.runtime(); val phone = b.runtime()
        try {
            pair(host, phone, a, b); phone.requestSync(); completed(a, b)
            assertEquals(setOf("电脑独有", "手机独有"), a.names()); assertEquals(a.names(), b.names())
            var previous = a.store.baseline(b.id, a.id).revision
            host.requestSync(); completed(a, b, previous)
            assertEquals(2, a.names().size)
            previous = a.store.baseline(b.id, a.id).revision
            Files.delete(b.root.resolve("entities/character_cards/a.json"))
            host.requestSync(); completed(a, b, previous)
            assertEquals(setOf("手机独有"), a.names())
            host.closeAndDrain(); host = a.runtime(); host.startHost()
            until("new random host port") { host.state.value.addresses.isNotEmpty() }
            b.write("c", "重开电脑后新增")
            previous = a.store.baseline(b.id, a.id).revision
            phone.requestSync(); completed(a, b, previous)
            assertEquals(setOf("手机独有", "重开电脑后新增"), a.names())
            listOf(a.stateRoot, b.stateRoot, a.stateRoot.resolve("workspace"), b.stateRoot.resolve("workspace")).forEach { root ->
                val scratch = Files.newDirectoryStream(root).use { it.filter { path -> path.fileName.toString().startsWith("capture-") || path.fileName.toString().startsWith("received-") || path.fileName.toString().startsWith("transfer-") }.toList() }
                assertTrue(scratch.isEmpty(), "temporary data must be released: $scratch")
            }
        } finally { host.closeAndDrain(); phone.closeAndDrain() }
    }
    @Test fun hostBaselineCrashFinishesOldPendingThenActuallyExchangesNewEdit() = runBlocking {
        val network = SyntheticLan(); var crashed = false
        val a = End("computer", network) { phase, _ -> if (phase == "HOST_BASELINE" && !crashed) { crashed = true; throw java.io.IOException("synthetic dropped final receipt") } }
        val b = End("phone", network); a.write("a", "原卡")
        val host = a.runtime(); val phone = b.runtime()
        try {
            pair(host, phone, a, b); phone.requestSync()
            until("injected durable-baseline window") { crashed }
            b.write("b", "回执丢失后新增")
            completed(a, b)
            until("new edit really arrived after pending cleanup") { "回执丢失后新增" in a.names() }
            assertEquals(a.names(), b.names())
        } finally { host.closeAndDrain(); phone.closeAndDrain() }
    }
    @Test fun phoneAppliedDisconnectAndBothLaterEditsRebaseWithoutOldPlanLoop() = runBlocking {
        val network = SyntheticLan(); var interrupted = false
        val a = End("computer", network); a.write("a", "电脑原卡")
        val b = End("phone", network) { phase, _ -> if (phase == "PHONE_APPLIED" && !interrupted) {
            interrupted = true; a.write("a", "电脑之后修改"); throw java.io.IOException("synthetic disconnect after phone apply")
        } }
        val host = a.runtime(); val phone = b.runtime()
        try {
            pair(host, phone, a, b); phone.requestSync()
            until("phone applied") { interrupted }
            b.write("b", "手机之后新增")
            completed(a, b)
            assertEquals(setOf("电脑之后修改", "手机之后新增"), a.names())
            assertEquals(a.names(), b.names())
            val revisions = Files.newDirectoryStream(a.stateRoot, "superseded-*").use { it.count() }
            assertEquals(1, revisions)
        } finally { host.closeAndDrain(); phone.closeAndDrain() }
    }
}
