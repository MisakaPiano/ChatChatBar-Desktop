package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.domain.syncStore.CommitCursor
import com.example.chatbar.domain.syncStore.SyncRootGroupCreateAdmission
import com.example.chatbar.domain.syncStore.SyncRootGroupCreateRequest
import com.example.chatbar.domain.syncStore.SyncStoreId
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.*
import org.junit.Assume.assumeTrue

class DesktopLanSyncReloadRecoveryTest {
    @Test
    fun committedSignedReceiveRefreshesTheOpenDesktopChatAndPreservesDraft() {
        assumeTrue("Opt-in Windows native reload proof",
            System.getProperty("os.name").startsWith("Windows") && System.getenv("CCB_DESKTOP_NATIVE_RELOAD_PROOF") == "true")
        val root = Files.createTempDirectory("ccb-native-reload-").toRealPath()
        try {
            val local = Files.createDirectory(root.resolve("isolated-local"))
            val classpath = requireNotNull(System.getProperty("ccb.readonly.classpath"))
            for (phase in listOf("receive", "reopen")) {
                val log = root.resolve("$phase.log")
                val launcher = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                    "-Dfile.encoding=UTF-8", "-cp", classpath, DesktopLanSyncReloadRecoveryChild::class.java.name,
                    root.toString(), phase).redirectErrorStream(true).redirectOutput(log.toFile())
                launcher.environment()["LOCALAPPDATA"] = local.toString()
                val child = launcher.start()
                child.outputStream.close()
                try {
                    assertTrue(child.waitFor(150, TimeUnit.SECONDS), "Synthetic Desktop reload process timed out: $phase")
                    val result = Files.readString(log)
                    assertEquals(0, child.exitValue(), result.takeLast(3000))
                    assertTrue(result.contains("DESKTOP_NATIVE_RELOAD_${phase.uppercase()}_PASS"))
                } finally {
                    if (child.isAlive) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                }
            }
        } finally {
            check(root.toFile().deleteRecursively())
        }
    }
}

object DesktopLanSyncReloadRecoveryChild {
    private const val session = "synthetic-session"
    private const val card = "synthetic-card"
    private const val baseMessage = "baseline-message"
    private const val remoteMessage = "signed-new-message"
    private const val finalDraft = "Synthetic local edit made after the remote commit"
    private const val remoteBody = "Synthetic signed message received on Windows"

