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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** Actual owners and synthetic strict ciphertext stores; recovery uses no invitation or network. */
class LanSyncGroupRecoveryJoiningTest {
    private class Joiner(val founder: LanSyncGroupAdministrationTest.Fixture, label: String = "new-daily") {
        val root = Files.createDirectory(founder.root.resolve("$label-profile"))
        val data = Files.createDirectory(founder.root.resolve("$label-data"))
        val workspace = LanSyncWorkspace(data, Files.createDirectory(founder.root.resolve("$label-workspace")), { Path.of(it) })
        private val identityFile = founder.root.resolve("$label-identity.protected")
        init {
            val bytes = LanSyncTlsIdentity.create().encode()
            try { Files.write(identityFile, bytes.reversedArray()) } finally { bytes.fill(0) }
        }
        // Reopen the same retained private identity for every owner, including the cold reopen.
        val identity: LanSyncTlsIdentity get() {
            val bytes = Files.readAllBytes(identityFile).reversedArray()
            return try { LanSyncTlsIdentity.decode(bytes) } finally { bytes.fill(0) }
        }
        private var gated = false
        val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) {
                check(!gated); gated = true; try { work() } finally { gated = false }
            }
            override suspend fun reload(sessionIds: Set<String>) { check(gated) }
        }
        fun owner(parent: Path = root) = LanSyncGroupJoining(parent, workspace, platform, identity,
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> })
        fun control() = lanSyncJson.parseToJsonElement(Files.readAllBytes(root.resolve("joining/profile.protected"))
            .reversedArray().toString(Charsets.UTF_8)).jsonObject
        fun author(trust: SyncGroupTrust) = SyncAuthorJournal(root.resolve("joining/node/author"), trust,
            Base64.getEncoder().encodeToString(identity.certificate.encoded), { it.reversedArray() }, { it.reversedArray() })
        fun custody(trust: SyncGroupTrust): SyncGroupCustody {
            val recipient = SyncDeviceKeyCustody(root.resolve("joining/recipient"), identity.fingerprint,
                { it.reversedArray() }, { it.reversedArray() }).open(control().getValue("recipient").jsonPrimitive.content)
            return SyncGroupCustody(root.resolve("joining/custody"), trust.group, recipient, { it.reversedArray() }, { it.reversedArray() })
        }
    }
    private fun fixture(work: suspend (LanSyncGroupAdministrationTest.Fixture) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-recovery-joining-").toRealPath()
        try { LanSyncGroupAdministrationTest.Fixture(root).use { it.seed(); work(it) } }
        finally { check(root.toFile().deleteRecursively()) }
    }
    private fun files(root: Path): Map<String, ByteArray> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate { root.relativize(it).toString() to Files.readAllBytes(it) }
    }
    private fun unchanged(expected: Map<String, ByteArray>, root: Path) {
        val actual = files(root); assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, bytes) -> assertContentEquals(bytes, actual.getValue(name), name) }
    }
    private suspend fun recovered(f: LanSyncGroupAdministrationTest.Fixture, backend: CiphertextStore,
        label: String = "manager"): LanSyncGroupManagementRecovery =
        LanSyncGroupManagementRecovery(Files.createDirectory(f.root.resolve(label))).also { owner ->
            owner.recover(f.code, f.service.encryptedManagementBackup().bytes(), backend, LanSyncManagementRecoveryAdmission { })
        }
    private fun entries(store: CiphertextStore): List<CiphertextObjectMetadata> {
        val result = mutableListOf<CiphertextObjectMetadata>(); var cursor: CommitCursor? = null
        do { val page = store.listCommits(cursor, 100); result += page.entries; cursor = page.nextCursor } while (cursor != null)
        return result
    }
    /** The actual reader handles exact reads/cache; this test lens only combines discovery pages. */
    private fun combined(root: Path, controls: CiphertextStore, cloud: CiphertextStore): CiphertextStore {
        val reader = LanSyncGroupReplicaReader(FileCiphertextStore(root.resolve("read-cache")), root.resolve("read-work"),
            root.resolve("read-candidates")) { _, failure -> throw failure }
        reader.routes(listOf(LanSyncGroupReplicaReader.Route("RECOVERED_CONTROLS", controls),
            LanSyncGroupReplicaReader.Route("OFFLINE_CLOUD", cloud)))
        val rows = (entries(controls) + entries(cloud)).distinct().sortedWith(
            compareBy<CiphertextObjectMetadata> { it.key.id.value }.thenBy { it.descriptor.sha256.hex })
        return object : CiphertextStore by reader {
            override fun put(request: CiphertextPutRequest, source: java.io.InputStream): CiphertextPutResult = error("SYNTHETIC_SOURCE_READ_ONLY")
            override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage {
                require(pageSize in 1..1000)
                val offset = cursor?.token?.toInt() ?: 0
                require(offset in 0..rows.size)
                val page = rows.drop(offset).take(pageSize)
                return CommitPage(page, if (offset + page.size < rows.size) CommitCursor((offset + page.size).toString()) else null)
            }
        }
    }

    @Test fun emptyDailyRecoversTheOriginalGroupAppliesCloudHistoryAndColdReopensWithoutCodeOrSources() = fixture { f ->
        f.rotateBeforeJoin()
        val sourceFile = f.data.resolve("entities/character_cards/original.json"); Files.createDirectories(sourceFile.parent)
        val originalCard = CharacterCard("original", "synthetic retained source", basicSetting = "exact cloud body", createdAt = 1, updatedAt = 1)
        LanSyncJsonFiles.write(sourceFile, CharacterCard.serializer(), originalCard)
        val sourceCommit = f.node.commit(f.node.inspect().review(setOf("character_cards/original")))
        f.node.upload(sourceCommit.entry.request.operation, FileCiphertextStore(f.profile.resolve("local-replica")),
            requireNotNull(f.service.profile()).localReplica)
        val cloudRoot = f.root.resolve("offline-cloud")
        val cloud = FileCiphertextStore(cloudRoot)
        requireNotNull(f.service.runtimeAccess()).targetPublication(Files.createDirectory(f.root.resolve("cloud-relay")),
            SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, SyncStoreId.random())).upload(cloud)
        val oldLedger = f.custody().ledger()
        val originalData = files(f.data)
        val manager = recovered(f, cloud)
        try {
            f.service.close()
            val oldProfile = files(f.profile)
            val offlineProfile = Files.move(f.profile, f.root.resolve("offline-original-profile"))
            val joiner = Joiner(f)
            assertTrue(Files.newDirectoryStream(joiner.data).use { !it.iterator().hasNext() })
            val initial = manager.recoveryBootstrap()
            val prepared = joiner.owner().prepareRecovery(initial.anchor) { it.validate() }
            assertEquals(LanSyncGroupJoinStage.PREPARED, prepared.stage)
            assertNull(joiner.owner().pendingRoute())
            assertEquals("SYNC_GROUP_JOIN_CLOUD_SOURCE_REQUIRED",
                assertFailsWith<IllegalArgumentException> { joiner.owner().bootstrapStore(LanSyncNetwork.Local) }.message)
            val recipientFiles = files(joiner.root.resolve("joining/recipient"))
            val preview = joiner.owner().prepareRecoveryDevice(manager, "actual fresh daily")
            assertEquals(oldLedger.current(), manager.ledger().current())
            assertFalse(Files.exists(joiner.root.resolve("joining/node/author/origin.protected")))
            assertFailsWith<IllegalArgumentException> { preview.acknowledge("0".repeat(64)) }
            val added = manager.addRecoveryDevice(preview.acknowledge(preview.request.approvalBinding))
            val bootstrap = manager.recoveryBootstrap()
            joiner.owner().acceptRecoveryBootstrap(bootstrap.anchor)
            val source = combined(joiner.root, bootstrap.source, cloud)
            val activated = joiner.owner().activate(source)
            assertEquals(LanSyncGroupJoinStage.READY, activated.profile.stage)
            assertEquals(oldLedger.trustAnchor.group, activated.profile.trust.group)
            assertEquals(oldLedger.trustAnchor.genesis, activated.profile.trust.genesis)
            assertEquals(oldLedger.ownerCertificate, activated.profile.trust.ownerCertificate)
            assertEquals(oldLedger.current().sequence + 1, added.ledger.sequence)
            val custody = joiner.custody(activated.profile.trust)
            assertEquals(added.ledger, custody.ledger().current())
            custody.ledger().history().distinctBy { it.epoch }.forEach { row -> assertEquals(row.keyCommitment, custody.key(row.epoch).keyCommitment()) }
            unchanged(recipientFiles, joiner.root.resolve("joining/recipient"))
            val node = requireNotNull(activated.node)
            val plan = requireNotNull(node.inspect().plan)
            assertEquals(LanSyncGroupNativeStage.READY_CONTENT, plan.units.getValue("character_cards/original").stage)
            assertEquals(LanSyncGroupNativeApplyStatus.APPLIED, node.apply(plan)?.status)
            val restored = requireNotNull(LanSyncJsonFiles.read(joiner.data.resolve("entities/character_cards/original.json"), CharacterCard.serializer()))
            assertEquals(originalCard.id, restored.id); assertEquals(originalCard.name, restored.name)
            assertEquals(originalCard.basicSetting, restored.basicSetting)
            assertEquals(sourceCommit.entry.version.changes.getValue("character_cards/original").valueHash,
                node.inspect().nativeState.units.getValue("character_cards/original").valueHash)
            assertEquals(0L, joiner.author(activated.profile.trust).state().committedSequence)
            assertTrue(node.sourceOperations().isEmpty())
            val origins = listOf("author", "native").associateWith {
                Files.readAllBytes(joiner.root.resolve("joining/node/$it/origin.protected"))
            }
            val control = joiner.control()
            assertTrue(control["offer"] == null || control["offer"] == JsonNull)
            assertNotNull(control["recoveryAnchor"])
            manager.close()
            Files.move(cloudRoot, f.root.resolve("offline-cloud-unavailable"))
            val reopened = requireNotNull(joiner.owner().open())
            assertEquals(LanSyncGroupNodeStage.READY, reopened.inspect().stage)
            assertEquals(LanSyncGroupJoinStage.READY, joiner.owner().profile()?.stage)
            assertEquals(0L, joiner.author(activated.profile.trust).state().committedSequence)
            assertTrue(reopened.sourceOperations().isEmpty())
            origins.forEach { (kind, bytes) -> assertContentEquals(bytes, Files.readAllBytes(joiner.root.resolve("joining/node/$kind/origin.protected"))) }
            assertEquals(sourceCommit.entry.version.reference(), requireNotNull(reopened.inspect().reconciliation.history).heads().single())
            files(joiner.root).forEach { (name, bytes) -> assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(f.code), name) }
            unchanged(oldProfile, offlineProfile); unchanged(originalData, f.data)
        } finally { manager.close() }
    }

    @Test fun unapprovedAndChangedAnchorsCannotEnrollAndDuplicatePreparationPreservesTheRetainedKeys() = fixture { f ->
        val manager = recovered(f, FileCiphertextStore(f.profile.resolve("local-replica")))
        try {
            val bootstrap = manager.recoveryBootstrap(); val before = manager.ledger().current()
            val joiner = Joiner(f)
            joiner.owner().prepareRecovery(bootstrap.anchor) { }
            val retained = files(joiner.root.resolve("joining/recipient"))
            assertFailsWith<IllegalArgumentException> { joiner.owner().prepareRecovery(bootstrap.anchor) { } }
            unchanged(retained, joiner.root.resolve("joining/recipient"))
            assertEquals("SYNC_GROUP_JOIN_PROOF_REQUIRED",
                assertFailsWith<IllegalArgumentException> { joiner.owner().activate(bootstrap.source) }.message)
            val changed = bootstrap.anchor.copy(vaultDescriptor = CiphertextDescriptor(bootstrap.anchor.vaultDescriptor.length,
                CiphertextSha256.parse(if (bootstrap.anchor.vaultDescriptor.sha256.hex == "f".repeat(64)) "e".repeat(64) else "f".repeat(64))))
            assertEquals("SYNC_GROUP_JOIN_RECOVERY_TRUST_CHANGED",
                assertFailsWith<IllegalArgumentException> { joiner.owner().acceptRecoveryBootstrap(changed) }.message)
            joiner.owner().acceptRecoveryBootstrap(bootstrap.anchor)
            val waiting = joiner.owner().activate(bootstrap.source)
            assertNotEquals(LanSyncGroupJoinStage.READY, waiting.profile.stage)
            assertNull(waiting.node)
            assertFalse(Files.exists(joiner.root.resolve("joining/node/author/origin.protected")))
            assertFalse(Files.exists(joiner.root.resolve("joining/custody/group.protected")))
            assertEquals(before, manager.ledger().current())
            unchanged(retained, joiner.root.resolve("joining/recipient"))
            val founderFiles = files(f.profile)
            assertEquals("SYNC_GROUP_FOUNDER_PROFILE_EXISTS",
                assertFailsWith<IllegalArgumentException> { joiner.owner(f.profile).prepareRecovery(bootstrap.anchor) { } }.message)
            unchanged(founderFiles, f.profile)
        } finally { manager.close() }
    }

    @Test fun anUnprovenJoiningCheckpointCannotPrepareAnOldParentRootApproval() = fixture { f ->
        val manager = recovered(f, FileCiphertextStore(f.profile.resolve("local-replica")))
        try {
            val anchor = manager.recoveryBootstrap().anchor
            val unknown = anchor.copy(trust = anchor.trust.copy(checkpoint = SyncVersionRef(SyncStoreId.random(), "9".repeat(64))))
            val joiner = Joiner(f)
            joiner.owner().prepareRecovery(unknown) { }
            val before = manager.ledger().current()
            assertEquals("SYNC_GROUP_JOIN_KNOWN_CHECKPOINT_MISSING",
                assertFailsWith<IllegalArgumentException> { joiner.owner().prepareRecoveryDevice(manager, "known-prefix fence") }.message)
            assertEquals(before, manager.ledger().current())
            assertFalse(Files.exists(joiner.root.resolve("joining/node/author/origin.protected")))
        } finally { manager.close() }
    }

    @Test fun actualLegacyInvitationStillActivatesAndCannotBeReinterpretedAsRecoveryPreparation() = fixture { f ->
        val joiner = Joiner(f, "legacy")
        val invitation = f.service.invitation(16384).also(f.invitations::add)
        val listener = (invitation.invitationContext().serverSocketFactory.createServerSocket(0, 2, InetAddress.getLoopbackAddress()) as SSLServerSocket)
            .also { it.needClientAuth = true }
        val pool = Executors.newSingleThreadExecutor()
        try {
            // Existing invitation proof uses actual mutual TLS on isolated loopback, not a recovery route.
            val offer = invitation.issue("10.0.0.1", listener.localPort)
            joiner.owner().prepare(offer.code()) { }
            val peer = pool.submit<LanSyncGroupInvitedPeer> { invitation.serve(listener.accept() as SSLSocket) }
            joiner.owner().prove("legacy daily") { actual ->
                LanSyncTls.connect(joiner.identity, actual.host, actual.port, actual.inviter, 15_000) { _, _ ->
                    Socket(InetAddress.getLoopbackAddress(), listener.localPort)
                }
            }
            val preview = f.service.prepareMember(peer.get(20, TimeUnit.SECONDS), invitation)
            f.service.addMember(f.service.resume(f.code), preview.acknowledge(preview.request.approvalBinding))
            val manager = recovered(f, FileCiphertextStore(f.profile.resolve("local-replica")))
            try {
                val anchor = manager.recoveryBootstrap().anchor
                assertFailsWith<IllegalArgumentException> { joiner.owner().prepareRecovery(anchor) { } }
                assertEquals("SYNC_GROUP_JOIN_RECOVERY_NOT_AVAILABLE",
                    assertFailsWith<IllegalArgumentException> { joiner.owner().prepareRecoveryDevice(manager, "not recovery") }.message)
            } finally { manager.close() }
            val oldControl = joiner.control().toMutableMap().also { it.remove("recoveryAnchor") }
            Files.write(joiner.root.resolve("joining/profile.protected"), JsonObject(oldControl).toString().toByteArray().reversedArray())
            assertEquals(LanSyncGroupJoinStage.READY,
                joiner.owner().activate(FileCiphertextStore(f.profile.resolve("local-replica"))).profile.stage)
            assertNotNull(joiner.owner().pendingRoute())
            assertNotNull(joiner.owner().open())
        } finally { listener.close(); pool.shutdownNow(); check(pool.awaitTermination(25, TimeUnit.SECONDS)) }
    }
}
