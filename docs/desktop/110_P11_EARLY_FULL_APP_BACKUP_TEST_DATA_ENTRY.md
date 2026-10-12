# 110 — P11 Early Intake: Official ChatBar 1.4.5 Full-App Archive as Desktop Test Corpus

**PROJECT EARLY FEASIBILITY / READ-ONLY EXACT-SOURCE REVIEW / NO BACKUP IMPORT OR DATA MIGRATION AUTHORIZED YET — 2026-10-12 JST**

## 1. Motivation and project boundary

The user has substantial real Android ChatBar data and wants to use the new official **1.4.5 whole-app `.cbbackup`** mechanism to seed high-fidelity Desktop P8–P10 compatibility tests, preferably before P11 itself is otherwise fully implemented. This is a useful acceleration opportunity but does not mean the Desktop can currently open a full archive or that an Android backup is automatically safe to restore on Windows.

This is an **early non-destructive P11 archive-intake and compatibility test-corpus track**, not a reordering or completion of P8/P9/P10/P11, nor permission to touch the user's phone, Android live data, Windows main root or secrets. D-038 / `95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md` remains the authoritative Public Preview portability requirement, and D-037 / `93_PUBLIC_PREVIEW_POLICY.md` remains the release gate. No automatic upstream sync, formal baseline promotion or official Prompt change.

Source authority:
- Upstream release **ChatBar v1.4.5**, published 2026-10-10 UTC; release commit **`1198a13cf3008de1358b8a69aa5e40b64797c8d9`**. Release notes explicitly claim complete backup of settings/chats/models/memories/images/voices/drafts/login and import-time verification, encrypted option, rollback.
- Exact official skill: `.agents/skills/chatbar-app-backup/SKILL.md` at the release commit.
- Exact implementation: `app/app/src/main/java/com/example/chatbar/domain/backup/{AppBackupModels,AppBackupCodec,BackupArchive,BackupJsonStream,BackupDataRegistry,BackupEntityValidator,BackupPreferenceTransfer,BackupPreparedSeal,BackupRestoreTransaction,AppBackupBootstrap,AppBackupService,LocalDataMaintenance}.kt`; `ui/manage/AppBackupHost.kt`.
- Exact tests: `app/app/src/test/java/com/example/chatbar/domain/backup/`; `doc/app_backup_manual_acceptance.md`.
- Desktop Project `95`, `19_DATA_COMPAT.md`, `109_P8_P10_PROJECT_CODE_REVIEW.md`, and pending `105` P8-S0D-R1 shutdown-failure visibility correction.

**Verification limitation:** official manual acceptance file explicitly records builds/compiled test source but **not a completed manual cross-device, interrupted-restore, or 1 GiB regression run**. Presence of official automated test source is not proof of their execution in this review. The Android→Android release is implemented; Android→Desktop is NOT.

## 2. Format and contents — exact-source facts

