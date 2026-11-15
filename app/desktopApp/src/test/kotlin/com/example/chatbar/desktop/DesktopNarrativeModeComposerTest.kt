@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import kotlinx.coroutines.*
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopNarrativeModeComposerTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    private suspend fun frames(scene: ImageComposeScene) { repeat(5) { scene.render(System.nanoTime()).close(); yield(); delay(10) } }
    private fun control(scene: ImageComposeScene, label: String) = nodes(scene).last {
        label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
    private fun clickText(scene: ImageComposeScene, text: String) {
        val node = nodes(scene).last { it.config.contains(SemanticsActions.OnClick) && walk(it).any { child ->
            child.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } } }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
    }
    @Test fun `production compact and expanded composers persist the same mode and global disable removes both controls`() = runBlocking(edt) {
        val root = Files.createTempDirectory("narrative-composer-")
        val c = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() })
        try {
            val card = CharacterCard.create("Synthetic", greeting = "Hello")
            c.characterRepository.save(card)
            val id = c.characterSessionService.createSessionForCharacter(card.id)
            val model = ModelConfig(id = "fixture", displayName = "Fixture", baseUrl = "https://example.invalid/v1",
                apiKey = "synthetic-no-requests", modelName = "fixture", createdAt = 1)
            c.modelRepository.saveModel(model)
            c.settingsRepository.updateAppSettings { it.copy(defaultModelId = model.id) }
            val controller = c.primaryChatController
            controller.refresh()
            controller.editComposer("draft stays unchanged")
            val scene = ImageComposeScene(1100, 900, coroutineContext = edt) {
                DesktopPrimaryChatPanel(controller, DesktopShellSize.MEDIUM, remember { DesktopComposerLayoutState() }) }
            try {
                frames(scene)
                assertTrue(control(scene, "回复节奏：聚焦模式").config[SemanticsActions.OnClick].action!!.invoke())
                frames(scene); clickText(scene, "推进模式")
                withTimeout(6000) { while (controller.state.value.selectedSession?.narrativeMode != ChatNarrativeMode.ADVANCE) { frames(scene) } }
                assertEquals(ChatNarrativeMode.ADVANCE, c.chatRepository.getSession(id)!!.narrativeMode)
                assertEquals("draft stays unchanged", controller.state.value.composerDraft)
                assertTrue(control(scene, DesktopUiText.EXPAND_COMPOSER.zhCn).config[SemanticsActions.OnClick].action!!.invoke())
                frames(scene)
                assertTrue(control(scene, "回复节奏：推进模式").config[SemanticsActions.OnClick].action!!.invoke())
                frames(scene); clickText(scene, "聚焦模式")
                withTimeout(6000) { while (controller.state.value.selectedSession?.narrativeMode != ChatNarrativeMode.FOCUS) { frames(scene) } }
                c.modelSettingsController.loadSettings()
                c.modelSettingsController.editSettings { it.copy(disableNarrativeModes = true) }
                assertTrue(c.modelSettingsController.state.value.chatDefaultsDirty)
                c.modelSettingsController.saveAppSettings()
                assertTrue(c.settingsRepository.getAppSettings().disableNarrativeModes)
                controller.refresh(); frames(scene)
                assertFalse(nodes(scene).any { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { label -> label.startsWith("回复节奏：") } })
                assertEquals("draft stays unchanged", controller.state.value.composerDraft)
                assertEquals(ChatNarrativeMode.FOCUS, c.chatRepository.getSession(id)!!.narrativeMode)
                assertEquals(1, c.chatRepository.getMessages(id).size) // No generated/user message or provider call.
            } finally { scene.close() }
        } finally { c.close(); root.toFile().deleteRecursively() }
    }
}
