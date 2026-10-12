# 111 — Project Review: P8-S0D-R1 Same-Branch Merge, Safe Wait Visibility and History RED

**PROJECT INDEPENDENT GITHUB DIFF / SOURCE REVIEW PASS FOR NARROW R1 IMPLEMENTATION — S0D OVERALL ACCEPTANCE HOLD ON UNRESOLVED 133-SUITE RED — 2026-10-12 JST**

## 1. Frozen evidence/commit topology

- Reviewed latest feature `feature/phase8-fish-audio @ a04f7ff25f0016a62c956b1de3b238c4f7ce63d4` and its raw GitHub Git commit API parent identities: parent #1 `86c7aa082510eafc0b3171045e75f95bd9fb2cec` (original local S0D-R1 code), parent #2 `e989c70c06aa81ef4eb09cbf72e919df43a6b4b8` (Project docs-only).
- Exact common ancestor `15caf46806411d161155709bae29f528be398cc9`. GitHub compare `a04..R1` has **only 3 docs file changes**; compare `a04..remote-docs-parent` has **only 4 lifecycle source/test changes**. Compared to common ancestor the merge tree changes **exactly seven files**, no hidden Android/shared/Prompt/Package/schema changes.
- The 4 R1 files: `app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopProcessLifecycle.kt` (modified), `DesktopShutdownWaitObserver.kt` (new); test `DesktopShutdownWaitFixture.kt` and `DesktopShutdownWaitObserverTest.kt` (new). The three docs-only changes: `docs/desktop/00_PROJECT_SOURCE_MAP.md`, `21_CURRENT_STATE.md`, `110_P11_EARLY_FULL_APP_BACKUP_TEST_DATA_ENTRY.md`.
- Project independent GitHub source/ref verification: `desktop @ 5ef95428421f7c693b010ac88f51d470804ab07a`, `master @ 550409689df8c51f459fb50b4e04c8ac2fa4bf35`, parked `sync/1.4.4 @ 9b6378dbb595dd2f3ff5143a7a8e46653c99e721`, unchanged. No additional branch/worktree created by GitHub. Local worktree clean and exact Windows paths/hashes are **Codex-reported**, not Project observed.
- P7 qualified engineering history and accepted `44b50a4f...` production remain frozen. No P8, P9 or P10 Phase ACCEPTED, no `desktop` merge, no upstream baseline promotion.

## 2. R1 lifetime code review

**SOURCE REVIEW PASS for the scoped wait-visibility implementation**; not evidence that all OS hang scenarios can display UI.

- `closeDesktopWithRecovery` now wraps `appContainer.close()` per attempt with an `AutoCloseable DesktopShutdownWaitObserver`; its disposal precedes recovery-window handling and the outer picker/root/terminal process-exit work.
- A daemon `Timer` triggers a warning after **5,000 ms**, not a timeout/cancel/interrupt of the underlying worker. It dispatches a Swing/AWT *safe close pending* status to the EDT. Its window has **no force-quit or early-retry action**, only a fixed safe diagnostic and copy action. Only successful close completion dismisses the warning and proceeds to the already-reviewed outer safe JVM exit. Failure path still retains unsafe data-root ownership and shows separate recovery diagnosis. A delayed queued EDT display cannot resurrect a status after the observer closes; `close()` cancels timer, synchronizes closed state, and flushes UI disposal via EDT.
- No change to `DesktopAutomaticBackupScheduler.close().join()` or `DesktopDataOperationCoordinator.closeAndDrain().await()` themselves, to keep incomplete background writes safe rather than faking a closed transaction. Thus an *ordinary long but eventually successful* wait becomes visible to the user.
- **Explicit limitation:** If the AWT EDT itself is unable to execute events, the status frame and synchronous disposal may fail to become visible/finish. This correction cannot categorically solve EDT deadlocks or prove the historical B2/WIN headless JVM root cause. A future reliable out-of-process watchdog is a separate risk/design question; do not force kill, abandon data root or claim proof by four-second zero-CPU samples.
- New `DesktopShutdownWaitObserverTest` covers fast close, queued no-ghost, backup + coordinator waiting with root lock and no exit, eventual completion/cleanup, retry/error. Real AWT `DesktopShutdownWaitFixture` confirms warning, minimize behavior, no early exit/lock release, final write and reacquire, and failure/recovery visibility for **isolated synthetic data**. Tests do **not** constitute user-manual acceptance of actual-world stuck writers or historical process death.
- No real chat, backup, Credential, Prompt, Secrets, user profile, SaveSlot or real paid API data is touched in new source/tests.

## 3. Codex local test evidence and exact RED

