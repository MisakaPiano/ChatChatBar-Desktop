package com.example.chatbar.desktop

import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.desktop.security.WindowsDpapiSecretProtector
import com.example.chatbar.desktop.security.WindowsSecretRootResolver
import kotlinx.coroutines.runBlocking
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal class DesktopLanSync(private val app: DesktopAppContainer,
    private val exclusive: suspend (suspend () -> Unit) -> Unit,
    private val reload: suspend (Set<String>) -> Unit,
    private val resolve: (String) -> Path,
    private val asset: (String) -> ByteArray,
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
    private val platform = object : LanSyncPlatform {
        override suspend fun exclusive(work: suspend () -> Unit) = exclusive.invoke(work)
        override suspend fun reload(sessionIds: Set<String>) = reload.invoke(sessionIds)
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
    private val groupCoordinatorOwner = lazy {
        LanSyncGroupCoordinator(hasExistingEvidence = ::hasGroupEvidence,
            enrollment = { groupEnrollment }, joining = { groupJoining }, deviceName = "CCB 电脑",
            beforeSetup = { if (runtimeOwner.isInitialized()) runtimeOwner.value.closeAndDrain() })
    }
    val groupCoordinator get() = groupCoordinatorOwner.value
    fun startGroupIfConfigured() { if (hasGroupEvidence()) groupCoordinator.startIfConfigured() }
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
        if (groupCoordinatorOwner.isInitialized()) groupCoordinatorOwner.value.closeAndDrain()
        if (groupEnrollmentOwner.isInitialized()) groupEnrollmentOwner.value.close()
        if (runtimeOwner.isInitialized()) runtimeOwner.value.closeAndDrain()
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
