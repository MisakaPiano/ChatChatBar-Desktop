# Typed mapping / inert intent matrix

Every G2A result below is **PURE_MODEL_VALIDATED**, except explicitly labelled authenticated archive or rerun runtime observations. No candidate is a production restore permission.

## Source/target classification

| Official145 source | Candidate target | Actual test / blocked condition |
|---|---|---|
| Preferences.novelAiToken | DesktopCredentialKey.NovelAiToken | CANDIDATE for attested absent/same target; different requires confirmation |
| Preferences.fishAudioKey | FishAudioApiKey | Separate global namespace; no name/account inference |
| AppSettings.siliconFlowApiKey | SiliconFlowApiKey | AppSettings location required; not a Model key |
| ModelConfig.apiKey | ModelApiKey(id) | Exact ID+endpoint+template+remote model evidence; changed endpoint/provider/model CONFLICT |
| legacy EmbeddingConfig.apiKey | LegacyEmbeddingApiKey(id) | Separate legacy namespace; source ID is required |
| singleton embedding_model_config.apiKey | SingletonEmbeddingApiKey | Separate singleton; same object ID does not alias legacy key |
| Preferences.communitySession | None | NONEMPTY gives UNMAPPED/NO_PROTECTED_SINK, blocks whole candidate; no Task/login or generic Key substitution |
| Null / empty known credential | No candidate action | ABSENT/EMPTY metadata retained; NO_SECRET is **not a contract for clearing or preserving target data in a future full restore** |
| Unknown family / wrong location | None | UNMAPPED or CONFLICT even if empty; no silent omission |
| Double source / duplicate target identity | None | CONFLICT, no last-wins |
| Same display name / different IDs | No automatic binding | REQUIRES_CONFIRMATION for source/source or source/target collision |
| Existing different protected credential | No replacement action | REQUIRES_CONFIRMATION; no resolution/write API exists here |
| Missing target observation / unavailable protected sink | None | UNMAPPED; absence of evidence is not empty target |
| Missing account / invalid URI / userinfo/query/fragment / invalid source digest | None | Stable failure category; unsafe identity strings never rendered |
| v2 / code85 or86 | No G2A candidate | SOURCE_FORMAT_UNSUPPORTED; S0 format readability remains separately unchanged |

Names are not authority. Endpoint comparison is deliberately exact, including a trailing slash; normalization/redirect/provider-equivalence is not guessed. Matching is scoped by existing key family, and all conflict decisions make the aggregate candidate inconsistent. Plan snapshots exclude raw keys/tokens/passwords, source JSON, paths and secret-value digests. Identity wrappers and candidate summaries redact imported IDs/names/endpoints. The planner takes trusted metadata; there is no implemented production decoder or target collector.

## Inert candidate intent

| Requirement / negative | Result |
|---|---|
| SOURCE_VERIFIED → PREPARED → INTENT_DURABLE → APPLYING → RECOVERING → COMPLETE | Legal pure-model order; durable label is a claimed fact, not an emitted journal |
| INTENT_DURABLE → RECOVERING; APPLYING → COMPLETE ALL_NEW | Legal model edges subject to full invariants |
| All36 phase pairs | Seven permitted edges; others reject including repeat COMPLETE/backward/skips |
| Missing/wrong source digest, unverified source, digest from different plan | SOURCE_INCOMPLETE |
| Unresolved mapping | PLAN_UNRESOLVED |
| Missing operation/root/protected-context identity | CONTEXT_INCOMPLETE |
| Missing/nonadvancing generation | GENERATION_MISSING |
| Missing/extra protected key, missing data participant, malformed domain | PARTICIPANT_MISSING |
| Duplicate data/protected participant | PARTICIPANT_DUPLICATE |
| Missing old proof; contradictory absence; existing target relabelled absent | OLD_EVIDENCE_MISSING |
| Missing new protected/data evidence | NEW_EVIDENCE_MISSING |
| Missing data or shared-secret ownership proof | OWNERSHIP_UNPROVEN |
| After PREPARED without claimed durable intent evidence | INTENT_UNPROVEN |
| KEY_FIRST / DATA_FIRST / SECOND_KEY_FAIL mixed model states | Cannot declare COMPLETE |
| Postpublication ambiguous / unknown cancellation or rollback result | Cannot declare COMPLETE or authorize business |
| Participant state NEW but wrong evidence/generation | MIXED_STATE |
| Uniform OLD or NEW and consistent evidence | Model-consistent COMPLETE; ALL_OLD only through RECOVERING; `authorizesBusiness=false` always |
| 4³ participant-state combinations × two outcomes | Only uniform matching OLD/NEW is model-consistent; counted inside one actual JUnit test, not128 extra tests |

Pure tests use deliberately illustrative digest markers and UUIDs. They do not create protected blobs, prove ownership/CAS or make evidence durable. Actual byte digests for generated source archives are separate retained artifacts. The new classes are not serializable, have no public persistence format, store reference, IO/lock/journal API or scheduler/provider callback.

## Actual existing-runtime observations rerun

**SYNTHETIC_RUNTIME_OBSERVED:** G1A17, real WindowsSecretStore implementation with Fake Protector and explicit temporary sibling roots, still observes A_NEW/B_OLD and DATA_NEW/KEY_OLD or DATA_OLD/KEY_NEW under injection. This remains **CROSS_DOMAIN_ATOMICITY_NOT_PROVEN / OBSERVED_NONATOMIC_SEQUENCE**. The pure validator refuses corresponding declarations; it does not fix persisted states.

**AUTHENTICATED_SYNTHETIC_WIRE:** G2A code84/v1 plaintext and password six-key sources pass S0 authentication then test-only projection; existing Archive refuses them. Community plaintext is authenticated but unmapped by plan. Wrong password and authenticated invalid UTF-8 never reach successful mapping. All source files remain byte-identical through inspection.

**NEEDS_ADAPTER / NOT_TESTED:** production metadata extraction, protected target observation, conflict resolution, Community lifecycle/sink, actual durable transaction, real DPAPI/profile/Portable, shared-root concurrency, crash/restart secret recovery, capacity, G2B/G2C, Moments preservation and downgrade protection. No actual transaction guarantee or portability acceptance follows.
