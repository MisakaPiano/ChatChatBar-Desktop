package com.example.chatbar.desktop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.*
import com.example.chatbar.data.local.entity.*
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

class DesktopChatUiPreferencesTest {
    @Test fun `old settings defaults and concurrent display owners survive reload and invalid settings stay intact`() = runBlocking {
        val root = Files.createTempDirectory("chat-display-settings-")
        try {
            val store = DesktopSettingsStore(root, DesktopDataOperationCoordinator())
            val a = DesktopChatUiController(store); a.load()
            assertFalse(Files.exists(store.settingsPath))
            assertEquals(DesktopSendKey.CTRL_ENTER, a.state.value.sendKey)
            assertFalse(a.state.value.multipleInlineStatuses)
            val pin = DesktopPinnedPerson("session", "card", "person")
            coroutineScope {
                launch { a.update { it.copy(sendKey = DesktopSendKey.ENTER) } }
                launch { a.pin(pin, true) }
                launch { store.updateLatest { it.copy(uiLanguage = DesktopUiLanguage.EN) } }
                launch { a.update { it.copy(inlineStatuses = true, multipleInlineStatuses = true) } }
            }
            val b = DesktopChatUiController(store); b.load()
            assertEquals(DesktopSendKey.ENTER, b.state.value.sendKey)
            assertEquals(listOf(pin), b.state.value.pinnedPeople)
            assertTrue(b.state.value.inlineStatuses)
            assertTrue(b.state.value.multipleInlineStatuses)
            assertEquals(DesktopUiLanguage.EN, assertIs<DesktopSettingsLoadResult.Loaded>(store.load()).document.settings.uiLanguage)
            b.update { it.copy(sendKey = DesktopSendKey.CTRL_ENTER, inlineStatuses = false, multipleInlineStatuses = false) }; b.pin(pin, false)
            a.load(); assertEquals(DesktopChatUiPreferences(), a.state.value)
            Files.writeString(store.settingsPath, "{broken")
            val before = Files.readAllBytes(store.settingsPath)
            a.pin(pin, true)
            assertNotNull(a.error.value); assertContentEquals(before, Files.readAllBytes(store.settingsPath))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `both send modes preserve IME newline modifiers and blank keyboard guard`() {
        var count = 0
        val text = TextFieldValue("draft")
        for (mode in DesktopSendKey.entries) {
            fun press(ctrl: Boolean, shift: Boolean = false, alt: Boolean = false, value: TextFieldValue = text) =
                desktopComposerSendKey(Key.Enter, KeyEventType.KeyDown, ctrl, value, true, mode, shift, alt) { count++ }
            assertTrue(press(mode == DesktopSendKey.CTRL_ENTER))
            assertFalse(press(mode != DesktopSendKey.CTRL_ENTER))
            assertFalse(press(mode == DesktopSendKey.CTRL_ENTER, shift = true))
            assertFalse(press(mode == DesktopSendKey.CTRL_ENTER, alt = true))
            assertFalse(press(mode == DesktopSendKey.CTRL_ENTER, value = text.copy(composition = TextRange(0, 5))))
            assertFalse(press(mode == DesktopSendKey.CTRL_ENTER, value = TextFieldValue(" \n")))
        }
        assertEquals(2, count)
    }

    @Test fun `all people retained with manual pins then valid active statuses then card order`() {
        val message = ChatMessage(id = "m", sessionId = "s", role = MessageRole.ASSISTANT, content = "state", createdAt = 1, updatedAt = 1)
        fun person(id: String, status: Boolean = false, resident: Boolean = true, enabled: Boolean = true) =
            DesktopChatPerson("card", CharacterInfo(id = id, name = id), true,
                if (status) DesktopPersonStatus("state", message) else null, enabled = enabled, resident = resident)
        val people = listOf(person("other"), person("active", true), person("old", true, false), person("avatarOnly"), person("off", true, enabled = false))
        assertEquals(listOf("active", "other", "old", "avatarOnly", "off"), desktopOrderedPeople(people, emptySet()).map { it.character.id })
        assertEquals(listOf("old", "active", "other", "avatarOnly", "off"), desktopOrderedPeople(people, setOf("old")).map { it.character.id })
        assertEquals(listOf("other", "active", "old", "avatarOnly", "off"), people.map { it.character.id })
        assertFalse(people.last().active); assertFalse(people[3].active)
        assertEquals(listOf("active", "other", "old", "avatarOnly"),
            desktopVisiblePeople(people, emptySet(), false).map { it.character.id })
        assertEquals(people.size, desktopVisiblePeople(people, emptySet(), true).size)
    }

    @Test fun `actual viewport budget reserves both rails once and names need enough width`() {
        val wide = desktopChatWorkspaceLayout(1300f, true)
        assertTrue(wide.names); assertTrue(wide.timeline.external)
        assertEquals(880f, wide.timeline.contentWidthDp)
        assertEquals(60f, wide.widthDp - wide.headerWidthDp)
        for (width in listOf(320f, 680f, 800f, 1100f, 1300f)) {
            val result = desktopChatWorkspaceLayout(width, true)
            assertTrue(result.widthDp <= width)
            assertTrue(result.timeline.contentWidthDp > 0)
        }
        assertEquals(64f, desktopChatWorkspaceLayout(680f, true).peopleWidthDp)
        assertEquals(64f, desktopChatWorkspaceLayout(800f, true).peopleWidthDp)
        assertEquals(0f, desktopChatWorkspaceLayout(1300f, false).peopleWidthDp)
        assertEquals(0f, desktopChatWorkspaceLayout(1300f, true, 280f, peopleHidden = true).peopleWidthDp)
        assertEquals(280f, desktopChatWorkspaceLayout(1300f, true, 280f).peopleWidthDp)
        assertFalse(desktopChatWorkspaceLayout(1000f, true, 62f).names)
        assertTrue(desktopChatWorkspaceLayout(1300f, true, 280f).names)
    }

    @Test fun `status preview follows trigger side and remains inside viewport edges`() {
        fun position(anchor: Rect) = DesktopPersonPopupPosition(anchor).calculatePosition(IntRect.Zero,
            IntSize(600, 400), LayoutDirection.Ltr, IntSize(280, 240))
        assertEquals(IntOffset(56, 30), position(Rect(10f, 30f, 50f, 70f)))
        val edge = position(Rect(550f, 360f, 590f, 400f))
        assertTrue(edge.x + 280 <= 550); assertEquals(160, edge.y)
    }
}
