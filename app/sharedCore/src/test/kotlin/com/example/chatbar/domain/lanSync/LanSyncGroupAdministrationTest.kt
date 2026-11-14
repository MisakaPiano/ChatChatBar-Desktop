package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** Actual managed founder, TLS/HPKE peer, root journal and local capsule delivery. Synthetic protectors. */
class LanSyncGroupAdministrationTest {
    private class Fixture(val root: Path) : AutoCloseable {
        val profile = Files.createDirectory(root.resolve("profile")); val data = Files.createDirectory(root.resolve("data"))
        val workspace = LanSyncWorkspace(data, Files.createDirectory(root.resolve("workspace")), { Path.of(it) })
        val identity = LanSyncTlsIdentity.create(); var hook: (String) -> Unit = {}; var gated = false
        val service = LanSyncGroupEnrollment(profile, workspace, object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) { check(!gated); gated = true; try { work() } finally { gated = false } }
            override suspend fun reload(sessionIds: Set<String>) { check(gated) }
        }, identity, { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> }, { hook(it) })
        lateinit var code: String; lateinit var node: LanSyncGroupNode
        val joiningIdentity = LanSyncTlsIdentity.create(); val joiningKey = SyncDeviceEncryptionKey.create(joiningIdentity.fingerprint)
        val invitations = mutableListOf<LanSyncGroupInvitation>()
        suspend fun seed() {
            val draft = service.prepare(SyncRootGroupCreateRequest(SyncStoreId.random(), LanSyncScope.schemas.keys, "a".repeat(64)), SyncRootGroupCreateAdmission {})
            code = draft.recoveryCode(); node = service.activate(draft, LanSyncGroupRecoverySavedAdmission {})
        }
        fun custody(): SyncGroupCustody {
            val record = lanSyncJson.parseToJsonElement(Files.readAllBytes(profile.resolve("profile.protected")).reversedArray().toString(Charsets.UTF_8)).jsonObject
            val recipient = SyncDeviceKeyCustody(profile.resolve("recipient"), identity.fingerprint, { it.reversedArray() }, { it.reversedArray() })
                .open(record.getValue("recipient").jsonPrimitive.content)
            return SyncGroupCustody(profile.resolve("custody"), SyncStoreId.parse(record.getValue("trust").jsonObject.getValue("group").jsonPrimitive.content),
                recipient, { it.reversedArray() }, { it.reversedArray() })
        }
        suspend fun prove(): Pair<LanSyncGroupInvitation, LanSyncGroupInvitedPeer> {
            val invitation = service.invitation().also(invitations::add)
            val executor = Executors.newSingleThreadExecutor()
            val listener = (invitation.invitationContext().serverSocketFactory.createServerSocket(0, 2, InetAddress.getLoopbackAddress()) as SSLServerSocket)
                .also { it.needClientAuth = true }
            try {
                val offer = invitation.issue("10.0.0.1", listener.localPort)
                val server = executor.submit<LanSyncGroupInvitedPeer> { invitation.serve(listener.accept() as SSLSocket) }
                val result = LanSyncGroupInviteClient.exchange(offer, joiningIdentity, joiningKey, "合成新手机", {
                    LanSyncTls.connect(joiningIdentity, offer.host, offer.port, offer.inviter, 15_000) { _, _ ->
                        Socket(InetAddress.getLoopbackAddress(), listener.localPort)
                    }
                })
                assertEquals(offer.trust, result.trust)
                return invitation to server.get(20, TimeUnit.SECONDS)
            } finally { listener.close(); executor.shutdownNow(); check(executor.awaitTermination(25, TimeUnit.SECONDS)) }
        }
        suspend fun rotateBeforeJoin() {
            val draft = service.resume(code); val session = draft.session; val custody = custody()
            val journal = SyncRootLedgerJournal(profile.resolve("root-actions"), requireNotNull(service.profile()).trust, session.ownerCertificate,
                session::protectJournal, session::unprotectJournal)
            val before = custody.ledger().current()
            val request = SyncRootLedgerRequest(SyncStoreId.random(), before.reference(), before.members, before.coveredTypes, before.acceptedHeads, true, "b".repeat(64))
            journal.applyToCustody(journal.commit(request, custody::ledger, SyncRootLedgerAdmission {}, session::signRootPayload), custody)
            service.resumeAdministration(draft)
        }
        fun verifyNewMemberKeys(peer: LanSyncGroupInvitedPeer) {
            val owner = custody(); val ledger = owner.ledger()
            val joined = SyncGroupCustody(Files.createDirectory(root.resolve("joined-custody")), peer.trust.group, joiningKey,
                { it.reversedArray() }, { it.reversedArray() })
            joined.enroll(peer.trust, ledger.history())
            val inbox = SyncGroupInbox(Files.createDirectory(root.resolve("joined-inbox")), FileCiphertextStore(profile.resolve("local-replica")), joined, joiningKey)
            var cursor: CommitCursor? = null
            do { cursor = inbox.discover(cursor, 1).nextCursor } while (cursor != null)
            assertFalse(inbox.reconcile().trustBlocked)
            ledger.history().map { it.epoch }.distinct().forEach { epoch ->
                assertEquals(owner.key(epoch).keyCommitment(), joined.key(epoch).keyCommitment())
            }
        }
        override fun close() { invitations.forEach { it.close() }; service.close() }
    }
    private fun withFixture(work: suspend (Fixture) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-group-administration-").toRealPath()
        try { Fixture(root).use { it.seed(); work(it) } } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun exactMemberPreviewAdmitsTheActualPeerAndDeliversAllHistoricalKeysBeforeAdminLocks() = withFixture { f ->
        f.rotateBeforeJoin(); val (invitation, peer) = f.prove(); val before = f.custody().ledger().current()
        val preview = f.service.prepareMember(peer, invitation)
        assertEquals("合成新手机", preview.name); assertEquals(f.joiningIdentity.fingerprint, preview.device)
        assertEquals(before.members + (peer.device to peer.member), preview.request.members)
        assertFailsWith<IllegalArgumentException> { preview.acknowledge("0".repeat(64)) }
        assertEquals(before.reference(), f.custody().ledger().current().reference())
        val draft = f.service.resume(f.code)
        val result = f.service.addMember(draft, preview.acknowledge(preview.request.approvalBinding))
        assertEquals(before.sequence + 1, result.ledger.sequence); assertEquals(peer.member, result.ledger.members[peer.device])
        assertFailsWith<IllegalStateException> { draft.recoveryCode() }
        f.verifyNewMemberKeys(peer)
        val card = f.data.resolve("entities/character_cards/card.json"); Files.createDirectories(card.parent)
        LanSyncJsonFiles.write(card, CharacterCard.serializer(), CharacterCard("card", "daily author after admin lock", createdAt = 1, updatedAt = 1))
        val view = f.node.inspect(); val source = f.node.commit(view.review(setOf("character_cards/card")))
        assertEquals(1L, source.entry.version.authorSequence); assertEquals(f.identity.fingerprint, source.entry.version.author)
        assertNotEquals(result.ledger.members.entries.first { it.value.certificate == requireNotNull(f.service.profile()).trust.ownerCertificate }.key, source.entry.version.author)
    }

    @Test fun approvedRootIntentSignedEntryAndLocalDeliveryLossResumeWithoutOldPeerProofOrAnotherRootRevision() {
        for (point in listOf("APPROVED_ROOT_INTENT_RETAINED", "ROOT_SIGNED_ENTRY_RETAINED", "ROOT_DELIVERY_SPEC_RETAINED")) withFixture { f ->
            val (invitation, peer) = f.prove(); val preview = f.service.prepareMember(peer, invitation); var interrupted = false
            f.hook = { if (it == point && !interrupted) { interrupted = true; throw IOException("SYNTHETIC_ROOT_MEMBER_REPLY_LOST") } }
            assertFailsWith<IOException> { f.service.addMember(f.service.resume(f.code), preview.acknowledge(preview.request.approvalBinding)) }
            assertTrue(interrupted); f.hook = {}; invitation.close(); f.service.lockManagement()
            val recovered = f.service.resumeAdministration(f.service.resume(f.code))
            assertEquals(2L, recovered.ledger.sequence); assertEquals(peer.member, recovered.ledger.members[peer.device])
            val history = f.custody().ledger().history()
            val repeat = f.service.resumeAdministration(f.service.resume(f.code))
            assertEquals(recovered.ledger, repeat.ledger); assertEquals(history, f.custody().ledger().history())
            f.verifyNewMemberKeys(peer)
        }
    }

    @Test fun changedProofOrBackgroundLockCannotCreateAnUnapprovedRootAction() {
        for (change in listOf("proof", "background")) withFixture { f ->
            val (invitation, peer) = f.prove(); val preview = f.service.prepareMember(peer, invitation)
            val draft = f.service.resume(f.code); val before = f.custody().ledger().current()
            if (change == "proof") invitation.close() else f.service.lockManagement()
            assertFails { f.service.addMember(draft, preview.acknowledge(preview.request.approvalBinding)) }
            assertEquals(before, f.custody().ledger().current())
            Files.newDirectoryStream(f.profile.resolve("root-actions"), "intent-*.protected").use { assertFalse(it.iterator().hasNext()) }
            assertEquals(LanSyncGroupNodeStage.READY, f.node.inspect().stage)
        }
    }
}
