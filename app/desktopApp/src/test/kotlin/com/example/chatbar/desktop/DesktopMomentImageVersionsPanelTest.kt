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
                    Files.write(root.resolve(path), DesktopImageEditing.png(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)))
                    post = DesktopMomentImageVersions.append(post, DesktopMomentImageResult(path,
                        GeneratedImageMetadata(path, "Scene $i", negativePrompt = "Negative", sizePreset = "SQUARE", width = 4, height = 4),
                        "Prompt $i", "Brief $i"), i + 1L)
                }
                app.jsonFileStorage.saveEntity("moment_posts", post.id, post, MomentPost.serializer())
                app.settingsRepository.updateAppSettings { it.copy(momentsEnabled = true) }
                val controller = DesktopMomentsController(store, app.settingsRepository, app.characterRepository, app.chatRepository,
                    app.dataOperationCoordinator, app.taskRuntime,
                    DesktopMomentGenerator { _, _, _, _, _, _, _, _, _, _ -> error("No provider call") }, resources = DesktopCharacterResourceStore(root))
                view = controller
                withTimeout(5_000) { controller.state.first { it.enabled && it.posts.size == 1 } }
                val scene = ImageComposeScene(width, 900, coroutineContext = edt) {
                    Column(Modifier.fillMaxSize()) { DesktopMomentsPanel(controller) }
                }
                try {
                    scene.frames(); scene.click("图片版本 3"); scene.frames()
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
    private suspend fun ImageComposeScene.click(label: String) {
        val node = nodes().first { it.label(label) && it.config.contains(SemanticsActions.OnClick) }
        assertTrue(node.boundsInRoot.width > 0 && node.boundsInRoot.height > 0)
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); frames()
    }
}
