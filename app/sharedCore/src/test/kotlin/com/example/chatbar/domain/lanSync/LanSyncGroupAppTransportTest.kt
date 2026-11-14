package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Real TCP/mutual TLS and explicit synthetic enrollment; protection is synthetic, never OS proof. */
class LanSyncGroupAppTransportTest {
    private class Fixture(val root: Path) {
        val identity = LanSyncTlsIdentity.create()
        val accepted = AtomicInteger()
        val errors = CopyOnWriteArrayList<String>()
        val network = object : LanSyncNetwork {
            override fun addresses() = listOf("10.0.0.1")
            override fun accepts(address: InetAddress): Boolean { accepted.incrementAndGet(); return address.isLoopbackAddress }
            override val discoveryEnabled = false
            override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket =
                LanSyncTls.connect(identity, peer.host, peer.port, peer.fingerprint, 15_000) { _, port -> Socket("127.0.0.1", port) }
        }
        private val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) = work()
            override suspend fun reload(sessionIds: Set<String>) { }
        }
        val enrollment = LanSyncGroupEnrollment(Files.createDirectory(root.resolve("profile")),
            LanSyncWorkspace(Files.createDirectory(root.resolve("data")), Files.createDirectory(root.resolve("workspace")), { Path.of(it) }),
            platform, identity, { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> })
        lateinit var access: LanSyncGroupRuntimeAccess
        lateinit var transport: LanSyncGroupAppTransport
        suspend fun initialize() {
            val draft = enrollment.prepare(SyncRootGroupCreateRequest(SyncStoreId.random(), LanSyncScope.schemas.keys, "a".repeat(64)),
                SyncRootGroupCreateAdmission { })
            enrollment.activate(draft, LanSyncGroupRecoverySavedAdmission { })
            access = requireNotNull(enrollment.runtimeAccess())
            transport = LanSyncGroupAppTransport(access, network) { route, _ -> errors += route }
        }
        suspend fun waitAccepted(count: Int) = withTimeout(5_000) { while (accepted.get() < count) delay(10) }
        suspend fun close() { if (::transport.isInitialized) transport.closeAndDrain(); enrollment.close() }
    }
    private fun fixture(work: suspend (Fixture) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-group-app-transport-").toRealPath()
        val f = Fixture(root)
        try { f.initialize(); work(f) }
        finally { f.close(); check(root.toFile().deleteRecursively()) }
    }

    @Test fun actualKnownMutualTlsUsesStableMainPortAndShutdownRejectsFurtherWork() = fixture { f ->
        val port = f.transport.start()
        assertEquals(port, f.transport.start())
        val remote = f.transport.remote(LanSyncPeer(f.identity.fingerprint, "same registered synthetic identity", "10.0.0.1", port))
        assertEquals(f.access.replica.metadata(f.access.vault.key), remote.metadata(f.access.vault.key))
        assertTrue(remote.listCommits(null).entries.isNotEmpty())
        assertTrue(f.errors.isEmpty())
        f.transport.closeAndDrain()
        assertFailsWith<IllegalStateException> { f.transport.start() }
        assertFailsWith<IllegalStateException> { f.transport.remote(LanSyncPeer(f.identity.fingerprint, "same", "10.0.0.1", port)) }
        assertFails { remote.metadata(f.access.vault.key) }
        assertFails { Socket("127.0.0.1", port).close() }
    }

    @Test fun invitationAndOrdinaryStalledSocketsHaveIndependentLifetimeAndAppCloseDrainsBoth() = fixture { f ->
        val port = f.transport.start()
        val invitation = f.enrollment.invitation(port)
        val offer = f.transport.invitation(invitation) { error("SYNTHETIC_NO_PROOF_SENT") }
        assertNotEquals(port, offer.port)
        Socket("127.0.0.1", port).use { ordinary ->
            Socket("127.0.0.1", offer.port).use { invite ->
                f.waitAccepted(2)
                f.transport.closeInvitation()
                invite.soTimeout = 5_000
                assertPeerClosed(invite)
                ordinary.soTimeout = 100
                assertFailsWith<SocketTimeoutException> { ordinary.getInputStream().read() }
                f.transport.closeAndDrain()
                ordinary.soTimeout = 5_000
                assertPeerClosed(ordinary)
                assertFails { Socket("127.0.0.1", offer.port).close() }
            }
        }
    }

    private fun assertPeerClosed(socket: Socket) {
        try {
            var bytes = 0
            while (socket.getInputStream().read() != -1) assertTrue(++bytes <= 1024, "Only bounded TLS shutdown bytes may precede EOF")
        }
        catch (_: java.net.SocketException) { /* An OS reset is also a closed transport. */ }
    }
}
