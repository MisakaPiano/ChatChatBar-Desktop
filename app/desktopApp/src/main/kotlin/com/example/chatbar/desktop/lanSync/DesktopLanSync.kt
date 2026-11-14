package com.example.chatbar.desktop

import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.desktop.security.WindowsDpapiSecretProtector
import com.example.chatbar.desktop.security.WindowsSecretRootResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal class DesktopLanSync(private val app: DesktopAppContainer,
    private val exclusive: suspend (suspend () -> Unit) -> Unit,
    private val reload: suspend (Set<String>) -> Unit,
    private val resolve: (String) -> Path,
    private val asset: (String) -> ByteArray,
    private val shutdownExclusive: suspend (suspend () -> Unit) -> Unit = exclusive,
) {
    private val localRoot = DesktopLanSyncStartup.localRoot(app.appDataRoot)
    private val protector = WindowsDpapiSecretProtector()
    private val stateStore = LanSyncStateStore(localRoot, protector::protect, protector::unprotect)
    private val workspace = LanSyncWorkspace(app.appDataRoot, localRoot.resolve("workspace"), resolveResource = resolve,
        assetResource = { reference -> asset(reference) }, audioResource = { session, id, hash, source ->
            val artifact = UUID.nameUUIDFromBytes("ccb-lan-audio:$id:$hash".toByteArray(Charsets.UTF_8)).toString()
            val path = app.appDataRoot.resolve("audio/generated/$session/$artifact.mp3")
            runBlocking {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) app.voiceAudioStore.verify(path.toString()).toString()
                else Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS).use {
                    app.voiceAudioStore.persist(session, artifact, it, Files.size(source)).path
                }
            }
        }, validateResource = ::validateDesktopLanSyncResource, resourceReference = { _, path -> desktopLanSyncResourceReference(app.appDataRoot, path) },
        imagePreviewRaster = DesktopLanSyncRaster::preview)
    @Volatile private var shutdownFlush = false
    private val finalFlushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val finalFlushLock = Any()
    private var finalFlush: Deferred<Boolean>? = null
    @Volatile var finalLocalFlushComplete: Boolean? = null
        private set
    private val platform = object : LanSyncPlatform {
        override suspend fun exclusive(work: suspend () -> Unit) =
            if (shutdownFlush) shutdownExclusive.invoke(work) else exclusive.invoke(work)
        override suspend fun reload(sessionIds: Set<String>) {
            check(!shutdownFlush) { "SYNC_GROUP_SHUTDOWN_RELOAD_REFUSED" }
            reload.invoke(sessionIds)
        }
    }
    private val runtimeOwner = lazy { LanSyncRuntime(stateStore, workspace, platform, "CCB 电脑") }
    val runtime: LanSyncRuntime get() {
        require(!hasGroupEvidence()) { "SYNC_GROUP_LEGACY_DISABLED" }
        return runtimeOwner.value
    }
    fun hasGroupEvidence(): Boolean {
        val profile = localRoot.resolve("group-profile")
        val attributes = try { Files.readAttributes(profile, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
        catch (_: NoSuchFileException) { return false }
        catch (_: Exception) { return true }
        if (!attributes.isDirectory || attributes.isSymbolicLink) return true
        return try {
            Files.newDirectoryStream(profile).use { children -> children.any { child ->
                val entry = Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                when (child.fileName.toString()) {
                    "profile.lock" -> !entry.isRegularFile || entry.isSymbolicLink
                    "abandoned-drafts" -> !entry.isDirectory || entry.isSymbolicLink
                    else -> true
                }
            } }
        } catch (_: Exception) { true }
    }
    private val networkMonitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val networkMonitorLock = Any()
    @Volatile private var networkMonitorClosed = false
    private var networkMonitor: DesktopLanSyncNetworkMonitor? = null
    private var networkMonitorObserver: Job? = null
    private fun networkStageEligible(): Boolean = groupCoordinatorOwner.isInitialized() &&
        groupCoordinatorOwner.value.state.value.stage in setOf(LanSyncGroupPanelStage.READY, LanSyncGroupPanelStage.JOINING)
    private fun ensureNetworkMonitor() = synchronized(networkMonitorLock) {
        if (!networkMonitorClosed && networkMonitor == null && networkStageEligible() && hasGroupEvidence()) {
            networkMonitor = DesktopLanSyncNetworkMonitor(onNetworkAvailable = {
                val current = synchronized(networkMonitorLock) {
                    if (!networkMonitorClosed && networkStageEligible()) groupCoordinatorOwner.value else null
                }
                current?.onNetworkAvailable()
            })
        }
    }
    private suspend fun closeIneligibleNetworkMonitor() {
        val monitor = synchronized(networkMonitorLock) {
            if (!networkMonitorClosed && (!networkStageEligible() || !hasGroupEvidence())) networkMonitor else null
        } ?: return
        if (monitor.closeAndDrain()) {
            synchronized(networkMonitorLock) { if (networkMonitor === monitor) networkMonitor = null }
            ensureNetworkMonitor()
        }
    }
    private suspend fun closeNetworkMonitorAndDrain() = withContext(NonCancellable) {
        val (observer, monitor) = synchronized(networkMonitorLock) {
            networkMonitorClosed = true
            networkMonitorObserver to networkMonitor
        }
        observer?.cancelAndJoin()
        monitor?.closeAndDrain()
        networkMonitorScope.coroutineContext[Job]?.cancelAndJoin()
    }
    private val groupCoordinatorOwner: Lazy<LanSyncGroupCoordinator> = lazy {
        LanSyncGroupCoordinator(hasExistingEvidence = ::hasGroupEvidence,
            enrollment = { groupEnrollment }, joining = { groupJoining }, deviceName = "CCB 电脑",
            beforeSetup = { if (runtimeOwner.isInitialized()) runtimeOwner.value.closeAndDrain() },
            sourceChanges = app.jsonFileStorage.committedRevisionFor(LanSyncScope.schemas.keys)).also { coordinator ->
                networkMonitorObserver = networkMonitorScope.launch {
                    coordinator.state.collect {
                        if (hasGroupEvidence() && networkStageEligible()) ensureNetworkMonitor()
                        else closeIneligibleNetworkMonitor()
                    }
                }
            }
    }
    val groupCoordinator: LanSyncGroupCoordinator get() {
        val current = synchronized(networkMonitorLock) {
            check(!networkMonitorClosed || groupCoordinatorOwner.isInitialized()) { "SYNC_GROUP_COORDINATOR_CLOSED" }
            groupCoordinatorOwner.value
        }
        ensureNetworkMonitor()
        return current
    }
    private fun configuredCoordinatorIfOpen(): LanSyncGroupCoordinator? {
        val current = synchronized(networkMonitorLock) {
            if (networkMonitorClosed || !hasGroupEvidence()) null else groupCoordinatorOwner.value
        }
        if (current != null) ensureNetworkMonitor()
        return current
    }
    fun startGroupIfConfigured() {
        configuredCoordinatorIfOpen()?.startIfConfigured()
    }
    fun onForeground() { configuredCoordinatorIfOpen()?.onForeground() }
    suspend fun quiesceAndDrain() {
        closeNetworkMonitorAndDrain()
        if (groupCoordinatorOwner.isInitialized()) groupCoordinatorOwner.value.quiesceAndDrain()
        if (runtimeOwner.isInitialized()) runtimeOwner.value.closeAndDrain()
    }
    /** The retained actual task owns storage until joined, including after the bounded caller wait. */
    suspend fun flushLocalAfterWriters(): Boolean = withContext(NonCancellable) {
        if (!groupCoordinatorOwner.isInitialized()) return@withContext true
        val task = synchronized(finalFlushLock) {
            finalFlush ?: finalFlushScope.async(start = CoroutineStart.LAZY) {
                shutdownFlush = true
                try { groupCoordinatorOwner.value.flushLocalAfterWriters() }
                finally { shutdownFlush = false }
            }.also { finalFlush = it }
        }
        task.start()
        val result = withTimeoutOrNull(30_000) { task.await() }
            ?: throw DesktopLanSyncFinalFlushTimeoutException()
        finalLocalFlushComplete = result
        if (!result) throw DesktopLanSyncFinalFlushIncompleteException()
        true
    }
    private fun groupProfileRoot(): Path {
        val profile = localRoot.resolve("group-profile")
        if (Files.exists(profile, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(localRoot.resolve("identity.protected"), LinkOption.NOFOLLOW_LINKS)) { "SYNC_GROUP_DAILY_IDENTITY_MISSING" }
        }
        stateStore.identity()
        if (!Files.exists(profile, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(profile)
        return profile
    }
    private val groupEnrollmentOwner = lazy {
        val profile = groupProfileRoot()
        LanSyncGroupEnrollment(profile, workspace, platform, stateStore.identity(), protector::protect,
            protector::unprotect, ::validateDesktopLanSyncResource)
    }
    private val groupJoiningOwner = lazy {
        LanSyncGroupJoining(groupProfileRoot(), workspace, platform, stateStore.identity(), protector::protect,
            protector::unprotect, ::validateDesktopLanSyncResource)
    }
    val groupEnrollment get() = groupEnrollmentOwner.value
    val groupJoining get() = groupJoiningOwner.value
    fun lockGroupManagement() {
        if (groupCoordinatorOwner.isInitialized()) groupCoordinatorOwner.value.lockManagement()
        else if (groupEnrollmentOwner.isInitialized()) groupEnrollmentOwner.value.lockManagement()
    }
    fun recoverBeforeRepositories() = workspace.recover()
    suspend fun closeAndDrain() {
        closeNetworkMonitorAndDrain()
        val pending = synchronized(finalFlushLock) { finalFlush }
        if (pending != null && !pending.isCompleted &&
            withTimeoutOrNull(30_000) { pending.join(); true } != true) throw DesktopLanSyncFinalFlushTimeoutException()
        if (groupCoordinatorOwner.isInitialized()) groupCoordinatorOwner.value.closeAndDrain()
        if (groupEnrollmentOwner.isInitialized()) groupEnrollmentOwner.value.close()
        if (runtimeOwner.isInitialized()) runtimeOwner.value.closeAndDrain()
        finalFlushScope.cancel()
    }
}

internal sealed class DesktopLanSyncFinalFlushException(message: String) : IllegalStateException(message)

/** Thirty seconds bounds this wait only. The same real task and storage ownership remain retained. */
internal class DesktopLanSyncFinalFlushTimeoutException : DesktopLanSyncFinalFlushException(
    "本机同步封存仍在使用资料；等待已结束，任务和资料目录锁继续保留。CCB_LAN_SYNC_FINAL_LOCAL_FLUSH_PENDING",
)

/** The flush has joined; safe shutdown may continue while local sync work remains pending. */
internal class DesktopLanSyncFinalFlushIncompleteException : DesktopLanSyncFinalFlushException(
    "本机资料已保存；设备组同步尚未完成，原件和待传队列已保留。CCB_LAN_SYNC_FINAL_LOCAL_FLUSH_INCOMPLETE",
) {
    var teardownCompleted: Boolean = false
        private set
    internal fun markTeardownCompleted() { teardownCompleted = true }
}

/** Writers and audio have already drained. Pause backup once and keep it paused until normal close. */
internal class DesktopLanSyncShutdownMaintenance(private val backup: DesktopAutomaticBackupRuntime,
    private val coordinator: DesktopDataOperationCoordinator) {
    private val admission = Mutex()
    private var backupPaused = false
    suspend fun <T> withExclusive(work: suspend () -> T): T = admission.withLock {
        if (!backupPaused) {
            check(backup.pauseForMaintenance() is DesktopMaintenancePauseResult.Paused) { "SYNC_GROUP_SHUTDOWN_BACKUP_PAUSE_FAILED" }
            backupPaused = true
        }
        coordinator.withExclusiveMaintenance { work() }
    }
}

internal fun desktopLanSyncResourceReference(root: Path, path: Path): String {
    require(path.normalize().startsWith(root)) { "SYNC_EXTERNAL_RESOURCE" }
    return root.relativize(path).toString().replace('\\', '/')
}

internal object DesktopLanSyncStartup {
    fun localRoot(root: Path): Path {
        val base = WindowsSecretRootResolver.resolve().parent.resolve("ChatChatBarDesktopLanSync")
        require(!base.startsWith(root) && !root.startsWith(base)) { "SYNC_STATE_ROOT_OVERLAP" }
        return base.resolve(lanSyncHash(root.toString().toByteArray(Charsets.UTF_8)))
    }
    fun recover(root: Path) {
        val workspace = localRoot(root).resolve("workspace")
        if (!Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) return
        LanSyncWorkspace(root, workspace, resolveResource = { error("SYNC_STARTUP_RESOURCE_ACCESS") }).recover()
    }
}

internal fun validateDesktopLanSyncResource(kind: String, path: Path, record: LanSyncRecord) {
    if (kind == "images") {
        DesktopLanSyncRaster.decode(path, record, 256, renderOrientation = false).flush()
    }
}
