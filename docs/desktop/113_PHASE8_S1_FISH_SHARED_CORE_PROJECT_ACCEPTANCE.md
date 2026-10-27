# 113 — P8-S1 Official Fish JVM Shared Core: Final Project Engineering Acceptance

**2026-10-12 JST — P8-S1 QUALIFIED ENGINEERING ACCEPTED / CLOSED WITH PROVENANCE LIMITS / FISH USER FEATURE STILL PENDING / NOT MERGED**

## 1. Scope and authority

- Official source baseline: **SaltyFishOTL/ChatChatBar v1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**; official `.agents/skills/chatbar-fish-audio-voice/SKILL.md` and original source/tests.
- Exact authorized S1 contract: `97_PHASE8_S0_PROJECT_REVIEW.md`, bounded by `94_PHASE8_FISH_AUDIO_ENTRY_AUDIT.md`. P8-S0D safe-process-lifetime engineering gate closed separately by `112_PHASE8_S0D_FINAL_PROJECT_ACCEPTANCE.md`.
- S1 implementation commit: `2163e445d0bcb6351a6f181a40c4b8069d01b8d8`, with parent P8-S0D initial `31d2a48d2097e9e778185c7b8db1a022924745b8`. Current reviewed P8 feature prior to this Project docs record: `7e7054ec37595970192ed2c8a2d84bf93ed86b1e`. Feature `desktop` integration **NOT** authorized; no upstream sync/version promotion.
- Project independently compared GitHub S1 → current P8 feature history and found **zero subsequent changes to S1 Fish shared production sources or corresponding golden tests**. Source-level closure continues to apply to the exact S1 implementation even though later P8-S0D source/test changes exist elsewhere.

## 2. GitHub identity and implementation contract

**Exact-official Git blobs at 1.4.1:**
- `domain/voice/FishAudioModels.kt` same blob `c6f406682fb1db1d46ad1741643c5d2410e8ede3`;
- `domain/voice/VoiceAnchorPolicy.kt` `9538e295fd90bf95077a0f9f7038e115c96a1026`;
- `domain/voice/VoiceGenerationPolicy.kt` `c2dfe135064f2b7af3fa9803516ab22563c1c3e4`;
- `domain/voice/VoiceMessageVersionPolicy.kt` `1f9ba811d568d97ad47defc19b336054268e0c87`.

**Entity and request seam:**
- `data/local/entity/FishAudioEntities.kt` (GeneratedVoiceMessage/VoiceAnchor/VoiceAnchorState) was a **zero-content-change rename from the pre-S1 fork Android source** to shared; it is not raw-blob equal to the monolithic official entity source because `FishAudioVoiceBinding` was already separated as the one shared owner before S1. CharacterInfo binding/package transfer, null/default serializer shape and historical snapshots unchanged.
- `domain/voice/FishAudioRequestFactory.kt` contains the exact 1.4.1 GET `/model` and POST `/v1/tts` request builder/DTO extracted from the former Android `FishAudioService.kt`. Only the documented `internal`→public module API visibility seam was intentionally changed; original Android definitions removed; Android Service retains HTTP execution. No duplicate factory/model/policy maintained.
- Original RequestFactory/VoiceAnchor/VoiceGeneration/VoiceMessageVersion golden tests were moved without modifying assertions. New `FishAudioSharedContractTest` and `FishAudioSharedConsumerTest` cover generated voice serializer/default and legacy-null, binding independence in STRUCTURED/FREEFORM Character Package, TTS serialized keys and Android→shared boundary. The broader Git diff reports **30 byte-identical renames** across S1 and the additional P9/P10 shared extractions, but don't mislabel all 30 as S1 files.
- No PromptTemplates text, AI Tag/Translation service, concurrency coordinator, background lease, audio storage, MP3 playback, native interface, model secrets, real Fish payment, media file cleanup, SaveSlot v8 implementation or Android-only QQ accessibility changes were introduced in S1.

## 3. Test evidence / acceptance provenance

Codex local `108_P8_P10_OVERNIGHT_HANDOFF.md` reports actual JDK17 JUnit XML-derived runs:
- S1 immediately after extraction: shared focused **5 suites / 31 tests PASS**; affected Android focused **8 / 30 PASS**; shared full JVM **116 / 753 PASS**; Android JVM **132 / 780 PASS**, all 0 failures/errors/skips.
- Final overnight source after S1 and further pure policy/adapter changes: shared JVM **126 / 819 PASS**, Android JVM **124 / 722 PASS**, Desktop *affected* **14 / 120 PASS**, not Desktop full. Changed-file diff-check and three-module compilation PASS.
- Subsequent P8-S0D-only source fix and test readiness correction were scoped to Desktop lifecycle and History test, never touching S1 shared Fish files; Codex latest bounded Desktop non-Native **133 suites / 1185 PASS** at `d0c17eff1136e7e15e45fdeaf8a4dbf46c20d1e4`, with previous 1185/1 failure RED preserved; S0D engineering closed by `112`.
- **Evidence grades**: Project independently checked exact GitHub diffs, source blob identity and ref ancestry; Codex measured/reran local XML/Windows tests. Project has NOT rerun local Gradle nor asserted a genuine paid Fish call, playable MP3 or Android hardware acceptance. Source parity for narrow S1 ≠ complete Fish feature parity.

## 4. Disposition

**PROJECT DECISION: P8-S1 QUALIFIED ENGINEERING ACCEPTED / CLOSED** for *JVM-neutral shared Fish domain/Entity/request construction and Android consumer compatibility only*. No automatic promotion of `13_FEATURE_PARITY.md` Fish Credential/Library/Binding/Tags/Generation/Anchors/Playback/QQ **PENDING rows**, which denote complete user/runtime capabilities. No claim for official 1.4.4/1.4.5 Fish retry/Tag/UI drift or latest upstream; formal baseline stays 1.4.1.

Next safe owner: **P8-S2A**, a bounded Desktop global/session voice-setting and Character voice-binding/library UI adapter task. Reuse `cfdbc555b7491ba73a6b72d31f42d7437131c20f` already-reviewed OS-protected Fish key and cancellable GET /model foundation; no sample-audio playback claim without the separate S2B/S3 Windows native-audio backend. Do not copy old Android Context/Media3 or invent paid preview. S2A needs precise live UI/persistence and fake transport tests and further Project acceptance. S3 thereafter owns strict safe MP3 metadata/storage and physical playback. Keep S2 feature partial until preview functionality is genuinely implemented.

**CURRENT: S1 SHARED CORE ENGINEERING CLOSED / FISH S2–S7 PARTIAL/PENDING / P8 PHASE NOT ACCEPTED / NO MERGE.**
