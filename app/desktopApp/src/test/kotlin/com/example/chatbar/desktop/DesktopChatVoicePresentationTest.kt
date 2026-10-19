@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.ThemeMode
import kotlinx.coroutines.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopChatVoicePresentationTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `chat generation stays collapsed and requires explicit authorization in light and dark compact layouts`() = runBlocking(edt) {
        DesktopChatVoiceRuntimeTest.Fixture().use { f ->
            f.seed()
            val card = f.characters.getById("card")!!
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                val scene = ImageComposeScene(360, 900) {
                    CompositionLocalProvider(LocalDesktopPalette provides desktopSemanticColors(mode, com.example.chatbar.domain.appearance.DefaultThemeColorHsv, false)) {
                        Column { DesktopVoiceGenerationControls(f.runtime, f.message,
                            DesktopPrimaryChatState(selectedSession = f.session, selectedCharacter = card)) }
                    }
                }
                fun nodes(): List<SemanticsNode> {
                    fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                    return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                }
                fun button(label: String) = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true && it.config.contains(SemanticsActions.OnClick) }
                suspend fun frame() { repeat(4) { scene.render(System.nanoTime()).close(); yield(); delay(10) } }
                try {
                    frame(); assertEquals(0, f.server.requestCount)
                    assertTrue(nodes().none { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("授权并开始生成") == true })
                    assertTrue(button("语音生成").config[SemanticsActions.OnClick].action!!())
                    frame()
                    assertTrue(button("生成整条回复 / 补齐语音").config[SemanticsActions.OnClick].action!!())
                    frame()
                    val confirm = button("授权并开始生成")
                    assertEquals(Role.Button, confirm.config[SemanticsProperties.Role])
                    assertTrue(confirm.boundsInRoot.left >= 0 && confirm.boundsInRoot.right <= 360)
                    assertTrue(confirm.config.contains(SemanticsActions.RequestFocus))
                    assertEquals(0, f.server.requestCount)
                    button("取消").config[SemanticsActions.OnClick].action!!(); frame()
                    assertEquals(0, f.server.requestCount)
                } finally { scene.close() }
            }
        }
    }
}
