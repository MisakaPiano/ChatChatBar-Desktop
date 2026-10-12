@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.voice.*
import kotlinx.coroutines.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopFishPresentationTest {
    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.label(s: String) = config.getOrNull(SemanticsProperties.ContentDescription)?.contains(s) == true ||
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == s } == true
    private suspend fun ImageComposeScene.until(check: () -> Boolean) = withTimeout(5_000) {
        do { render().close(); yield(); delay(10) } while (!check())
    }
    private suspend fun ImageComposeScene.tap(label: String) {
        until { nodes().any { it.label(label) } }
        val point = nodes().first { it.label(label) }.boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
        render().close(); yield()
    }

    @Test fun `picker real pointer query selects only explicit result with no preview`() = runBlocking(awt) {
        val requests = mutableListOf<FishAudioModelQuery>()
        val scope = CoroutineScope(SupervisorJob() + awt)
        val picker = DesktopFishPickerController(scope) { query ->
            requests.add(query)
            FishAudioModelPage(total = 1, items = listOf(FishAudioModel("ref", "Fixture", author = FishAudioAuthor(nickname = "Author"),
                visibility = "private", languages = listOf("zh"))), hasMore = false)
        }
        val chosen = mutableListOf<FishAudioModel>()
        var closed = false
        val scene = ImageComposeScene(740, 800) { DesktopFishPickerContent(picker, true, { closed = true }, chosen::add) }
        try {
            scene.until { scene.nodes().any { it.label("查询 / 重试") } }
            assertTrue(requests.isEmpty()); assertTrue(chosen.isEmpty())
            scene.tap("我的音色")
            scene.until { scene.nodes().any { it.label("选择音色 · Fixture") } }
            assertEquals(FishAudioLibrary.MINE, requests.single().library)
            assertTrue(chosen.isEmpty())
            assertTrue(scene.nodes().any { it.label("Fish Audio 音色库 · 试听尚未接入（PENDING）") })
            scene.tap("选择音色 · Fixture"); scene.until { chosen.size == 1 }
            assertEquals("ref", chosen.single().toBinding().referenceId)
            scene.tap("取消 / 关闭音色库"); assertTrue(closed)
        } finally { scene.close(); picker.close(); scope.cancel() }
    }

    @Test fun `session UI preserves three state choice and language spaces during incremental editing`() = runBlocking(awt) {
        var draft by mutableStateOf(ChatSession.create("card", "Fixture"))
        val scene = ImageComposeScene(600, 400) {
            Column { DesktopFishSessionSettings(draft) { change -> draft = change(draft) } }
        }
        try {
            scene.until { scene.nodes().any { it.label("会话听书模式 · 跟随全局 ▾") } }
            scene.tap("会话听书模式 · 跟随全局 ▾"); scene.tap("开启"); scene.until { draft.audiobookModeEnabled == true }
            val field = scene.nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }
            field.config[SemanticsActions.SetText].action!!(AnnotatedString("日语"))
            scene.until { draft.voiceLanguage == "日语" }
            field.config[SemanticsActions.SetText].action!!(AnnotatedString(""))
            scene.until { draft.voiceLanguage == "" }
            val language = "Brazilian Portuguese"
            language.forEachIndexed { index, character ->
                val currentField = scene.nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }
                // Feed each edit separately, deriving it from the actual current text: a swallowed
                // separator cannot be concealed by submitting the complete phrase in one SetText.
                currentField.config[SemanticsActions.SetText].action!!(AnnotatedString(draft.voiceLanguage.orEmpty() + character))
                scene.until { draft.voiceLanguage == language.take(index + 1) }
            }
            scene.nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }
                .config[SemanticsActions.SetText].action!!(AnnotatedString(" "))
            scene.until { draft.voiceLanguage == " " }
            // Blank-to-null belongs to the controller's save/comparison boundary, covered separately.
        } finally { scene.close() }
    }

    @Test fun `voice selector offers auxiliary only model by name and retains null follow session choice`() = runBlocking(awt) {
        fishFixture { _, container ->
            val model = ModelConfig.create("Auxiliary fixture", "https://fixture.invalid", "synthetic-key", "fixture")
                .copy(selectableForChat = false)
            container.modelRepository.saveModel(model)
            val controller = container.modelSettingsController
            controller.loadSettings()
            assertTrue(controller.state.value.availableChatModels.none { it.id == model.id })
            val scene = ImageComposeScene(800, 850) {
                val state by controller.state.collectAsState()
                Column { DesktopFishGlobalSettings(state, controller) }
            }
            try {
                scene.tap("语音翻译 / 标签模型 · 当前会话对话模型 ▾")
                scene.tap("Auxiliary fixture")
                scene.until { controller.state.value.settings!!.voiceTagModelId == model.id }
                assertTrue(scene.nodes().any { it.label("语音翻译 / 标签模型 · Auxiliary fixture ▾") })
                assertFalse(scene.nodes().any { it.label("已选模型不可用；不会自动替换") })
                scene.tap("语音翻译 / 标签模型 · Auxiliary fixture ▾")
                scene.tap("当前会话对话模型")
                scene.until { controller.state.value.settings!!.voiceTagModelId == null }
            } finally { scene.close() }
        }
    }

    @Test fun `global UI exposes stale explicit model without fallback and changes remain draft`() = runBlocking(awt) {
        fishFixture { _, container ->
            container.settingsRepository.saveAppSettings(AppSettings(voiceTagModelId = "stale-ref"))
            val controller = container.modelSettingsController
            controller.loadSettings()
            val scene = ImageComposeScene(800, 850) {
                val state by controller.state.collectAsState()
                Column { DesktopFishGlobalSettings(state, controller) }
            }
            try {
                scene.until { scene.nodes().any { it.label("语音翻译 / 标签模型 · 不可用 · stale-ref ▾") } }
                scene.tap("听书模式：关闭")
                scene.until { controller.state.value.chatDefaultsDirty }
                assertFalse(container.settingsRepository.getAppSettings().audiobookModeEnabled)
                scene.tap("保存全局设置")
                scene.until { !controller.state.value.chatDefaultsDirty }
                assertTrue(container.settingsRepository.getAppSettings().audiobookModeEnabled)
                assertEquals("stale-ref", container.settingsRepository.getAppSettings().voiceTagModelId)
            } finally { scene.close() }
        }
    }

    @Test fun `structured and avatar book fields display existing snapshot without auto query or binding`() = runBlocking(awt) {
        fishFixture { _, container ->
            val controller = container.characterEditorController
            for (mode in CharacterEditMode.entries) {
                controller.openNew()
                val person = CharacterInfo.create("Alice").copy(fishAudioVoice = FishAudioVoiceBinding("old", "Old voice"))
                controller.edit { it.copy(editMode = mode, characters = listOf(person)) }
                val before = controller.state.value.card
                val scene = ImageComposeScene(700, 450) { Column { DesktopFishBindingField(person, controller) } }
                try {
                    scene.until { scene.nodes().any { it.label("Fish 音色 · Old voice") } }
                    assertEquals(before, controller.state.value.card)
                    scene.tap("移除音色绑定")
                    assertNull(controller.state.value.card!!.characters.single().fishAudioVoice)
                    assertEquals(person.id, controller.state.value.card!!.characters.single().id)
                } finally { scene.close(); controller.discard() }
            }
        }
    }
}
