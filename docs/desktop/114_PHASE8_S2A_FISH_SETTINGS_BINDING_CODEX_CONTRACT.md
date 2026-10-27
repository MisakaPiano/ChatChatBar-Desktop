# 114 — P8-S2A: Fish Audio Global / Session Settings and Character Voice Binding UI

**PROJECT CODEX IMPLEMENTATION CONTRACT / SINGLE EXISTING P8 FEATURE BRANCH / S2 PARTIAL BY DESIGN / NO LIVE TTS OR PLAYBACK — 2026-10-12 JST**

## 1. Why this is the next safe implementation step

P8-S0D has qualified engineering acceptance (`112`), and S1 official v1.4.1 shared voice core + Android consumer boundary has qualified Project acceptance (`113`). Codex `cfdbc555b7491ba73a6b72d31f42d7437131c20f` already supplied a **DPAPI-backed typed FishAudioApiKey** and a **cancellable, bounded, credential-safe GET /model catalog foundation**, not a user-facing Fish product. P8-S2A connects these existing authorities to usable Desktop settings and Character voice binding in both official edit modes. **S2A remains PARTIAL** because actual preview audio and TTS require separately validated native backend/storage/lifecycle in S2B/S3 and generated voice runtime S4/S5.

Only current existing `feature/phase8-fish-audio` is authorized. No other Git branches/worktrees/PR, no protected-branch movement or full upstream sync. Formal voice semantic baseline **SaltyFishOTL/ChatChatBar 1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8**. Official `.agents/skills/chatbar-fish-audio-voice/SKILL.md` plus actual `FishAudioModels.kt`, Android `CharacterEditScreen.kt` voice picker, `CharacterEditViewModel.kt` search/preview ownership, `GlobalSettingsScreen.kt` settings and `SessionSettingsContent.kt` are runtime reference. Later v1.4.4–1.4.5 Fish response/Tag/retry differences are **unvalidated drift** and must NOT silently alter 1.4.1 request or Prompt text.

## 2. Exact allowed product functionality

### A — Fish credential UI, separate secure ownership

- Add in Desktop global Settings > 语音与朗读 or equivalent obvious Desktop-native section: **Fish Audio API Key save/replace/clear** via existing `DesktopFishAudioCredentials` and `DesktopCredentialKey.FishAudioApiKey` in existing DPAPI SecretStore. Credential editor is independent of unsaved AppSettings draft; existing keys are never placed into ordinary `AppSettings`, Desktop JSON, screen model, copy buffer, history, network debug, developer logs or saved Package.
- Display configured/unconfigured/error state without exposing stored token. Explicit user decision for clear; failed OS-store save/delete retains previously committed key/status appropriately and surfaces diagnostic without underlying token. No auto network request merely opening Settings, no credential echo. Existing NovelAI/provider keys untouched.
- Use platform lock/credential life semantics; no auto-restored Android credentials from sensitive `.cbbackup` and no real user key in tests.

### B — Global Fish and related selected models

- Edit existing authoritative `AppSettings.fishAudioTtsModelId`, `voiceTagModelId`, `audiobookModeEnabled` through the repository's existing **field-specific safe update/merge semantics**. Reuse current settings draft/save-and-leave/conflict/dirty handling where possible; never save a stale full AppSettings snapshot that would overwrite unrelated concurrent edits (other Model, WorldBook, P6, NovelAI settings).
- Fish TTS model choices are **only official `FishAudioTtsModels.supported`** from shared v1.4.1. For `voiceTagModelId`: `null` means official follow current session chat model; an explicit unavailable/stale ID must remain visibly unavailable and **must not silently fall back** to default. Audiobook default off unless already configured; no automatic task starts on toggle in S2A.
- No persistence schema version bump, no custom TTS endpoint, no `PromptTemplates` edits.

### C — Session voice controls

