package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ModelRequestException
import com.example.chatbar.domain.chat.StreamEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class VoiceResponseGatewayTest {
    private val inputs = listOf(VoiceTagInput("one", "hello", "Alice", ""))
    private val translation = """{"segments":[{"id":"one","translatedText":"hello"}]}"""
    private val tagged = """{"segments":[{"id":"one","ttsText":"hello"}]}"""
    private val model = ModelConfig("synthetic", "synthetic", "https://example.invalid", "synthetic", "mock", createdAt = 1)

    @Test fun `complete fenced JSON is accepted but incomplete mixed and trailing payloads still refuse`() {
        val service = FishAudioTagService(FishVoiceTextGateway { _, _, _, _ -> error("not a network test") })
        for (fence in listOf("```json\n", "~~~JSON\n")) {
            val ending = fence.take(3)
            assertEquals("hello", service.parseTranslation("$fence$translation\n$ending", inputs).translatedTextById["one"])
            assertEquals("hello", service.parseAndValidate("$fence$tagged\n$ending", inputs, FishAudioMarkerMode.SQUARE).taggedTextById["one"])
        }
        for (bad in listOf("```json\n$translation", "```json\n$translation $translation\n```",
            "before $translation", "```json\n$translation\n``` after")) {
            assertTrue(service.parseTranslation(bad, inputs).translatedTextById.isEmpty())
        }
    }

    @Test fun `actual auxiliary gateway retries typed transport then invalid output and returns fresh validated output`() = runTest {
        var calls = 0
        val notices = mutableListOf<String>()
        val service = FishAudioTagService(FishVoiceTextGateway { _, _, configured, thinking ->
            assertNull(configured.maxOutputTokens); assertTrue(thinking)
            when (++calls) {
                1 -> flowOf(StreamEvent.Error("synthetic busy", cause = ModelRequestException("synthetic", 503)))
                2 -> flowOf(StreamEvent.Delta("{incomplete"), StreamEvent.Done)
                else -> flowOf(StreamEvent.Delta("```json\n$translation\n```"), StreamEvent.Done)
            }
        })
        val result = service.translate(model, "English", "synthetic", "synthetic", inputs) { notices += it }
        assertEquals(3, calls)
        assertEquals("hello", result.translatedTextById["one"])
        assertFalse(result.rawOutput.contains("incomplete"))
        assertEquals(2, notices.count { it.contains("后重试") })
    }

    @Test fun `actual tag gateway never retries permanent auth errors and never sends raw text as success`() = runTest {
        val failure = ModelRequestException("synthetic unauthorized", 401)
        var calls = 0
        val service = FishAudioTagService(FishVoiceTextGateway { _, _, _, _ ->
            calls++; flowOf(StreamEvent.Error("synthetic", cause = failure))
        })
        assertSame(failure, assertFailsWith<ModelRequestException> { service.generate(model, "s2.1-pro-free", "", "", inputs) })
        assertEquals(1, calls)
    }
}
