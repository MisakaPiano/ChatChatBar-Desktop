# G0R actual test execution

Windows / Temurin JDK17.0.20.1+1 / 2026-10-23 JST. Control `e0923fc625052c1454663d429d1520b794c022bf`. Gradle ran from `app/`, offline, one worker, no configuration cache, single-use daemon, `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`. No GUI, Android, provider, installer or real data execution belongs to this task.

| Run | Actual result | Evidence |
|---|---|---|
| Initial harness invocation | Tool-reported exit1 **before Gradle**: evidence script traversed one parent too far and attempted `H:\app` | Original erroneous script and `raw/path-error.txt`. This is a harness error, not a product RED or JUnit run. |
| Corrected initial probe | 1 suite / 12 tests / 0 failures / 0 errors / 0 skips, exit0; BUILD SUCCESSFUL 2m34s | `raw/run-probe.ps1`, `probe-run.log`, `probe-exit.txt`, `initial/xml/`, initial source snapshot and records |
| Final source + selected regressions | **10 suites / 88 tests / 0 failures / 0 errors / 0 skips, exit0**; BUILD SUCCESSFUL 3m49s | `raw/run-final.ps1`, `final-run.log`, `final-exit.txt`, `final/xml/`, `final/results.json`, final source snapshot and records |

Final source adds guaranteed release/cancel/join in the cancellation test's failure paths; final twelve probes were re-executed after that test-only improvement. No production code was changed to make observations pass. Initial12 are not added to final88; old G0's158 and old S1 results are not added or relabelled.

## Exact commands

The executable scripts retain full environment and working directory setup. Commands were:

```powershell
.\gradlew.bat :desktopApp:test --tests '*P11S2OwnedMediaSafetyProbeTest' --offline --no-configuration-cache --console=plain --no-daemon --max-workers=1

.\gradlew.bat :desktopApp:test --tests '*P11S2OwnedMediaSafetyProbeTest' --tests '*DesktopCharacterResourceStoreTest' --tests '*DesktopPhase7R2Test' --tests '*DesktopFullBackupMappingTest' --tests '*DesktopFullBackupArchiveTest' --tests '*DesktopFullBackupServiceTest' --tests '*DesktopCoordinatedSnapshotServiceTest' --tests '*DesktopBackupReadOnlyInspectorTest' --tests '*DesktopReadOnlyBackupRedTest' --tests '*MomentArchiveReadOnlyValidatorTest' --offline --no-configuration-cache --console=plain --no-daemon --max-workers=1
```

`LASTEXITCODE` was recorded immediately after each Gradle invocation. Stdout and stderr are preserved together. Selected existing suites were inspected for platform-specific early returns/symlink skips; no such executed coverage is claimed. The moment status literal `SKIPPED` in the validator matrix is payload test data, not a skipped JUnit case.

## Final JUnit breakdown

| Suite | Tests |
|---|---:|
| P11S2OwnedMediaSafetyProbeTest | 12 |
| DesktopCharacterResourceStoreTest | 5 |
| DesktopPhase7R2Test | 5 |
| DesktopFullBackupMappingTest | 2 |
| DesktopFullBackupArchiveTest | 12 |
| DesktopFullBackupServiceTest | 10 |
| DesktopCoordinatedSnapshotServiceTest | 7 |
| DesktopBackupReadOnlyInspectorTest | 18 |
| DesktopReadOnlyBackupRedTest | 1 |
| MomentArchiveReadOnlyValidatorTest | 16 |
| **Total, same final invocation** | **88** |

The preexisting test called `DesktopReadOnlyBackupRedTest` now asserts the accepted read-only/restore-blocking separation; its passing name does not mean a new failing RED occurred. **Functional RED=NOT_APPLICABLE** for this observation task. Unsafe current outcomes are asserted as observations and documented as `OBSERVED_UNSAFE_CURRENT_BEHAVIOR`, not converted into permanent failing CI gates or falsely labelled production fixes. All prior historical RED remains untouched.

## Fixtures, cleanup and exact identity

- Each new probe acquires/releases its own root owner and coordinator. Complete stepwise inventories are in `raw/final/probe-records/`, with original six-byte fixture, file lengths and SHA-256. Input media digest is `0ace3c180294ea88af3dcb1e1f75d125296ec98cb07f0501ce22dc87cf9a5ccf`. Original and isolated copy identities are proven distinct before unlink with `Files.isSameFile=false`.
- Two real v1 archives exported by the current service are retained as `registered.fixture.cbbackup` and `hidden.fixture.cbbackup`. Authenticated inspection proves normal path inclusion and hidden path omission. Export randomness/timestamps need not reproduce identical future hashes; the preserved exact bytes identify this execution.
- Before deleting only its created temporary parent, each fixture records released ownership, `CLOSED`, successful lock reacquisition and final inventory. Every cleanup receipt reports `OWNED_TEMP_REMOVED=true`. While native lock is held, the lock file is explicitly length-only; its released state is hashed. No directory outside the explicit fresh temporary parent is recursively removed.
- `raw/production-tree-identities.json` proves unchanged Desktop, SharedCore and Android `src/main` trees and empty production working diffs. `raw/production-source-identities.json`, `official-source-identities.json` and `test-source-identities.json` retain exact source Git blobs, raw SHA and lengths. Source snapshots for both runs bind their XML/receipts to the actual tested test code.
- `raw/evidence-sha256.json` covers all other raw evidence without including itself. Raw files have `-text` attributes to preserve their original bytes in Git.

## Explicit limitations

Real persistent pending-deletion queued→processed, duplicate enqueue, interruption/crash, cancellation and resumed cleanup: **NOT_TESTED / NEEDS_ADAPTER**. See the actual owner/dependency chain in SOURCE_AUDIT; no replica was substituted. The repeated/cancelled/partially failed **Desktop cleanup** probes do not close these queue requirements.

No symbolic-link/reparse/hardlink attack, old binary, physical power failure, Android, GUI, actual image decoding, production seal/restore or real Provider was tested. No new persistent domain, quota/retention policy, C1/C2, baseline promotion or Preview acceptance follows from88 GREEN. The historical G0, full root migration, crash journal and broader158-test selection were not repeated because their production sources are unchanged.
