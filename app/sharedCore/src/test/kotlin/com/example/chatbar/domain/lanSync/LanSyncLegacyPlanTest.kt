package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.FormatCard
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncLegacyPlanTest {
    private fun snapshot() = LanSyncSnapshot(protocol = 1, deviceId = "synthetic", coveredTypes = LanSyncScope.legacyChatTypes,
        records = listOf(FormatCard("format", "合成格式", "保留正文", isDefault = true, createdAt = 1).let {
            LanSyncRecord("format_cards", it.id, lanSyncJson.encodeToJsonElement(FormatCard.serializer(), it).jsonObject)
        }))
    @Test fun compatibleBusinessBodyIsValidatedWithoutRewritingLegacyEnvelopeOrLocalPreference() {
        val old = snapshot(); val original = old.records.single().body.toString()
        LanSyncScope.validateLegacyPending(old)
        assertEquals(1, old.protocol); assertEquals(original, old.records.single().body.toString())
        assertEquals(JsonPrimitive(true), old.records.single().body["isDefault"])
    }
    @Test fun incompatibleLegacyBodyAndUnknownVersionRefuseWithExactReasonAndRetainOriginal() {
        val old = snapshot()
        val changed = old.copy(records = old.records.map { it.copy(body = JsonObject(it.body + ("unsupportedField" to JsonPrimitive(1)))) })
        for (unsupported in listOf(changed, old.copy(protocol = 99), old.copy(coveredTypes = old.coveredTypes + "moment_tasks"))) {
            val original = unsupported.toString()
            val failure = assertFailsWith<IllegalArgumentException> { LanSyncScope.validateLegacyPending(unsupported) }
            assertEquals("SYNC_LEGACY_PLAN_UNSUPPORTED", failure.message)
            assertEquals(original, unsupported.toString())
        }
    }
}