- On session settings (existing `DesktopPrimaryChatPanel`/`DesktopPrimaryChatController` draft), expose and persist `ChatSession.audiobookModeEnabled: Boolean?` with **inherit global / on / off** states, and `ChatSession.voiceLanguage: String?` (null = original spoken language; nonblank = text of target language for future S4 translation). Keep existing dirty/leave/discard/save and selected-session model rules.
- No modification of actual model request, no new translation/tag request until S4, no imaginary voice preview when no player exists. Session switching must not leak settings drafts to another session.

### D — Character Voice Library and explicit binding in both card modes

- Reuse one shared `FishAudioModelQuery`/`FishAudioModelPage`/`FishAudioModel.toBinding()` and existing `DesktopFishAudioLibrary` for the search/pagination/filter/query logic; do **not** hardcode another GET URL, body or model-field serializer.
- Provide a Desktop character picker from BOTH `STRUCTURED` person's regular editor AND `FREEFORM` 人物头像册/name-avatar editor (actual common `CharacterInfo` with stable ID). Show personal/community switch, page navigation (respect `hasMore`), sort, title, tag/language filters, loading/empty/error/offline/error-retry states, voice title/author/visibility/language tags. Disable inappropriate actions without configured Fish credential.
- Picker search/filter changes cancel previous in-flight request, version the result to discard late callbacks, and never bind results from an obsolete character or closed draft. HTTP responses/body/calls are closed/cancelled; no background jobs or non-daemon clients outlive app close. Wire to existing editor/task lifetime rather than creating a free-floating scope. If the existing catalog adapter needs a narrow cancellation-lifecycle improvement, keep it within this scope with fake timing tests.
- **Explicit selection only** updates the character draft using `DesktopCharacterEditorController.updateCharacter(id)` or equivalent existing ownership. Bind `FishAudioModel.toBinding()` snapshot (id/reference/title/author/preview metadata etc.) **without changing CharacterInfo.id**. Remove binding only after explicit user action; Cancel/Close of dialog doesn't mutate saved/draft card. A missing/deleted/private/inaccessible bound voice shows an informative stale/unavailable state; it is not replaced with first search result.
- Preserve the exact binding snapshot through CharacterCard Package JSON/CCB PNG, duplicate/import/export, card save/reopen and BOTH edit modes. No new binding-specific persistence storage, no auto-refresh overwriting saved characters, no generated voice added to ChatMessage/Prompt/RAG/Memory.
- Display bound voice title in the list/name/avatar/editor as the official Android reference does.

### E — Preview ownership and explicit deferral

- S2A may display **official sample metadata** only. **Do not claim playable preview** without a verified Windows MP3 preview backend, or implement experimental player in this settings task. No automatic GET of sample URL, no generated TTS sample, no implicit paid preview. A future S2B/S3 supports listening to existing Fish sample and explicit paid-text preview via user-confirmed operation, proper clip lifetime and playback cancellation; S2A should disclose that it is not yet wired, rather than create a misleading clickable inert “试听” button.
- The incomplete S2 preview behavior remains **PENDING** in `13_FEATURE_PARITY.md`.

## 3. Implementation boundaries and files to audit

Primary Desktop:
- `app/desktopApp/.../desktop/DesktopAppContainer.kt`, `DesktopFishAudioLibrary.kt`, `security/DesktopSecretStore.kt` (reuse current implementation);
- `DesktopManagePanel.kt`, `DesktopModelSettingsController.kt` and existing SettingsRepository field-specific persistence (global Settings);
- `DesktopPrimaryChatPanel.kt`, `DesktopPrimaryChatController.kt`, `DesktopSessionSettingsTabs.kt` (session);
- `DesktopCharacterEditorController.kt`, `DesktopCharacterEditorPanel.kt` and character workspace/management presentation (both modes);
- A narrow new Fish settings/search/picker controller and focused Compose UI files are allowed where existing owners would otherwise be overloaded.

