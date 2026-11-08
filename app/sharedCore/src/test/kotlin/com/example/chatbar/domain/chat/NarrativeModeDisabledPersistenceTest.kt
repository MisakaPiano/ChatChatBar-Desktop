package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.mergeSettingsDraft
import com.example.chatbar.domain.prompt.MainChatPromptAuthority
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class NarrativeModeDisabledPersistenceTest {
    private val json = Json { encodeDefaults = true }
    private fun message() = ChatMessage("m", "s", MessageRole.ASSISTANT, "old", createdAt = 1, updatedAt = 1)

    @Test fun `missing modes retain legacy focus while explicit null and nullable candidate entries survive serialization`() {
        val old = """{"id":"m","sessionId":"s","role":"ASSISTANT","content":"old","createdAt":1,"updatedAt":1}"""
        assertEquals(ChatNarrativeMode.FOCUS, json.decodeFromString(ChatMessage.serializer(), old).narrativeMode)
        val disabled = message().copy(narrativeMode = null, alternatives = listOf("old", "next"),
            alternativeNarrativeModes = listOf(null, ChatNarrativeMode.ADVANCE))
        val encoded = json.encodeToJsonElement(ChatMessage.serializer(), disabled).jsonObject
        assertEquals(JsonNull, encoded.getValue("narrativeMode"))
        assertEquals(JsonNull, encoded.getValue("alternativeNarrativeModes").jsonArray[0])
        assertEquals(disabled, json.decodeFromJsonElement(ChatMessage.serializer(), encoded))
        assertFails { json.decodeFromString(ChatMessage.serializer(), old.dropLast(1) + ",\"narrativeMode\":\"UNKNOWN\"}") }
        assertFalse(json.decodeFromString(AppSettings.serializer(), "{}").disableNarrativeModes)
        assertFalse("disableNarrativeModes" in json.encodeToJsonElement(AppSettings.serializer(), AppSettings()).jsonObject)
        val settings = AppSettings(disableNarrativeModes = true)
        assertEquals(settings, json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), settings)))
    }

    @Test fun `version selection normalization editing pruning and copying distinguish null from a missing candidate mode`() {
        val source = message().copy(narrativeMode = null)
        val appended = MessageAlternativeVersionPolicy.append(source, "next", "v2", narrativeMode = ChatNarrativeMode.ADVANCE)
        assertEquals(listOf(null, ChatNarrativeMode.ADVANCE), appended.alternativeNarrativeModes)
        val selected = MessageAlternativeVersionPolicy.select(appended, 0)
        assertNull(selected.narrativeMode)
        assertEquals(source.id, selected.currentAlternativeVersionId)
        val edited = MessageAlternativeVersionPolicy.editCurrentContent(selected, "edited")
        assertNull(edited.narrativeMode)
        assertEquals(listOf("edited", "next"), edited.alternatives)
        assertEquals(appended.alternativeVersionIds, edited.alternativeVersionIds)
        val copy = SessionCopyPolicy("copy", setOf("m")).message(edited, "copy")
        assertEquals(edited.alternativeNarrativeModes, copy.alternativeNarrativeModes)
        assertEquals(edited.alternativeVersionIds, copy.alternativeVersionIds)
        val pruned = MessageAlternativeVersionPolicy.append(edited, "last", "v3", maxVersions = 1, narrativeMode = null)
        assertNull(pruned.narrativeMode)
        assertEquals(listOf<String>("v3"), pruned.alternativeVersionIds)
        assertEquals(listOf<ChatNarrativeMode?>(null), pruned.alternativeNarrativeModes)
        val gap = appended.copy(alternativeNarrativeModes = listOf(null))
        assertEquals(listOf(null, ChatNarrativeMode.FOCUS), MessageAlternativeVersionPolicy.normalize(gap).alternativeNarrativeModes)
    }

    @Test fun `optional history helpers preserve disabled raw text and distinguish a null previous source from no source`() {
        val disabled = message().copy(narrativeMode = null)
        assertEquals("rendered body", ChatHistoryPromptPolicy.renderedHistoryText(disabled, "rendered body"))
        assertEquals("rendered body", ChatHistoryPromptPolicy.renderedHistoryText(message(), "rendered body", narrativeModesEnabled = false))
        assertNull(ChatHistoryPromptPolicy.previousNarrativeMode(listOf(disabled)))
        assertNull(ChatHistoryPromptPolicy.previousNarrativeMode(emptyList(), disabled))
        assertEquals(ChatNarrativeMode.FOCUS, ChatHistoryPromptPolicy.previousNarrativeMode(emptyList()))
        assertEquals(MainChatPromptAuthority.narrativeModeHistoryReply("rendered body", ChatNarrativeMode.FOCUS),
            ChatHistoryPromptPolicy.renderedHistoryText(message(), "rendered body"))
        assertEquals("user body", ChatHistoryPromptPolicy.renderedHistoryText(message().copy(role = MessageRole.USER), "user body"))
    }

    @Test fun `cold repository and unrelated settings drafts retain explicit disabled state and allow explicit reset`() = runBlocking {
        val root = Files.createTempDirectory("disabled-narrative-")
        try {
            val repository = ChatRepository(JsonFileStorage(root))
            repository.createSession(ChatSession("s", "c", "Synthetic", createdAt = 1, updatedAt = 1))
            val versions = MessageAlternativeVersionPolicy.append(message().copy(narrativeMode = null), "next", "v2",
                narrativeMode = ChatNarrativeMode.ADVANCE)
            repository.addMessage(versions)
            repository.updateMessage(MessageAlternativeVersionPolicy.select(versions, 0))
            val cold = assertNotNull(ChatRepository(JsonFileStorage(root)).getMessage("m", "s"))
            assertNull(cold.narrativeMode)
            assertEquals(versions.alternativeVersionIds, cold.alternativeVersionIds)
            assertEquals(listOf(null, ChatNarrativeMode.ADVANCE), cold.alternativeNarrativeModes)
            val baseline = AppSettings()
            val latest = baseline.copy(disableNarrativeModes = true)
            assertTrue(mergeSettingsDraft(AppSettings.serializer(), baseline, baseline.copy(defaultModelId = "chosen"), latest).disableNarrativeModes)
            assertFalse(mergeSettingsDraft(AppSettings.serializer(), latest, latest.copy(disableNarrativeModes = false), latest).disableNarrativeModes)
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
