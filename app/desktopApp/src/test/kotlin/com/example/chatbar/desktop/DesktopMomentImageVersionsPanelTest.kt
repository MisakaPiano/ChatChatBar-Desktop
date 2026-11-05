@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopMomentImageVersionsPanelTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `existing image arrows remain usable during generation and one image still disables directions`() = runBlocking(edt) {
        var post = MomentPost("post", "card", "session", senderName = "Synthetic", text = "Existing post", scheduledAt = 1, generatedAt = 1)
        repeat(2) { i ->
            val path = "images/existing-$i.png"
            post = DesktopMomentImageVersions.append(post, DesktopMomentImageResult(path,
                GeneratedImageMetadata(path, "Scene $i", negativePrompt = "Negative", sizePreset = "SQUARE", width = 2, height = 2), "Prompt $i", "Brief $i"), i + 1L)
        }
        post = DesktopMomentImageVersions.select(post, post.desktopImageVersions.first().id)
        var displayed by mutableStateOf(post)
        val scene = ImageComposeScene(420, 700, coroutineContext = edt) {
            DesktopMomentFeedCard(displayed, null, busy = true, onOpenSession = {}, onOpenCard = {}, onPreviewAvatar = {},
                onPreviewImage = {}, onEdit = {}, onCopy = {}, onLike = {}, onRetry = {}, onGenerateImage = {},
                onRegenerateImage = {}, onVersions = {}, onSelectVersion = { displayed = DesktopMomentImageVersions.select(displayed, it) }, onDelete = {})
        }
        try {
            scene.frames(); scene.click("下一张动态图片")
            assertEquals(post.desktopImageVersions[1].imagePath, displayed.imagePath)
            scene.click("上一张动态图片"); assertEquals(post.imagePath, displayed.imagePath)
            displayed = displayed.copy(desktopImageVersions = listOf(displayed.desktopImageVersions.first()))
            scene.frames(); assertTrue(scene.button("上一张动态图片").config.contains(SemanticsProperties.Disabled))
            assertTrue(scene.button("下一张动态图片").config.contains(SemanticsProperties.Disabled))
        } finally { scene.close() }
    }
    @Test fun `actual version panel selects saved candidate and confirms removal in narrow and wide timeline`(): Unit = runBlocking(edt) {
        for (width in listOf(420, 1100)) {
            val parent = Files.createTempDirectory("moment-versions-ui-")
            val root = parent.resolve("data")
            val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
                parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() },
                bundledAssets = { """{"entries":[]}""".toByteArray() })
            var view: DesktopMomentsController? = null
            try {
                val store = DesktopMomentStore(app.jsonFileStorage, app.dataOperationCoordinator)
                var post = MomentPost("post", "card", "session", senderName = "合成人物", text = "合成动态", scheduledAt = 1, generatedAt = 1)
                Files.createDirectories(root.resolve("images"))
                for (i in 0..2) {
                    val path = "images/version$i.png"
                    val pixels = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
                    pixels.createGraphics().let { graphics ->
                        try { graphics.color = java.awt.Color(40 + i * 60, 120, 170); graphics.fillRect(0, 0, 100, 100)
                            graphics.color = java.awt.Color.WHITE; graphics.fillOval(25, 25, 50, 50) } finally { graphics.dispose() }
                    }
                    Files.write(root.resolve(path), DesktopImageEditing.png(pixels))
                    post = DesktopMomentImageVersions.append(post, DesktopMomentImageResult(path,
                        GeneratedImageMetadata(path, "Scene $i", negativePrompt = "Negative", sizePreset = "SQUARE", width = 100, height = 100),
                        "Prompt $i", "Brief $i"), i + 1L)
                }
                post = DesktopMomentImageVersions.select(post, post.desktopImageVersions.first().id)
                app.jsonFileStorage.saveEntity("moment_posts", post.id, post, MomentPost.serializer())
                app.settingsRepository.updateAppSettings { it.copy(momentsEnabled = true) }
                val controller = DesktopMomentsController(store, app.settingsRepository, app.characterRepository, app.chatRepository,
                    app.dataOperationCoordinator, app.taskRuntime,
                    DesktopMomentGenerator { _, _, _, _, _, _, _, _, _, _ -> error("No provider call") }, resources = DesktopCharacterResourceStore(root))
                view = controller
                withTimeout(5_000) { controller.state.first { it.enabled && it.posts.size == 1 } }
                val scene = ImageComposeScene(width, 900, coroutineContext = edt) {
                    CompositionLocalProvider(LocalDesktopPalette provides desktopSemanticColors(
                        if (width == 420) ThemeMode.DARK else ThemeMode.LIGHT,
                        com.example.chatbar.domain.appearance.DefaultThemeColorHsv, false)) {
                        Column(Modifier.fillMaxSize()) { DesktopMomentsPanel(controller) }
                    }
                }
                try {
                    scene.frames()
                    assertTrue(scene.nodes().any { it.label("1 / 3") })
                    assertTrue(scene.button("上一张动态图片").config.contains(SemanticsProperties.Disabled))
                    scene.click("下一张动态图片")
                    withTimeout(5_000) { while (controller.state.value.posts.single().imagePrompt != "Prompt 1") scene.frames() }
                    scene.click("下一张动态图片")
                    withTimeout(5_000) { while (controller.state.value.posts.single().imagePrompt != "Prompt 2") scene.frames() }
                    scene.frames()
                    withTimeout(5_000) { while (scene.nodes().none {
                        it.config.getOrNull(DesktopDecodedImageIdentity) == desktopDecodedImageIdentity("images/version2.png")
                    }) scene.frames() }
                    assertTrue(scene.nodes().any { it.label("3 / 3") })
                    assertTrue(scene.button("下一张动态图片").config.contains(SemanticsProperties.Disabled))
                    val saved = DesktopMomentStore(app.jsonFileStorage, app.dataOperationCoordinator).posts().single()
                    assertEquals("Prompt 2", saved.imagePrompt)
                    assertEquals("Brief 2", saved.imageBrief)
                    assertEquals(3, saved.desktopImageVersions.size)
                    scene.click("上一张动态图片")
                    withTimeout(5_000) { while (controller.state.value.posts.single().imagePrompt != "Prompt 1") scene.frames() }
                    scene.click("下一张动态图片")
                    withTimeout(5_000) { while (controller.state.value.posts.single().imagePrompt != "Prompt 2") scene.frames() }
                    scene.frames()
                    fun tagged(tag: String) = scene.nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }.boundsInRoot
                    val author = tagged("moment-author-post"); val body = tagged("moment-text-post")
                    val image = tagged("moment-image-post"); val pager = tagged("moment-versions-post")
                    assertTrue(kotlin.math.abs(author.left - body.left) < 1)
                    assertTrue(kotlin.math.abs(body.left - image.left) < 1)
                    assertTrue(pager.top >= image.bottom && pager.right <= width)
                    for (label in listOf("编辑动态文字", "删除动态", "编辑Prompt重新生成", "动态更多操作")) {
                        val bounds = scene.button(label).boundsInRoot
                        assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.height <= 32, label)
                    }
                    scene.shot("moment-feed-versions-$width.png")
                    scene.click("动态更多操作"); scene.click("管理图片版本"); scene.frames()
                    assertTrue(scene.nodes().any { it.label("动态图片版本") })
                    assertTrue(scene.nodes().any { it.label("预览版本 3") })
                    System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
                        val file = Path.of(directory).resolve("moment-image-versions-$width.png"); Files.createDirectories(file.parent)
                        scene.render().use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
                    }
                    scene.click("使用版本 3")
                    withTimeout(5_000) { while (controller.state.value.posts.single().imagePrompt != "Prompt 2") scene.frames() }
                    assertEquals("Brief 2", store.posts().single().imageBrief)
                    scene.click("移除版本 2"); scene.frames()
                    assertEquals(3, store.posts().single().desktopImageVersions.size)
                    scene.click("确认移除版本")
                    withTimeout(5_000) { while (controller.state.value.posts.single().desktopImageVersions.size != 2) scene.frames() }
                    assertEquals("Prompt 2", store.posts().single().imagePrompt)
                    scene.click("关闭"); scene.frames()
                    scene.click("动态更多操作"); scene.click("管理图片版本"); scene.frames()
                    scene.click("移除版本 1"); scene.click("确认移除版本")
                    withTimeout(5_000) { while (controller.state.value.posts.single().desktopImageVersions.size != 1) scene.frames() }
                    scene.click("关闭"); scene.frames()
                    assertTrue(scene.nodes().any { it.label("1 / 1") })
                    assertTrue(scene.button("上一张动态图片").config.contains(SemanticsProperties.Disabled))
                    assertTrue(scene.button("下一张动态图片").config.contains(SemanticsProperties.Disabled))
                } finally { scene.close() }
            } finally { view?.closeAndDrain(); app.close(); check(parent.toFile().deleteRecursively()) }
        }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.label(text: String): Boolean = config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
        config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true || children.any { it.label(text) }
    private suspend fun ImageComposeScene.frames() { repeat(6) { render().close(); delay(15) } }
    private fun ImageComposeScene.button(label: String) = nodes().first { it.label(label) && it.config.contains(SemanticsActions.OnClick) }
    private fun ImageComposeScene.shot(name: String) { System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
        val file = Path.of(directory).resolve(name); Files.createDirectories(file.parent)
        render().use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
    } }
    private suspend fun ImageComposeScene.click(label: String) {
        val node = button(label)
        assertFalse(node.config.contains(SemanticsProperties.Disabled), label)
        assertTrue(node.boundsInRoot.width > 0 && node.boundsInRoot.height > 0)
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); frames()
    }
}
