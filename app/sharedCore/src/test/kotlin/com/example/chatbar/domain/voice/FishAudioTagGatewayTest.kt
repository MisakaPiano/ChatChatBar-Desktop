package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.prompt.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.*
import kotlin.test.*

class FishAudioTagGatewayTest {
    private val model = ModelConfig("synthetic", "synthetic", "https://example.invalid", "synthetic-key", "mock", createdAt = 1,
        maxOutputTokens = 12)

    @Test fun `migrated prompts and final serialized messages equal frozen official inputs for both tasks`() = runBlocking {
        val captured = mutableListOf<Pair<FishVoiceTextTask, List<ChatApiMessage>>>()
        val service = FishAudioTagService(FishVoiceTextGateway { task, messages, configured, disableThinking ->
            assertTrue(disableThinking); assertNull(configured.maxOutputTokens)
            captured += task to messages
            flowOf(StreamEvent.Delta(if (task == FishVoiceTextTask.TRANSLATE)
                """{"segments":[{"id":"one","translatedText":"hello"}]}""" else
                """{"segments":[{"id":"one","ttsText":"hello"}]}"""), StreamEvent.Done)
        })
        val inputs = listOf(VoiceTagInput("one", "hello", "Alice", "calm"))
        service.translate(model, "English", "synthetic previous", "synthetic response", inputs)
        service.generate(model, "s2.1-pro-free", "synthetic previous", "synthetic response", inputs)
        captured.forEach { (task, actual) ->
            val segments = if (task == FishVoiceTextTask.TRANSLATE)
                """[{"id":"one","text":"hello","character_name":"Alice"}]""" else
                """[{"id":"one","text":"hello","character_name":"Alice","speaking_style":"calm"}]"""
            val system = if (task == FishVoiceTextTask.TRANSLATE) OfficialFishPromptGolden.FISH_AUDIO_TRANSLATION_SYSTEM
                else OfficialFishPromptGolden.FISH_AUDIO_VOICE_TAG_SYSTEM
            val user = if (task == FishVoiceTextTask.TRANSLATE)
                OfficialFishPromptGolden.fishAudioTranslationUserInput("English", "synthetic previous", "synthetic response", segments)
            else OfficialFishPromptGolden.fishAudioVoiceTagUserInput("s2.1-pro-free", FishAudioMarkerMode.SQUARE.description,
                FishAudioTagPolicy.promptPolicy("s2.1-pro-free"), "synthetic previous", "synthetic response", segments)
            fun body(messages: List<ChatApiMessage>) = OpenAiChatRequestSerializer.serialize(
                AuxiliaryMessageAssembler.assemble(messages, false), model.withoutOutputTokenLimit(), stream = true,
                disableThinking = true, isolatedTaskParameters = true)
            assertEquals(body(listOf(ChatApiMessage.text("system", system.trim()), ChatApiMessage.text("user", user))), body(actual))
            val json = Json.parseToJsonElement(body(actual)).jsonObject
            assertEquals(8, json["messages"]!!.jsonArray.size)
            assertFalse("max_tokens" in json); assertFalse("max_completion_tokens" in json)
        }
    }

    @Test fun `strict IDs fields translation and changed spoken text retain official error and confirmation branches`() {
        val service = FishAudioTagService(FishVoiceTextGateway { _, _, _, _ -> error("no network") })
        val input = listOf(VoiceTagInput("one", "hello", "Alice", ""))
        listOf("{}", "{bad}", """{"segments":[{"id":"one","translatedText":""}]}""",
            """{"segments":[{"id":"one","translatedText":"hi","other":1}]}""",
            """{"segments":[{"id":"one","translatedText":"hi"},{"id":"one","translatedText":"hi"}]}""").forEach {
            assertTrue(service.parseTranslation(it, input).translatedTextById.isEmpty())
        }
        val changed = service.parseAndValidate("""{"segments":[{"id":"one","ttsText":"[calm] changed"}]}""", input, FishAudioMarkerMode.SQUARE)
        assertEquals(setOf("one"), changed.confirmationRequiredById.keys); assertTrue(changed.taggedTextById.isEmpty())
        assertTrue(service.parseAndValidate("""{"segments":[{"id":"one","ttsText":"(unknown)hello"}]}""", input,
            FishAudioMarkerMode.PARENTHESIS).confirmationRequiredById.isNotEmpty())
    }
}
