# 105 — Desktop Shutdown / Process Lifetime Fix Gate

**LATEST PROJECT STATUS AFTER R1 REVIEW (2026-10-12):** R1 code `86c7aa0...` now integrated to the existing P8 feature as normal dual-parent merge `a04f7ff25f0016a62c956b1de3b238c4f7ce63d4`, Project GitHub diff/code **SOURCE PASS**. Independent AWT daemon 5-second warning for unbounded safe drains, no early exit/lock release, with Codex-reported real wait fixture, 37 focused tests and 6 packaged zero-residual Windows exits PASS. **Milestone still HOLD** because Desktop bounded full `133 suites / 1185 tests / 1 failure` was RED at unchanged P7 History test; isolated 1/1 twice PASS does not erase failure. Async image-readiness mismatch identified in source and bounded **test-only** correction + full green review proposed by `111_PHASE8_S0D_R1_PROJECT_REVIEW.md`. AWT EDT deadlock UI visibility not generally guaranteed; old historical B2/WIN PID cause unknown. Original prior R1-needed paragraph below is historical.

**LATEST PROJECT REVIEW AFTER IMPLEMENTATION (2026-10-12):** Initial S0D production `31d2a48...` successfully moved `exitProcess(0)` to *after* cleanup and passed six local packaged WM_CLOSE cases as Codex reported. However `DesktopAutomaticBackupScheduler.close().join()` and `DesktopDataOperationCoordinator.closeAndDrain().await()` can wait indefinitely, after Compose disposes the main window but **before** the exception-based recovery UI can appear. `109_P8_P10_PROJECT_CODE_REVIEW.md` requires **S0D-R1** an independently visible, bounded-delay waiting/status surface that does not cancel writers, retains lock, closes on eventual success, and is tested on suspended fake backup/coordinator paths. Initial success route positive but overall S0D acceptance **HOLD**. Do not claim original old-P7 hang cause was found.

**PROJECT ENGINEERING DEFECT / P8 PRE-S1 OWNER / USER REQUESTS RELIABLE TRUE EXIT / IMPLEMENTATION PENDING — 2026-10-12 JST**

## 1. Confirmed symptom and outstanding question

After users closed historical P7 B2-R3 and WIN windows, Codex observed launcher→JVM pairs 34912→4492 and 20904→27568 alive with no discoverable main window. JVM and JLI modules were loaded. Repeated four-second CPU and I/O deltas were zero; Win32 threads waited. The JDK17 jcmd attach returned Access denied. This is **confirmed headless process retention**, **not** proof of an intentional tray app, self-relaunch, malicious work, a Java deadlock, or any particular blocked shutdown stage. Process IDs are historical and MUST be reverified by start time and executable path.

P7 qualified acceptance remains historically valid. Do not silently relabel it as failed, nor treat a past empty-profile WM_CLOSE smoke PASS as evidence that these two old running instances exited. Authorities: docs/desktop/99_DESKTOP_RESIDUAL_PROCESS_DIAGNOSTIC.md and 100_DESKTOP_RESIDUAL_PROCESS_PROJECT_REVIEW.md.

## 2. Source-grounded candidate, not proven cause

GitHub exact P7-B2 / P7-WIN / final P7 Main.kt is the same blob (9c92f80827eb355d925b396136bc495305d2a0da). Main.kt calls Compose application(exitProcessOnExit = false), forwards Window Close to exitApplication(), then performs runDesktopApplicationLifecycle finally with runBlocking { appContainer.close() }, closes DesktopPlatformPickers, and only afterwards releases DesktopDataRootOwnership, except when explicitly retained due to unsafe writer/drain timeout.

**Leading source hypothesis:** A successful return of all safe close/finally blocks reaches the end of main() without an explicit terminal process-exit guarantee, allowing non-daemon AWT/JVM threads to keep the windowless process alive. This does NOT prove which phase these older instances reached: Compose itself, active data-operation, task/draft drain, automatic backup or native dispose could still block before main() returns. Keep test evidence distinct from source inference.

## 3. Product invariant and phase priority

**P8-S0D — Desktop Shutdown Lifetime**, before P8-S1 voice implementation and certainly before Public Preview:

- Without a user-opted background/tray feature, normal Exit/last-window Close means all Compose/AWT windows disposed, active work safely stopped/drained, edits/drafts committed or recoverably retained, backup/coordinator/migration closed, data-root ownership released, then **both JVM child and jpackage launcher EXIT**.
- A failed/unfinished drain must **not** trigger a forced process exit, fabricated successful shutdown, or silent background process with all user windows gone. The user needs a visible deferred-close/error/recovery path while preserving storage ownership.
- Actual persisted user data remains production-valued from the Public Preview boundary; no secrets in diagnostics or logs; do not rewrite CCB Prompt, Entity, Package or upstream behavior.
- Repair as a narrow Desktop composition-root/platform lifecycle change, without reopening the P7 accepted feature set. Future P15 tray work may deliberately change the user-selected background behavior, but cannot justify today's invisible residual instances.

