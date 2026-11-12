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
        })
    val runtime by lazy { LanSyncRuntime(stateStore, workspace, object : LanSyncPlatform {
        override suspend fun exclusive(work: suspend () -> Unit) = exclusive.invoke(work)
        override suspend fun reload(sessionIds: Set<String>) = reload.invoke(sessionIds)
    }, "CCB 电脑") }
    fun recoverBeforeRepositories() = workspace.recover()
    suspend fun closeAndDrain() = runtime.closeAndDrain()
}

internal object DesktopLanSyncStartup {
    fun localRoot(root: Path): Path = WindowsSecretRootResolver.resolve(root).resolve("lan-sync-v1")
        .resolve(lanSyncHash(root.toString().toByteArray(Charsets.UTF_8)))
    fun recover(root: Path) {
        val workspace = localRoot(root).resolve("workspace")
        if (!Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) return
        LanSyncWorkspace(root, workspace, resolveResource = { error("SYNC_STARTUP_RESOURCE_ACCESS") }).recover()
    }
}
