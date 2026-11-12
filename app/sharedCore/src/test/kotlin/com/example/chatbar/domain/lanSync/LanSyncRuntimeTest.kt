package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.FormatCard
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
        fun write(id: String, name: String, image: String? = null) {
            writeCard(CharacterCard(id, name, avatar = image, createdAt = 1, updatedAt = 1))
        }
        fun writeCard(card: CharacterCard) {
            val id = card.id
            val path = root.resolve("entities/character_cards/$id.json"); Files.createDirectories(path.parent)
            Files.writeString(path, lanSyncJson.encodeToJsonElement(CharacterCard.serializer(), card).toString())
        }
        fun readCard(id: String) = lanSyncJson.decodeFromString(CharacterCard.serializer(), Files.readString(root.resolve("entities/character_cards/$id.json")))
        fun writeFormat(id: String) {
            val path = root.resolve("entities/format_cards/$id.json"); Files.createDirectories(path.parent)
            Files.writeString(path, lanSyncJson.encodeToJsonElement(FormatCard.serializer(), FormatCard(id, id, "合成格式", createdAt = 1)).toString())
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
            val roots = listOf(a.stateRoot, b.stateRoot, a.stateRoot.resolve("workspace"), b.stateRoot.resolve("workspace"))
            fun scratchFiles(root: Path) = Files.newDirectoryStream(root).use { it.filter { path ->
                path.fileName.toString().startsWith("capture-") || path.fileName.toString().startsWith("received-") || path.fileName.toString().startsWith("transfer-")
            }.toList() }
            // Bilateral data receipts precede each endpoint's finally tail. Wait for that owned
            // tail, bounded by the same runtime-test deadline; a genuine retained file still fails.
            until("invocation transfer cleanup must finish") { roots.all { scratchFiles(it).isEmpty() } }
            roots.forEach { root ->
                val scratch = scratchFiles(root)
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
        val a = End("computer", network)
        val image = a.root.resolve("images/synthetic.bin")
        Files.createDirectories(image.parent)
        val bytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        Files.write(image, bytes)
        a.write("a", "电脑原卡", image.toString())
        val b = End("phone", network) { phase, _ -> if (phase == "PHONE_APPLIED" && !interrupted) {
            interrupted = true; a.write("a", "电脑之后修改", image.toString()); throw java.io.IOException("synthetic disconnect after phone apply")
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
            for (end in listOf(a, b)) {
                val transfers = Files.newDirectoryStream(end.stateRoot, "transfer-*").use { it.toList() }
                assertTrue(transfers.isEmpty(), "superseded transfer bytes must be released: $transfers")
                end.workspace.capture(end.id).use { capture ->
                    assertTrue(capture.snapshot.resources.keys.contains(lanSyncHash(bytes)))
                    assertContentEquals(bytes, Files.readAllBytes(capture.blobs.getValue(lanSyncHash(bytes))))
                }
                assertTrue(Files.newDirectoryStream(end.stateRoot, "superseded-*").use { it.iterator().hasNext() }, "superseded identity evidence remains")
            }
        } finally { host.closeAndDrain(); phone.closeAndDrain() }
    }
    @Test fun initialPresetProjectionPersistsThroughReopenEditsConflictAndRepeatWithoutLocalIdChanges() = runBlocking {
        val network = SyntheticLan(); val a = End("computer", network); val b = End("phone", network)
        a.writeCard(CharacterCard("pc-card", "同源卡", characters = listOf(CharacterInfo("pc-person", "人物")),
            sourcePresetKey = "synthetic/preset", sourcePresetVersion = 1, createdAt = 1, updatedAt = 1))
        b.writeCard(a.readCard("pc-card").copy(id = "phone-card", characters = listOf(CharacterInfo("phone-person", "人物")), createdAt = 99, updatedAt = 99))
        val excluded = b.root.resolve("entities/moment_posts/retained.json"); Files.createDirectories(excluded.parent)
        val excludedBytes = "synthetic excluded phone-card phone-person".toByteArray(); Files.write(excluded, excludedBytes)
        var host = a.runtime(); var phone = b.runtime()
        try {
            pair(host, phone, a, b); phone.requestSync(); completed(a, b)
            val mapping = b.store.projection(a.id, b.id); assertEquals(2, mapping.bindings.size)
            assertEquals(setOf("同源卡"), a.names()); assertEquals(a.names(), b.names())
            assertFalse(Files.exists(b.root.resolve("entities/character_cards/pc-card.json")))
            var previous = a.store.baseline(b.id, a.id).revision
            host.closeAndDrain(); phone.closeAndDrain(); host = a.runtime(); phone = b.runtime()
            host.startHost(); phone.startPhone()
            b.writeCard(b.readCard("phone-card").copy(name = "手机重开后编辑"))
            phone.requestSync(); completed(a, b, previous)
            assertEquals("手机重开后编辑", a.readCard("pc-card").name)
            previous = a.store.baseline(b.id, a.id).revision
            a.writeCard(a.readCard("pc-card").copy(name = "电脑分歧")); b.writeCard(b.readCard("phone-card").copy(name = "手机分歧"))
            host.requestSync(); until("projected conflict") { host.state.value.conflicts.size == 1 }
            delay(16_000) // Human review must outlive the ordinary15s socket read timeout without reconnecting.
            assertEquals(1, host.state.value.conflicts.size)
            assertTrue(phone.state.value.busy)
            host.resolveConflicts(mapOf(host.state.value.conflicts.single().unit to LanSyncChoice.KEEP_PHONE))
            completed(a, b, previous); assertEquals("手机分歧", a.readCard("pc-card").name)
            previous = a.store.baseline(b.id, a.id).revision; phone.requestSync(); completed(a, b, previous)
            assertEquals(mapping, b.store.projection(a.id, b.id)); assertEquals(1, a.names().size); assertEquals(1, b.names().size)
            previous = a.store.baseline(b.id, a.id).revision
            a.writeCard(a.readCard("pc-card").copy(name = "电脑两份")); b.writeCard(b.readCard("phone-card").copy(name = "手机两份"))
            host.requestSync(); until("projected keep both conflict") { host.state.value.conflicts.size == 1 }
            host.resolveConflicts(mapOf(host.state.value.conflicts.single().unit to LanSyncChoice.KEEP_BOTH))
            completed(a, b, previous)
            assertEquals(setOf("电脑两份", "手机两份"), a.names()); assertEquals(a.names(), b.names())
            previous = a.store.baseline(b.id, a.id).revision; phone.requestSync(); completed(a, b, previous)
            assertEquals(2, a.names().size); assertEquals(2, b.names().size)
            assertEquals("phone-person", b.readCard("phone-card").characters.single().id)
            assertContentEquals(excludedBytes, Files.readAllBytes(excluded))
        } finally { host.closeAndDrain(); phone.closeAndDrain() }
    }
    @Test fun lostAcknowledgementAfterProjectionPersistenceResumesWithoutAnotherAliasOrCopy() = runBlocking {
        val network = SyntheticLan(); val a = End("computer", network); var interrupted = false
        val b = End("phone", network) { phase, _ -> if (phase == "PHONE_PROJECTION" && !interrupted) {
            interrupted = true; throw java.io.IOException("synthetic lost projection acknowledgement")
        } }
        a.writeCard(CharacterCard("pc", "同源卡", sourcePresetKey = "synthetic/preset", sourcePresetVersion = 1, createdAt = 1, updatedAt = 1))
        b.writeCard(a.readCard("pc").copy(id = "phone", createdAt = 2, updatedAt = 2))
        val host = a.runtime(); val phone = b.runtime()
        try {
            pair(host, phone, a, b); phone.requestSync(); completed(a, b)
            assertTrue(interrupted)
            assertEquals(listOf(LanSyncIdBinding("character_cards", "phone", "pc")), b.store.projection(a.id, b.id).bindings)
            assertEquals(setOf("同源卡"), a.names()); assertEquals(a.names(), b.names())
            assertFalse(Files.exists(b.root.resolve("entities/character_cards/pc.json")))
            assertEquals("phone", b.readCard("phone").id)
        } finally { host.closeAndDrain(); phone.closeAndDrain() }
    }
    @Test fun earlierProtocolIsRefusedBeforeTrustProjectionOrDataMutation() = runBlocking {
        val network = SyntheticLan(); val a = End("computer", network); val b = End("phone", network)
        a.write("retained", "原资料")
        val host = a.runtime()
        try {
            host.startHost(); until("host listening") { host.state.value.addresses.isNotEmpty() }
            host.pairOffer("192.168.1.2"); val offer = requireNotNull(host.state.value.offer)
            val error = assertFailsWith<IllegalArgumentException> { LanSyncPairOffer.parse("CCB-LAN1:legacy") }
            assertEquals("SYNC_PROTOCOL", error.message)
            network.connect(b.store.identity(), LanSyncPeer(a.id, "synthetic", offer.host, offer.port)).use { socket ->
                LanSyncWire(socket).use { wire ->
                    wire.sendText("""{"command":"PAIR","name":"legacy"}""")
                    assertEquals("SYNC_PROTOCOL", wire.text())
                }
            }
            network.connect(b.store.identity(), LanSyncPeer(a.id, "synthetic", offer.host, offer.port)).use { socket ->
                LanSyncWire(socket).use { wire ->
                    wire.sendText("""{"protocol":$LAN_SYNC_PROTOCOL,"command":"PAIR","name":"smaller-scope","coveredTypes":["character_cards"]}""")
                    assertEquals("SYNC_SCOPE_MISMATCH", wire.text())
                }
            }
            assertTrue(a.store.peers().isEmpty()); assertTrue(b.store.peers().isEmpty())
            assertEquals(setOf("原资料"), a.names())
            assertFalse(Files.newDirectoryStream(a.stateRoot, "projection-*").use { it.iterator().hasNext() })
        } finally { host.closeAndDrain() }
    }
    @Test fun existingCommonScopeExpandsWithoutResetAndInterruptedExpansionCannotDeleteNewTypesEarly() = runBlocking {
        val network = SyntheticLan(); val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
        val common = java.util.concurrent.atomic.AtomicBoolean(false)
        val oldTypes = setOf("character_cards")
        lateinit var a: End; lateinit var b: End
        a = End("computer", network) { phase, transaction -> if (phase == "HOST_BASELINE" && common.compareAndSet(false, true)) {
            assertEquals(transaction, b.store.baseline(a.id, b.id).revision)
            assertTrue(Files.exists(a.root.resolve("entities/format_cards/pc-format.json")))
        } }
        b = End("phone", network) { phase, _ -> if (phase == "PHONE_BASELINE" && interrupted.compareAndSet(false, true)) {
            assertEquals(oldTypes, a.store.baseline(b.id, a.id).coveredTypes)
            assertEquals(LanSyncScope.schemas.keys, b.store.baseline(a.id, b.id).coveredTypes)
            assertTrue(Files.exists(a.root.resolve("entities/format_cards/pc-format.json")))
            Files.delete(b.root.resolve("entities/format_cards/pc-format.json"))
            throw java.io.IOException("synthetic lost expanded-scope receipt after a later user deletion")
        } }
        for (end in listOf(a, b)) { end.write("keep", "旧类保留"); end.write("deleted", "旧类删除") }
        val old = a.workspace.capture(a.id).use { it.snapshot.copy(coveredTypes = oldTypes) }
        val previous = LanSyncPlan(java.util.UUID.randomUUID().toString(), old, emptyMap())
        a.store.complete(b.id, a.id, previous); b.store.complete(a.id, b.id, previous)
        Files.delete(a.root.resolve("entities/character_cards/deleted.json"))
        a.writeFormat("pc-format"); b.writeFormat("phone-format")
        val host = a.runtime(); val phone = b.runtime()
        try {
            pair(host, phone, a, b); phone.requestSync(); completed(a, b, previous.transaction)
            until("new type deletion only after shared expanded receipt") {
                common.get() && !Files.exists(a.root.resolve("entities/format_cards/pc-format.json")) &&
                    a.store.pending(b.id) == null && b.store.pending(a.id) == null &&
                    a.store.baseline(b.id, a.id).revision == b.store.baseline(a.id, b.id).revision
            }
            val expandedRevision = a.store.baseline(b.id, a.id).revision
            host.requestSync(); completed(a, b, expandedRevision)
            assertTrue(interrupted.get())
            for (end in listOf(a, b)) {
                assertFalse(Files.exists(end.root.resolve("entities/character_cards/deleted.json")))
                assertTrue(Files.exists(end.root.resolve("entities/format_cards/phone-format.json")))
            }
            assertTrue(previous.transaction in a.store.baseline(b.id, a.id).receipts)
            assertEquals(LanSyncScope.schemas.keys, a.store.baseline(b.id, a.id).coveredTypes)
        } finally { host.closeAndDrain(); phone.closeAndDrain() }
    }
}
