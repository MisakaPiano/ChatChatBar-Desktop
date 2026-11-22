package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.WebDavEndpointPolicy
import com.example.chatbar.domain.syncStore.webdav.WeakFixture
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
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

    private class RecoveryCloseProbe(network: LanSyncNetwork) {
        val factories = AtomicInteger(); val roots = AtomicInteger(); val joins = AtomicInteger()
        val founders = AtomicInteger(); val ready = AtomicInteger(); val http = AtomicInteger()
        private val client = OkHttpClient.Builder().addInterceptor {
            http.incrementAndGet(); error("SYNTHETIC_CLOSED_RECOVERY_MUST_NOT_USE_HTTP")
        }.build()
        val coordinator = LanSyncGroupCoordinator({ false },
            { founders.incrementAndGet(); error("SYNTHETIC_CLOSED_RECOVERY_MUST_NOT_OPEN_FOUNDER") },
            { joins.incrementAndGet(); error("SYNTHETIC_CLOSED_RECOVERY_MUST_NOT_OPEN_JOINING") }, network,
            "synthetic closed device", cloudRecoveryFactory = { callback ->
                factories.incrementAndGet()
                LanSyncGroupCloudRecoveryOwner(
                    root = { roots.incrementAndGet(); error("SYNTHETIC_CLOSED_RECOVERY_MUST_NOT_CREATE_ROOT") },
                    joining = { joins.incrementAndGet(); error("SYNTHETIC_CLOSED_RECOVERY_MUST_NOT_CREATE_JOINING") },
                    deviceName = "synthetic closed device", onReady = { ready.incrementAndGet(); callback(it) },
                    client = client, endpointPolicy = WebDavEndpointPolicy.HTTPS_ONLY)
            })
        fun assertUntouched(factoryCount: Int) {
            assertEquals(factoryCount, factories.get()); assertEquals(0, roots.get()); assertEquals(0, joins.get())
            assertEquals(0, founders.get()); assertEquals(0, ready.get()); assertEquals(0, http.get())
        }
        suspend fun close() {
            try { coordinator.closeAndDrain() }
            finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        }
    }

    @Test fun aNeverOpenedRecoveryFactoryCannotBeCreatedAfterCoordinatorQuiescence() = runBlocking {
        val probe = RecoveryCloseProbe(noNetwork)
        try {
            probe.coordinator.quiesceAndDrain()
            repeat(2) { assertNull(probe.coordinator.cloudRecovery) }
            probe.coordinator.open(); probe.coordinator.startIfConfigured()
            assertNull(probe.coordinator.cloudRecovery)
            probe.assertUntouched(0)
        } finally { probe.close() }
    }

    @Test fun aRetainedRecoveryReferenceCannotReopenOrPrepareAfterCoordinatorQuiescence() = runBlocking {
        val probe = RecoveryCloseProbe(noNetwork)
        try {
            val retained = requireNotNull(probe.coordinator.cloudRecovery)
            retained.open(); assertTrue(retained.state.value.visible)
            probe.assertUntouched(1)
            probe.coordinator.quiesceAndDrain()
            assertNull(probe.coordinator.cloudRecovery)
            // Use a valid code so an accidentally live owner cannot pass merely by rejecting input.
            val code = SyncManagementSession.create().use { session ->
                session.seal(SyncGroupTrust(SyncStoreId.random(),
                    SyncVersionRef(SyncStoreId.random(), "a".repeat(64)), session.ownerCertificate))
                session.recoveryCode()
            }
            val password = "synthetic password".toByteArray(Charsets.UTF_8)
            try {
                retained.open()
                retained.prepare(LanSyncGroupCloudDraft("synthetic recovery", "https://synthetic.invalid/group/",
                    "synthetic user", password), code)
            } finally { password.fill(0) }
            withTimeout(5_000) { retained.drain() }
            assertFalse(retained.state.value.visible); assertFalse(retained.state.value.busy)
            assertFalse(retained.state.value.complete); assertNull(retained.state.value.review)
            probe.assertUntouched(1)
        } finally { probe.close() }
    }

    @Test fun anActualPendingSetupRejectsBothExternalRecoveryEntriesBeforeTheirFactoriesOrInput() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val factories = AtomicInteger(); val inputs = AtomicInteger()
        val code = SyncManagementSession.create().use { session ->
            session.seal(SyncGroupTrust(SyncStoreId.random(),
                SyncVersionRef(SyncStoreId.random(), "a".repeat(64)), session.ownerCertificate))
            session.recoveryCode()
        }
        val coordinator = LanSyncGroupCoordinator(hasExistingEvidence = { false },
            enrollment = { factories.incrementAndGet(); error("SYNTHETIC_HELD_SETUP_MUST_NOT_OPEN_FOUNDER") },
            joining = { factories.incrementAndGet(); error("SYNTHETIC_HELD_SETUP_MUST_NOT_OPEN_JOINING") },
            network = noNetwork, deviceName = "synthetic pending setup", initiallyPaused = true,
            beforeSetup = {
                entered.complete(Unit); release.await()
                error("SYNTHETIC_SETUP_STOPS_BEFORE_IDENTITY")
            },
            cloudRecoveryFactory = { factories.incrementAndGet(); error("SYNTHETIC_BUSY_SETUP_MUST_NOT_OPEN_CLOUD_RECOVERY") },
            fileRecoveryFactory = { factories.incrementAndGet(); error("SYNTHETIC_BUSY_SETUP_MUST_NOT_OPEN_FILE_RECOVERY") })
        val password = "synthetic unused password".toByteArray(Charsets.UTF_8)
        try {
            coordinator.createGroup()
            withTimeout(5_000) { entered.await() }
            val held = coordinator.state.value
            assertEquals(LanSyncGroupPanelStage.EMPTY, held.stage); assertTrue(held.busy)
            val draft = LanSyncGroupCloudDraft("synthetic pending setup", "https://synthetic.invalid/group/", "synthetic user", password)
            repeat(2) {
                coordinator.openCloudRecovery(); coordinator.openFileRecovery()
                assertFalse(coordinator.prepareCloudRecovery(draft, code))
                assertFalse(coordinator.prepareFileRecovery({ inputs.incrementAndGet(); ByteArrayInputStream(byteArrayOf()) }, code))
                assertEquals(held, coordinator.state.value)
                assertEquals(0, factories.get()); assertEquals(0, inputs.get())
            }
        } finally {
            password.fill(0); release.complete(Unit); coordinator.closeAndDrain()
        }
    }

    @Test fun busyRecoveryPreparationRejectsTheOtherEntryWithoutOpeningItsStreamOrReplacingProgress() = runBlocking {
        val cloudEntered = CompletableDeferred<Unit>(); val cloudRelease = CompletableDeferred<Unit>()
        val fileEntered = CompletableDeferred<Unit>(); val fileRelease = CompletableDeferred<Unit>()
        val roots = AtomicInteger(); val joins = AtomicInteger(); val inputs = AtomicInteger(); val ready = AtomicInteger()
        val code = SyncManagementSession.create().use { session ->
            session.seal(SyncGroupTrust(SyncStoreId.random(),
                SyncVersionRef(SyncStoreId.random(), "a".repeat(64)), session.ownerCertificate))
            session.recoveryCode()
        }
        val coordinator = LanSyncGroupCoordinator(hasExistingEvidence = { false },
            enrollment = { error("SYNTHETIC_BUSY_RECOVERY_MUST_NOT_OPEN_FOUNDER") },
            joining = { joins.incrementAndGet(); error("SYNTHETIC_BUSY_RECOVERY_MUST_NOT_OPEN_JOINING") },
            network = noNetwork, deviceName = "synthetic busy recovery", initiallyPaused = true,
            cloudRecoveryFactory = { callback ->
                LanSyncGroupCloudRecoveryOwner(
                    root = { roots.incrementAndGet(); error("SYNTHETIC_GATE_MUST_PRECEDE_CLOUD_ROOT") },
                    joining = { joins.incrementAndGet(); error("SYNTHETIC_GATE_MUST_PRECEDE_JOINING") },
                    deviceName = "synthetic busy cloud", cloudWork = { work ->
                        cloudEntered.complete(Unit); cloudRelease.await(); work()
                    }, onReady = { ready.incrementAndGet(); callback(it) })
            }, fileRecoveryFactory = { callback ->
                LanSyncGroupFileRecoveryOwner(
                    root = { roots.incrementAndGet(); error("SYNTHETIC_GATE_MUST_PRECEDE_FILE_ROOT") },
                    joining = { joins.incrementAndGet(); error("SYNTHETIC_GATE_MUST_PRECEDE_JOINING") },
                    deviceName = "synthetic busy file", dataWork = { work ->
                        fileEntered.complete(Unit); fileRelease.await(); work()
                    }, onReady = { ready.incrementAndGet(); callback(it) })
            })
        fun input(): InputStream { inputs.incrementAndGet(); return ByteArrayInputStream(byteArrayOf()) }
        val password = "synthetic held password".toByteArray(Charsets.UTF_8)
        val draft = LanSyncGroupCloudDraft("synthetic held recovery", "https://synthetic.invalid/group/", "synthetic user", password)
        try {
            coordinator.openCloudRecovery()
            assertTrue(coordinator.prepareCloudRecovery(draft, code))
            withTimeout(5_000) { cloudEntered.await() }
            val cloud = requireNotNull(coordinator.cloudRecovery)
            val heldCloud = cloud.state.value
            assertTrue(heldCloud.busy); assertTrue(heldCloud.visible)
            assertFalse(coordinator.prepareFileRecovery(::input, code))
            coordinator.openFileRecovery()
            assertEquals(heldCloud, cloud.state.value)
            val file = requireNotNull(coordinator.fileRecovery)
            assertFalse(file.state.value.visible); assertFalse(file.state.value.busy)
            assertEquals(0, inputs.get()); assertEquals(0, roots.get()); assertEquals(0, joins.get()); assertEquals(0, ready.get())
            coordinator.lockManagement(); withTimeout(5_000) { cloud.drain() }

            coordinator.openFileRecovery()
            assertTrue(coordinator.prepareFileRecovery(::input, code))
            withTimeout(5_000) { fileEntered.await() }
            val heldFile = file.state.value
            assertTrue(heldFile.busy); assertTrue(heldFile.visible)
            assertFalse(coordinator.prepareCloudRecovery(draft, code))
            coordinator.openCloudRecovery()
            assertEquals(heldFile, file.state.value)
            assertFalse(cloud.state.value.visible); assertFalse(cloud.state.value.busy)
            assertEquals(0, inputs.get()); assertEquals(0, roots.get()); assertEquals(0, joins.get()); assertEquals(0, ready.get())
            coordinator.lockManagement(); withTimeout(5_000) { file.drain() }
            assertEquals(0, inputs.get()); assertEquals(0, roots.get()); assertEquals(0, joins.get()); assertEquals(0, ready.get())
        } finally {
            password.fill(0); coordinator.lockManagement()
            cloudRelease.complete(Unit); fileRelease.complete(Unit); coordinator.closeAndDrain()
        }
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
        var recoveryCode: String? = null
        val reloads = AtomicInteger()
        @Volatile var beforeExclusive: suspend () -> Unit = {}
        val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) { beforeExclusive(); work() }
            override suspend fun reload(sessionIds: Set<String>) { reloads.incrementAndGet() }
        }
        fun founder() = founder(workspace)
        fun founder(activeWorkspace: LanSyncWorkspace, observe: (String) -> Unit = {}) =
            LanSyncGroupEnrollment(profile, activeWorkspace, platform, identity,
                { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> }, observe).also(owners::add)
        fun coordinator(sourceChanges: Flow<Long>? = null, initiallyPaused: Boolean = false,
            enrollment: () -> LanSyncGroupEnrollment = ::founder, beforeSetup: suspend () -> Unit = {}) =
            LanSyncGroupCoordinator({ Files.exists(profile.resolve("profile.protected")) || Files.exists(profile.resolve("creation")) ||
                Files.exists(profile.resolve("abandon.protected")) },
                enrollment, { error("SYNTHETIC_FOUNDER_MUST_NOT_OPEN_JOINING") }, network, "synthetic PC",
                { setupCalls++; beforeSetup() }, sourceChanges, initiallyPaused).also(coordinators::add)
        fun author(trust: SyncGroupTrust) = SyncAuthorJournal(profile.resolve("node/author"), trust,
            Base64.getEncoder().encodeToString(identity.certificate.encoded), { it.reversedArray() }, { it.reversedArray() })
        fun custody(trust: SyncGroupTrust): SyncGroupCustody {
            val record = lanSyncJson.parseToJsonElement(Files.readAllBytes(profile.resolve("profile.protected"))
                .reversedArray().toString(Charsets.UTF_8)).jsonObject
            val recipient = SyncDeviceKeyCustody(profile.resolve("recipient"), identity.fingerprint,
                { it.reversedArray() }, { it.reversedArray() }).open(record.getValue("recipient").jsonPrimitive.content)
            return SyncGroupCustody(profile.resolve("custody"), trust.group, recipient, { it.reversedArray() }, { it.reversedArray() })
        }
        fun writeCard(name: String) {
            Files.createDirectories(card.parent)
            LanSyncJsonFiles.write(card, CharacterCard.serializer(), CharacterCard("card", name, createdAt = 1, updatedAt = 1))
        }
        suspend fun close() { coordinators.forEach { it.closeAndDrain() }; owners.forEach { it.close() } }
    }
    private fun fixture(network: LanSyncNetwork = noNetwork, work: suspend (Fixture) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-group-coordinator-").toRealPath()
        val f = Fixture(root, network)
        try { work(f) } finally { f.close(); check(root.toFile().deleteRecursively()) }
    }

    // Positive EMPTY admission is covered by busyRecoveryPreparationRejectsTheOtherEntryWithoutOpeningItsStreamOrReplacingProgress.
    // CloudRecoveryOwnerTest also covers the real PREPARED joining handoff to file recovery and recovered READY entry continuation.
    @Test fun anActualFounderReadyProfileRejectsBothExternalRecoveryEntriesBeforeAnyRecoveryFactoryOrInputIsTouched() = fixture { f ->
        val cloudFactories = AtomicInteger(); val fileFactories = AtomicInteger(); val joiningFactories = AtomicInteger(); val inputs = AtomicInteger()
        val coordinator = LanSyncGroupCoordinator(
            hasExistingEvidence = { Files.exists(f.profile.resolve("profile.protected")) || Files.exists(f.profile.resolve("creation")) },
            enrollment = f::founder,
            joining = { joiningFactories.incrementAndGet(); error("SYNTHETIC_FOUNDER_READY_MUST_NOT_CREATE_JOINING") },
            network = noNetwork, deviceName = "synthetic founder PC", initiallyPaused = true,
            cloudRecoveryFactory = { cloudFactories.incrementAndGet(); error("SYNTHETIC_FOUNDER_READY_MUST_NOT_CREATE_CLOUD_RECOVERY") },
            fileRecoveryFactory = { fileFactories.incrementAndGet(); error("SYNTHETIC_FOUNDER_READY_MUST_NOT_CREATE_FILE_RECOVERY") })
        val password = "synthetic unused recovery password".toByteArray(Charsets.UTF_8)
        try {
            coordinator.createGroup()
            await { coordinator.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE && !coordinator.state.value.busy }
            // Use the actual valid code, so rejection cannot be an invalid-input shortcut.
            val code = requireNotNull(coordinator.state.value.recoveryCode)
            coordinator.recoverySaved(requireNotNull(coordinator.state.value.setupBinding))
            await { coordinator.state.value.stage == LanSyncGroupPanelStage.READY && !coordinator.state.value.busy }
            assertEquals(LanSyncGroupEnrollmentStage.READY, requireNotNull(f.founder().profile()).stage)
            assertTrue(coordinator.state.value.localManagementAvailable)
            assertFalse(coordinator.state.value.canResumeExternalRecovery)
            assertTrue(coordinator.state.value.paused)
            val originalState = coordinator.state.value
            val originalFiles = retainedFiles(f.root)
            val originalDirectories = Files.walk(f.root).use { paths ->
                paths.filter { Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.map { f.root.relativize(it).toString() }.toList().toSet()
            }
            val draft = LanSyncGroupCloudDraft("synthetic unused recovery", "https://synthetic.invalid/group/", "synthetic user", password)
            repeat(2) {
                coordinator.openCloudRecovery()
                coordinator.openFileRecovery()
                assertFalse(coordinator.prepareCloudRecovery(draft, code))
                assertFalse(coordinator.prepareFileRecovery({ inputs.incrementAndGet(); error("SYNTHETIC_FOUNDER_READY_MUST_NOT_OPEN_INPUT") }, code))
            }
            assertEquals(0, cloudFactories.get()); assertEquals(0, fileFactories.get())
            assertEquals(0, joiningFactories.get()); assertEquals(0, inputs.get())
            assertEquals(originalState, coordinator.state.value)
            assertEquals(originalFiles, retainedFiles(f.root))
            val remainingDirectories = Files.walk(f.root).use { paths ->
                paths.filter { Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.map { f.root.relativize(it).toString() }.toList().toSet()
            }
            assertEquals(originalDirectories, remainingDirectories)
            assertFalse(Files.exists(f.root.resolve("cloud-recovery"))); assertFalse(Files.exists(f.root.resolve("file-recovery")))
        } finally { password.fill(0); coordinator.closeAndDrain() }
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

    @Test fun reopeningAPausedLiveFounderDraftPreservesItsSaveCodeFormButNeverResurrectsALockedCode() = fixture { f ->
        val coordinator = f.coordinator(initiallyPaused = true)
        coordinator.createGroup()
        await { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE }
        val code = requireNotNull(coordinator.state.value.recoveryCode)
        val binding = requireNotNull(coordinator.state.value.setupBinding)
        val prepared = Files.readAllBytes(f.profile.resolve("profile.protected"))
        suspend fun reopen() = coroutineScope {
            val completed = CompletableDeferred<Unit>()
            val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                var entered = false
                coordinator.state.collect { state ->
                    if (state.busy) entered = true else if (entered) completed.complete(Unit)
                }
            }
            try { coordinator.open(); withTimeout(5_000) { completed.await() } }
            finally { observer.cancelAndJoin() }
        }
        reopen()
        assertTrue(coordinator.state.value.paused)
        assertEquals(LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE, coordinator.state.value.stage)
        assertEquals(code, coordinator.state.value.recoveryCode)
        assertEquals(binding, coordinator.state.value.setupBinding)
        assertTrue(coordinator.state.value.managementUnlocked)
        assertContentEquals(prepared, Files.readAllBytes(f.profile.resolve("profile.protected")))
        assertNull(coordinator.view.value)
        assertFalse(Files.exists(f.profile.resolve("node")))
        coordinator.lockManagement()
        reopen()
        assertEquals(LanSyncGroupPanelStage.PREPARED_LOCKED, coordinator.state.value.stage)
        assertNull(coordinator.state.value.recoveryCode)
        assertNull(coordinator.state.value.setupBinding)
        assertFalse(coordinator.state.value.managementUnlocked)
        assertContentEquals(prepared, Files.readAllBytes(f.profile.resolve("profile.protected")))
        coordinator.unlockManagement(code)
        await { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE }
        assertEquals(binding, coordinator.state.value.setupBinding)
        coordinator.recoverySaved(binding)
        await { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.READY }
        assertTrue(coordinator.state.value.paused)
        assertEquals(0L, f.author(requireNotNull(f.founder().profile()).trust).state().committedSequence)
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

    @Test fun coldPageOffersExplicitArchiveForAnUnusedFounderBeforeItsProfileWasWritten() = fixture { f ->
        f.writeCard("synthetic untouched conversation data")
        val entity = Files.readAllBytes(f.card)
        val first = f.coordinator(enrollment = { f.founder(f.workspace) { phase ->
            if (phase == "APPROVED_GROUP_CREATION_RETAINED") throw IOException("SYNTHETIC_FIRST_CREATION_INTERRUPTED")
        } })
        first.createGroup()
        await { !first.state.value.busy && first.state.value.error != null }
        val intent = Files.readAllBytes(f.profile.resolve("creation/intent.protected"))
        assertNull(f.founder().profile())
        assertTrue(f.founder().canAbandonUnactivated())
        first.closeAndDrain()

        val cold = f.coordinator(initiallyPaused = true)
        cold.open()
        await { !cold.state.value.busy && (cold.state.value.stage != LanSyncGroupPanelStage.EMPTY || cold.state.value.error != null) }
        val opened = cold.state.value
        assertEquals(LanSyncGroupPanelStage.PREPARED_LOCKED, opened.stage)
        assertTrue(opened.canAbandonUnactivated)
        assertFalse(opened.localManagementAvailable)
        assertNull(opened.recoveryCode)
        assertNull(opened.error)
        assertFalse(Files.exists(f.profile.resolve("abandon.protected")))

        cold.abandonUnusedSetup()
        await { !cold.state.value.busy && cold.state.value.stage == LanSyncGroupPanelStage.EMPTY }
        val archive = Files.newDirectoryStream(f.profile.resolve("abandoned-drafts")).use { it.single() }
        assertTrue(Files.isRegularFile(archive.resolve("completed.protected")))
        assertTrue(intent.contentEquals(Files.readAllBytes(archive.resolve("creation/intent.protected"))))
        assertContentEquals(entity, Files.readAllBytes(f.card))
        cold.createGroup()
        await { !cold.state.value.busy && cold.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE }
        assertTrue(cold.state.value.canAbandonUnactivated)
    }

    @Test fun coldPageResumesAnInterruptedArchiveAfterTheLastDraftEntryMoved() = fixture { f ->
        f.writeCard("synthetic unchanged user data")
        val entity = Files.readAllBytes(f.card)
        val inProgress = f.coordinator(enrollment = { f.founder(f.workspace) { phase ->
            if (phase == "UNACTIVATED_GROUP_ENTRY_RETAINED" && Files.newDirectoryStream(f.profile).use { paths ->
                paths.none { it.fileName.toString() in setOf("creation", "recipient", "management-vault.bin", "profile.protected") }
            }) throw IOException("SYNTHETIC_ARCHIVE_INTERRUPTED_AFTER_FINAL_MOVE")
        } })
        inProgress.createGroup()
        await { !inProgress.state.value.busy && inProgress.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE }
        val liveBytes = Files.readAllBytes(f.profile.resolve("creation/intent.protected"))
        inProgress.lockManagement()
        inProgress.abandonUnusedSetup()
        await { !inProgress.state.value.busy && inProgress.state.value.error != null }
        assertFalse(Files.exists(f.profile.resolve("profile.protected")))
        assertFalse(Files.exists(f.profile.resolve("creation")))
        val decision = Files.readAllBytes(f.profile.resolve("abandon.protected"))
        val archive = Files.newDirectoryStream(f.profile.resolve("abandoned-drafts")).use { it.single() }
        assertFalse(Files.exists(archive.resolve("completed.protected")))
        inProgress.closeAndDrain()

        val cold = f.coordinator(initiallyPaused = true)
        cold.open()
        await { !cold.state.value.busy && (cold.state.value.stage != LanSyncGroupPanelStage.EMPTY || cold.state.value.error != null) }
        val opened = cold.state.value
        assertEquals(LanSyncGroupPanelStage.PREPARED_LOCKED, opened.stage)
        assertTrue(opened.canAbandonUnactivated)
        assertFalse(opened.localManagementAvailable)
        assertNull(opened.recoveryCode)
        assertNull(opened.error)
        assertTrue(decision.contentEquals(Files.readAllBytes(f.profile.resolve("abandon.protected"))))

        cold.abandonUnusedSetup()
        await { !cold.state.value.busy && cold.state.value.stage == LanSyncGroupPanelStage.EMPTY }
        assertFalse(Files.exists(f.profile.resolve("abandon.protected")))
        assertTrue(Files.isRegularFile(archive.resolve("completed.protected")))
        assertTrue(liveBytes.contentEquals(Files.readAllBytes(archive.resolve("creation/intent.protected"))))
        assertContentEquals(entity, Files.readAllBytes(f.card))
    }

    @Test fun lateFounderMetadataRefusalKeepsTheSameSetupThroughColdResumeAndRealInvitation() = fixture { f ->
        f.writeCard("synthetic original card")
        val original = Files.readAllBytes(f.card)
        val recordCost = LanSyncMetadataBudget().validate(f.card)
        val tight = LanSyncWorkspace(f.data, f.root.resolve("workspace"), { Path.of(it) },
            metadataBudget = LanSyncMetadataBudget(maximumDecodedCost = recordCost - 1))
        val first = f.coordinator(enrollment = { f.founder(tight) })
        first.createGroup()
        await { !first.state.value.busy && first.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE }
        val code = requireNotNull(first.state.value.recoveryCode)
        val binding = requireNotNull(first.state.value.setupBinding)
        val prepared = requireNotNull(f.founder().profile())
        assertEquals(LanSyncGroupEnrollmentStage.PREPARED, prepared.stage)
        assertTrue(first.state.value.canAbandonUnactivated)
        assertFalse(Files.exists(f.profile.resolve("node")))

        first.recoverySaved(binding)
        await { !first.state.value.busy && first.state.value.error?.contains("SYNC_METADATA_WORKING_BUDGET") == true }
        assertEquals(LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE, first.state.value.stage)
        assertFalse(first.state.value.canAbandonUnactivated)
        assertFalse(first.state.value.managementCanInvite)
        assertTrue(first.state.value.error?.contains("恢复码继续原设备组") == true)
        assertEquals(prepared, f.founder().profile())
        val origins = listOf("creation/genesis.protected", "management-vault.bin", "recipient/recipient.protected",
            "custody/group.protected", "root-actions/origin.protected", "node/author/origin.protected",
            "node/native/origin.protected", "node/native/head.protected", "node/resolutions/state.protected")
            .associateWith { Files.readAllBytes(f.profile.resolve(it)) }
        val vault = SyncManagementSession.locate(code)
        val replica = FileCiphertextStore(f.profile.resolve("local-replica"))
        val retainedVault = requireNotNull(replica.metadata(vault.key))
        val rootCommits = replica.listCommits(null).entries
        assertTrue(rootCommits.isNotEmpty())
        assertEquals(0L, f.author(prepared.trust).state().committedSequence)
        assertContentEquals(original, Files.readAllBytes(f.card))

        first.createInvitation()
        await { !first.state.value.busy && first.state.value.error?.contains("SYNC_GROUP_NEARBY_NOT_AVAILABLE") == true }
        assertNull(first.state.value.invitation)
        first.pauseAndDrain()
        assertEquals(LanSyncGroupPanelStage.PREPARED_LOCKED, first.state.value.stage)
        assertNull(first.state.value.recoveryCode)
        assertNull(first.state.value.setupBinding)
        assertFalse(first.state.value.canAbandonUnactivated)
        first.closeAndDrain()

        val cold = f.coordinator(initiallyPaused = true)
        cold.open()
        await { !cold.state.value.busy && cold.state.value.stage == LanSyncGroupPanelStage.PREPARED_LOCKED }
        assertFalse(cold.state.value.canAbandonUnactivated)
        assertNull(cold.state.value.recoveryCode)
        assertNull(cold.state.value.setupBinding)
        cold.unlockManagement(code)
        await { !cold.state.value.busy && cold.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE }
        assertTrue(cold.state.value.recoveryCode == code, "The retained synthetic code must reopen the original setup")
        assertEquals(binding, cold.state.value.setupBinding)
        assertFalse(cold.state.value.canAbandonUnactivated)
        assertFalse(cold.state.value.managementCanInvite)
        assertEquals(prepared, f.founder().profile())
        assertNull(f.founder().runtimeAccess())

        cold.recoverySaved(binding)
        await { !cold.state.value.busy && cold.state.value.stage == LanSyncGroupPanelStage.READY }
        assertTrue(cold.state.value.paused)
        assertNull(cold.state.value.recoveryCode)
        assertFalse(cold.state.value.managementCanInvite)
        assertNull(cold.state.value.invitation)
        assertEquals(prepared.copy(stage = LanSyncGroupEnrollmentStage.READY), f.founder().profile())
        origins.forEach { (relative, bytes) -> assertContentEquals(bytes, Files.readAllBytes(f.profile.resolve(relative))) }
        assertEquals(retainedVault, replica.metadata(vault.key))
        assertEquals(rootCommits, replica.listCommits(null).entries)
        assertEquals(0L, f.author(prepared.trust).state().committedSequence)
        val access = requireNotNull(f.founder().runtimeAccess())
        assertTrue(access.node.sourceOperations().isEmpty())

        cold.resumeIfConfigured()
        await { !cold.state.value.busy && !cold.state.value.paused && cold.state.value.local.lastSuccessAt != null }
        val source = requireNotNull(f.author(prepared.trust).state().last)
        assertEquals(1L, source.version.authorSequence)
        val ownReceipt = requireNotNull(access.node.receipt(source.request.operation, access.localTarget))
        assertEquals(prepared.localReplica, ownReceipt.target)
        assertSourceOriginal(f, source, original)
        assertNull(cold.state.value.invitation)
        cold.unlockManagement(code)
        await { !cold.state.value.busy && cold.state.value.managementCanInvite }
        cold.createInvitation()
        await { !cold.state.value.busy && cold.state.value.invitation != null }
        val offer = requireNotNull(cold.state.value.invitation)
        val parsed = LanSyncGroupInviteOffer.parse(offer.code())
        val bootstrap = requireNotNull(parsed.bootstrap)
        assertEquals(prepared.trust.copy(checkpoint = f.custody(prepared.trust).ledger().current().reference()), parsed.trust)
        assertEquals(prepared.device, parsed.inviter)
        assertEquals(vault, bootstrap.vault)
        assertEquals(setOf(source.version.reference()), bootstrap.dataHeads)
        val localPeer = LanSyncPeer(prepared.device, "synthetic self", "10.0.0.1", bootstrap.storePort)
        assertEquals(retainedVault, access.remote(localPeer, f.network).metadata(vault.key))

        val joiningRoot = Files.createDirectory(f.root.resolve("receiver-profile"))
        val joiningData = Files.createDirectory(f.root.resolve("receiver-data"))
        val joiningWorkspace = LanSyncWorkspace(joiningData, Files.createDirectory(f.root.resolve("receiver-workspace")), { Path.of(it) })
        val joiningIdentity = LanSyncTlsIdentity.create()
        val joining = LanSyncGroupJoining(joiningRoot, joiningWorkspace, f.platform, joiningIdentity,
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> })
        val pending = joining.prepare(offer.code()) { it.validate() }
        assertEquals(LanSyncGroupJoinStage.PREPARED, pending.stage)
        assertEquals(parsed.trust, pending.trust)
        val proof = joining.proveUsing("synthetic receiving device", f.network)
        assertEquals(prepared.device, proof.inviter)
        await { !cold.state.value.busy && cold.state.value.peerConfirmation?.fingerprint == joiningIdentity.fingerprint }
        val previousLedger = access.ledger().current()
        cold.acceptPeer(requireNotNull(cold.state.value.peerConfirmation).binding)
        await { !cold.state.value.busy && cold.state.value.devices.any { it.id == joiningIdentity.fingerprint } }
        assertNull(cold.state.value.error)
        val admitted = access.ledger().current()
        assertEquals(previousLedger.reference(), admitted.parent)
        assertEquals(proof.member, admitted.members.getValue(joiningIdentity.fingerprint))

        // The joining owner uses the approved receiver identity against the pinned store listener.
        val joined = joining.activate(joining.bootstrapStore(f.network))
        assertEquals(LanSyncGroupJoinStage.READY, joined.profile.stage, joined.waitingCode)
        assertEquals(parsed.trust, joined.profile.trust)
        assertEquals(joiningIdentity.fingerprint, joined.profile.device)
        val receiver = requireNotNull(joined.node)
        val unit = "character_cards/card"
        val incoming = requireNotNull(receiver.inspect().plan)
        assertEquals(LanSyncGroupNativeStage.READY_CONTENT, incoming.units.getValue(unit).stage)
        val received = requireNotNull(receiver.apply(incoming))
        assertEquals(setOf(unit), received.advancedUnits)
        val receiverCard = joiningData.resolve("entities/character_cards/card.json")
        assertEquals("synthetic original card", requireNotNull(LanSyncJsonFiles.read(receiverCard, CharacterCard.serializer())).name)
        val reopenedReceiver = requireNotNull(joining.open()).inspect()
        val receiverApplied = reopenedReceiver.nativeState.units.getValue(unit)
        assertEquals(setOf(source.version.reference()), receiverApplied.frontiers)
        assertEquals(source.version.changes.getValue(unit).valueHash, receiverApplied.valueHash)
        assertEquals(receiverApplied.valueHash, requireNotNull(reopenedReceiver.plan).before.hashes().getValue(unit))
        val receiverAuthor = SyncAuthorJournal(joiningRoot.resolve("joining/node/author"), joined.profile.trust,
            Base64.getEncoder().encodeToString(joiningIdentity.certificate.encoded), { it.reversedArray() }, { it.reversedArray() }).state()
        assertEquals(0L, receiverAuthor.committedSequence)
        assertNull(receiverAuthor.pendingApproved)
        cold.closeInvitation()
        cold.lockManagement()
        assertNull(cold.state.value.invitation)
        assertFalse(cold.state.value.managementCanInvite)
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
    private suspend fun readyCoordinator(f: Fixture, sourceChanges: Flow<Long>? = null,
        enrollment: () -> LanSyncGroupEnrollment = f::founder): LanSyncGroupCoordinator {
        val coordinator = f.coordinator(sourceChanges, enrollment = enrollment)
        coordinator.createGroup()
        await { coordinator.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE && !coordinator.state.value.busy }
        f.recoveryCode = requireNotNull(coordinator.state.value.recoveryCode)
        coordinator.recoverySaved(requireNotNull(coordinator.state.value.setupBinding))
        await { coordinator.state.value.stage == LanSyncGroupPanelStage.READY && !coordinator.state.value.busy && coordinator.view.value != null }
        assertNull(coordinator.state.value.error)
        return coordinator
    }

    @Test fun issuedInviteCanReachApprovalAfterDesktopFocusLocksManagement() = fixture { f ->
        val coordinator = readyCoordinator(f)
        val code = requireNotNull(f.recoveryCode)
        coordinator.unlockManagement(code)
        await { !coordinator.state.value.busy && coordinator.state.value.managementCanInvite }
        coordinator.createInvitation()
        await { !coordinator.state.value.busy && coordinator.state.value.invitation != null }
        val offer = requireNotNull(coordinator.state.value.invitation)

        coordinator.lockManagementOnDesktopFocusLoss()
        assertFalse(coordinator.state.value.managementUnlocked)
        assertTrue(coordinator.state.value.invitationRetainedWhileLocked)
        assertNull(coordinator.state.value.invitation)
        assertNull(coordinator.state.value.peerConfirmation)
        val receiver = LanSyncTlsIdentity.create()
        val key = SyncDeviceEncryptionKey.create(receiver.fingerprint)
        val receipt = LanSyncGroupInviteClient.exchange(offer, receiver, key, "synthetic Android",
            { f.network.connect(receiver, LanSyncPeer(offer.inviter, "synthetic PC", offer.host, offer.port)) })
        assertEquals(offer.inviter, receipt.inviter)
        assertFalse(coordinator.state.value.managementUnlocked)
        assertNull(coordinator.state.value.peerConfirmation)
        assertFalse(receiver.fingerprint in f.custody(offer.trust).ledger().current().members)

        coordinator.unlockManagement(code)
        await { !coordinator.state.value.busy && coordinator.state.value.peerConfirmation?.fingerprint == receiver.fingerprint }
        val access = requireNotNull(f.founder().runtimeAccess())
        assertFalse(Files.exists(access.work.resolve("peer-display.protected")))
        coordinator.acceptPeer(requireNotNull(coordinator.state.value.peerConfirmation).binding)
        await { !coordinator.state.value.busy && coordinator.state.value.devices.any { it.id == receiver.fingerprint } }
        assertEquals(receipt.member, f.custody(offer.trust).ledger().current().members.getValue(receiver.fingerprint))
        assertEquals("synthetic Android", access.peerDisplayName(receiver.fingerprint))
        assertFalse(coordinator.state.value.managementUnlocked)
        assertFalse(coordinator.state.value.invitationRetainedWhileLocked)
        assertNull(coordinator.state.value.invitation)
    }

    @Test fun cancellingAfterProofRejectsTheStaleApprovalWithoutWritingNamesOrMembership() = fixture { f ->
        val coordinator = readyCoordinator(f)
        coordinator.unlockManagement(requireNotNull(f.recoveryCode))
        await { !coordinator.state.value.busy && coordinator.state.value.managementCanInvite }
        coordinator.createInvitation()
        await { !coordinator.state.value.busy && coordinator.state.value.invitation != null }
        val offer = requireNotNull(coordinator.state.value.invitation)
        val receiver = LanSyncTlsIdentity.create()
        val key = SyncDeviceEncryptionKey.create(receiver.fingerprint)
        LanSyncGroupInviteClient.exchange(offer, receiver, key, "synthetic device", {
            f.network.connect(receiver, LanSyncPeer(offer.inviter, "synthetic PC", offer.host, offer.port))
        })
        await { !coordinator.state.value.busy && coordinator.state.value.peerConfirmation != null }
        val binding = requireNotNull(coordinator.state.value.peerConfirmation).binding
        coordinator.closeInvitation()
        assertEquals("邀请已取消", coordinator.state.value.status)
        coordinator.acceptPeer(binding)
        await { !coordinator.state.value.busy && coordinator.state.value.error != null }
        val access = requireNotNull(f.founder().runtimeAccess())
        assertFalse(Files.exists(access.work.resolve("peer-display.protected")))
        assertFalse(Files.exists(access.work.resolve("peer-display.lock")))
        assertFalse(receiver.fingerprint in access.ledger().current().members)
    }

    @Test fun cancellingDuringReplacementDoesNotIssueTheQueuedInvitation() = fixture { f ->
        val coordinator = readyCoordinator(f)
        coordinator.unlockManagement(requireNotNull(f.recoveryCode))
        await { !coordinator.state.value.busy && coordinator.state.value.managementCanInvite }
        coordinator.createInvitation()
        await { !coordinator.state.value.busy && coordinator.state.value.invitation != null }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeExclusive = {
            if (coordinator.state.value.status == "正在保全本机资料并准备邀请" && entered.complete(Unit)) release.await()
        }
        try {
            coordinator.createInvitation(coordinator.state.value.invitation?.host)
            withTimeout(10_000) { entered.await() }
            coordinator.closeInvitation()
            assertEquals("邀请已取消", coordinator.state.value.status)
        } finally {
            f.beforeExclusive = {}
            release.complete(Unit)
        }
        await { !coordinator.state.value.busy }
        assertNull(coordinator.state.value.invitation)
        assertNull(coordinator.state.value.error)
    }

    @Test fun cancellationAfterRootIntentDoesNotReportSuccessOrLoseTheApprovedMember() = fixture { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holding = AtomicBoolean(false)
        val founder = f.founder(f.workspace) { stage ->
            if (holding.get() && stage == "APPROVED_ROOT_INTENT_RETAINED") {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS))
            }
        }
        val coordinator = readyCoordinator(f, enrollment = { founder })
        coordinator.unlockManagement(requireNotNull(f.recoveryCode))
        await { !coordinator.state.value.busy && coordinator.state.value.managementCanInvite }
        coordinator.createInvitation()
        await { !coordinator.state.value.busy && coordinator.state.value.invitation != null }
        val offer = requireNotNull(coordinator.state.value.invitation)
        val receiver = LanSyncTlsIdentity.create()
        val key = SyncDeviceEncryptionKey.create(receiver.fingerprint)
        LanSyncGroupInviteClient.exchange(offer, receiver, key, "synthetic device", {
            f.network.connect(receiver, LanSyncPeer(offer.inviter, "synthetic PC", offer.host, offer.port))
        })
        await { !coordinator.state.value.busy && coordinator.state.value.peerConfirmation != null }
        try {
            holding.set(true)
            coordinator.acceptPeer(requireNotNull(coordinator.state.value.peerConfirmation).binding)
            await { entered.count == 0L }
            coordinator.closeInvitation()
            assertTrue(coordinator.state.value.invitationApprovalInProgress)
            assertEquals("正在确认批准结果", coordinator.state.value.status)
        } finally { release.countDown() }
        await { !coordinator.state.value.busy && coordinator.state.value.devices.any { it.id == receiver.fingerprint } }
        val access = requireNotNull(founder.runtimeAccess())
        val member = access.ledger().current().members.getValue(receiver.fingerprint)
        assertEquals(key.publicKey.encoded, member.recipientKey)
        assertEquals("synthetic device", access.peerDisplayName(receiver.fingerprint))
        assertFalse(coordinator.state.value.invitationApprovalInProgress)
        coordinator.closeInvitation()
        assertNotEquals("邀请已取消", coordinator.state.value.status)
    }

    @Test fun admittedDisplayNameSurvivesTheFirstPublicationFailureAndManagementResume() = fixture { f ->
        val interruption = AtomicBoolean(false)
        val founder = f.founder(f.workspace) { stage ->
            if (stage == "ROOT_DELIVERY_SPEC_RETAINED" && interruption.compareAndSet(true, false))
                throw java.io.IOException("SYNC_SYNTHETIC_PUBLICATION_INTERRUPTED")
        }
        val coordinator = readyCoordinator(f, enrollment = { founder })
        coordinator.unlockManagement(requireNotNull(f.recoveryCode))
        await { !coordinator.state.value.busy && coordinator.state.value.managementCanInvite }
        coordinator.createInvitation()
        await { !coordinator.state.value.busy && coordinator.state.value.invitation != null }
        val offer = requireNotNull(coordinator.state.value.invitation)
        val receiver = LanSyncTlsIdentity.create()
        val key = SyncDeviceEncryptionKey.create(receiver.fingerprint)
        LanSyncGroupInviteClient.exchange(offer, receiver, key, "合成已批准设备", {
            f.network.connect(receiver, LanSyncPeer(offer.inviter, "synthetic PC", offer.host, offer.port))
        })
        await { !coordinator.state.value.busy && coordinator.state.value.peerConfirmation != null }
        val access = requireNotNull(founder.runtimeAccess())
        assertFalse(Files.exists(access.work.resolve("peer-display.protected")))
        interruption.set(true)
        coordinator.acceptPeer(requireNotNull(coordinator.state.value.peerConfirmation).binding)
        await { !coordinator.state.value.busy && coordinator.state.value.error?.contains("SYNC_SYNTHETIC_PUBLICATION_INTERRUPTED") == true }
        assertEquals(key.publicKey.encoded, access.ledger().current().members.getValue(receiver.fingerprint).recipientKey)
        assertEquals("合成已批准设备", requireNotNull(founder.runtimeAccess()).peerDisplayName(receiver.fingerprint))
        coordinator.resumeManagementOperation()
        await { !coordinator.state.value.busy && coordinator.state.value.error == null && !coordinator.state.value.managementUnlocked }
        assertEquals("合成已批准设备", requireNotNull(founder.runtimeAccess()).peerDisplayName(receiver.fingerprint))
    }

    private class ObservedNetwork(private val delegate: LanSyncNetwork) : LanSyncNetwork {
        val calls = AtomicInteger()
        val forbidden = AtomicBoolean(false)
        private fun touch() {
            calls.incrementAndGet()
            check(!forbidden.get()) { "SYNTHETIC_PAUSED_NETWORK_FORBIDDEN" }
        }
        override fun addresses(): List<String> { touch(); return delegate.addresses() }
        override fun accepts(address: InetAddress): Boolean { touch(); return delegate.accepts(address) }
        override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket {
            touch(); return delegate.connect(identity, peer)
        }
        override fun listening(fingerprint: String, port: Int) { touch() }
        override fun discover(fingerprint: String): Pair<String, Int>? { touch(); return null }
        override val discoveryEnabled: Boolean get() { touch(); return false }
    }

    @Test fun optionalNearbyBindFailureDoesNotBlockLocalOrEnabledCloudAndForegroundRetriesTheListener() = fixture { f ->
        val blocked = AtomicBoolean(true)
        val attempts = AtomicInteger(); val ready = AtomicInteger(); val forbidden = AtomicBoolean(false)
        val network = object : LanSyncNetwork by noNetwork {
            override fun listening(fingerprint: String, port: Int) {
                attempts.incrementAndGet()
                check(!forbidden.get()) { "SYNTHETIC_PAUSED_LISTENER_FORBIDDEN" }
                if (blocked.get()) throw BindException("synthetic optional listener unavailable")
                ready.incrementAndGet()
            }
        }
        // This test supplies its network to the actual owner, while retaining the fixture's TLS/native owners.
        val founder = f.founder()
        val request = SyncRootGroupCreateRequest(SyncStoreId.random(), LanSyncScope.schemas.keys.toSet(), "a".repeat(64))
        val draft = founder.prepare(request, SyncRootGroupCreateAdmission { require(it == request) })
        f.recoveryCode = draft.recoveryCode()
        founder.activate(draft, LanSyncGroupRecoverySavedAdmission {})
        val access = requireNotNull(founder.runtimeAccess())
        f.writeCard("synthetic automatic source despite nearby bind failure")
        val original = Files.readAllBytes(f.card)
        val catalog = LanSyncGroupCloudRoutes(Files.createDirectory(f.root.resolve("catalog")), access.trust, access.device,
            { it.reversedArray() }, { it.reversedArray() }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(60, TimeUnit.SECONDS).build()
        try {
            WeakFixture(Files.createDirectory(f.root.resolve("provider"))).use { provider ->
                val password = "synthetic nearby independent cloud password".toByteArray()
                val route = try { catalog.add(LanSyncGroupCloudDraft("synthetic enabled drive",
                    provider.server.url("/sync/").newBuilder().host("127.0.0.1").build().toString(),
                    "synthetic user", password, enabled = true)) } finally { password.fill(0) }
                val coordinator = LanSyncGroupCoordinator({ Files.exists(f.profile.resolve("profile.protected")) }, f::founder,
                    { error("SYNTHETIC_FOUNDER_MUST_NOT_OPEN_JOINING") }, network, "synthetic PC",
                    cloudRuntimeFactory = { current, allowed, wake, report ->
                        LanSyncGroupCloudRuntime(current, allowed, wake, report, catalog, client,
                            WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
                    })
                try {
                    val initialNearby = coordinator.state.value.nearby
                    coordinator.open()
                    withTimeout(60_000) {
                        while (coordinator.state.value.busy || coordinator.view.value == null || attempts.get() == 0 ||
                            coordinator.state.value.nearby.text == initialNearby.text ||
                            coordinator.cloudSettings.value.progress[route]?.selectionObserved != true) delay(10)
                    }
                    assertEquals(LanSyncGroupPanelStage.READY, coordinator.state.value.stage)
                    assertFalse(coordinator.state.value.paused)
                    assertNotEquals(initialNearby.text, coordinator.state.value.nearby.text)
                    assertNotNull(coordinator.state.value.local.lastSuccessAt)
                    assertNull(coordinator.state.value.nearby.lastSuccessAt)
                    assertNull(coordinator.state.value.error)
                    assertEquals(0, ready.get())
                    val source = access.node.sourceOperations().single()
                    val entry = requireNotNull(f.author(access.trust).entry(source))
                    assertNotNull(access.node.receipt(source, access.localTarget))
                    val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, route)
                    val publication = access.targetPublication(access.work.resolve("cloud-runtime").resolve(route.value).resolve("target"),
                        target, prepare = false)
                    val receipt = requireNotNull(publication.cloudReceipt())
                    assertEquals(target, receipt.target)
                    assertTrue(receipt.objects.isNotEmpty())
                    assertEquals(access.ledger().current().reference(), receipt.checkpoint)
                    assertTrue(provider.dav.puts.isNotEmpty())
                    assertTrue(provider.server.requestCount > 0)
                    assertSourceOriginal(f, entry, original)

                    val failed = coordinator.state.value.nearby.text
                    val failedAttempts = attempts.get()
                    blocked.set(false)
                    coordinator.onForeground()
                    await { ready.get() > 0 && coordinator.state.value.nearby.text != failed }
                    assertTrue(attempts.get() > failedAttempts)
                    // Authentication still goes through the ordinary pinned TLS protocol, not the wake hook.
                    liveListenerPort(f, coordinator, access)
                    assertEquals(listOf(source), access.node.sourceOperations())
                    assertEquals(1L, f.author(access.trust).state().committedSequence)
                    assertContentEquals(original, Files.readAllBytes(f.card))

                    coordinator.pauseAndDrain()
                    forbidden.set(true)
                    val calls = attempts.get(); val http = provider.server.requestCount
                    repeat(3) { coordinator.onForeground(); coordinator.onNetworkAvailable(); coordinator.retry() }
                    delay(350)
                    assertEquals(calls, attempts.get())
                    assertEquals(http, provider.server.requestCount)
                    assertTrue(coordinator.state.value.paused)
                    assertNull(coordinator.state.value.error)
                } finally { coordinator.closeAndDrain() }
            }
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun coldCoordinatorReopensSameSavedTargetAndUploadsANormalCommittedEditWithoutExplicitSync() = fixture { f ->
        val network = object : LanSyncNetwork by noNetwork {
            override fun listening(fingerprint: String, port: Int) { throw BindException("synthetic optional nearby unavailable") }
        }
        val founder = f.founder()
        val request = SyncRootGroupCreateRequest(SyncStoreId.random(), LanSyncScope.schemas.keys.toSet(), "a".repeat(64))
        val draft = founder.prepare(request, SyncRootGroupCreateAdmission { require(it == request) })
        founder.activate(draft, LanSyncGroupRecoverySavedAdmission {})
        val access = requireNotNull(founder.runtimeAccess())
        val catalogRoot = Files.createDirectory(f.root.resolve("cold-cloud-catalog"))
        fun catalog() = LanSyncGroupCloudRoutes(catalogRoot, access.trust, access.device,
            { it.reversedArray() }, { it.reversedArray() }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(60, TimeUnit.SECONDS).build()
        try {
            WeakFixture(Files.createDirectory(f.root.resolve("cold-cloud-provider"))).use { provider ->
                val firstCatalog = catalog()
                val secret = "synthetic cold cloud credential".toByteArray()
                val route = try { firstCatalog.add(LanSyncGroupCloudDraft("synthetic retained drive",
                    provider.server.url("/sync/").newBuilder().host("127.0.0.1").build().toString(),
                    "synthetic user", secret, enabled = true)) } finally { secret.fill(0) }
                val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, route)
                val directory = access.work.resolve("cloud-runtime/${route.value}/target")
                data class ObservedTarget(val plan: LanSyncGroupTargetPlan, val receipt: LanSyncGroupCloudTargetReceipt,
                    val planPath: Path, val receiptPath: Path)
                fun existingTarget(wanted: Set<SyncVersionRef>): ObservedTarget? {
                    if (!Files.isDirectory(directory)) return null
                    return Files.newDirectoryStream(directory, "cloud-receipt-*.json").use { files ->
                        var result: ObservedTarget? = null
                        for (path in files) {
                            val receipt = requireNotNull(LanSyncJsonFiles.read(path, LanSyncGroupCloudTargetReceipt.serializer()))
                            val planPath = directory.resolve("plan-" + path.fileName.toString().removePrefix("cloud-receipt-"))
                            val plan = requireNotNull(LanSyncJsonFiles.read(planPath, LanSyncGroupTargetPlan.serializer(), receipt.planHash))
                            if (!plan.versions.containsAll(wanted)) continue
                            assertEquals(target, plan.target); assertEquals(target, receipt.target)
                            assertEquals(access.trust.group, plan.group); assertEquals(plan.checkpoint, receipt.checkpoint)
                            assertEquals(plan.objects, receipt.objects.map { it.objectRequest })
                            for (reference in wanted) assertEquals(1, receipt.objects.count {
                                it.objectRequest.role == LanSyncGroupTargetObjectRole.VERSION && it.objectRequest.slot == reference.id.value })
                            assertTrue(receipt.objects.isNotEmpty())
                            assertTrue(receipt.objects.all { it.evidence.readback is LanSyncGroupCloudTargetObservation.Chunks })
                            result = ObservedTarget(plan, receipt, planPath, path)
                        }
                        result
                    }
                }
                suspend fun awaitTarget(coordinator: LanSyncGroupCoordinator, wanted: Set<SyncVersionRef>): ObservedTarget = withTimeout(90_000) {
                    var result: ObservedTarget? = null
                    while (result == null) {
                        val history = coordinator.view.value?.reconciliation?.history
                        if (history != null && wanted.all(history::contains)) result = existingTarget(wanted)
                        if (result == null) delay(10)
                    }
                    requireNotNull(result)
                }
                fun coordinator(storage: JsonFileStorage, saved: LanSyncGroupCloudRoutes) = LanSyncGroupCoordinator(
                    { Files.exists(f.profile.resolve("profile.protected")) }, f::founder,
                    { error("SYNTHETIC_COLD_FOUNDER_MUST_NOT_OPEN_JOINING") }, network, "synthetic cold PC",
                    cloudRuntimeFactory = { current, allowed, wake, report ->
                        LanSyncGroupCloudRuntime(current, allowed, wake, report, saved, client, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
                    }, sourceChanges = storage.committedRevisionFor(LanSyncScope.schemas.keys))
                val initial = CharacterCard("card", "synthetic old cold-cloud body", createdAt = 1, updatedAt = 1)
                val firstStorage = JsonFileStorage(f.data)
                firstStorage.saveEntity("character_cards", initial.id, initial, CharacterCard.serializer())
                val first = coordinator(firstStorage, firstCatalog)
                var cold: LanSyncGroupCoordinator? = null
                try {
                    first.open()
                    await { first.view.value?.reconciliation?.history?.admittedVersions()?.count { it.author == access.device } == 1 }
                    val oldVersion = requireNotNull(first.view.value?.reconciliation?.history).admittedVersions().single { it.author == access.device }
                    val oldReference = oldVersion.reference()
                    val old = awaitTarget(first, setOf(oldReference))
                    assertEquals(1L, oldVersion.authorSequence)
                    assertEquals(initial, firstStorage.loadEntity("character_cards", initial.id, CharacterCard.serializer()))
                    first.closeAndDrain()
                    val oldPlanBytes = Files.readAllBytes(old.planPath); val oldReceiptBytes = Files.readAllBytes(old.receiptPath)
                    val authorRoot = f.profile.resolve("node/author")
                    val signedPrefix = retainedFiles(authorRoot).filterKeys { it.startsWith("signed-") || it.startsWith("intent-") }
                    assertEquals(2, signedPrefix.size)
                    val secondStorage = JsonFileStorage(f.data)
                    val secondCatalog = catalog()
                    assertEquals(route, secondCatalog.rows().single { it.enabled }.id)
                    val reopened = coordinator(secondStorage, secondCatalog).also { cold = it }
                    reopened.open()
                    await { !reopened.state.value.busy && reopened.state.value.stage == LanSyncGroupPanelStage.READY &&
                        reopened.view.value?.reconciliation?.history?.contains(oldReference) == true }
                    val edited = initial.copy(name = "synthetic ordinary edit after cold Coordinator", updatedAt = 2)
                    secondStorage.saveEntity("character_cards", edited.id, edited, CharacterCard.serializer())
                    val editedBytes = Files.readAllBytes(f.card)
                    withTimeout(60_000) {
                        while (reopened.view.value?.reconciliation?.history?.admittedVersions()?.none {
                            it.author == access.device && it.authorSequence == 2L && "character_cards/card" in it.changes } != false) delay(10)
                    }
                    val currentVersions = requireNotNull(reopened.view.value?.reconciliation?.history).admittedVersions()
                        .filter { it.author == access.device }.sortedBy { it.authorSequence }
                    assertEquals(listOf(1L, 2L), currentVersions.map { it.authorSequence })
                    val newVersion = currentVersions.last(); val newReference = newVersion.reference()
                    assertNotEquals(oldReference, newReference); assertEquals(oldReference, newVersion.previousByAuthor)
                    assertEquals(setOf(oldReference), newVersion.changes.getValue("character_cards/card").previous)
                    val actual = awaitTarget(reopened, setOf(oldReference, newReference))
                    old.receipt.objects.forEach { previous -> assertEquals(previous,
                        actual.receipt.objects.single { it.objectRequest.key == previous.objectRequest.key &&
                            it.objectRequest.descriptor == previous.objectRequest.descriptor }) }
                    assertContentEquals(oldPlanBytes, Files.readAllBytes(old.planPath))
                    assertContentEquals(oldReceiptBytes, Files.readAllBytes(old.receiptPath))
                    val retained = retainedFiles(authorRoot)
                    signedPrefix.forEach { (name, hash) -> assertEquals(hash, retained[name]) }
                    assertEquals(4, retained.keys.count { it.startsWith("signed-") || it.startsWith("intent-") })
                    val view = requireNotNull(reopened.view.value)
                    val applied = view.nativeState.units.getValue("character_cards/card")
                    assertEquals(newVersion.changes.getValue("character_cards/card").valueHash, applied.valueHash)
                    assertEquals(setOf(newReference), applied.frontiers); assertNull(view.nativeState.pendingTransaction)
                    assertEquals(edited, secondStorage.loadEntity("character_cards", edited.id, CharacterCard.serializer()))
                    assertContentEquals(editedBytes, Files.readAllBytes(f.card))
                    assertTrue(provider.dav.puts.isNotEmpty()); assertTrue(provider.server.requestCount > 0)
                } finally { first.closeAndDrain(); cold?.closeAndDrain() }
            }
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun coldDirtyCloudWorkRetriesAfterPlatformDeferralWithoutAnotherWake() = fixture { f ->
        val network = object : LanSyncNetwork by noNetwork {
            override fun listening(fingerprint: String, port: Int) { throw BindException("synthetic optional nearby unavailable") }
        }
        val founder = f.founder()
        val request = SyncRootGroupCreateRequest(SyncStoreId.random(), LanSyncScope.schemas.keys.toSet(), "a".repeat(64))
        val draft = founder.prepare(request, SyncRootGroupCreateAdmission { require(it == request) })
        founder.activate(draft, LanSyncGroupRecoverySavedAdmission {})
        val access = requireNotNull(founder.runtimeAccess())
        val catalogRoot = Files.createDirectory(f.root.resolve("deferred-cloud-catalog"))
        fun catalog() = LanSyncGroupCloudRoutes(catalogRoot, access.trust, access.device,
            { it.reversedArray() }, { it.reversedArray() }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(60, TimeUnit.SECONDS).build()
        try {
            WeakFixture(Files.createDirectory(f.root.resolve("deferred-cloud-provider"))).use { provider ->
                val firstCatalog = catalog()
                val password = "synthetic deferred cloud credential".toByteArray()
                val route = try { firstCatalog.add(LanSyncGroupCloudDraft("synthetic deferred drive",
                    provider.server.url("/sync/").newBuilder().host("127.0.0.1").build().toString(),
                    "synthetic user", password, enabled = true)) } finally { password.fill(0) }
                val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, route)
                val directory = access.work.resolve("cloud-runtime/${route.value}/target")
                fun receipt(wanted: Set<SyncVersionRef>): LanSyncGroupCloudTargetReceipt? {
                    if (!Files.isDirectory(directory)) return null
                    return Files.newDirectoryStream(directory, "cloud-receipt-*.json").use { paths ->
                        var found: LanSyncGroupCloudTargetReceipt? = null
                        for (path in paths) {
                            val actual = requireNotNull(LanSyncJsonFiles.read(path, LanSyncGroupCloudTargetReceipt.serializer()))
                            val plan = requireNotNull(LanSyncJsonFiles.read(directory.resolve("plan-" +
                                path.fileName.toString().removePrefix("cloud-receipt-")), LanSyncGroupTargetPlan.serializer(), actual.planHash))
                            if (!plan.versions.containsAll(wanted)) continue
                            assertEquals(target, actual.target); assertEquals(target, plan.target)
                            assertEquals(plan.objects, actual.objects.map { it.objectRequest })
                            assertTrue(actual.objects.all { it.evidence.readback is LanSyncGroupCloudTargetObservation.Chunks })
                            for (reference in wanted) assertEquals(1, actual.objects.count {
                                it.objectRequest.role == LanSyncGroupTargetObjectRole.VERSION && it.objectRequest.slot == reference.id.value })
                            found = actual
                        }
                        found
                    }
                }
                fun coordinator(storage: JsonFileStorage, saved: LanSyncGroupCloudRoutes,
                    schedule: LanSyncGroupCloudSchedule = LanSyncGroupCloudSchedule(),
                    cloudWork: suspend (suspend () -> Boolean) -> Boolean = { it() }) = LanSyncGroupCoordinator(
                    { Files.exists(f.profile.resolve("profile.protected")) }, f::founder,
                    { error("SYNTHETIC_DEFERRED_FOUNDER_MUST_NOT_OPEN_JOINING") }, network, "synthetic deferred PC",
                    cloudRuntimeFactory = { current, allowed, wake, report ->
                        LanSyncGroupCloudRuntime(current, allowed, wake, report, saved, client,
                            WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP, schedule = schedule)
                    }, sourceChanges = storage.committedRevisionFor(LanSyncScope.schemas.keys), cloudWork = cloudWork)
                val firstStorage = JsonFileStorage(f.data)
                val initial = CharacterCard("card", "synthetic old deferred body", createdAt = 1, updatedAt = 1)
                firstStorage.saveEntity("character_cards", initial.id, initial, CharacterCard.serializer())
                val first = coordinator(firstStorage, firstCatalog)
                var cold: LanSyncGroupCoordinator? = null
                try {
                    first.open()
                    withTimeout(60_000) { while (first.cloudSettings.value.progress[route]?.selectionObserved != true) delay(10) }
                    val oldReference = requireNotNull(first.view.value?.reconciliation?.history).admittedVersions()
                        .single { it.author == access.device }.reference()
                    val original = requireNotNull(receipt(setOf(oldReference)))
                    first.closeAndDrain()
                    val schedule = LanSyncGroupCloudSchedule()
                    val attempts = AtomicInteger(); val business = AtomicInteger(); val completed = AtomicInteger(); val active = AtomicInteger()
                    val deferNext = AtomicBoolean(false); val deferredAt = AtomicLong()
                    val deferred = CompletableDeferred<Int>()
                    val secondStorage = JsonFileStorage(f.data)
                    val reopened = coordinator(secondStorage, catalog(), schedule) { block ->
                        attempts.incrementAndGet()
                        if (deferNext.compareAndSet(true, false)) {
                            deferredAt.set(System.nanoTime() / 1_000_000L); deferred.complete(business.get()); false
                        } else {
                            business.incrementAndGet(); active.incrementAndGet()
                            try { block().also { if (it) completed.incrementAndGet() } } finally { active.decrementAndGet() }
                        }
                    }.also { cold = it }
                    reopened.open()
                    withTimeout(60_000) {
                        while (reopened.state.value.busy || reopened.state.value.stage != LanSyncGroupPanelStage.READY ||
                            completed.get() == 0 || active.get() != 0) delay(10)
                    }
                    // Genuine unchanged checks build a long idle only BEFORE the new business edit.
                    repeat(2) {
                        val before = completed.get(); reopened.onNetworkAvailable()
                        withTimeout(60_000) { while (completed.get() <= before || active.get() != 0) delay(10) }
                    }
                    withTimeout(10_000) {
                        var quiet = false
                        while (!quiet) { val before = completed.get(); delay(250); quiet = active.get() == 0 && completed.get() == before }
                    }
                    val oldIdle = requireNotNull(schedule.state(route))
                    assertNull(oldIdle.failure); assertTrue(oldIdle.waitMillis > 180_000)
                    deferNext.set(true)
                    val edited = initial.copy(name = "synthetic edit awaiting platform retry", updatedAt = 2)
                    secondStorage.saveEntity("character_cards", edited.id, edited, CharacterCard.serializer())
                    val businessAtDeferral = withTimeout(20_000) { deferred.await() }
                    assertEquals(businessAtDeferral, business.get(), "The refused platform attempt must not invoke cloud business")
                    val attemptsAtDeferral = attempts.get()
                    val newReference = requireNotNull(reopened.view.value?.reconciliation?.history).admittedVersions()
                        .single { it.author == access.device && it.authorSequence == 2L }.reference()
                    // No retry/foreground/network hint/edit follows. Only the existing worker's timer may retry.
                    var observed: LanSyncGroupCloudTargetReceipt? = null
                    try {
                        withTimeout(90_000) { while (observed == null) { observed = receipt(setOf(oldReference, newReference)); if (observed == null) delay(25) } }
                    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                        throw AssertionError("SYNTHETIC_DEFERRED_CLOUD_TAIL oldIdleMs=${oldIdle.waitMillis} attempts=${attempts.get()} business=${business.get()} refusedBusiness=$businessAtDeferral", timeout)
                    }
                    assertTrue(System.nanoTime() / 1_000_000L - deferredAt.get() >= 60_000,
                        "The automatic retry must respect the platform deferral instead of spinning")
                    assertEquals(attemptsAtDeferral + 1, attempts.get()); assertEquals(businessAtDeferral + 1, business.get())
                    val actual = requireNotNull(observed)
                    original.objects.forEach { row -> assertEquals(row.evidence.readback.observedAtMillis,
                        actual.objects.single { it.objectRequest.key == row.objectRequest.key &&
                            it.objectRequest.descriptor == row.objectRequest.descriptor }.evidence.readback.observedAtMillis) }
                    assertEquals(edited, secondStorage.loadEntity("character_cards", edited.id, CharacterCard.serializer()))
                    assertEquals(listOf(1L, 2L), requireNotNull(reopened.view.value?.reconciliation?.history).admittedVersions()
                        .filter { it.author == access.device }.map { it.authorSequence }.sorted())
                } finally { first.closeAndDrain(); cold?.closeAndDrain() }
            }
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    // Independent local-batch cancellation regression. It does not establish an R09 cause.
    @Test fun aCancelledCloudBatchDoesNotRetireAutomaticCloudWorkForLaterOrdinaryEdits() = fixture { f ->
        val network = object : LanSyncNetwork by noNetwork {
            override fun listening(fingerprint: String, port: Int) { throw BindException("synthetic optional nearby unavailable") }
        }
        val founder = f.founder()
        val request = SyncRootGroupCreateRequest(SyncStoreId.random(), LanSyncScope.schemas.keys.toSet(), "a".repeat(64))
        val draft = founder.prepare(request, SyncRootGroupCreateAdmission { require(it == request) })
        founder.activate(draft, LanSyncGroupRecoverySavedAdmission {})
        val access = requireNotNull(founder.runtimeAccess())
        val catalogRoot = Files.createDirectory(f.root.resolve("local-cancel-cloud-catalog"))
        fun catalog() = LanSyncGroupCloudRoutes(catalogRoot, access.trust, access.device,
            { it.reversedArray() }, { it.reversedArray() }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
        val cancelNextPut = AtomicBoolean(false)
        val cancellationInjected = AtomicBoolean(false)
        val uploadPutEntered = CompletableDeferred<kotlinx.coroutines.Job>()
        val localBatchExited = CompletableDeferred<Boolean>()
        val releasePut = CountDownLatch(1)
        val batchReference = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>()
        val attempts = AtomicInteger(); val completed = AtomicInteger(); val active = AtomicInteger()
        val actualUploadStack = AtomicBoolean(false)
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(60, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                if (chain.request().method == "PUT" && cancelNextPut.compareAndSet(true, false)) {
                    // Only a closed boolean is retained; no frame/name/path/body is emitted.
                    actualUploadStack.set(Thread.currentThread().stackTrace.any {
                        it.className.startsWith("com.example.chatbar.domain.lanSync.LanSyncGroupTargetPublication") &&
                            (it.className.contains("uploadCloud") || it.methodName.contains("uploadCloud"))
                    })
                    val batch = batchReference.get()
                    if (batch == null) uploadPutEntered.completeExceptionally(
                        AssertionError("SYNTHETIC_LOCAL_BATCH_CANCEL_PUT_WITHOUT_OWNED_BATCH"))
                    else uploadPutEntered.complete(batch)
                    check(releasePut.await(10, TimeUnit.SECONDS)) { "SYNTHETIC_LOCAL_BATCH_CANCEL_PUT_NOT_RELEASED" }
                }
                chain.proceed(chain.request())
            }.build()
        try {
            WeakFixture(Files.createDirectory(f.root.resolve("local-cancel-cloud-provider"))).use { provider ->
                val firstCatalog = catalog()
                val password = "synthetic local cancellation credential".toByteArray()
                val route = try { firstCatalog.add(LanSyncGroupCloudDraft("synthetic retained drive",
                    provider.server.url("/sync/").newBuilder().host("127.0.0.1").build().toString(),
                    "synthetic user", password, enabled = true)) } finally { password.fill(0) }
                val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, route)
                val directory = access.work.resolve("cloud-runtime/${route.value}/target")
                data class ObservedTarget(val plan: LanSyncGroupTargetPlan, val receipt: LanSyncGroupCloudTargetReceipt,
                    val planPath: Path, val receiptPath: Path)
                fun existingTarget(wanted: Set<SyncVersionRef>): ObservedTarget? {
                    if (!Files.isDirectory(directory)) return null
                    return Files.newDirectoryStream(directory, "cloud-receipt-*.json").use { files ->
                        var result: ObservedTarget? = null
                        for (path in files) {
                            val receipt = requireNotNull(LanSyncJsonFiles.read(path, LanSyncGroupCloudTargetReceipt.serializer()))
                            val planPath = directory.resolve("plan-" + path.fileName.toString().removePrefix("cloud-receipt-"))
                            val plan = requireNotNull(LanSyncJsonFiles.read(planPath, LanSyncGroupTargetPlan.serializer(), receipt.planHash))
                            if (!plan.versions.containsAll(wanted)) continue
                            assertEquals(wanted, plan.versions.toSet())
                            assertEquals(target, plan.target); assertEquals(target, receipt.target)
                            assertEquals(access.trust.group, plan.group); assertEquals(plan.checkpoint, receipt.checkpoint)
                            assertEquals(plan.objects, receipt.objects.map { it.objectRequest })
                            for (reference in wanted) assertEquals(1, receipt.objects.count {
                                it.objectRequest.role == LanSyncGroupTargetObjectRole.VERSION && it.objectRequest.slot == reference.id.value })
                            assertTrue(receipt.objects.isNotEmpty())
                            assertTrue(receipt.objects.all { it.evidence.readback is LanSyncGroupCloudTargetObservation.Chunks })
                            result = ObservedTarget(plan, receipt, planPath, path)
                        }
                        result
                    }
                }
                suspend fun awaitTarget(wanted: Set<SyncVersionRef>, marker: String): ObservedTarget = try {
                    withTimeout(90_000) {
                        var result: ObservedTarget? = null
                        while (result == null) { result = existingTarget(wanted); if (result == null) delay(25) }
                        requireNotNull(result)
                    }
                } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    throw AssertionError("SYNTHETIC_LOCAL_BATCH_CANCEL_$marker attempts=${attempts.get()} completed=${completed.get()} active=${active.get()} injected=${cancellationInjected.get()}", timeout)
                }
                fun coordinator(storage: JsonFileStorage, saved: LanSyncGroupCloudRoutes,
                    cloudWork: suspend (suspend () -> Boolean) -> Boolean = { it() }) = LanSyncGroupCoordinator(
                    { Files.exists(f.profile.resolve("profile.protected")) }, f::founder,
                    { error("SYNTHETIC_LOCAL_CANCEL_FOUNDER_MUST_NOT_OPEN_JOINING") }, network, "synthetic local cancellation PC",
                    cloudRuntimeFactory = { current, allowed, wake, report ->
                        LanSyncGroupCloudRuntime(current, allowed, wake, report, saved, client,
                            WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
                    }, sourceChanges = storage.committedRevisionFor(LanSyncScope.schemas.keys), cloudWork = cloudWork)
                suspend fun awaitAuthorSequence(coordinator: LanSyncGroupCoordinator, expected: Long) {
                    try {
                        withTimeout(60_000) {
                            while (coordinator.view.value?.reconciliation?.history?.admittedVersions()?.none {
                                it.author == access.device && it.authorSequence == expected && "character_cards/card" in it.changes } != false) delay(10)
                        }
                    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                        throw AssertionError("SYNTHETIC_LOCAL_BATCH_CANCEL_LOCAL_ADMISSION_$expected", timeout)
                    }
                }
                val initial = CharacterCard("card", "synthetic old local cancellation body", createdAt = 1, updatedAt = 1)
                val firstStorage = JsonFileStorage(f.data)
                firstStorage.saveEntity("character_cards", initial.id, initial, CharacterCard.serializer())
                val first = coordinator(firstStorage, firstCatalog)
                var cold: LanSyncGroupCoordinator? = null
                try {
                    first.open(); awaitAuthorSequence(first, 1)
                    val oldReference = requireNotNull(first.view.value?.reconciliation?.history).admittedVersions()
                        .single { it.author == access.device }.reference()
                    val old = awaitTarget(setOf(oldReference), "SEED_RECEIPT")
                    first.closeAndDrain()
                    val oldPlanBytes = Files.readAllBytes(old.planPath); val oldReceiptBytes = Files.readAllBytes(old.receiptPath)
                    val authorRoot = f.profile.resolve("node/author")
                    val signedPrefix = retainedFiles(authorRoot).filterKeys { it.startsWith("signed-") || it.startsWith("intent-") }
                    assertEquals(2, signedPrefix.size)
                    val secondStorage = JsonFileStorage(f.data)
                    val reopened = coordinator(secondStorage, catalog()) { block ->
                        attempts.incrementAndGet()
                        val outerJob = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
                        try {
                            kotlinx.coroutines.supervisorScope {
                                // Publishing the lazy child precedes its first possible PUT.
                                val batch = async(start = CoroutineStart.LAZY) { block() }
                                check(batchReference.compareAndSet(null, batch)) { "SYNTHETIC_LOCAL_BATCH_CANCEL_OVERLAPPING_BATCH" }
                                active.incrementAndGet()
                                try { batch.await().also { if (it) completed.incrementAndGet() } }
                                finally { active.decrementAndGet(); batchReference.compareAndSet(batch, null) }
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            // This proves a local child cancellation before it escapes the wrapper.
                            if (cancellationInjected.get()) localBatchExited.complete(outerJob.isActive)
                            throw cancelled
                        }
                    }.also { cold = it }
                    reopened.open()
                    try {
                        withTimeout(60_000) {
                            while (reopened.state.value.busy || reopened.state.value.stage != LanSyncGroupPanelStage.READY ||
                                completed.get() == 0 || active.get() != 0) delay(10)
                        }
                    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                        throw AssertionError("SYNTHETIC_LOCAL_BATCH_CANCEL_COLD_STARTUP_NOT_QUIET", timeout)
                    }
                    assertEquals(oldReference, requireNotNull(reopened.view.value?.reconciliation?.history).admittedVersions()
                        .single { it.author == access.device }.reference())
                    cancelNextPut.set(true)
                    val edited = initial.copy(name = "synthetic ordinary v2 awaiting local cancellation", updatedAt = 2)
                    secondStorage.saveEntity("character_cards", edited.id, edited, CharacterCard.serializer())
                    val batch = try { withTimeout(30_000) { uploadPutEntered.await() } }
                        catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                            throw AssertionError("SYNTHETIC_LOCAL_BATCH_CANCEL_UPLOAD_PUT_NOT_ENTERED", timeout)
                        }
                    assertTrue(actualUploadStack.get(), "SYNTHETIC_LOCAL_BATCH_CANCEL_NOT_UPLOAD_PUT")
                    assertTrue(batch.isActive, "SYNTHETIC_LOCAL_BATCH_CANCEL_BATCH_ALREADY_INACTIVE")
                    cancellationInjected.set(true)
                    batch.cancel(kotlinx.coroutines.CancellationException("SYNTHETIC_OWNED_CLOUD_BATCH_LOCAL_CANCEL"))
                    releasePut.countDown()
                    val outerWasActive = try { withTimeout(10_000) { localBatchExited.await() } }
                        catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                            throw AssertionError("SYNTHETIC_LOCAL_BATCH_CANCEL_DID_NOT_EXIT_WRAPPER", timeout)
                        }
                    assertTrue(outerWasActive, "SYNTHETIC_LOCAL_BATCH_CANCEL_WAS_PARENT_CANCELLATION")
                    awaitAuthorSequence(reopened, 2)
                    val secondReference = requireNotNull(reopened.view.value?.reconciliation?.history).admittedVersions()
                        .single { it.author == access.device && it.authorSequence == 2L }.reference()
                    assertNull(existingTarget(setOf(oldReference, secondReference)), "SYNTHETIC_LOCAL_BATCH_CANCEL_ALREADY_COMPLETED_BEFORE_CANCEL")
                    val finalEdit = edited.copy(name = "synthetic ordinary v3 after local cancellation", updatedAt = 3)
                    secondStorage.saveEntity("character_cards", finalEdit.id, finalEdit, CharacterCard.serializer())
                    val finalBytes = Files.readAllBytes(f.card)
                    awaitAuthorSequence(reopened, 3)
                    val versions = requireNotNull(reopened.view.value?.reconciliation?.history).admittedVersions()
                        .filter { it.author == access.device }.sortedBy { it.authorSequence }
                    assertEquals(listOf(1L, 2L, 3L), versions.map { it.authorSequence })
                    assertEquals(oldReference, versions[1].previousByAuthor)
                    assertEquals(secondReference, versions[2].previousByAuthor)
                    assertEquals(setOf(oldReference), versions[1].changes.getValue("character_cards/card").previous)
                    assertEquals(setOf(secondReference), versions[2].changes.getValue("character_cards/card").previous)
                    val finalReference = versions.last().reference()
                    // No retry, foreground/network hint or additional edit follows this ordinary save.
                    val actual = awaitTarget(setOf(oldReference, secondReference, finalReference), "POST_CANCEL_RECEIPT")
                    old.receipt.objects.forEach { previous -> assertEquals(previous,
                        actual.receipt.objects.single { it.objectRequest.key == previous.objectRequest.key &&
                            it.objectRequest.descriptor == previous.objectRequest.descriptor }) }
                    assertContentEquals(oldPlanBytes, Files.readAllBytes(old.planPath))
                    assertContentEquals(oldReceiptBytes, Files.readAllBytes(old.receiptPath))
                    val retained = retainedFiles(authorRoot)
                    signedPrefix.forEach { (name, hash) -> assertEquals(hash, retained[name]) }
                    assertEquals(6, retained.keys.count { it.startsWith("signed-") || it.startsWith("intent-") })
                    val view = requireNotNull(reopened.view.value)
                    val applied = view.nativeState.units.getValue("character_cards/card")
                    assertEquals(versions.last().changes.getValue("character_cards/card").valueHash, applied.valueHash)
                    assertEquals(setOf(finalReference), applied.frontiers); assertNull(view.nativeState.pendingTransaction)
                    assertEquals(finalEdit, secondStorage.loadEntity("character_cards", finalEdit.id, CharacterCard.serializer()))
                    assertContentEquals(finalBytes, Files.readAllBytes(f.card))
                    assertTrue(provider.dav.puts.isNotEmpty()); assertTrue(provider.server.requestCount > 0)
                } finally { releasePut.countDown(); first.closeAndDrain(); cold?.closeAndDrain() }
            }
        } finally { releasePut.countDown(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
    @Test fun pauseDrainsAnInFlightFailedNearbyStartupAndRejectsQueuedNetworkHints() {
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val attempts = AtomicInteger(); val forbidden = AtomicBoolean(false)
        val network = object : LanSyncNetwork by noNetwork {
            override fun listening(fingerprint: String, port: Int) {
                attempts.incrementAndGet()
                check(!forbidden.get()) { "SYNTHETIC_LATE_LISTENER_FORBIDDEN" }
                entered.complete(Unit)
                check(release.await(20, TimeUnit.SECONDS)) { "SYNTHETIC_LISTENER_RELEASE_TIMEOUT" }
                throw BindException("synthetic optional bind failure during pause")
            }
        }
        fixture(network) { f ->
            try {
                f.writeCard("synthetic source before optional listener pause")
                val original = Files.readAllBytes(f.card)
                val storage = JsonFileStorage(f.data)
                val coordinator = readyCoordinator(f, storage.committedRevisionFor(LanSyncScope.schemas.keys))
                withTimeout(5_000) { entered.await() }
                coroutineScope {
                    val requested = CompletableDeferred<Unit>()
                    val stop = async(Dispatchers.IO) { requested.complete(Unit); coordinator.pauseAndDrain() }
                    requested.await()
                    release.countDown()
                    withTimeout(20_000) { stop.await() }
                }
                assertTrue(coordinator.state.value.paused)
                assertFalse(coordinator.state.value.busy)
                forbidden.set(true)
                val calls = attempts.get()
                val access = requireNotNull(f.founder().runtimeAccess())
                val source = access.node.sourceOperations().single()
                val entry = requireNotNull(f.author(access.trust).entry(source))
                val reloads = f.reloads.get()
                storage.saveEntity("character_cards", "card", CharacterCard("card", "synthetic queued save after pause", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
                repeat(3) { coordinator.onForeground(); coordinator.onNetworkAvailable(); coordinator.retry() }
                delay(350)
                assertEquals(calls, attempts.get())
                assertEquals(listOf(source), access.node.sourceOperations())
                assertEquals(reloads, f.reloads.get())
                assertEquals(1L, f.author(access.trust).state().committedSequence)
                assertSourceOriginal(f, entry, original)
            } finally { release.countDown() }
        }
    }

    @Test fun aRunningListenerFailureRebuildsOnHintsAndPausePreventsAnotherListener() {
        val failAcceptance = AtomicBoolean(false)
        val failures = AtomicInteger(); val starts = AtomicInteger(); val lastPort = AtomicInteger()
        val forbidden = AtomicBoolean(false)
        val network = object : LanSyncNetwork by noNetwork {
            override fun listening(fingerprint: String, port: Int) {
                check(!forbidden.get()) { "SYNTHETIC_LISTENER_AFTER_PAUSE" }
                lastPort.set(port); starts.incrementAndGet()
            }
            override fun accepts(address: InetAddress): Boolean {
                if (failAcceptance.compareAndSet(true, false)) {
                    failures.incrementAndGet()
                    throw java.net.SocketException("synthetic running listener failure")
                }
                return noNetwork.accepts(address)
            }
        }
        fixture(network) { f ->
            f.writeCard("synthetic source kept through listener restart")
            val original = Files.readAllBytes(f.card)
            val coordinator = readyCoordinator(f)
            try {
                await { starts.get() > 0 }
                val access = requireNotNull(f.founder().runtimeAccess())
                var sources = access.node.sourceOperations()
                withTimeout(20_000) {
                    while (sources.isEmpty()) { delay(10); sources = access.node.sourceOperations() }
                }
                val sequence = f.author(access.trust).state().committedSequence
                for (attempt in 1..2) {
                    val before = starts.get()
                    failAcceptance.set(true)
                    Socket("127.0.0.1", lastPort.get()).use { }
                    await { failures.get() == attempt }
                    if (attempt == 1) coordinator.onNetworkAvailable() else coordinator.onForeground()
                    await { starts.get() > before }
                    // This traverses the new real listener and the ordinary certificate-pinned protocol.
                    liveListenerPort(f, coordinator, access)
                    assertEquals(sources, access.node.sourceOperations())
                    assertEquals(sequence, f.author(access.trust).state().committedSequence)
                    assertContentEquals(original, Files.readAllBytes(f.card))
                    assertNull(coordinator.state.value.error)
                }
                coordinator.pauseAndDrain()
                forbidden.set(true)
                val stopped = starts.get()
                repeat(3) { coordinator.onNetworkAvailable(); coordinator.onForeground(); coordinator.retry() }
                delay(350)
                assertEquals(stopped, starts.get())
                assertTrue(coordinator.state.value.paused)
            } finally { coordinator.closeAndDrain() }
        }
    }

    /** Obtain the real app listener through an ordinary management invitation, then use its pinned TLS protocol. */
    private suspend fun liveListenerPort(f: Fixture, coordinator: LanSyncGroupCoordinator,
        access: LanSyncGroupRuntimeAccess): Int {
        coordinator.unlockManagement(requireNotNull(f.recoveryCode))
        await { coordinator.state.value.managementUnlocked && !coordinator.state.value.busy }
        coordinator.createInvitation()
        await { coordinator.state.value.invitation != null && !coordinator.state.value.busy }
        assertNull(coordinator.state.value.error)
        val port = requireNotNull(coordinator.state.value.invitation?.bootstrap).storePort
        val remote = access.remote(LanSyncPeer(f.identity.fingerprint, "synthetic self", "10.0.0.1", port), f.network)
        assertEquals(requireNotNull(access.replica.metadata(access.vault.key)), remote.metadata(access.vault.key))
        coordinator.closeInvitation()
        coordinator.lockManagement()
        return port
    }

    private fun retainedFiles(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        paths.iterator().asSequence().filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.associate { path ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            root.relativize(path).toString() to digest.digest().joinToString("") { "%02x".format(it) }
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
        val originalCard = requireNotNull(LanSyncJsonFiles.read(f.card, CharacterCard.serializer()))
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
        // Restore the already signed business value with a new local audit byte sequence. An
        // ordinary background scan may observe it, but has no new business proposal to publish.
        LanSyncJsonFiles.write(f.card, CharacterCard.serializer(), originalCard.copy(updatedAt = originalCard.updatedAt + 1))
        val edited = Files.readAllBytes(f.card)
        assertFalse(original.contentEquals(edited))
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

    @Test fun dismissedDeletionCannotPublishWhileActualStorageWakePublishesExactlyTheNewBusinessValue() = fixture { f ->
        val unit = "character_cards/card"
        f.writeCard("synthetic initial original before dismissed deletion")
        val original = Files.readAllBytes(f.card)
        val storage = JsonFileStorage(f.data)
        val coordinator = readyCoordinator(f, storage.committedRevisionFor(LanSyncScope.schemas.keys))
        val access = requireNotNull(f.founder().runtimeAccess())
        val author = f.author(access.trust)
        val initialOperation = access.node.sourceOperations().single()
        val initial = requireNotNull(author.entry(initialOperation))
        val origin = Files.readAllBytes(f.profile.resolve("node/author/origin.protected"))
        Files.delete(f.card)
        coordinator.retry()
        await { !coordinator.state.value.busy && coordinator.view.value?.plan?.units?.get(unit)?.stage == LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW }
        coordinator.prepareSourceReview(requireNotNull(coordinator.view.value), unit)
        await { !coordinator.state.value.busy && coordinator.actionsReview.value?.source != null }
        val dismissed = requireNotNull(coordinator.actionsReview.value)
        assertTrue(dismissed.canConfirm)
        assertTrue(requireNotNull(dismissed.source).units.getValue(unit).current.isEmpty())
        assertEquals(listOf(initialOperation), access.node.sourceOperations())
        assertEquals(1L, author.state().committedSequence)
        coordinator.cancelActionReview()
        assertNull(coordinator.actionsReview.value)

        storage.saveEntity("character_cards", "card", CharacterCard("card", "synthetic final business edit after dismissed deletion", createdAt = 1, updatedAt = 2), CharacterCard.serializer())
        val edited = Files.readAllBytes(f.card)
        val targetHash = f.workspace.capture(f.identity.fingerprint).use { it.snapshot.hashes().getValue(unit) }
        coordinator.confirmActionReview(dismissed.binding)
        await { !coordinator.state.value.busy && coordinator.state.value.error?.endsWith("SYNC_GROUP_REVIEW_DISMISSED") == true }
        assertNull(coordinator.actionsReview.value)
        await {
            val plan = coordinator.view.value?.plan
            !coordinator.state.value.busy &&
                plan?.before?.records?.singleOrNull()?.body?.get("name")?.jsonPrimitive?.content == "synthetic final business edit after dismissed deletion" &&
                plan?.units?.get(unit)?.stage == LanSyncGroupNativeStage.UNCHANGED
        }
        val operations = access.node.sourceOperations()
        assertEquals(2, operations.size)
        assertEquals(initialOperation, operations.first())
        assertEquals(2L, author.state().committedSequence)
        assertNull(author.state().pendingApproved)
        val published = requireNotNull(author.entry(operations.last()))
        assertEquals(2L, published.version.authorSequence)
        assertEquals(initial.version.reference(), published.version.previousByAuthor)
        assertEquals(setOf(unit), published.version.changes.keys)
        val change = published.version.changes.getValue(unit)
        assertNotNull(change.content, "The independent storage wake must publish the edited value, never the dismissed tombstone")
        assertEquals(targetHash, change.valueHash)
        assertEquals(setOf(initial.version.reference()), change.previous)
        assertNotNull(access.node.receipt(operations.last(), access.localTarget))
        assertEquals(initial, author.entry(initialOperation))
        assertContentEquals(origin, Files.readAllBytes(f.profile.resolve("node/author/origin.protected")))
        assertContentEquals(edited, Files.readAllBytes(f.card))
        assertTrue(f.workspace.completedRecoveries(f.identity.fingerprint).isEmpty())
        Files.newDirectoryStream(f.root.resolve("workspace"), "restore-intent-*.json").use { assertFalse(it.iterator().hasNext()) }
        assertSourceOriginal(f, initial, original)
        assertSourceOriginal(f, published, edited)

        val beforeRetry = coordinator.view.value
        coordinator.retry()
        await { coordinator.view.value !== beforeRetry && !coordinator.state.value.busy && coordinator.state.value.error == null }
        assertNull(coordinator.state.value.error)
        assertEquals(operations, access.node.sourceOperations())
        assertEquals(2L, author.state().committedSequence)
        coordinator.confirmActionReview(dismissed.binding)
        await { !coordinator.state.value.busy && coordinator.state.value.error?.endsWith("SYNC_GROUP_REVIEW_DISMISSED") == true }
        assertNull(coordinator.actionsReview.value)
        assertEquals(operations, access.node.sourceOperations())
        val afterDismissal = coordinator.view.value
        coordinator.retry()
        await { coordinator.view.value !== afterDismissal && !coordinator.state.value.busy && coordinator.state.value.error == null }
        assertNull(coordinator.state.value.error)
        assertEquals(operations, access.node.sourceOperations())
        assertEquals(2L, author.state().committedSequence)
        assertEquals(initial, author.entry(initialOperation))
        assertContentEquals(origin, Files.readAllBytes(f.profile.resolve("node/author/origin.protected")))
        assertContentEquals(edited, Files.readAllBytes(f.card))
        assertTrue(f.workspace.completedRecoveries(f.identity.fingerprint).isEmpty())
        Files.newDirectoryStream(f.root.resolve("workspace"), "restore-intent-*.json").use { assertFalse(it.iterator().hasNext()) }
        assertSourceOriginal(f, initial, original)
        assertSourceOriginal(f, published, edited)
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
        f.writeCard("synthetic published user edit before recovery")
        val edited = Files.readAllBytes(f.card)
        coordinator.retry()
        await {
            val plan = coordinator.view.value?.plan
            !coordinator.state.value.busy &&
                plan?.before?.records?.singleOrNull()?.body?.get("name")?.jsonPrimitive?.content == "synthetic published user edit before recovery" &&
                plan?.units?.get(unit)?.stage == LanSyncGroupNativeStage.UNCHANGED
        }
        assertNull(coordinator.state.value.error)
        val publishedOperations = access.node.sourceOperations()
        assertEquals(2, publishedOperations.size)
        assertEquals(initialOperation, publishedOperations.first())
        assertEquals(2L, author.state().committedSequence)
        val editedEntry = requireNotNull(author.entry(publishedOperations.last()))
        assertEquals(initial.version.reference(), editedEntry.version.previousByAuthor)
        assertNotNull(access.node.receipt(publishedOperations.last(), access.localTarget))
        assertSourceOriginal(f, editedEntry, edited)
        val publishedNative = requireNotNull(coordinator.view.value).nativeState
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
        assertEquals(listOf("synthetic published user edit before recovery"), firstPreview.currentRecords.map { it.body.getValue("name").jsonPrimitive.content })
        assertEquals(listOf("synthetic signed recovery original"), firstPreview.restoredRecords.map { it.body.getValue("name").jsonPrimitive.content })
        assertContentEquals(edited, Files.readAllBytes(f.card))
        assertEquals(publishedOperations, access.node.sourceOperations())
        assertEquals(2L, author.state().committedSequence)
        assertEquals(publishedNative, access.node.inspect().nativeState)
        assertEquals(objects, retainedFiles(f.profile.resolve("local-replica")))
        assertTrue(f.workspace.completedRecoveries(f.identity.fingerprint).isEmpty())
        coordinator.cancelActionReview()
        coordinator.confirmActionReview(cancelled.binding)
        await { !coordinator.state.value.busy && coordinator.state.value.error?.endsWith("SYNC_GROUP_REVIEW_DISMISSED") == true }
        assertContentEquals(edited, Files.readAllBytes(f.card))
        assertTrue(f.workspace.completedRecoveries(f.identity.fingerprint).isEmpty())
        assertEquals(publishedOperations, access.node.sourceOperations())
        assertEquals(2L, author.state().committedSequence)
        assertEquals(publishedNative, access.node.inspect().nativeState)
        assertEquals(objects, retainedFiles(f.profile.resolve("local-replica")))
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
        assertEquals(3, operations.size)
        assertEquals(publishedOperations, operations.take(2))
        assertEquals(3L, author.state().committedSequence)
        val restored = requireNotNull(author.entry(operations.last()))
        assertEquals(3L, restored.version.authorSequence)
        assertEquals(editedEntry.version.reference(), restored.version.previousByAuthor)
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
        assertSourceOriginal(f, editedEntry, edited)
        assertRecoveryOriginal(f, preview.id, edited)
    }

    @Test fun initiallyPausedReadyOwnerOpensReadOnlyUntilExplicitResumeThenPublishesActualStorageChanges() {
        val network = ObservedNetwork(noNetwork)
        fixture(network) { f ->
            val unit = "character_cards/card"
            f.writeCard("synthetic original before paused reopen")
            val original = Files.readAllBytes(f.card)
            val initial = readyCoordinator(f)
            val access = requireNotNull(f.founder().runtimeAccess())
            val author = f.author(access.trust)
            val firstOperation = access.node.sourceOperations().single()
            val first = requireNotNull(author.entry(firstOperation))
            val origin = Files.readAllBytes(f.profile.resolve("node/author/origin.protected"))
            initial.closeAndDrain()
            val retained = listOf("node", "custody", "local-replica", "app-runtime/replica-reads")
                .associateWith { retainedFiles(f.profile.resolve(it)) }
            val storage = JsonFileStorage(f.data)
            network.calls.set(0)
            network.forbidden.set(true)
            val coordinator = f.coordinator(storage.committedRevisionFor(LanSyncScope.schemas.keys), initiallyPaused = true)
            coordinator.open()
            await { coordinator.state.value.stage == LanSyncGroupPanelStage.READY && !coordinator.state.value.busy }
            assertTrue(coordinator.state.value.paused)
            assertTrue(coordinator.state.value.devices.any { it.thisDevice })
            assertFalse(coordinator.state.value.managementUnlocked)
            assertNull(coordinator.state.value.error)
            assertNull(coordinator.view.value, "Paused open must not run a native inspection or publication cycle")
            coordinator.onForeground()
            coordinator.onNetworkAvailable()
            coordinator.startIfConfigured()
            delay(350)
            assertTrue(coordinator.state.value.paused)
            assertEquals(0, network.calls.get())
            assertEquals(1L, author.state().committedSequence)
            assertEquals(listOf(firstOperation), access.node.sourceOperations())
            for ((name, files) in retained) assertEquals(files, retainedFiles(f.profile.resolve(name)), name)
            assertContentEquals(original, Files.readAllBytes(f.card))
            assertContentEquals(origin, Files.readAllBytes(f.profile.resolve("node/author/origin.protected")))

            network.forbidden.set(false)
            coordinator.resumeIfConfigured()
            await { !coordinator.state.value.paused && !coordinator.state.value.busy && coordinator.view.value != null }
            liveListenerPort(f, coordinator, access)
            assertTrue(network.calls.get() > 0)
            storage.saveEntity("character_cards", "card", CharacterCard("card", "synthetic committed edit after explicit resume", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
            val edited = Files.readAllBytes(f.card)
            await {
                val plan = coordinator.view.value?.plan
                plan?.before?.records?.singleOrNull()?.body?.get("name")?.jsonPrimitive?.content == "synthetic committed edit after explicit resume" &&
                    plan?.units?.get(unit)?.stage == LanSyncGroupNativeStage.UNCHANGED && !coordinator.state.value.busy
            }
            assertNull(coordinator.state.value.error)
            val operations = access.node.sourceOperations()
            assertEquals(2, operations.size)
            assertEquals(firstOperation, operations.first())
            assertEquals(2L, author.state().committedSequence)
            val resumed = requireNotNull(author.entry(operations.last()))
            assertEquals(first.version.reference(), resumed.version.previousByAuthor)
            assertNotNull(access.node.receipt(operations.last(), access.localTarget))
            assertContentEquals(origin, Files.readAllBytes(f.profile.resolve("node/author/origin.protected")))
            assertContentEquals(edited, Files.readAllBytes(f.card))
            assertSourceOriginal(f, first, original)
            assertSourceOriginal(f, resumed, edited)
            assertEquals(1, f.setupCalls)
        }
    }

    @Test fun recoverablePauseClosesListenerKeepsLateStorageCommitUnsignedAndResumesSameAuthorExactlyOnce() {
        val network = ObservedNetwork(noNetwork)
        fixture(network) { f ->
            val unit = "character_cards/card"
            f.writeCard("synthetic original before recoverable pause")
            val original = Files.readAllBytes(f.card)
            val storage = JsonFileStorage(f.data)
            val coordinator = readyCoordinator(f, storage.committedRevisionFor(LanSyncScope.schemas.keys))
            val access = requireNotNull(f.founder().runtimeAccess())
            val author = f.author(access.trust)
            val firstOperation = access.node.sourceOperations().single()
            val first = requireNotNull(author.entry(firstOperation))
            val origin = Files.readAllBytes(f.profile.resolve("node/author/origin.protected"))
            val oldPort = liveListenerPort(f, coordinator, access)
            coordinator.pauseAndDrain()
            assertTrue(coordinator.state.value.paused)
            assertFalse(coordinator.state.value.busy)
            assertFalse(coordinator.state.value.managementUnlocked)
            assertNull(coordinator.state.value.invitation)
            assertFails { Socket("127.0.0.1", oldPort).close() }
            val retained = listOf("node", "custody", "local-replica", "app-runtime/replica-reads")
                .associateWith { retainedFiles(f.profile.resolve(it)) }
            network.calls.set(0)
            network.forbidden.set(true)
            storage.saveEntity("character_cards", "card", CharacterCard("card", "synthetic late committed edit while paused", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
            val edited = Files.readAllBytes(f.card)
            coordinator.onForeground()
            coordinator.onNetworkAvailable()
            coordinator.startIfConfigured()
            coordinator.retry()
            delay(350)
            assertTrue(coordinator.state.value.paused)
            assertNull(coordinator.state.value.error)
            assertEquals(0, network.calls.get())
            assertEquals(1L, author.state().committedSequence)
            assertEquals(listOf(firstOperation), access.node.sourceOperations())
            for ((name, files) in retained) assertEquals(files, retainedFiles(f.profile.resolve(name)), name)
            assertContentEquals(origin, Files.readAllBytes(f.profile.resolve("node/author/origin.protected")))
            assertContentEquals(edited, Files.readAllBytes(f.card))

            network.forbidden.set(false)
            coordinator.resumeIfConfigured()
            await {
                val plan = coordinator.view.value?.plan
                !coordinator.state.value.paused && !coordinator.state.value.busy &&
                    plan?.before?.records?.singleOrNull()?.body?.get("name")?.jsonPrimitive?.content == "synthetic late committed edit while paused" &&
                    plan?.units?.get(unit)?.stage == LanSyncGroupNativeStage.UNCHANGED
            }
            assertNull(coordinator.state.value.error)
            val operations = access.node.sourceOperations()
            assertEquals(2, operations.size)
            assertEquals(firstOperation, operations.first())
            assertEquals(2L, author.state().committedSequence)
            val resumed = requireNotNull(author.entry(operations.last()))
            assertEquals(2L, resumed.version.authorSequence)
            assertEquals(first.version.reference(), resumed.version.previousByAuthor)
            assertNotNull(access.node.receipt(operations.last(), access.localTarget))
            liveListenerPort(f, coordinator, access)
            val previousView = coordinator.view.value
            coordinator.retry()
            await { coordinator.view.value !== previousView && !coordinator.state.value.busy }
            assertNull(coordinator.state.value.error)
            assertEquals(operations, access.node.sourceOperations())
            assertEquals(2L, author.state().committedSequence)
            assertContentEquals(origin, Files.readAllBytes(f.profile.resolve("node/author/origin.protected")))
            assertContentEquals(edited, Files.readAllBytes(f.card))
            assertSourceOriginal(f, first, original)
            assertSourceOriginal(f, resumed, edited)
            assertEquals(1, f.setupCalls)
        }
    }

    @Test fun finalSealInspectsFreshSourceOnceWhenItsCurrentLocalDeliveryAlreadyHasAReceipt() {
        fixture { f ->
            f.writeCard("synthetic original already sealed to local replica")
            val original = Files.readAllBytes(f.card)
            val inspections = AtomicInteger()
            val sourceProofs = AtomicInteger()
            val owner = f.founder(f.workspace) { event ->
                when (event) {
                    "NATIVE_PLAN_READY" -> inspections.incrementAndGet()
                    "SOURCE_SIGNED_DELIVERY_READY" -> sourceProofs.incrementAndGet()
                    else -> Unit
                }
            }
            val coordinator = f.coordinator(initiallyPaused = true, enrollment = { owner })
            coordinator.createGroup()
            await { coordinator.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE && !coordinator.state.value.busy }
            coordinator.recoverySaved(requireNotNull(coordinator.state.value.setupBinding))
            await { coordinator.state.value.stage == LanSyncGroupPanelStage.READY && !coordinator.state.value.busy }
            assertTrue(coordinator.state.value.paused)

            val access = requireNotNull(owner.runtimeAccess())
            val inspected = access.node.inspectLocal()
            val unit = "character_cards/card"
            val commit = access.node.commit(inspected.review(setOf(unit)))
            val operation = commit.entry.request.operation
            val receipt = access.node.upload(operation, access.replica, access.localTarget)
            coordinator.quiesceAndDrain()
            inspections.set(0)
            sourceProofs.set(0)

            assertTrue(coordinator.flushLocalAfterWriters())
            assertEquals(1, inspections.get())
            assertEquals(1, sourceProofs.get())
            assertEquals(LanSyncGroupNativeStage.UNCHANGED, coordinator.view.value?.plan?.units?.get(unit)?.stage)
            assertEquals(listOf(operation), access.node.sourceOperations())
            assertEquals(receipt, access.node.receipt(operation, access.localTarget))
            assertContentEquals(original, Files.readAllBytes(f.card))
            assertSourceOriginal(f, commit.entry, original)
        }
    }

    @Test fun committedStorageRevisionAutomaticallyPublishesAndLateShutdownSaveSealsWithoutNetworkOrReload() {
        val forbidden = AtomicBoolean(false)
        val networkCalls = AtomicInteger()
        fun networkTouch() { networkCalls.incrementAndGet(); check(!forbidden.get()) { "SYNTHETIC_SHUTDOWN_NETWORK_FORBIDDEN" } }
        val network = object : LanSyncNetwork {
            override fun addresses(): List<String> { networkTouch(); return noNetwork.addresses() }
            override fun accepts(address: InetAddress): Boolean { networkTouch(); return noNetwork.accepts(address) }
            override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket {
                networkTouch(); return noNetwork.connect(identity, peer)
            }
            override fun listening(fingerprint: String, port: Int) { networkTouch() }
            override fun discover(fingerprint: String): Pair<String, Int>? { networkTouch(); return null }
            override val discoveryEnabled = false
        }
        fixture(network) { f ->
            val unit = "character_cards/card"
            f.writeCard("synthetic initial committed original")
            val original = Files.readAllBytes(f.card)
            val storage = JsonFileStorage(f.data)
            val coordinator = readyCoordinator(f, storage.committedRevisionFor(LanSyncScope.schemas.keys))
            val access = requireNotNull(f.founder().runtimeAccess())
            val author = f.author(access.trust)
            val firstOperation = access.node.sourceOperations().single()
            val first = requireNotNull(author.entry(firstOperation))
            val started = System.nanoTime()
            storage.saveEntity("character_cards", "card", CharacterCard("card", "synthetic ordinary committed edit", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
            val ordinaryRaw = Files.readAllBytes(f.card)
            withTimeout(10_000) { await {
                val plan = coordinator.view.value?.plan
                plan?.before?.records?.singleOrNull()?.body?.get("name")?.jsonPrimitive?.content == "synthetic ordinary committed edit" &&
                    plan?.units?.get(unit)?.stage == LanSyncGroupNativeStage.UNCHANGED
            } }
            assertTrue(System.nanoTime() - started < 60_000_000_000L, "An actual storage commit must wake the owner before the safety rescan")
            assertNull(coordinator.state.value.error)
            val ordinaryOperations = access.node.sourceOperations()
            assertEquals(2, ordinaryOperations.size)
            assertEquals(2L, author.state().committedSequence)
            assertNotNull(access.node.receipt(ordinaryOperations.last(), access.localTarget))
            assertContentEquals(ordinaryRaw, Files.readAllBytes(f.card))
            assertSourceOriginal(f, first, original)
            coordinator.quiesceAndDrain()
            val reloadsBefore = f.reloads.get()
            val networkBefore = networkCalls.get()
            forbidden.set(true)
            storage.saveEntity("character_cards", "card", CharacterCard("card", "synthetic final writer after quiesce", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
            val lateRaw = Files.readAllBytes(f.card)
            assertEquals(ordinaryOperations, access.node.sourceOperations())
            assertEquals(2L, author.state().committedSequence)
            assertTrue(withTimeout(20_000) { coordinator.flushLocalAfterWriters() })
            assertEquals(reloadsBefore, f.reloads.get())
            assertEquals(networkBefore, networkCalls.get())
            assertContentEquals(lateRaw, Files.readAllBytes(f.card))
            val finalOperations = access.node.sourceOperations()
            assertEquals(3, finalOperations.size)
            assertEquals(ordinaryOperations, finalOperations.take(2))
            assertEquals(3L, author.state().committedSequence)
            val final = requireNotNull(author.entry(finalOperations.last()))
            assertEquals(3L, final.version.authorSequence)
            assertEquals(requireNotNull(author.entry(ordinaryOperations.last())).version.reference(), final.version.previousByAuthor)
            assertNotNull(access.node.receipt(finalOperations.last(), access.localTarget))
            assertSourceOriginal(f, final, lateRaw)
            assertSourceOriginal(f, first, original)
            assertTrue(coordinator.flushLocalAfterWriters())
            assertEquals(finalOperations, access.node.sourceOperations())
            assertEquals(reloadsBefore, f.reloads.get())
            assertEquals(networkBefore, networkCalls.get())
        }
    }

    @Test fun explicitPausedExportSealsOnlyTheNewLocalFactAndWritesARealVerifiedPackageWithoutNetworkOrReload() {
        val network = ObservedNetwork(noNetwork)
        fixture(network) { f ->
            f.writeCard("synthetic original before paused export")
            val original = Files.readAllBytes(f.card)
            val storage = JsonFileStorage(f.data)
            val coordinator = readyCoordinator(f, storage.committedRevisionFor(LanSyncScope.schemas.keys))
            val access = requireNotNull(f.founder().runtimeAccess())
            val author = f.author(access.trust)
            val firstOperation = access.node.sourceOperations().single()
            val first = requireNotNull(author.entry(firstOperation))
            assertEquals(1L, author.state().committedSequence)
            coordinator.pauseAndDrain()
            assertTrue(coordinator.state.value.paused)
            val authorOrigin = Files.readAllBytes(f.profile.resolve("node/author/origin.protected"))
            val nativeOrigin = Files.readAllBytes(f.profile.resolve("node/native/origin.protected"))
            val custody = retainedFiles(f.profile.resolve("custody"))
            val oldReplica = retainedFiles(f.profile.resolve("local-replica"))
            val reloads = f.reloads.get()
            network.calls.set(0); network.forbidden.set(true)
            val remoteCalls = AtomicInteger()
            val forbidden = object : CiphertextStore by access.replica {
                private fun touch(): Nothing { remoteCalls.incrementAndGet(); error("SYNTHETIC_PAUSED_EXPORT_REMOTE_FORBIDDEN") }
                override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult = touch()
                override fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata? = touch()
                override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? = touch()
                override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage = touch()
            }
            access.reader.routes("pausedexportforbidden", listOf(LanSyncGroupReplicaReader.Route("paused-export-forbidden", forbidden)))
            try {
                storage.saveEntity("character_cards", "card", CharacterCard("card", "synthetic new edit for explicit paused export",
                    basicSetting = "retain this exact local edit", createdAt = 1, updatedAt = 2), CharacterCard.serializer())
                val edited = Files.readAllBytes(f.card)
                coordinator.onForeground(); coordinator.onNetworkAvailable(); coordinator.startIfConfigured()
                assertEquals(listOf(firstOperation), access.node.sourceOperations())
                coordinator.openFileExport()
                val exporter = requireNotNull(coordinator.fileExport)
                exporter.prepare()
                withTimeout(30_000) { exporter.drain() }
                assertNull(exporter.state.value.error); assertFalse(exporter.state.value.busy)
                val binding = requireNotNull(exporter.state.value.preparedBinding)
                assertTrue(coordinator.state.value.paused)
                val operations = access.node.sourceOperations()
                assertEquals(2, operations.size); assertEquals(firstOperation, operations.first())
                assertEquals(2L, author.state().committedSequence)
                val fresh = requireNotNull(author.entry(operations.last()))
                assertEquals(2L, fresh.version.authorSequence); assertEquals(first.version.reference(), fresh.version.previousByAuthor)
                assertNotNull(access.node.receipt(operations.last(), access.localTarget))
                assertEquals(reloads, f.reloads.get()); assertEquals(0, network.calls.get()); assertEquals(0, remoteCalls.get())

                exporter.hide()
                assertFalse(exporter.state.value.visible); assertEquals(binding, exporter.state.value.preparedBinding)
                val output = f.root.resolve("actual-paused-export.cbgroup")
                exporter.write(binding, writer = { body -> Files.newOutputStream(output).use { stream -> body(stream) } },
                    readback = { Files.newInputStream(output) })
                withTimeout(30_000) { exporter.drain() }
                assertNull(exporter.state.value.error); assertTrue(exporter.state.value.writeCompleted); assertTrue(exporter.state.value.readbackVerified)
                assertFalse(exporter.state.value.visible); assertEquals(Files.size(output), exporter.state.value.bytes)
                val inspection = Files.createDirectory(f.root.resolve("inspect-export"))
                Files.newInputStream(output).use { input ->
                    LanSyncGroupEncryptedFilePackage.stage(input, inspection).use { staged ->
                        val actualCustody = f.custody(access.trust)
                        val ledger = actualCustody.ledger().current()
                        val inventory = staged.verifyInventory(actualCustody.key(ledger.epoch), access.trust, ledger.reference())
                        assertEquals(setOf(first.version.reference(), fresh.version.reference()), inventory.plan.versions)
                        assertEquals(SyncDeliveryKind.LOCAL_REPLICA, inventory.plan.target.kind)
                    }
                }
                assertTrue(coordinator.state.value.paused); assertNull(coordinator.state.value.error)
                assertEquals(operations, access.node.sourceOperations()); assertEquals(2L, author.state().committedSequence)
                assertEquals(reloads, f.reloads.get()); assertEquals(0, network.calls.get()); assertEquals(0, remoteCalls.get())
                assertContentEquals(edited, Files.readAllBytes(f.card))
                assertContentEquals(authorOrigin, Files.readAllBytes(f.profile.resolve("node/author/origin.protected")))
                assertContentEquals(nativeOrigin, Files.readAllBytes(f.profile.resolve("node/native/origin.protected")))
                assertEquals(custody, retainedFiles(f.profile.resolve("custody")))
                val afterReplica = retainedFiles(f.profile.resolve("local-replica"))
                oldReplica.forEach { (path, hash) -> assertEquals(hash, afterReplica[path], path) }
                assertSourceOriginal(f, first, original); assertSourceOriginal(f, fresh, edited)
                Files.newDirectoryStream(access.work.resolve("file-export")).use { paths ->
                    assertTrue(paths.none { path -> listOf("receipt-", "weak-receipt-", "chunk-receipt-", "cloud-receipt-")
                        .any { path.fileName.toString().startsWith(it) } })
                }
                val second = ByteArrayOutputStream()
                exporter.write(binding, writer = { it(second) })
                withTimeout(30_000) { exporter.drain() }
                assertTrue(exporter.state.value.writeCompleted); assertFalse(exporter.state.value.readbackVerified)
                assertContentEquals(Files.readAllBytes(output), second.toByteArray())
                assertEquals(operations, access.node.sourceOperations()); assertEquals(2L, author.state().committedSequence)
                assertTrue(coordinator.state.value.paused); assertEquals(reloads, f.reloads.get())
                assertEquals(0, network.calls.get()); assertEquals(0, remoteCalls.get())
            } finally { access.reader.removeRoutes("pausedexportforbidden") }
        }
    }

}
