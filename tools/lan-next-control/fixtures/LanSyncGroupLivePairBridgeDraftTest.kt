package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.*
import kotlin.test.*

/** Draft overlay for frozen f5fc sharedCore tests. NOT compiled or executed.
 * Real two-owner TCP/TLS data plane; explicit test catalog/delivery polling replaces UDP discovery.
 * The phone side alone initiates sockets, so this topology can later be split at a Windows/AVD boundary.
 * This is neither production LAN discovery nor actual Windows/Android/OS-protection evidence. */
class LanSyncGroupLivePairBridgeDraftTest {
    private class Peer(val profile: Path, val data: Path, scratch: Path,
        val identity: LanSyncTlsIdentity, val joined: Boolean, val advertised: String) {
        val gate = LanSyncOperationGate()
        val storage = JsonFileStorage(data, gate)
        val chats = ChatRepository(storage)
        val workspace = LanSyncWorkspace(data, scratch, { Path.of(it) })
        val port = AtomicInteger()
        val connects = AtomicInteger()
        val accepts = AtomicInteger()
        val wrongPeer = AtomicInteger()
        @Volatile var allowedPeer: String? = null
        val network = object : LanSyncNetwork {
            override fun addresses() = listOf(advertised)
            override val discoveryEnabled = false
            override fun listening(fingerprint: String, port: Int) {
                check(fingerprint == identity.fingerprint)
                this@Peer.port.set(port)
            }
            override fun accepts(address: InetAddress): Boolean {
                accepts.incrementAndGet()
                return address.isLoopbackAddress // Test seam only; production Local is unchanged.
            }
            override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket {
                if (peer.fingerprint != allowedPeer) wrongPeer.incrementAndGet()
                connects.incrementAndGet()
                return LanSyncTls.connect(identity, peer.host, peer.port, peer.fingerprint, 15_000) { _, port ->
                    Socket(InetAddress.getLoopbackAddress(), port)
                }
            }
        }
        private val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) = gate.exclusive { work() }
            override suspend fun reload(sessionIds: Set<String>) { chats.reloadAfterLanSync() }
        }
        val founder = LanSyncGroupEnrollment(profile, workspace, platform, identity,
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> })
        val joining = LanSyncGroupJoining(profile, workspace, platform, identity,
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> })
        val coordinator = LanSyncGroupCoordinator({ Files.isRegularFile(profile.resolve(
            if (joined) "joining/profile.protected" else "profile.protected"), NOFOLLOW_LINKS) },
            { founder }, { joining }, network, if (joined) "synthetic phone" else "synthetic PC",
            sourceChanges = storage.committedRevisionFor(LanSyncScope.schemas.keys))

        // No production API is added. Read the exact already-attached instance, not a second Node graph.
        fun access(): LanSyncGroupRuntimeAccess = requireNotNull(
            LanSyncGroupCoordinator::class.java.getDeclaredField("access").apply { isAccessible = true }
                .get(coordinator) as LanSyncGroupRuntimeAccess?)
        suspend fun close() { coordinator.closeAndDrain(); founder.close() }
    }

    /** Test-only explicit route bridge. No direct apply/commit/retry button or cloud/file byte exchange.
     * Pull drives raw encrypted catalog discovery; the real Coordinator decides prefetch/native apply.
     * Return upload replays only the phone's already-signed queue through the real host TLS store. */
    private class Bridge(val host: Peer, val phone: Peer) {
        val rounds = AtomicInteger()
        val target = SyncDeliveryTarget(SyncDeliveryKind.NEARBY_DEVICE, SyncStoreId.random())
        suspend fun round() {
            val receiver = phone.access()
            val remote = receiver.remote(LanSyncPeer(host.identity.fingerprint, "synthetic host",
                host.advertised, host.port.get()), phone.network)
            receiver.reader.routes("explicit", listOf(
                LanSyncGroupReplicaReader.Route(host.identity.fingerprint, remote)))
            var cursor: CommitCursor? = null
            do { cursor = receiver.node.discover(receiver.reader.discoveryFrom(remote), cursor, 20).nextCursor }
            while (cursor != null)
            phone.coordinator.onNetworkAvailable()
            for (operation in receiver.node.sourceOperations()) receiver.node.upload(operation, remote, target)
            host.coordinator.onNetworkAvailable()
            rounds.incrementAndGet()
        }
    }

    private suspend fun await(stage: String, condition: suspend () -> Boolean) {
        try { withTimeout(120_000) { while (!condition()) delay(50) } }
        catch (_: TimeoutCancellationException) { throw AssertionError("LIVE_PAIR_STAGE_TIMEOUT:$stage") }
    }
    private fun settled(peer: Peer, sessions: Set<String> = emptySet()): Boolean {
        val view = peer.coordinator.view.value ?: return false
        val plan = view.plan ?: return false
        return !peer.coordinator.state.value.busy && view.stage == LanSyncGroupNodeStage.READY &&
            sessions.all { plan.units["conversation/$it"]?.stage == LanSyncGroupNativeStage.UNCHANGED } &&
            plan.units.values.none { it.stage == LanSyncGroupNativeStage.LOCAL_PROPOSAL }
    }
    private fun png(tag: String): ByteArray {
        val original = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
        val type = "ruSt".toByteArray(Charsets.US_ASCII)
        val body = tag.toByteArray(Charsets.UTF_8)
        val chunk = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            it.writeInt(body.size); it.write(type); it.write(body)
            it.writeInt(CRC32().apply { update(type); update(body) }.value.toInt())
        } }.toByteArray()
        return original.copyOfRange(0, original.size - 12) + chunk + original.copyOfRange(original.size - 12, original.size)
    }
    private suspend fun assertConversation(peer: Peer, session: String,
        expected: List<Pair<String, String>>, imageMessage: String, imageHash: String): Boolean {
        val messages = peer.chats.getMessages(session)
        if (messages.map { it.id to it.content } != expected) return false
        if (messages.map { it.id }.distinct().size != expected.size) return false
        val path = messages.singleOrNull { it.id == imageMessage }?.images?.singleOrNull() ?: return false
        val file = Path.of(path)
        return Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) > 0 &&
            lanSyncHash(Files.readAllBytes(file)) == imageHash && settled(peer, setOf(session))
    }
    private suspend fun join(admin: LanSyncGroupAdministrationTest.Fixture, host: Peer, phone: Peer) {
        val invitation = admin.service.invitation(host.port.get())
        val listener = (invitation.invitationContext().serverSocketFactory.createServerSocket(0, 2,
            InetAddress.getLoopbackAddress()) as SSLServerSocket).also { it.needClientAuth = true }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val offer = invitation.issue(host.advertised, listener.localPort)
            phone.joining.prepare(offer.code()) { }
            val proof = pool.submit<LanSyncGroupInvitedPeer> { invitation.serve(listener.accept() as SSLSocket) }
            phone.joining.prove("synthetic phone") { current ->
                LanSyncTls.connect(phone.identity, current.host, current.port, current.inviter, 15_000) { _, _ ->
                    Socket(InetAddress.getLoopbackAddress(), listener.localPort)
                }
            }
            val preview = admin.service.prepareMember(proof.get(20, TimeUnit.SECONDS), invitation)
            admin.service.addMember(admin.service.resume(admin.code), preview.acknowledge(preview.request.approvalBinding))
            val remote = LanSyncGroupRemoteStore(offer.trust.group,
                Files.createDirectory(phone.profile.resolve("draft-bootstrap-route")), {
                    LanSyncTls.connect(phone.identity, host.advertised, host.port.get(), host.identity.fingerprint, 15_000) { _, port ->
                        Socket(InetAddress.getLoopbackAddress(), port)
                    }
                }, authenticatedConnect = true)
            assertEquals(LanSyncGroupJoinStage.READY, phone.joining.activate(remote).profile.stage)
        } finally {
            invitation.close(); listener.close(); pool.shutdownNow(); check(pool.awaitTermination(25, TimeUnit.SECONDS))
        }
    }

    @Test fun realTlsTwoOwnersExchangeNewConversationsImagesAndLateWriteWithoutEcho(): Unit = runBlocking {
        check(System.getenv("GITHUB_ACTIONS") == "true" && System.getenv("CCB_LAN_LIVE_PAIR_PROOF") == "true")
        val runner = Path.of(requireNotNull(System.getenv("RUNNER_TEMP"))).toRealPath()
        val root = Files.createTempDirectory(runner, "synthetic-live-pair-").toRealPath()
        check(root.parent == runner)
        val admin = LanSyncGroupAdministrationTest.Fixture(Files.createDirectory(root.resolve("PC")))
        val peers = mutableListOf<Peer>()
        var polling: Job? = null
        var phase = "setup"
        try {
            admin.seed()
            val host = Peer(admin.profile, admin.data, root.resolve("PC/workspace"), admin.identity, false, "10.0.0.11").also(peers::add)
            val phoneRoot = Files.createDirectory(root.resolve("phone"))
            val phone = Peer(Files.createDirectory(phoneRoot.resolve("profile")), Files.createDirectory(phoneRoot.resolve("data")),
                Files.createDirectory(phoneRoot.resolve("workspace")), admin.joiningIdentity, true, "10.0.0.12").also(peers::add)
            phone.allowedPeer = host.identity.fingerprint
            host.storage.saveEntity("character_cards", "pair-card", CharacterCard("pair-card", "synthetic pair card", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
            host.coordinator.startIfConfigured()
            await("HOST_READY") { settled(host) && host.port.get() >= 1024 }
            join(admin, host, phone)
            phone.coordinator.startIfConfigured()
            await("PHONE_READY") { phone.port.get() >= 1024 && phone.coordinator.view.value != null }
            val bridge = Bridge(host, phone)
            polling = launch(Dispatchers.IO) { while (isActive) { bridge.round(); delay(500) } }
            await("SHARED_CARD") { phone.storage.loadEntity("character_cards", "pair-card", CharacterCard.serializer()) != null && settled(phone) }
            val phoneBefore = phone.access().node.sourceOperations()
            host.connects.set(0); phone.connects.set(0)

            phase = "forward"
            val forwardPng = png("synthetic-forward")
            val forwardPath = host.data.resolve("images/forward.png").also { Files.createDirectories(it.parent); Files.write(it, forwardPng) }
            val forwardHash = lanSyncHash(forwardPng)
            host.chats.createSession(ChatSession("pair-forward", "pair-card", "synthetic forward", createdAt = 10, updatedAt = 10))
            host.chats.addMessage(ChatMessage("forward-text", "pair-forward", MessageRole.ASSISTANT, "synthetic PC text", createdAt = 11, updatedAt = 11))
            host.chats.addMessage(ChatMessage("forward-image", "pair-forward", MessageRole.USER, "synthetic PC image", images = listOf(forwardPath.toString()), createdAt = 12, updatedAt = 12))
            val forward = listOf("forward-text" to "synthetic PC text", "forward-image" to "synthetic PC image")
            await("FORWARD_BODY_IMAGE") { assertConversation(phone, "pair-forward", forward, "forward-image", forwardHash) }
            assertEquals(phoneBefore, phone.access().node.sourceOperations(), "Remote application must not create a phone author echo")
            val hostBeforeReturn = host.access().node.sourceOperations()

            phase = "return"
            val returnPng = png("synthetic-return")
            val returnPath = phone.data.resolve("images/return.png").also { Files.createDirectories(it.parent); Files.write(it, returnPng) }
            val returnHash = lanSyncHash(returnPng)
            assertNotEquals(forwardHash, returnHash)
            phone.chats.createSession(ChatSession("pair-return", "pair-card", "synthetic return", createdAt = 20, updatedAt = 20))
            phone.chats.addMessage(ChatMessage("return-text", "pair-return", MessageRole.USER, "synthetic phone text", createdAt = 21, updatedAt = 21))
            phone.chats.addMessage(ChatMessage("return-image", "pair-return", MessageRole.ASSISTANT, "synthetic phone image", images = listOf(returnPath.toString()), createdAt = 22, updatedAt = 22))
            val returned = listOf("return-text" to "synthetic phone text", "return-image" to "synthetic phone image")
            await("RETURN_BODY_IMAGE") { assertConversation(host, "pair-return", returned, "return-image", returnHash) }
            assertEquals(hostBeforeReturn, host.access().node.sourceOperations(), "Remote application must not create a PC author echo")

            phase = "late-write-and-replay"
            assertEquals(2, phone.chats.getMessages("pair-forward").size) // Warm receiver repository index before late write.
            host.chats.addMessage(ChatMessage("forward-late", "pair-forward", MessageRole.ASSISTANT,
                "synthetic late PC reply", createdAt = 30, updatedAt = 30))
            val late = forward + ("forward-late" to "synthetic late PC reply")
            await("LATE_EXISTING_CONVERSATION") { assertConversation(phone, "pair-forward", late, "forward-image", forwardHash) }
            await("BOTH_SETTLED") { settled(host, setOf("pair-forward", "pair-return")) && settled(phone, setOf("pair-forward", "pair-return")) }
            val hostOperations = host.access().node.sourceOperations()
            val phoneOperations = phone.access().node.sourceOperations()
            val round = bridge.rounds.get()
            await("REPEATED_CATALOGS") { bridge.rounds.get() >= round + 6 }
            assertEquals(hostOperations, host.access().node.sourceOperations())
            assertEquals(phoneOperations, phone.access().node.sourceOperations())
            for (peer in peers) {
                assertTrue(assertConversation(peer, "pair-forward", late, "forward-image", forwardHash))
                assertTrue(assertConversation(peer, "pair-return", returned, "return-image", returnHash))
                assertTrue(peer.coordinator.cloudSettings.value.rows.isEmpty())
                assertNull(peer.coordinator.state.value.cloud.lastSuccessAt)
                assertEquals(0, peer.wrongPeer.get())
            }
            assertEquals(0, host.connects.get(), "Only the phone side initiates this explicit bridge")
            assertTrue(phone.connects.get() > 0 && host.accepts.get() > 0)
            for (operation in phoneOperations) assertNotNull(phone.access().node.receipt(operation, bridge.target))
            println("CCB_LIVE_PAIR_DRAFT_SCOPE=real-tcp-mutual-tls;explicit-test-catalog-and-upload;no-udp;no-windows-avd-proof;forward=3;return=2;png-forward=$forwardHash;png-return=$returnHash")
        } catch (_: Throwable) {
            throw AssertionError("SYNTHETIC_LIVE_PAIR_DRAFT_PHASE_FAILED:$phase")
        } finally {
            polling?.cancelAndJoin()
            peers.asReversed().forEach { it.close() }
            admin.close()
            check(root.parent == runner && root.fileName.toString().startsWith("synthetic-live-pair-"))
            check(root.toFile().deleteRecursively())
        }
    }
}