Codex-reported local S0D-R1 checkpoint (reviewer did not execute JDK17/tests/Windows):

| Gate | Result | Classification |
| --- | --- | --- |
| Focused lifecycle | **4 suites / 37 tests PASS**, 0 failure/error/skip | Codex local |
| JDK17 compile and whole changed-tree diff-check | PASS | Codex local |
| Real wait/recovery fixture | PASS | Codex local |
| Full *bounded non-Native* Desktop executable regression | **133 suites / 1185 tests / 1 failure / 0 errors / 0 skips** | **RED stays RED** |
| 210/210 packaged file-manifest readback | PASS | Codex local |
| 6 real Windows launcher/JVM WM_CLOSE cycles (including isolated reopened synthetic root) | **0/0 clean exits, 0 lingering PIDs**, lock reacquisition | Codex local, not Project-run or user-manual |
| History single failed test rerun twice in isolation | **2 × 1/1 PASS**, neither is all-suite rerun | Codex local |

The failed `app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/DesktopUnifiedViewerTest.kt:271` (`history direct viewer follows filtered order across batches without selecting or opening detail`) indexed `scene.nodes().filter { it.label("图片") }[1]` while only **1** matched Image node existed. The original test and `DesktopStudioHistory.kt` Git blobs are **byte-identical to P7 frozen source**, as Project independently checked. This is not proof that the R1 change caused a UI bug or proof that it did not impact scheduling.

**Project independently identified a specific test-readiness mismatch:** test waits for 4 `配方详情` button semantics but does not wait for the second decoded image. `DesktopStudioHistory` always renders recipe buttons from the album item, while `DesktopOwnedImage` uses `produceState` + `withContext(Dispatchers.IO)` and displays a temporary `正在读取图片…` placeholder before creating the `Image(contentDescription = "图片")` semantics node. Thus recipe-ready is NOT image-ready. This establishes a **credible asynchronous test selector race**, not a conclusively proven sole root cause without saved full semantic tree/trace and repeated bounded suite evidence. Test file itself is unchanged from prior P7 source.

No method changes may reclassify the original **133 / 1185 / 1 failure RED** as passed. Two isolated 1/1 passes narrow the failure to possible timing/order interaction but do not erase it.

## 4. Project decision and next bounded task

**S0D-R1 code diff/source safety: PASS within specified scope.** **S0D-R1 engineering/milestone acceptance: HOLD** because one executable Desktop regression failure is not yet accounted for by a reviewed stabilization gate, and actual P7 residual cause is unknown. Do not rollback the four files, reimplement shutdown, merge `desktop`, or begin Fish S2/P9/P10 runtime on this evidence alone.

Project authorizes only a **single-scope test-readiness stabilization candidate**, on the existing `feature/phase8-fish-audio` branch, after fresh ancestry/worktree check:
1. Record initial `DesktopUnifiedViewerTest` exact failure case/old XML; **do not edit** History/Viewer production implementation or official Prompt. In the test, after the existing four recipe-button readiness check, use `scene.until` (bounded, diagnostic on failure) to wait for at least **two actual Image semantics nodes** matching `图片`, then use the second one for `double`. Preserve all original behavioral assertions (filtered order, selected empty, unchanged draft and no detail entry); do not add sleeps, swallow exceptions or bypass an unavailable image. If there is evidence of a genuine product defect rather than slow asynchronous load, **STOP without adjusting expectations** and report.
2. Re-run the focused test multiple bounded times, plus affected Viewer/History selectors, and one full 133-suite-type Desktop non-Native regression **at the exact repaired test/code HEAD**, preserving original full RED and isolated PASS XML/log separately. Do not silently omit known failing test, treat Gradle UP-TO-DATE as executed, or mark historical Native/ScrollBy RED green.
3. If the new bounded wait still flakes, classify UNRESOLVED; stop, capture precise semantic tree and failures, and return to Project. If fully green, return exact commit SHA and local reports, and Project reviews the **test-only diff** before changing the final S0D acceptance status.
4. No new branches/worktrees, no protected refs, no forced push/rebase/reset; FF-only local sync under normal conditions. Do not repeat the already completed 43-path cleanup, do not touch protected historic B2/WIN PIDs or user data, and do not start P8 Fish/Memory persistent runtime. Keep original R1 code unchanged.

The latest `110` official 1.4.5 `.cbbackup` feasibility review is preserved in this branch but does not authorize importing user data or alter this test gate.

**CURRENT: SAME-FEATURE MERGE VERIFIED / R1 SOURCE PASS / FULL DESKTOP RED OPEN / S0D-MILESTONE HOLD / NARROW TEST-ONLY CORRECTION NEXT / P8–P10 UNACCEPTED.**
