@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.*
import com.example.chatbar.domain.image.NovelAiImageModel
import java.awt.Frame
import java.awt.Window
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopStudioUseWindowTest {
    private val edt = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block) }
    private suspend fun ImageComposeScene.frames() { repeat(5) { render(System.nanoTime()).close(); delay(20) } }
    private fun nodes(owners: Collection<SemanticsOwner>): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return owners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun click(nodes: List<SemanticsNode>, label: String) {
        assertTrue(nodes.single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(label) &&
            it.config.contains(SemanticsActions.OnClick) }.config[SemanticsActions.OnClick].action!!.invoke())
    }
    @Test fun `repeated use after choosing a target restores the same native auxiliary window and retains chosen sources`() = runBlocking(edt) {
        requireDesktopGraphicsForNativeTest()
        val f = FinalProductDesignFixture(); var auxiliary: ComposeWindow? = null
        try {
            f.initialize(); f.seedLocalImages(); val c = f.container.novelAiStudioController
            assertTrue(c.replace { it.withActiveSettings(it.activeSettings.copy(model = NovelAiImageModel.V4_5_FULL)) })
            val before = c.draft.value!!
            val scene = ImageComposeScene(1240, 900) { DesktopNovelAiStudioPanel(c) }
            try {
                suspend fun window(title: String): ComposeWindow = withTimeout(10000) {
                    while (true) {
                        scene.frames()
                        val found = Window.getWindows().filterIsInstance<ComposeWindow>().singleOrNull { it.isDisplayable && it.title == title }
                        if (found != null) return@withTimeout found
                    }
                    error("unreachable")
                }
                scene.frames(); click(nodes(scene.semanticsOwners), "图像操作 / 用作")
                val first = window("当前图片"); auxiliary = first
                click(nodes(first.semanticsOwners), "用作 · 图生图")
                assertSame(first, window("图像引导"))
                val base = assertNotNull(c.draft.value!!.imageGuidance.baseImage)
                first.extendedState = first.extendedState or Frame.ICONIFIED
                scene.frames(); assertTrue(first.extendedState and Frame.ICONIFIED != 0)
                click(nodes(scene.semanticsOwners), "图像操作 / 用作")
                assertSame(first, window("当前图片"))
                withTimeout(5000) { while (first.extendedState and Frame.ICONIFIED != 0) scene.frames() }
                click(nodes(first.semanticsOwners), "用作 · 氛围参考")
                assertSame(first, window("图像引导"))
                assertEquals(base, c.draft.value!!.imageGuidance.baseImage)
                assertEquals(1, c.draft.value!!.imageGuidance.vibes.size)
                click(nodes(scene.semanticsOwners), "图像操作 / 用作")
                assertSame(first, window("当前图片"))
                assertEquals(before.basePrompt, c.draft.value!!.basePrompt)
                assertEquals(before.activeSettings, c.draft.value!!.activeSettings)
                assertTrue(f.requests.isEmpty()); assertTrue(f.container.taskRuntime.tasks.value.isEmpty())
            } finally { scene.close(); auxiliary?.dispose() }
        } finally { f.close() }
    }
}
