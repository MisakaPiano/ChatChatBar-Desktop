package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
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
import kotlin.test.*

/** Actual founder/joining TLS, HPKE and management vault over independent roots; OS protection is synthetic. */
class LanSyncGroupManagementRecoveryTest {
    private class Joined(val founder: LanSyncGroupAdministrationTest.Fixture) {
        val root = Files.createDirectory(founder.root.resolve("joined-profile"))
        val data = Files.createDirectory(founder.root.resolve("joined-data"))
        val workspace = LanSyncWorkspace(data, Files.createDirectory(founder.root.resolve("joined-workspace")), { Path.of(it) })
        private var gated = false
        val owner = LanSyncGroupJoining(root, workspace, object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) { check(!gated); gated = true; try { work() } finally { gated = false } }
            override suspend fun reload(sessionIds: Set<String>) { check(gated) }
        }, founder.joiningIdentity, { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> })
        lateinit var node: LanSyncGroupNode
        lateinit var trust: SyncGroupTrust
        private val replicaStore by lazy {
            LanSyncGroupReplicaReader(FileCiphertextStore(root.resolve("joining/bootstrap/ciphertext-mirror")),
                root.resolve("joining/received-ciphertext"), root.resolve("joining/commit-candidates")) { _, failure -> throw failure }
        }
        fun replica(): CiphertextStore = replicaStore
        suspend fun join() {
            val invitation = founder.service.invitation(16384).also(founder.invitations::add)
            val listener = (invitation.invitationContext().serverSocketFactory.createServerSocket(0, 2, InetAddress.getLoopbackAddress()) as SSLServerSocket)
                .also { it.needClientAuth = true }
            val pool = Executors.newSingleThreadExecutor()
            try {
                val offer = invitation.issue("10.0.0.1", listener.localPort)
                owner.prepare(offer.code()) { }
                val peer = pool.submit<LanSyncGroupInvitedPeer> { invitation.serve(listener.accept() as SSLSocket) }
                owner.prove("synthetic surviving phone") { current ->
                    LanSyncTls.connect(founder.joiningIdentity, current.host, current.port, current.inviter, 15_000) { _, _ ->
                        Socket(InetAddress.getLoopbackAddress(), listener.localPort)
                    }
                }
                val preview = founder.service.prepareMember(peer.get(20, TimeUnit.SECONDS), invitation)
                founder.service.addMember(founder.service.resume(founder.code), preview.acknowledge(preview.request.approvalBinding))
                val result = owner.activate(FileCiphertextStore(founder.profile.resolve("local-replica")))
                assertEquals(LanSyncGroupJoinStage.READY, result.profile.stage)
                node = requireNotNull(result.node)
                trust = result.profile.trust.copy(checkpoint = founder.custody().ledger().current().reference())
            } finally { listener.close(); pool.shutdownNow(); check(pool.awaitTermination(25, TimeUnit.SECONDS)) }
        }
        suspend fun scan(): LanSyncGroupNodeView {
            var cursor: CommitCursor? = null
            do { cursor = node.discover(replica(), cursor).nextCursor } while (cursor != null)
            return node.inspect()
        }
    }
    private fun fixture(work: suspend (LanSyncGroupAdministrationTest.Fixture, Joined) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-management-recovery-").toRealPath()
        try {
            LanSyncGroupAdministrationTest.Fixture(root).use { founder ->
                founder.seed(); founder.rotateBeforeJoin()
                val joined = Joined(founder); joined.join()
                work(founder, joined)
            }
        } finally { check(root.toFile().deleteRecursively()) }
    }
    private fun allFiles(root: Path): Map<String, ByteArray> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate { root.relativize(it).toString() to Files.readAllBytes(it) }
    }
    private fun assertNoCode(root: Path, code: String) {
        allFiles(root).forEach { (name, bytes) -> assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(code), name) }
    }

    @Test fun joinedDeviceRecoversCodeAndVaultWithoutFounderThenRevokesLostFounderPreservingDailyOrigins() = fixture { f, joined ->
        val card = joined.data.resolve("entities/character_cards/offline.json"); Files.createDirectories(card.parent)
        LanSyncJsonFiles.write(card, CharacterCard.serializer(), CharacterCard("offline", "surviving daily original", createdAt = 1, updatedAt = 1))
        val committed = joined.node.commit(joined.node.inspect().review(setOf("character_cards/offline")))
        val sourceOperation = committed.entry.request.operation
        val authorOrigin = Files.readAllBytes(joined.root.resolve("joining/node/author/origin.protected"))
        val nativeOrigin = Files.readAllBytes(joined.root.resolve("joining/node/native/origin.protected"))
        val original = Files.readAllBytes(card)
        val vault = joined.owner.encryptedManagementBackup()
        val before = f.custody().ledger().current()
        f.service.close()
        Files.move(f.profile, f.root.resolve("unavailable-founder-profile"))
        val managementRoot = Files.createDirectory(f.root.resolve("surviving-device-management"))
        var management = LanSyncGroupManagementRecovery(managementRoot)
        try {
            val recovered = management.recover(f.code, vault.bytes(), joined.replica(), LanSyncManagementRecoveryAdmission {
                assertEquals(joined.trust.group, it.group); assertEquals(joined.trust.genesis, it.genesis); assertEquals(joined.trust.ownerCertificate, it.ownerCertificate)
            }, knownTrust = joined.trust)
            assertEquals(LanSyncManagementRecoveryStage.READY, recovered.stage)
            assertEquals(joined.trust.group, recovered.trust.group)
            val origin = Files.readAllBytes(managementRoot.resolve("root-actions/origin.protected"))
            val preview = management.prepareRevocation(f.identity.fingerprint)
            assertEquals(before.reference(), preview.request.expectedParent)
            assertFailsWith<IllegalArgumentException> { preview.acknowledge("0".repeat(64)) }
            val revoked = management.revokeDevice(preview.acknowledge(preview.request.approvalBinding))
            assertEquals(before.sequence + 1, revoked.ledger.sequence)
            assertNotEquals(before.epoch, revoked.ledger.epoch)
            assertFalse(revoked.ledger.members.getValue(f.identity.fingerprint).active)
            assertTrue(revoked.ledger.members.getValue(f.joiningIdentity.fingerprint).active)
            val shared = management.publish(joined.replica(), requireNotNull(joined.owner.profile()).localReplica)
            assertEquals(revoked.ledger, shared.ledger)
            assertEquals(1L, shared.localDelivery.revocationNoticeCount)
            assertEquals(LanSyncGroupNodeStage.READY, joined.scan().stage)
            assertEquals(listOf(sourceOperation), joined.node.sourceOperations())
            assertEquals(committed.entry.version.reference(), requireNotNull(joined.node.inspect().reconciliation.history).heads().single())
            assertContentEquals(authorOrigin, Files.readAllBytes(joined.root.resolve("joining/node/author/origin.protected")))
            assertContentEquals(nativeOrigin, Files.readAllBytes(joined.root.resolve("joining/node/native/origin.protected")))
            assertContentEquals(original, Files.readAllBytes(card))
            assertFalse(Files.exists(managementRoot.resolve("node")))
            assertFalse(Files.exists(managementRoot.resolve("recipient")))
            assertFalse(Files.exists(managementRoot.resolve("creation")))
            management.close(); management = LanSyncGroupManagementRecovery(managementRoot)
            assertEquals(LanSyncManagementRecoveryStage.READY, management.unlock(f.code).stage)
            assertEquals(revoked.ledger, management.resumeAdministration().ledger)
            assertContentEquals(origin, Files.readAllBytes(managementRoot.resolve("root-actions/origin.protected")))
            assertNoCode(managementRoot, f.code)
        } finally { management.close() }
    }

    @Test fun wrongVaultAndRejectedAdmissionCreateNoAuthorityWhileMissingHistoricalKeyImportResumesExactly() = fixture { f, joined ->
        val vault = joined.owner.encryptedManagementBackup()
        val managementRoot = Files.createDirectory(f.root.resolve("fresh-management"))
        var owner = LanSyncGroupManagementRecovery(managementRoot)
        try {
            val tampered = vault.bytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            assertFailsWith<IllegalArgumentException> { owner.recover(f.code, tampered, joined.replica(), LanSyncManagementRecoveryAdmission { error("MUST_NOT_ADMIT") }) }
            assertFailsWith<IllegalArgumentException> { owner.recover(f.code, byteArrayOf(), joined.replica(), LanSyncManagementRecoveryAdmission { error("MUST_NOT_ADMIT") }) }
            SyncManagementSession.create().use { wrong ->
                wrong.seal(SyncGroupTrust(SyncStoreId.random(), SyncVersionRef(SyncStoreId.random(), "a".repeat(64)), wrong.ownerCertificate))
                assertFailsWith<IllegalArgumentException> { owner.recover(wrong.recoveryCode(), vault.bytes(), joined.replica(), LanSyncManagementRecoveryAdmission { error("MUST_NOT_ADMIT") }) }
            }
            assertFailsWith<IOException> { owner.recover(f.code, vault.bytes(), joined.replica(), LanSyncManagementRecoveryAdmission { throw IOException("SYNTHETIC_IMPORT_NOT_APPROVED") }) }
            Files.newDirectoryStream(managementRoot).use { assertFalse(it.iterator().hasNext()) }
            val source = joined.replica()
            val hidden = linkedSetOf<CiphertextObjectKey>()
            val originalEpoch = f.custody().ledger().history().first().epoch
            var cursor: CommitCursor? = null
            do {
                val page = source.listCommits(cursor)
                page.entries.forEach { metadata ->
                    val bytes = ByteArrayOutputStream(); source.readTo(metadata.key, bytes, metadata.descriptor)
                    if (SyncKeyDeliveryCodec.hasPrefix(bytes.toByteArray())) {
                        val capsule = SyncKeyDeliveryCodec.decode(bytes.toByteArray())
                        if (capsule.context.recipient == vault.context.owner && capsule.context.epoch == originalEpoch) hidden += metadata.key
                    }
                }
                cursor = page.nextCursor
            } while (cursor != null)
            assertTrue(hidden.isNotEmpty())
            val missingHistory = object : CiphertextStore by source {
                override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage = source.listCommits(cursor, pageSize).let {
                    CommitPage(it.entries.filterNot { entry -> entry.key in hidden }, it.nextCursor)
                }
            }
            assertEquals("SYNC_GROUP_MANAGEMENT_WAITING_FOR_KEYS", assertFailsWith<IllegalArgumentException> {
                owner.recover(f.code, vault.bytes(), missingHistory, LanSyncManagementRecoveryAdmission { }, joined.trust)
            }.message)
            assertTrue(Files.isRegularFile(managementRoot.resolve("profile.protected")))
            assertFalse(Files.exists(managementRoot.resolve("custody/group.protected")))
            assertFalse(Files.exists(managementRoot.resolve("root-actions/origin.protected")))
            val retainedVault = Files.readAllBytes(managementRoot.resolve("management-vault.bin"))
            owner.close(); owner = LanSyncGroupManagementRecovery(managementRoot)
            assertEquals(LanSyncManagementRecoveryStage.READY, owner.resumeImport(f.code, source).stage)
            assertContentEquals(retainedVault, Files.readAllBytes(managementRoot.resolve("management-vault.bin")))
            assertNoCode(managementRoot, f.code)
        } finally { owner.close() }
    }

    @Test fun interruptedRecoveredRootActionResumesOneExactRevisionAndReadyMissingOriginCannotReenroll() = fixture { f, joined ->
        val vault = joined.owner.encryptedManagementBackup()
        val managementRoot = Files.createDirectory(f.root.resolve("interrupted-management"))
        var interrupt = false
        var owner = LanSyncGroupManagementRecovery(managementRoot) {
            if (interrupt && it == "ROOT_SIGNED_ENTRY_RETAINED") { interrupt = false; throw IOException("SYNTHETIC_MANAGEMENT_SIGNED_REPLY_LOST") }
        }
        try {
            owner.recover(f.code, vault.bytes(), joined.replica(), LanSyncManagementRecoveryAdmission { }, joined.trust)
            val origin = Files.readAllBytes(managementRoot.resolve("root-actions/origin.protected"))
            val preview = owner.prepareRevocation(f.identity.fingerprint)
            interrupt = true
            assertFailsWith<IOException> { owner.revokeDevice(preview.acknowledge(preview.request.approvalBinding)) }
            assertFalse(interrupt)
            owner.close(); owner = LanSyncGroupManagementRecovery(managementRoot)
            owner.unlock(f.code)
            val resumed = owner.resumeAdministration()
            assertEquals(preview.request.expectedParent, resumed.ledger.parent)
            assertFalse(resumed.ledger.members.getValue(f.identity.fingerprint).active)
            val repeated = owner.resumeAdministration()
            assertEquals(resumed.ledger, repeated.ledger)
            assertEquals(resumed.localDelivery, repeated.localDelivery)
            assertContentEquals(origin, Files.readAllBytes(managementRoot.resolve("root-actions/origin.protected")))
            owner.lock()
            val path = managementRoot.resolve("root-actions/origin.protected")
            Files.move(path, managementRoot.resolve("retained-missing-origin.protected"))
            assertEquals("SYNC_GROUP_MANAGEMENT_ORIGIN_MISSING", assertFailsWith<IllegalArgumentException> { owner.unlock(f.code) }.message)
            assertFalse(Files.exists(path))
            assertEquals("SYNC_GROUP_MANAGEMENT_ORIGIN_MISSING", assertFailsWith<IllegalArgumentException> { owner.resumeImport(f.code, joined.replica()) }.message)
            assertFalse(Files.exists(path))
            assertNoCode(managementRoot, f.code)
        } finally { owner.close() }
    }
}
