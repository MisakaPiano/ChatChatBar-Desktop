package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.VoiceAnchorState
import kotlinx.serialization.json.Json
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

/** Android module consumes the one shared serializer/factory through its public module boundary. */
class FishAudioSharedConsumerTest {
    @Test fun `Android consumer decodes shared request and anchor serializers`() {
        val request = FishAudioRequestFactory.tts("https://fixture.invalid", "fixture-key", "s1", "ref", "spoken")
        val payload = Json.decodeFromString(FishAudioTtsRequest.serializer(), Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        assertEquals("ref", payload.referenceId)
        assertEquals("spoken", payload.text)
        // A clock-valued default may be elided at encoding and regenerated a millisecond later.
        // Pin this serializer fixture so the full equality assertion tests the wire contract.
        val state = VoiceAnchorPolicy.initialState("message", "[spoken]", "version", "session").copy(updatedAt = 1)
        assertEquals(state, Json.decodeFromString(VoiceAnchorState.serializer(), Json.encodeToString(VoiceAnchorState.serializer(), state)))
    }
}
