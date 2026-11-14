package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real Target/codec/vault/root/HPKE/Joining/Native owners. Every original is inline synthetic. */
class FileRecoveryOwnerTest {
    private class Fixture(val root: Path) {
        val original = LanSyncGroupAdministrationTest.Fixture(Files.createDirectory(root.resolve("original")))
        val recoveryRoot = root.resolve("file-recovery")
        val dailyRoot = root.resolve("new-daily")
        val dailyData = root.resolve("new-data")
        val packageFile = root.resolve("portable.synthetic")
        val roots = AtomicInteger(); val joiners = AtomicInteger(); val readyCalls = AtomicInteger()
        val lastAccess = AtomicReference<LanSyncGroupRuntimeAccess?>()
        val owners = mutableListOf<LanSyncGroupFileRecoveryOwner>()
        var joiningObserve: (String) -> Unit = {}
        private val identityFile = root.resolve("new-device.protected")
        private val workspace by lazy {
            Files.createDirectories(dailyData)
            LanSyncWorkspace(dailyData, Files.createDirectory(root.resolve("new-workspace")), { Path.of(it) })
        }
        private var gated = false
        private val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) { check(!gated); gated = true; try { work() } finally { gated = false } }
            override suspend fun reload(sessionIds: Set<String>) { check(gated) }
        }
        lateinit var source: LanSyncGroupSourceCommit
        lateinit var originalLedger: SyncDeviceLedgerRecord
        lateinit var trust: SyncGroupTrust
        lateinit var originalBytes: ByteArray
        lateinit var packageDescriptor: CiphertextDescriptor
        lateinit var originalSnapshot: Map<String, ByteArray>
        val offlineOriginal get() = root.resolve("original-offline")
        fun identity(): LanSyncTlsIdentity {
            if (!Files.exists(identityFile)) {
                val bytes = LanSyncTlsIdentity.create().encode()
                try { Files.write(identityFile, bytes.reversedArray()) } finally { bytes.fill(0) }
            }
            val bytes = Files.readAllBytes(identityFile).reversedArray()
            return try { LanSyncTlsIdentity.decode(bytes) } finally { bytes.fill(0) }
        }
        fun joining(): LanSyncGroupJoining {
            joiners.incrementAndGet(); Files.createDirectories(dailyRoot)
            return LanSyncGroupJoining(dailyRoot, workspace, platform, identity(),
                { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> }, { joiningObserve(it) })
        }
        fun owner(clock: () -> Long = { System.nanoTime() / 1_000_000L }, observe: (String) -> Unit = {},
            dataWork: suspend (suspend () -> Boolean) -> Boolean = { it() }): LanSyncGroupFileRecoveryOwner =
            LanSyncGroupFileRecoveryOwner(root = { roots.incrementAndGet(); Files.createDirectories(recoveryRoot) },
                joining = ::joining, deviceName = "synthetic file restored daily", beforeSetup = {}, dataWork = dataWork,
                onReady = { lastAccess.set(it); readyCalls.incrementAndGet() }, clockMillis = clock, observe = observe).also(owners::add)
        suspend fun seed() {
            original.seed()
            original.rotateBeforeJoin()
            val access = requireNotNull(original.service.runtimeAccess())
            val card = original.data.resolve("entities/character_cards/original.json")
            Files.createDirectories(card.parent)
            LanSyncJsonFiles.write(card, CharacterCard.serializer(), CharacterCard("original", "inline original card",
                basicSetting = "exact original business body", createdAt = 1, updatedAt = 1))
            originalBytes = Files.readAllBytes(card)
            source = access.node.commit(access.node.inspect().review(setOf("character_cards/original")))
            access.node.upload(source.entry.request.operation, access.replica, access.localTarget)
            var cursor: CommitCursor? = null
            do { cursor = access.node.discover(access.replica, cursor).nextCursor } while (cursor != null)
            assertTrue(requireNotNull(access.node.reconcileForPrefetch().history).contains(source.entry.version.reference()))
            val publication = access.targetPublication(Files.createDirectory(root.resolve("target-plan")),
                SyncDeliveryTarget(SyncDeliveryKind.LOCAL_REPLICA, SyncStoreId.random()), prepare = false)
            val plan = publication.prepare()
            assertEquals(setOf(source.entry.version.reference()), plan.versions)
            assertTrue(plan.objects.any { it.role == LanSyncGroupTargetObjectRole.VERSION })
            val target = FileCiphertextStore(root.resolve("strict-target"))
            publication.upload(target)
            trust = access.trust; originalLedger = original.custody().ledger().current()
            val work = Files.createDirectory(root.resolve("package-work"))
            LanSyncGroupEncryptedFilePackage.prepare(plan, target, trust, original.custody().key(plan.epoch), work).use { prepared ->
                Files.newOutputStream(packageFile).use { prepared.writeTo(it) }; packageDescriptor = prepared.descriptor
            }
            original.close()
            originalSnapshot = files(original.root)
            Files.move(original.root, offlineOriginal)
            // The recovery caller receives no original profile, target store or exporter object.
            Files.move(root.resolve("strict-target"), root.resolve("strict-target-offline"))
        }
        suspend fun rootState(): SyncRootLedgerState {
            val bytes = Files.readAllBytes(recoveryRoot.resolve("management/management-vault.bin"))
            return SyncManagementSession.open(original.code, bytes).use { session ->
                SyncRootLedgerJournal(recoveryRoot.resolve("management/root-actions"), session.trust,
                    session.ownerCertificate, session::protectJournal, session::unprotectJournal).state()
            }
        }
        suspend fun managerLedger(): SyncDeviceLedgerRecord {
            val manager = LanSyncGroupManagementRecovery(recoveryRoot.resolve("management"))
            return try { manager.unlock(original.code); manager.ledger().current() } finally { manager.close() }
        }
        fun author(access: LanSyncGroupRuntimeAccess) = SyncAuthorJournal(dailyRoot.resolve("joining/node/author"), access.trust,
            Base64.getEncoder().encodeToString(identity().certificate.encoded), { it.reversedArray() }, { it.reversedArray() })
        fun custody(access: LanSyncGroupRuntimeAccess): SyncGroupCustody {
            val record = lanSyncJson.parseToJsonElement(Files.readAllBytes(dailyRoot.resolve("joining/profile.protected"))
                .reversedArray().toString(Charsets.UTF_8)).jsonObject
            val recipient = SyncDeviceKeyCustody(dailyRoot.resolve("joining/recipient"), identity().fingerprint,
                { it.reversedArray() }, { it.reversedArray() }).open(record.getValue("recipient").jsonPrimitive.content)
            return SyncGroupCustody(dailyRoot.resolve("joining/custody"), access.trust.group, recipient,
                { it.reversedArray() }, { it.reversedArray() })
        }
        fun noNative() {
            assertFalse(Files.exists(dailyRoot.resolve("joining/node/author/origin.protected")))
            assertFalse(Files.exists(dailyRoot.resolve("joining/node/native/origin.protected")))
            if (Files.exists(dailyData)) Files.list(dailyData).use { assertEquals(0L, it.count()) }
        }
        fun originalUnchanged() = unchanged(originalSnapshot, offlineOriginal)
        suspend fun close() { owners.forEach { it.closeAndDrain() }; original.close() }
    }
    private fun fixture(work: suspend (Fixture) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("file-recovery-inline-").toRealPath()
        val f = Fixture(root)
        try { f.seed(); work(f) } finally { f.close(); check(root.toFile().deleteRecursively()) }
    }
    private suspend fun review(f: Fixture, owner: LanSyncGroupFileRecoveryOwner,
        input: () -> InputStream = { Files.newInputStream(f.packageFile) }): LanSyncGroupFileRecoveryReview {
        owner.open(); owner.prepare(input, f.original.code)
        val state = withTimeout(30_000) { owner.state.first { !it.busy && (it.review != null || it.error != null) } }
        assertNull(state.error); assertFalse(state.complete); return requireNotNull(state.review)
    }
    private suspend fun completed(owner: LanSyncGroupFileRecoveryOwner): LanSyncGroupFileRecoveryState {
        val state = withTimeout(30_000) { owner.state.first { !it.busy && (it.complete || it.error != null) } }
        assertNull(state.error); assertTrue(state.complete); owner.drain(); return state
    }

    @Test fun anOfflineFileRecoversTheSameRootAndActualNativeBodyAndColdReadyRetryStillRequiresReview() = fixture { f ->
        val first = f.owner(); first.open()
        assertEquals(0, f.roots.get()); assertEquals(0, f.joiners.get())
        assertFalse(Files.exists(f.recoveryRoot)); assertFalse(Files.exists(f.dailyRoot))
        val preview = review(f, first)
        assertEquals(f.trust.group.value, preview.group)
        assertEquals(SyncDeviceSignatures.decode(f.trust.ownerCertificate).fingerprint, preview.rootFingerprint)
        assertEquals(0L, f.rootState().completedSequence); assertNull(f.rootState().pendingOperation)
        f.noNative(); f.originalUnchanged()
        first.confirm(preview.binding); first.confirm(preview.binding)
        completed(first)
        val access = requireNotNull(f.lastAccess.get())
        assertEquals(f.trust.group, access.trust.group); assertEquals(f.trust.genesis, access.trust.genesis)
        assertEquals(f.trust.ownerCertificate, access.trust.ownerCertificate)
        assertEquals(f.originalLedger.sequence + 1, access.ledger().current().sequence)
        assertEquals(1L, f.rootState().completedSequence); assertNull(f.rootState().pendingOperation)
        assertEquals(1, f.readyCalls.get()); assertEquals(0L, f.author(access).state().committedSequence)
        assertTrue(access.node.sourceOperations().isEmpty())
        val card = f.dailyData.resolve("entities/character_cards/original.json")
        val restored = requireNotNull(LanSyncJsonFiles.read(card, CharacterCard.serializer()))
        assertEquals("inline original card", restored.name); assertEquals("exact original business body", restored.basicSetting)
        assertEquals(f.source.entry.version.changes.getValue("character_cards/original").valueHash,
            access.node.inspect().nativeState.units.getValue("character_cards/original").valueHash)
        val custody = f.custody(access)
        assertEquals(2, custody.ledger().history().map { it.epoch }.toSet().size)
        custody.ledger().history().distinctBy { it.epoch }.forEach { assertEquals(it.keyCommitment, custody.key(it.epoch).keyCommitment()) }
        val origins = listOf("author", "native").associateWith { Files.readAllBytes(f.dailyRoot.resolve("joining/node/$it/origin.protected")) }
        first.closeAndDrain()
        val edited = restored.copy(basicSetting = "new local edit after recovery", updatedAt = restored.updatedAt + 1)
        LanSyncJsonFiles.write(card, CharacterCard.serializer(), edited)
        val editedBytes = Files.readAllBytes(card)
        val second = f.owner(); val again = review(f, second)
        assertEquals(1, f.readyCalls.get()); assertContentEquals(editedBytes, Files.readAllBytes(card))
        second.confirm(preview.binding)
        assertFalse(second.state.value.complete); assertNotNull(second.state.value.error)
        assertEquals(1L, f.rootState().completedSequence)
        second.confirm(again.binding); assertTrue(completed(second).pending)
        val cold = requireNotNull(f.lastAccess.get())
        assertContentEquals(editedBytes, Files.readAllBytes(card)); assertEquals(0L, f.author(cold).state().committedSequence)
        assertTrue(cold.node.sourceOperations().isEmpty()); assertEquals(1L, f.rootState().completedSequence)
        assertEquals(access.ledger().current(), cold.ledger().current()); assertEquals(2, f.readyCalls.get())
        assertEquals(LanSyncGroupNativeStage.LOCAL_PROPOSAL,
            requireNotNull(cold.node.inspect().plan).units.getValue("character_cards/original").stage)
        origins.forEach { (kind, bytes) -> assertContentEquals(bytes, Files.readAllBytes(f.dailyRoot.resolve("joining/node/$kind/origin.protected"))) }
        Files.newInputStream(f.packageFile).use { LanSyncGroupEncryptedFilePackage.verifyReadback(it, f.packageDescriptor) }
        f.originalUnchanged()
        files(f.recoveryRoot).values.forEach { assertFalse(it.toString(Charsets.ISO_8859_1).contains(f.original.code)) }
    }

    @Test fun tamperingTheSealEvenWithRecomputedPublicHashesCannotPrepareOrSignARootApproval() = fixture { f ->
        val bytes = Files.readAllBytes(f.packageFile); corruptSealAndRepairPublicHashes(bytes)
        val events = mutableListOf<String>(); val owner = f.owner(observe = { synchronized(events) { events += it } })
        owner.prepare({ ByteArrayInputStream(bytes) }, f.original.code)
        val state = withTimeout(30_000) { owner.state.first { !it.busy && it.error != null } }; owner.drain()
        assertNull(state.review); assertFalse(state.complete)
        assertFalse(synchronized(events) { "FILE_RECOVERY_INVENTORY_VERIFIED" in events })
        assertEquals(f.originalLedger, f.managerLedger()); assertEquals(0L, f.rootState().completedSequence)
        assertNull(f.rootState().pendingOperation); f.noNative(); assertEquals(0, f.readyCalls.get())
        Files.newInputStream(f.packageFile).use { LanSyncGroupEncryptedFilePackage.verifyReadback(it, f.packageDescriptor) }
        f.originalUnchanged()
    }

    @Test fun readyDailyWithANewerAdmittedCheckpointRefusesAnOldFileAndStaleManagerBeforeAnotherRootAction() = fixture { f ->
        val first = f.owner(); val approval = review(f, first)
        first.confirm(approval.binding); completed(first); first.closeAndDrain()
        val access = requireNotNull(f.lastAccess.get())
        val recovered = access.ledger().current()
        assertEquals(f.originalLedger.sequence + 1, recovered.sequence)
        assertEquals(f.originalLedger.reference(), recovered.parent)
        assertEquals(1, f.readyCalls.get())

        // A separate code-authorized manager receives the actual complete restored selection.
        val publication = access.targetPublication(Files.createDirectory(f.root.resolve("new-manager-plan")),
            SyncDeliveryTarget(SyncDeliveryKind.LOCAL_REPLICA, SyncStoreId.random()), prepare = false)
        val selection = publication.prepare()
        assertEquals(recovered.reference(), selection.checkpoint)
        assertEquals(setOf(f.source.entry.version.reference()), selection.versions)
        val complete = FileCiphertextStore(f.root.resolve("new-manager-source"))
        publication.upload(complete)
        val manager = LanSyncGroupManagementRecovery(Files.createDirectory(f.root.resolve("independent-management")))
        val future = FileCiphertextStore(f.root.resolve("future-root-controls"))
        val later = try {
            val imported = manager.recover(f.original.code, access.encryptedManagementBackup().bytes(), complete,
                LanSyncManagementRecoveryAdmission { assertEquals(f.trust, it) }, access.trust)
            assertEquals(LanSyncManagementRecoveryStage.READY, imported.stage)
            assertEquals(recovered, manager.ledger().current())
            val preview = manager.prepareRevocation(f.original.identity.fingerprint)
            assertEquals(recovered.reference(), preview.request.expectedParent)
            val revoked = requireNotNull(recovered.members[f.original.identity.fingerprint])
            val next = manager.revokeDevice(preview.acknowledge(preview.request.approvalBinding)).ledger
            assertEquals(recovered.sequence + 1, next.sequence)
            assertEquals(recovered.reference(), next.parent)
            assertEquals(recovered.members + (f.original.identity.fingerprint to revoked.copy(active = false)), next.members)
            assertEquals(recovered.coveredTypes, next.coveredTypes)
            assertEquals(recovered.acceptedHeads + f.source.entry.version.reference(), next.acceptedHeads)
            assertTrue(next.members.getValue(access.device).active)
            assertNotEquals(recovered.epoch, next.epoch)
            assertEquals(next, manager.publish(future,
                SyncDeliveryTarget(SyncDeliveryKind.LOCAL_REPLICA, SyncStoreId.random())).ledger)
            next
        } finally { manager.close() }

        // Deliver the real new daily capsules and signed prefix, but not the root recipient's
        // capsules. The old manager cannot acquire the new epoch merely from daily possession.
        val hidden = linkedSetOf<CiphertextObjectKey>()
        val hiddenEpochs = linkedSetOf<SyncStoreId>()
        var cursor: CommitCursor? = null
        do {
            val page = future.listCommits(cursor)
            page.entries.filter { it.descriptor.length <= SyncKeyDeliveryCodec.MAX_BYTES }.forEach { metadata ->
                val bytes = ByteArrayOutputStream()
                assertEquals(metadata, future.readTo(metadata.key, bytes, metadata.descriptor))
                if (SyncKeyDeliveryCodec.hasPrefix(bytes.toByteArray())) {
                    val capsule = SyncKeyDeliveryCodec.decode(bytes.toByteArray())
                    capsule.verifyOwner(access.trust, metadata.key)
                    if (capsule.context.recipient == access.vault.owner && capsule.context.authorizingLedger == later.reference()) {
                        hidden += metadata.key; hiddenEpochs += capsule.context.epoch
                    }
                }
            }
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals((access.ledger().history().map { it.epoch } + later.epoch).toSet(), hiddenEpochs)
        assertTrue(hidden.isNotEmpty())
        val dailyOnly = object : CiphertextStore by future {
            override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult =
                throw CiphertextStoreException(CiphertextStoreFailure.UNSUPPORTED)
            override fun metadata(key: CiphertextObjectKey) = if (key in hidden) null else future.metadata(key)
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?) =
                if (key in hidden) null else future.readTo(key, destination, expected)
            override fun listCommits(cursor: CommitCursor?, pageSize: Int) = future.listCommits(cursor, pageSize).let {
                CommitPage(it.entries.filterNot { entry -> entry.key in hidden }, it.nextCursor)
            }
        }
        access.reader.routes("futureproof", listOf(LanSyncGroupReplicaReader.Route("ACTUAL_FUTURE_ROOT", dailyOnly)))
        try {
            cursor = null
            do {
                val page = access.node.discover(dailyOnly, cursor)
                assertTrue(page.observations.none { it.stage == SyncInboxStage.INVALID_OBJECT })
                cursor = page.nextCursor
            } while (cursor != null)
            val admitted = access.node.reconcileForPrefetch()
            assertFalse(admitted.trustBlocked)
            assertEquals(later.reference(), admitted.ledgerCheckpoint)
            assertTrue(requireNotNull(admitted.history).contains(f.source.entry.version.reference()))
        } finally { access.reader.removeRoutes("futureproof") }
        assertEquals(later, access.ledger().current())
        assertEquals(later.keyCommitment, f.custody(access).key(later.epoch).keyCommitment())
        hidden.forEach { assertNull(access.reader.cachedOnly().metadata(it)) }
        val daily = f.joining()
        assertEquals(LanSyncGroupJoinStage.READY, requireNotNull(daily.profile()).stage)
        assertEquals(recovered.reference(), requireNotNull(daily.recoveryAnchor()).trust.checkpoint)
        val staleManager = LanSyncGroupManagementRecovery(f.recoveryRoot.resolve("management"))
        try {
            staleManager.unlock(f.original.code)
            val retained = staleManager.ledger()
            assertEquals(recovered, retained.current())
            assertFalse(retained.contains(later.reference()))
        } finally { staleManager.close() }

        val rootBefore = f.rootState()
        assertEquals(1L, rootBefore.completedSequence); assertNull(rootBefore.pendingOperation)
        assertEquals(recovered.reference(), rootBefore.last)
        val managementBefore = files(f.recoveryRoot.resolve("management"))
        assertTrue(managementBefore.keys.any { it.contains("signed") })
        val authorBefore = f.author(access).state()
        assertEquals(0L, authorBefore.committedSequence)
        val sourceBefore = access.node.sourceOperations(); assertTrue(sourceBefore.isEmpty())
        val dailyBefore = files(f.dailyRoot)
        val nativeBefore = files(f.dailyData)
        val identityBefore = Files.readAllBytes(f.root.resolve("new-device.protected"))
        val events = mutableListOf<String>()
        val retry = f.owner(observe = { synchronized(events) { events += it } })
        retry.open(); retry.prepare({ Files.newInputStream(f.packageFile) }, f.original.code)
        val refused = withTimeout(30_000) { retry.state.first { !it.busy && (it.error != null || it.review != null) } }
        retry.drain()
        assertTrue(refused.visible); assertNotNull(refused.error)
        assertNull(refused.review); assertFalse(refused.complete)
        assertTrue(synchronized(events) { "FILE_RECOVERY_STAGED" in events && "FILE_RECOVERY_MANAGEMENT_STARTED" in events })
        assertFalse(synchronized(events) { "FILE_RECOVERY_INVENTORY_VERIFIED" in events || "FILE_RECOVERY_DATA_PHASE" in events })
        assertEquals(1, f.readyCalls.get())
        assertEquals(rootBefore, f.rootState())
        unchanged(managementBefore, f.recoveryRoot.resolve("management"))
        unchanged(dailyBefore, f.dailyRoot); unchanged(nativeBefore, f.dailyData)
        assertContentEquals(identityBefore, Files.readAllBytes(f.root.resolve("new-device.protected")))
        assertEquals(authorBefore, f.author(access).state()); assertEquals(sourceBefore, access.node.sourceOperations())
        assertEquals(later, f.custody(access).ledger().current())
        assertEquals(later.keyCommitment, f.custody(access).key(later.epoch).keyCommitment())
        Files.newInputStream(f.packageFile).use { LanSyncGroupEncryptedFilePackage.verifyReadback(it, f.packageDescriptor) }
        f.originalUnchanged()
    }

    @Test fun aFailureAfterTheActualRootApprovalColdResumesTheSameKeysAndRootOperationWithoutResigning() = fixture { f ->
        val lost = AtomicBoolean()
        val first = f.owner(observe = { if (it == "FILE_RECOVERY_DATA_PHASE" && lost.compareAndSet(false, true)) throw IOException("synthetic data handoff lost") })
        val preview = review(f, first); first.confirm(preview.binding)
        withTimeout(30_000) { first.state.first { !it.busy && it.error != null } }; first.closeAndDrain()
        assertTrue(lost.get()); f.noNative()
        val before = f.rootState(); assertEquals(1L, before.completedSequence); assertNull(before.pendingOperation)
        val approved = f.managerLedger(); assertEquals(f.originalLedger.sequence + 1, approved.sequence)
        val recipient = files(f.dailyRoot.resolve("joining/recipient"))
        val signed = files(f.recoveryRoot.resolve("management/root-actions")).filterKeys { it.contains("signed") || it.contains("origin") }
        assertTrue(signed.keys.any { it.contains("signed") })
        val second = f.owner(); val resumed = review(f, second)
        assertEquals(preview.device, resumed.device); assertEquals(before, f.rootState())
        second.confirm(resumed.binding); completed(second)
        val access = requireNotNull(f.lastAccess.get())
        assertEquals(approved, access.ledger().current()); assertEquals(before, f.rootState())
        unchanged(recipient, f.dailyRoot.resolve("joining/recipient"))
        val afterSigned = files(f.recoveryRoot.resolve("management/root-actions")).filterKeys { it.contains("signed") || it.contains("origin") }
        assertEquals(signed.keys, afterSigned.keys); signed.forEach { (name, bytes) -> assertContentEquals(bytes, afterSigned.getValue(name), name) }
        assertEquals(0L, f.author(access).state().committedSequence)
        assertEquals("exact original business body", LanSyncJsonFiles.read(f.dailyData.resolve("entities/character_cards/original.json"), CharacterCard.serializer())?.basicSetting)
        f.originalUnchanged()
    }

    @Test fun longCiphertextStagingDoesNotSpendTheManagementTtlAndConfirmedHiddenDataWorkContinuesAfterIt() = fixture { f ->
        val clock = AtomicLong(10); val beganAt = AtomicLong(-1); lateinit var owner: LanSyncGroupFileRecoveryOwner
        owner = f.owner(clock::get, observe = { point ->
            if (point == "FILE_RECOVERY_MANAGEMENT_STARTED") beganAt.set(clock.get())
            if (point == "FILE_RECOVERY_DATA_PHASE") { owner.hide(); clock.addAndGet(600_000) }
        })
        val moved = AtomicBoolean()
        val preview = review(f, owner) {
            object : FilterInputStream(Files.newInputStream(f.packageFile)) {
                private fun advance() { if (moved.compareAndSet(false, true)) clock.addAndGet(600_000) }
                override fun read(): Int { advance(); return super.read() }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int { advance(); return `in`.read(bytes, offset, length) }
            }
        }
        assertEquals(600_010L, beganAt.get()); assertEquals(0L, f.rootState().completedSequence)
        owner.confirm(preview.binding); val finished = completed(owner)
        assertFalse(finished.visible); assertEquals(1, f.readyCalls.get()); assertEquals(1L, f.rootState().completedSequence)
        assertEquals("exact original business body", LanSyncJsonFiles.read(f.dailyData.resolve("entities/character_cards/original.json"), CharacterCard.serializer())?.basicSetting)
        f.originalUnchanged()
    }

    @Test fun explicitLockClosesItsActualBlockedInputAndDrainsWithoutCreatingDailyIdentityOrLateResults() = fixture { f ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closes = AtomicInteger(); val blocked = AtomicBoolean()
        val owner = f.owner(); owner.open()
        assertEquals(0, f.roots.get()); assertEquals(0, f.joiners.get())
        owner.prepare({
            object : FilterInputStream(Files.newInputStream(f.packageFile)) {
                private fun waitOnce() {
                    if (blocked.compareAndSet(false, true)) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                }
                override fun read(): Int { waitOnce(); return super.read() }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int { waitOnce(); return `in`.read(bytes, offset, length) }
                override fun close() { closes.incrementAndGet(); release.countDown(); super.close() }
            }
        }, f.original.code)
        assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
        owner.lock(); withTimeout(10_000) { owner.drain() }
        assertEquals(1, closes.get()); assertEquals(0, f.joiners.get()); assertEquals(0, f.readyCalls.get())
        assertNull(owner.state.value.review); assertFalse(owner.state.value.complete); assertFalse(owner.state.value.visible)
        assertFalse(Files.exists(f.recoveryRoot.resolve("management"))); assertFalse(Files.exists(f.dailyRoot))
        f.originalUnchanged()
    }

    private fun corruptSealAndRepairPublicHashes(bytes: ByteArray) {
        val raw = ByteArrayInputStream(bytes); val data = DataInputStream(raw)
        fun position() = bytes.size - raw.available()
        data.skipBytes("CCB-GROUP-ENCRYPTED-FILE\n".toByteArray().size + 1)
        val header = ByteArray(data.readInt()).also(data::readFully)
        val count = lanSyncJson.decodeFromString(LanSyncGroupEncryptedFilePackage.UnverifiedHeader.serializer(), lanSyncUtf8(header)).objectCount
        repeat(count.toInt()) {
            assertEquals(1, data.readUnsignedByte()); data.readUnsignedByte(); data.skipBytes(data.readUnsignedShort())
            data.readUnsignedByte(); data.skipBytes(32); val length = data.readLong(); data.skipBytes(64 + 32); data.skipBytes(length.toInt())
        }
        assertEquals(2, data.readUnsignedByte()); val length = data.readLong().toInt(); val hashOffset = position()
        data.skipBytes(64); val payload = position(); data.skipBytes(length); val footer = position()
        bytes[payload + length - 1] = (bytes[payload + length - 1].toInt() xor 1).toByte()
        hash(bytes.copyOfRange(payload, payload + length)).toByteArray(Charsets.US_ASCII).copyInto(bytes, hashOffset)
        hash(bytes.copyOfRange(0, footer)).toByteArray(Charsets.US_ASCII).copyInto(bytes, footer + 1 + 8 + 8)
    }
    private companion object {
        fun files(root: Path): Map<String, ByteArray> = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.toList()
            .associate { root.relativize(it).toString() to Files.readAllBytes(it) } }
        fun unchanged(expected: Map<String, ByteArray>, root: Path) {
            val actual = files(root); assertEquals(expected.keys, actual.keys)
            expected.forEach { (name, bytes) -> assertContentEquals(bytes, actual.getValue(name), name) }
        }
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
