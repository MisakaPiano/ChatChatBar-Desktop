package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.data.local.entity.MomentTask
import com.example.chatbar.data.local.entity.MomentTaskStatus
import com.example.chatbar.data.local.entity.MomentImageVersion
import com.example.chatbar.data.local.entity.GeneratedImageMetadata
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.MomentRepository
import com.example.chatbar.data.repository.NovelAiStudioStateRepository
import com.example.chatbar.domain.image.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.net.*
import java.nio.file.*
import javax.net.ssl.SSLSocket
import kotlin.test.*

class LanSyncRuntimeTest {
    private class SyntheticLan : LanSyncNetwork {
        private val endpoints = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val connections = java.util.concurrent.atomic.AtomicInteger()
        override fun addresses() = listOf("192.168.1.2")
        override fun accepts(address: InetAddress) = address.isLoopbackAddress
        override val discoveryEnabled = false
        override fun listening(fingerprint: String, port: Int) { endpoints[fingerprint] = port }
        override fun discover(fingerprint: String) = endpoints[fingerprint]?.let { "192.168.1.2" to it }
        override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket {
            connections.incrementAndGet()
            val plain = Socket()
            try {
                plain.connect(InetSocketAddress("127.0.0.1", peer.port), 2000)
                val socket = identity.context { it == peer.fingerprint }.socketFactory.createSocket(plain, "127.0.0.1", peer.port, true) as SSLSocket
                LanSyncTls.configure(socket); socket.startHandshake(); return socket
            } catch (failure: Throwable) { plain.close(); throw failure }
        }
    }
    private class End(val name: String, val network: SyntheticLan,
        private val protection: (String) -> Unit = {},
        private val journal: (String, Int) -> Unit = { _, _ -> },
        private val progress: (String, Long) -> Unit = { _, _ -> },
        private val observe: (String, String) -> Unit = { _, _ -> }) {
        val root = Files.createTempDirectory("ccb-lan-$name-").toRealPath()
        val stateRoot = Files.createTempDirectory("ccb-lan-$name-state-").toRealPath()
        val store = LanSyncStateStore(stateRoot, { it }, { it })
        val id = store.identity().fingerprint
        val workspace = LanSyncWorkspace(root, stateRoot.resolve("workspace"), { raw -> Path.of(raw) },
            observeJournal = journal,
            recoveryVault = LanSyncRecoveryVault(stateRoot.resolve("workspace/recovery"), observe = protection))
        val gate = LanSyncOperationGate()
        val storage = JsonFileStorage(root, gate)
        val studio by lazy { NovelAiStudioStateRepository(storage, preserveForeignEditHistory = true) }
        val exclusiveCalls = java.util.concurrent.atomic.AtomicInteger()
        fun runtime(studioCurrent: Boolean = false) = LanSyncRuntime(store, workspace, object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) { exclusiveCalls.incrementAndGet(); gate.exclusive {
                if (studioCurrent) studio.prepareForLanSync()
                work()
            } }
            override suspend fun reload(sessionIds: Set<String>) {
                storage.loadAll("novelai_generation_history", com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry.serializer())
                if (studioCurrent) studio.reloadAfterLanSync()
            }
        }, name, observe = observe, network = network, transferProgress = progress)
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
        fun writeConversation(text: String, model: String) {
            val session = ChatSession("session", "card", "合成会话", modelId = model, createdAt = 1, updatedAt = 1)
            val message = ChatMessage("message", session.id, MessageRole.ASSISTANT, text,
                alternatives = listOf("旧版本", text), alternativeVersionIds = listOf("version-0", "version-1"),
                currentAlternativeIndex = 1, currentAlternativeVersionId = "version-1", createdAt = 1, updatedAt = 1, orderKey = 1_000_000)
            val sessions = root.resolve("entities/chat_sessions"); val messages = root.resolve("entities/chat_messages")
            Files.createDirectories(sessions); Files.createDirectories(messages)
            Files.writeString(sessions.resolve("session.json"), lanSyncJson.encodeToJsonElement(ChatSession.serializer(), session).toString())
            Files.writeString(messages.resolve("session_message.json"), lanSyncJson.encodeToJsonElement(ChatMessage.serializer(), message).toString())
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
    @Test fun neitherEndpointPublishesUntilBothRecoverySealsExistAndEitherProtectionFailureCanRetry() = runBlocking {
        for (failingSide in listOf("computer", "phone")) {
            val network = SyntheticLan()
            val refuse = java.util.concurrent.atomic.AtomicBoolean()
            val protectedComputer = java.util.concurrent.atomic.AtomicBoolean()
            val protectedPhone = java.util.concurrent.atomic.AtomicBoolean()
            val requireBoth = java.util.concurrent.atomic.AtomicBoolean()
            fun protection(side: String): (String) -> Unit = { phase ->
                if (phase == "BEFORE_SEAL" && failingSide == side && refuse.get()) error("SYNC_TEST_PROTECTION")
            }
            val journal: (String, Int) -> Unit = { phase, _ ->
                if (phase == "AUTHORIZED" && requireBoth.get()) {
                    check(protectedComputer.get() && protectedPhone.get()) { "Publication preceded bilateral recovery" }
                }
            }
            val a = End("computer", network, protection("computer"), journal) { phase, _ ->
                if (phase == "HOST_PROTECTED") protectedComputer.set(true)
            }
            val b = End("phone", network, protection("phone"), journal) { phase, _ ->
                if (phase == "PHONE_PROTECTED") protectedPhone.set(true)
            }
            a.write("a", "原甲"); a.write("b", "原乙")
            val host = a.runtime(); val phone = b.runtime()
            try {
                pair(host, phone, a, b); phone.requestSync(); completed(a, b)
                val common = a.store.baseline(b.id, a.id).revision
                a.write("a", "电脑修改甲"); b.write("b", "手机修改乙")
                protectedComputer.set(false); protectedPhone.set(false); requireBoth.set(true); refuse.set(true)
                host.requestSync()
                until("failed $failingSide protection is visible") {
                    (if (failingSide == "computer") host else phone).state.value.failureCode == "SYNC_TEST_PROTECTION"
                }
                assertEquals(setOf("电脑修改甲", "原乙"), a.names())
                assertEquals(setOf("原甲", "手机修改乙"), b.names())
                assertEquals(common, a.store.baseline(b.id, a.id).revision)
                assertEquals(common, b.store.baseline(a.id, b.id).revision)
                assertFalse(a.workspace.applied(requireNotNull(a.store.pending(b.id)).plan.transaction))
                assertFalse(b.workspace.applied(requireNotNull(b.store.pending(a.id)).plan.transaction))
                refuse.set(false); host.requestSync(); phone.requestSync(); completed(a, b, common)
                assertEquals(setOf("电脑修改甲", "手机修改乙"), a.names()); assertEquals(a.names(), b.names())
                assertTrue(protectedComputer.get() && protectedPhone.get())
                assertTrue(a.workspace.recovery.list().isNotEmpty()); assertTrue(b.workspace.recovery.list().isNotEmpty())
            } finally { host.closeAndDrain(); phone.closeAndDrain() }
        }
    }
    @Test fun actualWireExchangesOnlyMissingResourceChunksAndReusesBothEndpointsOwnCaptures() = runBlocking {
        val network = SyntheticLan()
        val sent = java.util.concurrent.atomic.AtomicLong()
        val progress: (String, Long) -> Unit = { phase, bytes -> if (phase == "RESOURCE_CHUNK_SENT_BYTES") sent.addAndGet(bytes) }
        val a = End("computer", network, progress = progress); val b = End("phone", network, progress = progress)
        val file = a.root.resolve("images/ordinary.bin"); Files.createDirectories(file.parent)
        val content = ByteArray(2 * 1024 * 1024 + 19) { (it % 251).toByte() }; Files.write(file, content)
        a.write("a", "附件卡", file.toString())
        val host = a.runtime(); val phone = b.runtime()
        try {
            pair(host, phone, a, b); phone.requestSync(); completed(a, b)
            assertEquals(content.size.toLong(), sent.get())
            var previous = a.store.baseline(b.id, a.id).revision
            sent.set(0); phone.requestSync(); completed(a, b, previous); assertEquals(0L, sent.get())
            previous = a.store.baseline(b.id, a.id).revision
            content[0] = 9; Files.write(file, content); sent.set(0)
            host.requestSync(); completed(a, b, previous); assertEquals(content.size.toLong(), sent.get())
            b.workspace.capture(b.id).use { captured ->
                assertEquals(lanSyncHash(content), captured.snapshot.resources.keys.single())
                assertContentEquals(content, Files.readAllBytes(captured.blobs.values.single()))
            }
        } finally { host.closeAndDrain(); phone.closeAndDrain() }
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
    @Test fun requestDuringEitherEndpointCompletionTailSurvivesTheOlderRound() = runBlocking {
        for (side in listOf("HOST", "PHONE")) {
            val network = SyntheticLan()
            val injected = java.util.concurrent.atomic.AtomicBoolean()
            lateinit var host: LanSyncRuntime
            lateinit var phone: LanSyncRuntime
            val a = End("computer", network) { phase, _ ->
                if (side == "HOST" && phase == "HOST_PENDING_CLEARED" && injected.compareAndSet(false, true)) host.requestSync()
            }
            val b = End("phone", network) { phase, _ ->
                if (side == "PHONE" && phase == "PHONE_PENDING_CLEARED" && injected.compareAndSet(false, true)) phone.requestSync()
            }
            a.write("card", "合成卡")
            host = a.runtime(); phone = b.runtime()
            try {
                pair(host, phone, a, b); phone.requestSync()
                until("new $side request must complete a second bilateral transaction") {
                    val left = a.store.baseline(b.id, a.id); val right = b.store.baseline(a.id, b.id)
                    injected.get() && left.receipts.size >= 2 && left.revision == right.revision &&
                        a.store.pending(b.id) == null && b.store.pending(a.id) == null
                }
                assertEquals(setOf("合成卡"), a.names()); assertEquals(a.names(), b.names())
            } finally { host.closeAndDrain(); phone.closeAndDrain() }
        }
    }
    @Test fun hostDataFailurePausesSnapshotsButItsExplicitRetryResumesTheRememberedPhone() = runBlocking {
        val network = SyntheticLan(); val a = End("computer", network); val b = End("phone", network)
        a.write("card", "原卡")
        val host = a.runtime(); val phone = b.runtime()
        try {
            pair(host, phone, a, b); phone.requestSync(); completed(a, b)
            val revision = a.store.baseline(b.id, a.id).revision
            val wrong = a.root.resolve("entities/character_cards.json")
            Files.writeString(wrong, "synthetic wrong storage shape must remain")
            host.requestSync()
            until("PC records its source refusal") { host.state.value.failureCode == "SYNC_STORAGE_SHAPE" }
            until("phone sees a paused owner without starting another snapshot") { phone.state.value.failureCode == "SYNC_REMOTE_PAUSED" }
            val hostCalls = a.exclusiveCalls.get(); val phoneCalls = b.exclusiveCalls.get()
            val previousConnections = network.connections.get()
            until("authenticated poll keeps observing the paused owner") { network.connections.get() >= previousConnections + 2 }
            assertEquals(hostCalls, a.exclusiveCalls.get()); assertEquals(phoneCalls, b.exclusiveCalls.get())
            assertEquals(revision, a.store.baseline(b.id, a.id).revision)
            assertEquals("synthetic wrong storage shape must remain", Files.readString(wrong))
            Files.delete(wrong); a.write("later", "修正后新增")
            // Only the failed owner needs an explicit retry; the peer has retained its poll.
            host.requestSync(); completed(a, b, revision)
            assertEquals(setOf("原卡", "修正后新增"), b.names())
            assertNull(host.state.value.failureCode); assertNull(phone.state.value.failureCode)
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
        val excluded = b.root.resolve("entities/editor_drafts/retained.json"); Files.createDirectories(excluded.parent)
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
            val phone = b.runtime()
            try {
                // Rejected unauthenticated capabilities must not pause subsequent trusted work.
                pair(host, phone, a, b); phone.requestSync(); completed(a, b)
                assertEquals(setOf("原资料"), b.names())
            } finally { phone.closeAndDrain() }
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
    @Test fun oneAppliedScopeExpansionRetainsLaterDeletionUntilBilateralAuthorityWithoutRestoringLocalFile() = runBlocking {
        val network = SyntheticLan()
        val interrupted = java.util.concurrent.atomic.AtomicBoolean()
        val expanded = java.util.concurrent.atomic.AtomicBoolean()
        val deferred = java.util.concurrent.atomic.AtomicBoolean()
        val oldTypes = setOf("character_cards")
        lateinit var a: End; lateinit var b: End
        a = End("computer", network) { phase, transaction ->
            if (phase == "HOST_BASELINE" && expanded.compareAndSet(false, true)) {
                assertTrue(deferred.get())
                assertEquals(transaction, b.store.baseline(a.id, b.id).revision)
                assertTrue(Files.exists(a.root.resolve("entities/format_cards/pc-format.json")))
                assertFalse(Files.exists(b.root.resolve("entities/format_cards/pc-format.json")))
            }
        }
        b = End("phone", network) { phase, _ -> if (phase == "PHONE_APPLIED") {
            if (interrupted.compareAndSet(false, true)) {
                assertEquals(oldTypes, a.store.baseline(b.id, a.id).coveredTypes)
                Files.delete(b.root.resolve("entities/format_cards/pc-format.json"))
                b.write("phone-later", "手机应用后新增")
                a.write("card", "电脑应用后修改")
                throw java.io.IOException("synthetic disconnect after single-side expanded apply and later edits")
            } else if (!expanded.get()) {
                assertFalse(Files.exists(b.root.resolve("entities/format_cards/pc-format.json")))
                assertTrue(Files.exists(a.root.resolve("entities/format_cards/pc-format.json")))
                assertEquals(oldTypes, a.store.baseline(b.id, a.id).coveredTypes)
                assertEquals(setOf("format_cards/pc-format"), b.store.pending(a.id)?.deferredDeletion?.recordKeys)
                deferred.set(true)
            }
        } }
        listOf(a, b).forEach { it.write("card", "共同原卡") }
        val old = a.workspace.capture(a.id).use { it.snapshot.copy(coveredTypes = oldTypes) }
        val ancestor = LanSyncPlan(java.util.UUID.randomUUID().toString(), old, emptyMap())
        a.store.complete(b.id, a.id, ancestor); b.store.complete(a.id, b.id, ancestor)
        a.writeFormat("pc-format"); b.writeFormat("phone-format")
        val host = a.runtime(); val phone = b.runtime()
        try {
            pair(host, phone, a, b); phone.requestSync()
            until("deferred local deletion propagates only after the first common expanded receipt") {
                expanded.get() && !Files.exists(a.root.resolve("entities/format_cards/pc-format.json")) &&
                    a.store.pending(b.id) == null && b.store.pending(a.id) == null &&
                    a.store.baseline(b.id, a.id).revision == b.store.baseline(a.id, b.id).revision
            }
            for (end in listOf(a, b)) {
                val cardNames = end.workspace.capture(end.id).use { capture -> capture.snapshot.records
                    .filter { it.type == "character_cards" }.mapNotNull { it.text("name") }.toSet() }
                assertEquals(setOf("电脑应用后修改", "手机应用后新增"), cardNames)
                assertFalse(Files.exists(end.root.resolve("entities/format_cards/pc-format.json")))
                assertTrue(Files.exists(end.root.resolve("entities/format_cards/phone-format.json")))
                assertTrue(ancestor.transaction in end.store.baseline(if (end === a) b.id else a.id, end.id).receipts)
            }
            val revision = a.store.baseline(b.id, a.id).revision
            host.requestSync(); completed(a, b, revision)
            assertEquals(a.names(), b.names())
            assertEquals(1, Files.newDirectoryStream(a.stateRoot, "superseded-*").use { it.count() })
        } finally { host.closeAndDrain(); phone.closeAndDrain() }
    }
    @Test fun oldSameProtocolCoveragePendingFinishesOrRebasesWithoutResetEvenWhenHashesDidNotChange() = runBlocking {
        for (bothApplied in listOf(false, true)) {
            val network = SyntheticLan(); val a = End("computer", network); val b = End("phone", network)
            listOf(a, b).forEach { it.write("card", "旧范围原卡") }
            val left = a.workspace.capture(a.id); val right = b.workspace.capture(b.id)
            val old = LanSyncPlan(java.util.UUID.randomUUID().toString(), left.snapshot.copy(coveredTypes = LanSyncScope.legacyChatTypes), emptyMap())
            try {
                b.workspace.apply(old, right.snapshot.hashes(), b.id, emptyMap())
                if (bothApplied) a.workspace.apply(old, left.snapshot.hashes(), a.id, emptyMap())
                a.store.pending(LanSyncPending(b.id, old, left.snapshot.hashes(), right.snapshot.hashes(), localApplied = bothApplied))
                b.store.pending(LanSyncPending(a.id, old, right.snapshot.hashes(), emptyMap(), localApplied = true))
                if (bothApplied) a.write("card", "双方应用后修改")
                val host = a.runtime(); val phone = b.runtime()
                try {
                    pair(host, phone, a, b); phone.requestSync(); completed(a, b, old.transaction)
                    assertEquals(LanSyncScope.schemas.keys, a.store.baseline(b.id, a.id).coveredTypes)
                    assertEquals(a.names(), b.names())
                    assertEquals(setOf(if (bothApplied) "双方应用后修改" else "旧范围原卡"), a.names())
                } finally { host.closeAndDrain(); phone.closeAndDrain() }
            } finally { left.close(); right.close() }
        }
    }

    @Test fun oldNineteenTypePendingExpandsToOneStudioChoiceAndRefreshesLoadedOwnersWithoutResetOrHistoryLoss() = runBlocking {
        for (bothApplied in listOf(false, true)) {
            val network = SyntheticLan(); val a = End("computer", network); val b = End("phone", network)
            listOf(a, b).forEach { it.write("card", "旧共同卡") }
            val oldTypes = LanSyncScope.schemas.keys - LanSyncStudioCurrent.types
            val left = a.workspace.capture(a.id); val right = b.workspace.capture(b.id)
            val old = LanSyncPlan(java.util.UUID.randomUUID().toString(), left.snapshot.copy(coveredTypes = oldTypes), emptyMap())
            try {
                b.workspace.apply(old, right.snapshot.hashes(), b.id, emptyMap())
                if (bothApplied) a.workspace.apply(old, left.snapshot.hashes(), a.id, emptyMap())
                a.store.pending(LanSyncPending(b.id, old, left.snapshot.hashes(), right.snapshot.hashes(), localApplied = bothApplied))
                b.store.pending(LanSyncPending(a.id, old, right.snapshot.hashes(), emptyMap(), localApplied = true))
                for (end in listOf(a, b)) {
                    end.studio.loadDraft()
                    val value = end.studio.updateDraft { it.copy(basePrompt = "${end.name}原文", negativePrompt = "固定负面原文", aiDesignModelId = "${end.name}-local-model") }
                    end.studio.saveUndoDraft(value.copy(basePrompt = "${end.name}旧撤销"))
                    end.studio.saveGuidanceCheckpoint(value.imageGuidance)
                    end.storage.saveSingleton(LanSyncStudioCurrent.HISTORY, NovelAiStudioHistoryJournal(
                        NovelAiStudioHistoryTimeline(listOf(value.toEditHistorySnapshot(end.storage::retainStudioPayload)))), NovelAiStudioHistoryJournal.serializer())
                }
                val host = a.runtime(studioCurrent = true); val phone = b.runtime(studioCurrent = true)
                try {
                    pair(host, phone, a, b); phone.requestSync()
                    until("expanded current editor requires exactly one joint choice") { host.state.value.conflicts.size == 1 }
                    val conflict = host.state.value.conflicts.single()
                    assertEquals(LanSyncStudioCurrent.UNIT, conflict.unit); assertFalse(conflict.canKeepBoth)
                    assertEquals(4, conflict.computerRecords); assertEquals(4, conflict.phoneRecords)
                    host.resolveConflicts(mapOf(conflict.unit to LanSyncChoice.KEEP_PHONE)); completed(a, b, old.transaction)
                    for (end in listOf(a, b)) {
                        assertEquals("phone原文", end.studio.draft.value?.basePrompt)
                        assertEquals("${end.name}-local-model", end.studio.draft.value?.aiDesignModelId)
                        assertEquals("phone旧撤销", end.studio.loadUndoDraft()?.basePrompt)
                        assertEquals(LanSyncScope.schemas.keys, end.store.baseline(if (end === a) b.id else a.id, end.id).coveredTypes)
                    }
                    val revision = a.store.baseline(b.id, a.id).revision
                    b.studio.stageDraft { it.copy(extraPrompt = "手机在已加载owner内继续编辑") }
                    phone.requestSync(); completed(a, b, revision)
                    assertEquals("手机在已加载owner内继续编辑", a.studio.draft.value?.extraPrompt)
                    for (end in listOf(a, b)) {
                        val history = requireNotNull(end.storage.loadSingleton(LanSyncStudioCurrent.HISTORY, NovelAiStudioHistoryJournal.serializer()))
                        assertEquals(2, history.committed.entries.size); assertNull(history.pending)
                    }
                    assertEquals(a.workspace.capture(a.id).use { it.snapshot.hashes() }, b.workspace.capture(b.id).use { it.snapshot.hashes() })
                } finally { host.closeAndDrain(); phone.closeAndDrain() }
            } finally { left.close(); right.close() }
        }
    }
    @Test fun momentsHistoryVersionsConflictsAndDormantPlansRoundTripWithoutChatOrExecutionAuthority() = runBlocking {
        val network = SyntheticLan(); val a = End("computer", network); val b = End("phone", network)
        val left = MomentRepository(JsonFileStorage(a.root, a.gate)); val right = MomentRepository(JsonFileStorage(b.root, b.gate))
        val bytes = LanSyncPreviewSample.image()
        val images = listOf("first.png", "selected.png").map { name ->
            a.root.resolve("images/$name").also { Files.createDirectories(it.parent); Files.write(it, bytes) }.toString()
        }
        fun metadata(path: String, prompt: String) = GeneratedImageMetadata(path, prompt, negativePrompt = "合成负面", sizePreset = "synthetic", width = 64, height = 64)
        val versions = images.mapIndexed { index, path -> MomentImageVersion("image-version-$index", path, "版本$index",
            metadata(path, "版本$index"), "简述$index", index.toLong()) }
        val post = MomentPost("pc-post", "historical-missing-card", "historical-missing-session", senderCharacterId = "missing-person",
            senderName = "合成人物", text = "保留原文 sessionId=historical-missing-session", imagePath = images.last(),
            imagePrompt = versions.last().imagePrompt, generatedImageMetadata = versions.last().generatedImageMetadata,
            imageBrief = versions.last().imageBrief, scheduledAt = 1, generatedAt = 1, desktopImageVersions = versions)
        left.savePost(post)
        left.saveTask(MomentTask("pc-completed", post.characterCardId, post.sessionId, 1, MomentTaskStatus.COMPLETED, post.id, createdAt = 1))
        left.saveTask(MomentTask("pc-pending", post.characterCardId, post.sessionId, Long.MAX_VALUE, createdAt = 1))
        val placeholder = post.copy(id = "phone-placeholder", imagePath = null, generatedImageMetadata = null,
            imagePrompt = "", imageBrief = "", desktopImageVersions = emptyList(), isPlaceholder = true, generationCheckpoint = "opaque synthetic sessionId=historical-missing-session")
        right.savePost(placeholder)
        val running = MomentTask("phone-running", post.characterCardId, post.sessionId, 1, MomentTaskStatus.RUNNING, placeholder.id, createdAt = 1)
        right.saveTask(running)
        right.saveTask(running.copy(id = "phone-pending", status = MomentTaskStatus.PENDING, postId = null, scheduledAt = Long.MAX_VALUE))
        val host = a.runtime(); val phone = b.runtime()
        try {
            pair(host, phone, a, b); phone.requestSync(); completed(a, b)
            assertFalse(left.getAllTasks().single { it.id == "pc-pending" }.requiresLocalScheduleApproval)
            assertTrue(right.getAllTasks().single { it.id == "pc-pending" }.requiresLocalScheduleApproval)
            assertFalse(right.getAllTasks().single { it.id == "phone-pending" }.requiresLocalScheduleApproval)
            assertTrue(left.getAllTasks().single { it.id == "phone-pending" }.requiresLocalScheduleApproval)
            assertEquals(MomentTaskStatus.RUNNING, left.getAllTasks().single { it.id == running.id }.status)
            assertEquals(placeholder.generationCheckpoint, right.getPost(placeholder.id)?.generationCheckpoint)
            val imported = requireNotNull(right.getPost(post.id))
            assertEquals(listOf("image-version-0", "image-version-1"), imported.desktopImageVersions.map { it.id })
            assertEquals(imported.desktopImageVersions.last().imagePath, imported.imagePath)
            assertEquals(2, imported.desktopImageVersions.map { it.imagePath }.distinct().size)
            imported.desktopImageVersions.forEach { assertContentEquals(bytes, Files.readAllBytes(Path.of(it.imagePath))) }
            var previous = a.store.baseline(b.id, a.id).revision
            val edited = right.getAllTasks().single { it.id == "pc-pending" }.copy(scheduledAt = Long.MAX_VALUE - 1)
            right.saveTask(edited); phone.requestSync(); completed(a, b, previous)
            assertTrue(left.getAllTasks().single { it.id == edited.id }.requiresLocalScheduleApproval)
            right.saveTask(edited.copy(requiresLocalScheduleApproval = false))
            previous = a.store.baseline(b.id, a.id).revision; phone.requestSync(); completed(a, b, previous)
            assertTrue(left.getAllTasks().single { it.id == edited.id }.requiresLocalScheduleApproval)
            assertFalse(right.getAllTasks().single { it.id == edited.id }.requiresLocalScheduleApproval)
            left.savePost(requireNotNull(left.getPost(post.id)).copy(text = "电脑分歧"))
            right.savePost(requireNotNull(right.getPost(post.id)).copy(text = "手机分歧"))
            previous = a.store.baseline(b.id, a.id).revision; host.requestSync()
            until("one linked Moment conflict separate from chat") { host.state.value.conflicts.size == 1 }
            assertEquals("moment-task/pc-completed", host.state.value.conflicts.single().unit)
            host.resolveConflicts(mapOf(host.state.value.conflicts.single().unit to LanSyncChoice.KEEP_BOTH)); completed(a, b, previous)
            for (repo in listOf(left, right)) {
                val tasks = repo.getAllTasks().filter { it.status == MomentTaskStatus.COMPLETED }
                assertEquals(2, tasks.size)
                assertEquals(setOf("电脑分歧", "手机分歧"), tasks.map { requireNotNull(repo.getPost(requireNotNull(it.postId))).text }.toSet())
                tasks.forEach { assertEquals(2, requireNotNull(repo.getPost(requireNotNull(it.postId))).desktopImageVersions.size) }
            }
            previous = a.store.baseline(b.id, a.id).revision; host.requestSync(); completed(a, b, previous)
            assertEquals(5, right.getAllTasks().size)
            right.deletePost(placeholder.id)
            previous = a.store.baseline(b.id, a.id).revision; phone.requestSync(); completed(a, b, previous)
            assertNull(left.getPost(placeholder.id))
            assertEquals(MomentTaskStatus.RUNNING, left.getAllTasks().single { it.id == running.id }.status)
            assertFalse(Files.exists(a.root.resolve("entities/chat_sessions")))
        } finally { host.closeAndDrain(); phone.closeAndDrain() }
    }
    @Test fun newDuplicateMomentLinkAndOtherSidePostEditCommitChosenComponentAndRepeatStably() = runBlocking {
        for (choice in listOf(LanSyncChoice.KEEP_PHONE, LanSyncChoice.KEEP_BOTH)) {
            val network = SyntheticLan(); val a = End("computer", network); val b = End("phone", network)
            val left = MomentRepository(JsonFileStorage(a.root, a.gate)); val right = MomentRepository(JsonFileStorage(b.root, b.gate))
            val post = MomentPost("p", "missing-card", "missing-session", senderName = "合成历史作者", text = "共同原文", scheduledAt = 1, generatedAt = 1)
            val task = MomentTask("t", post.characterCardId, post.sessionId, 1, MomentTaskStatus.COMPLETED, post.id, createdAt = 1)
            for (repo in listOf(left, right)) { repo.savePost(post); repo.saveTask(task) }
            val host = a.runtime(); val phone = b.runtime()
            try {
                pair(host, phone, a, b); phone.requestSync(); completed(a, b)
                val common = a.store.baseline(b.id, a.id)
                assertEquals(mapOf("moment-task/t" to setOf("moment_tasks/t", "moment_posts/p")), common.componentRecords)
                assertEquals(common.componentRecords, LanSyncStateStore(a.stateRoot, { it }, { it }).baseline(b.id, a.id).componentRecords)
                left.saveTask(task.copy(id = "a")); right.savePost(post.copy(text = "手机后续编辑"))
                host.requestSync(); until("duplicate link stays in the existing common review group") { host.state.value.conflicts.size == 1 }
                val conflict = host.state.value.conflicts.single()
                assertEquals("moment-task/t", conflict.unit)
                assertEquals(3, conflict.computerRecords); assertEquals(2, conflict.phoneRecords)
                host.resolveConflicts(mapOf(conflict.unit to choice)); completed(a, b, common.revision)
                val expectedTasks = if (choice == LanSyncChoice.KEEP_PHONE) 1 else 3
                val expectedPosts = if (choice == LanSyncChoice.KEEP_PHONE) 1 else 2
                for (end in listOf(a, b)) {
                    end.workspace.capture(end.id).use { capture ->
                        val rows = capture.snapshot.records
                        val posts = rows.filter { it.type == "moment_posts" }.associateBy { it.id }
                        val tasks = rows.filter { it.type == "moment_tasks" }
                        assertEquals(expectedTasks, tasks.size); assertEquals(expectedPosts, posts.size)
                        assertTrue(tasks.all { it.text("postId") in posts })
                        assertEquals(rows.size, rows.map { it.key }.distinct().size)
                        if (choice == LanSyncChoice.KEEP_PHONE) assertEquals("手机后续编辑", posts.values.single().text("text"))
                    }
                }
                val before = a.workspace.capture(a.id).use { it.snapshot.records.map { it.key }.toSet() }
                val revision = a.store.baseline(b.id, a.id).revision
                phone.requestSync(); completed(a, b, revision)
                assertEquals(before, a.workspace.capture(a.id).use { it.snapshot.records.map { it.key }.toSet() })
            } finally { host.closeAndDrain(); phone.closeAndDrain() }
        }
    }
    @Test fun studioHistoryActualTlsReverseEditKeepBothAndDeletionPreserveRecipeRolesImagesAndVibeResources() = runBlocking {
        val network = SyntheticLan(); val a = End("computer", network); val b = End("phone", network)
        val left = NovelAiStudioStateRepository(a.storage); val right = NovelAiStudioStateRepository(b.storage)
        val bytes = LanSyncPreviewSample.image()
        val path = a.root.resolve("images/history.png"); Files.createDirectories(path.parent); Files.write(path, bytes)
        val encoding = "S".repeat(NovelAiInlinePayloadStore.INLINE_LIMIT + 37)
        val reference = NovelAiInlinePayloadStore(a.root.toFile()).retain(encoding)
        val recipe = NovelAiGenerationRecipe(basePrompt = "保留的原始配方", negativePrompt = "合成负面",
            characters = listOf(NovelAiCharacterPromptDraft("scoped-role", "合成人物")),
            imageGuidance = NovelAiImageGuidanceDraft(baseImage = NovelAiStudioAssetRef("scoped-asset", path.toString(), lanSyncHash(bytes), 32, 32),
                vibes = listOf(NovelAiVibeReferenceDraft("scoped-vibe", encodedVibe = reference))))
        val original = NovelAiGenerationHistoryEntry("history", listOf(NovelAiGenerationHistoryImage(path.toString(), 42)), recipe, createdAt = 1)
        left.saveHistory(original)
        val host = a.runtime(); val phone = b.runtime()
        try {
            pair(host, phone, a, b); phone.requestSync(); completed(a, b)
            val imported = right.history.first().single() // actual post-commit cache publication
            assertEquals(recipe.basePrompt, imported.recipe.basePrompt)
            assertEquals(imported.images.single().path, imported.recipe.imageGuidance.baseImage?.path)
            assertContentEquals(bytes, Files.readAllBytes(Path.of(imported.images.single().path)))
            assertEquals(encoding, NovelAiInlinePayloadStore(b.root.toFile()).resolve(requireNotNull(imported.recipe.imageGuidance.vibes.single().encodedVibe)))
            var previous = a.store.baseline(b.id, a.id).revision
            right.saveHistory(imported.copy(recipe = imported.recipe.copy(basePrompt = "手机反向修改")))
            phone.requestSync(); completed(a, b, previous)
            assertEquals("手机反向修改", left.history.first().single().recipe.basePrompt)
            left.saveHistory(left.history.first().single().let { it.copy(recipe = it.recipe.copy(basePrompt = "电脑保留配方")) })
            right.saveHistory(right.history.first().single().let { it.copy(recipe = it.recipe.copy(basePrompt = "手机保留配方")) })
            previous = a.store.baseline(b.id, a.id).revision; host.requestSync()
            until("one complete Studio history batch conflict") { host.state.value.conflicts.size == 1 }
            val conflict = host.state.value.conflicts.single()
            assertEquals("novelai_generation_history/history", conflict.unit)
            host.resolveConflicts(mapOf(conflict.unit to LanSyncChoice.KEEP_BOTH)); completed(a, b, previous)
            for ((end, repo) in listOf(a to left, b to right)) {
                val entries = repo.history.first()
                assertEquals(2, entries.size)
                assertEquals(setOf("电脑保留配方", "手机保留配方"), entries.map { it.recipe.basePrompt }.toSet())
                entries.forEach { entry ->
                    assertEquals(listOf("scoped-role"), entry.recipe.characters.map { it.id })
                    assertEquals("scoped-asset", entry.recipe.imageGuidance.baseImage?.id)
                    assertEquals("scoped-vibe", entry.recipe.imageGuidance.vibes.single().id)
                    assertContentEquals(bytes, Files.readAllBytes(Path.of(entry.images.single().path)))
                    assertEquals(encoding, NovelAiInlinePayloadStore(end.root.toFile()).resolve(requireNotNull(entry.recipe.imageGuidance.vibes.single().encodedVibe)))
                }
            }
            val ids = right.history.first().map { it.id }.toSet()
            previous = a.store.baseline(b.id, a.id).revision; phone.requestSync(); completed(a, b, previous)
            assertEquals(ids, right.history.first().map { it.id }.toSet())
            val fork = ids.single { it != original.id }
            right.deleteHistory(fork)
            previous = a.store.baseline(b.id, a.id).revision; phone.requestSync(); completed(a, b, previous)
            assertEquals(listOf(original.id), left.history.first().map { it.id })
            assertContentEquals(bytes, Files.readAllBytes(Path.of(left.history.first().single().images.single().path)))
        } finally { host.closeAndDrain(); phone.closeAndDrain() }
    }
    @Test fun appliedLegacyProtocolsRebaseOrFinishWithoutReplayingOrDuplicatingConversationCopies() = runBlocking {
        for (legacyProtocol in listOf(1, 2, 3, 4, 5)) for (bothApplied in listOf(false, true)) {
            val network = SyntheticLan(); val a = End("computer", network); val b = End("phone", network)
            for (end in listOf(a, b)) end.write("card", "同一合成角色")
            a.writeConversation("电脑版本", "pc-local-model"); b.writeConversation("手机版本", "phone-local-model")
            val left = a.workspace.capture(a.id); val right = b.workspace.capture(b.id)
            val oldTransaction = java.util.UUID.randomUUID().toString()
            val selected = LanSyncPlanner.plan(left.snapshot, right.snapshot, LanSyncBaseline(b.id, ownerId = a.id),
                mapOf("conversation/session" to LanSyncChoice.KEEP_BOTH), oldTransaction)
            val fork = selected.parents.keys.single()
            try {
                // The disk journal's exact entity payload is transport-version independent. Materialize
                // that compatible payload, then retain the earlier envelope as the interrupted plan.
                b.workspace.apply(selected, right.snapshot.hashes(), b.id, emptyMap())
                if (bothApplied) a.workspace.apply(selected, left.snapshot.hashes(), a.id, emptyMap())
                val legacy = selected.copy(snapshot = selected.snapshot.copy(protocol = legacyProtocol, coveredTypes = LanSyncScope.legacyChatTypes))
                a.store.pending(LanSyncPending(b.id, legacy, left.snapshot.hashes(), right.snapshot.hashes(), localApplied = bothApplied))
                b.store.pending(LanSyncPending(a.id, legacy, right.snapshot.hashes(), emptyMap(), localApplied = true))
                if (bothApplied) a.writeConversation("电脑应用后编辑", "pc-local-model")
                val before = Files.walk(b.root.resolve("entities")).use { paths -> paths.filter { Files.isRegularFile(it) }.toList().associateWith {
                    Files.readAllBytes(it) to Files.readAttributes(it, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
                } }
                val host = a.runtime(); val phone = b.runtime()
                try {
                    pair(host, phone, a, b); phone.requestSync(); completed(a, b, oldTransaction)
                    assertNotEquals(oldTransaction, a.store.baseline(b.id, a.id).revision)
                    assertEquals(a.workspace.capture(a.id).use { it.snapshot.hashes() }, b.workspace.capture(b.id).use { it.snapshot.hashes() })
                    for (end in listOf(a, b)) {
                        val sessions = Files.newDirectoryStream(end.root.resolve("entities/chat_sessions")).use { it.toList() }
                        assertEquals(setOf("session.json", "$fork.json"), sessions.map { it.fileName.toString() }.toSet())
                        val copied = lanSyncJson.decodeFromString(ChatSession.serializer(), Files.readString(end.root.resolve("entities/chat_sessions/$fork.json")))
                        assertEquals(oldTransaction, copied.lanSyncOrigin?.transaction)
                        assertEquals("session", copied.lanSyncOrigin?.parentSessionId)
                        if (!bothApplied) {
                            val old = lanSyncJson.decodeFromString<LanSyncPending>(Files.readString(end.stateRoot.resolve("superseded-$oldTransaction.json")))
                            assertEquals(legacyProtocol, old.plan.snapshot.protocol); assertEquals(selected.parents, old.plan.parents)
                        }
                    }
                    before.forEach { (path, proof) ->
                        if (!bothApplied || path != b.root.resolve("entities/chat_messages/session_message.json")) {
                            assertContentEquals(proof.first, Files.readAllBytes(path))
                            assertEquals(proof.second, Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
                        }
                    }
                    if (bothApplied) assertEquals("电脑应用后编辑", lanSyncJson.decodeFromString(ChatMessage.serializer(),
                        Files.readString(b.root.resolve("entities/chat_messages/session_message.json"))).content)
                    assertEquals("session", lanSyncJson.parseToJsonElement(Files.readString(b.stateRoot.resolve("receipt-${a.id}.json"))).jsonObject[fork]?.jsonPrimitive?.content)
                } finally { host.closeAndDrain(); phone.closeAndDrain() }
            } finally { left.close(); right.close() }
        }
    }
}