- File header: 8 ASCII bytes `CBBACKUP`, a bounded `AppBackupEnvelope` **format v1** header, and either unencrypted ZIP bytes or AES256_GCM_HKDF_1MB Streaming AEAD encrypted ZIP, with random Tink keyset wrapped under PBKDF2-HMAC-SHA256 **600,000** iterations + AES-GCM with authenticated associated data. The source's `formatVersion=1` is an official **archive format version**, unrelated to Character Package, SaveSlot schema or Desktop persisted Entity schema. Never hardcode it as an eternal cross-version assumption.
- ZIP payload has `files/`, `preferences.json`, `inventory.jsonl`, `manifest.json`. Streaming resource manifests capture relative path, logical byte length and SHA-256. Extractor validates duplicate entries, CRC, declared counts/bytes, allowed roots, hashes, size and directory traversal constraints. Export validates source file size/last-modified stability after scanning. `AppBackupService` performs export self-validation.
- Registered user-owned roots in `BackupDataRegistry`: `entities`, `images`, `audio`, `documents`, `draft_assets`, `save_slot_packages`, `novelai-vibe-payloads`, `danbooru_catalog`. User-owned Entity categories include characters/format/worldbooks, model/chat/drafts, voice/anchors, memory revisions/commit journals, vectors/RAG, Moments and image/Studio/NovelAI data; exact category list is authority of `BackupEntityValidator.serializers`.
- Intentional derived/ephemeral exclusions: shared-import staging, diagnostics, updates, prompt_dictionary, tag_completion, `entities/novelai_prompt_translation_cache.json`, partial or temporary files. OS permissions/device-local authorizations, Android app binary and dynamic process state are not packaged as desktop runtime features.
- Platform-owned **preferences contain secrets by default**: `BackupPreferences.novelAiToken`, `fishAudioKey`, `communitySession` in `preferences.json`, and model-configuration fields may additionally require independent secret classification. Official Android `AppBackupHost` offers **optional** encryption and explicitly warns that unencrypted backup holders can read both chats and keys. `AppBackupSummary.sourceFilesRoot` may contain an Android absolute path. Therefore a real `.cbbackup` is SENSITIVE—even encrypted input/password handling requires private local secure storage and zero secret logs. D-038 Desktop **ordinary export excludes secrets**, and Android archive recognition is not permission to hydrate Windows DPAPI automatically.
- `BackupDataRegistry.files` **fails export on unregistered top-level local directories**; `BackupEntityValidator` fails on unregistered Entity types and invalid SaveSlot/media. This is an explicit coverage registry, not guaranteed inclusion of arbitrary future data stores. Do not call it literally every OS byte.
- `BackupArchive.portablePath` converts registered Android filesystem/content URIs to `chatbar-backup-file:` tokens and copies external user media; original chat/prompt body strings are retained. On Android import, `BackupArchive.extract` resolves tokens to **destination `.absolutePath`**. **Desktop MUST NOT reuse Android final path write verbatim**, because D-027 Desktop persisted resources are root-relative. Archive tokens are transfer semantics; Desktop path adapters must materialize its own owned relative refs (including images/audio/documents and legacy inline cases) and prove restart/re-root works.
- Official `extract` checks Android `manifest.appVersionCode <= BuildConfig.VERSION_CODE`. Desktop cannot compare Android integer version codes to an invented Desktop version; validate the selected archive **format/schema/resource compatibility** against documented source instead. Never silently bypass supported-version checks.
- Android `BackupRestoreTransaction` owns a PREPARED/APPLYING/APPLIED/COMMITTED/ROLLING_BACK/ROLLED_BACK journal, old-root rollback and startup commit after successful repository initialization. Its `AppBackupBootstrap` depends on Android Context, shared_prefs and `android.system.Os.fsync`. On Windows, use `DesktopDataOperationCoordinator`, root ownership, strict JsonFileStorage, snapshot and platform durability primitives; don't copy Android Context or treat staged raw folder overwrite as equivalent.

## 3. Proposed accelerated safe track without full Preview permission

### M0 — Read-only exact-source portability and inventory mapping, can start early

Produce a category-by-category Android `.cbbackup` inventory and Windows owner matrix:
1. **LIVE NOW**: data that Desktop currently reads/writes safely (character, format/worldbook, messages, supported owned images and drafts, settings after field-specific mapping).
2. **SHADOW-ONLY/OPAQUE**: data whose owning Desktop runtime is still P8–P10/P12–P14 PENDING (voice media/anchors, full vectors/RAG, memory histories/journals, Moments/community/user research etc.). Keep these bytes losslessly inside the original encrypted archive and perhaps a separately protected non-authoritative test staging store; **do not let incomplete Desktop startup/cleanup code erase or rewrite them**.
3. **PLATFORM ADAPTER/SECRET**: Android-only authorization, credentials, Community session and external resources; never transfer secrets to ordinary persisted JSON, do not auto-login, and disclose unsupported capabilities.
4. **BLOCK BEFORE COMMIT**: unknown unvalidated entities, non-portable resource refs, unsupported active schema, corrupt media or decryption/space failures with no lossless preservation path.

