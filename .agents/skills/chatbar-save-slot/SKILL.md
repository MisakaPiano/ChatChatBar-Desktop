---
name: chatbar-save-slot
description: Maintain ChatBar conversation SaveSlot creation, listing, loading, import/export, v8 streaming packages, media inclusion policies, legacy schema compatibility, and transactional restore. Use when changing or diagnosing chat archives, `.cbsave` files, archive memory use, missing archived images/audio, or SaveSlot progress and cancellation.
---

# ChatBar SaveSlot

Desktop player role association and per-session avatar use optional `desktopPlayerRole`/`desktopPlayerAvatar` on both ChatSession and SaveSlot. Shared `SaveSlotSessionPolicy` carries the role; restore accepts the separately materialized player-avatar reference. `SaveSlotPackageStorage` counts/packages/validates that image and exposes `materializePlayerAvatar`; Desktop Inspector, capture and RestorePlan include it in the existing media proof and transaction. Image policy NONE omits the avatar image, preserving the explicit role. Global player avatar remains a whole-app setting, not a conversation archive field.

Keep archive size independent from peak app memory. Treat SaveSlot as a cross-store restore transaction, not one large serialized object.

## Entry Points

- Models and legacy inline fields: shared `app/sharedCore/src/main/kotlin/com/example/chatbar/data/local/entity/SaveSlot.kt` (byte-preserving move, same package/serializer; Android remains a consumer). Desktop `DesktopSaveSlotInspector` and `DesktopSaveSlotInspectionPicker` remain external-file-only bounded inspection ports; `DesktopSaveSlotController/Panel` own the separate native management workflow.
- Summary persistence and legacy raw export: `data/repository/SaveSlotRepository.kt`.
- The omitted-image marker constant is shared `app/sharedCore/src/main/kotlin/com/example/chatbar/domain/chat/ChatImageReference.kt`; Android SaveSlot and Desktop image views consume the same token.
- v8 package codec: shared `app/sharedCore/src/main/kotlin/com/example/chatbar/domain/chat/SaveSlotPackageStorage.kt`; Android keeps its Context/image/audio factory. `SaveSlotSessionPolicy`, `SessionCopyPolicy` and `SaveSlotJsonTransfer` share the official field/identity/legacy rules without a schema change.
- `ChatSession`/`SaveSlot.desktopCharacterParticipation` and `desktopAutoPauseAbsentPeople` are optional Desktop settings retained through capture/export/import/restore. Entries keep explicit card IDs, manual disabled IDs and automatic paused IDs separately; unrelated/past card scopes remain intact. Empty/default settings are omitted. Existing clients without this Desktop feature do not apply it; it is not an official Android participation capability.
- Chat orchestration and rollback: `ui/chat/ChatViewModel.kt`.
- Image/audio choices, progress, cancellation, and document pickers: `ui/chat/ChatSettingsDialog.kt`.
- Streaming entity replacement primitives: `app/sharedCore/src/main/kotlin/com/example/chatbar/data/local/JsonFileStorage.kt`, `data/repository/ChatRepository.kt`, and `domain/rag/RagRepository.kt`.
- Voice file restore: `domain/voice/FishAudioStorage.kt`; use `chatbar-fish-audio-voice` when voice ownership changes.
- Image metadata/display behavior: use `chatbar-image-generation-runtime`.
- Long-term-memory snapshot semantics: use `chatbar-long-term-memory`.
- Home duplication: `domain/chat/SessionCopyService.kt` uses a temporary original-media/audio package without adding a SaveSlot. It copies `ChatSession` settings directly, remaps message/image-anchor/RAG references through `SessionCopyPolicy`, preserves session-scoped source-turn/version identities, restores independent memory nodes and voice anchors, and removes the temporary package. Only current memory is copied; SaveSlot lists and memory revision history are not duplicated.

All abbreviated source paths are under `app/app/src/main/java/com/example/chatbar/`.

## Desktop audio handoff

- P8 `DesktopVoiceArchiveHandoff` is an audio-only port for v8 JSONL/media and supported legacy 1–7 resources. It does not implement package or entity-set transactions. P11 must validate mappings/counts, own the app-data scope, normalize versions before ID changes, stage before commit and use strict reference proof for rollback; see `docs/desktop/142_PHASE8_VOICE_P11_HANDOFF.md`.

## Desktop session transactions

