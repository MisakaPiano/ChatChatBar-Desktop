package com.example.chatbar.lanSync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.domain.moment.MomentScheduler
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Two synthetic private Android roots over authenticated loopback, not Windows or real Wi-Fi proof. */
class LanSyncMomentTransferTest {
    private class Network : LanSyncNetwork {
        private val ports = java.util.concurrent.ConcurrentHashMap<String, Int>()
        override fun addresses() = listOf("192.168.1.2")
        override fun accepts(address: InetAddress) = address.isLoopbackAddress
        override val discoveryEnabled = false
        override fun listening(fingerprint: String, port: Int) { ports[fingerprint] = port }
        override fun discover(fingerprint: String) = ports[fingerprint]?.let { "192.168.1.2" to it }
        override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket {
            val plain = Socket()
            try {
                plain.connect(InetSocketAddress("127.0.0.1", peer.port), 3000)
                return (identity.context { it == peer.fingerprint }.socketFactory
                    .createSocket(plain, "127.0.0.1", peer.port, true) as SSLSocket).also {
                    LanSyncTls.configure(it); it.startHandshake()
                }
            } catch (error: Throwable) { plain.close(); throw error }
        }
    }
    private class Endpoint(val app: ChatBarApp, val network: Network, val name: String,
        val root: Path = Files.createTempDirectory(app.cacheDir.canonicalFile.toPath(), "moment-wire-data-").toRealPath(),
        val privateRoot: Path = Files.createTempDirectory(app.cacheDir.canonicalFile.toPath(), "moment-wire-private-").toRealPath(),
        val keyAlias: String = "synthetic-moment-wire-${UUID.randomUUID()}") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gate = LanSyncOperationGate()
        val storage = JsonFileStorage(root, gate)
        val settings = SettingsRepository(storage)
        val repository = MomentRepository(storage)
        val scheduler = MomentScheduler(app, scope, settings, CharacterRepository(storage), ChatRepository(storage),
            repository, app.effectiveModelResolver, app.momentGenerationService, gate)
        private val keys = AndroidLanSyncKeyProtection(keyAlias)
        val store = LanSyncStateStore(privateRoot, keys::protect, keys::unprotect)
        val id = store.identity().fingerprint
        private val workspace = LanSyncWorkspace(root, privateRoot.resolve("workspace"), { Paths.get(it) },
            validateResource = { kind, file, _ -> if (kind == "images") {
                val bitmap = requireNotNull(BitmapFactory.decodeFile(file.toString()))
                require(bitmap.width == 8 && bitmap.height == 8); bitmap.recycle()
            } })
        val runtime = LanSyncRuntime(store, workspace, object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) = scheduler.withLanSyncMaintenance {
                AiBackgroundWorkManager.withLanSyncMaintenance { gate.exclusive(work) }
            }
            override suspend fun reload(sessionIds: Set<String>) {
                repository.refreshFromStorage(); settings.refreshPlayerSettingFromStorage()
                storage.loadAll("novelai_generation_history", NovelAiGenerationHistoryEntry.serializer())
            }
        }, name, scope = scope, network = network)
        suspend fun close() { runtime.closeAndDrain(); scope.cancel(); scope.coroutineContext[Job]!!.join() }
        fun reopen() = Endpoint(app, network, name, root, privateRoot, keyAlias)
    }

    private suspend fun await(check: suspend () -> Boolean) {
        withTimeout(30000) { while (!check()) delay(50) }
    }
    private suspend fun completed(a: Endpoint, b: Endpoint, previous: String? = null) = await {
        val left = a.store.baseline(b.id, a.id); val right = b.store.baseline(a.id, b.id)
        left.revision != null && left.revision != previous && left.revision == right.revision &&
            a.store.pending(b.id) == null && b.store.pending(a.id) == null
    }
    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            java.io.ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
        } finally { bitmap.recycle() }
    }

    @Test fun actualAndroidStudioHistoryVibeAndPlayerSingletonTransferReverseAndReopenWithoutProviders() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        withTimeout(60000) { while (!app.lanSyncStartupReady) { check(!app.lanSyncRecoveryFailed); delay(100) } }
        val network = Network()
        var a = Endpoint(app, network, "synthetic-studio-host"); var b = Endpoint(app, network, "synthetic-studio-phone")
        var left = NovelAiStudioStateRepository(a.storage); var right = NovelAiStudioStateRepository(b.storage)
        try {
            a.settings.initialize() // The destination has no player singleton yet: first participation is an addition.
            a.settings.updateAppSettings { it.copy(siliconFlowApiKey = "synthetic-local-only-not-a-key") }
            val settingsFile = a.root.resolve("entities/app_settings.json"); val settingsBytes = Files.readAllBytes(settingsFile)
            val pixels = png(Color.GREEN)
            val image = a.root.resolve("images/history.png"); Files.createDirectories(image.parent); Files.write(image, pixels)
            a.settings.savePlayerSetting(PlayerSetting(playerName = "合成全局玩家", globalPersona = "全局设定原文", desktopAvatar = image.toString()))
            val payload = "E".repeat(NovelAiInlinePayloadStore.INLINE_LIMIT + 123)
            val encoded = NovelAiInlinePayloadStore(a.root.toFile()).retain(payload)
            val recipe = NovelAiGenerationRecipe(basePrompt = "历史配方原文\n第二行", negativePrompt = "合成负面",
                characters = listOf(NovelAiCharacterPromptDraft("scoped-role", "合成人物")),
                imageGuidance = NovelAiImageGuidanceDraft(baseImage = NovelAiStudioAssetRef("scoped-asset", image.toString(), lanSyncHash(pixels), 8, 8),
                    vibes = listOf(NovelAiVibeReferenceDraft("scoped-vibe", encodedVibe = encoded))))
            val entry = NovelAiGenerationHistoryEntry("synthetic-history", listOf(NovelAiGenerationHistoryImage(image.toString(), 42)), recipe, createdAt = 1)
            left.saveHistory(entry)
            a.runtime.startHost(); await { a.runtime.state.value.addresses.isNotEmpty() }
            a.runtime.pairOffer("192.168.1.2"); b.runtime.pair(requireNotNull(a.runtime.state.value.offer).code())
            await { a.store.peers().size == 1 && b.store.peers().size == 1 }
            b.runtime.startPhone(); b.runtime.requestSync(); completed(a, b)
            val imported = right.history.first().single()
            assertEquals(recipe.basePrompt, imported.recipe.basePrompt)
            assertEquals(recipe.characters, imported.recipe.characters)
            assertEquals(imported.images.single().path, imported.recipe.imageGuidance.baseImage?.path)
            assertEquals(payload, NovelAiInlinePayloadStore(b.root.toFile()).resolve(requireNotNull(imported.recipe.imageGuidance.vibes.single().encodedVibe)))
            assertArrayEquals(pixels, Files.readAllBytes(Paths.get(imported.images.single().path)))
            val bitmap = requireNotNull(BitmapFactory.decodeFile(imported.images.single().path))
            try { assertEquals(Color.GREEN, bitmap.getPixel(0, 0)) } finally { bitmap.recycle() }
            val profile = b.settings.playerSetting.first()
            assertEquals("合成全局玩家", profile.playerName); assertEquals("全局设定原文", profile.globalPersona)
            assertEquals(imported.images.single().path, profile.desktopAvatar)
            assertTrue(Files.isRegularFile(b.root.resolve("entities/player_setting.json")))
            assertFalse(Files.exists(b.root.resolve("entities/player_setting")))
            assertArrayEquals(settingsBytes, Files.readAllBytes(settingsFile))
            var revision = a.store.baseline(b.id, a.id).revision
            right.saveHistory(imported.copy(recipe = imported.recipe.copy(basePrompt = "手机反向配方")))
            b.settings.updatePlayerSetting { it.copy(playerName = "手机反向玩家") }
            b.runtime.requestSync(); completed(a, b, revision)
            assertEquals("手机反向配方", left.history.first().single().recipe.basePrompt)
            assertEquals("手机反向玩家", a.settings.playerSetting.first().playerName)
            assertArrayEquals(settingsBytes, Files.readAllBytes(settingsFile))
            val aId = a.id; val bId = b.id
            a.close(); b.close(); a = a.reopen(); b = b.reopen()
            left = NovelAiStudioStateRepository(a.storage); right = NovelAiStudioStateRepository(b.storage)
            a.settings.initialize(); b.settings.initialize(); left.initialize(); right.initialize()
            assertEquals(aId, a.id); assertEquals(bId, b.id)
            assertEquals("手机反向玩家", b.settings.playerSetting.first().playerName)
            assertEquals(payload, NovelAiInlinePayloadStore(b.root.toFile()).resolve(requireNotNull(right.history.first().single().recipe.imageGuidance.vibes.single().encodedVibe)))
            a.runtime.startHost(); await { a.runtime.state.value.addresses.isNotEmpty() }; b.runtime.startPhone()
            revision = a.store.baseline(b.id, a.id).revision
            b.runtime.requestSync(); completed(a, b, revision)
            assertEquals(1, left.history.first().size)
            assertEquals(1, right.history.first().size)
            assertArrayEquals(settingsBytes, Files.readAllBytes(settingsFile))
        } finally { a.close(); b.close() }
    }

    @Test fun actualAndroidKeystoreRuntimeTransfersMomentVersionsReverseEditsAndReopens() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        withTimeout(60000) { while (!app.lanSyncStartupReady) { check(!app.lanSyncRecoveryFailed); delay(100) } }
        val network = Network()
        var a = Endpoint(app, network, "synthetic-host"); var b = Endpoint(app, network, "synthetic-phone")
        try {
            a.settings.initialize(); b.settings.initialize()
            val bytes = listOf(png(Color.RED), png(Color.BLUE))
            val versions = bytes.mapIndexed { index, payload ->
                val path = a.root.resolve("images/version-$index.png")
                Files.createDirectories(path.parent); Files.write(path, payload)
                MomentImageVersion("version-$index", path.toString(), "synthetic-$index",
                    GeneratedImageMetadata(path.toString(), "synthetic-$index", negativePrompt = "synthetic-negative",
                        sizePreset = "synthetic", width = 8, height = 8), "synthetic-brief-$index", index.toLong())
            }
            val post = MomentPost("synthetic-post", "missing-card", "missing-session", senderName = "合成历史作者",
                text = "合成原始正文", imagePath = versions.last().imagePath, imagePrompt = versions.last().imagePrompt,
                generatedImageMetadata = versions.last().generatedImageMetadata, imageBrief = versions.last().imageBrief,
                scheduledAt = 1, generatedAt = 1, desktopImageVersions = versions)
            val pending = MomentTask("synthetic-pending", post.characterCardId, post.sessionId, 1, createdAt = 1)
            val running = pending.copy(id = "synthetic-running", status = MomentTaskStatus.RUNNING, postId = post.id)
            a.repository.savePost(post); a.repository.saveTask(pending); a.repository.saveTask(running)
            a.runtime.startHost(); await { a.runtime.state.value.addresses.isNotEmpty() }
            a.runtime.pairOffer("192.168.1.2"); b.runtime.pair(requireNotNull(a.runtime.state.value.offer).code())
            await { a.store.peers().size == 1 && b.store.peers().size == 1 }
            b.runtime.startPhone(); b.runtime.requestSync(); completed(a, b)
            val imported = requireNotNull(b.repository.getPost(post.id))
            assertEquals(2, imported.desktopImageVersions.size)
            assertEquals(imported.desktopImageVersions.last().imagePath, imported.imagePath)
            imported.desktopImageVersions.forEachIndexed { index, version ->
                assertEquals("version-$index", version.id)
                assertEquals(version.imagePath, version.generatedImageMetadata?.imagePath)
                assertArrayEquals(bytes[index], Files.readAllBytes(Paths.get(version.imagePath)))
                val bitmap = requireNotNull(BitmapFactory.decodeFile(version.imagePath))
                try { assertEquals(if (index == 0) Color.RED else Color.BLUE, bitmap.getPixel(0, 0)) }
                finally { bitmap.recycle() }
            }
            assertEquals(imported, b.repository.getAllPosts().single()) // actual post-commit repository reload
            assertTrue(b.repository.getAllTasks().all { it.requiresLocalScheduleApproval })
            b.settings.updateAppSettings { it.copy(momentsEnabled = true) }
            b.scheduler.runOnce("synthetic-imported-dormancy", 2)
            assertEquals(MomentTaskStatus.PENDING, b.repository.getAllTasks().single { it.id == pending.id }.status)
            assertEquals(MomentTaskStatus.RUNNING, b.repository.getAllTasks().single { it.id == running.id }.status)
            assertFalse(a.settings.getAppSettings().momentsEnabled) // device-local setting never crossed the wire
            var revision = a.store.baseline(b.id, a.id).revision
            b.repository.savePost(imported.copy(text = "手机反向编辑", userLiked = true))
            b.runtime.requestSync(); completed(a, b, revision)
            assertEquals("手机反向编辑", a.repository.getPost(post.id)?.text)
            assertTrue(requireNotNull(a.repository.getPost(post.id)).userLiked)
            val firstId = a.id; val secondId = b.id
            a.close(); b.close(); a = a.reopen(); b = b.reopen()
            assertEquals(firstId, a.id); assertEquals(secondId, b.id) // actual Keystore unwrap of retained identities
            a.settings.initialize(); b.settings.initialize(); b.repository.initialize()
            a.runtime.startHost(); await { a.runtime.state.value.addresses.isNotEmpty() }; b.runtime.startPhone()
            val reopened = b.repository.getAllPosts().single()
            assertEquals(2, reopened.desktopImageVersions.size)
            assertEquals("手机反向编辑", reopened.text)
            b.repository.savePost(reopened.copy(userLiked = false))
            revision = a.store.baseline(b.id, a.id).revision
            b.runtime.requestSync(); completed(a, b, revision)
            assertFalse(requireNotNull(a.repository.getPost(post.id)).userLiked)
            assertEquals(2, requireNotNull(a.repository.getPost(post.id)).desktopImageVersions.size)
            assertEquals(2, b.repository.getAllTasks().size)
            revision = a.store.baseline(b.id, a.id).revision; a.runtime.requestSync(); completed(a, b, revision)
            assertEquals(2, a.repository.getAllTasks().size)
        } finally { a.close(); b.close() }
    }
}
