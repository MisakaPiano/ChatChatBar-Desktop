package com.example.chatbar.desktop.backup

/** G2A pure model derived from 163. Phase labels are assertions, never durable receipts.
 * Evidence booleans/digests must be proved by future adapters. No IO, locks, serialization or apply.
 */
internal enum class CandidateIntentPhase { SOURCE_VERIFIED, PREPARED, INTENT_DURABLE, APPLYING, RECOVERING, COMPLETE }
internal enum class CandidateParticipantDomain { DATA, PROTECTED_CREDENTIAL }
internal enum class CandidateObservedState { OLD, NEW, UNKNOWN, POST_PUBLICATION_AMBIGUOUS }
internal enum class CandidateRecoveryOutcome { ALL_OLD, ALL_NEW }
internal enum class CandidateIntentFailure {
    INVALID_TRANSITION, SOURCE_INCOMPLETE, PLAN_UNRESOLVED, GENERATION_MISSING,
    PARTICIPANT_MISSING, PARTICIPANT_DUPLICATE, OLD_EVIDENCE_MISSING, NEW_EVIDENCE_MISSING,
    OWNERSHIP_UNPROVEN, INTENT_UNPROVEN, OBSERVATION_INCOMPLETE, MIXED_STATE, AMBIGUOUS_PUBLICATION,
}

/** Evidence is for data bytes or OS-protected blobs, NEVER plaintext credential-value digests. */
internal class CandidateParticipant(
    val domain: CandidateParticipantDomain,
    val credential: ImportCredentialCandidate? = null,
    val dataOrdinal: Int? = null,
    val oldAbsent: Boolean = false,
    val oldEvidenceSha256: String? = null,
    val newEvidenceSha256: String? = null,
    val observed: CandidateObservedState = CandidateObservedState.UNKNOWN,
    val observedGeneration: Long? = null,
    val observedEvidenceSha256: String? = null,
) {
    override fun toString(): String = "CandidateParticipant($domain,$credential,$dataOrdinal,$observed)"
}

internal class CandidateRecoveryIntent(
    val sourceSha256: String?, val sourceFullyVerified: Boolean,
    val mappingPlan: DesktopCredentialImportPlan,
    val oldGeneration: Long?, val targetGeneration: Long?,
    participants: List<CandidateParticipant>,
    val dataOwnershipProven: Boolean = false, val sharedSecretOwnershipProven: Boolean = false,
    val intentEvidenceProven: Boolean = false,
) {
    val participants: List<CandidateParticipant> = participants.toList()
    override fun toString(): String = "CandidateRecoveryIntent(REDACTED,participants=${participants.size})"
}

internal data class CandidateIntentCheck(val failure: CandidateIntentFailure? = null) {
    val modelConsistent: Boolean get() = failure == null
    val authorizesBusiness: Boolean get() = false
}

