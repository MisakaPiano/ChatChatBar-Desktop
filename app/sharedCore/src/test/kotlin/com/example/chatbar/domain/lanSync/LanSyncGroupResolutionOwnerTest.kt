package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Actual native adoption/author/outbox/receipt flow. Local protection and routes are synthetic. */
class LanSyncGroupResolutionOwnerTest {
    private class Scene(val group: LanSyncGroupNodeTest.Group) {
        val a = group.End(0); val b = group.End(1)
        lateinit var an: LanSyncGroupNode; lateinit var bn: LanSyncGroupNode
        lateinit var left: LanSyncGroupSourceCommit; lateinit var right: LanSyncGroupSourceCommit
        val unit = "character_cards/a"
        suspend fun local(node: LanSyncGroupNode, units: Set<String>): LanSyncGroupSourceCommit {
            val review = node.inspect().review(units); return node.commit(review, review.requiredBindings)
        }
        suspend fun seed() {
            an = a.enroll(); bn = b.enroll(); a.card("a", "base")
            val base = local(an, setOf(unit)); a.send(an, base); bn.apply(requireNotNull(b.scan(bn).plan))
            a.card("a", "left"); left = local(an, setOf(unit))
            b.card("a", "right"); right = local(bn, setOf(unit))
            a.send(an, left); b.send(bn, right); a.scan(an)
        }
        suspend fun preview(source: LanSyncGroupResolutionSource = LanSyncGroupResolutionSource.Local,
            copies: Map<String, LanSyncGroupResolutionSource> = emptyMap()) = an.prepareResolution(
                mapOf(unit to LanSyncGroupResolutionChoice.Keep(source, copies)))
        fun acknowledge(preview: LanSyncGroupResolutionPreview) = preview.acknowledge(preview.binding,
            preview.requiredBindings, preview.requiredRestorations)
        fun sequence() = a.author().state().committedSequence
        fun updateOnlyRawTimestamp() {
            val path = a.data.resolve("entities/character_cards/a.json")
            val card = requireNotNull(LanSyncJsonFiles.read(path, CharacterCard.serializer()))
            LanSyncJsonFiles.write(path, CharacterCard.serializer(), card.copy(updatedAt = card.updatedAt + 1))
        }
    }
    private fun withScene(work: suspend (Scene) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-group-resolution-owner-").toRealPath()
        try { val scene = Scene(LanSyncGroupNodeTest.Group(root)); scene.seed(); work(scene) }
        finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun keepingTheSameLocalValueAuthorsOneNewRevisionAndRetryNeverReplaysItOverLaterEdits() = withScene { s ->
        val candidate = s.acknowledge(s.preview()); val before = s.sequence()
        val merged = requireNotNull(s.an.resolve(candidate))
        assertEquals(before + 1, merged.entry.version.authorSequence)
        assertEquals(s.left.entry.version.changes.getValue(s.unit).valueHash, merged.entry.version.changes.getValue(s.unit).valueHash)
        assertEquals(setOf(s.left.entry.version.reference(), s.right.entry.version.reference()), merged.entry.version.changes.getValue(s.unit).previous)
        assertEquals(setOf(merged.entry.version.reference()), s.an.inspect().nativeState.units.getValue(s.unit).frontiers)
        assertNull(s.an.receipt(merged.entry.request.operation, s.a.target))
        s.a.card("a", "later local edit")
        val repeat = requireNotNull(s.a.open().resolve(candidate))
        assertEquals(merged.entry, repeat.entry); assertEquals(before + 1, s.sequence()); assertEquals("later local edit", s.a.name("a"))
    }

    @Test fun adoptedCopiesSurviveRestartAndAreReservedUntilTheirOneBoundSourceOperation() = withScene { s ->
        val preview = s.preview(LanSyncGroupResolutionSource.Version(s.right.entry.version.reference()),
            mapOf("saved-local" to LanSyncGroupResolutionSource.Local))
        val candidate = s.acknowledge(preview); val copied = (preview.resolvedUnits - s.unit).single(); var failed = false
        s.a.hook = { if (it == "RESOLUTION_POST_SOURCE_RETAINED" && !failed) { failed = true; throw IOException("SYNTHETIC_ADOPTION_REPLY_LOST") } }
        assertFailsWith<IOException> { s.an.resolve(candidate) }; s.a.hook = {}
        assertEquals("right", s.a.name("a")); assertEquals("left", s.a.name(copied.substringAfter('/'))); assertEquals(2L, s.sequence())
        val node = s.a.open(); val view = node.inspect()
        assertTrue(view.reservedUnits.containsAll(preview.resolvedUnits)); assertFalse(copied in view.nativeState.units)
        assertFailsWith<IllegalArgumentException> { view.review(setOf(copied)) }
        assertFailsWith<IllegalArgumentException> { node.commit(view.sourceReview(setOf(copied))) }
        val commit = requireNotNull(node.resolve(candidate)); assertEquals(3L, commit.entry.version.authorSequence)
        assertEquals(preview.resolvedUnits, commit.entry.version.changes.keys)
        assertTrue(commit.entry.version.changes.getValue(copied).previous.isEmpty())
        s.a.send(node, commit); s.bn.apply(requireNotNull(s.b.scan(s.bn).plan))
        assertEquals("right", s.b.name("a")); assertEquals("left", s.b.name(copied.substringAfter('/')))
        assertTrue(s.a.open().inspect().reservedUnits.isEmpty())
    }

    @Test fun allLaterSignsNothingAndItsPersistedHoldStillAllowsAnIndependentNativeApply() = runBlocking {
        val root = Files.createTempDirectory("synthetic-group-later-owner-").toRealPath()
        try {
            val group = LanSyncGroupNodeTest.Group(root); val source = group.End(0); val target = group.End(1)
            val sn = source.enroll(); var tn = target.enroll()
            source.card("a", "held"); source.card("b", "independent")
            val review = sn.inspect().review(setOf("character_cards/a", "character_cards/b"))
            val published = sn.commit(review, review.requiredBindings); source.send(sn, published); target.scan(tn)
            val preview = tn.prepareResolution(mapOf("character_cards/a" to LanSyncGroupResolutionChoice.Later))
            assertNull(tn.resolve(preview.acknowledge(preview.binding)))
            assertEquals(0L, target.author().state().committedSequence)
            tn = target.open(); val held = tn.inspect(); assertEquals(setOf("character_cards/a"), held.reservedUnits)
            assertEquals(setOf("character_cards/b"), held.plan?.advances?.keys)
            tn.apply(requireNotNull(held.plan))
            assertNull(target.name("a")); assertEquals("independent", target.name("b"))
            assertFalse("character_cards/a" in tn.inspect().nativeState.units)
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun staleRawOrCausalHeadsBeforeAdoptionCannotExpandTheFrozenApproval() {
        for (change in listOf("raw", "heads")) withScene { s ->
            val candidate = s.acknowledge(s.preview(LanSyncGroupResolutionSource.Version(s.right.entry.version.reference())))
            if (change == "raw") s.updateOnlyRawTimestamp() else {
                s.b.card("independent", "new causal head"); val next = s.local(s.bn, setOf("character_cards/independent"))
                s.b.send(s.bn, next); s.a.scan(s.an)
            }
            assertFailsWith<IllegalArgumentException> { s.an.resolve(candidate) }
            assertEquals("left", s.a.name("a")); assertEquals(2L, s.sequence())
        }
    }

    @Test fun authorizedWalInterruptionReopensTheAdoptionWithIndependentOriginalsAndOneSignature() = withScene { s ->
        val original = Files.readAllBytes(s.a.data.resolve("entities/character_cards/a.json"))
        val oldEntries = s.a.workspace.recovery.list().map { it.transaction }.toSet()
        val candidate = s.acknowledge(s.preview(LanSyncGroupResolutionSource.Version(s.right.entry.version.reference())))
        var interrupted = false
        s.a.journalHook = { stage, _ -> if (stage == "AUTHORIZED" && !interrupted) { interrupted = true; throw IOException("SYNTHETIC_RESOLUTION_WAL_INTERRUPTED") } }
        assertFailsWith<IOException> { s.an.resolve(candidate) }; s.a.journalHook = { _, _ -> }
        val node = s.a.open(); val view = node.inspect()
        assertEquals("right", s.a.name("a")); assertEquals(2L, s.sequence()); assertTrue(s.unit in view.reservedUnits)
        val retained = s.a.workspace.recovery.list().filter { it.transaction !in oldEntries && it.sourceCheckpointGroup == s.group.cipher.group.value }
        assertTrue(retained.isNotEmpty())
        var rawFound = false
        retained.forEach { entry -> s.a.workspace.recovery.verify(entry); s.a.workspace.recovery.originals(entry) { row, path ->
            if (row.relative == "entities/character_cards/a.json") { rawFound = true; assertContentEquals(original, Files.readAllBytes(path)) }
        } }
        assertTrue(rawFound)
        assertEquals(3L, requireNotNull(node.resolve(candidate)).entry.version.authorSequence)
    }

    @Test fun aRawEditAfterAppliedButBeforePostSealIsHeldWithoutReplayingTheChosenTarget() = withScene { s ->
        val preview = s.preview(LanSyncGroupResolutionSource.Version(s.right.entry.version.reference())); val candidate = s.acknowledge(preview)
        var interrupted = false
        s.a.hook = { if (it == "RESOLUTION_LOCAL_DATA_APPLIED" && !interrupted) {
            interrupted = true; s.updateOnlyRawTimestamp(); throw IOException("SYNTHETIC_APPLIED_BEFORE_POST_SEAL")
        } }
        assertFailsWith<IOException> { s.an.resolve(candidate) }; s.a.hook = {}
        val raw = Files.readAllBytes(s.a.data.resolve("entities/character_cards/a.json")); val node = s.a.open(); val view = node.inspect()
        assertEquals(preview.target.hashes(), view.plan?.before?.hashes())
        assertEquals(LanSyncGroupResolutionLocalStage.NEEDS_REVIEW, view.resolutions.single().stage)
        assertFailsWith<IllegalArgumentException> { node.resolve(candidate) }
        assertEquals(2L, s.sequence()); assertContentEquals(raw, Files.readAllBytes(s.a.data.resolve("entities/character_cards/a.json")))
    }

    @Test fun approvedIntentSignedEntryAndSourceReceiptLossResumeExactlyOnceAndKeepLaterNativeEdits() {
        for (point in listOf("APPROVED_AUTHOR_INTENT_RETAINED", "SIGNED_AUTHOR_ENTRY_RETAINED", "NATIVE_SOURCE_INTENT_RETAINED")) withScene { s ->
            val preview = s.preview(LanSyncGroupResolutionSource.Version(s.right.entry.version.reference())); val candidate = s.acknowledge(preview)
            var interrupted = false
            s.a.hook = { if (it == point && !interrupted) { interrupted = true; throw IOException("SYNTHETIC_RESOLUTION_SIGNED_REPLY_LOST") } }
            assertFailsWith<IOException> { s.an.resolve(candidate) }; s.a.hook = {}; s.a.card("a", "later after approval")
            val node = s.a.open(); node.inspect()
            val recovered = requireNotNull(node.resolve(candidate)); val repeated = requireNotNull(node.resolve(candidate))
            assertEquals(3L, recovered.entry.version.authorSequence); assertEquals(recovered.entry, repeated.entry)
            assertEquals(setOf(s.left.entry.version.reference(), s.right.entry.version.reference()), recovered.entry.version.changes.getValue(s.unit).previous)
            assertEquals("later after approval", s.a.name("a")); assertEquals(3L, s.sequence())
        }
    }

    @Test fun changedAuthorHeadAfterLocalAdoptionRequiresReviewRatherThanExpandingTheOldAuthorReservation() = withScene { s ->
        val candidate = s.acknowledge(s.preview(LanSyncGroupResolutionSource.Version(s.right.entry.version.reference())))
        var interrupted = false
        s.a.hook = { if (it == "RESOLUTION_POST_SOURCE_RETAINED" && !interrupted) { interrupted = true; throw IOException("SYNTHETIC_BEFORE_AUTHOR_INTENT") } }
        assertFailsWith<IOException> { s.an.resolve(candidate) }; s.a.hook = {}
        val node = s.a.open(); s.a.card("independent", "approved separately")
        s.local(node, setOf("character_cards/independent"))
        assertEquals(3L, s.sequence()); assertFailsWith<IllegalArgumentException> { node.resolve(candidate) }
        assertEquals("right", s.a.name("a")); assertEquals("approved separately", s.a.name("independent")); assertEquals(3L, s.sequence())
    }
    @Test fun aDeletedUnpublishedCopyCanBeExplicitlyDroppedWithoutResurrectionOrAnInventedTombstone() = withScene { s ->
        val preview = s.preview(LanSyncGroupResolutionSource.Version(s.right.entry.version.reference()),
            mapOf("local-copy" to LanSyncGroupResolutionSource.Local)); val candidate = s.acknowledge(preview)
        var interrupted = false
        s.a.hook = { if (it == "RESOLUTION_POST_SOURCE_RETAINED" && !interrupted) { interrupted = true; throw IOException("SYNTHETIC_COPY_NOT_YET_AUTHORED") } }
        assertFailsWith<IOException> { s.an.resolve(candidate) }; s.a.hook = {}
        val copy = (preview.resolvedUnits - s.unit).single(); Files.delete(s.a.data.resolve("entities/$copy.json"))
        val node = s.a.open()
        val replacement = node.prepareResolution(mapOf(s.unit to LanSyncGroupResolutionChoice.Keep(LanSyncGroupResolutionSource.Local),
            copy to LanSyncGroupResolutionChoice.Keep(LanSyncGroupResolutionSource.Local)))
        val commit = requireNotNull(node.resolve(s.acknowledge(replacement)))
        assertEquals(setOf(s.unit), commit.entry.version.changes.keys); assertEquals(3L, s.sequence())
        assertFalse(Files.exists(s.a.data.resolve("entities/$copy.json"))); assertTrue(node.inspect().reservedUnits.isEmpty())
    }
}
