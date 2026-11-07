@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiAccountUsage
import com.example.chatbar.ui.imageprompt.NovelAiAccountUiState
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopStudioAccountLayoutTest {
    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `account controls align right and wrap without moving allowance or losing actions`() = runBlocking(awt) {
        val unknown = NovelAiAccountUiState(loading = false)
        val loaded = unknown.reconcile(NovelAiAccountUsage(12345, 3, true, 75.0, false))
        for ((kind, quota) in listOf("unknown" to unknown, "loaded" to loaded))
        for (width in listOf(220, 360, 600, 640, 1100)) {
            var settings = 0; var refresh = 0
            val label = if (width == 220) "当前账户：NovelAI · 合成测试账户名称用于窄窗回流验证" else "当前账户：NovelAI · 合成账户"
            val scene = ImageComposeScene(width, 480) {
                StudioAccountCluster(NovelAiImageModel.V5_FULL, quota) {
                    StudioAccountActions {
                        StatusText(label)
                        DesktopIconAction("账户设置测试", DesktopAppIcons.Settings) { settings++ }
                        StudioAction("刷新", icon = DesktopAppIcons.Refresh, style = StudioActionStyle.TERTIARY) { refresh++ }
                    }
                }
            }
            try {
                scene.frames()
                val account = scene.node(label).boundsInRoot
                val gear = scene.node("账户设置测试").boundsInRoot
                val reload = scene.node("刷新").boundsInRoot
                val allowance = scene.node(if (kind == "unknown") "V5 Opus · 额度未知" else
                    "V5 Opus 75% · 约 ${quota.approximateV5Images} 张").boundsInRoot
                if (kind == "loaded") {
                    assertTrue(scene.node("Anlas 12345").boundsInRoot.width > 0)
                    assertTrue(scene.node("V5 约 ${quota.approximateV5Images} 张").boundsInRoot.width > 0)
                }
                for (rect in listOf(account, gear, reload, allowance)) {
                    assertTrue(rect.width > 0 && rect.height > 0)
                    assertTrue(rect.left >= 8f && rect.right <= width - 8f + 1f, "$width: $rect")
                }
                assertEquals(width - 8f, reload.right, 1f)
                assertEquals(8f, allowance.left, 1f)
                if (width >= 600) {
                    assertTrue(account.left > width / 3f)
                    assertEquals(gear.center.y, reload.center.y, 1f)
                    assertEquals(account.center.y, gear.center.y, 1f)
                    assertTrue(allowance.top < reload.bottom && reload.top < allowance.bottom,
                        "Quota and controls share the compact information region: $width / $allowance / $reload")
                    assertTrue(scene.node(if (kind == "unknown") "V5 额度未知" else "V5 额度 75%").boundsInRoot.bottom <= 84f,
                        "No separate empty account row: $width / $kind")
                } else {
                    assertTrue(allowance.bottom <= minOf(account.top, gear.top, reload.top) + 1f)
                    assertTrue(reload.center.y >= account.center.y - 1f, "Controls wrap after information: $width / $account / $reload")
                }
                scene.click("账户设置测试"); scene.click("刷新")
                assertEquals(1, settings); assertEquals(1, refresh)
                assertFalse(scene.nodes().any { it.matches("NovelAI Studio") || it.matches("高级 / 诊断") })
                System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
                    val file = Path.of(directory).resolve("studio-account-header-$kind-$width.png")
                    Files.createDirectories(file.parent)
                    scene.render().use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
                }
            } finally { scene.close() }
        }
    }
    private suspend fun ImageComposeScene.frames() { repeat(8) { render().close(); yield() }; delay(60); render().close() }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.matches(s: String) = config.getOrNull(SemanticsProperties.ContentDescription)?.contains(s) == true ||
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == s } == true
    private fun ImageComposeScene.node(s: String) = nodes().first { it.matches(s) }
    private suspend fun ImageComposeScene.click(s: String) {
        val target = nodes().first { it.matches(s) && it.config.getOrNull(SemanticsActions.OnClick) != null }.boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, target, button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, target, button = PointerButton.Primary); frames()
    }
}