internal object RecoverableIntentContract {
    fun check(
        intent: CandidateRecoveryIntent, from: CandidateIntentPhase, to: CandidateIntentPhase,
        outcome: CandidateRecoveryOutcome? = null,
    ): CandidateIntentCheck {
        fun fail(reason: CandidateIntentFailure) = CandidateIntentCheck(reason)
        val allowed = when (from) {
            CandidateIntentPhase.SOURCE_VERIFIED -> setOf(CandidateIntentPhase.PREPARED)
            CandidateIntentPhase.PREPARED -> setOf(CandidateIntentPhase.INTENT_DURABLE)
            CandidateIntentPhase.INTENT_DURABLE -> setOf(CandidateIntentPhase.APPLYING, CandidateIntentPhase.RECOVERING)
            CandidateIntentPhase.APPLYING -> setOf(CandidateIntentPhase.RECOVERING, CandidateIntentPhase.COMPLETE)
            CandidateIntentPhase.RECOVERING -> setOf(CandidateIntentPhase.COMPLETE)
            CandidateIntentPhase.COMPLETE -> emptySet()
        }
        if (to !in allowed) return fail(CandidateIntentFailure.INVALID_TRANSITION)
        if (!intent.sourceFullyVerified || !digest(intent.sourceSha256) || !intent.mappingPlan.belongsTo(intent.sourceSha256)) return fail(CandidateIntentFailure.SOURCE_INCOMPLETE)
        if (!intent.mappingPlan.candidateConsistent) return fail(CandidateIntentFailure.PLAN_UNRESOLVED)
        if (intent.oldGeneration == null || intent.oldGeneration < 0 || intent.targetGeneration == null ||
            intent.targetGeneration <= intent.oldGeneration) return fail(CandidateIntentFailure.GENERATION_MISSING)
        val rows = intent.participants
        val required = intent.mappingPlan.decisions.mapNotNull { it.candidate }.toSet()
        val protected = rows.filter { it.domain == CandidateParticipantDomain.PROTECTED_CREDENTIAL }
        if (rows.none { it.domain == CandidateParticipantDomain.DATA } ||
            protected.map { it.credential }.toSet() != required ||
            rows.any { (it.domain == CandidateParticipantDomain.DATA && (it.dataOrdinal == null || it.dataOrdinal < 0 || it.credential != null)) ||
                (it.domain == CandidateParticipantDomain.PROTECTED_CREDENTIAL && (it.credential == null || it.dataOrdinal != null)) })
            return fail(CandidateIntentFailure.PARTICIPANT_MISSING)
        if (rows.filter { it.domain == CandidateParticipantDomain.DATA }.map { it.dataOrdinal }.distinct().size != rows.count { it.domain == CandidateParticipantDomain.DATA } ||
            protected.map { it.credential }.distinct().size != protected.size) return fail(CandidateIntentFailure.PARTICIPANT_DUPLICATE)
        if (rows.any { if (it.oldAbsent) it.oldEvidenceSha256 != null else !digest(it.oldEvidenceSha256) }) return fail(CandidateIntentFailure.OLD_EVIDENCE_MISSING)
        if (rows.any { !digest(it.newEvidenceSha256) }) return fail(CandidateIntentFailure.NEW_EVIDENCE_MISSING)
        if (!intent.dataOwnershipProven || (protected.isNotEmpty() && !intent.sharedSecretOwnershipProven)) return fail(CandidateIntentFailure.OWNERSHIP_UNPROVEN)
        if (to != CandidateIntentPhase.PREPARED && !intent.intentEvidenceProven) return fail(CandidateIntentFailure.INTENT_UNPROVEN)
        if (to == CandidateIntentPhase.COMPLETE) {
            if (rows.any { it.observed == CandidateObservedState.POST_PUBLICATION_AMBIGUOUS }) return fail(CandidateIntentFailure.AMBIGUOUS_PUBLICATION)
            if (outcome == null || rows.any { it.observed == CandidateObservedState.UNKNOWN }) return fail(CandidateIntentFailure.OBSERVATION_INCOMPLETE)
            val state = if (outcome == CandidateRecoveryOutcome.ALL_NEW) CandidateObservedState.NEW else CandidateObservedState.OLD
            val generation = if (outcome == CandidateRecoveryOutcome.ALL_NEW) intent.targetGeneration else intent.oldGeneration
            if (rows.any {
                val expected = if (outcome == CandidateRecoveryOutcome.ALL_NEW) it.newEvidenceSha256 else it.oldEvidenceSha256
                it.observed != state || it.observedGeneration != generation || it.observedEvidenceSha256 != expected
            }) return fail(CandidateIntentFailure.MIXED_STATE)
            if (outcome == CandidateRecoveryOutcome.ALL_OLD && from != CandidateIntentPhase.RECOVERING) return fail(CandidateIntentFailure.INVALID_TRANSITION)
        }
        return CandidateIntentCheck()
    }

    private fun digest(value: String?): Boolean = value?.matches(Regex("[a-f0-9]{64}")) == true
}
