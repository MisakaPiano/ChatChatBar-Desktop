@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
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
    @Test fun `official sample UI plays only on explicit click without text TTS or binding`() = runBlocking(awt) {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "audio/mpeg")
            .setBody(okio.Buffer().write(fishPreviewMp3())))
        server.start()
        val credentials = DesktopFishAudioCredentials(com.example.chatbar.desktop.security.InMemoryDesktopSecretStore())
        val output = PreviewOutputFake()
        val runtime = DesktopFishPreviewRuntime(DesktopFishPreviewTransport(credentials, okhttp3.OkHttpClient(), server.url("/").toString().trimEnd('/')),
            output, samples = DesktopFishSampleTransport(okhttp3.OkHttpClient()) { url -> server.url(url.encodedPath) })
        val preview = runtime.openSession()
        val scope = CoroutineScope(SupervisorJob() + awt)
        val voices = listOf(FishAudioModel("official", "Official", samples = listOf(FishAudioSample(audio = syntheticOfficialSample))),
            FishAudioModel("empty", "No Sample"),
            FishAudioModel("unsafe", "Unsupported", samples = listOf(FishAudioSample(audio = "https://untrusted.example/sample.mp3"))))
        val picker = DesktopFishPickerController(scope) { FishAudioModelPage(items = voices, total = voices.size) }
        var selected = false
        picker.search()
        val scene = ImageComposeScene(1000, 1500) {
            DesktopFishPickerContent(picker, true, onClose = { picker.close(); preview.requestClose() }, preview = preview) { selected = true }
        }
        try {
            scene.until { scene.nodes().any { it.label("试听官方样本 · Official") } }
            dumpFishActionSemantics(scene.nodes())
            for (label in listOf("试听官方样本 · No Sample", "试听官方样本 · Unsupported"))
                assertTrue(scene.nodes().single { it.label(label) }.config.contains(SemanticsProperties.Disabled))
            assertFalse(scene.nodes().any { it.label("生成 / 重播免费预览") })
            assertEquals(0, server.requestCount)
            scene.tap("试听官方样本 · Official")
            scene.until { preview.state.value.playing }
            val request = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)!!
            assertEquals("GET", request.method); assertNull(request.getHeader("Authorization"))
            assertFalse(selected); assertEquals(voices.first().samples.single().audio, syntheticOfficialSample)
            scene.tap("停止预览")
            scene.until { !preview.state.value.playing && !preview.state.value.busy }
            assertFalse(selected); assertFalse(output.playing)
        } finally { scene.close(); picker.close(); runtime.closeAndDrain(); scope.cancel(); server.close() }
    }

    @Test fun `free preview UI requires explicit text and click and never selects a voice`() = runBlocking(awt) {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "audio/mpeg")
            .setBody(okio.Buffer().write(fishPreviewMp3())))
        server.start()
        val credentials = DesktopFishAudioCredentials(com.example.chatbar.desktop.security.InMemoryDesktopSecretStore())
        credentials.save("synthetic-preview-only")
        val output = PreviewOutputFake()
        val runtime = DesktopFishPreviewRuntime(DesktopFishPreviewTransport(credentials, okhttp3.OkHttpClient(), server.url("/").toString().trimEnd('/')), output)
        val preview = runtime.openSession()
        val scope = CoroutineScope(SupervisorJob() + awt)
        val voice = FishAudioModel(id = "fixture", title = "Fixture")
        val picker = DesktopFishPickerController(scope) { FishAudioModelPage(items = listOf(voice)) }
        var selected = false
        picker.search()
        val scene = ImageComposeScene(800, 1100) {
            DesktopFishPickerContent(picker, true, onClose = { picker.close(); preview.requestClose() }, preview = preview) { selected = true }
        }
        try {
            scene.until { scene.nodes().any { it.label("预览目标 · Fixture") } }
            assertFalse(scene.nodes().any { it.label("免费预览文本（1–120 字）") })
            scene.tap("预览目标 · Fixture")
            scene.tap("免费文本生成")
            scene.until { scene.nodes().any { it.label("生成 / 重播免费预览") } }
            val button = scene.nodes().first { it.label("生成 / 重播免费预览") }
            dumpFishActionSemantics(scene.nodes())
            assertTrue(button.config.contains(SemanticsProperties.Disabled))
            assertEquals(0, server.requestCount)
            scene.nodes().single { it.label("免费预览文本（1–120 字）") && it.config.contains(SemanticsActions.SetText) }
                .config[SemanticsActions.SetText].action!!(AnnotatedString("合成测试"))
            assertFalse(selected); assertEquals(0, server.requestCount)
            scene.tap("生成 / 重播免费预览")
            scene.until { preview.state.value.playing }
            assertFalse(selected); assertEquals(1, server.requestCount)
            scene.tap("停止预览")
            scene.until { !preview.state.value.playing && !preview.state.value.busy }
            assertFalse(output.playing)
        } finally { scene.close(); picker.close(); runtime.closeAndDrain(); scope.cancel(); server.close() }
    }
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

    @Test fun `management voice subsection matches appearance heading and remains operable in narrow layout`() = runBlocking(awt) {
        fishFixture { _, container ->
            container.settingsRepository.saveAppSettings(AppSettings(voiceTagModelId = "stale-ref"))
            val controller = container.modelSettingsController
            val transfer = container.createTypedTransferController()
            val templates = container.createModelTemplateTransferController()
            val management = container.createManagementController(transfer)
            val scope = CoroutineScope(SupervisorJob() + awt)
            val backup = DesktopAutomaticBackupSettingsController(container.automaticBackupRuntime, scope)
            val scene = ImageComposeScene(1200, 900, coroutineContext = awt) {
                DesktopManagePanel(transfer, templates, management, controller, backup,
                    container.characterEditorController, container.formatCardEditorController,
                    container.worldBookEditorController, container.uiLanguageController,
                    container.appearanceController, container.connectionTestController,
                    container.novelAiSettingsController)
            }
            // ImageComposeScene.render() defaults to timestamp zero. Advance frames explicitly so
            // wheel animations progress; coroutine completion alone is not evidence of scrolling.
            var frameNanos = 0L
            suspend fun frame() {
                frameNanos += 16_666_667L
                scene.render(frameNanos).close()
                yield()
                delay(10)
            }
            suspend fun awaitFrame(check: () -> Boolean) = withTimeout(5_000) {
                do { frame() } while (!check())
            }
            fun node(label: String) = scene.nodes().first { it.label(label) }
            suspend fun pointerClick(label: String) {
                val point = node(label).boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
                frame()
            }
            try {
                // Startup refresh intentionally blocks section changes while management
                // or transfers are busy; a visible tab is not yet click admission.
                awaitFrame {
                    scene.nodes().any { it.label("设置") } && !management.state.value.busy &&
                        !transfer.state.value.busy && !templates.state.value.busy
                }
                pointerClick("设置")
                awaitFrame { scene.nodes().any { it.label("语音与朗读") } }
                fun layout(node: SemanticsNode): TextLayoutResult {
                    val results = mutableListOf<TextLayoutResult>()
                    assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
                    return results.single()
                }
                // Section labels are also navigation buttons now. Inspect the actual
                // heading after entering each retained pane, rather than the tab text.
                fun heading(label: String) = scene.nodes().single { it.label(label) &&
                    generateSequence(it) { parent -> parent.parent }.none { parent ->
                        parent.config.contains(SemanticsActions.OnClick)
                    } }
                pointerClick("外观与显示")
                awaitFrame { scene.nodes().any { it.label("聊天背景透明度（全局）") } }
                val appearance = heading("外观与显示")
                val appearanceStyle = layout(appearance).layoutInput.style
                pointerClick("语音与朗读")
                awaitFrame { scene.nodes().any { it.label("Fish Audio") } }
                val voice = heading("语音与朗读")
                val voiceStyle = layout(voice).layoutInput.style
                assertEquals(19.sp, voiceStyle.fontSize)
                assertEquals(FontWeight.SemiBold, voiceStyle.fontWeight)
                assertEquals(appearanceStyle, voiceStyle)
                assertEquals(2, scene.nodes().count { it.label("语音与朗读") })
                assertEquals(1, scene.nodes().count { it.label("Fish Audio") })
                assertFalse(scene.nodes().any { it.label("语音与朗读 · Fish Audio") })
                for (label in listOf("设置 / 替换 Fish 密钥", "清除 Fish 密钥",
                    "Fish TTS 模型 · s2.1-pro-free ▾", "语音翻译 / 标签模型 · 不可用 · stale-ref ▾",
                    "已选模型不可用；不会自动替换", "保存全局设置", "放弃设置更改")) {
                    assertTrue(scene.nodes().any { it.label(label) }, "Missing voice control: $label")
                }
                scene.constraints = Constraints.fixed(360, 700)
                frame()
                fun range() = scene.nodes().single {
                    it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
                }.config[SemanticsProperties.VerticalScrollAxisRange]
                val initialScroll = range().value()
                assertTrue(range().maxValue() > 0f)
                suspend fun reveal(label: String) = withTimeout(5_000) {
                    var wheels = 0
                    while (true) {
                        val target = node(label)
                        val top = target.positionInRoot.y
                        val bottom = top + target.size.height
                        val viewport = scene.nodes().single {
                            it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
                        }.boundsInRoot
                        if (top >= viewport.top && bottom <= viewport.bottom) {
                            val bounds = target.boundsInRoot
                            assertTrue(bounds.height > 0f && bounds.top >= 0f && bounds.bottom <= 700f,
                                "$label not visible: $bounds")
                            assertTrue(bounds.left >= 0f && bounds.right <= 360f,
                                "$label exceeds narrow viewport: $bounds")
                            assertEquals(target.size.height.toFloat(), bounds.height, 1f,
                                "$label is vertically clipped")
                            println("R1A_WHEEL label=$label wheels=$wheels scroll=${range().value()} max=${range().maxValue()} bounds=$bounds")
                            break
                        }
                        val before = range().value()
                        val direction = if (top < viewport.top) -2f else 2f
                        val point = Offset(viewport.right - 10f, viewport.center.y)
                        scene.sendPointerEvent(PointerEventType.Move, point)
                        scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, direction))
                        wheels++
                        awaitFrame { range().value() != before }
                    }
                }
                reveal("保存全局设置")
                assertTrue(range().value() > initialScroll, "Wheel did not change the actual scroll offset")
                reveal("听书模式：关闭")
                pointerClick("听书模式：关闭")
                awaitFrame { controller.state.value.chatDefaultsDirty }
                assertFalse(container.settingsRepository.getAppSettings().audiobookModeEnabled)
                reveal("放弃设置更改")
                pointerClick("放弃设置更改")
                awaitFrame { !controller.state.value.chatDefaultsDirty }
                assertFalse(controller.state.value.settings!!.audiobookModeEnabled)
                reveal("听书模式：关闭")
                pointerClick("听书模式：关闭")
                awaitFrame { controller.state.value.chatDefaultsDirty }
                reveal("保存全局设置")
                pointerClick("保存全局设置")
                awaitFrame { !controller.state.value.chatDefaultsDirty }
                val saved = container.settingsRepository.getAppSettings()
                assertTrue(saved.audiobookModeEnabled)
                assertEquals("stale-ref", saved.voiceTagModelId)
                assertEquals(FishAudioTtsModels.S2_1_PRO_FREE, saved.fishAudioTtsModelId)
            } finally { scene.close(); scope.cancel() }
        }
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
        val scene = ImageComposeScene(740, 800) { DesktopFishPickerContent(picker, true, { closed = true }, onUse = chosen::add) }
        try {
            scene.until { scene.nodes().any { it.label("查询 / 重试") } }
            assertTrue(requests.isEmpty()); assertTrue(chosen.isEmpty())
            scene.tap("我的音色")
            scene.until { scene.nodes().any { it.label("使用音色 · Fixture") } }
            assertEquals(FishAudioLibrary.MINE, requests.single().library)
            assertTrue(chosen.isEmpty())
            assertTrue(scene.nodes().any { it.label("Fish 紧凑单列") })
            scene.tap("使用音色 · Fixture"); scene.until { chosen.size == 1 }
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
