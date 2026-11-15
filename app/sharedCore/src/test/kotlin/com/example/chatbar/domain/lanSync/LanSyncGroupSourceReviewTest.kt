package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LanSyncGroupSourceReviewTest {
    private fun withFixture(work: (LanSyncGroupNativePlannerTest.Fixture) -> Unit) {
        val root = Files.createTempDirectory("synthetic-group-source-review-").toRealPath()
        try { work(LanSyncGroupNativePlannerTest.Fixture(root)) } finally { check(root.toFile().deleteRecursively()) }
    }
    private fun review(f: LanSyncGroupNativePlannerTest.Fixture, plan: LanSyncGroupNativePlan, selected: Set<String>): LanSyncGroupSourceReview {
        val view = f.view(); val evidence = LanSyncGroupRecordEvidence(requireNotNull(view.history), requireNotNull(view.ledgerCheckpoint))
        val service = LanSyncGroupDependencies(f.source.cipher.group, f.source.backend, f.custody(), f.source.work, f.workspace.resourceStore, { _, _, _ -> })
        view.authenticatedVersions.forEach { metadata -> requireNotNull(metadata.envelope.version).changes.filterValues { it.content != null }.keys.forEach { unit ->
            service.membership(view, metadata, unit).membership?.let(evidence::offer)
        } }
        // Deliberately use only retained frontier bodies. Tests must not invent an old body from its hash.
        val dependencies = plan.units.values.flatMap { row -> row.incoming.values.map { available ->
            LanSyncGroupMembershipRequest(available.version, row.unit) to LanSyncGroupDependencyResult(LanSyncGroupDependencyStage.UNIT_CONTENT_AVAILABLE, available)
        } }.toMap()
        return LanSyncGroupSourceReview.prepare(plan, view, selected, dependencies, evidence)
    }
    @Test fun deletingALocalUnitRequiresItsExactFrozenConfirmationWhileANewIndependentUnitDoesNot() = withFixture { f ->
        val a = "character_cards/a"; val b = "character_cards/b"
        f.card("a", "保留真实旧正文"); f.publish(setOf(a)); val applied = f.apply(f.plan(emptyMap()))
        Files.delete(f.target.resolve("entities/character_cards/a.json"))
        LanSyncJsonFiles.write(f.target.resolve("entities/character_cards/b.json"), CharacterCard.serializer(), CharacterCard("b", "独立新增角色", createdAt = 1, updatedAt = 1))
        val plan = f.plan(applied)
        val addition = review(f, plan, setOf(b)); assertTrue(addition.requiredBindings.isEmpty())
        assertEquals(setOf(b), addition.authorize().units.keys)
        val deletion = review(f, plan, setOf(a))
        assertEquals(setOf(a), deletion.requiredBindings.keys)
        assertTrue(deletion.units.getValue(a).current.isEmpty())
        assertTrue(deletion.units.getValue(a).versions.values.flatten().any { it.text("name") == "保留真实旧正文" })
        assertEquals("SYNC_GROUP_SOURCE_CONFIRMATION_REQUIRED", assertFailsWith<IllegalArgumentException> { deletion.authorize() }.message)
        assertEquals("SYNC_GROUP_SOURCE_CONFIRMATION_REQUIRED", assertFailsWith<IllegalArgumentException> { deletion.authorize(mapOf(a to "0".repeat(64))) }.message)
        assertEquals(setOf(a), deletion.authorize(deletion.requiredBindings).units.keys)
    }
    @Test fun aMissingPreviouslyAppliedBodyCannotBeReplacedWithAnInventedAncestorEvenAfterConfirmation() = withFixture { f ->
        val a = "character_cards/a"; f.card("a", "先前正文"); val first = f.publish(setOf(a)); val applied = f.apply(f.plan(emptyMap()))
        f.card("a", "新收到的正文"); f.publish(setOf(a))
        LanSyncJsonFiles.write(f.target.resolve("entities/character_cards/a.json"), CharacterCard.serializer(), CharacterCard("a", "本机不同正文", createdAt = 1, updatedAt = 1))
        val actual = review(f, f.plan(applied), setOf(a)); val row = actual.units.getValue(a)
        assertEquals(setOf(LanSyncGroupMembershipRequest(first.reference(), a)), row.missingBodies)
        assertFalse(first.reference() in row.versions)
        assertTrue(row.versions.values.flatten().any { it.text("name") == "新收到的正文" })
        assertEquals("SYNC_GROUP_SOURCE_REVIEW_EVIDENCE_MISSING", assertFailsWith<IllegalArgumentException> { actual.authorize(actual.requiredBindings) }.message)
    }
    @Test fun laterSourceChangesProduceADifferentFrozenAuthorizationBinding() = withFixture { f ->
        val a = "character_cards/a"
        Files.createDirectories(f.target.resolve("entities/character_cards"))
        LanSyncJsonFiles.write(f.target.resolve("entities/character_cards/a.json"), CharacterCard.serializer(), CharacterCard("a", "第一次正文", createdAt = 1, updatedAt = 1))
        val first = review(f, f.plan(emptyMap()), setOf(a)).authorize()
        LanSyncJsonFiles.write(f.target.resolve("entities/character_cards/a.json"), CharacterCard.serializer(), CharacterCard("a", "第二次正文", createdAt = 1, updatedAt = 1))
        val second = review(f, f.plan(emptyMap()), setOf(a)).authorize()
        assertNotEquals(first.binding, second.binding)
        assertEquals("第一次正文", first.units.getValue(a).current.single().text("name"))
        assertEquals("第二次正文", second.units.getValue(a).current.single().text("name"))
    }
}