Inspect official 1.4.5 codec and tests, exact current Desktop repositories and strict/read-failure ownership. Define a **versioned test inventory/report with category counts, total bytes, missing/unreadable references and hashed identities**, sanitized so chat/Prompt bodies, private filenames/paths, API keys and plaintext credentials are never logged or uploaded. No full local data access for Codex at M0; use synthetic golden fixtures.

### M1 — Early standalone read-only Desktop archive inspector

On the **existing feature branch only** and after separate Project authorization, extract/share the exact JVM-neutral codec/decryption/inventory validator; keep Android platform sources intact where they contain Context/UI/Keystore. Verify plaintext/encrypted archives against official 1.4.5 synthetic export golden fixtures, full SHA and envelope validation, wrong password/tamper/truncation/duplicate/path traversal/zip bomb/space/cancellation cases. No importing or switching root in this stage. Private real archive remains under the user's control; never upload to chat/GitHub/project files or remote telemetry.

### M2 — Isolated synthetic Desktop materialization / rollback, then real corpus opt-in

Build isolated **new Desktop data root**, snapshot of any preexisting destination, transactional staged materialization into Desktop-relative paths; verify source archive retained, all items either faithfully restored or explicitly losslessly shadowed/rejected, and re-open/read/rollback/crash-injection tests. Validate each P8 voice/P9 vector/P10 memory owner *only when that owner is implemented and accepted*; do not claim full runtime when only shared pure policies exist. A controlled local-only **user-authorized** encrypted real archive may later be tested on a separate disposable cloned Desktop profile; never use the primary live profile or the phone's only copy, never silently rewrite or discard unknown domains. Test preflight/cancel/corrupt/no-space first on synthetic inputs. The full P11 Android↔Desktop, Desktop↔Desktop and supported Desktop→Android round-trips are **still release gate work** and cannot be signed from M0/M1/M2 alone.

**Prerequisite for writing to any Desktop profile:** `P8-S0D-R1` data-safe window/process termination final gate (Project 109) has passed on exact source, so closing/reopening and root locking is trustworthy. M0/M1 read-only inspection can run separately without bypassing this operational gate.

## 4. User-side collection plan (no app data changes by Project)

On an **installed official Android 1.4.5** app with adequate space, user may enter **管理 → 全局设置 → 数据迁移 → 导出全量存档**, selecting **密码加密** (>=8 chars) and saving the completed `.cbbackup`. Prefer checking app's version before exporting; do not uninstall/reset Android or replace its data. Keep the original phone's data, the encrypted `.cbbackup` and another independently retained backup, ideally on different media.

Keep the encrypted archive in a **private folder outside the Git repo** (for example a suitably ACL-protected `I:\CCB-Private-Migration\Incoming\`). Never send the archive, decrypted files, password, secret-bearing `preferences.json`, or raw chat/Prompt content to ChatGPT, Codex chat outputs, GitHub repo, issues, logs or public/cloud artifact stores. No real-archive decryption/import action has been authorized in this turn. When M1 is ready, the user enters the password **locally via an explicit UI**, not by command line, chat or source files.

## 5. Decision and next review

**Feasibility: YES in principle.** Exact official source provides an explicit full-app transfer format suitable for a future Windows adapter; Desktop already has shared Entity/storage and an isolated root/backup/ownership foundation. The source-level hazard is real: Android implementation is **not directly portable** due to absolute-path materialization, Android app-version checks, SharedPreferences/Keystore, unsupported Desktop domain owners and transactional bootstrap differences.

**This review authorizes no production backup implementation, no migration, no real user archive access, no P8–P10 status promotion and no phase reorder.** Recommended next Project task: targeted **M0 mapping** and synthetic official 1.4.5 compatibility fixture design, possibly parallel to but without modifying the ongoing P8-S0D-R1 code. Only then approve M1 exact small implementation, then M2 dry run, before touching actual Android data.

**CURRENT: OFFICIAL 1.4.5 `.cbbackup` CONFIRMED / EARLY DATASET INTAKE TECHNICALLY PLAUSIBLE / DESKTOP IMPORT NOT IMPLEMENTED / REAL CORPUS PROTECTED / PUBLIC PREVIEW D-038 UNCHANGED.**
