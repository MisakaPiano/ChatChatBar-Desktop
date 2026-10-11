# Phase 7 — APNG Clipboard File-copy Project Code Review

**CURRENT POST-MERGE:** R1-A/R1-B Project GitHub code review PASS at production `44b50a4f`, user Windows receiver 3/3 PASS in `89`; this accepted code is **integrated into `desktop`** by FF-only `5dfc6a50` and Project Phase 7 qualified engineering closure `92`. The original `cdfbd0fe` REVIEW HOLD and its `NO PACKAGE / NO MERGE` are historically true at that checkpoint, but no longer the current R1 disposition. Native/OS clipboard temp policy limitations remain disclosed.

**CURRENT 2026-10-12: ORIGINAL HOLD SUPERSEDED BY R1-A/B PROJECT CODE REVIEW PASS @ `44b50a4f1f613ee05dce33bbcd3e720adde9bc91`; EXACT-SOURCE PACKAGE 210/210 PER CODEX; WINDOWS RECEIVER 3/3 USER PASS; NO MERGE.**

Review history: initial `cdfbd0fe` HOLD was legitimate. R1-A/B follow-ups `2c83db1d`, `4d92fefc`, `44b50a4f` closed incomplete staging/foreign inventory, confirmed-release seven-day grace and clock rollback/re-activation. Source and test review performed by ChatGPT Project; focused JDK17 2 suites/19 tests reported by Codex. User acceptance and exact package authority: `89_PHASE7_APNG_CLIPBOARD_USER_ACCEPTANCE.md`. External `java.io.tmpdir` cleanup remains a disclosed platform limitation and Phase 7 final merge is **not authorized** (see `90`).

## Historical first-pass code review (preserved)

**PROJECT REVIEW HOLD / R1 REQUIRED / NO PACKAGE / NO MERGE**

## Exact code and source authority

- Reviewed branch source: `feature/phase7-win-auxiliary-ux @ cdfbd0feaff0492c49784a6108aba1b26da69784`.
- Comparison base: prior Project docs-only `99f7742ba90d8d1cc4d3839adcc6bb478f50d6c7`, one commit ahead, no behind.
- Only these four files changed: new `DesktopImageClipboardFiles.kt` (103 lines); `DesktopImageToolsDialog.kt` +8/-3; `DesktopImageViewer.kt` +6/-2; new `DesktopImageClipboardFilesTest.kt` (127 lines); **+244/-5 overall**.
- Protected `desktop`, `master`, parked `sync/1.4.4` and previous frozen D branch unchanged. Formal validated upstream baseline stays CCB 1.4.1.
- Upstream `ImageMosaicEditor.kt::ApngProcessingResultDialog` / `ImageActions.kt::shareFromImageLongPress` provide file sharing for APNG. This implementation direction correctly chooses Windows file-list clipboard rather than losing animation to bitmap `imageFlavor`.
- Codex reports JDK17 compile/diff-check PASS and focused **2 suites / 9 tests PASS**, clean worktree, 8m18s, weekly 85% → 83%. Project independently inspected actual GitHub source/test diff, **did not run Windows clipboard integration or the user's H: gate file**.

## Correct implementation decisions retained

1. Static `复制结果` still exposes `imageFlavor` without persistent file staging.
2. For animation, UI changes to `复制文件` and `DesktopImageClipboardFiles.copy` stages original bytes, providing an actual file path through `javaFileListFlavor`. Tests compare staged content byte-for-byte, detect disguise and verify GIF-derived/restored frame colors and delays.
3. Existing Save/Undo/Redo/Cancel, image tool conversion, codec, Prompt and schema owners are unchanged. The clipboard now has an explicit limited inventory and cautious no-delete-on-unknown behavior.
4. Windows receiving applications have **not yet been tested**. File-list clipboard support depends on destination; do not claim direct animated-image paste works in all apps.

## Two R1 correctness issues — HOLD

### R1-A: interrupted staging can permanently poison all later copies

