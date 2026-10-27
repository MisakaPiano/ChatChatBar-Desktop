# 94 — Phase 8 Fish Audio / Audio Entry Audit and Execution Contract

**PROJECT P8 ENTRY / SOURCE-GROUNDED PLAN / PRODUCTION NOT STARTED / NOT ACCEPTED / NOT MERGED**

Project audit: **2026-10-12 JST**. Feature branch: **feature/phase8-fish-audio** from fork **desktop @ 4c2af2576e2319e8041ffd2a6c5a205450f18246**. Previous P7 is CLOSED / QUALIFIED ACCEPTED / INTEGRATED under 92.

## 1. Authority and policy

- Formal official runtime authority: **SaltyFishOTL/ChatChatBar 1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8**. Use its **.agents/skills/chatbar-fish-audio-voice/SKILL.md** and actual matching source and tests. Never assume the latest upstream is validated.
- Fork CURRENT controls: 00_PROJECT_SOURCE_MAP, 10_UPSTREAM_BASELINE, 21_CURRENT_STATE, 13_FEATURE_PARITY, 14_UPSTREAM_COMPAT, 16_TEST_MATRIX, 17_ROADMAP, 18_DECISIONS and 12_DESKTOP_ARCHITECTURE.
- **D-037 / 93_PUBLIC_PREVIEW_POLICY.md**: after P8/P9/P10/P11, P11 CLOSED / ACCEPTED precedes the independent Public Preview Data-Safety Gate. Public Preview can then precede P12–P17; from the first Preview, persisted user data is production-valued. P8 does not itself authorize Preview or 1.0.
- Newer observed official commits are **unvalidated drift**: fork master mirror 1.4.4 @ 550409689df8c51f459fb50b4e04c8ac2fa4bf35; parked sync/1.4.4 @ 9b6378dbb595dd2f3ff5143a7a8e46653c99e721; upstream master @ c90718420b8c82b298eceee5ae1665773ac120cb. Baseline→1.4.4 touches Fish service/model/tag, response/retry policy and PromptTemplates; 1.4.4→observed touches Fish storage, credential, QQ and backup. Classify separately, **DO NOT absorb, sync or promote baseline automatically**.
- Fish Audio official current developer context corroborates POST /v1/tts, model header, Bearer and reference_id, but this does not authorize silent changes to official CCB 1.4.1 request semantics. No live paid API call is needed for entry audit.

## 2. Official 1.4.1 ownership / invariants

**Keep distinct:** CharacterCard Package voice-binding transfer, persisted CharacterInfo/FishAudioVoiceBinding/GeneratedVoiceMessage/VoiceAnchor/VoiceAnchorState, and actual Fish TTS + auxiliary translation/tag requests. Similar field names do not collapse boundaries.

