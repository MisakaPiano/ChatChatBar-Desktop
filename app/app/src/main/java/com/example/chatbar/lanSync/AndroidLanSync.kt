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
    private val store = LanSyncStateStore(root, ::protect, ::unprotect)
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
            Files.createDirectories(target.parent)
            if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) Files.copy(source, target)
            require(LanSyncWorkspace.hashFile(target, Files.size(source)) == hash)
            target.toString()
        })
    val runtime by lazy { LanSyncRuntime(store, workspace, object : LanSyncPlatform {
        override suspend fun exclusive(work: suspend () -> Unit) {
            require(app.lanSyncStartupReady) { "SYNC_INITIALIZING" }
            AiBackgroundWorkManager.withLanSyncMaintenance { gate.exclusive { work() } }
        }
        override suspend fun reload(sessionIds: Set<String>) {
            app.characterRepository.refreshFromStorage(); app.formatCardRepository.refreshFromStorage(); app.worldBookRepository.refreshFromStorage()
            app.chatRepository.reloadAfterLanSync(); app.voiceMessageRepository.reload()
        }
    }, android.os.Build.MODEL.take(80)) }
    fun recoverBeforeRepositories() = workspace.recover()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    private fun protect(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(bytes)
    }
    private fun unprotect(bytes: ByteArray): ByteArray {
        require(bytes.size > 28)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }.doFinal(bytes.copyOfRange(12, bytes.size))
    }
    companion object { private const val ALIAS = "chatbar-lan-sync-identity-v1" }
}
