package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Real Node/native IO, signatures and offline delivery; protection and route are synthetic. */
class LanSyncGroupIncomingReviewTest {
    private class Scene(val group: LanSyncGroupNodeTest.Group) {
        val source = group.End(0); val target = group.End(1)
        lateinit var sn: LanSyncGroupNode; lateinit var tn: LanSyncGroupNode
        val unit = "character_cards/a"
        suspend fun publish(units: Set<String>): LanSyncGroupSourceCommit {
            val review = sn.inspect().review(units); val commit = sn.commit(review, review.requiredBindings)
            source.send(sn, commit); return commit
        }
        suspend fun seed() {
            sn = source.enroll(); tn = target.enroll(); source.card("a", "retained original")
            publish(setOf(unit)); tn.apply(requireNotNull(target.scan(tn).plan))
            Files.delete(source.data.resolve("entities/$unit.json")); source.card("independent", "new independent")
            publish(setOf(unit, "character_cards/independent")); target.scan(tn)
        }
    }
    private fun withScene(work: suspend (Scene) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-incoming-reduction-").toRealPath()
        try { val s = Scene(LanSyncGroupNodeTest.Group(root)); s.seed(); work(s) }
        finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun defaultHoldsDeletionAcrossRestartWhileIndependentDataAdvancesThenExplicitConfirmationUsesNoAuthorCounter() = withScene { s ->
        var view = s.tn.inspect(); val oldFrontier = view.nativeState.units.getValue(s.unit)
        assertEquals(setOf(s.unit), view.incomingReview?.reductions?.keys)
        assertEquals(setOf("character_cards/independent"), view.plan?.advances?.keys)
        s.tn.apply(requireNotNull(view.plan)); assertEquals("retained original", s.target.name("a"))
        assertEquals("new independent", s.target.name("independent"))
        s.tn = s.target.open(); view = s.tn.inspect()
        assertEquals(oldFrontier, view.nativeState.units.getValue(s.unit)); assertTrue(requireNotNull(view.plan).advances.isEmpty())
        val review = requireNotNull(view.incomingReview)
        assertEquals("retained original", review.reductions.getValue(s.unit).current.single().text("name"))
        assertTrue(review.reductions.getValue(s.unit).proposed.isEmpty())
        assertFailsWith<IllegalArgumentException> { review.acknowledge(mapOf(s.unit to "0".repeat(64))) }
        s.tn.applyIncoming(review.acknowledge(review.requiredBindings)); assertNull(s.target.name("a"))
        assertEquals(0L, s.target.author().state().committedSequence)
        assertTrue(s.target.workspace.recovery.list().isNotEmpty())
        assertTrue(s.target.open().inspect().incomingReview?.reductions.orEmpty().isEmpty())
    }

    @Test fun changedRawSourceOrDiscoveredCausalHeadsRefuseTheOldExactConfirmationBeforeAnyDataWrite() {
        for (change in listOf("raw", "heads")) withScene { s ->
            val review = requireNotNull(s.tn.inspect().incomingReview); val approval = review.acknowledge(review.requiredBindings)
            if (change == "raw") {
                val path = s.target.data.resolve("entities/${s.unit}.json")
                val card = requireNotNull(LanSyncJsonFiles.read(path, CharacterCard.serializer()))
                LanSyncJsonFiles.write(path, CharacterCard.serializer(), card.copy(updatedAt = card.updatedAt + 1))
            } else {
                s.source.card("later", "new causal head"); s.publish(setOf("character_cards/later")); s.target.scan(s.tn)
            }
            val before = s.target.workspace.capture(s.group.identities[1].fingerprint).use { it.sourceFiles }
            assertFailsWith<IllegalArgumentException> { s.tn.applyIncoming(approval) }
            s.target.workspace.capture(s.group.identities[1].fingerprint).use { assertEquals(before, it.sourceFiles) }
            assertEquals("retained original", s.target.name("a")); assertNull(s.target.name("independent"))
            assertEquals(0L, s.target.author().state().committedSequence)
        }
    }

    @Test fun aNewPersistentLaterDecisionPreventsAnOlderIncomingConfirmationFromBypassingTheHold() = withScene { s ->
        val review = requireNotNull(s.tn.inspect().incomingReview); val approval = review.acknowledge(review.requiredBindings)
        val later = s.tn.prepareResolution(mapOf(s.unit to LanSyncGroupResolutionChoice.Later))
        assertNull(s.tn.resolve(later.acknowledge(later.binding)))
        s.tn.applyIncoming(approval)
        assertEquals("retained original", s.target.name("a")); assertEquals("new independent", s.target.name("independent"))
        val reopen = s.target.open().inspect(); assertTrue(s.unit in reopen.reservedUnits)
        assertEquals(review.native.appliedBefore.getValue(s.unit), reopen.nativeState.units.getValue(s.unit))
    }

    @Test fun sharedMediaComponentsRequireEveryReductionConfirmationTogether() = runBlocking {
        val root = Files.createTempDirectory("synthetic-linked-reduction-").toRealPath()
        try {
            val group = LanSyncGroupNodeTest.Group(root); val source = group.End(0); val target = group.End(1)
            val sn = source.enroll(); val tn = target.enroll(); val units = setOf("character_cards/a", "character_cards/b")
            val image = source.data.resolve("images/shared.png"); Files.createDirectories(image.parent); Files.write(image, LanSyncPreviewSample.image())
            units.forEach { unit -> source.card(unit.substringAfter('/'), "shared")
                val path = source.data.resolve("entities/$unit.json"); val card = requireNotNull(LanSyncJsonFiles.read(path, CharacterCard.serializer()))
                LanSyncJsonFiles.write(path, CharacterCard.serializer(), card.copy(avatar = image.toString()))
            }
            suspend fun send() { val review = sn.inspect().review(units); source.send(sn, sn.commit(review, review.requiredBindings)) }
            send(); tn.apply(requireNotNull(target.scan(tn).plan))
            units.forEach { Files.delete(source.data.resolve("entities/$it.json")) }; send()
            val incoming = target.scan(tn); val review = requireNotNull(incoming.incomingReview)
            assertEquals(units, review.reductions.keys); assertTrue(requireNotNull(incoming.plan).advances.isEmpty())
            val onlyA = review.requiredBindings.filterKeys { it.endsWith("/a") }
            assertEquals("SYNC_GROUP_INCOMING_COMPONENT_CONFIRMATION_REQUIRED", assertFailsWith<IllegalArgumentException> { review.acknowledge(onlyA) }.message)
            tn.applyIncoming(review.acknowledge(review.requiredBindings)); assertNull(target.name("a")); assertNull(target.name("b"))
            assertEquals(0L, target.author().state().committedSequence)
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
