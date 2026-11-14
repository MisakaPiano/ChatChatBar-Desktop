package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.WebDavEndpointPolicy
import java.net.BindException
import java.net.InetAddress
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.test.*

/** Actual domain/file/crypto owners over fixed rclone HTTP; protection and platform are synthetic. */
class RealWebDavRuntimeChatRecoveryTest {
    @Test fun runtimeUploadThenOfflineOwnerRecoveryPreservesLatestConversationAndColdIdentity() {
        org.junit.Assume.assumeTrue("requires explicit isolated real-server job",
            System.getenv("CCB_REAL_DAV_PROOF") == "true")
        require(System.getenv("GITHUB_ACTIONS") == "true" && System.getenv("RUNNER_OS") == "Linux" &&
            System.getenv("GITHUB_REPOSITORY") == "MisakaPiano/ChatChatBar-Desktop")
        var phase = "fixed-environment"
        try { runBlocking {
            val endpoint = requireNotNull(System.getenv("CCB_REAL_DAV_URL")).toHttpUrl()
            require(endpoint.scheme == "http" && endpoint.host == "127.0.0.1" && endpoint.port == 18765 &&
                endpoint.encodedPath == "/" && endpoint.username.isEmpty() && endpoint.password.isEmpty() &&
                endpoint.query == null && endpoint.fragment == null)
            val user = requireNotNull(System.getenv("CCB_REAL_DAV_USER"))
            val pass = requireNotNull(System.getenv("CCB_REAL_DAV_PASS"))
            require(user == "synthetic-ccb-ci" && pass.matches(Regex("[0-9a-f]{48}")))
            val runner = Path.of(requireNotNull(System.getenv("RUNNER_TEMP"))).toRealPath()
            val methodEvidence = Path.of(requireNotNull(System.getenv("CCB_REAL_DAV_METHOD_EVIDENCE")))
                .toAbsolutePath().normalize()
            require(methodEvidence.fileName.toString() == "real-webdav-method-counts.json" &&
                methodEvidence.startsWith(runner) && methodEvidence.parent.toRealPath() == methodEvidence.parent &&
                !Files.exists(methodEvidence, NOFOLLOW_LINKS))
            val root = Files.createTempDirectory(runner, "ccb-rclone-recovery-").toRealPath()
            require(!methodEvidence.startsWith(root))
            val senderMethods = ConcurrentHashMap<String, AtomicInteger>()
            val recoveryMethods = ConcurrentHashMap<String, AtomicInteger>()
            val paths = ConcurrentHashMap<String, AtomicInteger>()
            val clients = mutableListOf<OkHttpClient>()
            fun client(methods: ConcurrentHashMap<String, AtomicInteger>) = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY).connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS).addInterceptor { chain ->
                    val request = chain.request()
                    check(request.header("Authorization") == Credentials.basic(user, pass, Charsets.UTF_8)) {
                        "SYNTHETIC_BASIC_HEADER_REQUIRED"
                    }
                    val method = request.method; val path = request.url.encodedPath
                    require(method.matches(Regex("[A-Z]{1,16}")) && path.length <= 1024 &&
                        path.matches(Regex("/[a-z0-9/._-]*")))
                    methods.computeIfAbsent(method) { AtomicInteger() }.incrementAndGet()
                    paths.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                    chain.proceed(request)
                }.build().also(clients::add)
            fun closeClient(value: OkHttpClient) {
                value.dispatcher.executorService.shutdownNow(); value.connectionPool.evictAll()
            }
            fun directory(name: String) = Files.createDirectory(root.resolve(name))
            val senderRoot = directory("sender")
            val sender = LanSyncGroupAdministrationTest.Fixture(senderRoot)
            var senderClosed = false
            var coordinator: LanSyncGroupCoordinator? = null
            var recovery: LanSyncGroupCloudRecoveryOwner? = null
            try {
                phase = "sender-real-enrollment-and-durable-card-document"
                sender.seed()
                val oldAccess = requireNotNull(sender.service.runtimeAccess())
                val oldTrust = oldAccess.trust
                val oldDevice = oldAccess.device
                val oldLedger = oldAccess.ledger().current()
                val recoveryCode = sender.code
                val documentBytes = ByteArray(131_113) { ((it * 17 + 11) % 251).toByte() }
                val documentHash = sha(documentBytes)
                val originalDocument = sender.data.resolve("documents/original.bin")
                Files.createDirectories(originalDocument.parent)
                Files.write(originalDocument, documentBytes, CREATE_NEW, WRITE)
                val originalCard = CharacterCard("original", "synthetic real-server recovered card",
                    basicSetting = "synthetic original domain body; no edited Prompt",
                    customDocuments = listOf(DocumentInfo("original-document", "original.bin",
                        originalDocument.toString(), "application/octet-stream", addedAt = 1)),
                    createdAt = 1, updatedAt = 1)
                val storage = JsonFileStorage(sender.data)
                storage.saveEntity("character_cards", originalCard.id, originalCard, CharacterCard.serializer())
                val originalCardFile = sender.data.resolve("entities/character_cards/original.json")
                val originalCardBytes = Files.readAllBytes(originalCardFile)
                val chats = ChatRepository(storage)
                chats.createSession(ChatSession(SESSION, originalCard.id, "synthetic latest recoverable conversation",
                    nextSourceTurnOrder = 2, nextTimelineTurn = 2, createdAt = 1, updatedAt = 4))
                val proposedMessages = listOf(
                    ChatMessage("u1", SESSION, MessageRole.USER, "synthetic first user turn",
                        currentAlternativeVersionId = "u1-v0", sourceTurnId = "turn-0", sourceTurnOrder = 0,
                        timelineTurn = 0, createdAt = 1, updatedAt = 1, orderKey = 1_000_000),
                    ChatMessage("a1", SESSION, MessageRole.ASSISTANT, "synthetic first active reply",
                        alternatives = listOf("synthetic first discarded reply", "synthetic first active reply"),
                        alternativeVersionIds = listOf("a1-v0", "a1-v1"), currentAlternativeIndex = 1,
                        currentAlternativeVersionId = "a1-v1", generatedFromMessageId = "u1",
                        sourceTurnId = "turn-0", sourceTurnOrder = 0, timelineTurn = 0,
                        createdAt = 2, updatedAt = 2, orderKey = 2_000_000),
                    ChatMessage("u2", SESSION, MessageRole.USER, "synthetic final user question before leaving",
                        currentAlternativeVersionId = "u2-v0", sourceTurnId = "turn-1", sourceTurnOrder = 1,
                        timelineTurn = 1, createdAt = 3, updatedAt = 3, orderKey = 3_000_000),
                    ChatMessage("a2", SESSION, MessageRole.ASSISTANT, LAST_MESSAGE,
                        alternatives = listOf("synthetic last discarded candidate", LAST_MESSAGE),
                        alternativeVersionIds = listOf("a2-v0", "a2-v1"), currentAlternativeIndex = 1,
                        currentAlternativeVersionId = "a2-v1", generatedFromMessageId = "u2",
                        sourceTurnId = "turn-1", sourceTurnOrder = 1, timelineTurn = 1,
                        createdAt = 4, updatedAt = 4, orderKey = 4_000_000))
                proposedMessages.forEach { chats.addMessage(it) }
                // Compare the actual repository-persisted graph, including its version policy.
                val expectedSession = requireNotNull(chats.getSession(SESSION))
                val expectedMessages = chats.getMessagesReadOnly(SESSION)
                assertConversation(sender.data, expectedSession, expectedMessages)
                val senderClient = client(senderMethods)
                val catalog = LanSyncGroupCloudRoutes(Files.createDirectory(senderRoot.resolve("synthetic-cloud-catalog")), oldTrust, oldAccess.device,
                    { it.reversedArray() }, { it.reversedArray() }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
                val password = pass.toByteArray(Charsets.UTF_8)
                val route = try { catalog.add(LanSyncGroupCloudDraft("synthetic fixed rclone", endpoint.toString(),
                    user, password, enabled = true)) } finally { password.fill(0) }
                val noNearby = object : LanSyncNetwork {
                    override fun addresses() = listOf("127.0.0.1")
                    override fun accepts(address: InetAddress) = false
                    override val discoveryEnabled = false
                    override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket =
                        error("SYNTHETIC_OLD_DEVICE_HAS_NO_NEARBY_PEER")
                    override fun listening(fingerprint: String, port: Int) {
                        throw BindException("SYNTHETIC_NEARBY_DISABLED_FOR_CLOUD_PROOF")
                    }
                }
                phase = "ordinary-coordinator-auto-source-and-cloud-runtime-upload"
                val live = LanSyncGroupCoordinator({ Files.exists(sender.profile.resolve("profile.protected")) },
                    { sender.service }, { error("SYNTHETIC_FOUNDER_MUST_NOT_JOIN") }, noNearby, "synthetic old PC",
                    cloudRuntimeFactory = { current, allowed, wake, report ->
                        LanSyncGroupCloudRuntime(current, allowed, wake, report, catalog, senderClient,
                            WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
                    }, sourceChanges = storage.committedRevisionFor(LanSyncScope.schemas.keys))
                coordinator = live
                live.open()
                withTimeout(120_000) {
                    while (live.state.value.busy || live.state.value.local.lastSuccessAt == null ||
                        live.cloudSettings.value.progress[route]?.selectionObserved != true) delay(25)
                }
                assertEquals(LanSyncGroupPanelStage.READY, live.state.value.stage)
                assertNull(live.state.value.error)
                val observedAt = requireNotNull(live.cloudSettings.value.progress.getValue(route).lastObservedAt)
                live.pauseAndDrain()
                val sourceOperation = oldAccess.node.sourceOperations().single()
                val sourceAuthor = SyncAuthorJournal(sender.profile.resolve("node/author"), oldTrust,
                    oldAccess.certificate, { it.reversedArray() }, { it.reversedArray() })
                val sourceEntry = requireNotNull(sourceAuthor.entry(sourceOperation))
                val sourceReference = sourceEntry.version.reference()
                val sourceHash = sourceEntry.version.changes.getValue(UNIT).valueHash
                val conversationHash = sourceEntry.version.changes.getValue(CONVERSATION).valueHash
                assertEquals(setOf(UNIT, CONVERSATION), sourceEntry.version.changes.keys)
                assertEquals(1L, sourceAuthor.state().committedSequence)
                assertNotNull(oldAccess.node.receipt(sourceOperation, oldAccess.localTarget))
                val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, route)
                val publication = oldAccess.targetPublication(oldAccess.work.resolve("cloud-runtime/${route.value}/target"),
                    target, prepare = false)
                val cloudReceipt = requireNotNull(publication.cloudReceipt())
                assertEquals(target, cloudReceipt.target); assertEquals(oldLedger.reference(), cloudReceipt.checkpoint)
                assertTrue(cloudReceipt.objects.any { it.objectRequest.role == LanSyncGroupTargetObjectRole.VERSION &&
                    it.objectRequest.slot == sourceReference.id.value })
                assertTrue(cloudReceipt.objects.any { it.objectRequest.role == LanSyncGroupTargetObjectRole.RESOURCE })
                assertTrue(cloudReceipt.objects.all { it.evidence.readback is LanSyncGroupCloudTargetObservation.Chunks })
                assertEquals(cloudReceipt.objects.maxOf { it.evidence.readback.observedAtMillis }, observedAt)
                assertContentEquals(originalCardBytes, Files.readAllBytes(originalCardFile))
                assertContentEquals(documentBytes, Files.readAllBytes(originalDocument))

                phase = "all-old-owners-closed-and-original-profile-unavailable"
                live.closeAndDrain(); coordinator = null
                sender.close(); senderClosed = true
                closeClient(senderClient)
                val oldFiles = hashes(senderRoot)
                val offlineSender = Files.move(senderRoot, root.resolve("offline-old-device"))
                assertFalse(Files.exists(senderRoot, NOFOLLOW_LINKS))
                assertEquals(oldFiles, hashes(offlineSender))

                phase = "fresh-device-recovery-owner-prepare-review-confirm"
                val fresh = directory("fresh-device")
                val profile = Files.createDirectory(fresh.resolve("profile"))
                val data = Files.createDirectory(fresh.resolve("data"))
                val workspaceRoot = Files.createDirectory(fresh.resolve("workspace"))
                val identityFile = fresh.resolve("daily-identity.protected")
                val createdIdentity = LanSyncTlsIdentity.create().encode()
                try { Files.write(identityFile, createdIdentity.reversedArray(), CREATE_NEW, WRITE) }
                finally { createdIdentity.fill(0) }
                fun identity(): LanSyncTlsIdentity {
                    val bytes = Files.readAllBytes(identityFile).reversedArray()
                    return try { LanSyncTlsIdentity.decode(bytes) } finally { bytes.fill(0) }
                }
                fun joining() = LanSyncGroupJoining(profile,
                    LanSyncWorkspace(data, workspaceRoot, { Path.of(it) }), platform(), identity(),
                    { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> })
                val ready = CompletableDeferred<LanSyncGroupRuntimeAccess>()
                val callbacks = AtomicInteger()
                val receiverClient = client(recoveryMethods)
                val owner = LanSyncGroupCloudRecoveryOwner(
                    root = { fresh.resolve("cloud-recovery").also { Files.createDirectories(it) } },
                    joining = ::joining, deviceName = "synthetic fresh recovery PC",
                    onReady = { current ->
                        // The callback never applies data or runs a Coordinator cycle.
                        assertRestored(data, documentHash, documentBytes)
                        assertConversation(data, expectedSession, expectedMessages)
                        callbacks.incrementAndGet(); ready.complete(current)
                    }, client = receiverClient, endpointPolicy = WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
                recovery = owner
                owner.open()
                val borrowed = pass.toByteArray(Charsets.UTF_8)
                try { owner.prepare(LanSyncGroupCloudDraft("synthetic restore only", endpoint.toString(), user,
                    borrowed, enabled = false), recoveryCode) } finally { borrowed.fill(0) }
                val previewState = withTimeout(120_000) {
                    owner.state.first { !it.busy && (it.review != null || it.error != null) }
                }
                assertNull(previewState.error); assertFalse(previewState.complete)
                val review = requireNotNull(previewState.review)
                assertEquals(oldTrust.group.value, review.group)
                assertNotEquals(oldDevice, review.device)
                for (kind in listOf("author", "native"))
                    assertFalse(Files.exists(profile.resolve("joining/node/$kind/origin.protected")))
                assertTrue(Files.newDirectoryStream(data).use { !it.iterator().hasNext() })
                // HTTP is an internal synthetic transport seam. The production route catalog
                // remains HTTPS_ONLY; this explicit choice must not pretend to save HTTP.
                owner.confirm(review.binding, saveConnection = false)
                val restored = withTimeout(120_000) { ready.await() }
                withTimeout(30_000) { owner.drain() }
                assertTrue(owner.state.value.complete); assertNull(owner.state.value.error)
                assertNull(owner.state.value.connectionEnabled); assertNull(owner.state.value.connectionNotice)
                assertEquals(1, callbacks.get())
                assertFalse(Files.exists(restored.work.resolve("cloud-connections"), NOFOLLOW_LINKS))
                assertEquals(oldTrust.group, restored.trust.group); assertEquals(oldTrust.genesis, restored.trust.genesis)
                assertEquals(oldTrust.ownerCertificate, restored.trust.ownerCertificate)
                assertEquals(oldLedger.sequence + 1, restored.ledger().current().sequence)
                assertEquals(oldLedger.members.keys + restored.device, restored.ledger().current().members.keys)
                oldLedger.members.forEach { (id, member) ->
                    assertEquals(member, restored.ledger().current().members.getValue(id))
                }
                assertTrue(restored.ledger().current().members.getValue(restored.device).active)
                assertEquals(restored.certificate, restored.ledger().current().members.getValue(restored.device).certificate)
                val actual = restored.node.inspect()
                assertEquals(LanSyncGroupNodeStage.READY, actual.stage)
                assertEquals(SyncVersionState.ADMITTED, requireNotNull(actual.reconciliation.history).state(sourceReference.id))
                assertEquals(sourceHash, actual.nativeState.units.getValue(UNIT).valueHash)
                assertEquals(setOf(sourceReference), actual.nativeState.units.getValue(UNIT).frontiers)
                assertEquals(LanSyncGroupNativeStage.UNCHANGED, requireNotNull(actual.plan).units.getValue(UNIT).stage)
                assertEquals(conversationHash, actual.nativeState.units.getValue(CONVERSATION).valueHash)
                assertEquals(setOf(sourceReference), actual.nativeState.units.getValue(CONVERSATION).frontiers)
                assertEquals(LanSyncGroupNativeStage.UNCHANGED, requireNotNull(actual.plan).units.getValue(CONVERSATION).stage)
                assertConversation(data, expectedSession, expectedMessages)
                val receiverAuthor = SyncAuthorJournal(profile.resolve("joining/node/author"), restored.trust,
                    restored.certificate, { it.reversedArray() }, { it.reversedArray() })
                assertEquals(0L, receiverAuthor.state().committedSequence)
                assertTrue(restored.node.sourceOperations().isEmpty())
                val dailyFingerprint = restored.device
                val ledger = restored.ledger().current()
                val identityBytes = Files.readAllBytes(identityFile)
                val origins = listOf("author", "native").associateWith {
                    Files.readAllBytes(profile.resolve("joining/node/$it/origin.protected"))
                }
                val restoredData = hashes(data)
                assertEquals(oldFiles, hashes(offlineSender))

                phase = "cold-new-owners-without-cloud-client-or-code"
                owner.closeAndDrain(); recovery = null
                closeClient(receiverClient)
                val beforeColdRequests = recoveryMethods.values.sumOf { it.get() }
                val cold = joining()
                assertEquals(LanSyncGroupJoinStage.READY, cold.profile()?.stage)
                val reopened = requireNotNull(cold.open())
                val coldView = reopened.inspect()
                assertEquals(LanSyncGroupNodeStage.READY, coldView.stage)
                assertEquals(SyncVersionState.ADMITTED, requireNotNull(coldView.reconciliation.history).state(sourceReference.id))
                assertEquals(sourceHash, coldView.nativeState.units.getValue(UNIT).valueHash)
                assertEquals(setOf(sourceReference), coldView.nativeState.units.getValue(UNIT).frontiers)
                assertEquals(LanSyncGroupNativeStage.UNCHANGED, requireNotNull(coldView.plan).units.getValue(UNIT).stage)
                assertEquals(conversationHash, coldView.nativeState.units.getValue(CONVERSATION).valueHash)
                assertEquals(setOf(sourceReference), coldView.nativeState.units.getValue(CONVERSATION).frontiers)
                assertEquals(LanSyncGroupNativeStage.UNCHANGED, requireNotNull(coldView.plan).units.getValue(CONVERSATION).stage)
                val coldAccess = requireNotNull(cold.runtimeAccess())
                assertEquals(dailyFingerprint, coldAccess.device)
                assertEquals(ledger, coldAccess.ledger().current())
                val coldAuthor = SyncAuthorJournal(profile.resolve("joining/node/author"), coldAccess.trust,
                    coldAccess.certificate, { it.reversedArray() }, { it.reversedArray() })
                assertEquals(0L, coldAuthor.state().committedSequence)
                assertTrue(reopened.sourceOperations().isEmpty())
                assertRestored(data, documentHash, documentBytes)
                assertConversation(data, expectedSession, expectedMessages)
                assertEquals(restoredData, hashes(data)); assertEquals(oldFiles, hashes(offlineSender))
                assertContentEquals(identityBytes, Files.readAllBytes(identityFile))
                origins.forEach { (kind, bytes) ->
                    assertContentEquals(bytes, Files.readAllBytes(profile.resolve("joining/node/$kind/origin.protected")))
                }
                assertEquals(beforeColdRequests, recoveryMethods.values.sumOf { it.get() })
                assertFalse(Files.exists(profile.resolve("joining/app-runtime/cloud-connections"), NOFOLLOW_LINKS))

                phase = "header-free-method-evidence"
                for (method in listOf("PUT", "GET", "PROPFIND", "MKCOL"))
                    assertTrue((senderMethods[method]?.get() ?: 0) > 0)
                for (method in listOf("GET", "PROPFIND"))
                    assertTrue((recoveryMethods[method]?.get() ?: 0) > 0)
                assertEquals(0, recoveryMethods["PUT"]?.get() ?: 0)
                assertTrue(paths.keys.any { it.startsWith("/weak-chunks-v1/") })
                assertTrue(paths.keys.any { it.startsWith("/weak-assemblies-v1/") })
                fun counts(values: Map<String, AtomicInteger>) = values.toSortedMap().entries.joinToString(
                    separator = ",", prefix = "{", postfix = "}") { (name, count) -> "\"$name\":${count.get()}" }
                val evidence = "{\"kind\":\"CCB_REAL_WEBDAV_RUNTIME_CHAT_RECOVERY_COUNTS\"," +
                    "\"sourceSha\":\"480fbc2e6f6c6c0ac8cf3af937ecccb18bc73507\"," +
                    "\"documentBytes\":131113,\"documentSha256\":\"$documentHash\"," +
                    "\"sessionCount\":1,\"messageCount\":4,\"conversationValueHash\":\"$conversationHash\"," +
                    "\"uploadedObjects\":${cloudReceipt.objects.size},\"sourceAuthorSequence\":1," +
                    "\"recoveredAuthorSequence\":0,\"saveConnection\":false,\"coldHttpRequests\":0," +
                    "\"senderMethods\":" + counts(senderMethods) + ",\"recoveryMethods\":" + counts(recoveryMethods) +
                    ",\"paths\":" + counts(paths) + "}\n"
                Files.writeString(methodEvidence, evidence, StandardCharsets.UTF_8, CREATE_NEW, WRITE)
            } finally {
                recovery?.closeAndDrain(); coordinator?.closeAndDrain()
                if (!senderClosed) sender.close()
                clients.forEach(::closeClient)
                check(root.startsWith(runner) && root.fileName.toString().startsWith("ccb-rclone-recovery-") &&
                    !Files.isSymbolicLink(root) && root.toRealPath() == root)
                Files.walk(root).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
            }
        } } catch (failure: Throwable) {
            // Retain only safe type/location diagnostics, never the original message/cause/suppressed values.
            val safe = AssertionError("REAL_DAV_RUNTIME_RECOVERY_FAILED phase=$phase type=${failure.javaClass.simpleName}")
            safe.stackTrace = failure.stackTrace
            throw safe
        }
    }