Shared authority already exists: `AppSettings.kt`, `ChatSession.kt`, `CharacterCard.kt`, `FishAudioVoiceBinding.kt`, `domain/voice/{FishAudioModels,FishAudioRequestFactory,VoiceGenerationPolicy}`; **DO NOT edit** these types' fields/serialization or official Prompt. Android reference is read-only other than a demonstrated *necessary* old consumer compile adaptation, which is not expected. No P8-S3 file storage, playback backend, MP3 parsing, S4 generation/tagging/translation, S5 chat voice bars, S6 QQ/P11 transfer implementation, P9/P10 runtime or real Android-backup ingestion.

## 4. Mandatory acceptance and safe tests

1. **Credential**: transient UI input and independent save/clear/replace using fake and Windows synthetic DPAPI profile; empty invalid input, OS-store failure rollback, token never in serialization/screen state/log/git/package, unrelated credentials unaffected. No live Fish account.
2. **Catalog**: MockWebServer tests for GET `/model` parameters and auth, MINE/COMMUNITY, paging/filter/sort, private/stale data, 401/403/429, malformed/oversize body, rapid query changes, cancel during response body, close dialog and close app with request in-flight. No real paid/previews.
3. **Character**: select only on explicit click, cancel leaves draft/card as-is, preserve stable person id and old snapshots, remove explicitly, FREEFORM avatar book and STRUCTURED entry both work, no duplicate or deleted-person late write, Package JSON/CCB PNG/copy/reopen round-trip existing tests remain green.
4. **Settings**: `AppSettings` Fish fields survive restart, preserve unrelated config under concurrent modification, visible stale explicit model; `ChatSession` null/inherit/on/off plus nullable language original/translated future semantics; dirty/leave/save/discard and second-session isolation. No Prompt or network request generated by merely editing preferences.
5. Run JDK17 `:sharedCore:test`, Android `:app:testDebugUnitTest`, `:app:compileDebugKotlin`, Desktop targeted settings/editor UI tests plus **bounded non-Native Desktop full regression**, compile and diff-check; preserve all RED. Use low Gradle workers and existing cache, no blanket clean.
6. Because S2A adds user-visible surfaces, build a single **new exact-source Windows distribution** if automated checks pass, verify file SHA-256 manifest and perform isolated mock/offline settings/character window-close/reopen smoke. Codex GUI automation does **not** replace user's later manual voice picker/real audio listening. Save logs/XML in one `phase8-s2a-*` gate dir; don't accumulate unnecessary duplicate packages or delete historic gate evidence.

Any changed official Prompt literal, serializer schema, unknown clean-up/data loss, unexpected malformed model fallback, regression fail, or inability to cancel query safely => **STOP with evidence**; do not partially wire unsafe feature or claim S2 COMPLETE. No API payment/real key. Existing P7 accepted image/chat behavior, and P8 S0D safe-exit order are invariant.

## 5. Submission and Project handoff

- Do all implementation on existing `feature/phase8-fish-audio` only, no extra branch/worktree/PR. First verify GitHub origin/local HEAD, clean state and FF-only ancestry; no reset/rebase/force, no move of `desktop/master/sync` or official upstream.
- Use one or more comprehensible code checkpoints on this same branch, each tied to passing current tests. Report full SHA, exact changed files, official source references, test-suite counts, code/protocol risks, Windows package file identity and visible UI evidence. Preserve historical RED/shortcomings.
- Do not self-declare S2A accepted or Fish runtime completed, don't promote `13_FEATURE_PARITY.md` Fish rows, don't auto-merge into desktop, don't start S3/P9/P10. **STOP and hand back to Project for independent GitHub diff review.**
- Program Control will not concurrently write to this same feature ref while Codex is processing its unpushed changes; next Project source review begins only after Codex posts pushed SHA.

**CURRENT: S2A EXACT-SOURCE CODING SPEC READY / CODEX NOT YET STARTED / FISH S2 USER FEATURE STILL PARTIAL/PENDING.**
