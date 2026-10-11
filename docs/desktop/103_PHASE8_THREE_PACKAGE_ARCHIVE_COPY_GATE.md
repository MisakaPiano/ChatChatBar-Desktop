# 103 — P8 Three Historical P7 Package Roots: Copy-Only Cold Archive Authorization

**SUPERSEDED / DO NOT EXECUTE (later user decision, 2026-10-12):** User withdrew the previously approved three-package `I:\CCB-Desktop-Archive\Phase7\` archive-copy plan in favor of **direct deletion of artifacts independently shown to be obsolete**. **No I: copy was executed, no H: deletion took place under this document.** New controlling task: `104_PHASE8_DIRECT_STALE_ARTIFACT_CLEANUP.md`. The historical approval below remains for chronology, **not current execution authorization**.

**CURRENT USER-APPROVED NARROW OPERATION — 2026-10-12 JST / ARCHIVE COPY & VERIFICATION ONLY / NO H: DELETE OR MOVE**

## 1. Source of authorization and control

Following Project review `102_PHASE8_STORAGE_FOOTPRINT_RETENTION_REVIEW.md`, the user explicitly agreed to:

> 完整复制三个历史 P7 Gradle build/package 目录到 `I:\CCB-Desktop-Archive\Phase7\` 并校验；**暂不删除 H: 原件**。

This approves a **non-destructive, copy-only archive operation for exactly three named existing roots**, with verification and no work beyond that. This **does not** approve deletion of the source, relocation (cut/move), deduplication, symlink substitution, compression, process termination, temporary cleanup, data-root migration, or changes to P8 production code. Any later H: source deletion or other distribution archival is a **separate Project/user decision** after source-vs-archive audit. The separate earlier authorization for A+B 13 generated paths under `96`/`98` remains **blocked by its original process safety gate** and is not reactivated by this archive permission.

Project GitHub feature baseline at time of approval: `feature/phase8-fish-audio @ a7de46e78e85c73c9f46eb977a1d73e8b0a30f8c`. `desktop @ 5ef95428421f7c693b010ac88f51d470804ab07a`. Formal upstream validated baseline stays 1.4.1; no syncing or Prompt changes.

## 2. Literal source → destination path allowlist

Copy all regular files with their relative paths and metadata inside each **complete** source root; do not omit its generated outputs or make runnable-payload-only copies. Root is `H:\ChatChatBar-Desktop\app\desktopApp\build\`.

| Exact source root beneath H: build | Exact destination root beneath I: |
| --- | --- |
| `phase7-final-distribution` | `I:\CCB-Desktop-Archive\Phase7\phase7-final-distribution` |
| `phase7-r2-distribution` | `I:\CCB-Desktop-Archive\Phase7\phase7-r2-distribution` |
| `phase7-r2-reaudit-distribution` | `I:\CCB-Desktop-Archive\Phase7\phase7-r2-reaudit-distribution` |

Codex last inspected **413 files** per root, combined `1,596,863,515` logical bytes (`1,522.888 MiB`), and found **no source reparse points** in these roots at that time. This is prior evidence, **not a waiver of current preflight**. Each directory contains its complete separate **210-file** `compose/binaries/main/app/ChatChatBarDesktop` Windows distribution plus 203 non-runtime build/support files. The three distinct Desktop business JARs must not be conflated; preserve entire tree.

## 3. All-target preflight, before any copy

1. Confirm local `H:\ChatChatBar-Desktop` Git origin and exact feature branch, clean worktree, local/remote expected SHA or non-destructive FF-only alignment after verifying ancestry; never change desktop/master/sync, use reset, rebase or force. GitHub Project cannot inspect local files or verify package bytes.
2. Verify all three source roots are ordinary directories within the verified canonical repo; confirm 413 regular files each and a bounded size close to the reported values. Reject file or ancestor directory **symlinks/junctions/reparse points**, unexpected executable, user-data/profile, backup, keys/secrets, new sensitive/unknown files, modified file sets, or unreadable files. No following links or copying unknown paths.
3. Independently verify the three source roots are stable **and unused by live processes or Gradle/Java/build operations**. The known resident B2 R3 / WIN JVMs may still be running in *different, excluded* roots. Do not terminate them or touch those roots. If source activity, lock or source mutation is uncertain, **STOP before copy**.
4. Verify **I: is an NTFS fixed volume** with sufficient free space and writable `I:\CCB-Desktop-Archive\Phase7\` parent, and it is not a junction/symlink or data root. Confirm destination roots and all per-root staging names are **absent before writing**; on an existing destination/staging item **STOP**, do not overwrite, merge, delete or try to repair it. Check reparse parents on I:. Volume capacity alone does not prove disk health or physical-device independence; this is local cold archive, not offsite backup.
5. Ensure no unexpected tree change between inventory and copy. Do not read or publish chat, secrets, personal files, environment/command-line secrets or user backup content.

## 4. Copy and verify; no deletion

- Work sequentially, one root at a time, low I/O priority/concurrency (e.g. single-threaded copy) to avoid aggravating the user's observed CPU/disk responsiveness. No new CCB launch, no tests, Gradle, updater or provider calls.
- Create a **new uniquely named temporary staging directory on I: under this exact Phase7 archive parent**, never on H:. If staging cannot be created cleanly, STOP. Copy the entire source tree preserving relative paths and ordinary file attributes/timestamps where supported, never follow links; avoid `robocopy /MIR`, `/PURGE`, `/MOVE`, `/MOV`, `/EFSRAW`, deletes, in-place hardlinks or blanket wildcard cleanup.
- Enumerate a deterministic source/target manifest of relative path, file type, byte size and **SHA-256 of every regular file across these THREE targeted roots**, not across the 9-GiB repository. Ensure exact identity of paths, file counts, bytes and hashes; verify the 210-file runnable payload structure, source-specific Desktop JAR + SharedCore JAR + EXE, non-runtime `libs/desktopApp.jar`, metadata/args files. Spot compare each historical known SHA from `38` / `44`; F business JAR has a newly observed hash but not an original independent historic manifest.
- Persist **source+archive SHA-256 inventory and a concise verification report only in I: under a separately named `archive-evidence` child**, not inside historical copied trees or in gitignored H: build outputs. Manifests contain filenames/sizes/hashes only; redact any sensitive source names if encountered (in which case stop before copying).
- After exact verification, rename each verified I: staging directory **within the same I: filesystem** to its specific final archive root. Ensure final inventory still matches and final path is unique. If validation or rename fails, retain the I: staging/evidence files for Project review, **do not clean them up automatically**, do not remove H: original, and STOP with an exact failure.
- Recheck the three H: roots' file sets/byte identity to detect unintended modification. Source deletion count must be **0**. No execution test of old EXEs is authorized in this archival turn; archive verification is file-level, not a new runtime product acceptance.

## 5. Stop rules and handoff

- Any identity/provenance, authorization, reparse, ownership, path-safety, write permissions, destination-exists, insufficient-space, source-mutation or byte/hash mismatch => STOP. If a partial I: staging copy exists, **leave it untouched and reported**; do not widen authority to cleanup. No final success claim for incomplete archives.
- Existing D-037/D-038 data-safety policy applies; never copy active real-user data roots, Portable profiles, SaveSlots, backups or SecretStore. No encrypted credential archive transfer is being performed.
- Report **all three source→destination pairs** and whether `VERIFIED_ARCHIVED` / `PARTIAL_STAGING` / `SKIPPED` / `FAILED`; each exact file count/byte total, manifest/hash parity, timing/CPU/IO notes, available H: and I: space before/after, and unchanged `git status` and branch pointers. Distinguish Codex local execution from Project GitHub review.
- Nothing about this approval makes the full **7.58 GiB** historical distribution collection redundant or safe to delete. It is only the first 3-root archival stage. No H: bytes are expected to be recovered until a *separate* user-authorized source removal after verification.

**CURRENT: COPY-ONLY AUTHORIZATION RECORDED / I: ARCHIVE NOT YET EXECUTED / ORIGINAL H: DATA UNTOUCHED / A+B 13-PATH CLEANUP BLOCKED.**
