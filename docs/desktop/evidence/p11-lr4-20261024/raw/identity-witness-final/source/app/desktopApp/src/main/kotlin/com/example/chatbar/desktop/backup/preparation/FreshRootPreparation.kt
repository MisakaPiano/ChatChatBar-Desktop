package com.example.chatbar.desktop.backup.preparation

import com.example.chatbar.desktop.backup.*

/** Synthetic/injected evidence, never a Windows account or native identity attestation. */
internal enum class PreparationEvidence { SYNTHETIC_VERIFIED, UNKNOWN, UNAVAILABLE, CONFLICT }
internal class PreparationRoot(val identity: String, val generation: Long, val evidence: PreparationEvidence,
    val proposedAuthorityId: java.util.UUID? = null) {
    override fun toString() = "PreparationRoot(REDACTED)"
}
internal class ProtectedParticipantIdentity(
    val target: ImportCredentialTarget, val protectorContext: String,
    val evidence: PreparationEvidence, val proposedProtectorId: java.util.UUID? = null,
) { override fun toString() = "ProtectedParticipantIdentity(REDACTED)" }
internal enum class FreshRootProblem {
    ROOT_UNPROVEN, ROOT_CLONED, GENERATION_INVALID, DESTINATION_NOT_FRESH_INACTIVE,
    PROTECTOR_UNPROVEN, PROFILE_MISMATCH, CONSUMERS_INCOMPLETE, LEGACY_RETENTION_REQUIRED,
    CONFLICT_UNRESOLVED, COMMUNITY_SINK_MISSING, PARTICIPANTS_INCOMPLETE,
}
internal class FreshRootCandidate(
    val oldRoot: PreparationRoot, val newRoot: PreparationRoot,
    val freshAndInactive: Boolean, val mapping: DesktopCredentialImportPlan,
    participants: List<ProtectedParticipantIdentity>, val expectedProtector: String,
    val declaredConsumersComplete: Boolean, val retainAllLegacyKeys: Boolean,
    expectedTargets: List<ImportCredentialTarget>,
) {
    val participants = participants.toList()
    val expectedTargets = expectedTargets.toList()
    override fun toString() = "FreshRootCandidate(REDACTED)"
}
internal class FreshRootAssessment(problems: Set<FreshRootProblem>) {
    val problems = problems.toSet()
    val syntheticPreparationConsistent get() = problems.isEmpty()
    val restoreAuthorized = false
    val authorizesBusiness = false
    val durableAuthorityEstablished = false
}

/** No IO, serialization, secret values, store, durable location, locks or publication API. */
internal object FreshRootPreparation {
    fun assess(c: FreshRootCandidate): FreshRootAssessment {
        val p = mutableSetOf<FreshRootProblem>()
        if (listOf(c.oldRoot,c.newRoot).any { it.identity.isBlank() || it.proposedAuthorityId == null || it.evidence != PreparationEvidence.SYNTHETIC_VERIFIED }) p += FreshRootProblem.ROOT_UNPROVEN
        if (c.oldRoot.identity == c.newRoot.identity || c.oldRoot.proposedAuthorityId == c.newRoot.proposedAuthorityId) p += FreshRootProblem.ROOT_CLONED
        if (c.oldRoot.generation < 0 || c.newRoot.generation <= c.oldRoot.generation) p += FreshRootProblem.GENERATION_INVALID
        if (!c.freshAndInactive) p += FreshRootProblem.DESTINATION_NOT_FRESH_INACTIVE
        if (!c.declaredConsumersComplete) p += FreshRootProblem.CONSUMERS_INCOMPLETE
        if (!c.retainAllLegacyKeys) p += FreshRootProblem.LEGACY_RETENTION_REQUIRED
        if (!c.mapping.candidateConsistent) p += FreshRootProblem.CONFLICT_UNRESOLVED
        if (c.mapping.decisions.any { it.kind == ImportCredentialKind.COMMUNITY }) p += FreshRootProblem.COMMUNITY_SINK_MISSING
        if (c.expectedProtector.isBlank() || c.participants.any { it.evidence != PreparationEvidence.SYNTHETIC_VERIFIED }) p += FreshRootProblem.PROTECTOR_UNPROVEN
        if (c.participants.any { it.protectorContext != c.expectedProtector }) p += FreshRootProblem.PROFILE_MISMATCH
        val needed = c.mapping.decisions.count { it.candidate != null }
        if (c.participants.any { it.proposedProtectorId == null } || c.participants.map { it.proposedProtectorId }.distinct().size > 1) p += FreshRootProblem.PROTECTOR_UNPROVEN
        if ((c.expectedTargets+c.participants.map { it.target }).any { target ->
            when(target.kind) {
                ImportCredentialKind.MODEL, ImportCredentialKind.LEGACY_EMBEDDING, ImportCredentialKind.SINGLETON_EMBEDDING -> target.account?.valid(target.kind) != true
                else -> target.account != null
            }
        }) p += FreshRootProblem.PARTICIPANTS_INCOMPLETE
        if (c.participants.size != needed || c.participants.map { it.target }.distinct().size != c.participants.size ||
            c.participants.any { it.target.relation == ExistingSecretRelation.UNKNOWN }) p += FreshRootProblem.PARTICIPANTS_INCOMPLETE
        // Reuse G2A exact logical target matching; cardinality alone is not target identity.
        val candidates = c.mapping.decisions.mapNotNull { it.candidate }
        if (c.expectedTargets.size != needed || c.participants.any { row ->
            c.expectedTargets.count { expected -> expected.kind == row.target.kind && expected.relation == row.target.relation &&
                (if(expected.account == null) row.target.account == null else row.target.account?.let(expected.account::sameAccount) == true) } != 1
        }) p += FreshRootProblem.PARTICIPANTS_INCOMPLETE
        val keys = c.participants.mapNotNull { row -> when(row.target.kind) {
            ImportCredentialKind.NOVEL_AI -> com.example.chatbar.desktop.security.DesktopCredentialKey.NovelAiToken
            ImportCredentialKind.FISH -> com.example.chatbar.desktop.security.DesktopCredentialKey.FishAudioApiKey
            ImportCredentialKind.SILICON_FLOW -> com.example.chatbar.desktop.security.DesktopCredentialKey.SiliconFlowApiKey
            else -> row.target.account?.key(row.target.kind)
        } }
        if (keys.distinct().size != keys.size || keys.size != candidates.size || candidates.any { candidate -> keys.none(candidate::matches) }) p += FreshRootProblem.PARTICIPANTS_INCOMPLETE
        return FreshRootAssessment(p)
    }
    /** Phase/evidence assertions stay G2A pure contracts; they are not a persisted journal. */
    fun checkRecovery(c: FreshRootCandidate, intent: CandidateRecoveryIntent, from: CandidateIntentPhase,
        to: CandidateIntentPhase, outcome: CandidateRecoveryOutcome? = null): CandidateIntentCheck {
        if (!assess(c).syntheticPreparationConsistent || intent.mappingPlan !== c.mapping ||
            intent.oldGeneration != c.oldRoot.generation || intent.targetGeneration != c.newRoot.generation ||
            intent.dataRootIdentity != c.newRoot.proposedAuthorityId ||
            intent.protectedContextIdentity != c.participants.firstOrNull()?.proposedProtectorId)
            return CandidateIntentCheck(CandidateIntentFailure.CONTEXT_INCOMPLETE)
        return RecoverableIntentContract.check(intent,from,to,outcome)
    }
}
