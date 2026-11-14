package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
}
