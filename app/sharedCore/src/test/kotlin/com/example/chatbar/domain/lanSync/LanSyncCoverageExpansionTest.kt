package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.*
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.test.*

class LanSyncCoverageExpansionTest {
    private val oldTypes = setOf("character_cards")
    private val newTypes = oldTypes + "format_cards"
    private fun card(id: String) = LanSyncRecord("character_cards", id,
        lanSyncJson.encodeToJsonElement(CharacterCard.serializer(), CharacterCard(id, id, createdAt = 1, updatedAt = 1)).jsonObject)
    private fun format(id: String) = LanSyncRecord("format_cards", id,
        lanSyncJson.encodeToJsonElement(FormatCard.serializer(), FormatCard(id, id, "合成格式", createdAt = 1)).jsonObject)
    @Test fun matchingExpansionRetainsOldDeletionAuthorityAndBothIndependentNewTypeAdds() {
        val old = LanSyncSnapshot(deviceId = "computer", records = listOf(card("keep"), card("deleted")), coveredTypes = oldTypes)
        val baseline = LanSyncBaseline("phone", "old-revision", old.hashes(), ownerId = "computer", coveredTypes = oldTypes)
        val left = old.copy(records = listOf(card("keep"), format("pc-only")), coveredTypes = newTypes)
        val right = old.copy(deviceId = "phone", records = old.records + format("phone-only"), coveredTypes = newTypes)
        val plan = LanSyncPlanner.plan(left, right, baseline, emptyMap(), UUID.randomUUID().toString())
        assertEquals(setOf("character_cards/keep", "format_cards/pc-only", "format_cards/phone-only"), plan.snapshot.records.map { it.key }.toSet())
        assertEquals(newTypes, plan.snapshot.coveredTypes)
        assertEquals(oldTypes, baseline.coveredTypes); assertEquals(old.hashes(), baseline.hashes)
        val shared = baseline.copy(revision = plan.transaction, hashes = plan.snapshot.hashes(), coveredTypes = newTypes)
        val deleted = plan.snapshot.copy(deviceId = "computer", records = plan.snapshot.records.filterNot { it.id == "pc-only" })
        assertEquals(true, LanSyncCompare.compare(deleted, plan.snapshot.copy(deviceId = "phone"), shared).automatic["format_cards/pc-only"])
    }
    @Test fun mixedEndpointCoverageAndScopeShrinkRefuseWithoutChangingStoredAncestor() {
        val old = LanSyncSnapshot(deviceId = "computer", records = listOf(card("keep")), coveredTypes = oldTypes)
        val baseline = LanSyncBaseline("phone", "old-revision", old.hashes(), ownerId = "computer", coveredTypes = oldTypes)
        assertFailsWith<IllegalArgumentException> { LanSyncCompare.compare(old.copy(coveredTypes = newTypes), old.copy(deviceId = "phone"), baseline) }
        assertFailsWith<IllegalArgumentException> { LanSyncCompare.compare(old, old.copy(deviceId = "phone"), baseline.copy(coveredTypes = newTypes)) }
        assertEquals(old.hashes(), baseline.hashes); assertEquals("old-revision", baseline.revision)
    }
}
