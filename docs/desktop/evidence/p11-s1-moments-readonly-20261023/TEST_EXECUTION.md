# Actual JVM execution — 2026-10-23 JST

Final: **8 suites / 89 tests / 0 failures / 0 errors / 0 skipped / exit0**. S1 child exit73/alive=false. Final test source hashes were verified unchanged after the run.

Environment: Windows, Temurin OpenJDK17.0.20.1. Commands below run from repository `app/`; Gradle cache access used the permitted sandbox escalation. No redeploy, packaging, GUI, Android/ADB, installer or Provider calls.

## Baseline and intermediate runs

1. `raw/compile-attempt-1.log` / exit1: incomplete intermediate source caused unresolved new-validator references. This is a compilation failure, **not functional RED**.
2. `raw/red.log` / exit1 / `raw/red.xml`: one real JUnit failure. The baseline oracle asks S0 to certify a Moments graph; S0 explicitly returns resourceGraphVerified=false. The one-test source is preserved in `raw/red-test-source.kt.txt`. This establishes an absent S0 capability, not a claim that its accepted behavior was unsafe. The final same-named test preserves S0=false and additionally checks separate S1 classification.
3. `raw/green-attempt-1.log` / exit1: intermediate compile errors (test inferred internal return type, and test/source compilation during an incomplete link-report edit). No acceptance claim. Tests were corrected to runBlocking<Unit>, then run on fixed complete sources.
4. `raw/green-attempt-2.log` / exit1 / `raw/attempt-2-xml/`: 8 suites, 89 tests, one failure, zero skipped. The test fixture's singleton inventory type mistakenly counted app_settings.json rather than app_settings; S0 rejected the manifest before S1. Fixed the independent fixture count builder; production admission was not relaxed. All 79 legacy tests and the other 9 S1 tests passed in that run.

Baseline command:

```powershell
.\gradlew.bat :desktopApp:test --tests '*MomentArchiveReadOnlyValidatorTest' --no-configuration-cache --console=plain
```

Final and both intermediate full-run commands:

```powershell
.\gradlew.bat :desktopApp:test --tests '*MomentArchiveReadOnlyValidatorTest' --tests '*DesktopBackupReadOnlyInspectorTest' --tests '*DesktopReadOnlyBackupRedTest' --tests '*DesktopFullBackupArchiveTest' --tests '*DesktopFullBackupServiceTest' --tests '*DesktopFullBackupCommitJournalTest' --tests '*DesktopDataRootOwnershipTest' --tests '*DesktopStartupGateTest' --no-configuration-cache --console=plain
```

Each run redirects stdout/stderr to its own log and immediately saves LASTEXITCODE. Final authority is `raw/green-final.log`, `raw/green-final-exit.txt`, `raw/final-xml/`, and `raw/final-results.json`. These are a single current run, not historical totals added together.

## Evidence identity

`raw/source-identity.json` records tested source length, working-byte SHA-256 and normalized Git blob. Independent fixture DTO/codec identities are included. `raw/upstream-blobs.json` pins exact official source commits/blobs, including absent historical paths. `raw/fixtures/` retains actual generated no-secret archives and exact Post JSON; `raw/fixture-sha256.json` records byte length/SHA-256. Archive encryption randomness and ZIP timestamps mean later regenerated archive hashes may differ.

`raw/evidence-sha256.json` hashes other raw files and excludes itself. Local .gitattributes preserves raw bytes without newline conversion. Source files follow ordinary repository normalization, so use Git blob identity when comparing checkout line endings.

For coverage and explicit restrictions see COVERAGE_AND_LIMITS.md. No historical RED or XML was overwritten. The existing 81 historical result set is not claimed as newly executed; actual current suite totals come only from the final XML.
