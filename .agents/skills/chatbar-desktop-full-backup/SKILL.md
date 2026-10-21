---
name: chatbar-desktop-full-backup
description: Maintain Desktop whole-app cbbackup export, offline inspection, credential exclusion, resource mapping and scheduled pre-repository restore.
---

# Desktop Full-App Backup

## Entry points and format authority

- `app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/`: selected official ChatBar 1.4.5 @ `1198a13cf3008de1358b8a69aa5e40b64797c8d9` v1 envelope, Tink 1.23.0 codec, registry and streaming JSON transducer. The formal upstream baseline is still 1.4.1. Version code 84 comes from the selected official build source.
- `DesktopFullBackupArchive`: bounded authenticated scan without plaintext disk output, then credential-free staging and ZIP central/inventory verification. Preserve original input and transaction evidence; inspection/snapshot/mapping scratch has a separate owned lifetime.
- `DesktopFullBackupMapping` / `DesktopBackupPolicy`: explicit store registry, logical model ID to Windows physical key, flat image/document resources, re-persisted verified MP3 and ownership receipts. Unknown fields/stores fail before commit. Internal brush preferences survive in a local singleton and return to official preferences.json on export.
- `DesktopBackupScratch`: registers only invocation-created files/directories with native identity. Removes redundant decrypted ZIP immediately; prepared entries live until confirm/discard/replacement/panel leave/cancellation/shutdown. Export and mapped plans close after use. Cleanup never traverses unknown children or touches restore journals; identity changes report cleanup unconfirmed. No sweeping historical or crash-left work without ownership evidence.
- `DesktopBackupCompatibility`: payload-free categories distinguish unsupported Moments, other unknown entity types, incompatible fields, and external credentials. All still reject the whole archive before live commit; no credential-transfer permission or opaque-preservation contract is implied.
- `DesktopFullBackupGraphValidation`: offline message/version/voice, memory graph, media and SaveSlot checks; no model calls or default-filling repair.
- `DesktopFullBackupService`, `Controller`, `Panel`: app-owned operation and explicit replacement confirmation on the Data page. Passwords are volatile and cleared. No SecretStore interface is injected; nonempty incoming credential fields are rejected before plaintext staging.

## Restore lifecycle

- Prepare and verify under normal admission; flush the chat composer before snapshot/schedule. Join the automatic backup scheduler before reserving audio. Confirm audio stop/close, then enter exclusive coordination.
- `DesktopFullBackupCommitJournal.schedule` copies old/new evidence and atomically authorizes the next startup; it does not replace live content. Seal the coordinator as restart-required with the authorization marker. UI prevents further navigation/writes and offers normal exit, never automatic restart.
- `DesktopStartupGate` holds the root lock and calls whole-app recovery before index/session recovery and before constructing repositories. Partial startup installation rolls back; committed evidence is verified. Foreign bytes or changed evidence block startup. Old/new journals remain retained, never swept.
- Local root selection, desktop-settings, diagnostics/backups and the separately located protected SecretStore are preserved. A full archive containing Desktop-specific entities (including retrieval_model_config, absent from the selected Android registry) is not claimed Android-compatible.

## Verification

- Focused suites: DesktopFullBackupArchiveTest, MappingTest, CommitJournalTest, ServiceTest, plus StartupGate, data coordination and audio-maintenance regressions. Synthetic fixtures only; no real root restore.
- `DesktopFullBackupProcessTest` exercises schedule/recover, hard child-JVM exit during apply, and corrupt evidence refusal in independent JVMs. `tools/desktop/interop/` provides opt-in pinned-official Android instrumentation and Desktop archive consumers; require per-test results, not just build/ADB exit success.
- Plain/encrypted format tests do not substitute for actual Android 1.4.5 or Windows restart acceptance. `.cbsave`, full `.cbbackup`, native installer retention and Preview release approval remain distinct evidence categories.
