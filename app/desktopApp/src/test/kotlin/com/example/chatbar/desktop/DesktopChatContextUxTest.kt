@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopChatContextUxTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `three scope rows never wrap and disabled segment cannot invoke another target`() = runBlocking(edt) {
        for (width in listOf(360, 1100)) {
            var invoked = ""
            val rows = listOf("回复" to listOf("重新生成", "修复格式"), "本段" to listOf("复制", "编辑", "删除", "截图", "生成语音"),
                "整条" to listOf("复制", "编辑", "删除", "截图", "生成语音")).map { (scope, labels) -> scope to labels.map { label ->
                DesktopChatMenuEntry(label, DesktopAppIcons.Copy, scope != "本段") { invoked = "$scope/$label" }
            } }
            val scene = ImageComposeScene(width, 400) { DesktopChatContextMenu(rows) {} }
            try {
                repeat(3) { scene.render(System.nanoTime()).close(); yield() }
                fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                val nodes = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                for ((scope, labels) in rows) {
                    val buttons = labels.map { e -> nodes.single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("$scope · ${e.label}") == true } }
                    assertTrue(buttons.all { it.boundsInRoot.top == buttons.first().boundsInRoot.top })
                    assertTrue(buttons.all { it.boundsInRoot.bottom <= 400 })
                    assertTrue(buttons.all { it.config[SemanticsProperties.Role] == Role.Button })
                    if (scope == "本段") assertTrue(buttons.all { it.config.contains(SemanticsProperties.Disabled) })
                }
                assertEquals("", invoked)
                val whole = nodes.single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("整条 · 编辑") == true }
                whole.config[SemanticsActions.OnClick].action!!(); assertEquals("整条/编辑", invoked)
            } finally { scene.close() }
        }
    }
    @Test fun `popup flips and stays inside viewport using window coordinates`() {
        val p = DesktopChatMenuPosition(Rect(340f, 500f, 355f, 520f))
        val at = p.calculatePosition(IntRect.Zero, IntSize(360, 550), LayoutDirection.Ltr, IntSize(350, 180))
        assertEquals(10, at.x); assertEquals(320, at.y)
    }
    @Test fun `toolbar pins image actions right and voice is last in left group`() = runBlocking(edt) {
        for (width in listOf(360, 1100)) {
            val scene = ImageComposeScene(width, 120) {
                DesktopChatMessageToolbar(listOf(DesktopMessageAction.COPY), {}, imageActions = {
                    DesktopChatIconAction("生成图片", DesktopAppIcons.ImageAdd) {}
                    DesktopChatIconAction("生图要求", DesktopAppIcons.Settings) {}
                }, showVoice = true)
            }
            try {
                repeat(3) { scene.render(System.nanoTime()).close(); yield() }
                fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                val nodes=scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                fun named(s: String)=nodes.single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(s)==true }
                assertTrue(named("生成本条语音").boundsInRoot.right <= named("生成图片").boundsInRoot.left)
                assertEquals(width.toFloat(), named("生图要求").boundsInRoot.right)
            } finally { scene.close() }
        }
    }
}
