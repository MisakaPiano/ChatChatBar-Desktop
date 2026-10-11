# 97 — Phase 8 S0 Project Source Review and Narrow S1 Contract

**PROJECT S0 CODE/SOURCE REVIEW PASS / S1 PURE-CORE EXTRACTION PERMITTED AFTER DOC-BRANCH RECONCILIATION / NO P8 PRODUCTION CHANGE YET**

Review date: 2026-10-12 JST. Reviewed Codex S0 read-only handoff on `feature/phase8-fish-audio @ 867cf1c27b9573ea059047ea503349937bc403a3`. Project independently fetched exact upstream baseline `1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8` Fish source/test files, Desktop shared dependencies and data-safety seam. Codex reported no Gradle/test/GUI/paid Fish API execution; its identified golden test counts **are not executed PASS evidence**.

## S0 dispositions

| Owner | Project finding |
| --- | --- |
| Official FishAudioModels, VoiceAnchorPolicy, VoiceGenerationPolicy, VoiceMessageVersionPolicy | Android-import-free JVM-neutral core; shared entity/roleplay dependencies available. **MOVE to shared only, no dual implementation.** |
| FishAudioEntities | Move only GeneratedVoiceMessage/VoiceAnchor/VoiceAnchorState to shared. Existing shared FishAudioVoiceBinding retained as single authority; serialized field names/defaults unchanged. |
| FishAudioRequestFactory/FishAudioTtsRequest | Pure request shape currently declared `internal` inside mixed-service file. **Can extract** with exact 1.4.1 endpoints/headers/body; Android module unit-test visibility may need minimal explicit API exposure (or equivalent test relocation). Do not retain duplicate factory/DTO in Android. |
| FishAudioTagPolicy/FishAudioTagService | PromptTemplates and StreamingChatService coupling: **not approved in S1**; strict Tag/Translation and Prompt physical ownership stay for S4 contract review. |
| FishAudioGenerationCoordinator | Indirect Android credential/storage/playback/background manager dependencies despite no direct Android import: **not approved for S1 movement**. S4 requires platform interfaces and task lifecycle policy. |
| VoiceMessageRepository, FishAudioStorage, VoicePlaybackController, credential store, QQ transfer | S3/S2/S6 owners respectively; Android adapters not copied wholesale into Desktop. |
| Additional pure queue/preview/file naming policies | Defer to owning S2/S3/S5 except optional VoicePlaybackQueuePolicy if needed by migrated core/tests. Avoid unnecessary relocation merely because it is easy. |

## Data safety: accepted blocker for later storage work

Upstream Android `JsonFileStorage.loadAll` skips per-entity corruption; `ChatBarApp` startup then calls `FishAudioStorage.cleanupOrphanFiles` using the loaded voice list. Desktop shared `JsonFileStorage` already includes strict enumerating `readAllEntitiesStrict`/per-entity read states. **Do not allow lost/corrupt/unreadable voice Entity reads to trigger cleanup of unreferenced MP3s.** Require fail-closed cleanup admission, unknown bytes retained and restart/failure fixture coverage in S3. Do not change P7 data or automatically clear audio.

## Narrow S1 implementation acceptance (before any later slice)

1. Move the exact 1.4.1 `GeneratedVoiceMessage`, `VoiceAnchor`, `VoiceAnchorState` serialization contracts to shared; keep `FishAudioVoiceBinding` singular and current `CharacterInfo.fishAudioVoice`/Package transfer untouched.
2. Relocate official `FishAudioModels`, `VoiceAnchorPolicy`, `VoiceGenerationPolicy`, `VoiceMessageVersionPolicy`; extract official `FishAudioRequestFactory` + `FishAudioTtsRequest` from Android's mixed service into shared, with no new TTS live execution and unchanged request semantics. Remove only the source duplicates; do not introduce a second implementation. Limited `internal`→module API exposure is acceptable only if named/tested.
3. Migrate exact relevant upstream baseline tests: RequestFactory 3, Anchor 9, Generation 10, Version 4 (counts Codex source-reported, **not verified test execution**); add direct JSON golden/legacy-null tests for entities, status/ID/text snapshots and bound FREEFORM/STRUCTURED Character Package regression as relevant.
4. Execute focused shared tests, affected Android tests, Android `:app:compileDebugKotlin`, Desktop compile, shared/Android module regressions appropriate to moved files and `git diff --check`. Report counts and full red errors; if stale Android type duplicates or tests fail, fix only in allowed S1 scope and stop on semantic incompatibility.
5. Do **not** move TagService/TagPolicy (Prompt-dependent), coordinator, storage, playback, credentials, QQ or Chat UI; no SecretStore key, new LLM Prompt literal, upstream selective retry port, API key, paid provider call, user audio deletion or data migration.

## New Program Control after S0 started

The user approved **D-038** full-app `.cbbackup` official 1.4.5 portability as a **P11 and Public Preview Data-Safety Gate blocker**; Program Control documents are now on `desktop` `18`, `93`, `95`, `17`, `20`, `19`, `13`. P8 S1 implements only voice core and passes persisted voice/media ownership contract to P11. Formal upstream compatibility baseline remains 1.4.1; Fish Tag thinking/retry/storage changes in later 1.4.4/1.4.5 stay observed/unadopted pending separate scope review.

`desktop` received docs-only D-038 + safe local cleanup policy (`96_LOCAL_ARTIFACT_RETENTION_AND_CLEANUP.md`) **after** the P8 feature was created. Before S1, Codex must fetch and reconcile the **desktop → P8 feature docs-only history** without force/rebase/reset, preserving `94` and this `97`, and submit exact conflict resolution/diff for Project review. Do not merge the P8 feature into desktop. Any doc conflict, unknown upstream non-doc delta or changed protected ref requires a controlled STOP rather than overwrite.

## Local disk cleanup boundary

User wants to remove unnecessary Windows project files. `96` authorizes an initial **read-only local artifact inventory**, not deletion. Historical P7 accepted packages/manifests/logs, actual user/Portable data roots, backups, SecretStore envelopes and unknown/corrupt bytes are **KEEP**. Exact candidate allowlist and user authorization must precede cleanup. This can run before heavyweight S1 builds to discover disk pressure.

**CURRENT: S0 PASS / S1 NARROW SCOPE APPROVED IN PRINCIPLE / DOC-BRANCH RECONCILIATION + LOCAL CLEANUP INVENTORY NEXT / P8 PRODUCTION NOT STARTED.**
