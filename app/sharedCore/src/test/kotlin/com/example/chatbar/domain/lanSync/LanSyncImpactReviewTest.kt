package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncImpactReviewTest {
    private val session = ChatSession("session", "card", "合成审阅会话", createdAt = 1, updatedAt = 1)
    private fun record(message: ChatMessage) = LanSyncRecord("chat_messages", message.id,
        lanSyncJson.encodeToJsonElement(ChatMessage.serializer(), message).jsonObject)
    private fun message() = ChatMessage("m", session.id, MessageRole.ASSISTANT, "当前正文\n不能丢失", alternatives = listOf("旧正文", "当前正文\n不能丢失"),
        alternativeVersionIds = listOf("v0", "v1"), currentAlternativeVersionId = "v1", currentAlternativeIndex = 1,
        images = listOf("ccb-lan-v1/images/11111111-1111-1111-1111-111111111111/${"a".repeat(64)}/png",
            "ccb-lan-v1/images/22222222-2222-2222-2222-222222222222/${"a".repeat(64)}/png"), createdAt = 1, updatedAt = 1)

    @Test fun retainedConversationStillReportsRemovedMessageVersionAndDuplicateByteAttachmentReference() {
        val before = message()
        val after = before.copy(alternatives = listOf(before.content), alternativeVersionIds = listOf("v1"),
            currentAlternativeIndex = 0, images = listOf(before.images[1]))
        val impact = LanSyncImpactReview.changes(listOf(record(before)), listOf(record(after))).single()
        assertEquals(LanSyncRecordChange.REPLACE, impact.change)
        assertEquals(setOf("v0"), impact.removedVersionIds)
        assertEquals(1, impact.removedAttachmentReferences)
        assertTrue(impact.needsConfirmation); assertTrue(impact.needsProtection)
        assertTrue(impact.fields.any { it.field == "alternatives" })
        val renamedReference = before.copy(images = before.images.map { it.replace("11111111-1111-1111-1111-111111111111", "33333333-3333-3333-3333-333333333333") })
        assertTrue(LanSyncImpactReview.changes(listOf(record(before)), listOf(record(renamedReference))).isEmpty())
    }

    @Test fun unilateralAutomaticDeletionHasAccurateRecordAndMessageCountsAndNoInventedAncestorBody() {
        val parent = LanSyncRecord("chat_sessions", session.id, lanSyncJson.encodeToJsonElement(ChatSession.serializer(), session).jsonObject)
        val original = listOf(parent, record(message()))
        val old = LanSyncSnapshot(deviceId = "pc", records = original)
        val baseline = LanSyncBaseline("phone", "old-revision", old.hashes(), ownerId = "pc")
        val deleted = old.copy(records = emptyList()); val phone = old.copy(deviceId = "phone")
        val comparison = LanSyncCompare.compare(deleted, phone, baseline)
        val review = LanSyncImpactReview.automaticReviews(comparison, baseline).single()
        assertTrue(review.needsConfirmation); assertFalse(review.ancestorBodyAvailable)
        assertEquals(0, review.computerRecords); assertEquals(2, review.phoneRecords)
        assertEquals(0, review.computerMessages); assertEquals(1, review.phoneMessages)
        assertEquals(2, review.phoneImpact.count { it.change == LanSyncRecordChange.REMOVE })
        assertEquals(old.hashes().values.single(), review.ancestorHash)
        assertNotEquals(LanSyncImpactReview.binding(comparison), LanSyncImpactReview.binding(LanSyncCompare.compare(old, phone, baseline)))
    }

    @Test fun ordinaryBodyReplacementIsProtectedEvenWithoutADeletionAndIndependentAdditionDoesNotNeedOldContentProtection() {
        val before = message(); val after = before.copy(content = "新的正文", alternatives = listOf("旧正文", "新的正文"))
        val replacement = LanSyncImpactReview.changes(listOf(record(before)), listOf(record(after))).single()
        assertTrue(replacement.needsProtection)
        assertTrue(replacement.fields.any { it.field == "content" && it.before == JsonPrimitive(before.content) && it.after == JsonPrimitive(after.content) })
        val addition = LanSyncImpactReview.changes(emptyList(), listOf(record(before))).single()
        assertFalse(addition.needsProtection); assertFalse(addition.needsConfirmation)
    }

    @Test fun replacingADistinctReferenceWithASecondOccurrenceOfSurvivingEqualBytesStillRequiresConfirmation() {
        val before = message()
        val after = before.copy(images = listOf(before.images[1], before.images[1]))
        val impact = LanSyncImpactReview.changes(listOf(record(before)), listOf(record(after))).single()
        assertEquals(1, impact.removedAttachmentReferences)
        assertTrue(impact.needsConfirmation)
    }

    @Test fun aRealBodyEditWithEquivalentRenamedIndependentAliasesDoesNotInventAttachmentDeletion() {
        val before = message()
        val after = before.copy(content = "真实修改的新正文", alternatives = listOf("旧正文", "真实修改的新正文"), images = before.images.mapIndexed { index, path ->
            path.replace(if (index == 0) "11111111-1111-1111-1111-111111111111" else "22222222-2222-2222-2222-222222222222",
                if (index == 0) "33333333-3333-3333-3333-333333333333" else "44444444-4444-4444-4444-444444444444")
        })
        val impact = LanSyncImpactReview.changes(listOf(record(before)), listOf(record(after))).single()
        assertEquals(0, impact.removedAttachmentReferences)
        assertFalse(impact.needsConfirmation); assertTrue(impact.needsProtection)
    }

    @Test fun legacyInlineSlotMessageAndVersionReductionsAreReviewedWithinTheRetainedSlotRecord() {
        val first = message(); val second = first.copy(id = "another-message")
        val slot = SaveSlot(id = "slot", sessionId = session.id, name = "合成旧内联存档", messages = listOf(first, second), createdAt = 1)
        fun row(value: SaveSlot) = LanSyncRecord("save_slots", value.id, lanSyncJson.encodeToJsonElement(SaveSlot.serializer(), value).jsonObject)
        val reduced = first.copy(alternatives = listOf(first.content), alternativeVersionIds = listOf("v1"), currentAlternativeIndex = 0)
        val impact = LanSyncImpactReview.changes(listOf(row(slot)), listOf(row(slot.copy(messages = listOf(reduced))))).single()
        assertEquals(LanSyncRecordChange.REPLACE, impact.change)
        assertEquals(setOf(second.id), impact.removedEmbeddedMessageIds)
        assertTrue("${first.id}/v0" in impact.removedVersionIds)
        assertTrue(impact.needsConfirmation)
    }
}
