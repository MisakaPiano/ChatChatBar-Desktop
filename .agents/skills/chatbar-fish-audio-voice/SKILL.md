---
name: chatbar-fish-audio-voice
description: Maintain ChatBar Fish Audio voice generation across API credentials, voice-library search and preview, character voice bindings, AI tag generation and text confirmation, stable message anchors, concurrent TTS, local audio persistence, playback, SaveSlot/card transfer, cleanup, and chat UI. Use when changing or diagnosing Fish Audio settings, model listing, TTS requests, voice generation progress or cancellation, speaker matching, generated voice messages, autoplay order, ExoPlayer crashes, or voice migration and export behavior.
---

# ChatBar Fish Audio Voice

Keep voice generation independent from chat text, prompt context, and long-term memory. Read the smallest matching path first.

Use `chatbar-model-request-runtime` for shared auxiliary-model resolution or SSE transport, `chatbar-background-work-runtime` for foreground leases, `chatbar-character-card-ai` for auto-fill/rewrite merge behavior, `chatbar-shadcn-compose` for UI changes, and `chatbar-emulator-test` for device playback verification.

## First Read

- App wiring: `app/app/src/main/java/com/example/chatbar/ChatBarApp.kt`.
- Settings and credentials: `data/local/entity/AppSettings.kt`, `data/local/entity/ChatSession.kt`, `data/security/FishAudioCredentialStore.kt`, `ui/manage/GlobalSettingsScreen.kt`, `ManageViewModel.kt`, `ui/chat/SessionSettingsContent.kt`, and `ChatSettingsDialog.kt`. Both settings surfaces group voice controls under 语音与朗读; the global credential editor saves independently.
- Voice bindings and transfer contracts: `app/sharedCore/src/main/kotlin/com/example/chatbar/data/local/entity/CharacterCard.kt`, `FishAudioVoiceBinding.kt`, and `app/sharedCore/src/main/kotlin/com/example/chatbar/domain/card/CardTransferModels.kt`; Android transfer orchestration remains in `CharacterCardTransferService.kt`.
- Shared exact-1.4.1 core: `app/sharedCore/src/main/kotlin/com/example/chatbar/domain/voice/` owns `FishAudioModels`, `FishAudioRequestFactory` / `FishAudioTtsRequest`, `VoiceAnchorPolicy`, `VoiceGenerationPolicy`, and `VoiceMessageVersionPolicy`; voice entities are in shared `data/local/entity/FishAudioEntities.kt`. Android `domain/voice/FishAudioService.kt` executes requests and retains platform storage; do not recreate the extracted definitions.
- Shared `domain/voice/FishAudioTagService.kt` owns the official strict protocol over `FishVoiceTextGateway`. Shared `FishAudioPromptAuthority` holds the byte-preserved official Fish prompts; Android `PromptTemplates` delegates and `AndroidFishVoiceTextGateway` retains task identities/streaming. Android orchestration remains `FishAudioGenerationCoordinator.kt`; Desktop uses its platform adapter.
- Shared `data/repository/VoiceMessageRepository.kt` retains official anchor/version/history behavior. Android uses its unchanged default read policy; Desktop opts into strict complete inventories and preserves explicit nonblank historical version IDs during restore. Missing/null legacy identities keep their migration policy; historical IDs never become current voices merely because text matches. Android `domain/voice/FishAudioStorage.kt` remains platform-specific.
- Playback: `domain/voice/VoicePlaybackController.kt`.
- QQ voice experiment: `domain/voice/qq/`, `AndroidManifest.xml`, and `res/xml/qq_voice_accessibility_service.xml`.
- Chat and character UI: `ui/chat/ChatScreen.kt`, `ChatViewModel.kt`, `ui/components/ChatBubble.kt`, `ui/character/CharacterEditScreen.kt`, `CharacterEditViewModel.kt`.
- Save/restore and deletion: shared `app/sharedCore/src/main/kotlin/com/example/chatbar/data/local/entity/SaveSlot.kt`; Android `domain/deletion/DeletionCoordinator.kt` retains restore/deletion ownership.
- Shared golden tests: `app/sharedCore/src/test/kotlin/com/example/chatbar/domain/voice/` contains RequestFactory/Anchor/Generation/Version tests and `FishAudioSharedContractTest`. Android tests retain `domain/voice/FishAudioTagPolicyTest.kt`, `FishAudioSharedConsumerTest.kt`, `domain/card/CharacterFishAudioSerializationTest.kt`, and SaveSlot serialization tests.