    private fun platform() = object : LanSyncPlatform {
        private var admitted = false
        override suspend fun exclusive(work: suspend () -> Unit) {
            check(!admitted); admitted = true
            try { work() } finally { admitted = false }
        }
        override suspend fun reload(sessionIds: Set<String>) { check(admitted) }
    }

    private fun assertRestored(data: Path, expectedHash: String, expectedBytes: ByteArray) {
        val card = requireNotNull(LanSyncJsonFiles.read(data.resolve("entities/character_cards/original.json"),
            CharacterCard.serializer()))
        assertEquals("original", card.id); assertEquals("synthetic real-server recovered card", card.name)
        assertEquals("synthetic original domain body; no edited Prompt", card.basicSetting)
        val document = card.customDocuments.single()
        assertEquals("original-document", document.id); assertEquals("original.bin", document.fileName)
        val path = Path.of(document.filePath).toAbsolutePath().normalize()
        assertTrue(path.startsWith(data)); assertTrue(Files.isRegularFile(path, NOFOLLOW_LINKS))
        assertEquals(expectedBytes.size.toLong(), Files.size(path))
        assertEquals(expectedHash, hash(path)); assertContentEquals(expectedBytes, Files.readAllBytes(path))
    }

    private suspend fun assertConversation(data: Path, expectedSession: ChatSession, expectedMessages: List<ChatMessage>) {
        val storage = JsonFileStorage(data)
        val session = requireNotNull(storage.loadEntity("chat_sessions", SESSION, ChatSession.serializer()))
        val messages = ChatRepository(storage).getMessagesReadOnly(SESSION)
        assertEquals(expectedSession, session)
        assertEquals(expectedMessages, messages)
        assertEquals(SESSION, session.id); assertEquals("original", session.characterCardId)
        assertNull(session.lanSyncOrigin)
        assertEquals(1, storage.loadAll("chat_sessions", ChatSession.serializer()).size)
        assertEquals(4, storage.loadAll("chat_messages", ChatMessage.serializer()).size)
        assertEquals(listOf("u1", "a1", "u2", "a2"), messages.map { it.id })
        assertEquals(4, messages.map { it.id }.distinct().size)
        assertTrue(messages.all { it.sessionId == SESSION })
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT),
            messages.map { it.role })
        assertTrue(messages.zipWithNext().all { (left, right) -> left.orderKey < right.orderKey })
        assertEquals(listOf(0L, 0L, 1L, 1L), messages.map { it.sourceTurnOrder })
        assertEquals(messages[0].sourceTurnId, messages[1].sourceTurnId)
        assertEquals(messages[2].sourceTurnId, messages[3].sourceTurnId)
        assertNotEquals(messages[0].sourceTurnId, messages[2].sourceTurnId)
        assertEquals("u1", messages[1].generatedFromMessageId)
        assertEquals("u2", messages[3].generatedFromMessageId)
        val last = messages.last()
        assertEquals(MessageRole.ASSISTANT, last.role)
        assertEquals(LAST_MESSAGE, last.content); assertEquals(LAST_MESSAGE, last.displayContent)
        assertEquals(listOf("a2-v0", "a2-v1"), last.alternativeVersionIds)
        assertEquals(1, last.currentAlternativeIndex); assertEquals("a2-v1", last.currentAlternativeVersionId)
        assertEquals(LAST_MESSAGE, session.lastMessagePreview); assertEquals(MessageRole.ASSISTANT, session.lastMessageRole)
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun hash(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun hashes(root: Path): Map<String, String> = Files.walk(root).use { files ->
        files.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList().associate {
            root.relativize(it).toString() to hash(it)
        }
    }

    private companion object {
        const val UNIT = "character_cards/original"
        const val SESSION = "latest-conversation"
        const val CONVERSATION = "conversation/latest-conversation"
        const val LAST_MESSAGE = "Synthetic last assistant reply: the offline trip starts after this exact message."
    }
}