- `DesktopImageClipboardFiles.kt` lines 64–70 create `copy-UUID` directory, then image file, then `owner.txt`, before clipboard publication. If the process exits, storage fails or cancellation interrupts between these writes, an incomplete directory remains.
- The next `copy()` takes **all root entries** and calls `directories.map(::inspect)` (lines 42–45). `inspect` lines 75–85 requires a complete valid record and two intact entries; one incomplete/orphan copy directory throws **before any new copy can be staged**.
- Because unknown ownership is deliberately never cleaned, this is a persistent failure for all future APNG file-copy attempts, even after disk space/clipboard access is restored. Existing tests do not simulate crash/incomplete `copy-UUID` followed by a successful new copy.
- **Required:** keep unknown/partial entries unchanged (no speculative deletion), but do not let a single recoverable interrupted cache entry irreversibly block new copies while bounded capacity is available. Use a durable publish protocol or a conservative quarantined/invalid inventory policy with safe quotas. Explicitly test injected incomplete entry + valid subsequent copy and no deletion of orphan/foreign data.

### R1-B: seven-day superseded-copy retention is measured from creation, not supersession

- Source comment promises superseded file copies live at least seven days (lines 15–18); cleanup at lines 53–61 checks `time - entry.created >= 7d` and that the path is not on the **current** clipboard.
- Counterexample: copy file A on day 0 and leave it current until day 10. Copy B on day 10, then C shortly afterwards. When copying C, A is no longer current and is immediately deleted because it is >7 days old — despite having been superseded only moments before. The code does **not** guarantee seven days *after* supersession.
- **Required:** define and implement a proven grace period from **first confirmed clipboard release/supersession**, not solely from initial creation, or revise the retention promise and obtain explicit Project approval for a weaker lifetime if safe. Current accepted intent is conservative seven-day superseded retention. Add a time-controlled test for long-active A → B → C within seconds and verify A survives the grace period.

## Design boundary / additional review note

- Staging root is `java.io.tmpdir/ccb-image-clipboard-v1` (lines 94–96). Platform/system temp cleanup could remove cached bytes even while a file remains referenced by the clipboard; seven-day retention is **app policy, not an OS guarantee**. R1 should consider an app-owned per-user nonvolatile clipboard cache path or document an evidence-based Windows alternative, without modifying user data roots, Portable Mode or unrelated secrets.
- Preserve the cache size limit 32 entries / 256 MiB, no deletion of arbitrary files, no symlink or junction traversal, no automatic sweep of unrelated directories. Failed clipboard publication must leave current source/result intact and report failure.
- `desktopImageTransferable` is based on a real staged file; do not replace the animation with one static `BufferedImage`, do not rewrite `ApngDisguiseCodec`.
- Only R1-A and R1-B require the immediate narrow production correction; other risks may be documented if they cannot safely fit within minimum-sufficient architecture.

## R1 acceptance and handoff

- Codex edits confined to `DesktopImageClipboardFiles.kt` and `DesktopImageClipboardFilesTest.kt` unless a demonstrable specific dependency needs approval.
- Focused deterministic tests for incomplete staging, unknown-content preservation, long-active-then-superseded 7-day retention, and prior static/animated byte-perfect copies; JDK17 compile/diff-check/scope. No Native Robot/HWND, historical synthetic ScrollBy, whole 18-group repeat, live AI calls, upstream writes or `desktop` merge.
- After Codex R1 commit, Project must independently review its actual GitHub diff before an exact-SHA package or focused receiver Windows check.
- Historical user group 11 remains **FAIL**, group 15 **NT**, other 16 groups retain **PASS**. The 115-item evidence ledger stays 59 SIGNED / 12 USER-EVIDENCED / 9 PARTIAL / 28 TECH-EVIDENCE / 7 OPEN; literal `66` checkbox sync remains separately pending due an earlier tool write restriction.

**DISPOSITION: R1 FIX REQUIRED. Current `cdfbd0fe` is NOT the final accepted Phase-7 production code.**
