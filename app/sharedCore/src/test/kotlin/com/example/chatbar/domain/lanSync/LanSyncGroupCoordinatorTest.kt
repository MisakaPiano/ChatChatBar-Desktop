package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/** Shared application owner with real enrollment/ciphertext/TLS; protection is synthetic only. */
class LanSyncGroupCoordinatorTest {
    private val noNetwork = object : LanSyncNetwork {
        override fun addresses() = listOf("10.0.0.1")
        override fun accepts(address: InetAddress) = address.isLoopbackAddress
        override val discoveryEnabled = false
        override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket =
            LanSyncTls.connect(identity, peer.host, peer.port, peer.fingerprint, 15_000) { _, port -> Socket("127.0.0.1", port) }
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(20_000) { while (!condition()) delay(10) }

    @Test fun emptyStartupOpenLockAndCloseNeverTouchIdentityProfileOrSetupFactories() = runBlocking {
        val factories = AtomicInteger()
        val setups = AtomicInteger()
        val coordinator = LanSyncGroupCoordinator({ false },
            { factories.incrementAndGet(); error("SYNTHETIC_UNCONFIGURED_IDENTITY_FACTORY") },
            { factories.incrementAndGet(); error("SYNTHETIC_UNCONFIGURED_JOINING_FACTORY") }, noNetwork,
            "synthetic device", { setups.incrementAndGet() })
        coordinator.startIfConfigured()
        coordinator.open()
        coordinator.lockManagement()
        coordinator.closeAndDrain()
        assertEquals(0, factories.get())
        assertEquals(0, setups.get())
        assertEquals(LanSyncGroupPanelStage.EMPTY, coordinator.state.value.stage)
        assertNull(coordinator.state.value.recoveryCode)
        assertNull(coordinator.view.value)
    }

    private class Fixture(val root: Path, val network: LanSyncNetwork) {
        val profile = Files.createDirectory(root.resolve("profile"))
        val data = Files.createDirectory(root.resolve("data"))
        val card = data.resolve("entities/character_cards/card.json")
        val identity = LanSyncTlsIdentity.create()
        val workspace = LanSyncWorkspace(data, Files.createDirectory(root.resolve("workspace")), { Path.of(it) })
        private val owners = mutableListOf<LanSyncGroupEnrollment>()
        private val coordinators = mutableListOf<LanSyncGroupCoordinator>()
        var setupCalls = 0
        val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) = work()
            override suspend fun reload(sessionIds: Set<String>) { }
        }
        fun founder() = LanSyncGroupEnrollment(profile, workspace, platform, identity,
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> }).also(owners::add)
        fun coordinator(beforeSetup: suspend () -> Unit = {}) = LanSyncGroupCoordinator({ Files.exists(profile.resolve("profile.protected")) || Files.exists(profile.resolve("creation")) },
            ::founder, { error("SYNTHETIC_FOUNDER_MUST_NOT_OPEN_JOINING") }, network, "synthetic PC", { setupCalls++; beforeSetup() }).also(coordinators::add)
        fun author(trust: SyncGroupTrust) = SyncAuthorJournal(profile.resolve("node/author"), trust,
            Base64.getEncoder().encodeToString(identity.certificate.encoded), { it.reversedArray() }, { it.reversedArray() })
        fun writeCard(name: String) {
            Files.createDirectories(card.parent)
            LanSyncJsonFiles.write(card, CharacterCard.serializer(), CharacterCard("card", name, createdAt = 1, updatedAt = 1))
        }
        suspend fun close() { coordinators.forEach { it.closeAndDrain() }; owners.forEach { it.close() } }
    }
    private fun fixture(work: suspend (Fixture) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-group-coordinator-").toRealPath()
        val f = Fixture(root, noNetwork)
        try { work(f) } finally { f.close(); check(root.toFile().deleteRecursively()) }
    }

    @Test fun managementLockDuringQueuedCreationNeverRevealsLateRecoveryCodeOrActivatesDraft() = fixture { f ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = f.coordinator { entered.complete(Unit); release.await() }
        coordinator.createGroup()
        withTimeout(5_000) { entered.await() }
        assertTrue(coordinator.state.value.busy)
        coordinator.lockManagement()
        release.complete(Unit)
        await { !coordinator.state.value.busy && coordinator.state.value.error != null }
        assertTrue(requireNotNull(coordinator.state.value.error).endsWith("SYNC_GROUP_MANAGEMENT_LOCKED"))
        assertNull(coordinator.state.value.recoveryCode)
        assertNull(coordinator.state.value.setupBinding)
        assertFalse(coordinator.state.value.managementUnlocked)
        assertNull(coordinator.view.value)
        assertEquals(LanSyncGroupEnrollmentStage.PREPARED, f.founder().profile()?.stage)
        for (name in listOf("node", "custody", "local-replica", "root-actions", "app-runtime")) assertFalse(Files.exists(f.profile.resolve(name)))
        // The hidden draft must also be gone: a later save cannot activate it using any binding.
        val firstError = coordinator.state.value.error
        coordinator.recoverySaved("synthetic-binding-never-exposed")
        await { !coordinator.state.value.busy && coordinator.state.value.error != firstError }
        assertTrue(requireNotNull(coordinator.state.value.error).endsWith("SYNC_GROUP_MANAGEMENT_LOCKED"))
        assertEquals(LanSyncGroupEnrollmentStage.PREPARED, f.founder().profile()?.stage)
        coordinator.closeAndDrain()
        assertNull(coordinator.view.value)
        assertFalse(Files.exists(f.profile.resolve("app-runtime")))
    }

    @Test fun explicitSavedCreationPublishesLocalReplicaAndReopenRunsWithManagementLocked() = fixture { f ->
        f.writeCard("synthetic user content")
        val original = Files.readAllBytes(f.card)
        val first = f.coordinator()
        first.createGroup()
        await { first.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE && !first.state.value.busy }
        val prepared = first.state.value
        assertNotNull(prepared.recoveryCode)
        val binding = requireNotNull(prepared.setupBinding)
        assertNull(first.view.value)
        first.recoverySaved(binding)
        await { first.state.value.stage == LanSyncGroupPanelStage.READY && !first.state.value.busy && first.view.value != null }
        assertEquals(1, f.setupCalls)
        assertNull(first.state.value.error)
        assertNull(first.state.value.recoveryCode)
        assertFalse(first.state.value.managementUnlocked)
        assertNotNull(first.state.value.local.lastSuccessAt)
        assertNull(first.state.value.nearby.lastSuccessAt)
        assertNull(first.state.value.cloud.lastSuccessAt)
        assertContentEquals(original, Files.readAllBytes(f.card))
        val access = requireNotNull(f.founder().runtimeAccess())
        val operations = access.node.sourceOperations()
        assertEquals(1, operations.size)
        assertNotNull(access.node.receipt(operations.single(), access.localTarget))
        assertTrue(requireNotNull(first.view.value?.plan).units.values.all { it.stage == LanSyncGroupNativeStage.UNCHANGED })
        first.lockManagement()
        val previous = first.view.value
        first.open()
        await { first.view.value !== previous && !first.state.value.busy }
        assertNull(first.state.value.error)
        assertFalse(first.state.value.managementUnlocked)
        first.closeAndDrain()

        val restarted = f.coordinator()
        restarted.startIfConfigured()
        await { restarted.state.value.stage == LanSyncGroupPanelStage.READY && !restarted.state.value.busy && restarted.view.value != null }
        assertNull(restarted.state.value.error)
        assertFalse(restarted.state.value.managementUnlocked)
        assertNull(restarted.state.value.recoveryCode)
        assertNull(restarted.state.value.local.lastSuccessAt, "Historical receipts have no invented wall-clock time")
        assertEquals(operations, requireNotNull(f.founder().runtimeAccess()).node.sourceOperations())
        assertContentEquals(original, Files.readAllBytes(f.card))
        assertEquals(1, f.setupCalls)
    }

    @Test fun localDeletionAfterInitialPublicationRemainsUnsignedAndVisibleForReview() = fixture { f ->
        f.writeCard("synthetic retained original")
        val coordinator = f.coordinator()
        coordinator.createGroup()
        await { coordinator.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE && !coordinator.state.value.busy }
        coordinator.recoverySaved(requireNotNull(coordinator.state.value.setupBinding))
        await { coordinator.state.value.stage == LanSyncGroupPanelStage.READY && !coordinator.state.value.busy && coordinator.view.value != null }
        val access = requireNotNull(f.founder().runtimeAccess())
        val operations = access.node.sourceOperations()
        assertEquals(1, operations.size)
        val entries = access.replica.listCommits(null).entries
        Files.delete(f.card)
        coordinator.lockManagement()
        coordinator.retry()
        await { coordinator.view.value?.plan?.units?.get("character_cards/card")?.stage == LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW && !coordinator.state.value.busy }
        assertNull(coordinator.state.value.error)
        assertFalse(Files.exists(f.card))
        assertEquals(operations, access.node.sourceOperations())
        assertEquals(entries, access.replica.listCommits(null).entries)
        val review = requireNotNull(coordinator.view.value).review(setOf("character_cards/card"))
        assertEquals(setOf("character_cards/card"), review.requiredBindings.keys)
        assertFailsWith<IllegalArgumentException> { review.authorize() }
        assertFalse(coordinator.state.value.managementUnlocked)
    }
    private suspend fun readyCoordinator(f: Fixture): LanSyncGroupCoordinator {
        val coordinator = f.coordinator()
        coordinator.createGroup()
        await { coordinator.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE && !coordinator.state.value.busy }
        coordinator.recoverySaved(requireNotNull(coordinator.state.value.setupBinding))
        await { coordinator.state.value.stage == LanSyncGroupPanelStage.READY && !coordinator.state.value.busy && coordinator.view.value != null }
        assertNull(coordinator.state.value.error)
        return coordinator
    }

    private fun retainedFiles(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        paths.iterator().asSequence().filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.associate { path ->
            root.relativize(path).toString() to LanSyncResourceStore.hashFile(path, Files.size(path))
        }
    }

    private fun assertSourceOriginal(f: Fixture, entry: SyncAuthorJournalEntry, original: ByteArray) =
        assertRecoveryOriginal(f, entry.request.sourceCheckpoint, original)

    private fun assertRecoveryOriginal(f: Fixture, transaction: String, original: ByteArray) {
        val custody = requireNotNull(f.workspace.recovery.read(transaction))
        assertTrue(custody.hasLocalOriginals)
        f.workspace.recovery.verify(custody)
        var found = false
        f.workspace.recovery.originals(custody) { row, path ->
            if (row.relative == "entities/character_cards/card.json") {
                found = true
                assertContentEquals(original, Files.readAllBytes(path))
            }
        }
        assertTrue(found, "The independently sealed source checkpoint must retain the exact original JSON")
    }

    @Test fun preparedDeletionDoesNotPublishUntilExplicitConfirmationThenRetainsOneSignedTombstoneAndOriginals() = fixture { f ->
        val unit = "character_cards/card"
        f.writeCard("synthetic original before confirmed deletion")
        val original = Files.readAllBytes(f.card)
        val coordinator = readyCoordinator(f)
        val access = requireNotNull(f.founder().runtimeAccess())
        val author = f.author(access.trust)
        val firstOperation = access.node.sourceOperations().single()
        val firstEntry = requireNotNull(author.entry(firstOperation))
        val origin = Files.readAllBytes(f.profile.resolve("node/author/origin.protected"))
        val objects = retainedFiles(f.profile.resolve("local-replica"))
        assertEquals(1L, author.state().committedSequence)
        assertSourceOriginal(f, firstEntry, original)
        Files.delete(f.card)
        coordinator.retry()
        await { coordinator.view.value?.plan?.units?.get(unit)?.stage == LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW && !coordinator.state.value.busy }
        val frozen = requireNotNull(coordinator.view.value)
        coordinator.prepareSourceReview(frozen, unit)
        await { coordinator.actionsReview.value != null && !coordinator.state.value.busy }
        val action = requireNotNull(coordinator.actionsReview.value)
        val review = requireNotNull(action.source)
        assertNull(coordinator.state.value.error)
        assertTrue(action.canConfirm)
        assertNull(action.incoming)
        assertNull(action.heldReason)
        assertEquals(setOf(unit), review.requiredBindings.keys)
        assertTrue(review.units.getValue(unit).current.isEmpty())
        assertEquals(setOf(firstEntry.version.reference()), review.units.getValue(unit).versions.keys)
        assertEquals(listOf("synthetic original before confirmed deletion"), review.units.getValue(unit).versions.values.single().map {
            it.body.getValue("name").jsonPrimitive.content
        })
        assertEquals(listOf(firstOperation), access.node.sourceOperations())
        assertEquals(1L, author.state().committedSequence)
        assertEquals(objects, retainedFiles(f.profile.resolve("local-replica")), "Preparing review must not publish any new ciphertext or bind a store put")
        assertFalse(Files.exists(f.card))
        coordinator.confirmActionReview(action.binding)
        await { coordinator.actionsReview.value == null && !coordinator.state.value.busy }
        assertNull(coordinator.state.value.error)
        val operations = access.node.sourceOperations()
        assertEquals(2, operations.size)
        assertEquals(firstOperation, operations.first())
        val tombstone = requireNotNull(author.entry(operations.last()))
        assertEquals(2L, author.state().committedSequence)
        assertEquals(2L, tombstone.version.authorSequence)
        assertEquals(firstEntry.version.reference(), tombstone.version.previousByAuthor)
        assertEquals(setOf(unit), tombstone.version.changes.keys)
        val change = tombstone.version.changes.getValue(unit)
        assertNull(change.valueHash)
        assertNull(change.content)
        assertEquals(setOf(firstEntry.version.reference()), change.previous)
        assertNotNull(access.node.receipt(operations.last(), access.localTarget))
        assertContentEquals(origin, Files.readAllBytes(f.profile.resolve("node/author/origin.protected")))
        assertFalse(Files.exists(f.card))
        assertSourceOriginal(f, firstEntry, original)
        coordinator.retry()
        await { coordinator.view.value?.nativeState?.units?.get(unit)?.frontiers == setOf(tombstone.version.reference()) && !coordinator.state.value.busy }
        assertEquals(operations, access.node.sourceOperations())
        assertEquals(2L, author.state().committedSequence)
    }

    @Test fun stalePreparedDeletionRefusesNewRawUserEditAndDismissedBindingCannotPublish() = fixture { f ->
        val unit = "character_cards/card"
        f.writeCard("synthetic original before stale deletion")
        val original = Files.readAllBytes(f.card)
        val coordinator = readyCoordinator(f)
        val access = requireNotNull(f.founder().runtimeAccess())
        val author = f.author(access.trust)
        val operations = access.node.sourceOperations()
        val firstEntry = requireNotNull(author.entry(operations.single()))
        Files.delete(f.card)
        coordinator.retry()
        await { coordinator.view.value?.plan?.units?.get(unit)?.stage == LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW && !coordinator.state.value.busy }
        val frozen = requireNotNull(coordinator.view.value)
        val nativeBefore = frozen.nativeState
        coordinator.prepareSourceReview(frozen, unit)
        await { coordinator.actionsReview.value != null && !coordinator.state.value.busy }
        val action = requireNotNull(coordinator.actionsReview.value)
        assertTrue(action.canConfirm)
        val objects = retainedFiles(f.profile.resolve("local-replica"))
        val recovery = f.workspace.recovery.list()
        f.writeCard("synthetic later user edit must survive stale confirmation")
        val edited = Files.readAllBytes(f.card)
        coordinator.confirmActionReview(action.binding)
        await { !coordinator.state.value.busy && coordinator.state.value.error != null }
        assertTrue(requireNotNull(coordinator.state.value.error).endsWith("SYNC_GROUP_SOURCE_CHANGED"))
        assertSame(action, coordinator.actionsReview.value)
        assertContentEquals(edited, Files.readAllBytes(f.card))
        assertEquals(operations, access.node.sourceOperations())
        assertEquals(1L, author.state().committedSequence)
        assertNull(author.state().pendingApproved)
        assertEquals(nativeBefore, access.node.inspect().nativeState)
        assertEquals(objects, retainedFiles(f.profile.resolve("local-replica")))
        assertEquals(recovery, f.workspace.recovery.list())
        assertSourceOriginal(f, firstEntry, original)
        coordinator.cancelActionReview()
        assertNull(coordinator.actionsReview.value)
        coordinator.confirmActionReview(action.binding)
        await { !coordinator.state.value.busy && coordinator.state.value.error?.endsWith("SYNC_GROUP_REVIEW_DISMISSED") == true }
        assertEquals(operations, access.node.sourceOperations())
        assertEquals(1L, author.state().committedSequence)
        assertNull(author.state().pendingApproved)
        assertEquals(nativeBefore, access.node.inspect().nativeState)
        assertContentEquals(edited, Files.readAllBytes(f.card))
        assertEquals(objects, retainedFiles(f.profile.resolve("local-replica")))
        assertSourceOriginal(f, firstEntry, original)
    }

    @Test fun recoveryActionsBrowseFrozenOriginalAndOnlyConfirmedRestorePublishesAFreshRevisionWithBothRawPreimages() = fixture { f ->
        val unit = "character_cards/card"
        f.writeCard("synthetic signed recovery original")
        val original = Files.readAllBytes(f.card)
        val coordinator = readyCoordinator(f)
        val access = requireNotNull(f.founder().runtimeAccess())
        val author = f.author(access.trust)
        val initialOperation = access.node.sourceOperations().single()
        val initial = requireNotNull(author.entry(initialOperation))
        val initialNative = requireNotNull(coordinator.view.value).nativeState
        f.writeCard("synthetic unpublished user edit before recovery")
        val edited = Files.readAllBytes(f.card)
        val objects = retainedFiles(f.profile.resolve("local-replica"))
        coordinator.refreshRecovery()
        await { coordinator.recoveryEntries.value.any { it.transaction == initial.request.sourceCheckpoint } && !coordinator.state.value.busy }
        val historical = coordinator.recoveryEntries.value.single { it.transaction == initial.request.sourceCheckpoint }
        assertTrue(historical.hasLocalOriginals)
        assertSourceOriginal(f, initial, original)
        coordinator.openRecovery(historical.transaction)
        await { coordinator.recoveryView.value?.entry?.transaction == historical.transaction && !coordinator.state.value.busy }
        val browser = requireNotNull(coordinator.recoveryView.value)
        assertEquals(historical, browser.entry)
        assertEquals(setOf(unit), browser.units.keys)
        assertEquals(listOf("synthetic signed recovery original"), browser.units.getValue(unit).map { it.body.getValue("name").jsonPrimitive.content })
        coordinator.prepareRestore(unit)
        await { coordinator.actionsReview.value?.recovery != null && !coordinator.state.value.busy }
        val cancelled = requireNotNull(coordinator.actionsReview.value)
        val firstPreview = requireNotNull(cancelled.recovery)
        assertTrue(cancelled.canConfirm)
        assertTrue(firstPreview.hasChanges)
        assertNull(cancelled.source)
        assertNull(cancelled.incoming)
        assertEquals(setOf(unit), firstPreview.restoredUnits)
        assertEquals(listOf("synthetic unpublished user edit before recovery"), firstPreview.currentRecords.map { it.body.getValue("name").jsonPrimitive.content })
        assertEquals(listOf("synthetic signed recovery original"), firstPreview.restoredRecords.map { it.body.getValue("name").jsonPrimitive.content })
        assertContentEquals(edited, Files.readAllBytes(f.card))
        assertEquals(listOf(initialOperation), access.node.sourceOperations())
        assertEquals(1L, author.state().committedSequence)
        assertEquals(initialNative, access.node.inspect().nativeState)
        assertEquals(objects, retainedFiles(f.profile.resolve("local-replica")))
        assertTrue(f.workspace.completedRecoveries(f.identity.fingerprint).isEmpty())
        coordinator.cancelActionReview()
        coordinator.confirmActionReview(cancelled.binding)
        await { !coordinator.state.value.busy && coordinator.state.value.error?.endsWith("SYNC_GROUP_REVIEW_DISMISSED") == true }
        assertContentEquals(edited, Files.readAllBytes(f.card))
        assertTrue(f.workspace.completedRecoveries(f.identity.fingerprint).isEmpty())
        assertEquals(1L, author.state().committedSequence)
        coordinator.prepareRestore(unit)
        await { coordinator.actionsReview.value?.recovery != null && !coordinator.state.value.busy }
        val confirmed = requireNotNull(coordinator.actionsReview.value)
        val preview = requireNotNull(confirmed.recovery)
        assertNotEquals(firstPreview.id, preview.id)
        assertSame(browser, coordinator.recoveryView.value)
        assertContentEquals(edited, Files.readAllBytes(f.card))
        coordinator.confirmActionReview(confirmed.binding)
        await { coordinator.actionsReview.value == null && !coordinator.state.value.busy }
        assertNull(coordinator.state.value.error)
        assertEquals("synthetic signed recovery original", requireNotNull(LanSyncJsonFiles.read(f.card, CharacterCard.serializer())).name)
        val completed = f.workspace.completedRecoveries(f.identity.fingerprint).single { it.revision == preview.id }
        assertTrue(completed.applied)
        assertEquals(setOf(unit), completed.restoredUnits)
        assertEquals(historical.transaction, completed.sourceTransaction)
        assertEquals(preview.binding, completed.previewBinding)
        assertRecoveryOriginal(f, preview.id, edited)
        assertSourceOriginal(f, initial, original)
        val operations = access.node.sourceOperations()
        assertEquals(2, operations.size)
        assertEquals(initialOperation, operations.first())
        assertEquals(2L, author.state().committedSequence)
        val restored = requireNotNull(author.entry(operations.last()))
        assertEquals(2L, restored.version.authorSequence)
        assertEquals(initial.version.reference(), restored.version.previousByAuthor)
        assertNotEquals(initial.version.reference(), restored.version.reference())
        assertEquals(initial.version.changes.getValue(unit).valueHash, restored.version.changes.getValue(unit).valueHash,
            "Restoring an already signed value must remain a fresh logical revision")
        assertNotNull(access.node.receipt(operations.last(), access.localTarget))
        assertEquals(setOf(restored.version.reference()), access.node.inspect().nativeState.units.getValue(unit).frontiers)
        assertEquals(LanSyncGroupNativeStage.UNCHANGED, access.node.inspect().plan?.units?.get(unit)?.stage)
        val allRecovery = f.workspace.recovery.list()
        assertTrue(allRecovery.any { it.transaction == historical.transaction })
        assertTrue(allRecovery.any { it.transaction == preview.id })
        allRecovery.forEach(f.workspace.recovery::verify)
        coordinator.closeRecovery()
        assertNull(coordinator.recoveryView.value)
        assertNull(coordinator.actionsReview.value)
        assertEquals(allRecovery, f.workspace.recovery.list())
        assertEquals(operations, access.node.sourceOperations())
        assertSourceOriginal(f, initial, original)
        assertRecoveryOriginal(f, preview.id, edited)
    }

}
