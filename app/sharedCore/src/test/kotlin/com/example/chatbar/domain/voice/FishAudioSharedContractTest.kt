package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.FishAudioVoiceBinding
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.local.entity.VoiceAnchorState
import com.example.chatbar.domain.card.CharacterCardPackage
import com.example.chatbar.domain.card.PackagedCharacter
import com.example.chatbar.domain.card.PackagedCharacterCard
import com.example.chatbar.domain.card.validateForImport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FishAudioSharedContractTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val legacy = """{"id":"v1","sessionId":"s1","messageId":"m1","sourceOrder":4,"sourceSegmentKind":"DIALOGUE","sourceSpeakerName":"Alice","sourceText":"source","taggedText":"[happy]source","characterId":"c1","characterName":"Alice","voice":{"referenceId":"ref1","title":"Voice"},"fishModelId":"s1","audioPath":"audio/v1.mp3","durationMs":500,"byteLength":120,"createdAt":1,"updatedAt":2}"""

    @Test fun `legacy missing and explicit null fields retain voice snapshot and source fallback`() {
        listOf(legacy, legacy.dropLast(1) + """, "messageVersionId":null,"anchorId":null,"synthesisText":null}""").forEach { raw ->
            val voice = json.decodeFromString(GeneratedVoiceMessage.serializer(), raw)
            assertNull(voice.messageVersionId)
            assertNull(voice.anchorId)
            assertNull(voice.synthesisText)
            assertEquals("source", voice.effectiveSynthesisText)
            assertEquals("audio/v1.mp3", voice.audioPath)
            assertEquals(voice, json.decodeFromString(GeneratedVoiceMessage.serializer(), json.encodeToString(GeneratedVoiceMessage.serializer(), voice)))
        }
    }

    @Test fun `generated voice serialized contract preserves snapshots without derived fields`() {
        val voice = json.decodeFromString(GeneratedVoiceMessage.serializer(), legacy).copy(
            messageVersionId = "version", anchorId = "anchor", synthesisText = "translated",
        )
        val encoded = json.encodeToString(GeneratedVoiceMessage.serializer(), voice)
        val value = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("id", "sessionId", "messageId", "messageVersionId", "anchorId", "sourceOrder",
            "sourceSegmentKind", "sourceSpeakerName", "sourceText", "synthesisText", "taggedText", "characterId",
            "characterName", "voice", "fishModelId", "audioPath", "durationMs", "byteLength", "createdAt", "updatedAt"), value.keys)
        assertEquals("translated", voice.effectiveSynthesisText)
        assertEquals("source", value.getValue("sourceText").jsonPrimitive.content)
        assertEquals(voice, json.decodeFromString(GeneratedVoiceMessage.serializer(), encoded))
    }

    @Test fun `legacy anchor state defaults and current offset identities round trip`() {
        val old = json.decodeFromString(VoiceAnchorState.serializer(), """{"messageId":"m","displayContentSnapshot":"old","updatedAt":3}""")
        assertEquals("", old.sessionId)
        assertNull(old.messageVersionId)
        assertEquals(emptyList(), old.anchors)
        val state = VoiceAnchorPolicy.initialState("m", "【Alice】[hello]", "version", "session")
        val encoded = json.encodeToString(VoiceAnchorState.serializer(), state)
        assertEquals(state, json.decodeFromString(VoiceAnchorState.serializer(), encoded))
        val oldJson = Json.parseToJsonElement(json.encodeToString(VoiceAnchorState.serializer(), old)).jsonObject
        assertEquals(JsonNull, oldJson["messageVersionId"])
    }

    @Test fun `both character package modes retain binding independently from generated audio`() {
        CharacterEditMode.entries.forEach { mode ->
            val binding = FishAudioVoiceBinding("reference", "private fixture", visibility = "private", languages = listOf("zh"))
            val data = CharacterCardPackage(exportedAt = 1, card = PackagedCharacterCard("card", editMode = mode,
                characters = listOf(PackagedCharacter("Alice", fishAudioVoice = binding))))
            data.validateForImport()
            val restored = json.decodeFromString(CharacterCardPackage.serializer(), json.encodeToString(CharacterCardPackage.serializer(), data))
            assertEquals(mode, restored.card.editMode)
            assertEquals(binding, restored.card.characters.single().fishAudioVoice)
        }
    }

    @Test fun `TTS serialized API keys stay distinct from entity and binding fields`() {
        val request = FishAudioRequestFactory.tts("https://fixture.invalid", "fixture-key", "s1", "voice-id", "text")
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        val payload = Json.parseToJsonElement(body).jsonObject
        assertEquals(setOf("text", "reference_id", "format", "sample_rate", "mp3_bitrate", "latency"), payload.keys)
        assertEquals("44100", payload.getValue("sample_rate").jsonPrimitive.content)
        assertEquals("64", payload.getValue("mp3_bitrate").jsonPrimitive.content)
        assertEquals("s1", request.header("model"))
    }
}
