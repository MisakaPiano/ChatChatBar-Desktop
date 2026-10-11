# 98 — P8-S0C Project Review and Local Artifact Cleanup Allowlist

## CURRENT USER AUTHORIZATION — A+B PERMANENT DELETION APPROVED (2026-10-12 JST)

**EXPLICIT USER APPROVAL RECEIVED / EXECUTION PENDING / ACTUAL RECOVERED BYTES = 0 AS OF THIS RECORD.** After the original read-only audit and Project path-by-path review, the user answered **「同意」** to the specific question asking permission for Codex to permanently delete **Group A's 12 generated directories + Group B's project-local `app/.gradle`**. This is **irrevocable-file-deletion authorization for exactly the 13 literal paths below**, conditional on all original §4 safety preflights. Historical pre-approval NO DELETION statements below remain true only for their earlier checkpoint.

Root: `H:\ChatChatBar-Desktop\` (local Windows repository, NOT any user-selected data root).

1. `app\app\build\generated`
2. `app\app\build\intermediates`
3. `app\app\build\kotlin`
4. `app\app\build\tmp`
5. `app\desktopApp\build\classes`
6. `app\desktopApp\build\generated`
7. `app\desktopApp\build\kotlin`
8. `app\desktopApp\build\resources`
9. `app\sharedCore\build\.transforms`
10. `app\sharedCore\build\classes`
11. `app\sharedCore\build\kotlin`
12. `app\sharedCore\build\resources`
13. `app\.gradle`

**Everything else stays protected and outside authorization**, including the five B1 R2-2..6 packages (~1,031 MiB), WiX tool cache, `desktopApp/build/libs`, ALL gate/manifest/XML/screenshots, accepted packages, user data/Portable roots, backups, `.phase7-live-data`, SecretStore, global `~/.gradle` and any unknown or corrupt bytes.

Before deletion Codex MUST confirm clean worktree, intended root/branch and origin, all 13 exact paths (allow missing paths as SKIP), none are symlinks/junctions/reparse points including descendants, no active related builds/IDE/Java Gradle daemon holds, and no post-audit additions of evidence, private media, credentials or unknown data. If **any** invariant is uncertain: STOP BEFORE DELETING ANY ITEM, return explicit blocker for Project. No wildcard deletion, no parent `build` removal, no `gradlew clean`, no `git clean`, no rebase/reset, no upstream/desktop/master/sync movement.

After safe deletion Codex must report **each** path deleted/skipped, observed files/bytes before removal, real pre/post available disk space, final clean Git status, and any failure. A new compiler run will recreate some cache directories; 707.32 MiB is the prior **logical** estimate, never a guaranteed physical reclaim. No new Gradle build or P8-S1 implementation in the cleanup turn. GitHub Project cannot execute or independently attest the Windows deletion.

---
**PROJECT DOCS RECONCILIATION REVIEW PASS / S0 ACCEPTED / S1 SCOPE IN PRINCIPLE / CLEANUP CONDITIONAL ALLOWLIST ONLY / NO DELETION**

Date: 2026-10-12 (JST). Source: Codex S0C user-supplied local read-only inventory, independently reviewed against GitHub `feature/phase8-fish-audio`, P7 frozen acceptance documentation, Program Control D-038, and `96_LOCAL_ARTIFACT_RETENTION_AND_CLEANUP.md`.

## 1. S0C GitHub review

- Reviewed feature: `af49434884df24a9d99161edc33ed0a2ef89068e`.
- Merge parents independently read from GitHub: P8 `5851f000ecd193c502fbb5b563c6d048a421062e`; Program Control `desktop` `5ef95428421f7c693b010ac88f51d470804ab07a`.
- Compare at review: feature ahead **7**, behind **0**, compared with `desktop`; exactly five `docs/desktop/*` paths differ: `00`, `17`, `21`, new `94`, new `97`. **No production source/test/build diff**.
- `00_PROJECT_SOURCE_MAP.md` retains the P8 `94`/`97` entry and D-038 / `95` plus `96`; `21_CURRENT_STATE.md` preserves P7 CLOSED, D-038 and P8 S0 Project review with historical entry; `17_ROADMAP.md` records current P8 S0 PASS, P11 .cbbackup blocker, P7 qualified integration, and no baseline promotion.
- Latest `93_PUBLIC_PREVIEW_POLICY.md` on feature includes D-038 G blocker; `95`/D-038/Release checklist/parity were not lost. Formal verified upstream remains **1.4.1**, observed official 1.4.5 is a selective future compatibility target.
- Codex reported local=origin feature, clean worktree, `git diff --check` PASS, docs-only merge, no tests/calls/data writes. Project did **not** itself inspect local Git worktree or user H: bytes.

**Review disposition: DOCS MERGE PASS.** P8 S1 pure-core scope approved in principle by `97`, not executed in this inventory step. Explicit protection of P7 production and current Program Control retained.

## 2. Local cleanup candidate audit — evidence separation

Codex read-only metadata inventory reports `H:` available space approximately **588 MiB**, approximately **8.57 GiB scanned** excluding protected user data. This is **not a Project filesystem observation**. Sizes below are reported *logical* MiB, not allocated disk blocks or guaranteed reclaimed space. No files were deleted/moved/quarantined.

### Conditional allowlist A — pure generated build intermediates (twelve exact directories)

Preconditions in §4 are mandatory; permission to execute deletion has **not** been granted.

| Exact local path relative to H:\\ChatChatBar-Desktop\\ | Reported MiB | Reported files | Project classification |
| --- | ---: | ---: | --- |
| `app\app\build\generated` | <0.01 | 2 | Conditional reconstructible output |
| `app\app\build\intermediates` | 348.30 | 12,040 | Conditional reconstructible output |
| `app\app\build\kotlin` | 13.63 | 358 | Conditional reconstructible output |
| `app\app\build\tmp` | 1.37 | 40 | Conditional reconstructible output |
| `app\desktopApp\build\classes` | 24.21 | 3,721 | Conditional reconstructible output |
| `app\desktopApp\build\generated` | 70.11 | 19 | Conditional reconstructible output |
| `app\desktopApp\build\kotlin` | 9.39 | 241 | Conditional reconstructible output |
| `app\desktopApp\build\resources` | 70.23 | 22 | Conditional reconstructible output |
| `app\sharedCore\build\.transforms` | 4.20 | 1,062 | Conditional reconstructible output |
| `app\sharedCore\build\classes` | 10.60 | 1,869 | Conditional reconstructible output |
| `app\sharedCore\build\kotlin` | 6.99 | 326 | Conditional reconstructible output |
| `app\sharedCore\build\resources` | <0.01 | 1 | Conditional reconstructible output |

Logical subtotal **approximately 559 MiB**. Codex classified these paths git-ignored, untracked build intermediates without independent accepted gate XML/log/manifest/packaging. Project verified only their *class and reported provenance*, **not local files or hashes**.

### Conditional allowlist B — project Gradle metadata cache (one exact directory)

- `H:\ChatChatBar-Desktop\app\.gradle` — **148.30 MiB / 275 files** (reported), latest modified 2026-10-12 05:21 JST.
- It is project-local, git-ignored generated Gradle state; may be regenerated but may invalidate incremental and configuration caches. No global Gradle user home, SDK, wrapper distribution or offline dependency root is allowed.
- Reclaim candidate **only if needed** after A; user approval is still required. This cache can regenerate when S1 builds.

**A+B logical total ≈707.32 MiB per Codex report**, not a guaranteed physical free-space gain. Next Gradle builds may recreate parts of A/B.

### HOLD — NOT on any deletion allowlist

| Exact item / class | Evidence and decision |
| --- | --- |
| `app\desktopApp\build\phase7-b1-r2-2-distribution` | ~206.24 MiB — P7 historical rebuild; no exact duplicate replacement proof. **REVIEW-QUARANTINE ONLY** |
| `app\desktopApp\build\phase7-b1-r2-3-distribution` | ~206.24 MiB — same |
| `app\desktopApp\build\phase7-b1-r2-4-distribution` | ~206.23 MiB — same |
| `app\desktopApp\build\phase7-b1-r2-5-distribution` | ~206.22 MiB — historical failed manual R2-5 acknowledged in `71_PHASE7_SLICE_B1_ACCEPTANCE.md`; **retain failure reproduction provenance until matched** |
| `app\desktopApp\build\phase7-b1-r2-6-distribution` | ~206.23 MiB — historical failed manual R2-6 acknowledged in `71`; keep associated evidence |
| `app\desktopApp\build\phase7-b1-r2-7-distribution` | **KEEP**: definitive B1 accepted frozen source/package in `71` |
| `app\desktopApp\build\phase7-apng-r1-distribution` | **KEEP**: final user 3/3 Windows receiver, `89` |
| `app\desktopApp\build\phase7-final-one-session-distribution` | **KEEP**: frozen 18-group user acceptance package, `85`/`86` |
| Other A/C/B1+C/B2/D/WIN/W3 user-accepted packages, distribution hashes, package manifests, gate summaries, JUnit XML, historical Native/HWND/`ScrollBy` RED, screenshot evidence ZIPs | **KEEP**: `69`–`92` user/Project acceptance |
| Nested `app\desktopApp\desktopApp\build\phase7-b1-r2-6-evidence` | **KEEP**: separate screenshot/evidence path, not a distribution duplication |
| `app\build\wix311` (~99.28 MiB) | **HOLD**: installer tool dependency; recoverability and packaging impact not verified |
| `app\desktopApp\build\libs` (~52.91 MiB) | **HOLD**: application JAR source identity/unique evidence not verified |
| `.phase7-live-data`, `build/**/live-data`, launch/input/reference/profile roots, any selected/Portable data root, backups, SecretStore, orphan audio/media, recovered/corrupt files | **UNKNOWN / PROTECTED**; no cleanup or deep scanning |
| `app\desktopApp\build\phase7-*gate` and corresponding exact reports/manifests | **KEEP**; no glob deletion |

Five R2-2..6 packages have total **~1,031.15 logical MiB HOLD**, not counted as safe reclaim. GitHub `71` names R2-7 as accepted, with R2-5/R2-6 failures; this **does not prove** earlier package binaries have no unique diagnostic value. No move/quarantine operation on these five without new exact SHA+manifest/evidence preservation review.

## 3. Disk-space consequences

- ~588 MiB free is tight for P8 S1 JVM+Android/Gradle; cleaning A alone might bring reported logical available to around **1.15 GiB** if disk allocation matches, A+B toward **1.30 GiB**. These are **illustrative upper-order estimates**, not measured physical free space.
- Moving candidate packages to a quarantine **on the same H: volume does not free H: space**; freeing capacity would require approved exact deletion or off-volume archival with retained provenance.
- Even after A+B cleanup, repeated S1 compiler/generated outputs could refill the disk. Re-measure actual allocated free space before S1 test/build; if low, reconsider build output / disk space rather than deleting frozen evidence.

## 4. Mandatory cleanup execution controls, once specifically authorized

1. Codex preflights exact local repo+branch, clean worktree, origin and 13 allowlisted literal paths. Recount logical file/byte inventory and ensure none gained extra evidence/JSON/user-data/secret-like files after audit; unexpected contents STOP.
2. Confirm **no Gradle daemon/Java/Kotlin compiler/Compose application** is using these paths; hold/locks or unknown process => STOP. Verify each exact path is an ordinary directory inside intended repo; reject symlink/junction/reparse point or descendant reparse; do not follow links. Reject any path escaping the canonical root.
3. No wildcards, no parent-level `build/` removal, no global Gradle cache, no source/test/resources outside the **exact** listed build-generated subdirectories. On mismatch STOP. Deleting directory content is only permissible under an approved *exact-path* list after no protected descendant is found.
4. For reversible cleanup, choose an explicitly approved **off-volume** archival target with manifest if needed; same-drive quarantine offers no disk relief. For permanent removal of exactly A and/or B, require explicit user approval of scope and irreversible nature; do not interpret the inventory as authorization.
5. Preserve current `*.gradle.kts`, tracked files, JAR distribution manifests, frozen Windows EXEs/JARs, native/GUI RED, package gate evidence and all user-owned/Portable data. Never run `git clean -fdx`, `gradlew clean`, `reset --hard`, force-push, entire `build` wildcard or global cache cleanup.
6. Report each exact deleted/moved path, count, pre/post free space, any skipped locks/errors and clean `git status`. If permissions/availability change, STOP instead of broadening deletion.

## 5. Current Project decisions

- **S0C docs reconciliation: PASS.** Feature remains independently reviewed docs-only; no source changes.
- **A (12 generated dirs) + B (1 project cache dir) are PROJECT-CLASSIFIED CONDITIONAL CLEANUP CANDIDATES**, pending explicit user scope and Codex path preflights. They are **not yet authorized to delete**.
- **Five B1 R2-2..6 package dirs: HOLD FOR PROVENANCE REVIEW**, not delete approved. Canonical accepted packages/gates and all protected data: KEEP.
- P8 S1 code extraction scope remains `97_PHASE8_S0_PROJECT_REVIEW.md`. No actual S1 production edits or upstream sync at this checkpoint.

**CURRENT: S0C REVIEW PASS / CLEANUP CANDIDATE ALLOWLIST READY FOR USER APPROVAL / RECOVERED DISK SPACE = 0.**
