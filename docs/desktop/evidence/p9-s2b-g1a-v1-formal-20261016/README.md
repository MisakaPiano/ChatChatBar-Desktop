# G1A-V1 formal bounded validation candidate

**CODEX VALIDATION CANDIDATE / PROJECT ENGINEERING REVIEW PENDING**

## Commit and source identity

- Validation parent: `d047d48c529e28c2c0979ada80073551bcabbd0a`.
- Source candidate: `806785a11446457fdc6bf7e9a6a313e391a9f32b`; direct parent is the validation parent.
- Source commit: exactly seven Kotlin files, +1057/-85. No source changes were made during this validation run.
- Validated full R2 patch SHA-256: `e18b10b53d577e21ca97b656147df668849f9ae29f5b514d5d42178e19cef4d8`.
- Seven raw Windows source SHA-256 values match the Project-reviewed R2 manifest before/after execution and after source commit; seven normalized Git blobs match the candidate. See source-commit-receipt.json and source-manifest-validated.json.
- This evidence-only commit directly follows the source candidate; it does not change source, current-state documents or acceptance claims.
- All 30 original G1A/E1/R1/R2 evidence files remain byte-identical. Historical RED, the original interrupted build, duration correction and earlier V1 RESOURCE GATE DEFERRED remain historical; none was rewritten as PASS.

## Exact single Gradle invocation

Working directory: `H:\ChatChatBar-Desktop\app`.
Temurin JDK `17.0.20.1+1`; Gradle wrapper 9.1.0; tracked JVM heap remains `-Xmx2048m -Dfile.encoding=UTF-8`.

```powershell
$gradleArgs = @(
  '--offline', '--no-daemon', '--no-parallel', '--max-workers=1',
  '--priority=low', '--console=plain', '--no-configuration-cache',
  '-Pkotlin.compiler.execution.strategy=in-process', ':desktopApp:test',
  '--tests', 'com.example.chatbar.desktop.DesktopApplicationLifecycleTest',
  '--tests', 'com.example.chatbar.desktop.DesktopProcessLifecycleTest',
  '--tests', 'com.example.chatbar.desktop.DesktopDataDirectoryTest',
  '--tests', 'com.example.chatbar.desktop.DesktopPortableRootTest',
  '--tests', 'com.example.chatbar.desktop.DesktopDataRootOwnershipTest',
  '--tests', 'com.example.chatbar.desktop.DesktopIndexStartupPreflightTest'
)
& .\gradlew.bat @gradleArgs
```

Exit code **0**. Gradle self-reported **10m 51s**; command wall time **663.1015345 seconds**, using UTC DateTimeOffset subtraction. The difference includes launcher/setup overhead, not another build.
One invocation, no retries, no clean, no rerun-tasks. Desktop compileKotlin, compileTestKotlin and test all **EXECUTED PASS**. Desktop jar also executed. SharedCore prerequisites were UP-TO-DATE, not new SharedCore/Android validation. Full task statuses are preserved in build-task-results.json.

The log retains Kotlin warnings and `Detected multiple Kotlin daemon sessions at`. This warning was not hidden or treated as a new compiler process: sampled process categories recorded only wrapper/single-use Gradle daemon/test worker, no separate Kotlin compiler daemon. Up to three Java processes belong to the one Gradle invocation, not parallel builds.

## Fresh original JUnit results

| Suite | Tests | JUnit reported pass | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| DesktopApplicationLifecycleTest | 39 | 39 | 0 | 0 | 0 |
| DesktopProcessLifecycleTest | 15 | 15 | 0 | 0 | 0 |
| DesktopDataDirectoryTest | 13 | 13 | 0 | 0 | 0 |
| DesktopPortableRootTest | 16 | 16 | 0 | 0 | 0 |
| DesktopDataRootOwnershipTest | 14 | 14 | 0 | 0 | 0 |
| DesktopIndexStartupPreflightTest | 19 | 18 | 0 | 0 | 1 |
| **Total** | **116** | **115** | **0** | **0** | **1** |

These are this run's actual six XML files, copied immediately on completion and verified against their originals. No historical PASS or UP-TO-DATE test task was reused. JUnit suite durations total 71.755 seconds, not overall build wall time.

**Platform limits remain explicit:** one real-symlink G0 test is SKIPPED. Four further stdout `SYMLINK_FIXTURE_UNAVAILABLE` events are early-return fixture branches (two ownership fixtures and two portable fixtures); those paths are **NOT TESTED**, even though their enclosing JUnit methods report success. They are not four extra JUnit skipped tests and are not successful symlink safety assertions. Active-root real replacement, complete Windows TOCTOU, domain-external hardlink absence, full ACL coverage and power-loss durability are not established.

Zero-write events: **40 records**, **38 unchanged snapshots** plus **2 explicit platform-limit records**. Real Windows junction and hardlink cases ran; their raw events remain in zero-write-events.jsonl. G0 is still standalone, without a production startup caller; NO_DOMAIN is not general data-root or legacy vector safety/readiness.

## What the focused gate exercised

Portable selection zero-write and explicit activation; initialization failure followed by real container/task drain; incomplete-close ownership retention including suppression-disabled exception carrier; primary identity/ordered suppression, cancellation and same-object exceptions; fixture-owned bounded teardown; withRoot rejection of unexpected retention and declared-incomplete drain; old normal lifecycle behavior. Results establish only these bounded test scenarios, not physical absolute blocking-I/O deadlines or a Windows EXE lifecycle acceptance.

## Resource record, no hard-memory gate

The user removed the 3 GiB Available / 8 GiB Commit hard gate and authorized continuation through paging/slowness. No V1D attribution, foreground-program modification, memory tuning, Ctrl+C or process kill was performed.

64 valid loop samples plus a post-exit snapshot: initial available RAM about 1.399 GiB, sampled minimum **0.069 GiB**, sampled minimum Commit headroom **4.480 GiB**, sampled maximum **21,048 pages/sec**, sampled maximum Java count **3**. After normal completion, the separate snapshot reports **zero Java processes**, available RAM about **2.693 GiB**, Commit headroom about **8.266 GiB**. No OOM or build failure observed. All extrema are sampled, not absolute peaks; summed Working Sets can double count shared pages. Chrome/NovelAI responsiveness was not tested or asserted.

Original resources.jsonl, resource-after-exit.json, resource-summary.json and resource-curve.csv are preserved. The historical runner/monitor scripts are evidence only: **do not execute them from this committed directory**, because their original output root was PSScriptRoot under the ignored build gate.

## Boundaries and review

Full regression, Android compilation/tests, production GUI/EXE, packaging, real user data and paid Provider/Embedding API: **NOT RUN**. G1B, startup G0 integration, recovery and index writer: **NOT STARTED**. Formal upstream 1.4.1 and protected refs unchanged. No Prompt/schema changes or upstream sync.

Sensitive content reviewed before publication: synthetic fixtures, source hashes, build logs and sanitized resource counters only; no full process command lines, browser contents, real user records or secrets. Raw files/XML have local -text attributes to preserve exact bytes; whitespace exceptions are scoped to raw evidence, while the source staged diff independently passed diff-check. Skill relevance reviewed; this source freeze does not make existing skill maps stale, so no extra skill change was needed.

**This is not Project acceptance.** Project must independently inspect the source candidate, original XML, hashes and limitations. Earlier source-manifest-before.json is the immutable R2 snapshot (including its then-BLOCKED label), not a revised current acceptance document.
