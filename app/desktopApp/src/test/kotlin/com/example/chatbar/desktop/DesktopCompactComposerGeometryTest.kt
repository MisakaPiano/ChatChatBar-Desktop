@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import kotlinx.coroutines.*
import java.nio.file.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.test.*

class DesktopCompactComposerGeometryTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private suspend fun frames(scene: ImageComposeScene) { repeat(5) { scene.render(System.nanoTime()).close(); yield(); delay(10) } }
    private fun shot(scene: ImageComposeScene, name: String) {
        System.getProperty("chatbar.test.screenshot.dir")?.let { dir ->
            Files.createDirectories(Path.of(dir)); scene.render(System.nanoTime()).use { image -> image.encodeToData()?.let { Files.write(Path.of(dir, name), it.bytes) } }
        }
    }
    private suspend fun fixture(root: Path): DesktopAppContainer {
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() })
        val person = CharacterInfo.create("Alice").copy(fishAudioVoice = FishAudioVoiceBinding("synthetic", "Voice"))
        val card = CharacterCard.create("Geometry", greeting = "Hello", characters = listOf(person))
        container.characterRepository.save(card)
        val id = container.characterSessionService.createSessionForCharacter(card.id)
        val session = container.chatRepository.getSession(id)!!
        container.chatRepository.saveSessionSettingsDraft(session, session.copy(audiobookModeEnabled = true))
        container.primaryChatController.refresh()
        return container
    }

    @Test fun `minimum composer centers text and all five controls with equal targets across widths`(): Unit = runBlocking(edt) {
        val root = Files.createTempDirectory("compact-composer-geometry-")
        val container = fixture(root)
        try {
            for (width in listOf(420, 900, 1600)) {
                val controller = container.primaryChatController
                val navigation = DesktopCompactChatNavigation().apply { onSessionEntered(controller.state.value.selectedSession!!.id, controller.state.value) }
                val layout = DesktopComposerLayoutState().apply { drag(1000f, 900f) }
                val scene = ImageComposeScene(width, 900) { DesktopPrimaryChatPanel(controller, DesktopShellLayoutPolicy.sizeForWidth(width.toFloat()), layout,
                    compactNavigation = navigation) }
                try {
                    frames(scene)
                    val labels = listOf("朗读音色设置", "添加图片附件", DesktopUiText.EXPAND_COMPOSER.zhCn, "发送", "发送按键设置")
                    val controls = labels.map { label -> nodes(scene).single { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() } }
                    val center = controls.first().boundsInRoot.center.y
                    controls.forEach { node ->
                        assertTrue(abs(node.boundsInRoot.center.y - center) <= 1f, "Control center: ${node.boundsInRoot}")
                        assertEquals(36f, node.boundsInRoot.height)
                        assertTrue(node.boundsInRoot.left >= 0 && node.boundsInRoot.right <= width)
                    }
                    val placeholder = nodes(scene).single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == desktopComposerPlaceholder(DesktopSendKey.CTRL_ENTER) } == true }
                    assertTrue(abs(placeholder.boundsInRoot.center.y - center) <= 1f, "Text is not centered: ${placeholder.boundsInRoot}")
                    shot(scene, "minimum-composer-$width.png")
                } catch (failure: Throwable) { shot(scene, "minimum-composer-failure-$width.png"); throw failure }
                finally { scene.close() }
            }
        } finally { container.close(); root.toFile().deleteRecursively() }
    }

    @Test fun `wide chat with actual indexed history exposes exterior vertical navigation and first then bottom work`(): Unit = runBlocking(edt) {
        val root = Files.createTempDirectory("indexed-chat-navigation-")
        val container = fixture(root)
        try {
            val controller = container.primaryChatController
            val id = controller.state.value.selectedSession!!.id
            repeat(12) { turn ->
                container.chatRepository.addMessage(ChatMessage.create(id, MessageRole.USER, "Question $turn"))
                container.chatRepository.addMessage(ChatMessage.create(id, MessageRole.ASSISTANT, "Synthetic response $turn\n".repeat(10)))
            }
            controller.refresh()
            assertEquals(25, controller.state.value.totalMessageCount)
            val scene = ImageComposeScene(1800, 900) { DesktopPrimaryChatPanel(controller, DesktopShellSize.WIDE, remember { DesktopComposerLayoutState() }) }
            fun action(label: String) = nodes(scene).singleOrNull { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            try {
                withTimeout(7000) { do { frames(scene) } while (action(DesktopUiText.FIRST_MESSAGE.zhCn)?.let { !it.config.contains(SemanticsProperties.Disabled) } != true) }
                val timeline = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == "chat-timeline-content" }.boundsInRoot
                val first = action(DesktopUiText.FIRST_MESSAGE.zhCn)!!
                val previous = action(DesktopUiText.PREVIOUS_MESSAGE.zhCn)!!
                assertTrue(first.boundsInRoot.left >= timeline.right)
                assertEquals(previous.boundsInRoot.left, first.boundsInRoot.left)
                assertTrue(first.boundsInRoot.top >= previous.boundsInRoot.bottom)
                shot(scene, "indexed-history-before-navigation.png")
                assertTrue(first.config[SemanticsActions.OnClick].action!!.invoke())
                withTimeout(7000) { do { frames(scene) } while (action(DesktopUiText.JUMP_BOTTOM.zhCn)?.let { !it.config.contains(SemanticsProperties.Disabled) } != true) }
                shot(scene, "indexed-history-exterior-navigation.png")
                assertTrue(action(DesktopUiText.JUMP_BOTTOM.zhCn)!!.config[SemanticsActions.OnClick].action!!.invoke())
                withTimeout(7000) { do { frames(scene) } while (action(DesktopUiText.FIRST_MESSAGE.zhCn)?.let { !it.config.contains(SemanticsProperties.Disabled) } != true) }
            } catch (failure: Throwable) { shot(scene, "indexed-history-navigation-failure.png"); throw failure }
            finally { scene.close() }
        } finally { container.close(); root.toFile().deleteRecursively() }
    }
}
