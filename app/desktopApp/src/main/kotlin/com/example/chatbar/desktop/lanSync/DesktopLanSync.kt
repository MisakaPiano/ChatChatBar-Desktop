package com.example.chatbar.desktop

import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.desktop.security.WindowsDpapiSecretProtector
import com.example.chatbar.desktop.security.WindowsSecretRootResolver
import kotlinx.coroutines.runBlocking
import java.nio.file.*
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
        }, validateResource = ::validateDesktopLanSyncResource, resourceReference = { _, path -> desktopLanSyncResourceReference(app.appDataRoot, path) })
    private val platform = object : LanSyncPlatform {
        override suspend fun exclusive(work: suspend () -> Unit) = exclusive.invoke(work)
        override suspend fun reload(sessionIds: Set<String>) = reload.invoke(sessionIds)
    }
    val runtime by lazy { LanSyncRuntime(stateStore, workspace, platform, "CCB 电脑") }
    val groupEnrollment by lazy {
        val profile = localRoot.resolve("group-profile")
        if (Files.exists(profile, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(localRoot.resolve("identity.protected"), LinkOption.NOFOLLOW_LINKS)) { "SYNC_GROUP_DAILY_IDENTITY_MISSING" }
        }
        val daily = stateStore.identity()
        if (!Files.exists(profile, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(profile)
        LanSyncGroupEnrollment(profile, workspace, platform, daily, protector::protect,
            protector::unprotect, ::validateDesktopLanSyncResource)
    }
    fun recoverBeforeRepositories() = workspace.recover()
    suspend fun closeAndDrain() = runtime.closeAndDrain()
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
        val result = LanSyncPostProcess.record(record)
        if (result != null) {
            require(Files.size(path) <= DesktopPostProcessRaster.MAX_BYTES) { "SYNC_IMAGE_BUDGET" }
            DesktopPostProcessRaster.decode(Files.readAllBytes(path), com.example.chatbar.domain.image.NovelAiPostProcessTab.valueOf(result.operation), longestSide = 256).flush()
        } else {
            require(Files.size(path) <= DesktopImageEditing.MAX_BYTES) { "SYNC_IMAGE_BUDGET" }
            DesktopImageEditing.decode(Files.readAllBytes(path), longestSide = 256).flush()
        }
    }
}
