# P9-S2B-G1B-A production read-only startup ingress — candidate evidence

**G1B-A CODEX ENGINEERING CANDIDATE / PROJECT REVIEW PENDING**

Source: `a8990b34073647169e7e1a62b2d0e969323cddf6`; direct parent: `90182962fd1a37954c193c4cd7d5215123f0d126`.
Branch: `feature/phase8-fish-audio`. This separate evidence commit does not confer Project acceptance.
Validated upstream remains ChatBar 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`.
No CURRENT, parity, baseline or previous acceptance/evidence files changed.

## Scope and startup sequence

Seven files, +558/-13: six Kotlin files and required compact Feature Map maintenance.

| File | + / - |
| --- | --- |
| `.agents/skills/chatbar-feature-map/SKILL.md` | 1 / 1 |
| `DesktopDataDirectory.kt` | 8 / 1 |
| `DesktopProcessLifecycle.kt` | 6 / 1 |
| `DesktopStartupBlockedPresenter.kt` (new) | 79 / 0 |
| `DesktopStartupGate.kt` (new) | 125 / 0 |
| `Main.kt` | 10 / 10 |
| `DesktopStartupGateTest.kt` (new) | 329 / 0 |

Main now invokes the same injectable ingress exercised by the tests. Its default gate uses real read-only root selection, existing ownership acquisition and G0, before dialog owner, pickers or AppContainer construction. Ordinary roots require NO_DOMAIN. Portable's provisional identity is acquisition-only: first G0 -> owned activation/probe/cleanup -> a fresh second G0 under the same owner -> original business startup. No background business service is started between final proof and the business callback. CLI/Portable/bootstrap/default priority and no-fallback authority remain unchanged.

NO_DOMAIN means **only absence of the candidate transaction domain in that metadata observation**. It is not READY/COMMITTED, whole-root safety, legacy-vector safety or permission to skip existing strict storage rules. G0 algorithm, ownership authority, AppContainer, Repository and sharedCore were not modified. No journal schema, payload parser, transaction domain, writer or recovery was introduced.

The standalone presenter uses fixed bilingual text and enum-derived diagnostic codes, with Close and Copy diagnostic code. It does not read theme, settings, repositories or secrets. Probe retention is explicitly warned about. Presentation failure logs only a fixed code and still refuses admission. The internal refusal becomes STARTUP_BLOCKED only after structured preflight/probe completion and normal ownership release; the process lifecycle requests exit code 2. A lock-close failure remains unsafe rather than masquerading as a safe refusal. Existing AlreadyInUse behavior and genuine writer drain/retention are retained.

## Actual command and compilation

Windows Temurin `17.0.20.1+1` (existing JDK17), Gradle 9.1.0, unchanged `org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8`.
Each attempt ran this single invocation from `app`, via a PowerShell string argument array:

```powershell
$gradleArgs = @(
  '--offline','--no-daemon','--no-parallel','--max-workers=1','--priority=low',
  '--console=plain','--no-configuration-cache','-Pkotlin.compiler.execution.strategy=in-process',
  ':desktopApp:test',
  '--tests','com.example.chatbar.desktop.DesktopStartupGateTest',
  '--tests','com.example.chatbar.desktop.DesktopApplicationLifecycleTest',
  '--tests','com.example.chatbar.desktop.DesktopProcessLifecycleTest',
  '--tests','com.example.chatbar.desktop.DesktopDataDirectoryTest',
  '--tests','com.example.chatbar.desktop.DesktopPortableRootTest',
  '--tests','com.example.chatbar.desktop.DesktopDataRootOwnershipTest',
  '--tests','com.example.chatbar.desktop.DesktopIndexStartupPreflightTest'
)
& .\gradlew.bat @gradleArgs
```

No clean, rerun-tasks, fail-fast, parallel Gradle invocation, heap change or software install.
Exact arguments, UTC timestamps, exit codes and copied fresh XML identities are in each attempt's invocation/result JSON. The JDK version command's stderr appears as PowerShell NativeCommandError formatting in its raw log; its actual exit was 0, not a JDK failure.

- Attempt 01: exit **1**, 550.0228785 seconds. Desktop main and test compilation actually executed successfully; test task actually executed and **FAILED** (136 tests, 132 reported PASS, 3 failures, 0 errors, 1 skipped).
- Attempt 02: exit **0**, 68.4944998 seconds. Desktop main UP-TO-DATE against byte-identical production inputs from attempt 01; test compilation and test task actually executed. **7 suites / 136 tests: 135 reported PASS, 0 failures, 0 errors, 1 skipped**.
- SharedCore main/test-fixture tasks were UP-TO-DATE. Android and SharedCore full regression NOT RUN. No claim of fresh SharedCore compilation.
- Compilation warnings: new private data-class constructor/generated copy visibility warning (future Kotlin 2.5 compatibility); existing painterResource deprecation. Neither was a compile error. No code was changed after final validation.

### Final raw JUnit results

| Suite | Tests | Reported PASS | Failures | Errors | Skipped | Seconds |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| DesktopApplicationLifecycleTest | 39 | 39 | 0 | 0 | 0 | 0.926 |
| DesktopDataDirectoryTest | 13 | 13 | 0 | 0 | 0 | 0.449 |
| DesktopDataRootOwnershipTest | 14 | 14 | 0 | 0 | 0 | 0.767 |
| DesktopIndexStartupPreflightTest | 19 | 18 | 0 | 0 | 1 | 2.026 |
| DesktopPortableRootTest | 16 | 16 | 0 | 0 | 0 | 0.538 |
| DesktopProcessLifecycleTest | 15 | 15 | 0 | 0 | 0 | 0.457 |
| DesktopStartupGateTest | 20 | 20 | 0 | 0 | 0 | 3.527 |

All fourteen XML files (seven RED-attempt and seven final) were copied before another Gradle run could overwrite them. The 20 new startup tests cover actual Main ingress, container initialization order, all G0 refusals, domain shapes without parsing, Portable two-pass order/late domain/identity drift/retained probe, default-root infrastructure, single-instance refusal, malformed authority/no fallback, safe nonzero exit, sanitized view actions, presenter failure, structured cancellation, original business initialization/drain and retry without cached admission.

The existing G0 JUnit symlink case is **1 SKIPPED**. Four additional `SYMLINK_FIXTURE_UNAVAILABLE` early returns (two Ownership and two Portable cases) remain **NOT TESTED**, not extra JUnit skips or successful coverage. G0 final raw events: 40 records, 38 unchanged snapshots and 2 explicit platform limits. Those events concern G0 fixtures, not whole Desktop startup zero-write. New assertions separately observe G0 bytes/lock length, ownership-created root/lock infrastructure, Portable probe effects and business initialization writes.

## Preserved first RED and bounded fixture correction

Attempt 01's new snapshot helper tried reading the Windows ownership-locked file through a second data channel. Three success-path fixtures also omitted the headless presenter override, so their caught preflight exception opened synthetic startup-blocked prompts and waited for close. This was a **test-fixture failure**, not a successful GUI gate.

A single read-only jcmd thread sample of the verified own worker (PID 6836, parent 56644, GradleWorkerMain) confirmed the wait. Only that worker's exact fixed-title prompt windows received normal WM_CLOSE; three close requests are recorded. No process was killed, no user windows were inspected/controlled, and no actual packaged CCB launcher was started. The run then finished RED and all original logs/XML/source identities were preserved.

Only `DesktopStartupGateTest.kt` changed for attempt 02: ownership infrastructure is observed by its fixed file length instead of attempting a prohibited content stream, and all test calls explicitly inject headless presenters. Real file bytes remain hashed for other files; behavioral assertions were retained. Production source/skill hashes are identical between attempts. The first full patch and both source manifests preserve the exact RED and final versions. Unexpected real presentation or a platform/manual GUI test was not used as an acceptance claim.

## Resource observations (sampled, not absolute peaks)

| Observation | Attempt 01 | Attempt 02 |
| --- | ---: | ---: |
| Samples | 55 | 9 |
| Available GiB first | 0.358 | 1.810 |
| Available GiB minimum | 0.096 | 0.610 |
| Available GiB final sample | 0.705 | 1.771 |
| Commit headroom GiB minimum | 5.971 | 6.813 |
| Pages/sec maximum | 29541 | 4279 |
| Java processes maximum | 3 | 3 |

64 valid samples total. Memory pressure/paging was real; no OOM occurred and no Gradle was interrupted for low memory. Initial prebuild read also observed 0.551 GiB available / 8.357 GiB commit headroom / no Java. No Chrome/NAI/user process was closed, reprioritized or otherwise operated. No foreground responsiveness PASS is inferred.

The recorded daemon and test worker priority was BelowNormal; wrapper was Normal. Kotlin in-process was passed as one intact property; no independent Kotlin daemon appeared in samples. Maximum sampled Java aggregate private bytes / aggregate working set: attempt01 2256834560 / 1406255104; attempt02 1663877120 / 1124405248. Working-set sums may double-count shared pages and are not system physical usage. Absolute process peaks are UNKNOWN. Attempt01 monitor ended with one exiting Java; Java was confirmed absent before attempt02. Attempt02 final sample has zero Java processes.

## Identity, static checks and evidence handling

`source-manifest.json` lists all seven source/skill paths, raw worktree bytes/SHA-256, normalized LF bytes/SHA-256 and committed Git blobs. All 7/7 match the tested final inputs; staging and commit did not change file bytes. Final complete working-tree patch SHA-256: `4b75631eaef1b64dba5efec5ab98acc4b1ad1d936b18a3ad00c40c9b8750ac24`. Canonical staged patch SHA-256: `71f3996fe714b90c6deae5bb412538e0d776db80fe7122deffaa3a80e61941da`. Both represent the same final files; the full patch includes new files, and reverse application was checked. Source staged diff-check PASS, scope exactly seven approved implementation/test/required-skill paths, no unstaged source residue. Protected local/remote refs match the recorded snapshot (feature deliberately advances).

`evidence-manifest.json` covers every other file in this new directory with byte length and SHA-256; it excludes itself to avoid self-reference. Directory .gitattributes preserves raw artifacts without line-ending or historical-whitespace rewriting. The raw manifests/logs/scripts/XML were copied byte-for-byte, not regenerated. Summaries are separately named derived data. Pattern scanning plus content review found no production credentials, private keys/certificates, real user records or unredacted Provider requests. Thread dump is only the synthetic test worker; resource logs contain PID/category/memory, no full Java command lines. All fixtures are isolated synthetic roots; no real user roots or paid APIs were accessed.

## Limits and non-claims

- Windows packaged launcher/EXE, manual UI acceptance, G1B-B smoke, packaging, Android and full regression: **NOT RUN**. The accidental test prompts described above do not change that boundary.
- Real symlink paths, actual locked root replacement, complete Windows TOCTOU, out-of-domain hardlink absence, comprehensive ACL coverage and power-loss durability remain unproven.
- Structured cancellable waits were tested; genuinely blocking filesystem I/O has no physical absolute timeout guarantee.
- Real system clipboard, production prompt close/copy behavior and actual packaged exit code still require G1B-B/manual verification. Unit tests exercise the same fixed view actions through injected callbacks and the process exit hook.
- Production writer/recovery/schema, G2, automatic embedding, RAG prompt injection, migration and repair/cleanup remain **NOT STARTED / NOT AUTHORIZED** by this slice.
- Historical G0/G1A evidence, RED, platform limits and acceptance records remain intact. No Project acceptance, Feature Parity or baseline is promoted.

**G1B-A CODEX ENGINEERING CANDIDATE / PROJECT REVIEW PENDING**