- Source domain: **domain/voice/FishAudioModels.kt, FishAudioService.kt, FishAudioTagService.kt, FishAudioGenerationCoordinator.kt, VoiceGenerationPolicy.kt, VoiceAnchorPolicy.kt, VoiceMessageVersionPolicy.kt**, Android **data/repository/VoiceMessageRepository.kt** and **data/local/entity/FishAudioEntities.kt**.
- Platform adapters: **data/security/FishAudioCredentialStore.kt** uses Android Keystore, **FishAudioStorage.kt** uses Android Context/MediaMetadataRetriever, **VoicePlaybackController.kt** uses Media3/ExoPlayer, and **domain/voice/qq/** uses Android accessibility/service/notifications. Never copy these Android bindings as Desktop runtime.
- User entry points: **ui/manage/GlobalSettingsScreen.kt**, chat **SessionSettingsContent.kt**, character **CharacterEditScreen.kt/CharacterEditViewModel.kt**, **ChatViewModel.kt/ChatScreen.kt/ChatBubble.kt**. Full voice features are not proven by pre-existing character-binding serialization fields.
- Keep generated voice and anchor records **outside ChatMessage and model Prompt/RAG/Memory history**. Persist historical generation-time character/voice/Fish-model snapshots even if the current card changes.
- Same role binding and unique speaker matching in STRUCTURED / FREEFORM; preserve CharacterInfo IDs through Package copy/import/export. No silent substitute for inaccessible private voice.
- Library GET /model: community/personal, pagination, title/tag/language/sort; TTS POST /v1/tts: Bearer, model, reference_id, MP3 64kbps. Prefer official voice sample preview; only explicit user-provided preview text may generate a new paid sample.
- Translation and tags: voiceLanguage null means source text; configured language requires strict translation. Audiobook mode skips tags but still translates when configured. Stale explicit model fails, never silently falls back. Strict JSON IDs/duplicates/unknown/missing/text-change/tag syntax/truncation; no direct raw synthesis on tag/translation failure.
- AWAITING_TEXT_CONFIRMATION for legal AI text changes, explicit accept/reject. No foreground-work lease during confirmation. Official **PromptTemplates** voice text is unchanged; any suggested Prompt edit requires an independent old-text/diff/user approval.
- Stable per-message-alternative anchors, same-version reconciliation, deleted-segment reattachment rules, multiple voices per anchor; historical sourceText vs synthesisText semantics unchanged.
- Shared five-permit concurrent TTS; one translation/tag batch per message; partial successful results may persist, but no autoplay on partial failure/cancel. Correct session foreground, manual voice tap override, ordered autoplay, Stop and shutdown drain before deletion.
- Owned MP3 .part + validated length/duration + atomic publish. Replace new before deleting previous. Failed/corrupt operations and unknown external paths never cause destructive cleanup.
- Official 1.4.1 **SaveSlot v8** uses voices.jsonl + media/audio streaming assets; schema 1–7 legacy Base64 audio. P8 owns audio/voice contract and owned-media adapter; **P11 retains full SaveSlot/session lifecycle and real Android↔Desktop .cbsave transactional round-trip**. The owner boundary must be accepted before P8 closeout.
- Android QQ voice experiment uses accessibility and press-to-speak. Desktop needs a **Windows QQ feasibility spike**, with evidence. File save/reveal/copy/drag is a safe fallback, **not proof of equivalent auto-send**; if no stable interface, leave QQ transfer BLOCKED with reason and seek product decision.

## 3. Current downstream reuse already verified

- SharedCore: existing **FishAudioVoiceBinding.kt** identical field shape to formal model, **CharacterCard.kt::CharacterInfo.fishAudioVoice**, **AppSettings.fishAudioTtsModelId/voiceTagModelId/audiobookModeEnabled**, **ChatSession.audiobookModeEnabled/voiceLanguage**. Earlier cross-platform card binding evidence exists; **no sharedCore/domain/voice folder yet**.
- Android still owns nearly all Fish 1.4.1 core/runtime. Desktop package directory presently has no Fish/Voice feature files; entity fields alone are **not** a running Desktop voice feature.
- Existing Desktop DPAPI-backed **security/DesktopSecretStore.kt / WindowsSecretStore.kt / WindowsDpapiSecretProtector.kt** must be reused. **DesktopCredentialKey** currently has NovelAI/model/embedding credentials but not Fish, requiring an explicit Fish key with no plaintext JSON/log/export leakage.
- Desktop DI **DesktopAppContainer** and **DesktopTaskRuntime** currently handle Chat and NovelAI. New VOICE task admission/cancellation must not disturb P0–P7 or reuse image task slot semantics accidentally.
- Kotlin/JVM/Compose Desktop + JDK17; Windows audio backend via injectible adapter, not Android Media3, Electron, browser/WebView or localStorage.

## 4. Planned packets (subject to S0 source graph review)

| Slice | Scope | Gate |
| --- | --- | --- |
| **S0** | Read-only official exact-source / existing downstream graph, tests and drift counter-audit | Classify shared-neutral, mixed, Android-only, existing shared; identify real compile/test boundaries and missing dependencies. **No code** |
| **S1** | Share official JVM-neutral voice models, request policy, speaker/anchor/version rules and persistable voice entity contract | One official-core implementation, golden parity fixtures, affected Android and shared compile |
| **S2** | DPAPI Fish credential, global/session settings, library/community/personal selector and preview | Key redaction, correct request/pagination/private errors, explicit preview only, binding works in both Character modes |
| **S3** | VoiceMessage repository, owned MP3 atomic storage, Windows audio player and ordered queue | Restart/data preservation, anchor versions, delete/drain, no external deletion, native playback with fake backend tests |
| **S4** | Strict translation/tags/confirmation and Fish batch coordinator + task runtime | Exact request/Prompt contract, 5 permits, batch phases, cancellation, partial persistence/autoplay safety |
| **S5** | Complete Chat voice UI: message/segment generation, voice bar, long-press edit/regenerate/save, progress/autoplay | Real Windows workflows, safe foreground/lifecycle, no chat Prompt contamination |
| **S6** | SaveSlot audio owned-media compatibility handoff + QQ Windows feasibility | voices.jsonl/media/audio, legacy handling, P11 complete .cbsave owner; QQ evidence-based EQUIVALENT or BLOCKED |
| **S7** | Independent Project code review, bounded automation, exact-SHA package, **one consolidated Windows user acceptance** and integration | Every Fish parity row evidence-classified, retained evidence limits; user approval before FF-only merge |

These packets are planning labels, not permission to change all source immediately. The Project will refine S1 after S0 audit. Keep P8 work on feature/* and preserve upstream/master/sync.

## 5. Test matrix and stop rules

Use 13_FEATURE_PARITY Fish entries and 16_TEST_MATRIX Fish/QQ entries as the authoritative gate. Golden tests: STATIC/FREEFORM CharacterInfo voice binding ID, exact Fish GET /model and POST /v1/tts request serialization, strict Tag/Translation JSON, anchor/version rekey and voice snapshots, generator concurrency/confirmation/cancel, atomic file IO, old audio playback without Fish key, safe cleanup, and SaveSlot owned-audio handoff.

Separate **Project GitHub code review**, **Codex local compile/tests/package**, **real Windows manual playback/UX**, **Android device reference**, **later P11 Android↔Desktop media SaveSlot real round-trip**, and **provider-current observation**. Do not claim one category proves another.

Never change official existing voice Prompt text without separate explicit approval, introduce duplicate core policies, reveal secrets, generate paid preview or paid TTS without approval, delete user audio on failure, revive audio after message deletion, auto-update baseline, run destructive clean or merge desktop/upstream.

## 6. Authorized first task

**P8-S0 READ-ONLY ARCHITECTURE/DEPENDENCY AUDIT only.** Use the exact branch/entry document and baseline Skill. Deliver file-level move/reuse map, Android dependencies that block direct sharing, changed Fish 1.4.4/latest drift classification, suggested first safe implementation diff boundaries, focused official tests and acceptance fixtures; no files changed, no test/build/network API calls or commit. Verify clean branch and report current/remote SHAs and remaining questions. STOP for Project review before S1.

**P8 STATUS: ENTRY CONTROL ESTABLISHED / S0 AUDIT AUTHORIZED / NO PRODUCTION IMPLEMENTATION / NOT ACCEPTED / NOT MERGED.**