- `DesktopSaveSlotService` creates/imports/exports/restores v8 and supported legacy slots, duplicates sessions and rebinds cards/extra worldbooks. Restore and rebind publish a before-change safety slot in the same transaction. Rebinding retains the existing format override, messages and versions, and explicitly resets worldbook timers.
- Confirmed deletion revalidates the complete lightweight SaveSlot catalog under exclusive admission. Remove only the selected record and a package with no remaining references. The session journal verifies package deletion against the deleted record in retained old evidence; shared/live images and audio are never deletion targets. Successful deletion remains recoverable in retained transaction evidence.
- Admission order: voice session mutation reservation → message mutation reservation → memory `withSessionQuiesced` → bounded preparation → audio maintenance/drain → real coordinator exclusive publication. Never hold a normal lease while upgrading to exclusive. Close failure preserves the native lease and blocks publication.
- `DesktopSaveSlotArchive` validates immutable source copies and exports without replacing destinations. `DesktopSaveSlotSummaryReader` skips inline bodies/Base64 for the catalog. `DesktopSaveSlotRestorePlan` spools message/RAG records and restores one message at a time; `VoiceMessageRepository.restoreForSessionStreaming` rebuilds anchors without retaining the complete conversation.
- Archive capacity remains 256 MiB. `CapacityRefused` distinguishes input, local capture and prepared recheck before bounded hashing/copy; Controller explains that an over-limit recovery safety capture has not replaced the current session. No smaller/incomplete safety slot is substituted. Large whole-app backups use their separate transport limits.
- RestorePlan reuses SessionCopyPolicy for foreign-session source packages or source message IDs already consumed by another live session. Service supplies a strict, coordinator-admitted live-message observation (also needed after a legacy library envelope has been rebound). Message/generated-from/RAG references and anchor namespaces become independent; explicit version/source-turn identities remain intact. Ordinary same-session restores retain their message IDs. Tests: DesktopHistoricalSaveSlotProcessTest foreign child-JVM path and DesktopSaveSlotServiceTest legacy variants. The test-only `prepreview/SlotIsolatedRestoreProbe` independently verifies the ordered target namespace and generated-from mapping, rather than requiring foreign source IDs to survive; text/version/source-turn and media-byte checks remain separate.
- Shared `SaveSlotPackageStorage.importPackage` assigns a new library id/session/name/time and physical package reference while retaining original package bytes. Whole-backup merge/custody validate this precise envelope via `DesktopSaveSlotLibraryEnvelope`; package-local aliases adapt nested owners during a fork, with original bytes retained independently. Do not require an imported library owner to equal the immutable source manifest or globally rebind its source session.
- Production Archive opts into explicit historical-voice custody: unknown nonblank version IDs (bounded256) are retained with the immutable original package, owner/session/audio/length/count/CRC checks intact. RestorePlan opts into the matching Handoff policy and uses the strict Desktop repository, so a pruned ID is never inferred as current. Default standalone Inspector/Handoff remain strict; opt-in inspection reports historicalVoices and never authorizes restore. `DesktopHistoricalSaveSlotProcessTest` exercises actual import/restore, child-JVM exit/reopen and export; it is synthetic Windows JVM evidence, not Android/GUI acceptance.
- Full-backup merge preparation uses `backup/DesktopMergeSaveSlotPackage` for a separately retained original and strictly verified rewritten v8 package. Media bytes/explicit old voice versions stay intact; shared MessageAlternativeVersionPolicy pins implicit versions before changing message IDs. The helper requires complete caller-provided bindings and does not activate a root.
- `backup/DesktopSaveSlotSourceCustody` is the owned, Desktop-only original-package/raw-outer-record domain. Inventory/root validation require exact source, index, file set and owner proofs; interrupted publication refuses rather than sweeping bytes. It is separate from live slot files and persists through snapshots/Desktop whole backup. Whole-root nested inspection matches Archive's existing historical-version retention policy.
- `backup/DesktopSlotHistoricalVoiceCustody` adds a distinct indexed child to the existing historical-voice domain when actual session restoration creates new voice IDs. The session journal appends the complete original package plus precise restored bindings in the same transaction; deleting the library slot/live audio does not remove custody. Whole-backup validation derives its audio/binding proof from the retained package. Legacy zero byteLength is undeclared, positive lengths remain exact; v8 resource IDs resolve independently of filename extension. The combined restore/custody budget remains 256 MiB and over-budget attempts retain current data.
- `DesktopSessionInventory` proves complete owned entity sets. `DesktopSessionCommitJournal` retains old/new bytes, publishes the entity/media graph, refuses foreign changes and recovers under root ownership before caches open. Cancellation cannot interrupt publication/cache reconciliation; uncertain publication seals restart-required. Old media and failed-attempt evidence are retained, not swept.
- Windows archive-only readers opt into verified extended local paths; this does not change ordinary playback admission. Staged images become flat owned references; MP3 ownership receipts remain required. Desktop focus tests include `DesktopSaveSlotServiceTest`, `DesktopSessionCommitJournalTest`, `DesktopSaveSlotArchiveTest` and the unchanged voice/maintenance contracts.