All abbreviated source paths are under `app/app/src/main/java/com/example/chatbar/`; tests are under `app/app/src/test/java/com/example/chatbar/`.

## Desktop Fish settings and preview

- `DesktopFishAudioPanel`, credential/settings controllers and `DesktopCharacterEditorController.bindFishVoice` own S2A settings and explicit selection in both card modes. Previewing never calls binding or writes chat/voice Entities.
- The app container injects its data coordinator into `DesktopFishCredentialController`; credential save/clear participates in normal admission before protected mutation, including close/restart refusal. Independent password editing and presence-only state remain unchanged.
- `DesktopFishAudioLibrary` remains catalog-only: shared GET factory, bounded/sanitized responses, redirect rejection and cancellation through body drain. Its app owner drains pending catalog calls at shutdown. Historical S2A W22 remains untested in its original records; F1 evidence `p8-s2b-f1-20261018` separately proves one isolated native hanging-query picker-close scenario and actual exit/drain.
- `DesktopFishPreviewRuntime` owns app-scope picker leases, one serialized output path and the first generated clip per reference ID for each picker. A changed text does not regenerate a cached voice; UI must disclose reuse. Only explicit nonblank 1–120-character text plus a generate click can request `s2.1-pro-free`; paid/stale configured models are rejected without fallback.
- `DesktopFishPreviewTransport` reuses shared `tts()`, sends only to the fixed Fish endpoint, disables redirects/connection retries and uses one-shot bodies plus a per-call network-exchange guard. No sample CDN URL is accepted. Credentials stay in the typed DPAPI store.
- `DesktopFishPreviewStore` creates separate OS-temp picker directories, bounds clip/session sizes to 16/64 MiB (four outstanding leases maximum), streams `.part`, checks MP3 frame structure/120-second duration, publishes atomically and rechecks cached hashes/file identity. It only deletes recorded own files after real drain; replacement/unknown entries remain evidence.
- Its default temp creation rejects reparse parents before expanding the OS 8.3 spelling with `toRealPath(NOFOLLOW_LINKS)` and `Files.isSameFile`. Only the internally created default directory is canonicalized; explicit external aliases remain rejected. `DesktopFishPreviewDefaultTempTest` uses fresh child JVMs because the JDK caches the default temp path, and covers byte custody, clipboard defaults and junction refusal.
- `DesktopWindowsAudioOutput` is a replaceable local-file JNA/winmm MCI backend. Windows supplies the decoder; no codec binary is shipped. It serializes native commands and confirms stop/close before file cleanup. MCI is legacy; codec/device availability and physical I/O deadlines are not guaranteed. AppContainer closes preview before credentials/storage and root release.
- Focused tests: `DesktopFishPreviewTest`, `DesktopFishPreviewWindowsTest`, `DesktopFishPresentationTest`, library/settings tests. Native status/silent fixtures do not substitute for user hearing a packaged clip. Formal voice repositories, chat autoplay, SaveSlot audio and ordinary sample downloads remain outside this preview slice.

- Physical-output cases in `DesktopFishPreviewWindowsTest` and `DesktopVoicePauseWindowsTest` use `DesktopNativeAudioAssumption`: Windows `waveOutGetNumDevs()>0` is required. Actual device absence is reported as JUnit skipped/NOT_TESTED; a present device still runs the original MCI assertions and errors remain failures.

- `DesktopVoiceAudioStore` has an optional creation callback used only for isolated full-backup mapped audio. It registers newly created scratch directories/partial/MP3/receipt identities; ordinary audio uses the empty callback and retains the existing ownership/close rules. Full-backup mapping first rejects negative source duration or inconsistent/nonpositive declared audio length, then passes the original expected length to persist.

## Desktop chat presentation

- `DesktopChatContextMenu` renders role-specific rows built by `PrimaryMessageBubble`: User secondary click has only Copy/Edit/Delete/Screenshot; Assistant retains Reply/Segment/Whole rows shared with footer ellipsis. Ellipsis has no implicit segment; screenshot actions are supplied by a separate composition-local owner. `DesktopChatMessageToolbar` limits User footers to right-aligned copy/edit; Assistant footers keep voice last in the left group and image actions pinned right; a history window with newer messages must not expose regeneration eligibility. `DesktopVoiceGenerationDialog` retains explicit auxiliary authorization and message/segment identity. Message header/ellipsis use the same popup; image-specific controls remain separately scoped. Menu focus must attach before requesting it (do not defer the focus target behind unused subcomposition).

