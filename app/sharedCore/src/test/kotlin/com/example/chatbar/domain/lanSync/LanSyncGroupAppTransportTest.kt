package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
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

    /** Opaque transport evidence only: neither candidate is claimed to be an authenticated AEAD envelope. */
    @Test fun sameCommitIdExactDescriptorSurvivesActualTlsPutListGetAndColdTransportReopenWithoutReplacingPrimary() = fixture { f ->
        val key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random())
        val first = ByteArray(143 * 1024) { ((it * 31 + 7) % 253).toByte() }
        val second = first.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        fun descriptor(bytes: ByteArray) = CiphertextDescriptor(bytes.size.toLong(), CiphertextSha256.parse(lanSyncHash(bytes)))
        val firstDescriptor = descriptor(first)
        val secondDescriptor = descriptor(second)
        assertEquals(firstDescriptor.length, secondDescriptor.length)
        assertNotEquals(firstDescriptor.sha256, secondDescriptor.sha256)
        val firstMetadata = CiphertextObjectMetadata(key, firstDescriptor)
        val secondMetadata = CiphertextObjectMetadata(key, secondDescriptor)
        f.access.replica.put(CiphertextPutRequest(key, SyncStoreId.random(), firstDescriptor), first.inputStream())
        var port = f.transport.start()
        var remote = f.transport.remote(LanSyncPeer(f.identity.fingerprint, "registered synthetic sender", "10.0.0.1", port))
        assertEquals(firstMetadata, remote.put(CiphertextPutRequest(key, SyncStoreId.random(), firstDescriptor), first.inputStream()).metadata)
        val secondRequest = CiphertextPutRequest(key, SyncStoreId.random(), secondDescriptor)
        assertEquals(secondMetadata, remote.put(secondRequest, second.inputStream()).metadata)
        assertEquals(firstMetadata, remote.metadata(key), "An unselected lookup still names the retained primary")
        fun assertCandidates(store: CiphertextStore) {
            val candidates = store.listCommits(null, 100).entries.filter { it.key == key }
            assertEquals(setOf(firstMetadata, secondMetadata), candidates.toSet())
            assertEquals(2, candidates.size)
            val secondOutput = ByteArrayOutputStream()
            assertEquals(secondMetadata, store.readTo(key, secondOutput, secondDescriptor))
            assertContentEquals(second, secondOutput.toByteArray())
            val firstOutput = ByteArrayOutputStream()
            assertEquals(firstMetadata, store.readTo(key, firstOutput, firstDescriptor))
            assertContentEquals(first, firstOutput.toByteArray())
        }
        assertCandidates(remote)
        assertEquals(firstMetadata, f.access.replica.metadata(key))
        val primary = ByteArrayOutputStream()
        f.access.replica.readTo(key, primary, firstDescriptor)
        assertContentEquals(first, primary.toByteArray())
        f.transport.closeAndDrain()
        val work = f.access.work
        f.access = requireNotNull(f.enrollment.runtimeAccess())
        assertEquals(work, f.access.work)
        f.transport = LanSyncGroupAppTransport(f.access, f.network) { route, _ -> f.errors += route }
        port = f.transport.start()
        remote = f.transport.remote(LanSyncPeer(f.identity.fingerprint, "registered synthetic sender", "10.0.0.1", port))
        assertCandidates(remote)
        assertEquals(CiphertextPutDisposition.ALREADY_PRESENT, remote.put(secondRequest, second.inputStream()).disposition)
        assertEquals(CiphertextStoreFailure.OPERATION_CONFLICT, assertFailsWith<CiphertextStoreException> {
            remote.put(secondRequest.copy(descriptor = firstDescriptor), first.inputStream())
        }.failure)
        assertCandidates(remote)
        assertEquals(firstMetadata, f.access.replica.metadata(key))
        assertTrue(f.errors.isEmpty())
    }

    private fun assertPeerClosed(socket: Socket) {
        try {
            var bytes = 0
            while (socket.getInputStream().read() != -1) assertTrue(++bytes <= 1024, "Only bounded TLS shutdown bytes may precede EOF")
        }
        catch (_: java.net.SocketException) { /* An OS reset is also a closed transport. */ }
    }
}
