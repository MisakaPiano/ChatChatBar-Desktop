package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncDetailedReviewTest {
    @Test fun completeFieldPagingPreservesUnicodeAndKeepsArchiveMessageCountsSeparateFromRecords() {
        val content = "a".repeat(8191) + "🚀" + "尾部".repeat(5000)
        val card = LanSyncRecord("character_cards", "card", lanSyncJson.encodeToJsonElement(CharacterCard.serializer(),
            CharacterCard("card", "合成", basicSetting = content, createdAt = 1, updatedAt = 1)).jsonObject)
        val slot = LanSyncRecord("save_slots", "slot", lanSyncJson.encodeToJsonElement(SaveSlot.serializer(),
            SaveSlot(schemaVersion = 8, id = "slot", sessionId = "session", name = "合成历史",
                packageRef = SaveSlotPackageRef("synthetic.cbsave", messageCount = 4096), createdAt = 1)).jsonObject)
        val detail = LanSyncDetailedUnit(LanSyncReviewHeader("synthetic", null, null, null,
            setOf(card.key, slot.key), emptySet()), listOf(card, slot), emptyList())
        val pages = detail.fieldPage(card.key, "basicSetting", true, 0).second
        assertEquals(content, (0 until pages).joinToString("") { detail.fieldPage(card.key, "basicSetting", true, it).first })
        assertEquals(2, detail.computer.size); assertEquals(0, detail.computerMessages); assertEquals(4096L, detail.computerArchivedMessages)
        assertEquals("此端正文已结束", detail.fieldPage(card.key, "basicSetting", true, pages).first)
    }
}
