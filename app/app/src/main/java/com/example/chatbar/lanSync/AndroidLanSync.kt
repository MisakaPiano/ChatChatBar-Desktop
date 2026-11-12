package com.example.chatbar.lanSync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.chatbar.ChatBarApp
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import java.nio.file.*
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
        resolveResource = { reference ->
            val path = java.nio.file.Paths.get(reference)
            val alias = app.filesDir.toPath()
            when {
                path.isAbsolute && path.startsWith(alias) -> dataRoot.resolve(alias.relativize(path))
                path.isAbsolute -> path
                else -> dataRoot.resolve(reference)
            }
        }, assetResource = { reference ->
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
        }, validateResource = { kind, path ->
            if (kind == "images") {
                require(Files.size(path) <= 32L * 1024 * 1024) { "SYNC_IMAGE_BUDGET" }
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 32_000_000) { "SYNC_IMAGE_INVALID" }
            }
        })
    val runtime by lazy { LanSyncRuntime(store, workspace, object : LanSyncPlatform {
        override suspend fun exclusive(work: suspend () -> Unit) {
            require(app.lanSyncStartupReady) { "SYNC_INITIALIZING" }
            app.momentScheduler.withLanSyncMaintenance {
                AiBackgroundWorkManager.withLanSyncMaintenance { gate.exclusive { work() } }
            }
        }
        override suspend fun reload(sessionIds: Set<String>) {
            app.characterRepository.refreshFromStorage(); app.formatCardRepository.refreshFromStorage(); app.worldBookRepository.refreshFromStorage()
            app.chatRepository.reloadAfterLanSync(); app.voiceMessageRepository.reload()
            app.momentRepository.refreshFromStorage()
        }
    }, android.os.Build.MODEL.take(80)) }
    fun recoverBeforeRepositories() = workspace.recover()

}
