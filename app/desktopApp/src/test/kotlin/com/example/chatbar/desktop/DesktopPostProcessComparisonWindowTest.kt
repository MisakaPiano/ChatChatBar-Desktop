@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.awt.ComposeWindow
import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.card.PngTextChunks
import com.example.chatbar.domain.image.*
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopPostProcessComparisonWindowTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun descendants(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::descendants)
    private fun ImageComposeScene.nodes() = semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
    private fun ComposeWindow.nodes() = semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
    private fun List<SemanticsNode>.comparison() = single { it.config.contains(SemanticsActions.SetProgress) }
    private fun List<SemanticsNode>.action(label: String) = firstOrNull { it.config.contains(SemanticsActions.OnClick) &&
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
    private fun List<SemanticsNode>.hasZoom(value: String) = any { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(value) } == true }

    @Test fun `actual resizable comparison window retains one view across expand return resize and discards previous record`() = runBlocking(edt) {
        requireDesktopGraphicsForNativeTest()
        val raster = BufferedImage(160, 120, BufferedImage.TYPE_INT_ARGB)
        raster.createGraphics().let { graphics -> try {
            graphics.color = java.awt.Color.BLUE; graphics.fillRect(0, 0, 160, 120)
            graphics.color = java.awt.Color.GREEN; graphics.fillRect(0, 48, 160, 24)
        } finally { graphics.dispose() } }
        val bytes = try { DesktopImageEditing.png(raster) } finally { raster.flush() }
        var identity by mutableStateOf("synthetic-a")
        val scene = ImageComposeScene(640, 440, coroutineContext = edt) {
            DesktopPostProcessComparisonWorkspace(identity, bytes, bytes, IntSize(160, 120))
        }
        fun windows() = java.awt.Window.getWindows().filterIsInstance<ComposeWindow>()
            .filter { it.title == "原图与处理结果对比" && it.isDisplayable }
        suspend fun frames() { repeat(5) { scene.render(System.nanoTime()).close(); delay(20) } }
        suspend fun await(label: String, condition: () -> Boolean) {
            assertTrue(withTimeoutOrNull(15_000) { while (!condition()) frames(); true } == true, label)
        }
        suspend fun open(): ComposeWindow {
            assertTrue(scene.nodes().action("展开大图对比")!!.config[SemanticsActions.OnClick].action!!.invoke())
            await("Actual native comparison child must render") { windows().singleOrNull()?.let { child ->
                child.isShowing && child.nodes().count { it.config.contains(SemanticsActions.SetProgress) } == 1 &&
                    child.nodes().comparison().boundsInRoot.width > 200f } == true }
            assertTrue(scene.nodes().none { it.config.contains(SemanticsActions.SetProgress) }, "Only the child owns a viewport while expanded")
            return windows().single()
        }
        try {
            frames(); val first = scene.nodes().comparison()
            assertTrue(first.config[SemanticsActions.SetProgress].action!!.invoke(.7f)); frames()
            scene.sendPointerEvent(PointerEventType.Scroll, first.boundsInRoot.center, scrollDelta = Offset(0f, -7f)); frames()
            assertTrue(scene.nodes().hasZoom("201%"))
            var child = open()
            assertEquals(.7f, child.nodes().comparison().config[SemanticsProperties.ProgressBarRangeInfo].current)
            assertTrue(child.nodes().hasZoom("201%"))
            val width = child.nodes().comparison().boundsInRoot.width
            child.setSize(if (child.width > 900) 850 else child.width + 120, 620)
            await("Comparison must use the resized viewport") { child.nodes().comparison().boundsInRoot.width != width }
            assertEquals(.7f, child.nodes().comparison().config[SemanticsProperties.ProgressBarRangeInfo].current)
            assertTrue(child.nodes().hasZoom("201%"))
            frames()
            System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
                val target = Path.of(directory).resolve("post-process-expanded-comparison.png"); Files.createDirectories(target.parent)
                val capture = java.awt.Robot().createScreenCapture(child.bounds)
                try { javax.imageio.ImageIO.write(capture, "png", target.toFile()) } finally { capture.flush() }
            }
            assertTrue(child.nodes().action("返回面板对比")!!.config[SemanticsActions.OnClick].action!!.invoke())
            await("Return must dispose child and restore inline viewport") { windows().isEmpty() && scene.nodes().count { it.config.contains(SemanticsActions.SetProgress) } == 1 }
            assertEquals(.7f, scene.nodes().comparison().config[SemanticsProperties.ProgressBarRangeInfo].current)
            assertTrue(scene.nodes().hasZoom("201%"))
            child = open(); child.dispatchEvent(WindowEvent(child, WindowEvent.WINDOW_CLOSING))
            await("Native close must return to inline rather than stop the host") { windows().isEmpty() && scene.nodes().count { it.config.contains(SemanticsActions.SetProgress) } == 1 }
            assertEquals(.7f, scene.nodes().comparison().config[SemanticsProperties.ProgressBarRangeInfo].current)
            open(); identity = "synthetic-b"
            await("New record must dispose old child and start its own inline view") { windows().isEmpty() && scene.nodes().count { it.config.contains(SemanticsActions.SetProgress) } == 1 && scene.nodes().hasZoom("100%") }
            assertEquals(.5f, scene.nodes().comparison().config[SemanticsProperties.ProgressBarRangeInfo].current)
            await("New record must publish only its decoded image identities") { scene.nodes().count { node ->
                node.config.getOrNull(DesktopDecodedImageIdentity) in listOf(desktopDecodedImageIdentity("comparison-synthetic-b-source"), desktopDecodedImageIdentity("comparison-synthetic-b-result")) } == 2 }
        } finally { scene.close(); repeat(4) { yield(); delay(20) } }
    }

    @Test fun `actual old DDIM PNG remains readable but High Enhance is disabled while Medium and independent Upscale are available`() = runBlocking(edt) {
        val parent = Files.createTempDirectory("post-process-sampler-entry-")
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
            for (medium in listOf(false, true)) {
                val model = if (medium) "nai-diffusion-5-full-medium" else "nai-diffusion-5-full"
                val comment = """{"model":"$model","width":64,"height":64,"prompt":"synthetic scene","steps":28,"scale":6,"sampler":"ddim_v3","cfg_rescale":0}"""
                val raster = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
                val bytes = try { PngTextChunks.insertTextChunk(DesktopImageEditing.png(raster), "Comment", comment) } finally { raster.flush() }
                val path = Files.write(parent.resolve("synthetic-$medium.png"), bytes)
                val metadata = DesktopImageMetadata.readEnhance(path.toString())
                assertEquals(NovelAiSampler.DDIM, metadata.settings.sampler)
                assertEquals(medium, metadata.settings.isMedium)
                val scene = ImageComposeScene(650, 1100, coroutineContext = edt) { DesktopImagePostProcessPanel(path, controller) { error("Use must remain explicit") } }
                suspend fun frames() { repeat(5) { scene.render(System.nanoTime()).close(); delay(20) } }
                fun submit(label: String) = scene.nodes().last { node -> node.config.contains(SemanticsActions.OnClick) &&
                    node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
                suspend fun await(label: String, condition: () -> Boolean) {
                    assertTrue(withTimeoutOrNull(10_000) { while (!condition()) frames(); true } == true, label)
                }
                try {
                    await("Independent Upscale must remain available for readable DDIM PNG") {
                        scene.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.startsWith("原图 64") } == true } &&
                            !submit(NovelAiPostProcessTab.UPSCALE.label).config.contains(SemanticsProperties.Disabled) }
                    assertTrue(scene.nodes().action(NovelAiPostProcessTab.ENHANCE.label)!!.config[SemanticsActions.OnClick].action!!.invoke()); frames()
                    if (medium) {
                        assertFalse(submit(NovelAiPostProcessTab.ENHANCE.label).config.contains(SemanticsProperties.Disabled))
                        assertNull(metadata.settings.requestValidationError(metadata.prompt.characterCaptions.size))
                    } else {
                        assertTrue(submit(NovelAiPostProcessTab.ENHANCE.label).config.contains(SemanticsProperties.Disabled))
                        assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.contains("Enhance 不可用") } == true })
                    }
                    assertTrue(app.taskRuntime.tasks.value.isEmpty(), "No provider task may be admitted by inspection")
                    assertContentEquals(bytes, Files.readAllBytes(path))
                } finally { scene.close() }
            }
        } finally { app.close(); check(parent.toFile().deleteRecursively()) }
    }
}
