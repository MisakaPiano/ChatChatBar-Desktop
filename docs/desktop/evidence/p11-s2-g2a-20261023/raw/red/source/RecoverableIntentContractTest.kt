package com.example.chatbar.desktop.backup

import kotlin.test.*

/** Pure invariant checks, NOT OS durability or actual recovery execution. */
class RecoverableIntentContractTest {
    private val old = "1".repeat(64)
    private val next = "2".repeat(64)
    private val plan = G2AMetadata.plan()
    private fun rows(states: List<CandidateObservedState> = List(3) { CandidateObservedState.NEW }) =
        (listOf<ImportCredentialCandidate?>(null) + plan.decisions.map { it.candidate }).mapIndexed { i, key ->
            val state = states[i]
            CandidateParticipant(if (i == 0) CandidateParticipantDomain.DATA else CandidateParticipantDomain.PROTECTED_CREDENTIAL,
                credential = key, dataOrdinal = if (i == 0) 0 else null, oldEvidenceSha256 = old, newEvidenceSha256 = next,
                observed = state, observedGeneration = if (state == CandidateObservedState.OLD) 3 else 4,
                observedEvidenceSha256 = if (state == CandidateObservedState.OLD) old else next)
        }
    private fun intent(rows: List<CandidateParticipant> = rows(), source: String? = G2AMetadata.SOURCE, verified: Boolean = true,
        mapping: DesktopCredentialImportPlan = plan, oldGeneration: Long? = 3, generation: Long? = 4,
        dataOwned: Boolean = true, secretsOwned: Boolean = true, durable: Boolean = true) =
        CandidateRecoveryIntent(source, verified, mapping, oldGeneration, generation, rows, dataOwned, secretsOwned, durable)
    private fun complete(intent: CandidateRecoveryIntent = intent(), outcome: CandidateRecoveryOutcome = CandidateRecoveryOutcome.ALL_NEW) =
        RecoverableIntentContract.check(intent, CandidateIntentPhase.RECOVERING, CandidateIntentPhase.COMPLETE, outcome)

