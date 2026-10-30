@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopAdaptiveChatPresentationTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.label(s: String) = config.getOrNull(SemanticsProperties.Text)?.any { it.text == s } == true ||
        config.getOrNull(SemanticsProperties.ContentDescription)?.contains(s) == true
    private suspend fun frames(scene: ImageComposeScene) { repeat(6) { scene.render(System.nanoTime()).close(); yield(); delay(15) } }
    private suspend fun tap(scene: ImageComposeScene, label: String) {
        val candidate = nodes(scene).asReversed().first { it.label(label) }
        val node = generateSequence(candidate) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); frames(scene)
    }
    private fun capture(scene: ImageComposeScene, name: String) {
        val output = System.getenv("CCB_UI_REVIEW_OUTPUT") ?: return
        Files.createDirectories(Path.of(output))
        scene.render(System.nanoTime()).use { it.encodeToData()?.let { bytes -> Files.write(Path.of(output, name), bytes.bytes) } }
    }
    @Test fun `live resizing restores automatic columns but respects manually closed columns and exposes compact logs`() = runBlocking(edt) {
        val base = Files.createTempDirectory("adaptive-chat-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(base.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            base.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { """{"entries":[]}""".toByteArray() })
        try {
            val people = listOf("小宁", "阿岚", "林音", "夏禾").map { CharacterInfo.create(it) }
            val card = CharacterCard.create("旅行故事 · 合成资料", greeting =
                "清晨的阳光越过窗沿，地图上的河流被照得清亮。远处传来一声短促的汽笛。\n" +
                    "<n=\"小宁\"/>[渡口比车站近，我们先去那里问问吧。]()\n" +
                    "阿岚把折好的外套搭在椅背，俯身看了看她指着的地方。\n" +
                    "<n=\"阿岚\"/>[可以。不过出发之前，先把这封信读完。]()\n" +
                    "```text\n姓名：小宁\n时间：清晨\n位置：旅馆大厅\n衣着：浅色外套，旅行背包\n当前动作：查看渡口地图，准备启程。\n心情：期待又有些紧张。\n```\n" +
                    "```text\n姓名：阿岚\n位置：旅馆大厅\n当前动作：整理行李，等同伴一起出发。\n```").copy(characters = people)
            app.characterRepository.save(card)
            app.characterSessionService.createSessionForCharacter(card.id)
            app.primaryChatController.refresh()
            var width by mutableStateOf(1760f)
            val scene = ImageComposeScene(1760, 880, coroutineContext = edt) {
                Box(Modifier.width(width.dp).fillMaxHeight()) {
                    DesktopPrimaryChatPanel(app.primaryChatController, DesktopShellSize.WIDE, remember { DesktopComposerLayoutState() })
                }
            }
            fun hasRail() = nodes(scene).any { it.config.getOrNull(SemanticsProperties.TestTag) == "chat-people-column" }
            fun hasSessions() = nodes(scene).any { it.label("搜索对话") }
            fun hasQuickList() = nodes(scene).any { it.label("活跃 2/4") && generateSequence(it) { n -> n.parent }
                .any { n -> n.config.contains(SemanticsActions.OnClick) } && it.boundsInRoot.left > 500f }
            try {
                frames(scene); assertTrue(hasRail()); assertTrue(hasSessions())
                assertFalse(hasQuickList())
                capture(scene, "chat-adaptive-wide.png")
                tap(scene, "角色栏设置")
                assertFalse(nodes(scene).any { it.label("允许同时展开多个状态") })
                tap(scene, "原地展开状态")
                withTimeout(5000) { while (!app.primaryChatController.chatUi!!.state.value.inlineStatuses) { frames(scene) } }
                frames(scene); tap(scene, "允许同时展开多个状态")
                withTimeout(5000) { while (!app.primaryChatController.chatUi!!.state.value.multipleInlineStatuses) { frames(scene) } }
                tap(scene, "关闭设置")
                suspend fun person(name: String) {
                    val target = nodes(scene).first { it.label("人物状态 · $name") }
                    generateSequence(target) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
                        .config[SemanticsActions.OnClick].action!!.invoke()
                    frames(scene)
                }
                fun expanded() = nodes(scene).count { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("inline-person-status-") == true }
                person("小宁"); person("阿岚"); assertEquals(2, expanded())
                capture(scene, "chat-multiple-statuses.png")
                person("小宁"); assertEquals(1, expanded())
                person("小宁"); assertEquals(2, expanded())
                app.primaryChatController.chatUi!!.update { it.copy(multipleInlineStatuses = false) }; frames(scene)
                assertEquals(1, expanded())
                person("阿岚"); assertEquals(1, expanded())
                width = 970f; frames(scene)
                assertFalse(hasSessions()); assertTrue(hasRail())
                width = 590f; frames(scene)
                assertFalse(hasRail())
                assertTrue(nodes(scene).any { it.label("活跃 2/4") })
                capture(scene, "chat-adaptive-narrow.png")
                width = 650f; frames(scene); assertFalse(hasRail())
                width = 720f; frames(scene); assertTrue(hasRail()); assertFalse(hasSessions())
                width = 1100f; frames(scene); assertTrue(hasSessions()); assertTrue(hasRail())
                tap(scene, "收起角色栏"); assertFalse(hasRail())
                width = 1760f; frames(scene); assertFalse(hasRail())
                tap(scene, "展开角色栏"); assertTrue(hasRail())
                tap(scene, "收起会话列表"); assertFalse(hasSessions())
                width = 970f; frames(scene); width = 1760f; frames(scene); assertFalse(hasSessions())
                // The existing model-details disclosure remains the only permanent debug entry.
                val detail = nodes(scene).first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { s -> s.contains("模型详情") } == true }
                generateSequence(detail) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }.config[SemanticsActions.OnClick].action!!.invoke()
                frames(scene); tap(scene, "调试日志")
                assertTrue(nodes(scene).any { it.label("聊天调试日志") })
                tap(scene, "▸ 维护工具")
                assertTrue(nodes(scene).any { it.label("检查消息顺序") })
                assertTrue(nodes(scene).any { it.label("重建 RAG 索引") })
                assertNull(app.chatMaintenanceController.state.value.sessionId)
                capture(scene, "chat-debug-maintenance.png")
                tap(scene, "关闭调试日志")
            } finally { scene.close() }
        } finally { app.close(); base.toFile().deleteRecursively() }
    }
}
