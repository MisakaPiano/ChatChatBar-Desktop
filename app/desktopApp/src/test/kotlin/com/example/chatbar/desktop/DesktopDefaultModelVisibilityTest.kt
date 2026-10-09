@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopDefaultModelVisibilityTest {
    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private class Fixture {
        val root = Files.createTempDirectory("desktop-default-visibility-")
        val c = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() })
        val chat = ModelConfig("chat", "聊天甲", "https://fixture.invalid", "fixture-key", "model-chat", createdAt = 1)
        val image = ModelConfig("image", "设计乙", "https://fixture.invalid", "fixture-key", "model-image", createdAt = 2)
        suspend fun initialize() {
            c.modelRepository.saveModel(chat); c.modelRepository.saveModel(image)
            c.settingsRepository.updateAppSettings { it.copy(defaultModelId = chat.id, defaultImageModelId = image.id) }
            c.characterRepository.save(CharacterCard.create("Fixture").copy(id = "card"))
            c.chatRepository.createSession(ChatSession(id = "session", characterCardId = "card", title = "Fixture",
                createdAt = 1, updatedAt = 1))
        }
        suspend fun defaults(imageId: String?) {
            c.settingsRepository.updateAppSettings { it.copy(defaultImageModelId = imageId) }
            c.modelSettingsController.loadSettings(); c.modelSettingsController.loadModels()
            c.novelAiSettingsController.load()
            c.primaryChatController.refresh(); c.primaryChatController.selectSession("session")
        }
        suspend fun close() { c.close(); root.toFile().deleteRecursively() }
    }
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(); try { f.initialize(); block(f) } finally { f.close() }
    }
    private suspend fun ImageComposeScene.frames() { repeat(8) { render().close(); yield() }; delay(35); render().close() }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.has(text: String) = config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(text) } == true ||
        config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains(text) } == true
    private fun ImageComposeScene.has(text: String) = nodes().any { it.has(text) }
    private fun ImageComposeScene.exactText(text: String): SemanticsNode = nodes().single {
        it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == text
    }
    private fun ImageComposeScene.closedChoice(label: String): String = nodes().first { node ->
        node.config.getOrNull(SemanticsActions.OnClick) != null &&
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("$label · ") && it.text.endsWith("▾") } == true
    }.config[SemanticsProperties.Text].single().text
    private val zh = DesktopUiStrings(DesktopUiLanguage.ZH_CN)

    @Test fun `explicit and inherited global image defaults come from the effective resolver`() = runBlocking { fixture { f ->
        f.defaults(f.image.id)
        val explicit = f.c.modelSettingsController.state.value.effectiveModels
        assertEquals(f.chat.id, explicit.chat?.id); assertEquals(f.image.id, explicit.image?.id)
        assertEquals(f.image.displayName, explicit.image?.name)
        assertEquals("跟随默认对话模型 · 聊天甲", explicit.globalImageFallbackLabel(zh))
        f.defaults(null)
        val inherited = f.c.modelSettingsController.state.value.effectiveModels
        assertEquals(f.chat.id, inherited.image?.id)
        assertEquals("跟随默认对话模型 · 聊天甲", inherited.globalImageFallbackLabel(zh))
        assertEquals(f.chat.id, f.c.effectiveModelResolver.defaultImageModel()?.id)
    } }
    @Test fun `session null inherits concrete image default while explicit or stale choice stays visible`() = runBlocking(awt) { fixture { f ->
        f.defaults(f.image.id)
        suspend fun renderAndCheck(expected: String) {
            val state = f.c.primaryChatController.state.value
            val scene = ImageComposeScene(900, 850) {
                DesktopSessionSettingsBody(state, f.c.primaryChatController, "", DesktopSessionSettingsTab.IMAGES,
                    onBackgroundLibrary = {}, onOpenStudio = {}, onWorldBookQuery = {})
            }
            try { scene.frames(); assertTrue(scene.has(expected), expected) } finally { scene.close() }
        }
        val original = f.c.chatRepository.getSession("session")!!
        renderAndCheck("跟随全局生图辅助默认 · 设计乙")
        val basic = ImageComposeScene(900, 700) {
            DesktopSessionSettingsBody(f.c.primaryChatController.state.value, f.c.primaryChatController, "",
                DesktopSessionSettingsTab.BASIC, {}, {}, {})
        }
        try { basic.frames(); assertTrue(basic.has("跟随全局对话默认 · 聊天甲")) } finally { basic.close() }
        assertEquals(original, f.c.chatRepository.getSession("session"))
        f.c.primaryChatController.editSessionSettings { it.copy(imageModelId = f.chat.id) }
        renderAndCheck("Prompt 设计模型 · 聊天甲")
        f.c.primaryChatController.saveSessionSettings()
        assertEquals(f.chat.id, f.c.chatRepository.getSession("session")!!.imageModelId)
        f.c.primaryChatController.editSessionSettings { it.copy(imageModelId = "missing-model") }
        renderAndCheck("不可用 · missing-model")
        renderAndCheck("当前有效 · 设计乙")
        f.c.primaryChatController.saveSessionSettings()
        assertEquals("missing-model", f.c.chatRepository.getSession("session")!!.imageModelId)
    } }
    @Test fun `global settings show fallback name and keep unavailable explicit choice`() = runBlocking(awt) { fixture { f ->
        for ((id, expected) in listOf(f.image.id to "当前默认生图辅助 · 设计乙",
            null to "跟随默认对话模型 · 聊天甲", "missing-model" to "指定模型不可用")) {
            f.defaults(id)
            val before = f.c.settingsRepository.getAppSettings()
            val scene = ImageComposeScene(1000, 800) {
                DesktopNovelAiSettingsPanel(f.c.novelAiSettingsController, f.c.modelSettingsController,
                    f.c.modelSettingsController.state.value.availableChatModels)
            }
            try { repeat(3) { scene.frames() }; assertTrue(scene.has(expected), expected) }
            finally { scene.close() }
            assertEquals(before, f.c.settingsRepository.getAppSettings())
        }
    } }
    @Test fun `AI Design choice shows inherited concrete model and preserves explicit and stale IDs`() = runBlocking(awt) { fixture { f ->
        f.defaults(null)
        val before = f.c.novelAiStudioController.repository.loadDraft()
        val options = listOf(f.chat, f.image)
        for ((id, expected) in listOf(null to "跟随生图辅助默认 · 聊天甲",
            f.image.id to "设计模型 · 设计乙", "missing-model" to "指定模型不可用 · missing-model")) {
            val scene = ImageComposeScene(650, 180) {
                DesktopDesignModelChoice(id, options, f.c.modelSettingsController.state.value.effectiveModels.image!!.name) { fail("Viewing must not edit") }
            }
            try { scene.frames(); assertTrue(scene.has(expected), expected) } finally { scene.close() }
        }
        assertEquals(before, f.c.novelAiStudioController.repository.loadDraft())
    } }
    @Test fun `Models rows mark effective chat and image defaults including same-model case`() = runBlocking(awt) { fixture { f ->
        for ((id, same) in listOf(f.image.id to false, null to true)) {
            f.defaults(id)
            val state = f.c.modelSettingsController.state.value
            val before = f.c.settingsRepository.getAppSettings()
            val scene = ImageComposeScene(1000, 1100) {
                DesktopModelsPanel(state, f.c.modelSettingsController, launch = {})
            }
            try {
                scene.frames()
                val chatName = scene.exactText("聊天甲")
                val imageName = scene.exactText("设计乙")
                val chatBadge = scene.exactText(if (same) "默认对话模型 / 默认生图辅助" else "默认对话模型")
                val imageBadge = if (same) chatBadge else scene.exactText("默认生图辅助")
                assertTrue(chatBadge.boundsInRoot.center.y in chatName.boundsInRoot.top..chatName.boundsInRoot.bottom)
                assertTrue(imageBadge.boundsInRoot.center.y in
                    (if (same) chatName else imageName).boundsInRoot.top..(if (same) chatName else imageName).boundsInRoot.bottom)
                assertTrue(scene.nodes().none { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text ==
                    (if (same) "默认生图辅助" else "默认对话模型 / 默认生图辅助") })
                assertEquals(f.chat.id, state.effectiveModels.chat?.id)
                assertEquals(if (same) f.chat.id else f.image.id, state.effectiveModels.image?.id)
                assertTrue(scene.exactText("model-chat · 自定义 · chat").boundsInRoot.top > chatBadge.boundsInRoot.bottom)
            } finally { scene.close() }
            assertEquals(before, f.c.settingsRepository.getAppSettings())
        }
    } }
    @Test fun `large AI Design list shows selected null inheritance in closed trigger`() = runBlocking(awt) { fixture { f ->
        f.defaults(f.image.id)
        val many = (0..12).map { f.chat.copy(id = "model-$it", displayName = "Model $it") }
        val scene = ImageComposeScene(700, 180) {
            DesktopDesignModelChoice(null, many, f.image.displayName) { fail("Viewing must not select") }
        }
        try {
            scene.frames()
            assertEquals("设计模型 · 跟随生图辅助默认 · 设计乙 ▾", scene.closedChoice("设计模型"))
        } finally { scene.close() }
    } }
    @Test fun `large session model list shows selected null inheritance in closed trigger`() = runBlocking(awt) { fixture { f ->
        f.defaults(f.image.id)
        val many = (0..12).map { DesktopPrimaryChoice("model-$it", "Model $it") }
        val state = f.c.primaryChatController.state.value.copy(modelChoices = many)
        val scene = ImageComposeScene(900, 850) {
            DesktopSessionSettingsBody(state, f.c.primaryChatController, "", DesktopSessionSettingsTab.IMAGES, {}, {}, {})
        }
        try {
            scene.frames()
            assertEquals("Prompt 设计模型 · 跟随全局生图辅助默认 · 设计乙 ▾", scene.closedChoice("Prompt 设计模型"))
        } finally { scene.close() }
        assertNull(f.c.chatRepository.getSession("session")!!.imageModelId)
    } }
    @Test fun `global follow chat option previews chat default before it is selected`() = runBlocking(awt) { fixture { f ->
        f.defaults(f.image.id)
        assertEquals(f.image.id, f.c.effectiveModelResolver.defaultImageModel()?.id)
        val scene = ImageComposeScene(1000, 800) {
            DesktopNovelAiSettingsPanel(f.c.novelAiSettingsController, f.c.modelSettingsController,
                f.c.modelSettingsController.state.value.availableChatModels)
        }
        try {
            repeat(3) { scene.frames() }
            val follow = scene.nodes().first { it.has("跟随默认对话模型 · 聊天甲") && it.config.getOrNull(SemanticsActions.OnClick) != null }
            assertEquals(f.image.id, f.c.settingsRepository.getAppSettings().defaultImageModelId)
            follow.config[SemanticsActions.OnClick].action!!.invoke()
            withTimeout(5000) { while (f.c.settingsRepository.getAppSettings().defaultImageModelId != null) { scene.frames() } }
            assertEquals(f.chat.id, f.c.effectiveModelResolver.defaultImageModel()?.id)
        } finally { scene.close() }
    } }
    @Test fun `Models effective markers remain localized in English mode`() = runBlocking(awt) { fixture { f ->
        for (id in listOf(f.image.id, null)) {
            f.defaults(id)
            val state = f.c.modelSettingsController.state.value.copy(models =
                f.c.modelSettingsController.state.value.models.map { if (it.id == f.chat.id) it.copy(displayName = "Very long model name ".repeat(12)) else it })
            val before = f.c.settingsRepository.getAppSettings()
            val scene = ImageComposeScene(420, 1100) {
                CompositionLocalProvider(LocalDesktopUiStrings provides DesktopUiStrings(DesktopUiLanguage.EN)) {
                    DesktopModelsPanel(state, f.c.modelSettingsController, launch = {})
                }
            }
            try {
                scene.frames()
                val badge = scene.exactText(if (id == null) "Default chat model / Default image design" else "Default chat model")
                val name = scene.exactText("Very long model name ".repeat(12))
                assertTrue(badge.boundsInRoot.right <= 420f)
                assertTrue(name.boundsInRoot.right <= badge.boundsInRoot.left)
                if (id != null) scene.exactText("Default image design")
                assertFalse(scene.has("默认生图辅助"))
                assertFalse(scene.has("默认对话模型"))
            } finally { scene.close() }
            assertEquals(before, f.c.settingsRepository.getAppSettings())
        }
    } }

}