- `DesktopChatScreenshotHost` supplies the message-menu screenshot owner; `DesktopChatScreenshotSelection` retains baseline pure selection/file-name policies and shared roleplay block IDs. Independent `DesktopChatScreenshotRenderer` reuses Markdown/segment presentation, snapshots message versions, excludes all recording blocks, bounds raster/resources and exports new PNG destinations. `DesktopCharacterResourceStore.readScreenshotImageBytes` is the bounded image-only port; screenshot selection never requests audio generation. `verifyScreenshotMessages` checks session, message version and ordered visual identities only; an empty screenshot audio map must never be compared with persisted recordings. Host feedback identifies safe opening/preview/export stages without exception payloads.

## Desktop formal audio foundation

- Structured session participation is resolved by shared `SessionCharacterParticipationPolicy` at generation admission and narrator presentation. Disabled people cannot create/replace clips; original card bindings and historical playback/files remain available. `DesktopChatVoiceRuntime.withCharacterParticipationChange` shares the generation mutation lock and refuses queued/running/text-confirmation batches before settings change.

- `DesktopVoiceUx.desktopNarratorChoices` requires a nonblank reference ID in the effective card; audiobook preference alone never creates an empty narrator dock. Its session-local `DesktopVoiceUiSelection` retains narrator selection while `DesktopComposerVoiceControl` expands it horizontally from the composer voice icon. No effective bound voice means no control; generation/playback controls remain independent. Automatic residency metadata uses `withAutomaticParticipationUpdate`; already admitted batches keep their captured `GenerationContext`/targets and audio, while later admissions resolve the updated participation. Manual participation changes retain the busy refusal above.

- `DesktopVoiceAudioStore` persists immutable MP3 artifacts under the selected root `audio/generated`, with independent ownership/hash receipts, bounded streams and atomic publication. It has no startup orphan sweep; corrupt indexes, missing receipts and unknown files remain preserved. Formal clips use a separate 16 MiB / 30-minute structural/native profile; picker preview stays at its accepted 16 MiB / 120 seconds. Both profiles use the same Windows backend with explicit per-port duration admission.
- `DesktopVoicePlayback` provides key-independent ordered playback, real MCI pause/position/resume and stop/join. Manual `playFrom` atomically toggles the same active/paused recording under the existing playback mutex; clicks during its pending native open coalesce. A different target retains stop/join before replacement, while automatic queue admission does not toggle. A paused native alias retains the queue and data-operation lease until resume/stop; first UI Stop pauses, second closes. `DesktopChatVoiceRuntime.play` uses current-version placements across later assistant replies in the same session, skipping User turns without TTS (Desktop enhancement). `DesktopAudioFocus` provides separate formal/preview ports over one MCI output; a superseded port cannot stop the current owner. AppContainer owns formal voice drain before preview/native output and root release. Queue admission checks the originating UI foreground token inside the playback mutex; leaving and returning cannot revive a delayed click. Replacement releases a queued/active old voice through native stop before deleting its artifact.
- `DesktopAudioMaintenance` reserves that same focus owner before migration/snapshot exclusive admission. Migration explicitly stops/joins formal playback and confirms native close before destination preparation and scheduler stop; automatic backup takes only an idle reservation, otherwise reports a retryable scheduler failure (never successful/not-due). Reservations do not wait for each other or hold the focus mutex across scheduler/coordinator waits. Audio epochs invalidate queued formal opens; restart seals admission. Failed close keeps the player's original normal registration until an explicit stop retry confirms release; failed drain quarantines focus. `DesktopAudioMaintenanceTest` combines the real coordinator, scheduler and migration adapter with controlled native close signals. Migration `AUDIO_MAINTENANCE` is precommit/retryable; actual source-runtime resume results remain attached to other precommit failures.
- `DesktopFishGenerationCoordinator` adapts official targets, text confirmation, global five-permit batches, history/replacement and foreground autoplay. `DesktopFishVoiceTransport` reuses shared TTS serialization with one-shot/one-exchange transport and callback/body drain. Only explicit `s2.1-pro-free` is authorized; stale/paid models fail, and the dated free profile fails closed after 2026-11-30 pending re-verification. Artifact cleanup re-reads a complete strict reference inventory; uncertain commits preserve audio. Exact durable voice readback may confirm a post-write exception before refreshing the repository. `DesktopFishTtsAdmission` shares five permits across formal and picker TTS, through actual body drain.

