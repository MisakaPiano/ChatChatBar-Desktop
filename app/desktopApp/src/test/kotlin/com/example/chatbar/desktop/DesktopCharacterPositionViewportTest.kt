@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopCharacterPositionViewportTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `default tool frame and short viewport keep both coordinate fields and reset reachable at real click height`() = runBlocking(edt) {
        for (height in listOf(650, 480)) {
            var draft by mutableStateOf(NovelAiStudioDraft(selectedModel = NovelAiImageModel.V5_FULL,
                v5Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, useCharacterPositions = true),
                characters = listOf(NovelAiCharacterPromptDraft(id = "person", prompt = "Synthetic person", center = DesignedCharacterCenter(.2f, .3f)))))
            val chrome = DesktopWindowChrome {}
            val recorder = DesktopChromeLayoutRecorder(chrome)
            val scene = ImageComposeScene(640, height, coroutineContext = edt) {
                DesktopImageToolFrame("角色位置", chrome, recorder) { DesktopCharacterPositions(draft) { draft = it(draft) } }
            }
            fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
            fun nodes() = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
            fun label(n: SemanticsNode, value: String): Boolean = n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true ||
                n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true || n.children.any { label(it, value) }
            fun target(value: String) = nodes().first { label(it, value) &&
                (it.config.contains(SemanticsActions.SetText) || it.config.contains(SemanticsActions.OnClick)) }
            suspend fun frames() { repeat(4) { scene.render().close(); delay(20) } }
            suspend fun reach(value: String): SemanticsNode {
                repeat(5) {
                    val n = target(value); val b = n.boundsInRoot
                    if (b.width >= 24 && b.height >= 24 && b.top >= 0 && b.bottom <= height) return n
                    val scroll = nodes().firstOrNull { it.config.contains(SemanticsActions.ScrollBy) }
                    assertNotNull(scroll, "$height/$value has unusable bounds $b and no scroll owner")
                    assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 160f))
                    frames()
                }
                error("$height/$value not reachable with positive click height: ${target(value).boundsInRoot}")
            }
            try {
                frames()
                val x = reach("X %")
                assertTrue(x.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("25"))); frames()
                val y = reach("Y %")
                assertTrue(y.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("75"))); frames()
                assertEquals(DesignedCharacterCenter(.25f, .75f), draft.characters.single().center)
                val reset = reach("均匀重置")
                System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
                    val p = Path.of(directory).resolve("position-controls-reachable-$height.png"); Files.createDirectories(p.parent)
                    scene.render().use { image -> image.encodeToData()!!.use { Files.write(p, it.bytes) } }
                }
                assertTrue(reset.config[SemanticsActions.OnClick].action!!.invoke()); frames()
                assertNull(draft.characters.single().center)
            } finally { scene.close() }
        }
    }
}