    private val loopback = object : LanSyncNetwork {
        override val discoveryEnabled = false
        override fun addresses() = listOf("10.0.0.1")
        override fun accepts(address: InetAddress) = address.isLoopbackAddress
        override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer) = connectOwned(identity, peer) {}
        override fun connectOwned(identity: LanSyncTlsIdentity, peer: LanSyncPeer, own: (Socket) -> Unit): SSLSocket {
            require(peer.host == addresses().single())
            val plain = Socket()
            try {
                own(plain)
                plain.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), peer.port), 5_000)
                val pinned = identity.context { it == peer.fingerprint }
                val socket = pinned.socketFactory.createSocket(plain, peer.host, peer.port, true) as SSLSocket
                own(socket)
                LanSyncTls.configure(socket)
                socket.soTimeout = 5_000
                socket.startHandshake()
                check(LanSyncTls.peerFingerprint(socket) == peer.fingerprint)
                return socket
            } catch (error: Throwable) {
                plain.close()
                throw error
            }
        }
    }

    private fun container(data: Path) = DesktopAppContainer(
        DesktopDataRootResolution.Resolved(data, DesktopDataRootProvenance.CLI_OVERRIDE, data.parent.resolve("bootstrap.json")),
        secretStoreFactory = { InMemoryDesktopSecretStore() })

    // DesktopLanSync's real platform forwards this callback inside the native gate. The test
    // wraps the same chat refresh steps as DesktopAppContainer, with one injected failure.
    private fun adapter(app: DesktopAppContainer, beforeReload: (Set<String>) -> Unit = {}) = DesktopLanSync(app,
        exclusive = { work ->
            app.primaryChatController.flushComposerForSnapshot()
            app.taskRuntime.withIdleMaintenance { app.dataOperationCoordinator.withExclusiveMaintenance { work() } }
        },
        reload = { sessions ->
            beforeReload(sessions)
            app.characterRepository.refreshFromStorage()
            app.chatRepository.reloadAfterLanSync()
            app.primaryChatController.refresh()
        },
        resolve = { app.appDataRoot.resolve(it) },
        asset = { error("SYNTHETIC_RELOAD_FIXTURE_HAS_NO_ASSETS") })

    private suspend fun publish(access: LanSyncGroupRuntimeAccess): LanSyncGroupSourceCommit {
        val view = access.node.inspect()
        val selected = requireNotNull(view.plan).units.filterValues { it.stage == LanSyncGroupNativeStage.LOCAL_PROPOSAL }.keys
        check("conversation/$session" in selected)
        val review = view.review(selected)
        return access.node.commit(review, review.requiredBindings).also {
            access.node.upload(it.entry.request.operation, access.replica, access.localTarget)
        }
    }

    private suspend fun receive(root: Path) {
        val app = container(Files.createDirectory(root.resolve("receiver")))
        val source = container(Files.createDirectory(root.resolve("publisher")))
        val inject = AtomicBoolean()
        val attempts = AtomicInteger()
        val native = adapter(app) { sessions ->
            if (inject.get()) {
                check(session in sessions)
                if (attempts.incrementAndGet() == 1) throw IOException("SYNTHETIC_DESKTOP_CHAT_RELOAD_REFUSED")
            }
        }
        val publisher = adapter(source)
        val publisherJoin = LanSyncGroupCoordinator(publisher::hasGroupEvidence, { publisher.groupEnrollment },
            { publisher.groupJoining }, loopback, "Synthetic publishing device")
        try {
            app.characterRepository.save(CharacterCard(card, "Synthetic chat character", createdAt = 1, updatedAt = 1))
            app.chatRepository.createSession(ChatSession(session, card, "Synthetic open chat", createdAt = 1, updatedAt = 1))
            app.chatRepository.addMessage(ChatMessage(baseMessage, session, MessageRole.USER, "Synthetic baseline", createdAt = 2, updatedAt = 2))
            val chat = app.primaryChatController
            chat.selectSession(session)
            chat.editComposer("Synthetic draft present before reception")
            check(chat.state.value.messages.map { it.id } == listOf(baseMessage))

            val owner = native.groupEnrollment
            val draft = owner.prepare(SyncRootGroupCreateRequest(SyncStoreId.random(), LanSyncScope.schemas.keys,
                "a".repeat(64)), SyncRootGroupCreateAdmission {})
            val code = draft.recoveryCode()
            owner.activate(draft, LanSyncGroupRecoverySavedAdmission {})
            val receiver = requireNotNull(owner.runtimeAccess())
            publish(receiver)

            val receivingTransport = LanSyncGroupAppTransport(receiver, loopback) { _, _ -> }
            val raw = app.appDataRoot.resolve("entities/chat_messages/${session}_${remoteMessage}.json")
            val revision = app.chatRepository.lanSyncRevision.value
            val committed = try {
                val invitation = owner.invitation(receivingTransport.start())
                val proof = CompletableDeferred<LanSyncGroupInvitedPeer>()
                val offer = receivingTransport.invitation(invitation) { proof.complete(it) }
                publisherJoin.beginJoin(offer.code())
                val preview = owner.prepareMember(withTimeout(15_000) { proof.await() }, invitation)
                owner.addMember(owner.resume(code), preview.acknowledge(preview.request.approvalBinding))
                withTimeout(25_000) {
                    while (publisherJoin.state.value.busy) delay(25)
                    if (publisherJoin.state.value.stage != LanSyncGroupPanelStage.READY) publisherJoin.retryJoin()
                    while (publisherJoin.state.value.stage != LanSyncGroupPanelStage.READY || publisherJoin.state.value.busy) delay(25)
                }
                publisherJoin.pauseAndDrain()
                assertEquals(listOf(baseMessage), source.chatRepository.getMessages(session).map { it.id })
                source.chatRepository.addMessage(ChatMessage(remoteMessage, session, MessageRole.USER, remoteBody, createdAt = 3, updatedAt = 3))
                val sendingAccess = requireNotNull(publisher.groupJoining.runtimeAccess())
                val update = publish(sendingAccess)
                val sendingTransport = LanSyncGroupAppTransport(sendingAccess, loopback) { _, _ -> }
                try {
                    val endpoint = LanSyncPeer(sendingAccess.device, "Synthetic publishing device", loopback.addresses().single(),
                        sendingTransport.start())
                    val remote = receivingTransport.remote(endpoint)
                    receiver.reader.routes("nearby", listOf(LanSyncGroupReplicaReader.Route(sendingAccess.device, remote)))
                    var cursor: CommitCursor? = null
                    do { cursor = receiver.node.discover(receiver.reader.discoveryFrom(remote), cursor).nextCursor } while (cursor != null)
                    val before = receiver.node.inspect()
                    val plan = requireNotNull(before.plan)
                    check("conversation/$session" in plan.advances)
                    inject.set(true)
                    val failed = assertFailsWith<IllegalStateException> { receiver.node.apply(plan) }
                    assertEquals("SYNC_GROUP_NATIVE_RELOAD_PENDING", failed.message)
                    assertTrue(generateSequence<Throwable>(failed) { it.cause }.any {
                        it is IOException && it.message == "SYNTHETIC_DESKTOP_CHAT_RELOAD_REFUSED"
                    })
                    assertEquals(1, attempts.get())
                    assertEquals(revision, app.chatRepository.lanSyncRevision.value)
                    assertEquals(listOf(baseMessage), chat.state.value.messages.map { it.id })
                    assertEquals(remoteBody, Json.decodeFromString(ChatMessage.serializer(), Files.readString(raw)).content)
                    receiver.node.inspectLocal(recoverNative = false).nativeState.also {
                        assertEquals(before.nativeState.completedSequence + 1, it.completedSequence)
                        assertNull(it.pendingTransaction)
                        assertEquals(setOf(update.entry.version.reference()), it.units.getValue("conversation/$session").frontiers)
                    }
                } finally { sendingTransport.closeAndDrain() }
            } finally { receivingTransport.closeAndDrain() }

            val bytes = Files.readAllBytes(raw)
            val modified = Files.getLastModifiedTime(raw)
            chat.editComposer(finalDraft)
            native.onForeground()
            withTimeout(25_000) {
                while (native.groupCoordinator.view.value?.nativeState != committed ||
                    chat.state.value.messages.map { it.id } != listOf(baseMessage, remoteMessage)) delay(25)
            }
            assertEquals(2, attempts.get())
            assertEquals(revision + 1, app.chatRepository.lanSyncRevision.value)
            assertEquals(finalDraft, chat.state.value.composerDraft)
            assertEquals(listOf(baseMessage, remoteMessage), app.chatRepository.getMessages(session).map { it.id })
            assertContentEquals(bytes, Files.readAllBytes(raw))
            assertEquals(modified, Files.getLastModifiedTime(raw))
        } finally {
            native.closeAndDrain()
            publisherJoin.closeAndDrain()
            publisher.closeAndDrain()
            app.close()
            source.close()
        }
    }

    private suspend fun reopen(root: Path) {
        val data = root.resolve("receiver")
        DesktopLanSyncStartup.recover(data)
        val app = container(data)
        try {
            app.primaryChatController.selectSession(session)
            val state = app.primaryChatController.state.value
            assertEquals(listOf(baseMessage, remoteMessage), state.messages.map { it.id })
            assertEquals(remoteBody, state.messages.last().content)
            assertEquals(finalDraft, state.composerDraft)
        } finally { app.close() }
    }

    @JvmStatic fun main(args: Array<String>) = runBlocking {
        check(System.getenv("CCB_DESKTOP_NATIVE_RELOAD_PROOF") == "true")
        val root = Path.of(args[0]).toRealPath()
        check(root.fileName.toString().startsWith("ccb-native-reload-"))
        check(Path.of(System.getenv("LOCALAPPDATA")).toRealPath() == root.resolve("isolated-local").toRealPath())
        val phase = args[1]
        when (phase) {
            "receive" -> receive(root)
            "reopen" -> reopen(root)
            else -> error("SYNTHETIC_UNKNOWN_RELOAD_PHASE")
        }
        println("DESKTOP_NATIVE_RELOAD_${phase.uppercase()}_PASS")
    }
}
