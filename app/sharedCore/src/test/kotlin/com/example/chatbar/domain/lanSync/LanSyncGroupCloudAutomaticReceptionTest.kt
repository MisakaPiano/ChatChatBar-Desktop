package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.*
import java.net.BindException
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

/** Real TLS invitation, protected saved HTTPS routes and app-owned automatic repository reception.
 * Reopen replaces JVM owners, not the OS process. Protection and HTTPS trust are synthetic/pinned;
 * this does not establish system TLS, commercial-provider or physical-device behavior. */
class LanSyncGroupCloudAutomaticReceptionTest {
    private class NoLan : LanSyncNetwork {
        val connects = AtomicInteger()
        override fun addresses() = listOf("10.0.0.1")
        override fun accepts(address: InetAddress) = address.isLoopbackAddress
        override val discoveryEnabled = false
        override fun listening(fingerprint: String, port: Int) { throw BindException("SYNTHETIC_AUTOMATIC_RECEPTION_NEARBY_UNAVAILABLE") }
        override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket {
            connects.incrementAndGet(); error("SYNTHETIC_AUTOMATIC_RECEPTION_LAN_BYPASS")
        }
    }

    private class Https(private val shared: WeakFixture) : AutoCloseable {
        private val identity = LanSyncTlsIdentity.create()
        val authorized = AtomicInteger()
        private val authorization = Credentials.basic("synthetic user", "synthetic password", Charsets.UTF_8)
        val server = MockWebServer().apply {
            dispatcher = shared.dav
            useHttps(identity.context { false }.socketFactory, false)
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val endpoint = server.url("/sync/").newBuilder().host("127.0.0.1").build().toString()
        val client: OkHttpClient
        init {
            shared.dav.overrideRequest = { request ->
                if (request.getHeader("Authorization") != authorization) MockResponse().setResponseCode(401)
                else { authorized.incrementAndGet(); null }
            }
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers() = arrayOf(identity.certificate)
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { error("SYNTHETIC_NO_CLIENT_AUTH") }
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    require(chain.size == 1 && chain.single().encoded.contentEquals(identity.certificate.encoded))
                    chain.single().checkValidity(); chain.single().verify(identity.certificate.publicKey)
                }
            }
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS).sslSocketFactory(context.socketFactory, trust)
                .hostnameVerifier { host, session -> host == "127.0.0.1" &&
                    session.peerCertificates.single().encoded.contentEquals(identity.certificate.encoded) }.build()
        }
        override fun close() {
            server.close(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        }
    }

    private class Peer(val profile: Path, val data: Path, val workspaceState: Path,
        val identity: LanSyncTlsIdentity, val joined: Boolean, https: Https, val network: NoLan) {
        init { Files.createDirectories(profile); Files.createDirectories(data); Files.createDirectories(workspaceState) }
        val gate = LanSyncOperationGate()
        val storage = JsonFileStorage(data, gate)
        val chats = ChatRepository(storage)
        val reloads = AtomicInteger()
        private var admitted = false
        private val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) = gate.exclusive {
                check(!admitted); admitted = true
                try { work() } finally { admitted = false }
            }
            override suspend fun reload(sessionIds: Set<String>) {
                check(admitted) { "SYNTHETIC_AUTOMATIC_RECEPTION_RELOAD_OUTSIDE_ADMISSION" }
                chats.reloadAfterLanSync(); reloads.incrementAndGet()
            }
        }
        val workspace = LanSyncWorkspace(data, workspaceState, { Path.of(it) })
        val founder = LanSyncGroupEnrollment(profile, workspace, platform, identity,
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> })
        val joining = LanSyncGroupJoining(profile, workspace, platform, identity,
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> })
        @Volatile var activeAccess: LanSyncGroupRuntimeAccess? = null
        private var closed = false
        val coordinator = LanSyncGroupCoordinator({ Files.isRegularFile(profile.resolve(
            if (joined) "joining/profile.protected" else "profile.protected"), NOFOLLOW_LINKS) },
            { founder }, { joining }, network, if (joined) "synthetic receiving phone" else "synthetic sending PC",
            cloudRuntimeFactory = { current, allowed, wake, report ->
                activeAccess = current
                LanSyncGroupCloudRuntime(current, allowed, wake, report, current.cloudRoutes(), https.client,
                    WebDavEndpointPolicy.HTTPS_ONLY)
            }, sourceChanges = storage.committedRevisionFor(LanSyncScope.schemas.keys))
        val dailyRoot get() = if (joined) profile.resolve("joining") else profile
        val authorRoot get() = dailyRoot.resolve("node/author")
        val routeRoot get() = dailyRoot.resolve("app-runtime/cloud-connections")
        suspend fun setupAccess() = requireNotNull(if (joined) joining.runtimeAccess() else founder.runtimeAccess())
        fun author(trust: SyncGroupTrust) = SyncAuthorJournal(authorRoot, trust,
            Base64.getEncoder().encodeToString(identity.certificate.encoded), { it.reversedArray() }, { it.reversedArray() })
        suspend fun close() { if (!closed) { closed = true; coordinator.closeAndDrain(); founder.close() } }
    }

    private suspend fun awaitStage(stage: String, millis: Long = 90_000, condition: suspend () -> Boolean) {
        try { withTimeout(millis) { while (!condition()) delay(25) } }
        catch (timeout: TimeoutCancellationException) { throw AssertionError("SYNTHETIC_AUTOMATIC_RECEPTION_$stage", timeout) }
    }
    private fun digest(path: Path): String {
        val hash = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun authorPrefix(peer: Peer): Map<String, String> = Files.newDirectoryStream(peer.authorRoot).use { paths ->
        paths.filter { val name = it.fileName.toString(); name == "origin.protected" || name.startsWith("signed-") || name.startsWith("intent-") }
            .associate { it.fileName.toString() to digest(it) }
    }
    private fun ownReferences(peer: Peer): Set<SyncVersionRef> = requireNotNull(peer.coordinator.view.value?.reconciliation?.history)
        .admittedVersions().filter { it.author == peer.identity.fingerprint }.mapTo(linkedSetOf()) { it.reference() }
    private fun settled(peer: Peer, conversations: Set<String>): Boolean {
        val view = peer.coordinator.view.value ?: return false
        val plan = view.plan ?: return false
        return !peer.coordinator.state.value.busy && view.stage == LanSyncGroupNodeStage.READY &&
            conversations.all { plan.units["conversation/$it"]?.stage == LanSyncGroupNativeStage.UNCHANGED } &&
            plan.units.values.none { it.stage == LanSyncGroupNativeStage.LOCAL_PROPOSAL }
    }
    private fun finalMessageIsInNativeView(peer: Peer, session: String, message: String, content: String, preview: String?): Boolean {
        val view = peer.coordinator.view.value ?: return false
        val before = view.plan?.before ?: return false
        val hash = before.hashes()["conversation/$session"] ?: return false
        return before.records.any { it.type == "chat_messages" && it.text("id") == message && it.text("content") == content } &&
            before.records.any { it.type == "chat_sessions" && it.text("id") == session && it.text("lastMessagePreview") == preview } &&
            view.nativeState.units["conversation/$session"]?.valueHash == hash
    }
    private data class CloudOriginal(val plan: LanSyncGroupTargetPlan, val receipt: LanSyncGroupCloudTargetReceipt,
        val planPath: Path, val receiptPath: Path)
    private fun completeCloudOriginal(access: LanSyncGroupRuntimeAccess, route: SyncStoreId,
        wanted: Set<SyncVersionRef>, checkpoint: SyncVersionRef): CloudOriginal? {
        val root = access.work.resolve("cloud-runtime/${route.value}/target")
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return null
        return Files.newDirectoryStream(root, "cloud-receipt-*.json").use { paths ->
            var found: CloudOriginal? = null
            for (path in paths) {
                val receipt = requireNotNull(LanSyncJsonFiles.read(path, LanSyncGroupCloudTargetReceipt.serializer()))
                val planPath = root.resolve("plan-" + path.fileName.toString().removePrefix("cloud-receipt-"))
                val plan = requireNotNull(LanSyncJsonFiles.read(planPath, LanSyncGroupTargetPlan.serializer(), receipt.planHash))
                if (plan.checkpoint != checkpoint || plan.versions.toSet() != wanted) continue
                val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, route)
                assertEquals(target, plan.target); assertEquals(target, receipt.target)
                assertEquals(access.trust.group, plan.group); assertEquals(plan.checkpoint, receipt.checkpoint)
                assertEquals(plan.objects, receipt.objects.map { it.objectRequest })
                assertTrue(receipt.objects.isNotEmpty())
                assertTrue(receipt.objects.all { it.evidence.readback is LanSyncGroupCloudTargetObservation.Chunks })
                wanted.forEach { reference -> assertEquals(1, receipt.objects.count {
                    it.objectRequest.role == LanSyncGroupTargetObjectRole.VERSION && it.objectRequest.slot == reference.id.value }) }
                found = CloudOriginal(plan, receipt, planPath, path)
            }
            found
        }
    }
    private suspend fun join(admin: LanSyncGroupAdministrationTest.Fixture, receiver: Peer) {
        // Explicit first enrollment is setup, using the existing real TLS invitation and root API.
        val invitation = admin.service.invitation(16384).also(admin.invitations::add)
        val listener = (invitation.invitationContext().serverSocketFactory.createServerSocket(0, 2,
            InetAddress.getLoopbackAddress()) as SSLServerSocket).also { it.needClientAuth = true }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val offer = invitation.issue("10.0.0.1", listener.localPort)
            receiver.joining.prepare(offer.code()) { }
            val proof = pool.submit<LanSyncGroupInvitedPeer> { invitation.serve(listener.accept() as SSLSocket) }
            receiver.joining.prove("synthetic receiving phone") { current ->
                LanSyncTls.connect(receiver.identity, current.host, current.port, current.inviter, 15_000) { _, _ ->
                    Socket(InetAddress.getLoopbackAddress(), listener.localPort)
                }
            }
            val preview = admin.service.prepareMember(proof.get(20, TimeUnit.SECONDS), invitation)
            admin.service.addMember(admin.service.resume(admin.code), preview.acknowledge(preview.request.approvalBinding))
            val result = receiver.joining.activate(FileCiphertextStore(admin.profile.resolve("local-replica")))
            assertEquals(LanSyncGroupJoinStage.READY, result.profile.stage)
        } finally { listener.close(); pool.shutdownNow(); check(pool.awaitTermination(25, TimeUnit.SECONDS)) }
    }

    @Test fun coldConfiguredReceiverAutomaticallyAppliesNewConversationFromHttpsAfterSenderGoesOffline() = runBlocking {
        val root = Files.createTempDirectory("synthetic-cloud-automatic-reception-").toRealPath()
        val sourceRoot = Files.createDirectory(root.resolve("source"))
        val receiverRoot = Files.createDirectory(root.resolve("receiver"))
        val providerRoot = Files.createDirectory(root.resolve("provider"))
        assertFalse(receiverRoot.startsWith(sourceRoot)); assertFalse(providerRoot.startsWith(sourceRoot))
        val peers = mutableListOf<Peer>(); var observedJob: Job? = null
        val admin = LanSyncGroupAdministrationTest.Fixture(sourceRoot)
        try {
            WeakFixture(providerRoot).use { shared -> Https(shared).use { https ->
                try {
                admin.seed()
                fun sender() = Peer(admin.profile, admin.data, sourceRoot.resolve("workspace"), admin.identity,
                    false, https, NoLan()).also(peers::add)
                fun receiver() = Peer(receiverRoot.resolve("profile"), receiverRoot.resolve("data"),
                    receiverRoot.resolve("workspace"), admin.joiningIdentity, true, https, NoLan()).also(peers::add)
                val setupSender = sender()
                setupSender.storage.saveEntity("character_cards", "shared-card",
                    CharacterCard("shared-card", "synthetic shared card", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
                setupSender.chats.createSession(ChatSession("source-old", "shared-card", "synthetic source original", createdAt = 1, updatedAt = 1))
                setupSender.chats.addMessage(ChatMessage("source-old-message", "source-old", MessageRole.USER,
                    "synthetic source original body", createdAt = 2, updatedAt = 2))
                val setupSourcePreview = requireNotNull(setupSender.chats.getSession("source-old")).lastMessagePreview
                setupSender.coordinator.startIfConfigured()
                awaitStage("SETUP_SOURCE_LOCAL_PUBLICATION") {
                    settled(setupSender, setOf("source-old")) && ownReferences(setupSender).isNotEmpty() &&
                        finalMessageIsInNativeView(setupSender, "source-old", "source-old-message", "synthetic source original body", setupSourcePreview)
                }
                setupSender.close()

                val setupReceiver = receiver()
                try { withTimeout(60_000) { join(admin, setupReceiver) } }
                catch (timeout: TimeoutCancellationException) { throw AssertionError("SYNTHETIC_AUTOMATIC_RECEPTION_TLS_JOIN_SETUP", timeout) }
                val receiverAccess = setupReceiver.setupAccess()
                val receiverTrust = receiverAccess.trust
                val password = "synthetic password".toByteArray()
                val receiverRoute = try { receiverAccess.cloudRoutes().add(LanSyncGroupCloudDraft("synthetic receiver saved route",
                    https.endpoint, "synthetic user", password, enabled = false)) } finally { password.fill(0) }
                setupReceiver.coordinator.startIfConfigured()
                awaitStage("SETUP_JOINED_OLD_GRAPH_APPLIED") {
                    settled(setupReceiver, setOf("source-old")) && setupReceiver.chats.getAllSessions().any { it.id == "source-old" }
                }
                val receiverCard = requireNotNull(setupReceiver.chats.getSession("source-old")).characterCardId
                setupReceiver.chats.createSession(ChatSession("receiver-old", receiverCard, "synthetic receiver original", createdAt = 3, updatedAt = 3))
                setupReceiver.chats.addMessage(ChatMessage("receiver-old-message", "receiver-old", MessageRole.USER,
                    "synthetic receiver original body", createdAt = 4, updatedAt = 4))
                val setupReceiverPreview = requireNotNull(setupReceiver.chats.getSession("receiver-old")).lastMessagePreview
                awaitStage("SETUP_RECEIVER_LOCAL_AUTHOR_SETTLED") {
                    settled(setupReceiver, setOf("source-old", "receiver-old")) && ownReferences(setupReceiver).isNotEmpty() &&
                        finalMessageIsInNativeView(setupReceiver, "receiver-old", "receiver-old-message", "synthetic receiver original body", setupReceiverPreview)
                }
                val receiverOwnRefs = ownReferences(setupReceiver)
                setupReceiver.close()
                val receiverAuthor = setupReceiver.author(receiverTrust).state()
                assertNull(receiverAuthor.pendingApproved)
                val receiverAuthorPrefix = authorPrefix(setupReceiver)
                val receiverOldSession = requireNotNull(setupReceiver.chats.getSession("receiver-old"))
                val receiverOldMessages = setupReceiver.chats.getMessages("receiver-old")
                val receiverOldBytes = listOf("entities/chat_sessions/receiver-old.json", "entities/chat_messages/receiver-old_receiver-old-message.json")
                    .associateWith { Files.readAllBytes(setupReceiver.data.resolve(it)) }
                // The already saved connection is enabled before the cold-owner boundary, without running it.
                receiverAccess.cloudRoutes().setEnabled(receiverRoute, true)
                val receiverCatalogPath = setupReceiver.routeRoot.resolve("cloud-routes.protected")
                val receiverCatalogBytes = Files.readAllBytes(receiverCatalogPath)
                val receiverProfileBytes = Files.readAllBytes(setupReceiver.dailyRoot.resolve("profile.protected"))
                val receiverRecipientBytes = Files.readAllBytes(setupReceiver.dailyRoot.resolve("recipient/recipient.protected"))
                val receiverCertificate = setupReceiver.identity.certificate.encoded.copyOf()
                admin.close()

                val source = sender()
                val sourceAccess = source.setupAccess()
                val sourcePassword = "synthetic password".toByteArray()
                val sourceRoute = try { sourceAccess.cloudRoutes().add(LanSyncGroupCloudDraft("synthetic sender saved route",
                    https.endpoint, "synthetic user", sourcePassword, enabled = true)) } finally { sourcePassword.fill(0) }
                source.coordinator.startIfConfigured()
                awaitStage("SETUP_SENDER_CLOUD_READY") {
                    settled(source, setOf("source-old")) && source.coordinator.cloudSettings.value.progress[sourceRoute]?.selectionObserved == true
                }
                val baselineOwnRefs = ownReferences(source)
                val sourceOld = requireNotNull(source.chats.getSession("source-old"))
                val sourceOldMessages = source.chats.getMessages("source-old")
                assertNull(source.chats.getSession("receiver-old"))

                // Measured source work uses only normal public repository saves.
                val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
                val image = source.data.resolve("images/measured-original.png")
                Files.createDirectories(image.parent); Files.write(image, png)
                val pngHash = digest(image)
                source.chats.createSession(ChatSession("measured-new", "shared-card", "synthetic measured conversation", createdAt = 10, updatedAt = 10))
                source.chats.addMessage(ChatMessage("measured-first", "measured-new", MessageRole.ASSISTANT,
                    "synthetic first received message", createdAt = 11, updatedAt = 11))
                source.chats.addMessage(ChatMessage("measured-second", "measured-new", MessageRole.USER,
                    "synthetic latest received message", images = listOf(image.toString()), createdAt = 12, updatedAt = 12))
                source.chats.addMessage(ChatMessage("source-later-message", "source-old", MessageRole.ASSISTANT,
                    "synthetic new reply in the already cached conversation", createdAt = 13, updatedAt = 13))
                val expectedSession = requireNotNull(source.chats.getSession("measured-new"))
                val expectedMessages = source.chats.getMessages("measured-new")
                val expectedUpdatedOldSession = requireNotNull(source.chats.getSession("source-old"))
                val expectedUpdatedOldMessages = source.chats.getMessages("source-old")
                assertEquals(sourceOldMessages + expectedUpdatedOldMessages.last(), expectedUpdatedOldMessages)
                assertEquals("source-later-message", expectedUpdatedOldMessages.last().id)
                awaitStage("SOURCE_NORMAL_SAVES_SIGNED") {
                    val view = source.coordinator.view.value
                    val before = view?.plan?.before
                    val records = before?.records.orEmpty()
                    val imageMessage = records.singleOrNull { it.type == "chat_messages" && it.text("id") == "measured-second" }
                    val imageRef = (imageMessage?.body?.get("images") as? JsonArray)?.singleOrNull()?.jsonPrimitive?.contentOrNull
                    val imageParts = imageRef?.split('/').orEmpty()
                    val finalBodies = imageMessage?.text("content") == expectedMessages.last().content &&
                        records.any { it.type == "chat_messages" && it.text("id") == "source-later-message" &&
                            it.text("content") == expectedUpdatedOldMessages.last().content } &&
                        records.any { it.type == "chat_sessions" && it.text("id") == "source-old" &&
                            it.text("lastMessagePreview") == expectedUpdatedOldSession.lastMessagePreview } &&
                        records.any { it.type == "chat_sessions" && it.text("id") == "measured-new" &&
                            it.text("lastMessagePreview") == expectedSession.lastMessagePreview }
                    val imageBound = imageParts.size == 5 && imageParts[0] == "ccb-lan-v1" && imageParts[1] == "images" &&
                        imageParts[3] == pngHash && before?.resources?.get(pngHash) == png.size.toLong()
                    val hashes = before?.hashes().orEmpty()
                    val nativeBound = setOf("conversation/source-old", "conversation/measured-new").all { unit ->
                        hashes[unit] != null && view?.nativeState?.units?.get(unit)?.valueHash == hashes[unit]
                    }
                    finalBodies && imageBound && nativeBound && settled(source, setOf("source-old", "measured-new")) &&
                        ownReferences(source).size > baselineOwnRefs.size
                }
                val sourceHistory = requireNotNull(source.coordinator.view.value?.reconciliation?.history)
                val sourceVersions = sourceHistory.admittedVersions().filter { it.author == source.identity.fingerprint }.sortedBy { it.authorSequence }
                assertEquals((1L..sourceVersions.size.toLong()).toList(), sourceVersions.map { it.authorSequence })
                val wanted = sourceHistory.admittedVersions().mapTo(linkedSetOf()) { it.reference() }
                val newFrontiers = sourceHistory.frontier("conversation/measured-new").mapTo(linkedSetOf()) { it.version }
                assertEquals(1, newFrontiers.size)
                val checkpoint = sourceAccess.ledger().current().reference()
                var cloud: CloudOriginal? = null
                awaitStage("SOURCE_EXACT_FULL_TYPED_CLOUD_RECEIPT") {
                    cloud = completeCloudOriginal(sourceAccess, sourceRoute, wanted, checkpoint); cloud != null
                }
                val full = requireNotNull(cloud)
                assertTrue(full.plan.objects.any { it.role == LanSyncGroupTargetObjectRole.RESOURCE && it.descriptor.length > 0 })
                val fullPlanBytes = Files.readAllBytes(full.planPath); val fullReceiptBytes = Files.readAllBytes(full.receiptPath)
                assertEquals(listOf("measured-first", "measured-second"), expectedMessages.map { it.id })
                assertContentEquals(png, Files.readAllBytes(Path.of(expectedMessages.last().images.single())))
                source.close()
                val offline = root.resolve("source-offline")
                Files.move(sourceRoot, offline)
                assertFalse(Files.exists(sourceRoot, NOFOLLOW_LINKS))

                // A new receiver owner graph retains the original identity, recipient, files and saved route.
                val cold = receiver()
                assertNotSame(setupReceiver.storage, cold.storage); assertNotSame(setupReceiver.workspace, cold.workspace)
                assertNotSame(setupReceiver.chats, cold.chats); assertNotSame(setupReceiver.coordinator, cold.coordinator)
                assertTrue(Files.isRegularFile(receiverCatalogPath, NOFOLLOW_LINKS))
                val freshCatalog = LanSyncGroupCloudRoutes(cold.routeRoot, receiverTrust, cold.identity.fingerprint,
                    { it.reversedArray() }, { it.reversedArray() })
                val saved = freshCatalog.rows().single()
                assertEquals(receiverRoute, saved.id); assertEquals(https.endpoint, saved.endpoint); assertTrue(saved.enabled)
                assertNotEquals(sourceRoute, receiverRoute)
                assertContentEquals(receiverCatalogBytes, Files.readAllBytes(receiverCatalogPath))
                assertContentEquals(receiverCertificate, cold.identity.certificate.encoded)
                assertContentEquals(receiverRecipientBytes, Files.readAllBytes(cold.dailyRoot.resolve("recipient/recipient.protected")))
                cold.chats.initialize()
                assertEquals(setOf("source-old", "receiver-old"), cold.chats.getAllSessions().map { it.id }.toSet())
                assertEquals(sourceOldMessages, cold.chats.getMessages("source-old"))
                assertEquals(receiverOldMessages, cold.chats.getMessages("receiver-old"))
                val beforeRevision = cold.chats.lanSyncRevision.value
                val observed = AtomicReference(cold.chats.getAllSessions())
                observedJob = launch(start = CoroutineStart.UNDISPATCHED) { cold.chats.observeSessions().collect { observed.set(it) } }
                val requestsBeforeReceiver = https.server.requestCount
                cold.coordinator.startIfConfigured()
                cold.coordinator.onNetworkAvailable() // One normal lifecycle hint; no explicit sync or recovery action.
                awaitStage("COLD_RECEIVER_AUTOMATIC_REPOSITORY_APPLY") {
                    val view = cold.coordinator.view.value
                    cold.reloads.get() > 0 && cold.chats.lanSyncRevision.value > beforeRevision &&
                        observed.get().any { it.id == "measured-new" && it.lastMessagePreview == expectedSession.lastMessagePreview } &&
                        observed.get().any { it.id == "source-old" && it.lastMessagePreview == expectedUpdatedOldSession.lastMessagePreview } &&
                        cold.chats.getMessages("source-old").map { it.id } == expectedUpdatedOldMessages.map { it.id } &&
                        view?.nativeState?.units?.get("conversation/measured-new")?.frontiers == newFrontiers &&
                        settled(cold, setOf("source-old", "receiver-old", "measured-new"))
                }
                observedJob?.cancelAndJoin(); observedJob = null
                cold.close() // Freeze successful business results and drain owned HTTP before total-counter checks.
                assertTrue(https.server.requestCount > requestsBeforeReceiver)
                val receivedSession = cold.chats.getAllSessions().single { it.id == "measured-new" }
                assertEquals(expectedSession.title, receivedSession.title)
                assertEquals(expectedSession.lastMessagePreview, receivedSession.lastMessagePreview)
                assertEquals(expectedSession.lastMessageTime, receivedSession.lastMessageTime)
                assertEquals(expectedSession.lastMessageRole, receivedSession.lastMessageRole)
                val receivedMessages = cold.chats.getMessages("measured-new")
                assertEquals(expectedMessages.map { it.copy(images = emptyList()) }, receivedMessages.map { it.copy(images = emptyList()) })
                val receivedImage = Path.of(receivedMessages.last().images.single()).toAbsolutePath().normalize()
                assertTrue(receivedImage.startsWith(cold.data)); assertFalse(receivedImage.startsWith(sourceRoot))
                assertContentEquals(png, Files.readAllBytes(receivedImage))
                assertEquals(sourceOld.title, requireNotNull(cold.chats.getSession("source-old")).title)
                assertEquals(expectedUpdatedOldSession.lastMessagePreview,
                    cold.chats.getAllSessions().single { it.id == "source-old" }.lastMessagePreview)
                assertEquals(expectedUpdatedOldMessages, cold.chats.getMessages("source-old"))
                assertEquals(receiverOldSession, cold.chats.getSession("receiver-old"))
                assertEquals(receiverOldMessages, cold.chats.getMessages("receiver-old"))
                receiverOldBytes.forEach { (relative, bytes) -> assertContentEquals(bytes, Files.readAllBytes(cold.data.resolve(relative))) }
                val finalHistory = requireNotNull(cold.coordinator.view.value?.reconciliation?.history)
                assertTrue(wanted.all(finalHistory::contains))
                assertEquals(receiverOwnRefs, ownReferences(cold))
                val finalAuthor = cold.author(receiverTrust).state()
                assertEquals(receiverAuthor.committedSequence, finalAuthor.committedSequence)
                assertEquals(receiverAuthor.last?.version?.reference(), finalAuthor.last?.version?.reference())
                assertNull(finalAuthor.pendingApproved); assertEquals(receiverAuthorPrefix, authorPrefix(cold))
                val actualCatalog = requireNotNull(cold.activeAccess).cloudRoutes().rows().single()
                assertEquals(saved, actualCatalog)
                assertContentEquals(receiverCatalogBytes, Files.readAllBytes(receiverCatalogPath))
                assertContentEquals(receiverProfileBytes, Files.readAllBytes(cold.dailyRoot.resolve("profile.protected")))
                assertContentEquals(receiverRecipientBytes, Files.readAllBytes(cold.dailyRoot.resolve("recipient/recipient.protected")))
                assertContentEquals(fullPlanBytes, Files.readAllBytes(offline.resolve(sourceRoot.relativize(full.planPath))))
                assertContentEquals(fullReceiptBytes, Files.readAllBytes(offline.resolve(sourceRoot.relativize(full.receiptPath))))
                assertFalse(Files.exists(sourceRoot, NOFOLLOW_LINKS))
                assertEquals(0, shared.server.requestCount)
                awaitStage("HTTPS_AUTH_COUNTS_DRAINED", 5_000) { https.server.requestCount == https.authorized.get() }
                assertEquals(https.server.requestCount, https.authorized.get())
                assertTrue(peers.all { it.network.connects.get() == 0 })
                println("CCB_GROUP_CLOUD_AUTOMATIC_RECEPTION " + buildJsonObject {
                    put("actualInvitationTlsJoin", true); put("actualPinnedHttps", true)
                    put("ordinaryRepositorySavesOnly", true); put("strictFullCloudReceipt", true)
                    put("senderOfflineRootMoved", true); put("coldJvmOwnersRecreated", true)
                    put("savedProtectedRouteRetained", true); put("actualRepositoryReloadCallbacks", cold.reloads.get())
                    put("receivedMessageCount", receivedMessages.size); put("receivedImageBytes", png.size)
                    put("warmExistingMessageIndexRefreshed", true); put("updatedExistingMessageCount", expectedUpdatedOldMessages.size)
                    put("bothOriginalConversationsRetained", true); put("receiverAuthorUnchanged", true)
                    put("sourceVersionCount", sourceVersions.size); put("receiverAuthorSequence", finalAuthor.committedSequence)
                    put("plainHttpRequests", shared.server.requestCount); put("lanConnectAttempts", peers.sumOf { it.network.connects.get() })
                })
                } finally {
                    withContext(NonCancellable) {
                        observedJob?.cancelAndJoin(); observedJob = null
                        peers.asReversed().forEach { it.close() }
                    }
                }
            } }
        } finally {
            withContext(NonCancellable) {
                observedJob?.cancelAndJoin(); peers.asReversed().forEach { it.close() }; admin.close()
                check(root.toFile().deleteRecursively())
            }
        }
    }
}
