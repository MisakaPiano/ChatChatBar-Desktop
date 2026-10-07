@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.NovelAiStudioStateRepository
import com.example.chatbar.domain.image.*
import java.awt.event.WindowEvent
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopDesignParityTest {
    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private suspend fun ImageComposeScene.frames() {
        repeat(6) { render(System.nanoTime()).close(); yield() }
        delay(35); render(System.nanoTime()).close()
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.matches(text: String) = config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true ||
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    private suspend fun ImageComposeScene.click(text: String) {
        val node = nodes().first { it.matches(text) && it.config.getOrNull(SemanticsActions.OnClick) != null }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); frames()
    }
    private fun files(f: FinalProductDesignFixture) = Files.walk(f.container.appDataRoot.resolve("entities")).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { it.toString() to (Files.readString(it) to Files.getLastModifiedTime(it)) }
    }
    private suspend fun fixture(block: suspend (FinalProductDesignFixture, DesktopNovelAiStudioController) -> Unit) {
        val f = FinalProductDesignFixture()
        try { f.initialize(); block(f, f.container.novelAiStudioController) } finally { f.close() }
    }

    @Test fun `extra requirement persists in Studio and new Design snapshots it while old context is immutable`() = runBlocking { fixture { f, c ->
        f.container.settingsRepository.updateAppSettings { it.copy(imagePromptToolPreference = "APP_OTHER_OWNER_SENTINEL") }
        val appBefore = f.container.settingsRepository.getAppSettings()
        c.setDesignRequirement("DRAFT_REQUIREMENT_ONE")
        assertEquals("DRAFT_REQUIREMENT_ONE", c.designRequirement())
        assertEquals("DRAFT_REQUIREMENT_ONE", NovelAiStudioStateRepository(JsonFileStorage(f.container.appDataRoot)).loadDraft().extraRequirement)
        assertEquals(appBefore, f.container.settingsRepository.getAppSettings())
        assertTrue(c.design("First explicit turn")); f.idle()
        val first = assertNotNull(c.designRepository.currentConversation())
        assertEquals("DRAFT_REQUIREMENT_ONE", first.designContext.finalPromptRequirement)
        assertTrue(f.requests.toString().contains("DRAFT_REQUIREMENT_ONE"))
        assertFalse(f.requests.toString().contains("APP_OTHER_OWNER_SENTINEL"))
        c.setDesignRequirement("DRAFT_REQUIREMENT_TWO")
        f.requests.clear()
        assertTrue(c.design("Continuation in saved context")); f.idle()
        assertEquals(first.designContext, c.designRepository.currentConversation()!!.designContext)
        assertTrue(f.requests.toString().contains("DRAFT_REQUIREMENT_ONE"))
        assertFalse(f.requests.toString().contains("DRAFT_REQUIREMENT_TWO"))
        assertFalse(f.requests.toString().contains("APP_OTHER_OWNER_SENTINEL"))
        c.newDesignConversation(); assertTrue(c.design("New context")); f.idle()
        assertEquals("DRAFT_REQUIREMENT_TWO", c.designRepository.currentConversation()!!.designContext.finalPromptRequirement)
        assertEquals(first.designContext, c.designRepository.conversations.value.first { it.id == first.id }.designContext)
        assertEquals(appBefore, f.container.settingsRepository.getAppSettings())
    } }

    @Test fun `imported image reverse Prompt uses only draft requirement through the fake Designer`() = runBlocking { fixture { f, c ->
        val model = assertNotNull(f.container.modelRepository.getModel("local-design"))
        f.container.modelRepository.saveModel(model.copy(isMultimodal = true))
        f.container.settingsRepository.updateAppSettings { it.copy(imagePromptToolPreference = "APP_REVERSE_DECOY") }
        c.setDesignRequirement("DRAFT_REVERSE_AUTHORITY")
        val draft = c.repository.loadDraft(); val app = f.container.settingsRepository.getAppSettings()
        val bytes = DesktopImageEditing.png(java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB))
        assertTrue(c.reverseImage(bytes)); f.idle()
        assertNotNull(c.state.value.reverseCandidate)
        assertTrue(f.requests.toString().contains("DRAFT_REVERSE_AUTHORITY"))
        assertFalse(f.requests.toString().contains("APP_REVERSE_DECOY"))
        assertEquals(draft, c.repository.loadDraft()); assertEquals(app, f.container.settingsRepository.getAppSettings())
    } }

    @Test fun `leaving transient new clears input attachment progress error and restores current without writes`() = runBlocking { fixture { f, c ->
        assertTrue(c.design("Conversation A")); f.idle()
        val current = assertNotNull(c.designRepository.currentConversation())
        assertTrue(c.state.value.designProgress.stage.isNotBlank())
        c.newDesignConversation(); c.editDesignInput("unsent"); c.attachDesignPrompt(true)
        assertFalse(c.design(" ")) // Transient preflight error, not a persisted turn.
        assertTrue(c.state.value.status.isNotBlank())
        val before = files(f); val draft = c.repository.loadDraft()
        c.leaveDesignScreen()
        assertFalse(c.designComposer.value.composingNew); assertEquals("", c.designComposer.value.input)
        assertFalse(c.designComposer.value.attachStudioPrompt)
        assertEquals(DesktopReversePromptProgress(), c.state.value.designProgress); assertEquals("", c.state.value.status)
        assertEquals(current, c.designRepository.currentConversation()); assertEquals(draft, c.repository.loadDraft())
        assertEquals(before, files(f))
        c.initializeDesignComposer(); assertFalse(c.designComposer.value.composingNew)
        // Upstream leaveScreen deliberately preserves a composer for an already-open durable conversation.
        c.editDesignInput("durable conversation draft"); c.leaveDesignScreen()
        assertEquals("durable conversation draft", c.designComposer.value.input)
    } }

    @Test fun `leave without durable current stays new but clears transient input without reseeding legacy`() = runBlocking { fixture { f, c ->
        c.edit { it.copy(imageDescription = "legacy retained") }
        c.editDesignInput("unsent"); c.attachDesignPrompt(true)
        val before = files(f)
        c.leaveDesignScreen(); c.initializeDesignComposer()
        assertTrue(c.designComposer.value.composingNew); assertEquals("", c.designComposer.value.input)
        assertFalse(c.designComposer.value.attachStudioPrompt); assertNull(c.designRepository.currentConversation())
        assertEquals(before, files(f))
    } }

    @Test fun `internal Design navigation preserves input while Return Studio ends transient tool session`() = runBlocking(awt) { fixture { f, c ->
        assertTrue(c.design("Conversation A")); f.idle()
        val current = c.designRepository.currentConversationId.value
        val auth = c.designAuthentication(); val draft = c.repository.loadDraft()
        var route by mutableStateOf("conversation")
        val scene = ImageComposeScene(950, 740) {
            DesktopDesignToolLifecycle(c, route != "studio")
            Column(Modifier.fillMaxSize()) {
                if (route == "conversation") {
                    BootstrapButton("返回 Studio") { route = "studio" }
                    DesktopDesignConversation(c, draft, false, auth, { route = "settings" }, { route = "history" }, {}, {})
                } else BootstrapButton("返回 AI 设计") { route = "conversation" }
            }
        }
        try {
            scene.frames(); scene.click("新对话")
            c.editDesignInput("保留的未发输入"); c.attachDesignPrompt(true); scene.frames()
            val before = files(f)
            for (label in listOf("设计设置", "设计历史")) {
                scene.click(label); scene.click("返回 AI 设计")
                assertEquals("保留的未发输入", c.designComposer.value.input); assertTrue(c.designComposer.value.attachStudioPrompt)
            }
            scene.click("返回 Studio"); scene.click("返回 AI 设计")
            assertFalse(c.designComposer.value.composingNew); assertEquals("", c.designComposer.value.input)
            assertFalse(c.designComposer.value.attachStudioPrompt); assertEquals(current, c.designRepository.currentConversationId.value)
            assertEquals(before, files(f))
        } finally { scene.close() }
    } }

    @Test fun `real Studio auxiliary OS close runs leave semantics`() = runBlocking(awt) { fixture { f, c ->
        assertTrue(c.design("Conversation A")); f.idle()
        val current = c.designRepository.currentConversationId.value
        val scene = ImageComposeScene(1360, 900) { DesktopNovelAiStudioPanel(c) }
        try {
            repeat(8) { scene.frames() }
            scene.click("AI 设计")
            c.newDesignConversation(); c.editDesignInput("unsent closed window"); c.attachDesignPrompt(true)
            scene.frames()
            val window = java.awt.Window.getWindows().filterIsInstance<java.awt.Frame>().single { it.title == "AI 设计" && it.isDisplayable }
            val before = files(f)
            window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING)); scene.frames()
            assertFalse(c.designComposer.value.composingNew); assertEquals("", c.designComposer.value.input)
            assertFalse(c.designComposer.value.attachStudioPrompt); assertEquals(current, c.designRepository.currentConversationId.value)
            assertEquals(before, files(f))
        } finally { scene.close() }
    } }

    private suspend fun seed(f: FinalProductDesignFixture, id: String) {
        val conversation = NovelAiDesignConversation(id = id, title = id, turns = (0..11).map { index ->
            NovelAiDesignTurn(id = "$id-$index", userText = "$id turn $index\n" + "Reading fixture line\n".repeat(5),
                status = NovelAiDesignTurnStatus.FAILED, error = "Local fixture; no network")
        })
        f.container.jsonFileStorage.saveEntity("novelai_design_conversations", id, conversation, NovelAiDesignConversation.serializer())
    }

    @Test fun `scroll disposal restores same conversation and settings roundtrip while history selection opens newest`() = runBlocking(awt) {
        val f = FinalProductDesignFixture()
        try {
            seed(f, "A"); seed(f, "B"); f.initialize()
            val c = f.container.novelAiStudioController
            c.switchDesign("A")
            val auth = c.designAuthentication(); val draft = c.repository.loadDraft()
            var showing by mutableStateOf(true)
            val scene = ImageComposeScene(950, 740) { Column(Modifier.fillMaxSize()) {
                if (showing) DesktopDesignConversation(c, draft, false, auth, { showing = false }, { showing = false }, {}, {})
                else BootstrapButton("返回 AI 设计") { showing = true }
            } }
            fun position(): Float = scene.nodes().first { it.matches("AI 设计对话记录") }.config[SemanticsProperties.VerticalScrollAxisRange].value()
            suspend fun scrollTo(index: Int) {
                val node = scene.nodes().first { it.matches("AI 设计对话记录") }
                assertTrue(node.config[SemanticsActions.ScrollToIndex].action!!.invoke(index)); scene.frames()
                // Nonzero offset proves both index and offset survive, not just the turn identity.
                assertTrue(node.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 27f)); repeat(12) { scene.frames() }
            }
            try {
                scene.frames(); val bottomA = position()
                scrollTo(2); val reading = position(); assertTrue(reading < bottomA)
                scene.click("设计设置"); scene.click("返回 AI 设计")
                assertEquals(reading, position(), .01f)
                scene.click("设计历史"); scene.click("返回 AI 设计")
                assertEquals(reading, position(), .01f)
                showing = false; scene.frames()
                val savedA = c.designRepository.consumeInitialScrollPosition("A", 12)
                assertEquals(2, savedA.first); assertTrue(savedA.second > 0, "saved=$savedA, observed=$reading")
                c.switchDesign("B"); showing = true; scene.frames(); assertEquals(bottomA, position(), .01f)
                scrollTo(4); showing = false; scene.frames()
                val savedB = c.designRepository.consumeInitialScrollPosition("B", 12)
                assertEquals(4, savedB.first); assertTrue(savedB.second > 0)
                assertEquals(savedA, c.designRepository.consumeInitialScrollPosition("A", 12))
                c.switchDesign("A"); showing = true; scene.frames()
                assertEquals(bottomA, position(), .01f) // switchCurrent's one-shot open-at-bottom overrides A's saved reading position.
                assertEquals(savedB, c.designRepository.consumeInitialScrollPosition("B", 12))
            } finally { scene.close() }
        } finally { f.close() }
    }
}
