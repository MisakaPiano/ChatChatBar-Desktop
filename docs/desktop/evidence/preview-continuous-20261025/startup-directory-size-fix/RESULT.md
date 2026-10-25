# Native startup false refusal: unspecified directory length

The user actually launched the 1008 manual entrypoint after its PowerShell correction and received `G0_CHANGED_DURING_CHECK`. This was not a successful visual check. The earlier independently recorded 1008 native launch remains historical evidence of that execution, not a guarantee that a later cold start works.

## Independent diagnosis

An independent native launch of the same immutable 1008 image/source `2f75a4d3d4e5557d7bd513f2e9368a4ed5e8d1f7` reproduced a blocked startup. The original probe did not observe the normal main window and returned exit 1. Its one-shot early close missed the delayed blocker window, retaining two owned processes; a subsequent exact PID/parent/image check sent normal close and confirmed exit, without forced termination. The original failed probe result is retained; this follow-up does not turn it into a successful native launch.

A separate headless companion observed **the same explicit synthetic 1008 data root** using frozen 1008 production JARs. It read metadata only under existing root ownership; no repositories, credentials, content repair or recovery were invoked. On round 0, `entities` retained the same native identity, creation time, last-modified time and directory type, but `BasicFileAttributes.size()` changed from **0 to 4096**. The original inspector returned `CHANGED_DURING_CHECK`; the next seven observations returned `NO_DOMAIN`. The raw metadata trace is included. Cache warming/retrying is not the fix.

The installed JDK 17.0.20 source `java.base/java/nio/file/attribute/BasicFileAttributes.java` documents size of non-regular files as implementation specific and unspecified. Windows directory enumeration and direct attribute queries cannot supply a stable directory-content-length invariant. The actual metadata trace establishes this discrepancy locally; it does not imply a missing user file or archive corruption.

The initial diagnostic Gradle invocation failed before execution because the private init duplicated the existing task registration. A source-only init corrected this, compile exit 0. A separate direct compiler attempt failed for missing compiler-only kotlin-reflect; its failure remains. The successful companion JAR instead contains only the already compiled trace classes and runs against immutable packaged production dependencies. None of these compilation attempts are JUnit passes.

## Minimal production correction

`DesktopIndexStartupPreflight` normalizes directory length in its internal stamp and compares byte length only for regular files. Native file ID, creation time, type, relevant directory/file last-modified timestamps, ownership, reparse/path checks, enumeration/name budgets, candidate-domain presence and file lengths remain checked. Nothing is cleared; no retry, unconditional startup bypass or Prompt/schema/credential/restore change is introduced. The obsolete “no production caller” comment is corrected because StartupGate already invokes this preflight.

Two independent synthetic JUnit methods cover unchanged directory allocation sizes (including absent and present candidate-domain layouts) and continued refusal of regular-file length drift.

| Actual execution | Suites | Tests | PASS | FAIL | ERROR | SKIP | Exit |
|---|---:|---:|---:|---:|---:|---:|---:|
| Before production correction | 1 | 21 | 19 | 1 | 0 | 1 | 1 |
| Final affected regression | 3 | 83 | 81 | 0 | 0 | 2 | 0 |

The first failure is the newly added directory-size counterexample. Final suites are StartupPreflight, StartupGate and DocumentIndexTransactionInspector. Two existing real-symlink cases require an executable fixture unavailable in this Windows context and remain **NOT TESTED**; they are not counted as PASS. No historical suite counts are accumulated.

`SOURCE_IDENTITY.json` binds the exact pre-execution changed Kotlin snapshots; `RAW_INDEX.json` binds the selected raw sources, commands, exits, logs and XML. New packaged/native cold-start validation follows this source checkpoint; this report alone does not certify that package or human visual acceptance. The separately running large-root relocation uses its original frozen 1007 source and is not silently relabeled as this corrected source. MSI administrator repair, installer upgrade/rollback, Android physical checks and full Preview remain separate.
