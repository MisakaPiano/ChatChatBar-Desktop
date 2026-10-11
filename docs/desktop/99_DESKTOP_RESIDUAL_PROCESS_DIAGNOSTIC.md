# 99 — Windows Closed-Window Residual Process: Read-Only Diagnostic Gate

**2026-10-12 JST — USER AUTHORIZED DIAGNOSIS / SUSPECTED SHUTDOWN-LIFETIME DEFECT / ROOT CAUSE UNKNOWN / NO PROCESS TERMINATION / NO FILE CLEANUP / NO CODE EDIT**

This is a targeted Project diagnostic handoff after Codex correctly STOPPED the user-approved exact 13-directory cache cleanup twice because **two historical packaged CCB executions still had running processes after their windows were reportedly closed**. It does **not** reopen accepted P7 scope or prove that latest production source has the same defect. Keep existing exact-path cleanup authorization in `96`/`98` on hold until all safety conditions are independently satisfied.

## 1. Exact user-observed evidence

| Historical distribution | PIDs reported still present after window close |
| --- | --- |
| `H:\ChatChatBar-Desktop\app\desktopApp\build\phase7-b2-r3-distribution` | `34912`, `4492` |
| `H:\ChatChatBar-Desktop\app\desktopApp\build\phase7-win-manual-distribution` | `20904`, `27568` |

Codex first STOPPED while these four processes ran; a second read-only check after the user closed Windows reported **the same four PIDs still present** (Windows process query plus Get-Process). No PID was terminated, no file was deleted, moved or quarantined, no Gradle/GUI/package/test invoked. H: available then `616,779,776` bytes (`588.21 MiB`). Last verified `feature/phase8-fish-audio @ 86c3b5eab3bed3ff1fbff16e666fc6d01e2be5ac` and clean worktree. PID reuse, exact subprocess identity, start times, main window handles, memory/CPU activity and JVM thread stacks were **not established** by those checks.

## 2. Project exact-source findings (not a root-cause finding)

- Project independently retrieved `app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/Main.kt` at frozen B2 `dde3b8f71286d8ed2aeffaf3e3a08c2a21aca75f`, WIN `50a7caf`, and final P7 `44b50a4f1f613ee05dce33bbcd3e720adde9bc91`. **Identical Main.kt blob SHA `9c92f80827eb355d925b396136bc495305d2a0da`** in all three. Thus the common exit wiring is not a later P8 regression; whether either old packaged binary matches its exact source must still be grounded in package SHA manifests, not guessed.
- `application(exitProcessOnExit = false)`; main window onClose delegates to `requestWindowClose()` then Compose `exitApplication()`. The false flag avoids unconditional JVM termination so lifecycle `finally` can drain resources. **Do not mechanically flip true or add `exitProcess()` without proving and preserving write/ownership shutdown.**
- `runDesktopApplicationLifecycle` finally calls `appContainer.close()` with `runBlocking`, and outer lifetime releases `DesktopDataRootOwnership` in finally after safe drain. A task/draft/reading-position drain timeout can intentionally retain ownership while writers might be active; do not treat its root lock file as stale merely because the window vanished.
- `DesktopAppContainer.close()` sequences connection-test, NovelAI and TaskRuntime drain; draft/reading-position/editor drains; automatic backup scheduler stop; data coordinator drain; migration service close. `DesktopTaskRuntime.closeAndDrain` has 10s bounded cancellation; `DesktopAutomaticBackupScheduler.close` waits for in-flight backups; data-operation coordinator may await active operations. These are **candidate waiting points only**.
- `WindowsCommonItemDialog` uses a daemon STA executor and has a close path; mere existence of this executor is not proof of process leak. AWT/Compose threads, pending native windows, or a packaged launcher/runtime distinction remain possibilities.
- Historical exact-SHA P7 isolated launch tests with `WM_CLOSE` / zero residual process in separate fixtures are **not** proof that these two specific current process instances exited. Conversely, these old resident processes do **not** establish a regression at the latest P7 source SHA without reproduction.

## 3. Codex task — READ-ONLY live-process diagnosis

