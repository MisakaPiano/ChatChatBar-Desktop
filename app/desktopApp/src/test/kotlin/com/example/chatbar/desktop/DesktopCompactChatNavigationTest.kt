@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.ModelConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopCompactChatNavigationTest {
    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    private suspend fun ImageComposeScene.frames() {
        repeat(5) { render().close(); yield() }
        delay(35)
        render().close()
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)
        return semanticsOwners.flatMap { all(it.rootSemanticsNode) }
    }

    private fun SemanticsNode.matches(label: String) =
        config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
            config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true

    private fun ImageComposeScene.has(label: String) = nodes().any { it.matches(label) }

    private suspend fun ImageComposeScene.click(label: String) {
        val node = nodes().first { it.matches(label) && it.config.getOrNull(SemanticsActions.OnClick) != null }
        val center: Offset = node.boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, center, button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, center, button = PointerButton.Primary)
        frames()
    }

    private suspend fun seeded(): Pair<FinalProductDesignFixture, DesktopPrimaryChatController> {
        val fixture = FinalProductDesignFixture()
        val c = fixture.container
        val card = CharacterCard.create("Navigation character").copy(id = "card")
        c.characterRepository.save(card)
        c.modelRepository.saveModel(ModelConfig("model", "Local model", "fixture-model",
            "http://127.0.0.1:1/v1", "fixture-only", createdAt = 1))
        c.settingsRepository.saveAppSettings(AppSettings(defaultModelId = "model", allowCleartextModelApi = true))
        c.chatRepository.createSession(ChatSession("a", card.id, "First room", createdAt = 1, updatedAt = 1))
        c.chatRepository.createSession(ChatSession("b", card.id, "Second room", createdAt = 2, updatedAt = 2))
        c.primaryChatController.refresh()
        return fixture to c.primaryChatController
    }

    @Test fun `cold compact keeps auto selected session in browser until user enters it`() = runBlocking(awt) {
        val (fixture, controller) = seeded()
        val navigation = DesktopCompactChatNavigation()
        val autoId = controller.state.value.selectedSession!!.id
        val initialSession = fixture.container.chatRepository.getSession(autoId)
        val scene = ImageComposeScene(700, 900) {
            DesktopPrimaryChatPanel(controller, DesktopShellSize.COMPACT, remember { DesktopComposerLayoutState() },
                compactNavigation = navigation)
        }
        try {
            scene.frames()
            assertTrue(scene.has("搜索对话"))
            assertFalse(scene.has("展开会话列表"))
            assertFalse(navigation.enteredChat)
            scene.click("First room")
            withTimeout(5_000) { while (!navigation.enteredChat) { delay(15); scene.frames() } }
            assertFalse(scene.has("搜索对话"))
            assertTrue(scene.has("展开会话列表"))
            assertEquals("a", controller.state.value.selectedSession?.id)
            assertEquals(initialSession, fixture.container.chatRepository.getSession(autoId))
            scene.click("展开会话列表")
            assertTrue(scene.has("搜索对话"))
            scene.click("Second room")
            withTimeout(5_000) { while (controller.state.value.selectedSession?.id != "b") { delay(15); scene.frames() } }
            scene.frames()
            assertFalse(scene.has("搜索对话"))
            assertEquals("b", controller.state.value.selectedSession?.id)
            scene.click("展开会话列表")
            fixture.container.chatRepository.deleteSession("a")
            scene.click("First room")
            assertTrue(scene.has("搜索对话"))
            assertTrue(navigation.browserRequested)
            assertEquals("b", controller.state.value.selectedSession?.id)
            assertTrue(controller.state.value.error?.contains("Session no longer exists") == true)
        } finally { scene.close(); fixture.close() }
    }

    @Test fun `wide actual chat display survives compact and route round trips but a new shell starts in browser`() = runBlocking(awt) {
        val (fixture, controller) = seeded()
        val navigation = DesktopCompactChatNavigation()
        val originalDraft = controller.state.value.composerDraft
        val originalReading = controller.state.value.readingPosition
        var size by mutableStateOf(DesktopShellSize.WIDE)
        var chatRoute by mutableStateOf(true)
        val scene = ImageComposeScene(1300, 900) {
            if (chatRoute) DesktopPrimaryChatPanel(controller, size, remember { DesktopComposerLayoutState() },
                compactNavigation = navigation)
        }
        try {
            scene.frames()
            assertTrue(navigation.enteredChat)
            size = DesktopShellSize.COMPACT
            scene.frames()
            assertFalse(scene.has("搜索对话"))
            assertTrue(scene.has("展开会话列表"))
            chatRoute = false; scene.frames()
            chatRoute = true; scene.frames()
            assertFalse(scene.has("搜索对话"))
            scene.click("展开会话列表")
            assertTrue(scene.has("搜索对话"))
            chatRoute = false; scene.frames()
            chatRoute = true; scene.frames()
            assertTrue(scene.has("搜索对话"))
            scene.click("收起会话列表")
            assertFalse(scene.has("搜索对话"))
            assertEquals(originalDraft, controller.state.value.composerDraft)
            assertEquals(originalReading, controller.state.value.readingPosition)
        } finally { scene.close() }
        val fresh = DesktopCompactChatNavigation()
        val next = ImageComposeScene(700, 900) {
            DesktopPrimaryChatPanel(controller, DesktopShellSize.COMPACT, remember { DesktopComposerLayoutState() },
                compactNavigation = fresh)
        }
        try {
            next.frames()
            assertTrue(next.has("搜索对话"))
            assertFalse(fresh.enteredChat)
        } finally { next.close(); fixture.close() }
    }

    @Test fun `creating a session from compact browser enters the new chat only after persistence succeeds`() = runBlocking(awt) {
        val (fixture, controller) = seeded()
        val navigation = DesktopCompactChatNavigation()
        val before = fixture.container.chatRepository.getAllSessions().mapTo(mutableSetOf()) { it.id }
        val scene = ImageComposeScene(700, 900) {
            DesktopPrimaryChatPanel(controller, DesktopShellSize.COMPACT, remember { DesktopComposerLayoutState() },
                compactNavigation = navigation)
        }
        try {
            scene.frames()
            scene.click("+ 新建对话")
            withTimeout(5_000) { while (!scene.has("开始聊天")) { delay(15); scene.frames() } }
            assertTrue(scene.has("搜索对话"))
            assertFalse(navigation.enteredChat)
            scene.click("开始聊天")
            withTimeout(5_000) { while (!navigation.enteredChat) { delay(15); scene.frames() } }
            scene.frames()
            val created = controller.state.value.selectedSession!!.id
            assertTrue(created !in before)
            assertEquals(created, fixture.container.chatRepository.getSession(created)?.id)
            assertFalse(scene.has("搜索对话"))
            assertFalse(scene.has("开始聊天"))
        } finally { scene.close(); fixture.close() }
    }

    @Test fun `dirty settings defers compact selection and preserves draft until confirmed`() = runBlocking(awt) {
        val (fixture, controller) = seeded()
        val navigation = DesktopCompactChatNavigation()
        val original = controller.state.value.selectedSession!!.id
        controller.editComposer("unsubmitted draft")
        controller.editSessionSettings { it.copy(supplementarySetting = "pending setting") }
        val scene = ImageComposeScene(700, 900) {
            DesktopPrimaryChatPanel(controller, DesktopShellSize.COMPACT, remember { DesktopComposerLayoutState() },
                compactNavigation = navigation)
        }
        try {
            scene.frames()
            val target = if (original == "a") "Second room" else "First room"
            scene.click(target)
            assertTrue(scene.has("搜索对话"))
            assertFalse(navigation.enteredChat)
            assertTrue(controller.state.value.sessionSettingsLeavePrompt)
            assertEquals(original, controller.state.value.selectedSession?.id)
            assertEquals("unsubmitted draft", controller.state.value.composerDraft)
            controller.continueSessionSettingsEditing(); scene.frames()
            assertTrue(scene.has("搜索对话"))
            assertTrue(controller.state.value.sessionSettingsDirty)
            scene.click(target)
            controller.resolveSessionSettingsLeave(save = false)
            withTimeout(5_000) { while (!navigation.enteredChat) { delay(15); scene.frames() } }
            scene.frames()
            assertFalse(scene.has("搜索对话"))
            assertFalse(controller.state.value.sessionSettingsDirty)
            assertEquals("unsubmitted draft", fixture.container.chatRepository.getSessionDraft(original))
            assertFalse(fixture.container.chatRepository.getSession(original)!!.supplementarySetting == "pending setting")
        } finally { scene.close(); fixture.close() }
    }
}
