package com.example.chatbar.lanSync

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.chatbar.ChatBarApp
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class AndroidLanSyncBackgroundPhase { NOT_CONFIGURED, RECOVERY_PENDING, WAITING_FOR_FOREGROUND, STARTING, RUNNING, STOPPING, PAUSED, FAILED }

data class AndroidLanSyncBackgroundState(
    val phase: AndroidLanSyncBackgroundPhase = AndroidLanSyncBackgroundPhase.NOT_CONFIGURED,
    val notificationsVisible: Boolean = true,
    val foregroundProtected: Boolean = false,
    val error: String? = null
)

class AndroidLanSync(private val app: ChatBarApp) {
    val gate = LanSyncOperationGate()
    private val dataRoot = app.filesDir.canonicalFile.toPath()
    private val root = app.noBackupFilesDir.canonicalFile.toPath().resolve("lan-sync-v1")
    private val protection = AndroidLanSyncKeyProtection()
    private val store = LanSyncStateStore(root, protection::protect, protection::unprotect)
    private val workspace = LanSyncWorkspace(dataRoot, root.resolve("workspace"),
        resolveResource = { reference -> androidLanSyncResourcePath(dataRoot, app.filesDir.toPath(), reference) }, assetResource = { reference ->
            app.assets.open(reference.removePrefix("asset:").removePrefix("/")).use { input ->
                val result = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break
                    require(result.size() + count <= 32 * 1024 * 1024); result.write(buffer, 0, count) }
                result.toByteArray()
            }
        }, audioResource = { _, _, hash, source ->
            val target = dataRoot.resolve("audio/lan-$hash.mp3")
            LanSyncBlobs.publish(source, target, Files.size(source), hash, root)
            target.toString()
        }, validateResource = ::validateAndroidLanSyncResource, imagePreviewRaster = ::androidLanSyncPreviewRaster)
    private val platform = object : LanSyncPlatform {
        override suspend fun exclusive(work: suspend () -> Unit) {
            require(app.lanSyncStartupReady) { "SYNC_INITIALIZING" }
            app.momentScheduler.withLanSyncMaintenance {
                AiBackgroundWorkManager.withLanSyncMaintenance { gate.exclusive {
                    app.novelAiStudioRepository.prepareForLanSync()
                    work()
                } }
            }
        }
        override suspend fun reload(sessionIds: Set<String>) {
            app.characterRepository.refreshFromStorage(); app.formatCardRepository.refreshFromStorage(); app.worldBookRepository.refreshFromStorage()
            app.chatRepository.reloadAfterLanSync(); app.voiceMessageRepository.reload()
            app.momentRepository.refreshFromStorage()
            app.settingsRepository.refreshPlayerSettingFromStorage()
            app.novelAiStudioRepository.initialize()
            app.novelAiStudioRepository.reloadAfterLanSync()
        }
    }
    private val runtimeOwner = lazy { LanSyncRuntime(store, workspace, platform, android.os.Build.MODEL.take(80)) }
    val runtime: LanSyncRuntime get() {
        require(!hasGroupEvidence()) { "SYNC_GROUP_LEGACY_DISABLED" }
        return runtimeOwner.value
    }
    fun hasGroupEvidence(): Boolean {
        val profile = root.resolve("group-profile")
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
    private val hookLock = Any()
    private var hookObserver: Job? = null
    private var applicationHooks: AndroidLanSyncLifecycle? = null
    @Volatile private var hooksClosed = false
    private val backgroundAdmission = Mutex()
    private val backgroundPreferences = app.getSharedPreferences("lan-sync-background", Context.MODE_PRIVATE)
    @Volatile private var userPaused = backgroundPreferences.getBoolean("user-paused", false)
    @Volatile private var serviceGeneration = 0L
    @Volatile private var serviceAliveGeneration = -1L
    @Volatile private var serviceStartPending = false
    @Volatile private var protectionLost = false
    private var stopPromotedService: (() -> Unit)? = null
    private val mutableBackgroundState = MutableStateFlow(AndroidLanSyncBackgroundState())
    val backgroundState = mutableBackgroundState.asStateFlow()
    private val cloudWorkOwner: Lazy<AndroidLanSyncCloudWork> = lazy {
        AndroidLanSyncCloudWork(app,
            canStart = { synchronized(hookLock) {
                !hooksClosed && app.lanSyncStartupReady && applicationHooks?.isForeground == true
            } },
            canContinue = { !hooksClosed && app.lanSyncStartupReady },
            cancelCloud = { if (groupCoordinatorOwner.isInitialized()) groupCoordinatorOwner.value.cancelCloudWork() })
    }

    /** Install before the first Activity: late setup must still know that an Activity is resumed. */
    fun observeApplicationLifecycle(): Unit = synchronized(hookLock) {
        if (!hooksClosed && applicationHooks == null)
            applicationHooks = AndroidLanSyncLifecycle(app, ::onForeground, ::lockGroupManagement, ::onNetworkAvailable)
    }
    private fun ensureApplicationHooks() {
        observeApplicationLifecycle()
        if (hasGroupEvidence()) applicationHooks?.observeNetwork()
    }
    private fun notificationsVisible(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return false
        return app.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(LanSyncGroupForegroundService.CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    private fun backgroundStatus(phase: AndroidLanSyncBackgroundPhase, error: String? = null) {
        mutableBackgroundState.value = AndroidLanSyncBackgroundState(phase,
            runCatching { notificationsVisible() }.getOrDefault(false),
            serviceAliveGeneration >= 0 && serviceAliveGeneration == serviceGeneration, error)
    }
    fun onForeground() {
        ensureApplicationHooks()
        app.applicationScope.launch { startBackgroundIfAllowed(retryFailure = true) }
        val current = synchronized(hookLock) {
            if (!hooksClosed && !userPaused && serviceAliveGeneration == serviceGeneration &&
                backgroundState.value.phase == AndroidLanSyncBackgroundPhase.RUNNING &&
                app.lanSyncStartupReady && groupCoordinatorOwner.isInitialized()) groupCoordinatorOwner.value else null
        }
        current?.onForeground()
    }
    fun onNetworkAvailable() {
        val current = synchronized(hookLock) {
            if (!hooksClosed && !userPaused && serviceAliveGeneration == serviceGeneration &&
                backgroundState.value.phase == AndroidLanSyncBackgroundPhase.RUNNING &&
                app.lanSyncStartupReady && groupCoordinatorOwner.isInitialized()) groupCoordinatorOwner.value else null
        }
        current?.onNetworkAvailable()
    }
    private val groupCoordinatorOwner: Lazy<LanSyncGroupCoordinator> = lazy {
        LanSyncGroupCoordinator(hasExistingEvidence = ::hasGroupEvidence,
            enrollment = { groupEnrollment }, joining = { groupJoining }, deviceName = android.os.Build.MODEL.take(80),
            beforeSetup = { if (runtimeOwner.isInitialized()) runtimeOwner.value.closeAndDrain() },
            sourceChanges = app.jsonFileStorage.committedRevisionFor(LanSyncScope.schemas.keys), initiallyPaused = true,
            cloudWorkAllowed = { !userPaused && cloudWorkOwner.isInitialized() && cloudWorkOwner.value.allowed },
            cloudWork = ::runCloudWork,
            cloudRecoveryFactory = { ready -> LanSyncGroupCloudRecoveryOwner(
                root = { root.resolve("cloud-recovery").also { if (!Files.exists(it)) Files.createDirectory(it) } },
                joining = { groupJoining }, deviceName = android.os.Build.MODEL.take(80),
                beforeSetup = { if (runtimeOwner.isInitialized()) runtimeOwner.value.closeAndDrain() },
                networkAllowed = { !userPaused && cloudWorkOwner.isInitialized() && cloudWorkOwner.value.allowed },
                cloudWork = ::runCloudWork, onReady = ready) },
            fileRecoveryFactory = { ready -> LanSyncGroupFileRecoveryOwner(
                // External recovery routes share the same original management journal.
                root = { root.resolve("cloud-recovery").also { if (!Files.exists(it)) Files.createDirectory(it) } },
                joining = { groupJoining }, deviceName = android.os.Build.MODEL.take(80),
                beforeSetup = { if (runtimeOwner.isInitialized()) runtimeOwner.value.closeAndDrain() },
                dataWork = ::runFileWork, onReady = ready) }, fileWork = ::runFileWork).also { coordinator ->
                hookObserver = app.applicationScope.launch {
                    coordinator.state.collect {
                        if (hasGroupEvidence()) ensureApplicationHooks()
                        startBackgroundIfAllowed()
                    }
                }
            }
    }
    /** The production batch path; the native opt-in proof exercises this same foreground gate. */
    internal suspend fun runCloudWork(work: suspend () -> Boolean): Boolean {
        if (userPaused) return false
        return cloudWorkOwner.value.run { if (userPaused) false else work() }
    }
    /** Explicit local file work uses the same finite service, with no permission for network routes. */
    internal suspend fun runFileWork(work: suspend () -> Boolean): Boolean = cloudWorkOwner.value.run(work)
    val groupCoordinator: LanSyncGroupCoordinator get() = groupCoordinatorOwner.value
    fun openGroupPage() {
        groupCoordinator.open()
        ensureApplicationHooks()
        app.applicationScope.launch { startBackgroundIfAllowed() }
    }
    fun startGroupIfConfigured() {
        if (hasGroupEvidence()) {
            ensureApplicationHooks()
            groupCoordinator.startIfConfigured()
            app.applicationScope.launch { startBackgroundIfAllowed() }
        }
    }
    private suspend fun startBackgroundIfAllowed(retryFailure: Boolean = false) = backgroundAdmission.withLock {
        if (hooksClosed || protectionLost) return@withLock
        if (!hasGroupEvidence()) {
            if (backgroundState.value.phase != AndroidLanSyncBackgroundPhase.NOT_CONFIGURED) {
                if (!pauseCoordinatorSafely()) return@withLock
                val stop = stopPromotedService
                stopPromotedService = null
                backgroundStatus(if (serviceStartPending) AndroidLanSyncBackgroundPhase.STOPPING else AndroidLanSyncBackgroundPhase.NOT_CONFIGURED)
                stop?.invoke()
            }
            return@withLock
        }
        val phase = backgroundState.value.phase
        if (userPaused) {
            if (phase !in setOf(AndroidLanSyncBackgroundPhase.STOPPING, AndroidLanSyncBackgroundPhase.FAILED))
                backgroundStatus(AndroidLanSyncBackgroundPhase.PAUSED)
            return@withLock
        }
        val recoveryPending = try {
            groupJoining.profile()?.stage?.let { it != LanSyncGroupJoinStage.READY && groupJoining.recoveryAnchor() != null } == true
        } catch (_: Exception) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            backgroundStatus(AndroidLanSyncBackgroundPhase.FAILED, "设备组本机进度暂时无法读取，已有文件已保留")
            return@withLock
        }
        if (recoveryPending) {
            backgroundStatus(AndroidLanSyncBackgroundPhase.RECOVERY_PENDING)
            return@withLock
        }
        if (phase in setOf(AndroidLanSyncBackgroundPhase.STARTING, AndroidLanSyncBackgroundPhase.RUNNING, AndroidLanSyncBackgroundPhase.STOPPING)) {
            if (retryFailure) backgroundStatus(phase, backgroundState.value.error)
            return@withLock
        }
        if (phase == AndroidLanSyncBackgroundPhase.FAILED && !retryFailure) return@withLock
        if (!app.lanSyncStartupReady || applicationHooks?.isForeground != true) {
            backgroundStatus(AndroidLanSyncBackgroundPhase.WAITING_FOR_FOREGROUND)
            return@withLock
        }
        val generation = ++serviceGeneration
        serviceStartPending = true
        backgroundStatus(AndroidLanSyncBackgroundPhase.STARTING)
        try {
            ContextCompat.startForegroundService(app, Intent(app, LanSyncGroupForegroundService::class.java)
                .setAction(LanSyncGroupForegroundService.ACTION_START)
                .putExtra(LanSyncGroupForegroundService.EXTRA_GENERATION, generation))
        } catch (_: Exception) {
            serviceStartPending = false
            if (!pauseCoordinatorSafely()) return@withLock
            backgroundStatus(AndroidLanSyncBackgroundPhase.FAILED, "SYNC_GROUP_BACKGROUND_START_FAILED")
        }
    }
    /** User stop is sticky; ordinary Activity/network events never clear it. */
    fun pauseBackground() {
        userPaused = true
        if (cloudWorkOwner.isInitialized()) cloudWorkOwner.value.cancel()
        backgroundStatus(AndroidLanSyncBackgroundPhase.STOPPING)
        app.applicationScope.launch { backgroundAdmission.withLock { pauseBackgroundLocked() } }
    }
    private suspend fun pauseBackgroundLocked() {
        val saved = runCatching { backgroundPreferences.edit().putBoolean("user-paused", true).commit() }.getOrDefault(false)
        if (!pauseCoordinatorSafely()) return
        protectionLost = false
        val stop = stopPromotedService
        if (stop != null) {
            stopPromotedService = null
            backgroundStatus(if (saved) AndroidLanSyncBackgroundPhase.PAUSED else AndroidLanSyncBackgroundPhase.FAILED,
                if (saved) null else "SYNC_GROUP_BACKGROUND_PREFERENCE_NOT_SAVED")
            stop()
        } else if (!serviceStartPending) {
            backgroundStatus(if (saved) AndroidLanSyncBackgroundPhase.PAUSED else AndroidLanSyncBackgroundPhase.FAILED,
                if (saved) null else "SYNC_GROUP_BACKGROUND_PREFERENCE_NOT_SAVED")
        } else {
            // Keep the stop pending until an already requested service really promotes.
            backgroundStatus(AndroidLanSyncBackgroundPhase.STOPPING,
                if (saved) null else "SYNC_GROUP_BACKGROUND_PREFERENCE_NOT_SAVED")
        }
    }
    private suspend fun pauseCoordinatorSafely(): Boolean = try {
        if (cloudWorkOwner.isInitialized()) cloudWorkOwner.value.cancel()
        if (groupCoordinatorOwner.isInitialized()) groupCoordinatorOwner.value.pauseAndDrain()
        true
    } catch (_: Exception) {
        backgroundStatus(AndroidLanSyncBackgroundPhase.FAILED, "SYNC_GROUP_BACKGROUND_PAUSE_FAILED")
        false
    }
    fun resumeBackground() {
        app.applicationScope.launch {
            backgroundAdmission.withLock {
                if (hooksClosed) return@withLock
                if (protectionLost) {
                    if (!pauseCoordinatorSafely()) return@withLock
                    protectionLost = false
                }
                if (serviceStartPending || serviceAliveGeneration >= 0) return@withLock
                if (backgroundState.value.phase in setOf(AndroidLanSyncBackgroundPhase.STARTING,
                        AndroidLanSyncBackgroundPhase.RUNNING, AndroidLanSyncBackgroundPhase.STOPPING)) return@withLock
                if (!runCatching { backgroundPreferences.edit().putBoolean("user-paused", false).commit() }.getOrDefault(false)) {
                    backgroundStatus(AndroidLanSyncBackgroundPhase.FAILED, "SYNC_GROUP_BACKGROUND_PREFERENCE_NOT_SAVED")
                    return@withLock
                }
                userPaused = false
                backgroundStatus(AndroidLanSyncBackgroundPhase.WAITING_FOR_FOREGROUND)
            }
            startBackgroundIfAllowed(retryFailure = true)
        }
    }
    internal fun servicePromoted(generation: Long, stopAfterDrain: () -> Unit) {
        if (generation == serviceGeneration) { serviceStartPending = false; serviceAliveGeneration = generation }
        app.applicationScope.launch { backgroundAdmission.withLock {
            if (generation != serviceGeneration || hooksClosed) { stopAfterDrain(); return@withLock }
            stopPromotedService = stopAfterDrain
            if (userPaused) {
                pauseBackgroundLocked()
            } else if (!app.lanSyncStartupReady || !hasGroupEvidence()) {
                if (!pauseCoordinatorSafely()) return@withLock
                stopPromotedService = null
                backgroundStatus(if (hasGroupEvidence()) AndroidLanSyncBackgroundPhase.WAITING_FOR_FOREGROUND else AndroidLanSyncBackgroundPhase.NOT_CONFIGURED)
                stopAfterDrain()
            } else if (!protectionLost && serviceAliveGeneration == generation) {
                if (backgroundState.value.phase != AndroidLanSyncBackgroundPhase.RUNNING) groupCoordinator.resumeIfConfigured()
                backgroundStatus(AndroidLanSyncBackgroundPhase.RUNNING)
            }
        } }
    }
    internal fun stopFromNotification(generation: Long) {
        if (generation == serviceGeneration) pauseBackground()
    }
    internal fun servicePromotionFailed(generation: Long) {
        if (generation != serviceGeneration) return
        protectionLost = true
        serviceStartPending = false
        serviceAliveGeneration = -1L
        backgroundStatus(AndroidLanSyncBackgroundPhase.FAILED, "SYNC_GROUP_BACKGROUND_PROMOTION_FAILED")
        app.applicationScope.launch { backgroundAdmission.withLock {
            if (generation == serviceGeneration) {
                stopPromotedService = null
                if (pauseCoordinatorSafely()) protectionLost = false
            }
        } }
    }
    internal fun serviceDestroyed(generation: Long, expectedStop: Boolean) {
        if (generation != serviceGeneration) return
        serviceStartPending = false
        serviceAliveGeneration = -1L
        if (expectedStop || backgroundState.value.phase == AndroidLanSyncBackgroundPhase.PAUSED) {
            backgroundStatus(backgroundState.value.phase, backgroundState.value.error)
            return
        }
        if (backgroundState.value.error == "SYNC_GROUP_BACKGROUND_PROMOTION_FAILED") return
        protectionLost = true
        backgroundStatus(AndroidLanSyncBackgroundPhase.FAILED, "SYNC_GROUP_BACKGROUND_SERVICE_LOST")
        app.applicationScope.launch { backgroundAdmission.withLock {
            if (generation != serviceGeneration) return@withLock
            stopPromotedService = null
            if (pauseCoordinatorSafely()) protectionLost = false
        } }
    }
    internal fun cloudServicePromoted(generation: Long, stopAfterDrain: () -> Unit) {
        if (cloudWorkOwner.isInitialized()) cloudWorkOwner.value.promoted(generation, stopAfterDrain)
        else stopAfterDrain()
    }
    internal fun cloudServicePromotionFailed(generation: Long) {
        if (cloudWorkOwner.isInitialized()) cloudWorkOwner.value.promotionFailed(generation)
    }
    internal fun cloudServiceTimedOut(generation: Long) {
        if (cloudWorkOwner.isInitialized()) cloudWorkOwner.value.timedOut(generation)
    }
    internal fun cloudServiceDestroyed(generation: Long, expectedStop: Boolean) {
        if (cloudWorkOwner.isInitialized()) cloudWorkOwner.value.destroyed(generation, expectedStop)
    }
    internal fun stopCloudFromNotification(generation: Long) {
        if (!hooksClosed && cloudWorkOwner.isInitialized() && cloudWorkOwner.value.acceptsStop(generation)) pauseBackground()
    }
    fun stopLegacy() { if (runtimeOwner.isInitialized()) runtimeOwner.value.stop() }
    private fun groupProfileRoot(): Path {
        val profile = root.resolve("group-profile")
        if (Files.exists(profile, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(root.resolve("identity.protected"), LinkOption.NOFOLLOW_LINKS)) { "SYNC_GROUP_DAILY_IDENTITY_MISSING" }
        }
        store.identity()
        if (!Files.exists(profile, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(profile)
        return profile
    }
    private val groupEnrollmentOwner = lazy {
        LanSyncGroupEnrollment(groupProfileRoot(), workspace, platform, store.identity(), protection::protect,
            protection::unprotect, ::validateAndroidLanSyncResource)
    }
    private val groupJoiningOwner = lazy {
        LanSyncGroupJoining(groupProfileRoot(), workspace, platform, store.identity(), protection::protect,
            protection::unprotect, ::validateAndroidLanSyncResource)
    }
    val groupEnrollment get() = groupEnrollmentOwner.value
    val groupJoining get() = groupJoiningOwner.value
    fun lockGroupManagement() {
        if (groupCoordinatorOwner.isInitialized()) groupCoordinatorOwner.value.lockManagement()
        else if (groupEnrollmentOwner.isInitialized()) groupEnrollmentOwner.value.lockManagement()
    }
    suspend fun closeAndDrain() {
        val hooks = synchronized(hookLock) { hooksClosed = true; applicationHooks.also { applicationHooks = null } }
        hooks?.close()
        hookObserver?.cancelAndJoin()
        if (cloudWorkOwner.isInitialized()) cloudWorkOwner.value.closeAndDrain()
        backgroundAdmission.withLock {
            if (groupCoordinatorOwner.isInitialized()) groupCoordinatorOwner.value.closeAndDrain()
            if (groupEnrollmentOwner.isInitialized()) groupEnrollmentOwner.value.close()
            if (runtimeOwner.isInitialized()) runtimeOwner.value.closeAndDrain()
            stopPromotedService?.invoke()
            stopPromotedService = null
        }
    }
    fun recoverBeforeRepositories() = workspace.recover()

}
