package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopSettingsSurfaceParityTest {
    @Test
    fun `loaded settings expose persisted history exclusion`() = runBlocking {
        withContainer { container ->
            container.settingsRepository.saveAppSettings(AppSettings(excludeAssistantStatusFromHistory = false))
            container.modelSettingsController.loadSettings()
            val draft = assertNotNull(container.modelSettingsController.state.value.settings)
            assertFalse(draft.excludeAssistantStatusFromHistory)
        }
    }

    @Test
    fun `loaded settings expose persisted bubble scale`() = runBlocking {
        withContainer { container ->
            container.settingsRepository.saveAppSettings(AppSettings(chatBubbleFontScale = 1.4f))
            container.modelSettingsController.loadSettings()
            val state = container.modelSettingsController.state.value
            assertEquals(1.4f, state.chatBubbleFontScale)
        }
    }

    @Test
    fun `history exclusion is a discardable draft and guards leave`() = runBlocking {
        withContainer { container ->
            val controller = container.modelSettingsController
            controller.loadSettings()
            assertTrue(controller.state.value.settings!!.excludeAssistantStatusFromHistory)
            controller.editSettings { it.copy(excludeAssistantStatusFromHistory = false) }
            assertTrue(controller.state.value.chatDefaultsDirty)
            assertTrue(container.settingsRepository.getAppSettings().excludeAssistantStatusFromHistory)
            var left = false
            controller.requestLeave { left = true }
            assertFalse(left)
            assertTrue(controller.state.value.leavePrompt)
            controller.resolveLeave(save = false)
            assertTrue(left)
            assertFalse(controller.state.value.chatDefaultsDirty)
            assertTrue(controller.state.value.settings!!.excludeAssistantStatusFromHistory)
        }
    }

    @Test
    fun `history exclusion saves both values and survives repository restart`() = runBlocking {
        withContainer { container ->
            val controller = container.modelSettingsController
            controller.loadSettings()
            for (value in listOf(false, true)) {
                controller.editSettings { it.copy(excludeAssistantStatusFromHistory = value) }
                controller.saveAppSettings()
                assertFalse(controller.state.value.chatDefaultsDirty)
                val restarted = com.example.chatbar.data.repository.SettingsRepository(container.jsonFileStorage)
                assertEquals(value, restarted.getAppSettings().excludeAssistantStatusFromHistory)
            }
        }
    }

    @Test
    fun `font scale writes discrete safe values without dirtying ordinary defaults`() = runBlocking {
        withContainer { container ->
            val controller = container.modelSettingsController
            controller.loadSettings()
            for ((input, expected) in listOf(0.5f to 0.5f, 1f to 1f, 1.5f to 1.5f,
                0f to 0.5f, 2f to 1.5f, Float.NaN to 1f, Float.POSITIVE_INFINITY to 1f, 1.26f to 1.3f)) {
                controller.updateBubbleFontScale(input)
                assertEquals(expected, controller.state.value.chatBubbleFontScale)
                assertEquals(expected, container.settingsRepository.getAppSettings().chatBubbleFontScale)
                assertFalse(controller.state.value.chatDefaultsDirty)
                assertFalse(controller.state.value.bubbleFontScaleError)
            }
            val restarted = com.example.chatbar.data.repository.SettingsRepository(container.jsonFileStorage)
            assertEquals(1.3f, restarted.getAppSettings().chatBubbleFontScale)
        }
    }

    @Test
    fun `rapid font changes update UI before blocked storage and latest selection is durable`() = runBlocking {
        withContainer { container -> coroutineScope {
            val controller = container.modelSettingsController
            controller.loadSettings()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val maintenance = launch {
                container.dataOperationCoordinator.withExclusiveMaintenance {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            val writes = listOf(0.8f, 1.2f, 0.9f, 1.4f).map { value ->
                launch(start = CoroutineStart.UNDISPATCHED) { controller.updateBubbleFontScale(value) }
            }
            var left = false
            val leave = launch(start = CoroutineStart.UNDISPATCHED) { controller.requestLeave { left = true } }
            try {
                assertEquals(1.4f, controller.state.value.chatBubbleFontScale)
                assertTrue(controller.state.value.bubbleFontScaleSaving)
                assertFalse(controller.state.value.chatDefaultsDirty)
                assertFalse(left)
            } finally { release.complete(Unit) }
            writes.forEach { it.join() }
            maintenance.join()
            leave.join()
            assertTrue(left)
            assertEquals(1.4f, container.settingsRepository.getAppSettings().chatBubbleFontScale)
            assertFalse(controller.state.value.bubbleFontScaleSaving)
        } }
    }

    @Test
    fun `ordinary save queued behind immediate write retains latest scale and draft values`() = runBlocking {
        withContainer { container -> coroutineScope {
            val controller = container.modelSettingsController
            controller.loadSettings()
            controller.editSettings { it.copy(defaultContextWindowSize = "41", excludeAssistantStatusFromHistory = false) }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val maintenance = launch {
                container.dataOperationCoordinator.withExclusiveMaintenance {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            val font = launch(start = CoroutineStart.UNDISPATCHED) { controller.updateBubbleFontScale(1.4f) }
            val save = launch(start = CoroutineStart.UNDISPATCHED) { controller.saveAppSettings() }
            try {
                assertEquals(1.4f, controller.state.value.chatBubbleFontScale)
                assertTrue(controller.state.value.chatDefaultsDirty)
            } finally { release.complete(Unit) }
            font.join()
            save.join()
            maintenance.join()
            val saved = container.settingsRepository.getAppSettings()
            assertEquals(1.4f, saved.chatBubbleFontScale)
            assertEquals(41, saved.defaultContextWindowSize)
            assertFalse(saved.excludeAssistantStatusFromHistory)
            assertFalse(controller.state.value.chatDefaultsDirty)
        } }
    }

    @Test
    fun `dirty defaults coexist with immediate font save through save and discard`() = runBlocking {
        withContainer { container ->
            val controller = container.modelSettingsController
            controller.loadSettings()
            controller.editSettings { it.copy(defaultContextWindowSize = "37", defaultFormatCardId = "draft-format",
                assistantSegmentedBubblesEnabled = false, excludeAssistantStatusFromHistory = false) }
            val draft = controller.state.value.settings
            controller.updateBubbleFontScale(1.3f)
            assertEquals(draft, controller.state.value.settings)
            assertTrue(controller.state.value.chatDefaultsDirty)
            assertEquals(20, container.settingsRepository.getAppSettings().defaultContextWindowSize)
            controller.saveAppSettings()
            val saved = container.settingsRepository.getAppSettings()
            assertEquals(37, saved.defaultContextWindowSize)
            assertEquals("draft-format", saved.defaultFormatCardId)
            assertFalse(saved.assistantSegmentedBubblesEnabled)
            assertFalse(saved.excludeAssistantStatusFromHistory)
            assertEquals(1.3f, saved.chatBubbleFontScale)
            controller.editSettings { it.copy(defaultContextWindowSize = "99", excludeAssistantStatusFromHistory = true) }
            controller.updateBubbleFontScale(0.7f)
            controller.discardChatDefaults()
            assertEquals("37", controller.state.value.settings!!.defaultContextWindowSize)
            assertFalse(controller.state.value.settings!!.excludeAssistantStatusFromHistory)
            assertEquals(0.7f, controller.state.value.chatBubbleFontScale)
            assertEquals(0.7f, container.settingsRepository.getAppSettings().chatBubbleFontScale)
            assertFalse(controller.state.value.chatDefaultsDirty)
        }
    }

    @Test
    fun `chat selection refresh and switching read latest safe scale without rewriting old value`() = runBlocking {
        withContainer { container ->
            val card = CharacterCard.create("Inline", greeting = "Hello")
            container.characterRepository.save(card)
            val first = container.characterSessionService.createSessionForCharacter(card.id)
            val second = container.characterSessionService.createSessionForCharacter(card.id)
            val controller = container.primaryChatController
            container.settingsRepository.updateAppSettings { it.copy(chatBubbleFontScale = 0.5f) }
            controller.selectSession(first)
            assertEquals(0.5f, controller.state.value.chatBubbleFontScale)
            container.settingsRepository.updateAppSettings { it.copy(chatBubbleFontScale = 1.5f) }
            controller.refresh()
            assertEquals(1.5f, controller.state.value.chatBubbleFontScale)
            container.settingsRepository.updateAppSettings { it.copy(chatBubbleFontScale = 2f) }
            val path = container.appDataRoot.resolve("entities/app_settings.json")
            val before = Files.readAllBytes(path)
            controller.selectSession(second)
            assertEquals(1.5f, controller.state.value.chatBubbleFontScale)
            container.modelSettingsController.loadSettings()
            assertEquals(1.5f, container.modelSettingsController.state.value.chatBubbleFontScale)
            controller.selectSession(first)
            assertContentEquals(before, Files.readAllBytes(path))
            assertEquals(2f, container.settingsRepository.getAppSettings().chatBubbleFontScale)
        }
    }

    @Test
    fun `message typography scales body and markdown headings only without changing source or chrome`() {
        val chrome = TextStyle(fontSize = 14.sp)
        for (scale in listOf(0.5f, 1f, 1.5f)) {
            val typography = DesktopChatTypography(scale)
            assertEquals((14 * scale).sp, typography.style(chrome).fontSize)
            assertEquals(14.sp, chrome.fontSize)
            val raw = "# Header\n**Body**"
            val markdown = desktopMarkdown(raw, typography)
            assertEquals((19 * scale).sp, markdown.spanStyles.first().item.fontSize)
            assertEquals("Header\nBody", markdown.text)
            val persisted = ChatMessage.create("s", MessageRole.ASSISTANT, raw)
            val streaming = desktopStreamingMessage("s", "task", raw, "")
            assertEquals(desktopMarkdown(persisted.content, typography), desktopMarkdown(streaming.content, typography))
            assertEquals(raw, persisted.content)
            assertEquals(raw, streaming.content)
        }
        assertEquals(0.5f, desktopSafeBubbleFontScale(0f))
        assertEquals(1.5f, desktopSafeBubbleFontScale(2f))
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertEquals(1f, desktopSafeBubbleFontScale(it))
        }
    }

    private suspend fun withContainer(block: suspend (DesktopAppContainer) -> Unit) {
        val parent = Files.createTempDirectory("settings-surface-parity-")
        val container = DesktopAppContainer(
            DesktopDataRootResolution.Resolved(parent.resolve("data"),
                DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() },
        )
        try { block(container) } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }
}