- `DesktopChatVoiceRuntime` and `DesktopChatVoicePanel` wire explicit assistant segment/whole generation, bound narrator choice, auxiliary-cost confirmation, new take vs replacement, version-scoped voice bars, ordered playback, stop, export and explicit delete. `DesktopVoiceUx` retains timeline generation controls and the fixed paused/playing session; narrator configuration lives in the inline/full composer toolbar, outside the timeline. `DesktopRecordingRow` uses measured content width with a 200dp usable minimum constrained by the viewport. Recording rows show standard play/pause icons and real position/duration progress from `DesktopVoicePlayback` polling the serialized output port; unavailable position is explicit and never animated. The fixed floating player uses Pause while playing and Stop while paused, with shared ellipsis/right-click actions in a popup and dismissible notifications. On-demand narrator selection follows shared `requiresNarratorSelection` even with a single bound voice in a multi-character card; idle audiobook mode has no timeline dock; its composer icon expands the narrator selector without changing the voice target rules. Batch details retain independent controls for every session batch. Replacement feedback carries session/message/voice identity, is cleared before a replacement attempt, and is emitted after publication and old-file release. Primary chat mutations invalidate pending play intents and stop/join before draining voice jobs for editing/deleting/switching versions; foreground leases drop autoplay on screen/session leave. No assistant completion automatically requests TTS. Formal playback holds a data-operation lease through native stop/close. Existing message/image mutations retain their own normal/exclusive scopes; do not wrap image cleanup in an outer normal lease. The lazy owned auxiliary text transport drains its real sockets/executor at app shutdown.

- `DesktopVoiceArchiveHandoff` provides P11 audio-only JSONL/media ports and strict rollback proof. See `docs/desktop/142_PHASE8_VOICE_P11_HANDOFF.md` for token/version/mapping and ownership requirements; it is not a full archive transaction. DesktopSaveSlotService owns that transaction, reserves the existing chat-voice mutation owner and uses restoreForSessionStreaming for bounded anchor reattachment; failed native drain cannot publish. Windows QQ native sending is unverified/BLOCKED; explicit MP3 export never claims delivery.
- Handoff defaults to known-version validation. Production SaveSlot RestorePlan explicitly enables historical custody after immutable package validation: nonblank unknown IDs stay exact, while each owned MP3 is still decoded/receipted and the original package survives. The strict repository keeps these recordings outside current-version playback. No text-match or active-version fallback for explicit history; null legacy behavior remains. Inspector historical counts are metadata, never restoration permission.
- SaveSlot restores append `DesktopSlotHistoricalVoiceCustody` original-package/binding evidence with their session journal so later full backups can verify newly assigned voice IDs. Original custody remains after library-slot/live-audio deletion; whole-root validation still refuses changed/unproven historical bindings or media. This is separate from current-version playback and top-level full-backup custody.
- Cross-session SaveSlot restores use the shared SessionCopyPolicy message namespace, including legacy envelopes whose session field was already rebound. This prevents global voice-anchor file collisions with the source conversation; versions/source turns and historical exclusion stay unchanged. Same-session restore IDs remain unchanged.

## Ownership and Isolation

- Keep generated voice records outside `ChatMessage`. Chat prompt assembly, RAG, source fingerprints, and long-term memory must continue reading text and existing image fields only.
- Home session copying snapshots voice anchor states by source message IDs, then restores them under fresh message IDs/session ID via `VoiceMessageRepository.snapshotAnchorsForMessages/restoreCopiedAnchors`. Normalize message alternative versions before changing message IDs; preserve version and segment anchor IDs inside their new message namespace. Copy audio through SaveSlot package materialization.
- Preserve generated voice history after character deletion, card replacement, speaker-tag edits, or later voice changes. Historical records use generation-time character, voice, and Fish-model snapshots.
- Keep original bubble text in `GeneratedVoiceMessage.sourceText` for anchors and store the tag-free translated/original synthesis snapshot in `synthesisText`; old records fall back to `sourceText`.
- Delete voices only with their owning message/session, explicit voice deletion, or failed replacement cleanup.
- Keep Fish API keys in Android Keystore. Never serialize, export, log, or save blank credentials.
- Gate new voice selection and generation on a configured Fish key. Keep existing local voice playback and deletion available without the key.

