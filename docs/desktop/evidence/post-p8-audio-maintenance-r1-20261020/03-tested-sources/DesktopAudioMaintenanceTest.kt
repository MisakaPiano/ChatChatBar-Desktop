package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.snapshot.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopAudioMaintenanceTest {
    private class Native : DesktopAudioOutput {
        @Volatile var alias = false
        @Volatile var paused = false
        @Volatile var failClose = false
        var closeEntered: CompletableDeferred<Unit>? = null
        var closeRelease: CompletableDeferred<Unit>? = null
        val starts = Channel<Unit>(Channel.UNLIMITED)
        val plays = AtomicInteger()
        val closeAttempts = AtomicInteger()
        override suspend fun play(path: Path) { alias = true; paused = false; plays.incrementAndGet(); starts.send(Unit) }
        override suspend fun pause(): Long { check(alias); paused = true; return 4321 }
        override suspend fun resume() { check(alias && paused); paused = false }
        override suspend fun isPlaying() = alias && !paused
        override suspend fun stopAndDrain() {
            if (!alias) return
            closeAttempts.incrementAndGet()
            closeEntered?.complete(Unit)
            closeRelease?.await()
            if (failClose) error("synthetic close failure")
            alias = false; paused = false
        }
        override suspend fun closeAndDrain() { stopAndDrain() }
    }
    private class Fixture private constructor(val root: Path, val voice: GeneratedVoiceMessage) {
        val gate = DesktopDataOperationCoordinator()
        val native = Native()
        val focus = DesktopAudioFocus(native)
        val port = focus.port(formal = true)
        val player = DesktopVoicePlayback(DesktopVoiceAudioStore(root), port, gate)
        val audio = DesktopAudioMaintenance({ focus }) { player.stop() }
        val primitive = Primitive(gate)
        val snapshots = DesktopCoordinatedSnapshotService(primitive, gate, audioMaintenance = audio)
        val backup = DesktopAutomaticBackupRuntime(DesktopSettingsStore(root, gate), snapshots, gate)
        val services = mutableListOf<DesktopDataRootMigrationService>()
        var prepares = 0; var preflights = 0; var materializations = 0; var commits = 0
        var preflight: suspend () -> Unit = {}
        var rejectPreflight = false
        suspend fun playPaused() {
            player.playSequence(listOf(voice)); withTimeout(3000) { native.starts.receive() }
            // pause() is serialized with the player's completion/state publication.
            withTimeout(3000) { player.state.first { it.playing } }
            player.pause(); assertTrue(player.state.value.paused)
        }
        fun migration(): DesktopDataRootMigrationService {
            val resolution = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, root.parent.resolve("bootstrap.json"))
            return DesktopDataRootMigrationService(resolution, backup, gate,
                dependencies = DesktopDataRootMigrationDependencies(
                    prepareDestination = { source, target -> prepares++; DesktopMigrationDestinationPreparer().prepare(source, target) },
                    preflight = { _, _ ->
                        preflights++; assertFalse(native.alias); assertEquals(DesktopDataOperationCoordinatorState.EXCLUSIVE, gate.state)
                        preflight()
                        if (rejectPreflight) DesktopMigrationMaterializationPreflightResult.Failure(
                            DesktopMigrationMaterializationResult.Failure(DesktopMigrationMaterializationFailureKind.PRECONDITION_FAILED, "synthetic preflight"))
                        else DesktopMigrationMaterializationPreflightResult.Ready
                    },
                    createSafetySnapshot = { snapshot(root, it) },
                    materialize = { _, dest -> materializations++; DesktopMigrationMaterializationResult.Materialized(dest.destinationRoot,
                        DesktopMigrationMaterializationSummary(0, 0, 0, emptyList()), emptyList()) },
                    commitAuthority = { _, dest -> commits++; DesktopBootstrapAuthorityCommitResult.Committed(dest,
                        DesktopBootstrapDocument(DesktopDataRootSelection.custom(dest), buildJsonObject {})) },
                ), audioMaintenance = audio,
            ).also { services += it }
        }
        suspend fun close() {
            native.failClose = false; native.closeRelease?.complete(Unit)
            player.closeAndDrain(); focus.closeAndDrain(); backup.close()
            services.forEach { it.close() }; gate.closeAndDrain()
            assertFalse(native.alias); assertEquals(DesktopDataOperationCoordinatorState.CLOSED, gate.state)
            check(root.parent.fileName.toString().startsWith("post-audio-maint-"))
            Files.walk(root.parent).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
        companion object {
            suspend fun create(): Fixture {
                val root = Files.createDirectory(Files.createTempDirectory("post-audio-maint-").resolve("source"))
                val clip = DesktopVoiceAudioStore(root).persist("session", "clip", fishPreviewMp3().inputStream())
                val v = GeneratedVoiceMessage.create("session", "message", "version", null, 0, "WHOLE_MESSAGE", "", "hello", "hello", "hello",
                    "alice", "Alice", FishAudioVoiceBinding("ref", "synthetic"), "s2.1-pro-free", clip.path, clip.durationMs, clip.byteLength)
                return Fixture(root, v).also { it.backup.initialize() }
            }
        }
    }
    private class Primitive(val gate: DesktopDataOperationCoordinator) : DesktopSnapshotPrimitive {
        var automaticCalls = 0
        var onAutomatic: () -> Unit = {}
        override fun executeAutomaticBackup(minimumInterval: Duration, maximumCount: Int): AutomaticBackupExecutionResult {
            assertEquals(DesktopDataOperationCoordinatorState.EXCLUSIVE, gate.state)
            automaticCalls++; onAutomatic(); return AutomaticBackupExecutionResult.SkippedNotDue
        }
        override fun createSnapshot(purpose: SnapshotPurpose) = snapshot(Path.of("synthetic"), purpose)
        override fun restoreSnapshot(snapshotDirectory: Path) = error("not used")
        override fun pruneAutomaticSnapshots(maximumCount: Int) = AutomaticSnapshotPruneResult(emptyList())
        override fun listSnapshots() = emptyList<AppDataSnapshot>()
        override fun validateSnapshot(snapshotDirectory: Path) = SnapshotValidation.VALID
    }

    @Test fun `paused lease blocks exclusive until confirmed native drain`(): Unit = maintenanceTest {
        val f = Fixture.create()
        try {
            f.playPaused()
            val entered = CompletableDeferred<Unit>()
            val exclusive = async(start = CoroutineStart.UNDISPATCHED) { f.gate.withExclusiveMaintenance { entered.complete(Unit) } }
            assertEquals(DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING, f.gate.state); assertFalse(entered.isCompleted)
            f.native.closeEntered = CompletableDeferred(); f.native.closeRelease = CompletableDeferred()
            val stop = async { f.player.stop() }
            withTimeout(3000) { f.native.closeEntered!!.await() }
            assertFalse(entered.isCompleted); assertTrue(f.native.alias)
            f.native.closeRelease!!.complete(Unit); withTimeout(3000) { stop.await(); exclusive.await() }
            assertFalse(f.native.alias); assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.gate.state)
        } finally { f.close() }
    }

    @Test fun `migration waits for drain rejects late playback and seals after commit`(): Unit = maintenanceTest {
        val f = Fixture.create()
        try {
            f.playPaused(); f.native.closeEntered = CompletableDeferred(); f.native.closeRelease = CompletableDeferred()
            val tx = f.migration(); val migration = async { tx.migrate(f.root.parent.resolve("destination")) }
            withTimeout(3000) { f.native.closeEntered!!.await() }
            assertEquals(0, f.prepares); assertEquals(0, f.preflights); assertEquals(0, f.materializations); assertEquals(0, f.commits)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.preflight = { entered.complete(Unit); release.await() }
            val oldAdmission = f.port.playbackAdmissionToken() - 1
            val latePlay = async { f.player.playSequence(listOf(f.voice), oldAdmission) }
            val lateQueue = async { f.player.enqueueSequence(listOf(f.voice)) }
            val resume = async { f.player.resume() }
            f.native.closeRelease!!.complete(Unit)
            withTimeout(3000) { entered.await(); latePlay.await(); lateQueue.await(); resume.await() }
            assertEquals(1, f.native.plays.get()); assertFalse(f.native.alias)
            assertFails { f.port.playFormal(Path.of(f.voice.audioPath)) }
            release.complete(Unit)
            assertIs<DesktopDataRootMigrationResult.Success>(withTimeout(3000) { migration.await() })
            assertEquals(1, f.materializations); assertEquals(1, f.commits); assertTrue(f.gate.isRestartRequired)
            assertFails { f.port.playFormal(Path.of(f.voice.audioPath)) }
            assertFailsWith<DesktopAudioMaintenanceBusyException> { f.audio.withMaintenance(false, { false }) {} }
        } finally { f.close() }
    }

    @Test fun `failed native close fails closed before any migration files then explicit retry recovers`(): Unit = maintenanceTest {
        val f = Fixture.create()
        try {
            f.playPaused(); val original = Files.readAllBytes(Path.of(f.voice.audioPath)); f.native.failClose = true
            val tx = f.migration()
            val failed = assertIs<DesktopDataRootMigrationResult.Failure>(tx.migrate(f.root.parent.resolve("failed")))
            assertEquals(DesktopDataRootMigrationFailureKind.AUDIO_MAINTENANCE, failed.kind)
            assertTrue(failed.sourceStillAuthoritative == true); assertFalse(failed.restartRequired)
            assertEquals(0, f.prepares); assertEquals(0, f.materializations); assertEquals(0, f.commits)
            assertTrue(f.native.alias); assertContentEquals(original, Files.readAllBytes(Path.of(f.voice.audioPath)))
            val unsafeExclusive = async(start = CoroutineStart.UNDISPATCHED) { f.gate.withExclusiveMaintenance { error("alias still open") } }
            assertEquals(DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING, f.gate.state)
            unsafeExclusive.cancelAndJoin()
            assertEquals(1, f.native.closeAttempts.get()) // No autonomous close retry hides the failure.
            assertFails { f.port.playFormal(Path.of(f.voice.audioPath)) }
            assertFailsWith<DesktopAudioMaintenanceBusyException> { f.snapshots.executeAutomaticBackup(Duration.ZERO, 2) }
            f.native.failClose = false; f.rejectPreflight = true
            val retry = assertIs<DesktopDataRootMigrationResult.Failure>(tx.migrate(f.root.parent.resolve("retry")))
            assertEquals(DesktopDataRootMigrationFailureKind.PREFLIGHT, retry.kind)
            assertIs<DesktopMaintenanceResumeResult.Resumed>(retry.runtimeDisposition)
            f.port.playFormal(Path.of(f.voice.audioPath)); f.port.stopAndDrain()
            assertFalse(f.native.alias); assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.gate.state)
        } finally { f.close() }
    }

    @Test fun `automatic backup reports busy without exclusive and scheduler retries after stop`(): Unit = maintenanceTest {
        val f = Fixture.create()
        val clock = TestCoroutineScheduler()
        val primitive = Primitive(f.gate)
        val dispatcher = StandardTestDispatcher(clock)
        val service = DesktopCoordinatedSnapshotService(primitive, f.gate, dispatcher, f.audio)
        val events = mutableListOf<DesktopAutomaticBackupEvent>()
        val scheduler = DesktopAutomaticBackupScheduler(service::executeAutomaticBackup, dispatcher) { events += it }
        try {
            f.playPaused(); scheduler.start(DesktopAutomaticBackupSchedule(Duration.ZERO, 2, Duration.ofSeconds(1))); clock.runCurrent()
            assertEquals(1, events.size); assertIs<DesktopAudioMaintenanceBusyException>(assertIs<DesktopAutomaticBackupEvent.Failed>(events.single()).error)
            assertEquals(0, primitive.automaticCalls); assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.gate.state)
            assertTrue(f.native.alias && f.native.paused)
            f.player.stop(); clock.advanceTimeBy(1000); clock.runCurrent()
            assertEquals(1, primitive.automaticCalls); assertIs<DesktopAutomaticBackupEvent.SkippedNotDue>(events.last())
        } finally {
            val closing = async(start = CoroutineStart.UNDISPATCHED) { scheduler.close() }
            clock.runCurrent(); closing.await(); f.close()
        }
    }

    @Test fun `idle backup reservation blocks check to exclusive race and migration defers without scheduler cycle`(): Unit = maintenanceTest {
        val f = Fixture.create()
        try {
            val normalEntered = CompletableDeferred<Unit>(); val releaseNormal = CompletableDeferred<Unit>()
            val normal = async { f.gate.withNormalOperation { normalEntered.complete(Unit); releaseNormal.await() } }
            normalEntered.await()
            val backup = async(start = CoroutineStart.UNDISPATCHED) { f.snapshots.executeAutomaticBackup(Duration.ZERO, 2) }
            assertEquals(DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING, f.gate.state)
            val requests = f.native.plays.get(); f.player.playSequence(listOf(f.voice)); f.player.enqueueSequence(listOf(f.voice))
            assertEquals(requests, f.native.plays.get())
            val result = assertIs<DesktopDataRootMigrationResult.Failure>(f.migration().migrate(f.root.parent.resolve("busy")))
            assertEquals(DesktopDataRootMigrationFailureKind.AUDIO_MAINTENANCE, result.kind); assertEquals(0, f.prepares)
            releaseNormal.complete(Unit); withTimeout(3000) { normal.await(); backup.await() }
            f.rejectPreflight = true
            assertEquals(DesktopDataRootMigrationFailureKind.PREFLIGHT,
                assertIs<DesktopDataRootMigrationResult.Failure>(f.migration().migrate(f.root.parent.resolve("after-backup"))).kind)
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.gate.state)
        } finally { f.close() }
    }

    @Test fun `migration reservation makes concurrent automatic checks defer before scheduler join`(): Unit = maintenanceTest {
        val f = Fixture.create()
        try {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); f.rejectPreflight = true
            f.preflight = { entered.complete(Unit); release.await() }
            val migration = async { f.migration().migrate(f.root.parent.resolve("destination")) }
            entered.await()
            assertFailsWith<DesktopAudioMaintenanceBusyException> { f.snapshots.executeAutomaticBackup(Duration.ZERO, 2) }
            release.complete(Unit); withTimeout(3000) { migration.await() }
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.gate.state)
        } finally { f.close() }
    }

    @Test fun `in flight real scheduler backup cannot form migration audio scheduler join cycle`(): Unit = maintenanceTest {
        val f = Fixture.create()
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        try {
            f.primitive.onAutomatic = { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
            f.backup.applySettings(DesktopSettings(automaticBackup = DesktopAutomaticBackupSettings(enabled = true)))
            entered.await()
            val busy = assertIs<DesktopDataRootMigrationResult.Failure>(f.migration().migrate(f.root.parent.resolve("during-scheduler")))
            assertEquals(DesktopDataRootMigrationFailureKind.AUDIO_MAINTENANCE, busy.kind)
            assertEquals(0, f.prepares)
            release.countDown()
            f.backup.applySettings(DesktopSettings(automaticBackup = DesktopAutomaticBackupSettings(enabled = false)))
            f.rejectPreflight = true
            assertEquals(DesktopDataRootMigrationFailureKind.PREFLIGHT,
                assertIs<DesktopDataRootMigrationResult.Failure>(f.migration().migrate(f.root.parent.resolve("after-scheduler"))).kind)
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.gate.state)
        } finally { release.countDown(); f.close() }
    }

    @Test fun `cancellation completes native drain before reopening admission and skips migration transaction`(): Unit = maintenanceTest {
        val f = Fixture.create()
        try {
            f.playPaused(); f.native.closeEntered = CompletableDeferred(); f.native.closeRelease = CompletableDeferred()
            val migration = async { f.migration().migrate(f.root.parent.resolve("cancelled")) }
            f.native.closeEntered!!.await(); migration.cancel()
            assertFalse(migration.isCompleted); assertTrue(f.native.alias); assertEquals(0, f.prepares)
            f.native.closeRelease!!.complete(Unit); withTimeout(3000) { migration.join() }
            assertTrue(migration.isCancelled); assertFalse(f.native.alias); assertEquals(0, f.materializations)
            f.port.playFormal(Path.of(f.voice.audioPath)); f.port.stopAndDrain()
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.gate.state)
        } finally { f.close() }
    }

    @Test fun `cancelled pending exclusive and duplicate migration release both admission owners`(): Unit = maintenanceTest {
        val f = Fixture.create()
        try {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); f.rejectPreflight = true
            f.preflight = { entered.complete(Unit); release.await() }
            val tx = f.migration(); val first = async { tx.migrate(f.root.parent.resolve("first")) }
            entered.await()
            val duplicate = async(start = CoroutineStart.UNDISPATCHED) { tx.migrate(f.root.parent.resolve("second")) }
            assertEquals(1, f.prepares); duplicate.cancelAndJoin(); first.cancelAndJoin()
            assertEquals(DesktopAutomaticBackupRuntimeMode.ACTIVE, f.backup.state.value.mode)
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.gate.state)
            f.port.playFormal(Path.of(f.voice.audioPath)); f.port.stopAndDrain()
        } finally { f.close() }
    }

    @Test fun `cancellation while waiting for exclusive releases audio reservation and destination`(): Unit = maintenanceTest {
        val f = Fixture.create()
        try {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val normal = async { f.gate.withNormalOperation { entered.complete(Unit); release.await() } }
            entered.await()
            val tx = f.migration()
            val migration = async(start = CoroutineStart.UNDISPATCHED) { tx.migrate(f.root.parent.resolve("cancel-pending")) }
            assertEquals(DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING, f.gate.state)
            migration.cancelAndJoin()
            assertEquals(0, f.materializations); assertEquals(0, f.commits)
            release.complete(Unit); normal.await()
            f.port.playFormal(Path.of(f.voice.audioPath)); f.port.stopAndDrain()
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.gate.state)
        } finally { f.close() }
    }

    @Test fun `real coordinator playFrom intent resume and autoplay cannot reopen during drain`(): Unit = maintenanceTest {
        val gate = DesktopDataOperationCoordinator(); val native = Native(); val focus = DesktopAudioFocus(native)
        DesktopChatVoiceRuntimeTest.Fixture(gate, { focus.port(formal = true) }).use { f ->
            try {
                f.seed(); f.runtime.foreground(f.session.id).join()
                val clip = f.audio.persist(f.session.id, "manual", fishPreviewMp3().inputStream())
                val v = GeneratedVoiceMessage.create(f.session.id, f.message.id,
                    com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy.activeVersionId(f.message), null, 0, "WHOLE_MESSAGE", "", "hello", "hello", "hello",
                    "alice", "Alice", FishAudioVoiceBinding("reference", "snapshot"), "s2.1-pro-free", clip.path, clip.durationMs, clip.byteLength)
                f.repo.save(v)
                f.runtime.coordinator.playFrom(v, listOf(v)).join()
                withTimeout(3000) { native.starts.receive(); f.runtime.playback.state.first { it.playing } }
                f.runtime.playback.pause()
                val old = f.runtime.playbackIntent(); val admission = f.runtime.playback.admissionToken()
                native.closeEntered = CompletableDeferred(); native.closeRelease = CompletableDeferred()
                val inMaintenance = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
                val audio = DesktopAudioMaintenance({ focus }) { f.runtime.stopForMaintenance() }
                val work = async { audio.withMaintenance(true, { false }) { inMaintenance.complete(Unit); finish.await() } }
                native.closeEntered!!.await()
                f.runtime.coordinator.playFrom(v, listOf(v), old).join()
                val resume = async { f.runtime.playback.resume() }
                val automatic = async { f.runtime.playback.enqueueSequence(listOf(v), admission) }
                native.closeRelease!!.complete(Unit)
                withTimeout(3000) { inMaintenance.await(); resume.await(); automatic.await() }
                assertEquals(1, native.plays.get()); assertFalse(native.alias); assertEquals(0, f.server.requestCount)
                finish.complete(Unit); work.await()
            } finally { native.closeRelease?.complete(Unit); f.runtime.stopForMaintenance(); focus.closeAndDrain() }
        }
        gate.closeAndDrain(); assertFalse(native.alias)
    }

    private fun maintenanceTest(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking {
        withTimeout(15000, block)
    }

    private companion object {
        fun snapshot(root: Path, purpose: SnapshotPurpose) = AppDataSnapshot("synthetic", root.resolve("snapshot"), Instant.EPOCH, purpose, SnapshotValidation.VALID)
    }
}
