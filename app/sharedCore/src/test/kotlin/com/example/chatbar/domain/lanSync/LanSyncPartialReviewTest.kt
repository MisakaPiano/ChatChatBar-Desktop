package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.*
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncPartialReviewTest {
    private fun card(id: String, name: String = id) = LanSyncRecord("character_cards", id,
        lanSyncJson.encodeToJsonElement(CharacterCard.serializer(), CharacterCard(id, name, createdAt = 1, updatedAt = 1)).jsonObject)
    private fun snapshot(device: String, vararg rows: LanSyncRecord) = LanSyncSnapshot(deviceId = device, records = rows.toList())
    private fun baseline(before: LanSyncSnapshot) = LanSyncBaseline("phone", "old", before.hashes(), ownerId = "computer")
    private fun proposed(left: LanSyncSnapshot, right: LanSyncSnapshot, common: LanSyncBaseline) =
        LanSyncPlanner.plan(left, right, common, emptyMap(), UUID.randomUUID().toString())

    @Test fun unconfirmedDeletionPreservesEachOriginalViewAndCompletesIndependentAdditionsWithoutResurrection() {
        val old = snapshot("computer", card("deleted")); val common = baseline(old)
        val left = snapshot("computer", card("deleted"), card("new-a"))
        val right = snapshot("phone", card("new-b"))
        val plan = LanSyncPartialReview.prepare(proposed(left, right, common), left, right, common)
        assertEquals(listOf("character_cards/deleted"), plan.heldReviews.map { it.unit })
        val pc = LanSyncPartialReview.localPlan(plan, "computer").snapshot
        val phone = LanSyncPartialReview.localPlan(plan, "phone").snapshot
        assertEquals(setOf("deleted", "new-a", "new-b"), pc.records.map { it.id }.toSet())
        assertEquals(setOf("new-a", "new-b"), phone.records.map { it.id }.toSet())
        val (hashes, components) = LanSyncPartialReview.baseline(plan, common)
        assertEquals(old.hashes().getValue("character_cards/deleted"), hashes["character_cards/deleted"])
        assertEquals(3, hashes.size)
        val partial = common.copy(revision = plan.transaction, hashes = hashes, componentRecords = components)
        val next = proposed(pc, phone, partial)
        val stillHeld = LanSyncPartialReview.prepare(next, pc, phone, partial)
        assertEquals(plan.heldReviews.single().binding, stillHeld.heldReviews.single().binding)
        assertFalse(LanSyncPartialReview.localPlan(stillHeld, "phone").snapshot.records.any { it.id == "deleted" })
        val confirmed = LanSyncPartialReview.prepare(next, pc, phone, partial,
            mapOf(stillHeld.heldReviews.single().unit to stillHeld.heldReviews.single().binding))
        assertTrue(confirmed.heldReviews.isEmpty())
        assertFalse(confirmed.snapshot.records.any { it.id == "deleted" })
        assertFalse(LanSyncPartialReview.baseline(confirmed, partial).first.containsKey("character_cards/deleted"))
    }
    @Test fun changedFrozenContentInvalidatesAnEarlierConfirmationAndTamperedHeldBodiesRefuse() {
        val old = snapshot("computer", card("deleted")); val common = baseline(old)
        val left = old; val right = snapshot("phone")
        val held = LanSyncPartialReview.prepare(proposed(left, right, common), left, right, common)
        val proof = held.heldReviews.single()
        val tampered = held.copy(heldReviews = listOf(proof.copy(computer = listOf(card("deleted", "篡改正文")))))
        assertEquals("SYNC_REVIEW_BINDING", assertFailsWith<IllegalArgumentException> {
            LanSyncPartialReview.localPlan(tampered, "computer")
        }.message)
        val changed = snapshot("computer", card("deleted", "新正文"))
        // A concurrent modification becomes an ordinary conflict rather than reusing a stale automatic deletion decision.
        val compared = LanSyncCompare.compare(changed, right, common)
        assertEquals(1, compared.conflicts.size)
        assertNotEquals(proof.binding, LanSyncPartialReview.reviewBinding(proof.unit, changed.records, emptyList(), common))
        assertEquals("SYNC_REVIEW_BASELINE_CHANGED", assertFailsWith<IllegalArgumentException> {
            LanSyncPartialReview.baseline(held, common.copy(hashes = emptyMap()))
        }.message)
    }
    @Test fun automaticMessageVersionReductionIsHeldWhileAnUnrelatedRecordStillArrives() {
        val session = LanSyncRecord("chat_sessions", "session", lanSyncJson.encodeToJsonElement(ChatSession.serializer(),
            ChatSession("session", "card", "合成会话", createdAt = 1, updatedAt = 1)).jsonObject)
        val original = ChatMessage("message", "session", MessageRole.ASSISTANT, "保留版本",
            alternatives = listOf("旧版本", "保留版本"), alternativeVersionIds = listOf("version-0", "version-1"),
            currentAlternativeIndex = 1, currentAlternativeVersionId = "version-1", createdAt = 1, updatedAt = 1)
        fun row(message: ChatMessage) = LanSyncRecord("chat_messages", "message",
            lanSyncJson.encodeToJsonElement(ChatMessage.serializer(), message).jsonObject)
        val left = snapshot("computer", card("card"), session, row(original))
        val right = snapshot("phone", card("card"), session,
            row(original.copy(alternatives = listOf("保留版本"), alternativeVersionIds = listOf("version-1"), currentAlternativeIndex = 0)), card("unrelated"))
        val common = baseline(left)
        val plan = LanSyncPartialReview.prepare(proposed(left, right, common), left, right, common)
        assertEquals(listOf("conversation/session"), plan.heldReviews.map { it.unit })
        assertEquals(left.records.single { it.type == "chat_messages" },
            LanSyncPartialReview.localPlan(plan, "computer").snapshot.records.single { it.type == "chat_messages" })
        assertEquals(right.records.single { it.type == "chat_messages" },
            LanSyncPartialReview.localPlan(plan, "phone").snapshot.records.single { it.type == "chat_messages" })
        assertTrue(LanSyncPartialReview.localPlan(plan, "computer").snapshot.records.any { it.id == "unrelated" })
    }
    @Test fun aHeldFormatDeletionAlsoHoldsItsRequiredSessionEdgeWhileIndependentCardsAdvance() {
        val format = LanSyncRecord("format_cards", "format", lanSyncJson.encodeToJsonElement(FormatCard.serializer(),
            FormatCard("format", "合成格式", "合成内容", createdAt = 1)).jsonObject)
        val session = ChatSession("session", "card", "关联会话", formatCardId = "format", createdAt = 1, updatedAt = 1)
        fun row(value: ChatSession) = LanSyncRecord("chat_sessions", value.id,
            lanSyncJson.encodeToJsonElement(ChatSession.serializer(), value).jsonObject)
        val left = snapshot("computer", card("card"), format, row(session))
        val right = snapshot("phone", card("card"), row(session.copy(formatCardId = null)), card("independent"))
        val common = baseline(left)
        val plan = LanSyncPartialReview.prepare(proposed(left, right, common), left, right, common)
        assertEquals(setOf("format_cards/format", "conversation/session"), plan.heldReviews.map { it.unit }.toSet())
        val pc = LanSyncPartialReview.localPlan(plan, "computer").snapshot
        val phone = LanSyncPartialReview.localPlan(plan, "phone").snapshot
        assertEquals("format", pc.records.single { it.type == "chat_sessions" }.text("formatCardId"))
        assertNull(phone.records.single { it.type == "chat_sessions" }.text("formatCardId"))
        assertTrue(pc.records.any { it.id == "independent" }); assertFalse(phone.records.any { it.type == "format_cards" })
        val (hashes, _) = LanSyncPartialReview.baseline(plan, common)
        assertEquals(common.hashes.getValue("conversation/session"), hashes["conversation/session"])
    }
    @Test fun recoveryComparisonHashesNeverBecomeSharedDeletionAuthorityOrResurrectAHeldCopy() {
        val left = snapshot("computer", card("newly-covered")); val right = snapshot("phone")
        val comparisonAncestor = baseline(left)
        val shared = LanSyncBaseline("phone", ownerId = "computer")
        val held = LanSyncPartialReview.prepare(proposed(left, right, comparisonAncestor), left, right, comparisonAncestor,
            commonBaseline = shared)
        assertTrue(held.heldReviews.single().ancestorHashes.isEmpty())
        val (hashes, components) = LanSyncPartialReview.baseline(held, shared)
        assertTrue(hashes.isEmpty())
        val nextShared = shared.copy(revision = held.transaction, hashes = hashes, componentRecords = components)
        val pc = LanSyncPartialReview.localPlan(held, "computer").snapshot
        val phone = LanSyncPartialReview.localPlan(held, "phone").snapshot
        val freshAddition = proposed(pc, phone, nextShared)
        assertTrue(freshAddition.snapshot.records.any { it.id == "newly-covered" })
        val preserved = LanSyncPartialReview.preserveQueuedProposals(freshAddition, pc, phone, held.heldReviews)
        assertTrue(preserved.snapshot.records.isEmpty())
        val stillHeld = LanSyncPartialReview.prepare(preserved, pc, phone, nextShared)
        assertEquals(1, stillHeld.heldReviews.size)
        assertTrue(LanSyncPartialReview.localPlan(stillHeld, "phone").snapshot.records.isEmpty())
        val proof = stillHeld.heldReviews.single()
        val confirmed = LanSyncPartialReview.prepare(preserved, pc, phone, nextShared, mapOf(proof.unit to proof.binding))
        assertTrue(confirmed.heldReviews.isEmpty()); assertTrue(confirmed.snapshot.records.isEmpty())
    }
    @Test fun concurrentlyAddedSessionCannotLoseItsRequiredFormatAndUnrelatedAdditionsStillAdvance() {
        val format = LanSyncRecord("format_cards", "format", lanSyncJson.encodeToJsonElement(FormatCard.serializer(),
            FormatCard("format", "关联格式", "合成格式", createdAt = 1)).jsonObject)
        val old = snapshot("computer", card("card"), format)
        val left = snapshot("computer", card("card"))
        val session = LanSyncRecord("chat_sessions", "new-session", lanSyncJson.encodeToJsonElement(ChatSession.serializer(),
            ChatSession("new-session", "card", "另一端新增会话", formatCardId = "format", createdAt = 1, updatedAt = 1)).jsonObject)
        val right = snapshot("phone", card("card"), format, session, card("independent"))
        val common = baseline(old)
        assertEquals("SYNC_MISSING_FORMAT", assertFailsWith<IllegalArgumentException> { proposed(left, right, common) }.message)
        val draft = LanSyncPlanner.draft(left, right, common, emptyMap(), UUID.randomUUID().toString())
        val plan = LanSyncPartialReview.prepare(draft, left, right, common)
        assertEquals(setOf("format_cards/format", "conversation/new-session"), plan.heldReviews.map { it.unit }.toSet())
        val held = plan.heldReviews.single { it.unit == "format_cards/format" }; assertTrue(held.dependencyIssue)
        val pc = LanSyncPartialReview.localPlan(plan, "computer").snapshot
        val phone = LanSyncPartialReview.localPlan(plan, "phone").snapshot
        assertFalse(pc.records.any { it.type in setOf("format_cards", "chat_sessions") })
        assertTrue(phone.records.any { it.key == "chat_sessions/new-session" })
        assertTrue(phone.records.any { it.key == "format_cards/format" })
        assertTrue(pc.records.any { it.id == "independent" })
        val stillBlocked = LanSyncPartialReview.prepare(draft, left, right, common, mapOf(held.unit to held.binding))
        assertTrue(stillBlocked.heldReviews.single { it.unit == held.unit }.dependencyIssue)
        LanSyncScope.validateGraph(stillBlocked.snapshot)
    }
    @Test fun sharedResourceIdentityDefersItsDependentNewRecordWithoutRenamingOrMixingResourceVersions() {
        val identity = "11111111-1111-1111-1111-111111111111"
        val first = "1".repeat(64); val second = "2".repeat(64)
        fun image(id: String, hash: String) = card(id).let { it.copy(body = JsonObject(it.body +
            ("avatar" to JsonPrimitive("ccb-lan-v1/images/$identity/$hash/png")))) }
        val left = snapshot("computer", image("left", first)).copy(resources = mapOf(first to 5L))
        val right = snapshot("phone", image("left", second), image("new-dependent", second), card("independent"))
            .copy(resources = mapOf(second to 7L))
        val common = baseline(left)
        val draft = proposed(left, right, common)
        val plan = LanSyncPartialReview.prepare(draft, left, right, common)
        assertEquals(setOf("character_cards/left", "character_cards/new-dependent"), plan.heldReviews.map { it.unit }.toSet())
        val pc = LanSyncPartialReview.localPlan(plan, "computer").snapshot
        val phone = LanSyncPartialReview.localPlan(plan, "phone").snapshot
        assertEquals(setOf(first), pc.resources.keys); assertEquals(setOf(second), phone.resources.keys)
        assertFalse(pc.records.any { it.id == "new-dependent" }); assertTrue(phone.records.any { it.id == "new-dependent" })
        assertTrue(pc.records.any { it.id == "independent" })
        val root = plan.heldReviews.single { it.unit == "character_cards/left" }
        val confirmed = LanSyncPartialReview.prepare(draft, left, right, common, mapOf(root.unit to root.binding))
        assertTrue(confirmed.heldReviews.isEmpty()); assertEquals(setOf(second), confirmed.snapshot.resources.keys)
    }
}
