# 100 — Residual Windows JVM: Independent Project Review of P99 Read-Only Evidence

**PROJECT SOURCE REVIEW: EVIDENCE ACCEPTED / TWO LIVING OLD-BUILD JVMs CONFIRMED BY CODEX LOCAL EVIDENCE / ROOT CAUSE UNRESOLVED / NO SOURCE REGRESSION DECLARED**

Date: 2026-10-12 JST. Source of Windows observations: user-supplied Codex read-only execution result ~07:37:11–07:38:13 JST, following Project diagnosis spec `99_DESKTOP_RESIDUAL_PROCESS_DIAGNOSTIC.md`. Project independently verified current GitHub `Main.kt`, `DesktopAppContainer`, `DesktopTaskRuntime` and the existing P7 frozen source comparison. **Project did not directly observe H: OS process state**.

## 1. Scope and evidence classification

| PID | Historical package | Role and identity | Creation time JST | Working set / private committed MiB | Threads / handles |
| --- | --- | --- | --- | --- | --- |
| 34912 | phase7-b2-r3-distribution | launcher without JVM, parent of 4492 | 2026-10-11 08:33:43.532 | 6.29 / 1.27 | 2 / 110 |
| 4492 | phase7-b2-r3-distribution | child JVM with `jvm.dll` and `jli.dll` | 2026-10-11 08:33:43.563 | 35.46 / 1074.73 | 78 / 1122 |
| 20904 | phase7-win-manual-distribution | launcher without JVM, parent of 27568 | 2026-10-11 22:26:53.880 | 3.60 / 1.27 | 2 / 110 |
| 27568 | phase7-win-manual-distribution | child JVM with `jvm.dll` and `jli.dll` | 2026-10-11 22:26:54.373 | 38.89 / 839.14 | 85 / 1161 |

- Identity was checked by two independent Windows read-only process mechanisms, creation times, paths, and parent/child relations. Both outer original parents were no longer present. **No PID reuse indication on that sample**. Reverify PID+creation time before any later action.
- Four `MainWindowHandle=0` values do **not** prove no hidden HWND/tray/AWT windows, only absence of a discoverable main window.
- Four-second CPU-time delta **0 seconds on all four**, suggesting idle/waiting at observation but **does not identify what they are waiting on**; no continuous CPU-burning loop observed.
- JVM **private bytes total 1,913.87 MiB (~1.87 GiB)**, JVM working set **74.35 MiB**. Virtual committed private bytes and resident physical RAM are distinct; not evidence of ~1.87 GiB active physical RAM saturation. The resident processes do not explain ~9 GiB of build/distribution files.
- One attempted `jcmd <JVM_PID> Thread.print -l` per confirmed JVM failed **Access denied** at `VirtualMachineImpl.openProcess` with exit code 1. **No Java thread dump was captured.** No elevated retry, debugger, process kill or data files touched.

## 2. Independent exact GitHub source checks

- `Main.kt` is identical across accepted/frozen P7-B2 `dde3b8f71286d8ed2aeffaf3e3a08c2a21aca75f`, P7-WIN `50a7caf`, and final P7 `44b50a4f1f613ee05dce33bbcd3e720adde9bc91`: blob SHA `9c92f80827eb355d925b396136bc495305d2a0da`.
- `application(exitProcessOnExit=false)`, window-close `requestWindowClose()` → `exitApplication()`, `runDesktopApplicationLifecycle` `finally` → `runBlocking { appContainer.close() }`, then data-root ownership close unless specific writer-drain errors require retention. These are **source features, not proof of any particular waiting phase**.
- `DesktopAppContainer.close()` drains task/NovelAI/connection work, drafts/read position/editors, backup scheduler, data-operation coordinator and migration service. `DesktopTaskRuntime.closeAndDrain()` uses a bounded 10s cancellation/drain; automatic backup and data coordinator can wait for active operations. The named wait stage is **unresolved without stack or internal phase markers**.
- `WindowsCommonItemDialog` STA executor is explicitly a **daemon** and has `close`; merely seeing this code is not evidence it is the blocker.
- Existing earlier isolated Windows WM_CLOSE smoke/exit 0/0 and process-level tests are valid for their exact packages and conditions, not for these two persisted sessions. No claim that present final P7 source/regression broke is justified without exact runtime binary manifest and reproduction.

## 3. Project disposition and remaining work

**P99 READ-ONLY EVIDENCE: PROJECT REVIEW PASS (SCOPED).** Windows packaged-app process lifetime is a **CONFIRMED symptom** in two old distributions; exact cause is **OPEN/UNRESOLVED**. This is a new shutdown diagnosis owner / possible data-safety release hardening issue, **not a reopening of historical qualified P7 acceptance** and not automatic attribution to Compose, backup or task runtime.

No data root lock should be removed, no arbitrary `exitProcess(0)` or `exitProcessOnExit=true` patch applied: that might skip required ordered shutdown. Current process closure and thread attachment rights have not been proven safe. A process that has no main window can still own live persistence resources.

**Safe next read-only evidence, if needed**: OS thread wait-state/wait-reason summaries and sampled per-process IO counters (not just CPU); optional built-in Windows wait-chain analysis if accessible, without elevated access or installing tools. Stop if unavailable. Do not retry `jcmd` with privilege workarounds, force attach, Java agents or dump private heap. This can distinguish busy IO from idle waits but still may not identify the exact Java shutdown caller.

A **future scoped fix**, only once evidence supports it, would add bounded, redacted shutdown-phase telemetry and a process-level exact-package acceptance test of close → container drained → root lock released → launcher/JVM exited. Avoid adding blanket JVM-kill behavior. If live stacks remain unavailable, controlled reproduction on an isolated, proven disposable root and instrumented build is a separate explicit Project task after disk-space preflight; **no build or source modification authorized now**.

## 4. Interaction with approved local cleanup / full 9-GiB audit

- The user previously approved permanent cleanup of exactly A+B 13 paths in `96`/`98`, **but its safety gate is still blocked**, because two historical packaged JVMs are currently unconfirmed safe to retire. No deletion of any path; last reported H: `616,779,776` bytes (588.21 MiB) free; recovered **0**.
- The user separately requested a **whole-project >9 GiB storage breakdown** (top folders/files, historical exact-SHA distribution duplicates, manifests). That request was **not delivered by this P99 process-only Codex report**. It can proceed independently as a read-only bounded metadata inventory, without process closure or access to sensitive user files, subject to CPU/IO budget and exclusions.
- P8-S0C Project review PASS, narrow S1 JVM-neutral pure core approved **in principle**. No source edits, Gradle, tests, upsync, or P8 S1 work occurred here; desktop/master/parked sync remain untouched.

**CURRENT: P99 SYMPTOM CONFIRMED / CAUSE UNRESOLVED / CLEANUP PAUSED / FULL DISK AUDIT PENDING / P8 S1 NOT STARTED.**
