@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopMomentsPanelTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `narrow and wide timelines preserve post controls private state editable text and persisted viewed marker`() = runBlocking(edt) {
        val parent = Files.createTempDirectory("moments-panel-")
        val root = Files.createDirectory(parent.resolve("data"))
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() })
        var controller: DesktopMomentsController? = null
        try {
            val resources = DesktopCharacterResourceStore(root)
            Files.createDirectory(root.resolve("images"))
            val pixels = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
            val graphics = pixels.createGraphics()
            try { graphics.color = java.awt.Color(40, 140, 170); graphics.fillRect(0, 0, 100, 100)
                graphics.color = java.awt.Color.WHITE; graphics.fillOval(25, 25, 50, 50)
            } finally { graphics.dispose() }
            ImageIO.write(pixels, "png", root.resolve("images/fixture.png").toFile())
            val card = CharacterCard("card", "合成角色卡", createdAt = 1, updatedAt = 1)
            container.jsonFileStorage.saveEntity("character_cards", card.id, card, CharacterCard.serializer())
            container.chatRepository.createSession(ChatSession("session", card.id, "合成会话", createdAt = 1, updatedAt = 1))
            val post = MomentPost("post", card.id, "session", senderName = "合成人物", senderAvatar = "images/fixture.png",
                text = "合成动态文字", imagePath = "images/fixture.png", isPrivate = true, userLiked = true, scheduledAt = 1, generatedAt = 10)
            container.jsonFileStorage.saveEntity("moment_posts", post.id, post, MomentPost.serializer())
            container.settingsRepository.updateAppSettings { it.copy(momentsEnabled = true) }
            val store = DesktopMomentStore(container.jsonFileStorage, container.dataOperationCoordinator)
            val diagnostics = DesktopTransportDiagnosticsOwner()
            diagnostics.addMedia(DesktopTransportDiagnosticEntry("image-task", "moments:IMAGE", "NovelAI", "synthetic", 1,
                status = DesktopTaskStatus.COMPLETED, image = DesktopImageRequestEvidence(1, "POST", 1, 200, 1, 512)))
            val view = DesktopMomentsController(store, container.settingsRepository, container.characterRepository,
                container.chatRepository, container.dataOperationCoordinator, container.taskRuntime,
                DesktopMomentGenerator { _, _, _, _, _, _, _, _, _, _ -> error("Scene must not generate") },
                resources = resources, diagnostics = diagnostics)
            controller = view
            withTimeout(10_000) { view.state.first { it.posts.size == 1 } }
            for (width in listOf(420, 1100)) {
                val opened = mutableListOf<String>()
                val scene = ImageComposeScene(width, 900, coroutineContext = edt) {
                    Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.background)) {
                        DesktopMomentsPanel(view, onOpenSession = { opened += it })
                    }
                }
                try {
                    suspend fun frames() { repeat(5) { scene.render().close(); delay(30) } }
                    fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                    fun all() = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
                    fun matches(node: SemanticsNode, label: String): Boolean =
                        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
                            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true || node.children.any { matches(it, label) }
                    fun click(label: String) {
                        val node = all().first { matches(it, label) && it.config.contains(SemanticsActions.OnClick) }
                        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
                    }
                    fun shot(name: String) { System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { target ->
                        val file = Path.of(target).resolve(name); Files.createDirectories(file.parent)
                        scene.render().use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
                    } }
                    frames()
                    assertTrue(all().any { matches(it, "仅你可见") })
                    assertTrue(all().any { matches(it, "生成动态") })
                    assertFalse(view.state.value.scheduling)
                    withTimeout(10_000) { while (container.settingsRepository.getAppSettings().lastSeenMomentsAt < 10) { frames() } }
                    shot("moments-timeline-$width.png")
                    click(store.posts().single().senderName)
                    withTimeout(5000) { while (opened.isEmpty()) frames() }
                    assertEquals(listOf("session"), opened)
                    click("朋友圈相册"); frames()
                    assertTrue(all().any { matches(it, "相册分组 ${card.id}") })
                    shot("moments-album-groups-$width.png")
                    click("相册分组 ${card.id}"); frames()
                    assertTrue(all().any { matches(it, "返回朋友圈动态 ${post.id}") })
                    shot("moments-album-posts-$width.png")
                    click("返回朋友圈动态 ${post.id}"); frames()
                    assertTrue(all().any { matches(it, "编辑动态文字") })
                    click("更多操作"); frames(); click("请求日志"); frames(); click("动态配图"); frames()
                    assertTrue(all().any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains("有效返回：1") } == true })
                    shot("moments-image-log-$width.png"); click("关闭调试日志"); frames()
                    click("编辑动态文字"); frames()
                    val previousSender = store.posts().single().senderName
                    click("发布人物 · 原发布者 · $previousSender ▾"); frames()
                    click("角色卡 · 合成角色卡"); frames()
                    val field = all().first { it.config.contains(SemanticsActions.SetText) }
                    assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("合成修改 $width")))
                    frames(); shot("moments-edit-$width.png"); click("保存")
                    withTimeout(10_000) { while (store.posts().single().text != "合成修改 $width") { frames() } }
                    frames()
                    assertTrue(store.posts().single().isPrivate); assertTrue(store.posts().single().userLiked)
                    assertEquals("合成角色卡", store.posts().single().senderName)
                    assertNull(store.posts().single().senderCharacterId)
                    assertEquals(post.imagePath, store.posts().single().imagePath)
                    click("删除动态"); frames(); click("关闭"); frames()
                    assertEquals(listOf(post.id), store.posts().map { it.id })
                } finally { scene.close() }
            }
        } finally { controller?.closeAndDrain(); container.close(); parent.toFile().deleteRecursively() }
    }
}
