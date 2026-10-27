# 199 — Local Project Disk Space Audit and Safe, Limited Cleanup

**CURRENT USER-REQUESTED LOCAL STORAGE HYGIENE / AUTHORIZED UNDER [197](197_P11_LR6_USER_AUTHORIZATION_AND_LOCAL_SPACE_SCOPE.md).** This is NOT permission to delete user data, source, historical proof, retained journals or Git history. GitHub Project cannot inspect the actual `H:` disk; measurements and guarded reclamation require the user's local Codex runtime. Report actual size measurements, not guessed savings.

## 0. Read-only census is mandatory before any deletion

Working copy only: `H:\ChatChatBar-Desktop`; no traversal of reparse points, junctions, symlinks or mount-point links. This is a disk owner boundary, not a license to inspect the user's other drives or entire profile. Do not touch `H:\手机CCB导出` for cleanup **under any circumstances**.

Use read-only Git status, `git count-objects -vH`, filesystem metadata and safe disk-usage enumeration, following exact root identity. Produce a CSV/JSON inventory by size/owner/relative path for:
- `.git` objects, packs and LFS (historical provenance; NOT candidate for deletion);
- tracked `docs/desktop/evidence/**` and retained project docs/source (immutable historical evidence);
- repo-local `app/.gradle`, `.gradle`, `.kotlin`, IDE cache (expensive to recreate; recommendation-only);
- per-module `app/**/build/{classes,tmp,generated,distributions,compose,test-results,p11-lr*,other}` with **separate exact owners**; NEVER assume all build descendants are disposable;
- test-specific `app/desktopApp/build/p11-lr*` (may contain **private witnesses or necessary raw evidence**; retain by default);
- root-local zip/archives, ignored/untracked sources, workspace and backups (retain by default).
Count allocated-on-disk if cheaply available; otherwise mark all estimates as logical file bytes (no invented physical savings). Report size before/after, path counts, top reclaim categories, and free space. Do not emit personal descendant names or raw file content into public committed reports. User-only local full-path audit may stay ignored.

## 1. Absolute protections

**NO DELETE, REWRITE, REPACK OR CLEAN**:
- `.git/**`, Git pack/objects/logs/refs, any tracked `docs/desktop/evidence/**`, historical original RED/GREEN, source snapshots, Task Registry/Project docs or already committed test proof. No history rewrite / `git gc` / `git prune` / `git clean -fdx`.
- all user-created source/notes/unknown ignored/untracked files; private test witnesses and archives, `*.cbbackup`, `*.cbsave`, user PNG/cards/images/audio, `*.zip` unless separately verified disposable with user consent; installed app/preview distributable/installer, important release binaries.
- `H:\手机CCB导出`, any active Desktop data root, `%APPDATA%`, `%LOCALAPPDATA%`, Windows protected stores, password/Key/Supabase/Git secrets, root-switch/backup/rollback/journal/snapshot or Portable data.
- global Gradle/Maven/IDE caches, outside-repo paths, all symlinks/reparse descendants, all paths with unknown owner/changed identity/active open handle, or anything that serves as the only surviving proof of recovery.
- `app/desktopApp/build/p11-lr1`…`p11-lr4` task areas *by default*: previous real-file local private witnesses/ignored test roots may live there, and cannot be inferred redundant from their names.

## 2. Exact auto-reclaim allowlist (subject to evidence)

Only these narrowly scoped **LOCAL** outputs may be deleted without a second user confirmation, and only after an explicit per-item dry-run manifest and all checks below pass:

**A. Proven purely regenerable Gradle task scratch:** exact compiler-task scratch directories `app/{desktopApp,sharedCore}/build/tmp/{compileKotlin,compileTestKotlin}`, if present and confirmed Gradle-owned/generated only; no human/unknown files or descendants. Keep classes and test-results unless separately approved. Extend module/task list ONLY through a separate review, not a wildcard.

**B. LR5 historical synthetic task roots:** exact known accepted task-owned directories
`app/desktopApp/build/p11-lr5/first-protocol`,
`app/desktopApp/build/p11-lr5/native-reader`,
`app/desktopApp/build/p11-lr5/protocol-green`
**only** when every descendant is verified fake synthetic data, no private witness or release artifact, original source/XML/child exits/final snapshot ZIP are retained in the pushed `docs/desktop/evidence/p11-lr5-20261024` Git tree, and no running task needs them. Their Git-tracked evidence must first match its recorded identity; do not delete a surviving sole witness or unrecorded additional file.

**C. LR6 disposable output AFTER LR6 evidence is safely published and Project review accepted:** *not during this same Codex run*. Any user test profile/VM image and its rollback snapshots require their own owner/lifetime review; no automatic VM deletion merely because testing ended.

For every candidate: prove canonical target stays within repo; no junction/reparse children; `git status` does not show meaningful tracked/untracked work; every file has **provably task-generated provenance**; exact file identity hasn't changed since dry-run; no current build/Codex/IDE/Java test/recovery process using it; expected output is re-creatable; a verified alternative original receipt is committed where needed. Unknown/locked file means SKIP. One final predelete guard checks every path and recorded total byte/entry count. Remove the **exact allowlisted item**, not a wildcard parent, with no force-unlocking or unrelated-process termination.

Record `DELETE_SUCCESS`, `RETAIN_UNKNOWN`, `SKIP_ACTIVE`, `SKIP_CHANGED`, `NEEDS_APPROVAL` per entry; verify actual sizes afterward and do not claim bytes freed from the pre-delete estimate. If no candidates pass, report `0 BYTES SAFE RECLAIMED`; do not lower conditions to invent cleanup progress.

## 3. Separate recommendation-only candidates

Possibly-large `app/.gradle` or `.gradle`, other `build/classes`, `build/test-results`, intermediate cached distribution/installer, private restored test roots, `docs/evidence` Git tracking and history are **not auto-delete eligible**. List size and consequences (offline rebuild risk, source proof loss, version availability). Preserve until a new precise approval and archive/retention policy. Removing tracked evidence from the working tree won't reclaim history and will make Git dirty. Do not optimize Git repository size with history rewriting.

If the project size primarily comes from tracked original evidence/history, propose **future evidence packaging/retention architecture** and new checkpoint size caps without rewriting accepted commits. For completed runs, preserve immutable manifest, source/git identities, raw original RED/GREEN, test artifacts and reproducibility.

## 4. Execution ordering and receipts

Perform stage (0) early, **before** LR6-B VM setup or allocating large disks. Stage (2) only after the read-only census and LR5 proof verification; ideally after active coding/Gradle processes have exited and no active use remains. Schedule LR6 experiment work and deletion so they never overlap. Capture `LOCAL_DISK_AUDIT_BEFORE`, `CLEANUP_DRY_RUN`, `CLEANUP_DECISION_LEDGER`, `LOCAL_DISK_AUDIT_AFTER`, `REMAINING_APPROVAL_REQUIRED`. Commit only relative, sanitized category/size/decision summaries to Git; local exact-path manifests should be ignored/private.

Success is **verified recovery of permitted disk space with no data/evidence loss**, not reaching an arbitrary cleanup quota. Do not use administrator or elevate privileges to access/clear locked paths.
