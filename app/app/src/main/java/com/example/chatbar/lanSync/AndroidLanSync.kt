package com.example.chatbar.lanSync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.chatbar.ChatBarApp
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
    private var hooksClosed = false
    private fun ensureApplicationHooks(): Unit = synchronized(hookLock) {
        if (!hooksClosed && applicationHooks == null && hasGroupEvidence())
            applicationHooks = AndroidLanSyncLifecycle(app, ::onForeground, ::lockGroupManagement, ::onNetworkAvailable)
    }
    fun onForeground() {
        val current = synchronized(hookLock) {
            if (!hooksClosed && app.lanSyncStartupReady && hasGroupEvidence() && groupCoordinatorOwner.isInitialized()) groupCoordinatorOwner.value else null
        }
        current?.onForeground()
    }
    fun onNetworkAvailable() {
        val current = synchronized(hookLock) {
            if (!hooksClosed && app.lanSyncStartupReady && hasGroupEvidence() && groupCoordinatorOwner.isInitialized()) groupCoordinatorOwner.value else null
        }
        current?.onNetworkAvailable()
    }
    private val groupCoordinatorOwner: Lazy<LanSyncGroupCoordinator> = lazy {
        LanSyncGroupCoordinator(hasExistingEvidence = ::hasGroupEvidence,
            enrollment = { groupEnrollment }, joining = { groupJoining }, deviceName = android.os.Build.MODEL.take(80),
            beforeSetup = { if (runtimeOwner.isInitialized()) runtimeOwner.value.closeAndDrain() },
            sourceChanges = app.jsonFileStorage.committedRevisionFor(LanSyncScope.schemas.keys)).also { coordinator ->
                hookObserver = app.applicationScope.launch {
                    coordinator.state.collect { if (hasGroupEvidence()) ensureApplicationHooks() }
                }
            }
    }
    val groupCoordinator: LanSyncGroupCoordinator get() = groupCoordinatorOwner.value
    fun startGroupIfConfigured() {
        if (hasGroupEvidence()) { ensureApplicationHooks(); groupCoordinator.startIfConfigured() }
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
        if (groupCoordinatorOwner.isInitialized()) groupCoordinatorOwner.value.closeAndDrain()
        if (groupEnrollmentOwner.isInitialized()) groupEnrollmentOwner.value.close()
        if (runtimeOwner.isInitialized()) runtimeOwner.value.closeAndDrain()
    }
    fun recoverBeforeRepositories() = workspace.recover()

}
