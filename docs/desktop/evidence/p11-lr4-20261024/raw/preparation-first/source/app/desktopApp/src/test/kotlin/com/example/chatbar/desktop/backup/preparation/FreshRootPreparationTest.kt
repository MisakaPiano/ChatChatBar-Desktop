package com.example.chatbar.desktop.backup.preparation
import com.example.chatbar.desktop.backup.*
import org.junit.Test
import kotlin.test.*
import java.util.UUID

class FreshRootPreparationTest {
    private val target=ImportCredentialTarget(ImportCredentialKind.NOVEL_AI,ExistingSecretRelation.ABSENT)
    private fun candidate(clone:Boolean=false,profile:String="synthetic-profile",consumer:Boolean=true,retain:Boolean=true,
        evidence:PreparationEvidence=PreparationEvidence.SYNTHETIC_VERIFIED, fresh:Boolean=true,
        participants:List<ProtectedParticipantIdentity> = listOf(ProtectedParticipantIdentity(target,profile,evidence)),
        plan:DesktopCredentialImportPlan = mapping()):FreshRootCandidate = FreshRootCandidate(
            PreparationRoot("old",1,PreparationEvidence.SYNTHETIC_VERIFIED),PreparationRoot(if(clone) "old" else "new",2,evidence),
            fresh,plan,participants,"synthetic-profile",consumer,retain,listOf(target))
    private fun mapping(kind:ImportCredentialKind=ImportCredentialKind.NOVEL_AI,relation:ExistingSecretRelation=ExistingSecretRelation.ABSENT)=DesktopCredentialImportPlan.classify(1,1,84,"a".repeat(64),
        listOf(ImportCredentialSource(kind,if(kind==ImportCredentialKind.COMMUNITY) ImportCredentialLocation.PREFERENCES_COMMUNITY else ImportCredentialLocation.PREFERENCES_NOVEL_AI,ImportSecretPresence.NONEMPTY)),
        listOf(ImportCredentialTarget(kind,relation)),true)
    @Test fun syntheticConsistentNeverAuthorizesBusiness(){val a=FreshRootPreparation.assess(candidate());assertTrue(a.syntheticPreparationConsistent);assertFalse(a.restoreAuthorized);assertFalse(a.authorizesBusiness);assertFalse(a.durableAuthorityEstablished)}
    @Test fun cloneAndInactiveChecks(){assertTrue(FreshRootProblem.ROOT_CLONED in FreshRootPreparation.assess(candidate(clone=true)).problems);assertTrue(FreshRootProblem.DESTINATION_NOT_FRESH_INACTIVE in FreshRootPreparation.assess(candidate(fresh=false)).problems)}
    @Test fun profileAndNativeEvidenceNotGuessed(){assertFalse(FreshRootPreparation.assess(candidate(profile="other")).syntheticPreparationConsistent);for(e in PreparationEvidence.entries.filter{it!=PreparationEvidence.SYNTHETIC_VERIFIED}) assertFalse(FreshRootPreparation.assess(candidate(evidence=e)).syntheticPreparationConsistent)}
    @Test fun offlineThirdConsumerAndLegacyRetention(){assertFalse(FreshRootPreparation.assess(candidate(consumer=false)).syntheticPreparationConsistent);assertFalse(FreshRootPreparation.assess(candidate(retain=false)).syntheticPreparationConsistent)}
    @Test fun missingDuplicateAndConflictingParticipant(){assertFalse(FreshRootPreparation.assess(candidate(participants=emptyList())).syntheticPreparationConsistent);val p=ProtectedParticipantIdentity(target,"synthetic-profile",PreparationEvidence.SYNTHETIC_VERIFIED);assertFalse(FreshRootPreparation.assess(candidate(participants=listOf(p,p))).syntheticPreparationConsistent);assertFalse(FreshRootPreparation.assess(candidate(participants=listOf(ProtectedParticipantIdentity(ImportCredentialTarget(ImportCredentialKind.FISH,ExistingSecretRelation.ABSENT),"synthetic-profile",PreparationEvidence.SYNTHETIC_VERIFIED)))).syntheticPreparationConsistent)}
    @Test fun communityAndConflictCannotBecomeReady(){assertFalse(FreshRootPreparation.assess(candidate(plan=mapping(ImportCredentialKind.COMMUNITY))).syntheticPreparationConsistent);assertFalse(FreshRootPreparation.assess(candidate(plan=mapping(relation=ExistingSecretRelation.DIFFERENT))).syntheticPreparationConsistent);assertFalse(FreshRootPreparation.assess(candidate(plan=mapping(relation=ExistingSecretRelation.UNKNOWN))).syntheticPreparationConsistent)}
    @Test fun missingAndMixedRecoveryEvidenceFailClosed(){
        val c=candidate();val credential=c.mapping.decisions.single().candidate!!
        fun intent(rows:List<CandidateParticipant>)=CandidateRecoveryIntent("a".repeat(64),true,c.mapping,1,2,rows,true,true,true,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID())
        val old=CandidateParticipant(CandidateParticipantDomain.DATA,dataOrdinal=0,oldEvidenceSha256="b".repeat(64),newEvidenceSha256="c".repeat(64),observed=CandidateObservedState.OLD,observedGeneration=1,observedEvidenceSha256="b".repeat(64))
        val new=CandidateParticipant(CandidateParticipantDomain.PROTECTED_CREDENTIAL,credential=credential,oldAbsent=true,newEvidenceSha256="d".repeat(64),observed=CandidateObservedState.NEW,observedGeneration=2,observedEvidenceSha256="d".repeat(64))
        assertFalse(FreshRootPreparation.checkRecovery(c,intent(listOf(old)),CandidateIntentPhase.SOURCE_VERIFIED,CandidateIntentPhase.PREPARED).modelConsistent)
        assertFalse(FreshRootPreparation.checkRecovery(c,intent(listOf(old,new)),CandidateIntentPhase.APPLYING,CandidateIntentPhase.COMPLETE,CandidateRecoveryOutcome.ALL_NEW).modelConsistent)
    }
    @Test fun identitiesAreRedacted(){assertFalse(candidate().toString().contains("synthetic-profile"));assertFalse(PreparationRoot("private-root",1,PreparationEvidence.UNKNOWN).toString().contains("private-root"))}
}
