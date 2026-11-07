@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopGuidanceNumericControlsTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `actual guidance decimals and undo stay with each Vibe across reorder removal and equivalent information edits`(): Unit = runBlocking(edt) {
        val parent = Files.createTempDirectory("guidance-numeric-")
        val raster = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        val bytes = try { DesktopImageEditing.png(raster) } finally { raster.flush() }
        val file = Files.write(parent.resolve("reference.png"), bytes)
        val asset = NovelAiStudioAssetRef(path = file.toString(), sha256 = DesktopPostProcessResults.digest(bytes), width = 32, height = 32)
        val a = NovelAiVibeReferenceDraft(id = "a", asset = asset, encodedVibe = "AQID", informationExtracted = .5f, strength = .5f)
        val b = NovelAiVibeReferenceDraft(id = "b", asset = asset.copy(id = "asset-b"), encodedVibe = "BAUG", informationExtracted = .5f, strength = .5f)
        val original = NovelAiImageGuidanceDraft(referenceMode = NovelAiReferenceMode.VIBE, vibes = listOf(a, b))
        var guidance by mutableStateOf(original)
        val histories = DesktopStudioTextHistories()
        val scene = ImageComposeScene(640, 1100, coroutineContext = edt) {
            CompositionLocalProvider(LocalDesktopStudioTextHistories provides histories) {
                Column(Modifier.fillMaxSize()) { DesktopGuidanceNumericControls(guidance) { guidance = it(guidance) } }
            }
        }
        fun nodes(): List<SemanticsNode> {
            fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
        }
        fun fields(label: String) = nodes().filter { it.config.contains(SemanticsActions.SetText) &&
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
        suspend fun frames() { repeat(5) { scene.render(System.nanoTime()).close(); delay(20) } }
        suspend fun set(label: String, index: Int, text: String) {
            val field = fields(label)[index]
            assertTrue(field.config[SemanticsActions.RequestFocus].action!!.invoke()); frames()
            assertTrue(fields(label)[index].config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text))); frames()
        }
        suspend fun undo(label: String, index: Int) {
            assertTrue(fields(label)[index].config[SemanticsActions.RequestFocus].action!!.invoke()); frames()
            scene.sendKeyEvent(KeyEvent(Key.Z, KeyEventType.KeyDown, isCtrlPressed = true))
            scene.sendKeyEvent(KeyEvent(Key.Z, KeyEventType.KeyUp, isCtrlPressed = true)); frames()
        }
        try {
            frames(); set("强度", 0, "0")
            assertEquals("0", fields("强度")[0].config[SemanticsProperties.EditableText].text)
            set("强度", 0, "0."); set("强度", 0, "0.3"); set("强度", 0, "0.35")
            assertEquals(.35f, guidance.vibes[0].strength); assertEquals(.5f, guidance.vibes[1].strength)
            assertEquals(a.encodedVibe, guidance.vibes[0].encodedVibe)
            set("强度", 1, "0.65"); undo("强度", 1)
            assertEquals(.35f, guidance.vibes[0].strength); assertEquals(.5f, guidance.vibes[1].strength)
            undo("强度", 0)
            assertEquals(.5f, guidance.vibes[0].strength)
            set("信息提取", 0, "0.50")
            assertEquals(a.encodedVibe, guidance.vibes[0].encodedVibe, "Equivalent numeric input must preserve cached encoding")
            set("信息提取", 1, "0.8")
            assertNull(guidance.vibes[1].encodedVibe); assertEquals(a, guidance.vibes[0])
            set("强度", 1, "0.7")
            guidance = guidance.copy(vibes = guidance.vibes.reversed()); frames()
            undo("强度", 0)
            assertEquals("b", guidance.vibes[0].id); assertEquals(.5f, guidance.vibes[0].strength)
            val staleA = fields("强度")[1].config[SemanticsActions.SetText].action!!
            val remove = nodes().filter { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("移除 Vibe") == true }
            assertTrue(remove[1].config[SemanticsActions.OnClick].action!!.invoke()); frames()
            assertTrue(staleA.invoke(AnnotatedString("0.9"))); frames()
            assertEquals(original.copy(vibes = listOf(b.copy(informationExtracted = .8f, encodedVibe = null))), guidance)
            assertContentEquals(bytes, Files.readAllBytes(file))
            System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { folder ->
                val target = Path.of(folder).resolve("guidance-numeric-vibe-identity.png"); Files.createDirectories(target.parent)
                scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(target, it.bytes) } }
            }
        } finally { scene.close(); check(parent.toFile().deleteRecursively()) }
    }
}
