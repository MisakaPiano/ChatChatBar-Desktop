@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.desktop.backup.*
import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Random
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*

class DesktopLargePostProcessResultTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private suspend fun ImageComposeScene.frames() { repeat(4) { render(System.nanoTime()).close(); delay(20) } }
    private fun SemanticsNode.has(label: String): Boolean =
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true || children.any { it.has(label) }
    private fun ImageComposeScene.click(label: String) {
        val node = nodes().last { it.has(label) && it.config.contains(SemanticsActions.OnClick) }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
    }

    @Test fun `valid noisy large response survives actual request reopen preview explicit use save and whole backup without widening inputs`() = runBlocking(edt) {
        val parent = Files.createTempDirectory("large-post-process-")
        val resolved = DesktopDataRootResolution.Resolved(Files.createDirectory(parent.resolve("data")), DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json"))
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolved)).ownership
        val focus = DesktopAudioFocus()
        val secrets = InMemoryDesktopSecretStore().apply { save(DesktopCredentialKey.NovelAiToken, "synthetic-large") }
        val app = DesktopAppContainer(resolved, secretStoreFactory = { secrets }, bundledAssets = { path ->
                if (path.startsWith("presets/")) """{"entries":[]}""".toByteArray() else DesktopBundledAssetReader()(path)
            })
        val server = MockWebServer()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        try {
            val input = withContext(Dispatchers.IO) { DesktopImageEditing.png(BufferedImage(1500, 1500, BufferedImage.TYPE_INT_ARGB)) }
            val output = withContext(Dispatchers.IO) {
                val raster = BufferedImage(3000, 3000, BufferedImage.TYPE_INT_ARGB)
                try {
                    val pixels = (raster.raster.dataBuffer as DataBufferInt).data
                    val random = Random(7234)
                    for (i in pixels.indices) pixels[i] = random.nextInt()
                    DesktopImageEditing.png(raster)
                } finally { raster.flush() }
            }
            assertTrue(output.size > DesktopImageEditing.MAX_BYTES && output.size < 100 * 1024 * 1024)
            val source = Files.write(parent.resolve("source.png"), input)
            val saved = parent.resolve("explicit-save.png")
            val picker = object : DesktopFilePicker {
                override fun pickOpenFile(type: DesktopFileType) = source
                override fun pickSaveFile(type: DesktopFileType, suggestedName: String) = saved
            }
            fun controller() = DesktopNovelAiStudioController(app.jsonFileStorage, app.characterResourceStore, picker,
                app.dataOperationCoordinator, {}, app.novelAiGenerationRuntime,
                DesktopNovelAiGuidance(app.appDataRoot, app.characterResourceStore, secrets), app.taskRuntime,
                app.novelAiInfrastructure, app.settingsRepository, app.effectiveModelResolver, app.characterRepository,
                { NovelAiAccountUsage(100, 3, true, 50.0, false) }, { true }, DesktopNovelAiPostProcessor(secrets, client))
            server.enqueue(MockResponse().setBody("""{"images":[{"image":"${Base64.getEncoder().encodeToString(output)}"}]}"""))
            val original = controller(); assertTrue(original.load())
            val draft = original.repository.loadDraft()
            assertTrue(original.postProcess(input, NovelAiPostProcessTab.UPSCALE, null, NovelAiEnhanceOptions(), {}, {}))
            withTimeout(60_000) { while (app.taskRuntime.tasks.value.any { it.status == DesktopTaskStatus.RUNNING }) delay(20) }
            assertEquals(DesktopTaskStatus.COMPLETED, app.taskRuntime.tasks.value.single().status)
            val reopened = controller(); assertTrue(reopened.load())
            val record = reopened.postProcessResults.records.value.single()
            assertContentEquals(output, reopened.postProcessResults.read(record))
            assertFails { app.characterResourceStore.readScreenshotImageBytes(record.imagePath) }
            assertFails { DesktopImageEditing.decode(output) }
            assertFails { DesktopPostProcessRaster.decode(output, NovelAiPostProcessTab.ENHANCE) }
            assertFails { DesktopNovelAiPostProcessor(secrets, client).process(output, NovelAiPostProcessTab.UPSCALE,
                null, NovelAiEnhanceOptions(), null, {}) }
            assertEquals(1, server.requestCount)

            val scene = ImageComposeScene(1450, 1300, coroutineContext = edt) { DesktopNovelAiStudioPanel(reopened) }
            try {
                scene.frames(); scene.click("导入图片"); scene.frames(); scene.click("▸ Enhance / Upscale")
                val resultIdentity = desktopDecodedImageIdentity("after-${output.contentHashCode()}")
                withTimeout(20_000) { while (scene.nodes().none { it.config.getOrNull(DesktopDecodedImageIdentity) == resultIdentity }) scene.frames() }
                scene.click("保存 PNG")
                withTimeout(10_000) { while (!Files.exists(saved)) scene.frames() }
                assertContentEquals(output, Files.readAllBytes(saved))
                scene.click("用作当前导入图片")
                var imported: Path? = null
                withTimeout(20_000) {
                    while (imported == null) {
                        scene.frames()
                        val name = scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                            .map { it.text }.firstOrNull { it.startsWith("ccb-postprocess-") && it.endsWith(".png") }
                        if (name != null) imported = Path.of(System.getProperty("java.io.tmpdir")).resolve(name)
                    }
                    val identity = desktopDecodedImageIdentity(requireNotNull(imported).toString())
                    while (scene.nodes().none { it.config.getOrNull(DesktopDecodedImageIdentity) == identity }) scene.frames()
                }
                assertContentEquals(output, Files.readAllBytes(requireNotNull(imported)))
                System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { folder ->
                    val file = Path.of(folder).resolve("large-post-process-used-current-image.png")
                    Files.createDirectories(file.parent)
                    scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
                }
            } finally { scene.close() }
            assertContentEquals(input, Files.readAllBytes(source)); assertEquals(draft, reopened.repository.loadDraft())

            val file = parent.resolve("large.cbbackup")
            val service = DesktopFullBackupService(app.appDataRoot, app.dataOperationCoordinator, DesktopAudioMaintenance({ focus }) {})
            service.exportModern(file, null)
            DesktopFullBackupStaging.prepare(file, null, Files.createDirectory(parent.resolve("inspect"))).use { staged ->
                val target = Files.createDirectory(parent.resolve("restored"))
                DesktopFullBackupMapping(target).restoreMerged(staged.files, Files.createDirectory(parent.resolve("map")), BackupPreferences()).use { mapped ->
                    for ((relative, from) in mapped.files) {
                        val to = target.resolve(relative); Files.createDirectories(to.parent); Files.copy(from, to)
                    }
                    DesktopFullBackupGraphValidation.validate(target)
                    val store = DesktopPostProcessResults(JsonFileStorage(target), DesktopCharacterResourceStore(target), app.dataOperationCoordinator)
                    store.load(); assertContentEquals(output, store.read(store.records.value.single()))
                }
            }
            val liveImage = app.appDataRoot.resolve(record.imagePath)
            val unownedLarge = app.appDataRoot.resolve("images/unregistered-large.png")
            Files.copy(liveImage, unownedLarge)
            assertFails { DesktopFullBackupGraphValidation.validate(app.appDataRoot) }
            Files.delete(unownedLarge)
            assertContentEquals(output, reopened.postProcessResults.read(record))
            assertEquals(1, server.requestCount)
        } finally {
            app.close(); focus.closeAndDrain(); owner.close()
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            withContext(Dispatchers.IO) { check(client.dispatcher.executorService.awaitTermination(10, TimeUnit.SECONDS)); server.close() }
            check(parent.toFile().deleteRecursively())
        }
    }
}