    @Test fun `ordered candidate phases validate but never authorize business`() {
        val phases = CandidateIntentPhase.entries
        for ((from, to) in phases.zipWithNext()) {
            val result = RecoverableIntentContract.check(intent(), from, to, CandidateRecoveryOutcome.ALL_NEW)
            assertTrue(result.modelConsistent); assertFalse(result.authorizesBusiness)
        }
    }
    @Test fun `invalid phase edges and repeated complete reject deterministically`() {
        val valid = setOf(0 to 1, 1 to 2, 2 to 3, 2 to 4, 3 to 4, 3 to 5, 4 to 5)
        for (from in CandidateIntentPhase.entries) for (to in CandidateIntentPhase.entries) {
            val result = RecoverableIntentContract.check(intent(), from, to, CandidateRecoveryOutcome.ALL_NEW)
            assertEquals((from.ordinal to to.ordinal) in valid, result.modelConsistent)
            assertFalse(result.authorizesBusiness)
        }
    }
    @Test fun `source identity must be complete authenticated and bound to same plan`() {
        for (value in listOf(intent(source = null), intent(source = "bad"), intent(verified = false), intent(source = "3".repeat(64)))) {
            assertEquals(CandidateIntentFailure.SOURCE_INCOMPLETE, complete(value).failure)
        }
    }
    @Test fun `unresolved mapping and missing protected participant never complete`() {
        val unresolved = G2AMetadata.plan(listOf(G2AMetadata.source(ImportCredentialKind.COMMUNITY)))
        assertEquals(CandidateIntentFailure.PLAN_UNRESOLVED, complete(intent(mapping = unresolved)).failure)
        assertEquals(CandidateIntentFailure.PARTICIPANT_MISSING, complete(intent(rows().dropLast(1))).failure)
        assertEquals(CandidateIntentFailure.PARTICIPANT_MISSING, complete(intent(rows().drop(1))).failure)
    }
    @Test fun `duplicate protected and data participants reject`() {
        for (duplicate in rows()) {
            assertEquals(CandidateIntentFailure.PARTICIPANT_DUPLICATE, complete(intent(rows() + duplicate)).failure)
        }
    }
    @Test fun `extra undeclared key or malformed participant rejects`() {
        val extraPlan = G2AMetadata.plan(listOf(G2AMetadata.source(ImportCredentialKind.SILICON_FLOW)))
        val extra = CandidateParticipant(CandidateParticipantDomain.PROTECTED_CREDENTIAL, extraPlan.decisions.single().candidate, oldEvidenceSha256 = old, newEvidenceSha256 = next)
        assertEquals(CandidateIntentFailure.PARTICIPANT_MISSING, complete(intent(rows() + extra)).failure)
        assertEquals(CandidateIntentFailure.PARTICIPANT_MISSING, complete(intent(listOf(CandidateParticipant(CandidateParticipantDomain.DATA, dataOrdinal = -1)) + rows().drop(1))).failure)
    }
    @Test fun `missing old evidence or contradictory absent proof rejects`() {
        for (row in listOf(CandidateParticipant(CandidateParticipantDomain.DATA, dataOrdinal = 0, newEvidenceSha256 = next),
            CandidateParticipant(CandidateParticipantDomain.DATA, dataOrdinal = 0, oldAbsent = true, oldEvidenceSha256 = old, newEvidenceSha256 = next))) {
            assertEquals(CandidateIntentFailure.OLD_EVIDENCE_MISSING, complete(intent(listOf(row) + rows().drop(1))).failure)
        }
    }
    @Test fun `missing new protected evidence rejects`() {
        val invalid = CandidateParticipant(CandidateParticipantDomain.PROTECTED_CREDENTIAL, plan.decisions[0].candidate, oldEvidenceSha256 = old)
        assertEquals(CandidateIntentFailure.NEW_EVIDENCE_MISSING, complete(intent(listOf(rows()[0], invalid, rows()[2]))).failure)
    }
    @Test fun `generation must be present positive advancing and shared by all observations`() {
        for ((oldGen, newGen) in listOf(null to 4L, 3L to null, -1L to 4L, 3L to 3L, 3L to 2L)) {
            assertEquals(CandidateIntentFailure.GENERATION_MISSING, complete(intent(oldGeneration = oldGen, generation = newGen)).failure)
        }
        val bad = CandidateParticipant(CandidateParticipantDomain.DATA, dataOrdinal = 0, oldEvidenceSha256 = old, newEvidenceSha256 = next,
            observed = CandidateObservedState.NEW, observedGeneration = 99, observedEvidenceSha256 = next)
        assertEquals(CandidateIntentFailure.MIXED_STATE, complete(intent(listOf(bad) + rows().drop(1))).failure)
    }
    @Test fun `data and shared secret ownership plus durable evidence are separate claims`() {
        assertEquals(CandidateIntentFailure.OWNERSHIP_UNPROVEN, complete(intent(dataOwned = false)).failure)
        assertEquals(CandidateIntentFailure.OWNERSHIP_UNPROVEN, complete(intent(secretsOwned = false)).failure)
        assertEquals(CandidateIntentFailure.INTENT_UNPROVEN, complete(intent(durable = false)).failure)
        assertTrue(RecoverableIntentContract.check(intent(durable = false), CandidateIntentPhase.SOURCE_VERIFIED, CandidateIntentPhase.PREPARED).modelConsistent)
    }
    @Test fun `key first data first and second key failure cannot be called complete`() {
        val O = CandidateObservedState.OLD; val N = CandidateObservedState.NEW
        for (states in listOf(listOf(O, N, O), listOf(N, O, O), listOf(N, N, O), listOf(O, N, N))) {
            assertEquals(CandidateIntentFailure.MIXED_STATE, complete(intent(rows(states))).failure)
        }
    }
    @Test fun `all combinations of old new unknown and ambiguous forbid mixed success`() {
        for (data in CandidateObservedState.entries) for (a in CandidateObservedState.entries) for (b in CandidateObservedState.entries) {
            val states = listOf(data, a, b)
            for (outcome in CandidateRecoveryOutcome.entries) {
                val expected = if (outcome == CandidateRecoveryOutcome.ALL_NEW) CandidateObservedState.NEW else CandidateObservedState.OLD
                assertEquals(states.all { it == expected }, complete(intent(rows(states)), outcome).modelConsistent)
            }
        }
    }
    @Test fun `post publication ambiguous outcome blocks even when rest looks new`() {
        assertEquals(CandidateIntentFailure.AMBIGUOUS_PUBLICATION, complete(intent(rows(listOf(CandidateObservedState.NEW, CandidateObservedState.POST_PUBLICATION_AMBIGUOUS, CandidateObservedState.NEW)))).failure)
    }
    @Test fun `unknown rollback or cancellation result cannot unlock business`() {
        assertEquals(CandidateIntentFailure.OBSERVATION_INCOMPLETE, complete(intent(rows(listOf(CandidateObservedState.OLD, CandidateObservedState.UNKNOWN, CandidateObservedState.OLD))), CandidateRecoveryOutcome.ALL_OLD).failure)
        val check = RecoverableIntentContract.check(intent(), CandidateIntentPhase.APPLYING, CandidateIntentPhase.RECOVERING)
        assertTrue(check.modelConsistent); assertFalse(check.authorizesBusiness)
        assertEquals(CandidateIntentFailure.OBSERVATION_INCOMPLETE, RecoverableIntentContract.check(intent(), CandidateIntentPhase.RECOVERING, CandidateIntentPhase.COMPLETE).failure)
    }
    @Test fun `all old rollback requires recovering and does not imply imported success`() {
        val allOld = intent(rows(List(3) { CandidateObservedState.OLD }))
        assertTrue(complete(allOld, CandidateRecoveryOutcome.ALL_OLD).modelConsistent)
        assertEquals(CandidateIntentFailure.INVALID_TRANSITION, RecoverableIntentContract.check(allOld, CandidateIntentPhase.APPLYING, CandidateIntentPhase.COMPLETE, CandidateRecoveryOutcome.ALL_OLD).failure)
        assertFalse(complete(allOld, CandidateRecoveryOutcome.ALL_OLD).authorizesBusiness)
    }
    @Test fun `new state label alone is insufficient without exact protected byte evidence`() {
        val row = CandidateParticipant(CandidateParticipantDomain.PROTECTED_CREDENTIAL, plan.decisions[0].candidate,
            oldEvidenceSha256 = old, newEvidenceSha256 = next, observed = CandidateObservedState.NEW, observedGeneration = 4, observedEvidenceSha256 = old)
        assertEquals(CandidateIntentFailure.MIXED_STATE, complete(intent(listOf(rows()[0], row, rows()[2]))).failure)
    }
    @Test fun `old absence evidence permits verified all old rollback only`() {
        val row = CandidateParticipant(CandidateParticipantDomain.DATA, dataOrdinal = 0, oldAbsent = true, newEvidenceSha256 = next,
            observed = CandidateObservedState.OLD, observedGeneration = 3)
        assertTrue(complete(intent(listOf(row) + rows(List(3) { CandidateObservedState.OLD }).drop(1)), CandidateRecoveryOutcome.ALL_OLD).modelConsistent)
    }
    @Test fun `intent snapshots do not expose hashes or mutate with caller collection`() {
        val rows = rows().toMutableList(); val value = intent(rows)
        rows.clear(); assertEquals(3, value.participants.size)
        assertFalse(value.toString().contains(G2AMetadata.SOURCE)); assertFalse(value.participants.toString().contains(next))
    }
}
