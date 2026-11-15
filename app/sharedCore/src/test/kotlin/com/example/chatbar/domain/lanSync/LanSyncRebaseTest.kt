package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.FormatCard
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncRebaseTest {
    private val card = LanSyncRecord("character_cards", "card", lanSyncJson.encodeToJsonElement(CharacterCard.serializer(),
        CharacterCard("card", "合成卡", createdAt = 1, updatedAt = 1)).jsonObject)
    private val format = LanSyncRecord("format_cards", "format", lanSyncJson.encodeToJsonElement(FormatCard.serializer(),
        FormatCard("format", "合成格式", "合成正文", createdAt = 1)).jsonObject)
    private val snapshot = LanSyncSnapshot(deviceId = "computer", records = listOf(card, format))
    private val old = LanSyncPlan("00000000-0000-0000-0000-000000000001", snapshot, emptyMap())
    private val pending = LanSyncPending("phone", old, snapshot.hashes(), snapshot.hashes(), localApplied = true)

    @Test fun scopeExpansionKeepsOldDeletionAuthorityAndDoesNotPromoteLocalOnlyHashes() {
        val common = snapshot.copy(records = listOf(card), coveredTypes = setOf("character_cards"))
        val baseline = LanSyncBaseline("phone", "common", common.hashes(), ownerId = "computer", coveredTypes = common.coveredTypes)
        assertEquals(common.hashes(), LanSyncRebase.ancestorHashes(pending, baseline, snapshot, snapshot.copy(deviceId = "phone"), false))
        val mixed = LanSyncRecord("vector_chunks", "chunk", buildJsonObject {
            put("id", "chunk"); put("sourceType", "CHARACTER_DOCUMENT"); put("sourceId", "card")
        })
        val expanded = snapshot.copy(records = snapshot.records + mixed)
        val later = pending.copy(plan = old.copy(snapshot = expanded), expectedLocal = expanded.hashes())
        assertEquals(common.hashes(), LanSyncRebase.ancestorHashes(later, baseline, expanded, expanded.copy(deviceId = "phone"), false))
        // A pre-existing unit deliberately absent from the captured ancestor must stay absent.
        assertTrue(LanSyncRebase.ancestorHashes(pending.copy(expectedLocal = emptyMap()), baseline, snapshot,
            snapshot.copy(deviceId = "phone"), false).isEmpty())
    }

    @Test fun deferredDeletionRequiresAppliedIdenticalUnresolvedNewTypeAndCannotOverrideAnExplicitChoice() {
        val plan = old.copy(transaction = "00000000-0000-0000-0000-000000000002", supersedes = old.transaction,
            commonCoveredTypesBefore = setOf("character_cards"))
        val afterDeletion = snapshot.copy(records = listOf(card))
        val proof = assertNotNull(LanSyncRebase.deferred(pending, afterDeletion, plan, true))
        assertEquals(setOf(format.key), proof.recordKeys)
        assertEquals(listOf(card), LanSyncRebase.localPlan(plan, proof, old).snapshot.records)
        assertNull(LanSyncRebase.deferred(pending, afterDeletion, plan, false))
        assertNull(LanSyncRebase.deferred(pending, afterDeletion, plan.copy(resolvedUnits = setOf(format.unit)), true))
        assertFails { LanSyncRebase.localPlan(plan.copy(resolvedUnits = setOf(format.unit)), proof, old) }
        assertFails { LanSyncRebase.localPlan(plan, proof.copy(recordKeys = setOf(card.key)), old) }
        assertNull(LanSyncRebase.deferred(pending, afterDeletion, plan.copy(snapshot = snapshot.copy(records = listOf(card,
            format.copy(body = JsonObject(format.body + ("name" to JsonPrimitive("后续修改"))))))), true))
        assertEquals(snapshot, plan.snapshot)
    }
}