**No code, commits, test/build, API call, new CCB window, file cleanup, forced/normal termination or handle closing.** Do not modify current `desktop`, `master`, `sync/*`, or current feature. Work from clean local repo `H:\ChatChatBar-Desktop`; fetch only if necessary to know exact Project docs, without running any source. Treat the four PID numbers as *candidates*, not authoritative identity until verified by **start time + canonical executable path + current parent relationship**.

A. **Identity / process tree.** Use bounded CIM and Get-Process read-only queries to collect PID, PPID, executable name/path, created-at time, main-window handle/title, thread count, handle count, working set and private bytes for exact four PIDs and their **immediate parent/children**. Redact command line and private paths; **do not print raw CommandLine, environment or secrets**. Check PID reuse, whether launcher and JVM are actually separate vs JVM hosted in launcher, whether any window/tray is truly active. A missing or repurposed PID is NOT a living CCB process.

B. **Resource activity.** Sample Get-Process CPU *cumulative time* at two points separated by ~3–5 seconds, optionally working set/handles. Report per PID CPU delta and trend; high memory alone does not prove CPU saturation or deadlock. Do not infer persistent CPU usage from one sample or confuse RAM retention with H: disk exhaustion.

C. **Safe JVM thread stacks when available.** Check installed JDK 17 `jcmd` accessibility without downloads or privilege escalation. Only after identity confirmation, attempt bounded `jcmd <actual_JVM_PID> Thread.print -l` on a JVM that accepts attach (if supported; executable `.exe` may host JVM). Prefer *one sample* per genuine JVM, second only if useful; do not run `VM.command_line`, heap/GC dumps, `jmap`, full memory/process dumps, debugger injection, `jcmd GC.run` or anything that changes persistent data. An attach may briefly pause the target; timeout/inaccessibility/denied permissions must be reported rather than worked around. Do not save or post raw thread dumps containing private text; summarize only thread names/state, daemon status if known, top relevant stack methods and clear wait owner, with sensitive details redacted.

D. **Match the shutdown phase.** Distinguish (i) Compose still inside UI/application loop, (ii) `runBlocking{appContainer.close()}` stuck at task/draft/backup/data-operation join, (iii) lifecycle ended but non-daemon threads keep JVM alive, (iv) native packaged launcher/child relationship issue, (v) actual app/tray remains open, (vi) PID reuse/inaccessible diagnosis. Only name root cause if stack/process evidence supports it. Explain possible data-root lock retention, but **do not acquire, delete or rewrite ownership locks**.

E. **No speculative fix.** If stacks fail or lack evidence, return classification **UNRESOLVED / NEXT MINIMAL EVIDENCE**, with specific methods and blockers. If verified safe-shutdown violation, propose a **narrow future Project code-review patch and process-level regression test** separately; no editing until Project explicitly scopes. Do not claim that all P7 accepted tests need rerun or that P8 S1 has already started.

## 4. Safety boundary and stop rules

- No `Stop-Process`, `taskkill`, process kill, closing handles, Windows reboot, user-triggered APP restart, forced Compose exit, root lock removal, data rewrite/migration, `git clean`, `gradlew clean`, code/Gradle modifications, or even the **previously user-authorized** 13-path deletion in this diagnostic turn.
- Do not access or upload user backups, API keys, prompts, audio, profile datasets or private credentials.
- If a process could still be writing, **retain it** until a separate data-safety plan. Reassess process identity before any later targeted exit/cleanup step; do not rely on old PID alone.
- All debugging reports should be compact and sanitized; optionally report read-only standard output in Codex response without creating repository files. GitHub Project cannot directly inspect the user's H: processes.

## 5. Expected handoff

Report: precise local branch/HEAD and clean status; per-PID verified identity/time/path family, parent/child facts, window-handle state, two CPU time samples/delta, RAM/private bytes/thread counts; attach availability/JVM-stacks if safe; **which shutdown phase is supported versus inferred**; whether the process still has open app windows; whether cleanup remains blocked; list specifically what further safe evidence or later source tests would be required.

**CURRENT: INCIDENT DIAGNOSTIC AUTHORIZED / NO CHANGES TO PRODUCTION OR USER DATA / 13-PATH CLEANUP EXECUTION REMAINS PAUSED / P8 S1 NOT STARTED.**