## Package Contract

- Schema 8 uses a ZIP `.cbsave`: `manifest.json`, line-delimited `messages.jsonl`, `rag.jsonl`, optional `voices.jsonl`, and media beneath `media/images/` or `media/audio/`.
- Keep manifest lists empty for v8; `SaveSlotPackageRef` owns counts and package metadata. Old schema 1–7 remains inline JSON and decode-compatible.
- Summary listing must parse only lightweight top-level fields. Do not deserialize old Base64 media to show the SaveSlot list or export an unchanged legacy archive.
- Detect import format from stream bytes, not filename or MIME type. Export v8 as `.cbsave`; retain legacy JSON export.
- Validate manifest version, duplicate/blank IDs, declared counts, and every media reference before replacing target-session data.

## Cross-platform proof

- `tools/desktop/interop/Official145InteropTest.kt` runs actual pinned official Android import/load/chat/export APIs in a new synthetic emulator. Wait for `dependenciesReady` and model usability after official configuration refresh; no timing-only delay substitutes for readiness.
- `Run-DesktopInterop.ps1` produces and consumes formal Desktop archives; `verify_roundtrip.py` compares original message order/versions, media bytes, voice references, RAG and current Memory. Emulator instrumentation remains distinct from real-device/manual acceptance.
- Official SaveSlot has no input draft field. Destination draft retention is not source draft portability; full backup owns `chat_drafts`.

## Bounded-Memory Invariants

- Stream messages and RAG one record at a time; keep only a small repository batch in memory.
- Stream original images/audio with a fixed buffer. For compressed images, decode and recycle one sampled bitmap at a time; animated GIF stays original.
- Desktop v8 inspection separates streamed aggregate expansion (256 MiB) from structured data (64 MiB). Existing 256 MiB source, 16 MiB entry, 1 MiB JSONL record, 256 KiB manifest, entry/count/ratio checks remain. It derives legacy message versions through shared `MessageAlternativeVersionPolicy`, matching restore preparation; malformed explicit version sets always refuse, while unknown voice references require the explicit historical-custody policy described above. Structure inspection alone does not validate codecs or authorize restore.
- Never rebuild package media as Base64, a whole ZIP byte array, or one in-memory message list.
- `SaveSlotImagePolicy.NONE` must not read image bytes. Preserve one omitted-image token per attachment so stored attachment indexes and metadata links survive restore; these tokens are not visible attachments.
- Transform `ChatMessage.images` and matching `generatedImageMetadata.imagePath` together for every image policy.

## Restore and Ownership

- Stage and validate the package plus materialize resources before committing session data. Replace message and RAG entity sets through their streaming transactional APIs.
- Remap restored message session IDs, RAG IDs, voice IDs, image paths, metadata paths, and background path to the target session.
- Persist replacement records before deleting old owned media. On pre-commit failure or cancellation, delete only files created by that attempt.
- Never delete external files. Package deletion may target only the deterministic app-private SaveSlot package path.
- Restore only the current long-term-memory snapshot. Do not archive histories, running jobs, coordinator state, or stale runtime progress.
- `automaticImageGenerationEnabled` is a default-false manifest/session setting. ChatViewModel writes it at SaveSlot creation and restores it in packaged and legacy paths; loading history never triggers automatic images.
- `novelAiNaturalLanguageMode` is also default false in session/manifest JSON; preserve it at creation and both restore paths independently of the selected NovelAI model, so switching back to V5 restores the preference.

## UI and Lifecycle

- Session settings retain a dedicated 存档 tab. Before loading, the settings host resolves pending parameter drafts through save/discard/continue, then rebases on restored session values; switching tool tabs alone keeps parameter drafts.

- Default new SaveSlot to no images. Offer compressed and original policies explicitly; audio inclusion is independent.
- Keep creation, loading, import, and export progress visible and cancellable. Do not dismiss the dialog before a long operation reports completion or failure.
- Chat bubbles and image preview lists skip restored omitted-image tokens, including previously imported archives. Omitted-image-only messages render no bubble or metadata row; mixed messages retain text/voice and original attachment block indexes. A missing included resource is an archive validation failure.
- Startup may clean stale partial package files, but must not sweep complete packages without matching SaveSlot ownership checks.

## Review Checklist

1. Trace one message, generated-image metadata record, background, voice, RAG chunk, and memory snapshot through create → export → import → restore.
2. Check cancellation before materialization, during media copy, and during transactional replacement.
3. Check schema 1–7 listing/export/load separately from v8.
4. Verify default no-image, compressed-image, original-image, and audio-off paths without increasing peak memory with archive size.