## API and Voice Binding

- Keep community and personal libraries on `GET /model` with explicit `self`, pagination, title/tag/language filters, and sort mapping.
- Keep TTS on `POST /v1/tts` with Bearer auth, `model` header, `reference_id`, MP3, and 64 kbps.
- Do not synthesize paid previews solely because a model lacks an official sample. In the character picker, synthesize only when the user supplies explicit preview text; cache the first generated clip per voice for that picker session and delete generated preview files on close or selection. Previewing never binds a voice; only explicit selection mutates the character draft.
- Store a non-sensitive `FishAudioVoiceBinding` snapshot on each `CharacterInfo`.
- `CharacterDialog` exposes the same voice picker in structured and freeform modes, outside the structured-only profile fields. Freeform users bind voices through 人物头像册; both modes retain `CharacterInfo` IDs/bindings and use `VoiceGenerationPolicy` name matching. `CharacterRow` displays the bound voice title.
- Preserve `fishAudioVoice` through character-card copy/import/export/community transfer and AI auto-fill/rewrite materialization. AI output must not create or overwrite it.
- Treat inaccessible private bindings as unavailable. Never substitute another voice silently.

## Tagging and Confirmation

- Translation and tags explicitly declare VOICE_TRANSLATION/TRANSLATE and VOICE_TAGS/TAG contexts. The shared text transport adds neutral confirmation; Fish TTS itself never receives message-role wrappers. Failed auxiliary output remains an error batch and cannot reach synthesis.

- Generate tags only for assistant `DIALOGUE` and `THOUGHT` segments with one unique speaker match and a bound voice.
- Resolve `voiceTagModelId = null` to the current session model. Treat an explicit stale model as disabled; never fall back.
- Call the shared streaming text service with thinking disabled. Keep prompts in `PromptTemplates`.
- Translation/tag requests apply withoutOutputTokenLimit, omit both output-token aliases and configured maxOutputTokens, and keep the existing batch cancel action. Server-reported truncation still fails the batch before TTS; no partial-JSON/plain-text fallback.
- Treat `ChatSession.voiceLanguage = null` as original-language synthesis. When configured, translate each target through the resolved voice tag model before tag generation; audiobook mode still translates but skips tags.
- Validate translation JSON IDs, duplicates, omissions, unknown IDs, unknown fields, and blank results. Never fall back to original text after translation failure.
- Validate strict JSON IDs, duplicates, omissions, unknown IDs, tag syntax, and unchanged spoken text separately.
- Use fixed parenthesized S1 tags and bracketed S2 cues.
- Keep S1 fixed tags in official English. Make each S2 natural-language cue match the language of the synthesis text it controls.
- Strip dialogue and thought bubble borders from target text and assistant-response context before auxiliary translation or tag calls; preserve dialogue direction as plain context.
- Never send original text directly to Fish after tag failure.
- When legal AI output changes spoken text, enter `AWAITING_TEXT_CONFIRMATION`; show original/proposed text and wait for explicit use/reject.
- Release the foreground-work lease while awaiting user confirmation. Resume synthesis under a fresh lease after acceptance.
- Treat long-press regeneration text as already user-confirmed, but still validate Fish tag syntax.
- On regeneration, reuse only the confirmed synthesis/tagged text. Resolve the same character ID against the current card, then use its current voice binding and the current configured Fish model; fail visibly when unavailable and never fall back to the historical binding.

## Anchors, Files, and Lifecycle

