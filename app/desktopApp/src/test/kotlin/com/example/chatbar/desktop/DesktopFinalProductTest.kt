@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.image.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopFinalProductTest {
    private val awt = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block) }
    private suspend fun ImageComposeScene.frames() { repeat(5) { render().close(); yield() } }
    private suspend fun ImageComposeScene.key(key: Key) { sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)); sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp)); frames() }
    private fun ImageComposeScene.shot(name: String) = render().use { image ->
        val output = Path.of("build/phase7-final-product-evidence/$name.png"); Files.createDirectories(output.parent)
        image.encodeToData()?.use { Files.write(output, it.bytes) }; Unit
    }

    @Test fun `IME edits retain selection composition through delayed echoes and recomposition`() {
        val editor = DesktopDesignInput("")
        val writes = mutableListOf<String>()
        editor.edit(TextFieldValue("zhong", TextRange(5), TextRange(0, 5)), writes::add)
        val current = TextFieldValue("中文", TextRange(2), TextRange(0, 2))
        editor.edit(current, writes::add)
        editor.echo("zhong"); assertEquals(current, editor.value)
        editor.echo("中文"); assertEquals(current, editor.value)
        editor.edit(current.copy(composition = null, selection = TextRange(1)), writes::add)
        editor.echo("中文"); assertEquals(TextRange(1), editor.value.selection); assertEquals(2, writes.size)
        editor.echo("external replacement"); assertNull(editor.value.composition)
        assertEquals(TextRange(20), editor.value.selection)
    }

    @Test fun `new conversation and branch editors do not share transient selection`() {
        val first = DesktopDesignInput("原需求")
        first.edit(TextFieldValue("编辑需求", TextRange(2), TextRange(0, 2))) {}
        val branch = DesktopDesignInput("编辑需求")
        val new = DesktopDesignInput("")
        assertNull(branch.value.composition); assertEquals(TextRange(4), branch.value.selection)
        assertEquals(TextFieldValue(""), new.value)
        assertNotNull(first.value.composition)
    }

    @Test fun `fake provider errors are classified with no provider body or secret cause`() = runBlocking {
        val model = ModelConfig("fake", "Fake", "https://fixture.invalid", "not-a-real-secret", "fixture", createdAt = 1)
        val fixtures = listOf(
            ProviderStreamEvent.Error("流式请求失败 (401): not-a-real-secret") to DesktopDesignFailure.AUTH,
            ProviderStreamEvent.Error("流式请求失败 (403): not-a-real-secret") to DesktopDesignFailure.AUTH,
            ProviderStreamEvent.Error("流式请求失败 (429): not-a-real-secret") to DesktopDesignFailure.PROVIDER,
            ProviderStreamEvent.Error("流式请求失败", cause = java.io.IOException("not-a-real-secret")) to DesktopDesignFailure.NETWORK,
            ProviderStreamEvent.Error("解析 SSE 数据失败: not-a-real-secret") to DesktopDesignFailure.RESPONSE)
        for ((event, expected) in fixtures) {
            val transport = DesktopNovelAiTextTransport { _, _ -> flowOf(event) }
            val failure = assertFailsWith<DesktopDesignException> { transport.streamText(NovelAiTextStage.GENERATE, listOf(ChatApiMessage.text("user", "fixture")), model).collect() }
            assertEquals(expected, failure.category); assertNull(failure.cause)
            assertFalse(failure.stackTraceToString().contains("not-a-real-secret"))
        }
        assertEquals(DesktopDesignFailure.CANCELLED, desktopDesignFailure(CancellationException("not-a-real-secret")))
        assertEquals(DesktopDesignFailure.RESPONSE, desktopDesignFailure(IllegalStateException("对话 AI 返回的生图 Prompt JSON 无法解析，原始内容: not-a-real-secret")))
        assertEquals(DesktopDesignFailure.UNKNOWN, desktopDesignFailure(IllegalArgumentException("not-a-real-secret")))
    }

    @Test fun `incomplete fake stream is response failure and successful stream keeps envelope`() = runBlocking {
        val model = ModelConfig("fake", "Fake", "https://fixture.invalid", "", "fixture", createdAt = 1)
        var messages: List<ChatApiMessage> = emptyList()
        val transport = DesktopNovelAiTextTransport { input, _ -> messages = input; flowOf(ProviderStreamEvent.ContentDelta("fixture"), ProviderStreamEvent.Completed(ProviderCompletionMetadata(finishReason = "stop"))) }
        val source = listOf(ChatApiMessage.text("user", "fixture input"))
        val result = transport.streamText(NovelAiTextStage.GENERATE, source, model).toList()
        assertEquals(com.example.chatbar.domain.prompt.AuxiliaryMessageAssembler.assemble(source, true), messages)
        assertEquals(StreamEvent.Done, result.last())
        val incomplete = DesktopNovelAiTextTransport { _, _ -> flowOf(ProviderStreamEvent.ContentDelta("partial")) }
        assertEquals(DesktopDesignFailure.RESPONSE, assertFailsWith<DesktopDesignException> { incomplete.streamText(NovelAiTextStage.GENERATE, source, model).collect() }.category)
    }

    @Test fun `structured design modules preserve order raw copy and apply semantics`() = runBlocking {
        val fixture = FinalProductDesignFixture()
        try {
            fixture.initialize(); val c = fixture.container.novelAiStudioController
            assertTrue(c.design(newConversation = true)); fixture.idle()
            val conversation = assertNotNull(c.designRepository.currentConversation())
            val reply = assertNotNull(conversation.turns.last().reply)
            val modules = desktopDesignModules(reply)
            assertEquals(reply.plan.baseCaption, modules.first().prompt)
            assertEquals(reply.plan.characterCaptions.map { it.prompt }, modules.drop(1).map { it.prompt })
            assertTrue(modules.size > 1)
            val before = c.repository.loadDraft()
            c.applyDesign(reply)
            val expected = before.applyDesignedPromptPlan(reply.plan, reply.targetImageModel)
            val applied = c.repository.loadDraft()
            assertEquals(expected.toPromptPlan(), applied.toPromptPlan())
            assertEquals(expected.characters.map { it.prompt to it.negativePrompt }, applied.characters.map { it.prompt to it.negativePrompt })
            assertTrue(applied.contentRevision > before.contentRevision)
            assertTrue(applied.promptContentRevision > before.promptContentRevision)
            assertEquals(expected.copy(characters = applied.characters, contentRevision = applied.contentRevision,
                promptContentRevision = applied.promptContentRevision, updatedAt = applied.updatedAt), applied)
            assertTrue(fixture.requests.isNotEmpty())
        } finally { fixture.close() }
    }

    @Test fun `design failure retry branch and regenerate keep context and never publish failed output`() = runBlocking {
        val fixture = FinalProductDesignFixture()
        try {
            fixture.initialize(); val c = fixture.container.novelAiStudioController
            fixture.failure = DesktopDesignFailure.AUTH
            val before = c.repository.loadDraft()
            c.design(newConversation = true, attach = true); fixture.idle()
            val conversation = assertNotNull(c.designRepository.currentConversation())
            val failed = conversation.turns.last()
            assertEquals(DesktopDesignFailure.AUTH.text, failed.error); assertNull(failed.reply)
            assertEquals(before, c.repository.loadDraft())
            fixture.failure = null
            c.retryDesign(conversation, failed); fixture.idle()
            val retried = assertNotNull(c.designRepository.currentConversation())
            assertEquals(conversation.id, retried.id); assertEquals(failed.id, retried.turns.last().id)
            assertEquals(conversation.designContext, retried.designContext)
            assertEquals(failed.attachedStudioPrompt, retried.turns.last().attachedStudioPrompt)
            assertEquals(NovelAiDesignTurnStatus.COMPLETED, retried.turns.last().status)
            c.retryDesign(retried, retried.turns.last(), editedText = "新的中文画面需求"); fixture.idle()
            val branch = assertNotNull(c.designRepository.currentConversation())
            assertNotEquals(retried.id, branch.id); assertEquals("新的中文画面需求", branch.turns.last().userText)
            c.retryDesign(branch, branch.turns.last(), regenerate = true); fixture.idle()
            assertEquals(before, c.repository.loadDraft())
        } finally { fixture.close() }
    }

    @Test fun `cost labels project shared estimator values including encoding and allowance`() {
        assertEquals("生成免费", desktopGenerateLabel(false, true, NovelAiGenerationCost(NovelAiGenerationChargeKind.FREE)))
        assertEquals("生成免费", desktopGenerateLabel(false, true, NovelAiGenerationCost(NovelAiGenerationChargeKind.V5_ALLOWANCE)))
        assertEquals("生成消耗 15 Anlas（含编码 2）（含额外 Vibe 3）", desktopGenerateLabel(false, true, NovelAiGenerationCost(NovelAiGenerationChargeKind.ANLAS, 15, 2, 3)))
        assertTrue(desktopGenerateLabel(false, false, null).contains("不可用"))
        assertEquals("停止当前任务 · 2/4", desktopGenerateLabel(true, true, null, "2/4"))
        assertEquals(.5f, DesktopTokenProgress(50, 100).fraction)
        assertTrue(DesktopTokenProgress(90, 100).warning)
        assertTrue(DesktopTokenProgress(110, 100).exceeded)
        assertEquals(1f, DesktopTokenProgress(110, 100).fraction)
    }

    @Test fun `filmstrip preserves current batch priority and history order without duplicate authority`() {
        val history = listOf(NovelAiGenerationHistoryEntry(images = listOf(NovelAiGenerationHistoryImage("b", 2), NovelAiGenerationHistoryImage("c", 3))))
        assertEquals(listOf("a", "b", "c"), desktopStudioFilmstrip(listOf("a", "b"), history))
        assertEquals(listOf("b", "c"), desktopStudioFilmstrip(emptyList(), history))
        assertTrue(desktopResultHeight(700f, 1000f) > desktopResultHeight(400f, 600f))
    }

    @Test fun `tab keyboard navigation preserves externally owned dirty draft`() = runBlocking(awt) {
        var selected by mutableStateOf(DesktopSessionSettingsTab.BASIC)
        val draft = com.example.chatbar.data.local.entity.ChatSession(id = "session", characterCardId = "fixture", title = "fixture", createdAt = 1, updatedAt = 1)
        val before = Json.encodeToString(com.example.chatbar.data.local.entity.ChatSession.serializer(), draft)
        val focus = FocusRequester()
        val scene = ImageComposeScene(700, 160) { Box(Modifier.focusRequester(focus)) { DesktopSessionSettingsTabs(selected) { selected = it } } }
        try {
            scene.frames(); focus.requestFocus(); scene.key(Key.DirectionRight); scene.key(Key.DirectionRight)
            assertEquals(DesktopSessionSettingsTab.IMAGES, selected)
            assertEquals(before, Json.encodeToString(com.example.chatbar.data.local.entity.ChatSession.serializer(), draft))
            scene.shot("session-tabs")
        } finally { scene.close() }
    }

    @Test fun `module copy invokes exact payload and renders structured card`() = runBlocking(awt) {
        val reply = NovelAiDesignReply(NovelAiPromptPlan(baseCaption = "sunlit library", characterCaptions = listOf(NovelAiCharacterCaption("adult reader", DesignedCharacterCenter(.5f, .5f))) ))
        var copied = ""
        val focus = FocusRequester()
        val scene = ImageComposeScene(800, 420) { Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).focusRequester(focus)) { DesktopDesignResult(reply) { copied = it } } }
        try { scene.frames(); focus.requestFocus(); scene.key(Key.Enter); assertEquals("sunlit library", copied)
            // Selectable module text is also focusable; traverse it before the next module Copy.
            scene.key(Key.Tab); scene.key(Key.Tab); scene.key(Key.Enter); assertEquals("adult reader", copied); scene.shot("structured-design") }
        finally { scene.close() }
    }

    @Test fun `pending attachments have zero empty height large previews and hover removal`() = runBlocking(awt) {
        val bytes = DesktopImageEditing.png(java.awt.image.BufferedImage(24, 24, java.awt.image.BufferedImage.TYPE_INT_RGB))
        var images by mutableStateOf(listOf(DesktopPendingImage("one", bytes), DesktopPendingImage("two", bytes)))
        var preview = ""
        var height = -1
        val scene = ImageComposeScene(360, 220) { Column(Modifier.onSizeChanged { height = it.height }) {
            DesktopPendingImageStrip(images, true, { id -> images = images.filterNot { it.id == id } }, {}, false, { preview = it.id })
        } }
        suspend fun click(x: Float, y: Float) { scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), button = PointerButton.Primary); scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), button = PointerButton.Primary); scene.frames() }
        try {
            scene.frames(); assertEquals(112, height)
            click(50f, 60f); assertEquals("one", preview)
            scene.sendPointerEvent(PointerEventType.Move, Offset(90f, 15f)); scene.frames(); scene.shot("attachments-hover")
            click(98f, 14f); assertEquals(listOf("two"), images.map { it.id })
            images = emptyList(); scene.frames(); assertEquals(0, height)
        } finally { scene.close() }
    }

    @Test fun `session tab changes preserve draft and background immediate save survives cancel`() = runBlocking(awt) {
        val root = Files.createTempDirectory("p7-settings-tabs-")
        val file = root.resolve("background.png")
        Files.write(file, DesktopImageEditing.png(java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB)))
        val picker = object : DesktopFilePicker {
            override fun pickOpenFile(type: DesktopFileType) = file
            override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = null
        }
        val c = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
            secretStoreFactory = { com.example.chatbar.desktop.security.InMemoryDesktopSecretStore() }, filePicker = picker)
        var tab by mutableStateOf(DesktopSessionSettingsTab.BASIC)
        try {
            c.characterRepository.save(com.example.chatbar.data.local.entity.CharacterCard.create("Fixture").copy(id = "card"))
            val controller = c.primaryChatController
            c.chatRepository.createSession(com.example.chatbar.data.local.entity.ChatSession(id = "session", characterCardId = "card", title = "Fixture", createdAt = 1, updatedAt = 1))
            controller.refresh(); controller.selectSession("session")
            val id = assertNotNull(controller.state.value.selectedSession).id
            controller.editSessionSettings { it.copy(replyLanguage = "日本語", imagePromptPreference = "draft image requirement") }
            val scene = ImageComposeScene(780, 600) {
                val state by controller.state.collectAsState()
                Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { DesktopSessionSettingsTabs(tab) { tab = it }; DesktopSessionSettingsBody(state, controller, "", tab, {}, {}) {} }
            }
            try {
                scene.frames(); tab = DesktopSessionSettingsTab.IMAGES; scene.frames()
                controller.chooseSessionBackground(); controller.setBackgroundOpacity(.35f)
                val background = assertNotNull(c.chatRepository.getSession(id)?.chatBackground)
                assertTrue(controller.state.value.sessionSettingsDirty)
                scene.shot("session-images")
                tab = DesktopSessionSettingsTab.CONTEXT; scene.frames()
                assertEquals("日本語", controller.state.value.sessionSettingsDraft?.replyLanguage)
                controller.discardSessionSettings()
                assertEquals(background, c.chatRepository.getSession(id)?.chatBackground)
                assertEquals(background, controller.state.value.sessionSettingsDraft?.chatBackground)
                assertEquals(.35f, controller.state.value.backgroundOpacity)
                assertNotEquals("draft image requirement", controller.state.value.sessionSettingsDraft?.imagePromptPreference)
                controller.editSessionSettings { it.copy(imagePromptPreference = "saved image requirement") }
                tab = DesktopSessionSettingsTab.BASIC; scene.frames(); controller.saveSessionSettings()
                assertEquals("saved image requirement", c.chatRepository.getSession(id)?.imagePromptPreference)
            } finally { scene.close() }
        } finally { c.close(); root.toFile().deleteRecursively() }
    }

    @Test fun `refusal cancellation and empty replies retain shared terminal failure types`() = runBlocking {
        val model = ModelConfig("fake", "Fake", "https://fixture.invalid", "", "fixture", createdAt = 1)
        val refused = DesktopNovelAiTextTransport { _, _ -> flowOf(ProviderStreamEvent.Completed(ProviderCompletionMetadata(refused = true))) }
        assertFailsWith<com.example.chatbar.domain.prompt.AiTaskRefusalException> { refused.streamText(NovelAiTextStage.PLAN, listOf(ChatApiMessage.text("user", "fixture")), model).collect() }
        val empty = DesktopNovelAiTextTransport { _, _ -> flowOf(ProviderStreamEvent.Completed(ProviderCompletionMetadata(finishReason = "stop"))) }
        assertFailsWith<com.example.chatbar.domain.prompt.AiTaskEmptyResponseException> { empty.streamText(NovelAiTextStage.PLAN, listOf(ChatApiMessage.text("user", "fixture")), model).collect() }
        val cancelled = DesktopNovelAiTextTransport { _, _ -> flow { throw CancellationException() } }
        assertFailsWith<CancellationException> { cancelled.streamText(NovelAiTextStage.PLAN, listOf(ChatApiMessage.text("user", "fixture")), model).collect() }
        Unit
    }

    @Test fun `unknown selected design model shows configuration category without starting a task`() = runBlocking {
        val fixture = FinalProductDesignFixture()
        try {
            fixture.initialize(); val c = fixture.container.novelAiStudioController
            c.edit { it.copy(aiDesignModelId = "missing") }
            assertFalse(c.design()); assertEquals(DesktopDesignFailure.MODEL.text, c.state.value.status)
            assertTrue(c.taskEntries.value.isEmpty()); assertTrue(fixture.requests.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun `wide narrow Studio scenes show adaptive local result and selectable scrollable filmstrip`() = runBlocking(awt) {
        val fixture = FinalProductDesignFixture()
        try {
            fixture.initialize(); fixture.seedLocalImages()
            val c = fixture.container.novelAiStudioController
            for ((width, height) in listOf(1280 to 900, 1600 to 1000, 700 to 700)) {
                val scene = ImageComposeScene(width, height) { DesktopNovelAiStudioPanel(c) }
                try { repeat(8) { delay(30); scene.frames() }; scene.shot("studio-$width") } finally { scene.close() }
            }
            val paths = c.state.value.results
            var selected by mutableStateOf(paths.first())
            val scene = ImageComposeScene(280, 130) { StudioFilmstrip(paths, selected, c.resources::readBytes) { selected = it } }
            try {
                scene.frames()
                scene.sendPointerEvent(PointerEventType.Press, Offset(132f, 42f), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, Offset(132f, 42f), button = PointerButton.Primary); scene.frames()
                assertEquals(paths[1], selected)
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(140f, 40f), scrollDelta = Offset(0f, 3f)); scene.frames()
                scene.sendPointerEvent(PointerEventType.Press, Offset(45f, 42f), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, Offset(45f, 42f), button = PointerButton.Primary); scene.frames()
                assertNotEquals(paths.first(), selected)
                scene.shot("filmstrip-scrolled")
            } finally { scene.close() }
            assertTrue(c.taskEntries.value.isEmpty())
        } finally { fixture.close() }
    }
}
