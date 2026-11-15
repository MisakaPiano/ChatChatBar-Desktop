package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Real TLS/HPKE possession and root approval; the private display-file protector is synthetic. */
class LanSyncGroupPeerNamesTest {
    private fun fixture(work: suspend (LanSyncGroupAdministrationTest.Fixture) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-group-peer-names-").toRealPath()
        try { LanSyncGroupAdministrationTest.Fixture(root).use { it.seed(); work(it) } }
        finally { check(root.toFile().deleteRecursively()) }
    }
    private suspend fun prove(f: LanSyncGroupAdministrationTest.Fixture, key: SyncDeviceEncryptionKey, name: String): Pair<LanSyncGroupInvitation, LanSyncGroupInvitedPeer> {
        val invitation = f.service.invitation().also(f.invitations::add)
        val listener = (invitation.invitationContext().serverSocketFactory.createServerSocket(0, 2, InetAddress.getLoopbackAddress()) as SSLServerSocket)
            .also { it.needClientAuth = true }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val offer = invitation.issue("10.0.0.1", listener.localPort)
            val proof = pool.submit<LanSyncGroupInvitedPeer> { invitation.serve(listener.accept() as SSLSocket) }
            LanSyncGroupInviteClient.exchange(offer, f.joiningIdentity, key, name, {
                LanSyncTls.connect(f.joiningIdentity, offer.host, offer.port, offer.inviter, 15_000) { _, _ -> Socket(InetAddress.getLoopbackAddress(), listener.localPort) }
            })
            return invitation to proof.get(20, TimeUnit.SECONDS)
        } finally { listener.close(); pool.shutdownNow(); check(pool.awaitTermination(25, TimeUnit.SECONDS)) }
    }

    @Test fun authenticatedPendingNameRemainsHiddenUntilApprovalAndSurvivesRootReplyLossAndRuntimeReopen() = fixture { f ->
        val access = requireNotNull(f.service.runtimeAccess())
        val (invitation, peer) = f.prove()
        val preview = f.service.prepareMember(peer, invitation)
        access.rememberPeerName(peer)
        assertNull(access.peerDisplayName(peer.device))
        val file = access.work.resolve("peer-display.protected")
        assertTrue(Files.isRegularFile(file))
        assertFalse(Files.readAllBytes(file).toString(Charsets.UTF_8).contains(peer.name))
        var interrupted = false
        f.hook = { if (it == "ROOT_SIGNED_ENTRY_RETAINED" && !interrupted) { interrupted = true; throw IOException("SYNTHETIC_MEMBER_SIGNED_REPLY_LOST") } }
        assertFailsWith<IOException> { f.service.addMember(f.service.resume(f.code), preview.acknowledge(preview.request.approvalBinding)) }
        assertTrue(interrupted); f.hook = { }
        assertNull(access.peerDisplayName(peer.device))
        invitation.close()
        f.service.resumeAdministration(f.service.resume(f.code))
        assertEquals(peer.name, access.peerDisplayName(peer.device))
        assertEquals(peer.name, requireNotNull(f.service.runtimeAccess()).peerDisplayName(peer.device))
        assertTrue(access.node.sourceOperations().isEmpty())
    }

    @Test fun nameIsBoundToTheActualAdmittedRecipientAndCannotBeReplacedByAnOlderDifferentProof() = fixture { f ->
        val access = requireNotNull(f.service.runtimeAccess())
        val (firstInvitation, firstPeer) = f.prove()
        access.rememberPeerName(firstPeer)
        firstInvitation.close()
        val replacementKey = SyncDeviceEncryptionKey.create(f.joiningIdentity.fingerprint)
        val (secondInvitation, secondPeer) = prove(f, replacementKey, "真正批准的新手机")
        val preview = f.service.prepareMember(secondPeer, secondInvitation)
        f.service.addMember(f.service.resume(f.code), preview.acknowledge(preview.request.approvalBinding))
        assertNull(access.peerDisplayName(firstPeer.device))
        access.rememberPeerName(secondPeer)
        assertEquals(secondPeer.name, access.peerDisplayName(secondPeer.device))
        assertEquals("SYNC_GROUP_PEER_NAME_IDENTITY_CHANGED", assertFailsWith<IllegalArgumentException> { access.rememberPeerName(firstPeer) }.message)
        assertEquals(secondPeer.name, requireNotNull(f.service.runtimeAccess()).peerDisplayName(secondPeer.device))
    }

    @Test fun revokedIdentityKeepsItsVerifiedDisplayNameWithoutRegainingAuthority() = fixture { f ->
        val access = requireNotNull(f.service.runtimeAccess())
        val (invitation, peer) = f.prove()
        val preview = f.service.prepareMember(peer, invitation)
        access.rememberPeerName(peer)
        f.service.addMember(f.service.resume(f.code), preview.acknowledge(preview.request.approvalBinding))
        val revocation = f.service.prepareRevocation(peer.device)
        f.service.revokeDevice(f.service.resume(f.code), revocation.acknowledge(revocation.request.approvalBinding))
        assertFalse(access.ledger().current().members.getValue(peer.device).active)
        assertEquals(peer.name, access.peerDisplayName(peer.device))
        assertFalse(access.ledger().current().members.getValue(peer.device).active)
    }

    @Test fun wrongGroupAndProtectionFailureRefuseWithoutRewritingRetainedNamesOrLeakingBorrowedBuffers() = fixture { f ->
        val access = requireNotNull(f.service.runtimeAccess())
        val (invitation, peer) = f.prove()
        val preview = f.service.prepareMember(peer, invitation)
        access.rememberPeerName(peer)
        f.service.addMember(f.service.resume(f.code), preview.acknowledge(preview.request.approvalBinding))
        val file = access.work.resolve("peer-display.protected")
        val original = Files.readAllBytes(file)
        val wrong = LanSyncGroupPeerNames(access.work, access.trust.copy(group = SyncStoreId.random()), { access.ledger() }, { it.reversedArray() }, { it.reversedArray() })
        assertEquals("SYNC_GROUP_PEER_NAMES_GROUP_CHANGED", assertFailsWith<IllegalArgumentException> { wrong.displayName(peer.device) }.message)
        var borrowed: ByteArray? = null
        val brokenRead = LanSyncGroupPeerNames(access.work, access.trust, { access.ledger() }, { it.reversedArray() }, {
            borrowed = it; throw IOException("SYNTHETIC_NAME_UNPROTECT_FAILED")
        })
        assertEquals("SYNC_GROUP_PEER_NAMES_PROTECTION_FAILED", assertFailsWith<IllegalStateException> { brokenRead.displayName(peer.device) }.message)
        assertTrue(requireNotNull(borrowed).all { it == 0.toByte() })
        val fresh = Files.createDirectory(f.root.resolve("failed-name-write"))
        val brokenWrite = LanSyncGroupPeerNames(fresh, access.trust, { access.ledger() }, {
            borrowed = it; throw IOException("SYNTHETIC_NAME_PROTECT_FAILED")
        }, { it.reversedArray() })
        assertEquals("SYNC_GROUP_PEER_NAMES_PROTECTION_FAILED", assertFailsWith<IllegalStateException> { brokenWrite.remember(peer) }.message)
        assertTrue(requireNotNull(borrowed).all { it == 0.toByte() })
        assertFalse(Files.exists(fresh.resolve("peer-display.protected")))
        assertContentEquals(original, Files.readAllBytes(file))
        assertEquals(peer.name, access.peerDisplayName(peer.device))
    }
}