- Give every assistant alternative a stable version ID. Store voice ownership and anchor state per message version; switching alternatives selects that version's voices without reconciling another version's anchors.
- Reconcile stable anchors only after edits within the same message version or segment deletion, using character-offset mapping plus monotonic segment matching. Lazily infer legacy versionless voices from the old anchor snapshot and source text without deleting audio.
- Preserve explicit target IDs for direct segment edits. Reattach a deleted target to the previous speakable segment, or make it a message-head orphan when none exists.
- Order voices by source segment order, then creation time. Allow multiple voices per anchor.
- Write downloads to `.part`, verify response length and parsable duration, then atomically replace the final MP3.
- On regeneration, persist the replacement before deleting the previous file. Preserve the old record/file on failure.
- Cancel and join message/session batches before repository or file deletion so completed network work cannot resurrect deleted voices.
- Clean stale `.part` and unreferenced owned audio without touching external files.
- SaveSlot v8 writes voice metadata as JSONL and copies each audio file through a bounded stream; restore through `FishAudioStorage.restoreArchivedAudioStream`, remap globally owned voice IDs, and delete newly materialized files if pre-commit work fails. Base64 restoration is legacy schema 1–7 compatibility only. Keep character-card package compatibility for older versions.

## Concurrency, Progress, and Playback

- Share one five-permit TTS semaphore across all batches. Whole-message generation calls translation once when configured, then tags once when required, then synthesizes missing targets concurrently.
- Persist successful targets during partial failure, but do not autoplay any partially failed or cancelled batch.
- Keep visible batch states for queued, translation/tagging streams, text confirmation, synthesis completion count/bytes, failure, and cancellation.
- Use shared `AiBackgroundWorkManager` only while model/TTS network work is active. Do not hold a lease for user decisions.
- Preserve autoplay order by session timeline and visual voice order. Later messages must not steal playback when earlier batches are pending.
- Discard autoplay intent when the originating ChatScreen is no longer resumed. Do not replay it on return.
- Stop active playback when the originating ChatScreen leaves composition. App pause or stop alone only drops autoplay eligibility.
- Serialize every ExoPlayer call onto its application/main thread. Never call `stop`, media-item APIs, `prepare`, or `play` from the app IO scope.
- Manual voice taps stop current playback and automatic queues, then restart the selected voice and continue through later visible voices in the same message.

## UI and Tutorial

- Keep voice progress cards visible near the composer rather than below a potentially long message.
- Put QQ-style voice bars below their anchor; when segmented bubbles are off, list all voices below the whole text bubble.
- Android retains independently selectable voice blocks for long screenshots. Desktop excludes all audio under the user's updated screenshot contract.
- Voice long-press supports tagged-text editing, regeneration, download to the public Download directory, deletion, and experimental standard-QQ native-voice sending. QQ sending uses a temporary state machine, exact `com.tencent.mobileqq:id/press_to_speak_iv` matching, notification-triggered accessibility hold, and dedicated media playback; it never mutates voice/message persistence. Require speaker output, nonzero media volume, user-selected chat and press-to-talk mode; clip at 50 seconds, never guess coordinates/text, and never claim delivery after releasing the button. Download names use the current rendered chat title plus the generation-time character snapshot and download time.
- Document every voice long-press action in the advanced tutorial. Keep basic setup, binding, generation, confirmation, and playback instructions in the basic tutorial.

## Workflow

1. Identify the owner: API, tag protocol, coordinator, anchor/repository, playback, transfer, or UI.
2. Reproduce from persisted state and exact batch phase before changing fallback or cleanup behavior.
3. Preserve isolation, historical snapshots, cancellation ordering, and foreground-lease boundaries.
4. Add focused JVM tests for request construction, tag parsing, anchor migration, or serialization.
5. Compile UI/shared changes, run CI-equivalent checks, then verify playback and background behavior on a release-signed device.

## Verification

Run from `app/`:

```powershell
.\gradlew.bat test
.\gradlew.bat :app:compileDebugKotlin --rerun-tasks
powershell -ExecutionPolicy Bypass -File .\ci.ps1 -SkipAssemble
```

With a connected physical device, use `chatbar-emulator-test` and the data-preserving release deployment. Reproduce playback with AndroidRuntime logs when investigating Media3 crashes.

## Stop Conditions

- Do not move voice metadata into `ChatMessage` or prompt/history inputs.
- Do not weaken strict tag parsing into hidden plain-text fallback.
- Do not access ExoPlayer from IO/background dispatchers.
- Do not hold foreground protection while waiting for user confirmation.
- Do not delete historical voice files because current character/card configuration changed.

- Desktop whole-app merge also preserves top-level explicit historical versions through DesktopHistoricalVoiceCustody: raw voice/owner/audio originals remain outside live cleanup; whole-root admission checks a matching identity/content witness and the live audio hash. This is separate from nested SaveSlot original-package custody. It grants no current-version playback or version inference; Android default behavior is unchanged.
