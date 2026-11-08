@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopStudioGuidanceImagesTest {
    private val edt = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block) }
    private suspend fun ImageComposeScene.frames() { repeat(6) { render(System.nanoTime()).close(); delay(15) } }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    @Test fun `same image in several uses stays distinct and removing one slot preserves other sources and parameters`() {
        val asset = NovelAiStudioAssetRef(id = "shared", path = "images/shared.png", width = 64, height = 64)
        val g = NovelAiImageGuidanceDraft(action = NovelAiGenerationAction.INPAINT, baseImage = asset,
            maskImage = asset.copy(id = "mask"), focusedInpaintRegion = NovelAiFocusedInpaintRegion(0f,0f,.5f,.5f),
            referenceMode = NovelAiReferenceMode.VIBE, preciseReference = NovelAiPreciseReferenceDraft(asset = asset, strength = .4f),
            vibes = listOf(NovelAiVibeReferenceDraft(id = "a", asset = asset, strength = .2f), NovelAiVibeReferenceDraft(id = "b", asset = asset, strength = .8f)))
        val slots = desktopGuidanceImageBindings(g, NovelAiImageModel.V4_5_FULL)
        assertEquals(listOf("base","precise","vibe:a","vibe:b"), slots.map { it.key })
        assertEquals(listOf(true,false,true,true), slots.map { it.active })
        val removed = desktopRemoveGuidanceImage(g, slots[2])
        assertEquals(g.copy(vibes = listOf(g.vibes[1])), removed)
        val baseRemoved = desktopRemoveGuidanceImage(g, slots[0])
        assertEquals(g.copy(action = NovelAiGenerationAction.TEXT_TO_IMAGE, baseImage = null, maskImage = null, focusedInpaintRegion = null), baseRemoved)
        assertFails { desktopRemoveGuidanceImage(g.copy(baseImage = asset.copy(id = "new")), slots[0]) }
        assertTrue(desktopGuidanceImageBindings(g, NovelAiImageModel.V5_FULL).filter { it.key != "base" }.none { it.active })
    }
    @Test fun `real controller replaces one Vibe preserving its identity settings current mode and undo and refuses stale source`() = runBlocking {
        val f = FinalProductDesignFixture()
        try {
            f.initialize(); val c = f.container.novelAiStudioController
            val file = f.root.resolve("source.png")
            Files.write(file, DesktopImageEditing.png(BufferedImage(64,64,BufferedImage.TYPE_INT_RGB)))
            assertTrue(c.importGuidance(file, NovelAiImageUseTarget.VIBE_REFERENCE))
            assertTrue(c.importGuidance(file, NovelAiImageUseTarget.VIBE_REFERENCE))
            c.replace { d -> d.copy(imageGuidance = d.imageGuidance.copy(referenceMode = NovelAiReferenceMode.NONE,
                vibes = d.imageGuidance.vibes.map { it.copy(strength = .37f, informationExtracted = .82f, encodedVibe = "synthetic-encoding") })) }
            val before = assertNotNull(c.draft.value)
            val first = before.imageGuidance.vibes[0]
            assertTrue(c.importGuidance(file, NovelAiImageUseTarget.VIBE_REFERENCE, first.id, preserveMode = true, expectedAsset = first.asset))
            val after = c.draft.value!!.imageGuidance
            assertEquals(2, after.vibes.size); assertEquals(before.imageGuidance.referenceMode, after.referenceMode)
            assertEquals(first.copy(asset = after.vibes[0].asset, encodedVibe = null), after.vibes[0])
            assertNotEquals(first.asset, after.vibes[0].asset); assertEquals(before.imageGuidance.vibes[1], after.vibes[1])
            val stable = c.draft.value
            assertFalse(c.importGuidance(file, NovelAiImageUseTarget.VIBE_REFERENCE, first.id, preserveMode = true, expectedAsset = first.asset))
            assertEquals(stable, c.draft.value)
            assertTrue(c.undo()); assertEquals(before.imageGuidance, c.draft.value!!.imageGuidance)
            assertTrue(f.requests.isEmpty()); assertTrue(f.container.taskRuntime.tasks.value.isEmpty())
        } finally { f.close() }
    }
    @Test fun `compact guidance shows exact image uses and paused encoded references with reachable per-use actions`() = runBlocking(edt) {
        val bytes = DesktopImageEditing.png(BufferedImage(64,64,BufferedImage.TYPE_INT_RGB))
        val asset = NovelAiStudioAssetRef(id = "a", path = "images/a.png", width = 64, height = 64)
        val g = NovelAiImageGuidanceDraft(action = NovelAiGenerationAction.IMAGE_TO_IMAGE, baseImage = asset,
            preciseReference = NovelAiPreciseReferenceDraft(asset = asset), vibes = listOf(NovelAiVibeReferenceDraft(id = "encoded", encodedVibe = "synthetic")))
        for (width in listOf(320,680)) {
            val replaced = mutableListOf<String>(); val removed = mutableListOf<String>(); var viewed = 0
            val scene = ImageComposeScene(width, 420) { DesktopStudioGuidanceImages(g, NovelAiImageModel.V5_FULL, { bytes },
                { viewed++ }, { replaced += it.key }, { removed += it.key }, {}) }
            try {
                scene.frames()
                assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.contains("3 项") } == true })
                fun action(label: String) = scene.nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(label) }
                for (label in listOf("查看基图","更换基图","移除基图","更换精确参考","更换氛围参考 1")) {
                    val node = action(label); val bounds = node.boundsInRoot
                    assertTrue(bounds.width >= 24 && bounds.height >= 24 && bounds.left >= 0 && bounds.right <= width)
                    assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
                }
                assertTrue(action("查看氛围参考 1").config.contains(SemanticsProperties.Disabled))
                assertEquals(listOf("base","precise","vibe:encoded"), replaced)
                assertEquals(listOf("base"), removed); assertEquals(1, viewed)
                System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { dir ->
                    val p = java.nio.file.Path.of(dir).resolve("guidance-bindings-$width.png"); Files.createDirectories(p.parent)
                    scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(p,it.bytes) } }
                }
            } finally { scene.close() }
        }
    }
}
