@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.PngTextChunks
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopPostProcessSourceIdentityTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test fun `actual Pane clears previous input and admission while replacement source read is held`(): Unit = runBlocking(edt) {
        val parent = Files.createTempDirectory("postprocess-source-")
        val secrets = InMemoryDesktopSecretStore()
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { secrets }, bundledAssets = { path ->
                if (path.startsWith("presets/")) """{"entries":[]}""".toByteArray() else DesktopBundledAssetReader()(path)
            })
        try {
            val controller = DesktopNovelAiStudioController(app.jsonFileStorage, app.characterResourceStore, UnconfiguredDesktopFilePicker,
                app.dataOperationCoordinator, {}, app.novelAiGenerationRuntime,
                DesktopNovelAiGuidance(app.appDataRoot, app.characterResourceStore, secrets), app.taskRuntime,
                app.novelAiInfrastructure, app.settingsRepository, app.effectiveModelResolver, app.characterRepository,
                account = { NovelAiAccountUsage(100, 3, true, 50.0, false) }, credentialConfigured = { true })
            assertTrue(controller.load())
            fun image(width: Int, height: Int): ByteArray {
                val raster = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                return try { PngTextChunks.insertTextChunk(DesktopImageEditing.png(raster), "Comment",
                    """{"model":"nai-diffusion-5-full","width":$width,"height":$height,"v4_prompt":{"caption":{"base_caption":"synthetic source","char_captions":[]}},"steps":28,"scale":6,"sampler":"k_euler_ancestral"}""")
                } finally { raster.flush() }
            }
            val firstBytes = image(64, 96); val secondBytes = image(128, 64)
            val first = Files.write(parent.resolve("first.png"), firstBytes)
            val second = Files.write(parent.resolve("second.png"), secondBytes)
            var selected by mutableStateOf(first)
            val clock = TestCoroutineScheduler()
            val inputDispatcher = StandardTestDispatcher(clock)
            var composedRecords: List<DesktopPostProcessResultRecord> = emptyList()
            val scene = ImageComposeScene(650, 1100, coroutineContext = edt) {
                val currentRecords = controller.postProcessResults.records.collectAsState().value
                SideEffect { composedRecords = currentRecords }
                DesktopImagePostProcessPanel(selected, controller, inputDispatcher) { error("Use must remain explicit") }
            }
            fun nodes(): List<SemanticsNode> {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
            }
            fun submit() = nodes().last { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(NovelAiPostProcessTab.UPSCALE.label) == true }
            fun dimensions() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                .filter { it.startsWith("原图 ") }
            suspend fun frames() { repeat(5) { scene.render(System.nanoTime()).close(); delay(20) } }
            try {
                frames()
                assertTrue(dimensions().isEmpty()); assertTrue(submit().config.contains(SemanticsProperties.Disabled))
                clock.runCurrent(); frames()
                assertTrue(dimensions().single().startsWith("原图 64 × 96"))
                assertFalse(submit().config.contains(SemanticsProperties.Disabled))
                val staleSubmit = submit().config[SemanticsActions.OnClick].action!!
                selected = second; frames() // The new physical read remains queued, rather than racing a tiny PNG.
                assertTrue(dimensions().isEmpty(), "Old input must disappear before the new source read completes")
                assertTrue(submit().config.contains(SemanticsProperties.Disabled), "Old cost/input must not admit processing for the new source")
                assertTrue(staleSubmit.invoke()); frames()
                assertTrue(app.taskRuntime.tasks.value.isEmpty(), "Disposed source actions cannot admit a task")
                clock.runCurrent(); frames()
                assertTrue(dimensions().single().startsWith("原图 128 × 64"))
                assertFalse(submit().config.contains(SemanticsProperties.Disabled))
                selected = parent.resolve("missing.png"); frames()
                assertTrue(dimensions().isEmpty()); assertTrue(submit().config.contains(SemanticsProperties.Disabled))
                clock.runCurrent(); frames()
                assertTrue(dimensions().isEmpty()); assertTrue(submit().config.contains(SemanticsProperties.Disabled))
                assertTrue(app.taskRuntime.tasks.value.isEmpty())
                assertEquals(0, controller.postProcessResults.records.value.size)
                assertContentEquals(firstBytes, Files.readAllBytes(first)); assertContentEquals(secondBytes, Files.readAllBytes(second))

                // A LAN choice can replace metadata under the same immutable ID. Hold the actual
                // storage admission after publishing the new flow, so its new image read cannot win a race.
                selected = first; frames(); clock.runCurrent(); frames()
                val oldOutput = DesktopImageEditing.png(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB))
                val original = controller.postProcessResults.persist(firstBytes, oldOutput, NovelAiPostProcessTab.UPSCALE, NovelAiEnhanceOptions())
                fun texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                suspend fun awaitResult(label: String) = withTimeout(10_000) {
                    while (texts().none { it == label }) frames()
                }
                awaitResult("已保存结果 16 × 16")
                val newOutput = DesktopImageEditing.png(BufferedImage(24, 16, BufferedImage.TYPE_INT_ARGB))
                val relative = "images/lan-sync-replaced.png"
                Files.write(app.appDataRoot.resolve(relative), newOutput)
                val replacement = original.copy(imagePath = relative, imageSha256 = DesktopPostProcessResults.digest(newOutput), strength = .7f)
                val published = CompletableDeferred<Unit>(); val releaseRead = CompletableDeferred<Unit>()
                val incoming = launch(Dispatchers.IO) {
                    app.dataOperationCoordinator.withExclusiveMaintenance {
                        app.jsonFileStorage.saveEntity(DesktopPostProcessResults.KEY, replacement.id, replacement, DesktopPostProcessResultRecord.serializer())
                        check(controller.reloadPostProcessResults())
                        published.complete(Unit); releaseRead.await()
                    }
                }
                try {
                    withTimeout(10_000) {
                        published.await()
                        do {
                            scene.render(System.nanoTime()).close()
                            if (composedRecords != listOf(replacement)) delay(20)
                        } while (composedRecords != listOf(replacement))
                    }
                    assertTrue(texts().none { it.startsWith("已保存结果 ") }, "Previous bytes must disappear while the same-ID replacement read waits")
                    assertTrue(texts().none { it in setOf("用作当前导入图片", "保存 PNG", "复制图片") }, "Old result actions must not acquire the replacement record")
                } finally { releaseRead.complete(Unit); incoming.join() }
                awaitResult("已保存结果 24 × 16")
                assertContentEquals(newOutput, controller.postProcessResults.read(replacement))
                assertTrue(app.taskRuntime.tasks.value.isEmpty())
            } finally { scene.close(); clock.runCurrent() }
        } finally { app.close(); check(parent.toFile().deleteRecursively()) }
    }
}
