package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Actual joined daily profile, coordinator callbacks, invitation/store TLS and root management.
 * File routes and platform protection are synthetic; this is not native OS/UI acceptance. */
class LanSyncGroupCoordinatorManagementRecoveryTest {
    private val network = object : LanSyncNetwork {
        override fun addresses() = listOf("10.0.0.1")
        override fun accepts(address: InetAddress) = address.isLoopbackAddress
        override val discoveryEnabled = false
        override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket =
            LanSyncTls.connect(identity, peer.host, peer.port, peer.fingerprint, 15_000) { _, port -> Socket("127.0.0.1", port) }
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(25_000) { while (!condition()) delay(10) }
    private class Joined(val f: LanSyncGroupAdministrationTest.Fixture, network: LanSyncNetwork) {
        val root = Files.createDirectory(f.root.resolve("joined-profile"))
        val data = Files.createDirectory(f.root.resolve("joined-data"))
        val workspace = LanSyncWorkspace(data, Files.createDirectory(f.root.resolve("joined-workspace")), { Path.of(it) })
        private val gate = Mutex()
        private val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) = gate.withLock { work() }
            override suspend fun reload(sessionIds: Set<String>) { }
        }
        val joining = LanSyncGroupJoining(root, workspace, platform, f.joiningIdentity,
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> })
        private val founders = mutableListOf<LanSyncGroupEnrollment>()
        private fun founderAtJoinedProfile() = LanSyncGroupEnrollment(root, workspace, platform, f.joiningIdentity,
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> }).also(founders::add)
        val coordinator = LanSyncGroupCoordinator({ Files.isRegularFile(root.resolve("joining/profile.protected")) },
            ::founderAtJoinedProfile, { joining }, network, "synthetic surviving phone")
        lateinit var node: LanSyncGroupNode
        lateinit var trust: SyncGroupTrust
        suspend fun join() {
            val invitation = f.service.invitation(16384).also(f.invitations::add)
            val listener = (invitation.invitationContext().serverSocketFactory.createServerSocket(0, 2, InetAddress.getLoopbackAddress()) as SSLServerSocket)
                .also { it.needClientAuth = true }
            val pool = Executors.newSingleThreadExecutor()
            try {
                val offer = invitation.issue("10.0.0.1", listener.localPort)
                joining.prepare(offer.code()) { }
                val proof = pool.submit<LanSyncGroupInvitedPeer> { invitation.serve(listener.accept() as SSLSocket) }
                joining.prove("synthetic surviving phone") { current ->
                    LanSyncTls.connect(f.joiningIdentity, current.host, current.port, current.inviter, 15_000) { _, _ ->
                        Socket(InetAddress.getLoopbackAddress(), listener.localPort)
                    }
                }
                val preview = f.service.prepareMember(proof.get(20, TimeUnit.SECONDS), invitation)
                f.service.addMember(f.service.resume(f.code), preview.acknowledge(preview.request.approvalBinding))
                val activated = joining.activate(FileCiphertextStore(f.profile.resolve("local-replica")))
                assertEquals(LanSyncGroupJoinStage.READY, activated.profile.stage)
                trust = activated.profile.trust; node = requireNotNull(activated.node)
            } finally { listener.close(); pool.shutdownNow(); check(pool.awaitTermination(25, TimeUnit.SECONDS)) }
        }
        fun author() = SyncAuthorJournal(root.resolve("joining/node/author"), trust,
            Base64.getEncoder().encodeToString(f.joiningIdentity.certificate.encoded), { it.reversedArray() }, { it.reversedArray() })
        suspend fun close() { coordinator.closeAndDrain(); founders.forEach { it.close() } }
    }
    private fun rootState(root: Path, code: String, vault: SyncManagementVaultBlob): SyncRootLedgerState =
        SyncManagementSession.open(code, vault.bytes()).use { session ->
            SyncRootLedgerJournal(root.resolve("root-actions"), session.trust, session.ownerCertificate,
                session::protectJournal, session::unprotectJournal).state()
        }
    private fun prove(offer: LanSyncGroupInviteOffer, identity: LanSyncTlsIdentity, key: SyncDeviceEncryptionKey) =
        LanSyncGroupInviteClient.exchange(offer, identity, key, "synthetic newly approved tablet", {
            LanSyncTls.connect(identity, offer.host, offer.port, offer.inviter, 15_000) { _, port -> Socket(InetAddress.getLoopbackAddress(), port) }
        })

    @Test fun joinedCoordinatorRecoversOriginalManagementApprovesRealPeerAndPageLockCannotReplayOldApproval() = runBlocking {
        val root = Files.createTempDirectory("synthetic-coordinator-management-recovery-").toRealPath()
        try {
            LanSyncGroupAdministrationTest.Fixture(root).use { f ->
                f.seed(); f.rotateBeforeJoin()
                val joined = Joined(f, network)
                try {
                    joined.join()
                    val card = joined.data.resolve("entities/character_cards/retained.json"); Files.createDirectories(card.parent)
                    LanSyncJsonFiles.write(card, CharacterCard.serializer(), CharacterCard("retained", "existing daily original", createdAt = 1, updatedAt = 1))
                    val source = joined.node.commit(joined.node.inspect().review(setOf("character_cards/retained")))
                    val sourceOriginals = requireNotNull(joined.workspace.recovery.read(source.entry.request.sourceCheckpoint))
                    val raw = Files.readAllBytes(card)
                    val dailyOrigin = Files.readAllBytes(joined.root.resolve("joining/node/author/origin.protected"))
                    val nativeOrigin = Files.readAllBytes(joined.root.resolve("joining/node/native/origin.protected"))
                    val vault = joined.joining.encryptedManagementBackup()
                    val originalLedger = f.custody().ledger().current()
                    f.service.close(); Files.move(f.profile, f.root.resolve("unavailable-founder-profile"))
                    val c = joined.coordinator
                    c.startIfConfigured()
                    await { c.state.value.stage == LanSyncGroupPanelStage.READY && !c.state.value.busy && c.view.value != null }
                    assertNull(c.state.value.error)
                    assertFalse(c.state.value.localManagementAvailable)
                    assertTrue(c.state.value.managementRecoveryAvailable)
                    assertFalse(c.state.value.managementUnlocked)
                    c.recoverManagement(f.code)
                    await { c.state.value.managementUnlocked && c.state.value.managementCanInvite && !c.state.value.busy }
                    assertNull(c.state.value.error)
                    assertNull(c.state.value.recoveryCode)
                    val access = requireNotNull(joined.joining.runtimeAccess())
                    assertEquals(joined.trust.group, access.trust.group)
                    assertEquals(joined.trust.ownerCertificate, access.trust.ownerCertificate)
                    val managementRoot = access.work.resolve("recovered-management")
                    val managementOrigin = Files.readAllBytes(managementRoot.resolve("root-actions/origin.protected"))
                    assertEquals(0L, rootState(managementRoot, f.code, vault).completedSequence)

                    val newIdentity = LanSyncTlsIdentity.create()
                    val newKey = SyncDeviceEncryptionKey.create(newIdentity.fingerprint)
                    c.createInvitation()
                    await { c.state.value.invitation != null && !c.state.value.busy }
                    val firstOffer = requireNotNull(c.state.value.invitation)
                    assertEquals(f.joiningIdentity.fingerprint, firstOffer.inviter)
                    prove(firstOffer, newIdentity, newKey)
                    await { c.state.value.peerConfirmation?.fingerprint == newIdentity.fingerprint && !c.state.value.busy }
                    val staleBinding = requireNotNull(c.state.value.peerConfirmation).binding
                    c.lockManagement()
                    assertFalse(c.state.value.managementUnlocked)
                    assertFalse(c.state.value.managementCanInvite)
                    assertNull(c.state.value.peerConfirmation)
                    assertNull(c.state.value.invitation)
                    assertNull(c.state.value.recoveryCode)
                    c.acceptPeer(staleBinding)
                    await { !c.state.value.busy && c.state.value.error?.endsWith("SYNC_GROUP_MEMBER_CONFIRMATION_REQUIRED") == true }
                    assertEquals(originalLedger, access.ledger().current())
                    assertFalse(newIdentity.fingerprint in access.ledger().current().members)
                    assertEquals(0L, rootState(managementRoot, f.code, vault).completedSequence)

                    c.recoverManagement(f.code)
                    await { c.state.value.managementUnlocked && c.state.value.managementCanInvite && !c.state.value.busy }
                    assertNull(c.state.value.error)
                    assertContentEquals(managementOrigin, Files.readAllBytes(managementRoot.resolve("root-actions/origin.protected")))
                    c.createInvitation()
                    await { c.state.value.invitation != null && !c.state.value.busy }
                    val offer = requireNotNull(c.state.value.invitation)
                    assertNotEquals(firstOffer.token, offer.token)
                    prove(offer, newIdentity, newKey)
                    await { c.state.value.peerConfirmation?.fingerprint == newIdentity.fingerprint && !c.state.value.busy }
                    val confirmation = requireNotNull(c.state.value.peerConfirmation)
                    assertEquals("synthetic newly approved tablet", confirmation.name)
                    c.acceptPeer(confirmation.binding)
                    await { !c.state.value.busy && c.state.value.devices.any { it.id == newIdentity.fingerprint } &&
                        c.view.value?.reconciliation?.ledgerCheckpoint != originalLedger.reference() }
                    assertNull(c.state.value.error)
                    val admitted = access.ledger().current()
                    assertEquals(originalLedger.reference(), admitted.parent)
                    assertEquals(originalLedger.sequence + 1, admitted.sequence)
                    assertEquals(originalLedger.epoch, admitted.epoch)
                    assertTrue(admitted.members.getValue(newIdentity.fingerprint).active)
                    assertEquals(newKey.publicKey.encoded, admitted.members.getValue(newIdentity.fingerprint).recipientKey)
                    assertEquals("synthetic newly approved tablet", c.state.value.devices.single { it.id == newIdentity.fingerprint }.name)
                    assertFalse(c.state.value.managementUnlocked)
                    assertNull(c.state.value.recoveryCode)
                    assertEquals(1L, rootState(managementRoot, f.code, vault).completedSequence)
                    assertContentEquals(managementOrigin, Files.readAllBytes(managementRoot.resolve("root-actions/origin.protected")))

                    // Read-only enrollment evidence comes through the coordinator's ordinary app TLS listener.
                    val remote = LanSyncGroupRemoteStore(offer.trust.group, Files.createDirectory(root.resolve("new-peer-route-cache")), {
                        LanSyncTls.connect(newIdentity, offer.host, requireNotNull(offer.bootstrap).storePort, offer.inviter, 15_000) { _, port ->
                            Socket(InetAddress.getLoopbackAddress(), port)
                        }
                    }, authenticatedConnect = true)
                    val recipient = SyncGroupCustody(Files.createDirectory(root.resolve("new-peer-custody")), joined.trust.group,
                        newKey, { it.reversedArray() }, { it.reversedArray() })
                    val inbox = SyncGroupInbox(Files.createDirectory(root.resolve("new-peer-inbox")), remote, recipient, newKey)
                    var cursor: CommitCursor? = null
                    do { cursor = inbox.discover(cursor, 2).nextCursor } while (cursor != null)
                    val evidence = inbox.prepareEnrollment(offer.trust)
                    assertEquals(SyncGroupBootstrapStage.READY, evidence.stage)
                    assertEquals(admitted, requireNotNull(evidence.evidence).records.last())
                    assertFalse(Files.exists(root.resolve("new-peer-custody/group.protected")))

                    c.recoverManagement(f.code)
                    await { c.state.value.managementUnlocked && !c.state.value.busy }
                    assertNull(c.state.value.error)
                    c.lockManagement()
                    c.acceptPeer(confirmation.binding)
                    await { !c.state.value.busy && c.state.value.error?.endsWith("SYNC_GROUP_MEMBER_CONFIRMATION_REQUIRED") == true }
                    assertEquals(admitted, access.ledger().current())
                    assertEquals(1L, rootState(managementRoot, f.code, vault).completedSequence)
                    assertContentEquals(managementOrigin, Files.readAllBytes(managementRoot.resolve("root-actions/origin.protected")))
                    assertContentEquals(dailyOrigin, Files.readAllBytes(joined.root.resolve("joining/node/author/origin.protected")))
                    assertContentEquals(nativeOrigin, Files.readAllBytes(joined.root.resolve("joining/node/native/origin.protected")))
                    assertContentEquals(raw, Files.readAllBytes(card))
                    assertEquals(1L, joined.author().state().committedSequence)
                    assertEquals(source.entry, joined.author().entry(source.entry.request.operation))
                    joined.workspace.recovery.verify(sourceOriginals)
                    assertFalse(c.state.value.managementUnlocked)
                    assertFalse(c.state.value.managementCanInvite)
                    assertNull(c.state.value.recoveryCode)
                } finally { joined.close() }
            }
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