## 4. Proposed Codex implementation steps after 104 safe space cleanup

1. Verify current clean P8 feature and GitHub Program Control. **104_PHASE8_DIRECT_STALE_ARTIFACT_CLEANUP.md is the latest approved disposal policy; unexecuted 103 archive copy is SUPERSEDED.** Only independently unused build artifacts may be pruned. Keep presently active B2/WIN directories and real profile/data, backups, locks, historical gates and RED. Do not run substantial Gradle builds on an almost-full H: volume; verify >=5 GiB working headroom or use independently validated alternate build output.
2. Instrument **minimal, sanitized shutdown phase IDs** with timestamps in a disposable isolated test build: close requested/deferred; Compose returned; task/network drain; draft/editor persistence; backup stopped; coordinator drain; migration close; picker close; ownership released; clean main exit handoff. No private chat, Prompt, keys, user paths, or unsolicited dumps. Report where the sequence stops, with UNKNOWN if not proven.
3. Reproduce on a **fresh isolated Desktop profile/data root**, using real packaged launcher and real WM_CLOSE/titlebar Close; no real provider/API/credential requests and no use of the two historical headless processes. If no current-source repro is possible, say so and scope mitigation as preventative, not a proven old-instance root-cause fix.
4. On confirmed **successful** completion of Compose, appContainer.close(), platform picker disposal, and root-lock release, implement an **explicit testable terminal JVM-exit mechanism at the outermost main entry** (for example a narrow injectable ProcessTerminator). Keep exitProcessOnExit=false so existing ordered Kotlin finally cleanup runs. Do **not** call exitProcess in onCloseRequest, inside application, or unconditional finally, and do not invoke Runtime.halt, taskkill, shutdownNow on live writers or delete lock files. If application() or drain never returns, fix that **actual earlier blocker** rather than adding unreachable exit code.
5. On unsafe shutdown/error or writer-drain timeout, keep the lock and recoverable user bytes; ensure user gets an actionable visible failure/deferred-close state instead of a no-window zombie. No silent success exit. If main thread cannot present a Compose UI once shutdown fails, a narrow platform-owned dialog/status path is permissible subject to Project review.
6. Add focused JVM unit tests for close ordering, exactly-once successful termination handoff, error bypass, ownership lock retention on failed drain, and platform disposal. Never call real process exit inside the JUnit runner.

## 5. Non-negotiable real packaged Windows regression

Run a newly built exact-SHA package with **isolated disposable data roots**, after bounded H: disk-space preflight. Codex automated evidence and USER real-Windows acceptance are separate:

- >=5 fresh launch→WM_CLOSE/titlebar Close cycles, each identifying actual launcher + jvm.dll/JLI-loaded child PID by executable path/start time. Wait a bounded after-close interval and assert **neither process remains**. Do not mistake MainWindowHandle=0 or zero CPU for PASS.
- At least one reopened persisted synthetic Character/chat/message/draft/media fixture: same root, no data loss, exact ownership reacquisition, no duplicate messages and clean process exit on second run.
- Simulated in-flight chat/NovelAI tasks and backup draining; cancellation/no false success, no silent data lock leak. Test timeout/error path with visible explicit diagnosis; never cover it by force exit.
- Auxiliary Compose/AWT/native dialog exit as applicable; actual packaged jpackage launcher plus JVM both absent, neither hangs until next login.
- Gate report: executable/JAR hashes, commit SHA, launch/exit IDs/timestamps/status, bounded test timeout and sanitized phase trace. Existing lifecycle tests remain green. Run affected JDK17 Desktop/Shared/Android tests/compiles appropriate to changed modules; diff-check; Project independently reviews exact GitHub production diff and Windows evidence.

An isolated test may manage only **processes it itself launched** under a clearly disposable root after capturing failure diagnostics; it may NEVER terminate original user P7 lingering PIDs without distinct explicit user approval and data-root protection.

## 6. Release gating and old instance handling

Treat any reproduced closed-window-but-running JVM/launcher in a Public Preview candidate as a **release blocker** for Desktop shutdown and data safety, regardless of P11 full-app .cbbackup implementation. Existing D-037/D-038 Preview Data-Safety Gate, P17 feedback hardening and P18 full parity stay separate.

Existing historical P7 B2/WIN resident processes can be retired under a **separate user-approved, PID+creation-time exact process handling** after inspecting active storage-root ownership and preserving needed user state; this task does NOT approve ending them. A clean fix in a new package cannot retroactively terminate old running processes.

**CURRENT: FIX SPECIFICATION RECORDED / CODING NOT YET EXECUTED / NO FORCED EXIT, PROCESS TERMINATION OR DATA DELETION AUTHORIZED HERE.**
