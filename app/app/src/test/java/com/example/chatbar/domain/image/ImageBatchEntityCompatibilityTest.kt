package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.SaveSlot
import com.example.chatbar.data.repository.mergeSettingsDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ImageBatchEntityCompatibilityTest {
    private val json=Json { encodeDefaults=true; ignoreUnknownKeys=true }
    @Test fun oldSessionsAndSaveSlotsStartWithDisabledSafeDefaults() {
        val session=json.decodeFromString<ChatSession>("""{"id":"s","characterCardId":"c","title":"synthetic","createdAt":1,"updatedAt":1}""")
        val slot=json.decodeFromString<SaveSlot>("""{"id":"slot","sessionId":"s","name":"synthetic","createdAt":1}""")
        assertEquals(ImageBatchPreferences(),session.imageBatchPreferences)
        assertEquals(ImageBatchPreferences(),slot.imageBatchPreferences)
    }
    @Test fun sessionAndSaveSlotRetainFractionalPacingWithoutApprovingRuntimePayment() {
        val saved=ImageBatchPreferences(true,4,3,ImageBatchPacing(ImageBatchInterval.RANGE,
            minMillis=1500,maxMillis=2300),allowAnlasSpending=true)
        val session=ChatSession.create("c","synthetic").copy(imageBatchPreferences=saved)
        val slot=SaveSlot(id="slot",sessionId=session.id,name="synthetic",createdAt=1,imageBatchPreferences=saved)
        assertEquals(saved,json.decodeFromString<ChatSession>(json.encodeToString(session)).imageBatchPreferences)
        val raw=json.encodeToString(slot)
        assertEquals(saved,json.decodeFromString<SaveSlot>(raw).imageBatchPreferences)
        assertFalse(raw.contains("confirmedBatchAnlas"));assertFalse(raw.contains("paymentConfirmation"))
    }
    @Test fun savingBatchDraftKeepsNewerMemoryAndAnotherConversationIndependent() {
        val baseline=ChatSession.create("c","synthetic")
        val prefs=ImageBatchPreferences(true,4,3)
        val latest=baseline.copy(longTermMemory="newer saved memory")
        val merged=mergeSettingsDraft(ChatSession.serializer(),baseline,baseline.copy(imageBatchPreferences=prefs),latest)
        assertEquals(prefs,merged.imageBatchPreferences)
        assertEquals(latest.longTermMemory,merged.longTermMemory)
        assertEquals(ImageBatchPreferences(),baseline.imageBatchPreferences)
    }
}
