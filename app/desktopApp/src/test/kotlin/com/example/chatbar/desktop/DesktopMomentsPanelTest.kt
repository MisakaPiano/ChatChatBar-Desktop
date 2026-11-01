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
            val post = MomentPost("post", card.id, "session", senderName = "合成人物", senderAvatar = "images/fixture.png",
                text = "合成动态文字", imagePath = "images/fixture.png", isPrivate = true, userLiked = true, scheduledAt = 1, generatedAt = 10)
            container.jsonFileStorage.saveEntity("moment_posts", post.id, post, MomentPost.serializer())
            container.settingsRepository.updateAppSettings { it.copy(momentsEnabled = true) }
            val store = DesktopMomentStore(container.jsonFileStorage, container.dataOperationCoordinator)
            val view = DesktopMomentsController(store, container.settingsRepository, container.characterRepository,
                container.chatRepository, container.dataOperationCoordinator, container.taskRuntime,
                DesktopMomentGenerator { _, _, _, _, _, _, _, _, _, _ -> error("Scene must not generate") }, resources = resources)
            controller = view
            withTimeout(10_000) { view.state.first { it.posts.size == 1 } }
            for (width in listOf(420, 1100)) {
                val scene = ImageComposeScene(width, 900, coroutineContext = edt) {
                    Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.background)) { DesktopMomentsPanel(view) }
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
                    click("编辑动态文字"); frames()
                    val field = all().first { it.config.contains(SemanticsActions.SetText) }
                    assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("合成修改 $width")))
                    frames(); shot("moments-edit-$width.png"); click("保存")
                    withTimeout(10_000) { while (store.posts().single().text != "合成修改 $width") { frames() } }
                    frames()
                    assertTrue(store.posts().single().isPrivate); assertTrue(store.posts().single().userLiked)
                    click("删除动态"); frames(); click("关闭"); frames()
                    assertEquals(listOf(post.id), store.posts().map { it.id })
                } finally { scene.close() }
            }
        } finally { controller?.closeAndDrain(); container.close(); parent.toFile().deleteRecursively() }
    }
}
