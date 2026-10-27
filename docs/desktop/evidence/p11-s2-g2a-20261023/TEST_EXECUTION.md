# G2A actual test execution

JDK: `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`; JVM17 target. Working directory `H:\ChatChatBar-Desktop\app`. `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`; offline Gradle, no GUI/device/provider.

| Run | Actual outcome | Raw evidence |
|---|---|---|
| Initial sandbox invocation | exit1, Gradle cache wrapper lock denied; **0 tests executed**, no new JUnit | `raw/run-initial.ps1`, `initial.log`, `initial-exit.txt` |
| Focused new suites | exit1; 3 suites,40 tests,39 PASS,1 FAIL,0 errors/skips;1m18s | `raw/run-focused.ps1`, `focused.log`, `focused-exit.txt`, `raw/red/junit/` and exact `raw/red/source/` |
| Bounded final | exit0;13 suites,150 tests,150 PASS,0 FAIL/errors/skips;1m47s | `raw/run-bounded.ps1`, `bounded.log`, `bounded-exit.txt`, `raw/green/junit/` and exact `raw/green/source/` |

No artificial pre-implementation functional RED was manufactured. The real RED was a missing temporary test work directory, corrected in the fixture. Two additional intent evidence tests were added after review of the new model. Production recovery behavior was not changed to accommodate tests.

Exact final Gradle invocation (also retained as an executable PowerShell script):

```powershell
.\gradlew.bat :desktopApp:test --tests '*DesktopCredentialImportPlanTest' --tests '*RecoverableIntentContractTest' --tests '*G2AOfficialWireTest' --tests '*P11S2CredentialTransactionBoundaryProbeTest' --tests '*WindowsSecretStoreTest' --tests '*DesktopSecureModelRepositoryTest' --tests '*DesktopSecureEmbeddingRepositoryTest' --tests '*DesktopFullBackupArchiveTest' --tests '*DesktopBackupReadOnlyInspectorTest' --tests '*MomentArchiveReadOnlyValidatorTest' --tests '*DesktopFullBackupCommitJournalTest' --tests '*DesktopStartupGateTest' --tests '*DesktopReadOnlyBackupRedTest' --offline --no-configuration-cache --console=plain --no-daemon --max-workers=1
```

| Final suite | tests/PASS | FAIL/errors/SKIPPED |
|---|---:|---:|
| DesktopCredentialImportPlanTest |17|0/0/0|
| RecoverableIntentContractTest |20|0/0/0|
| G2AOfficialWireTest |5|0/0/0|
| P11S2CredentialTransactionBoundaryProbeTest |17|0/0/0|
| WindowsSecretStoreTest |7|0/0/0|
| DesktopSecureModelRepositoryTest |5|0/0/0|
| DesktopSecureEmbeddingRepositoryTest |8|0/0/0|
| DesktopFullBackupArchiveTest |12|0/0/0|
| DesktopBackupReadOnlyInspectorTest |18|0/0/0|
| MomentArchiveReadOnlyValidatorTest |16|0/0/0|
| DesktopFullBackupCommitJournalTest |4|0/0/0|
| DesktopStartupGateTest |20|0/0/0|
| DesktopReadOnlyBackupRedTest |1|0/0/0|

Gradle configuration/resource tasks labelled SKIPPED/NO-SOURCE are not skipped tests. `raw/green/junit-summary.json` is parsed from the actual13 XML files. Historical G1A112 is not reused as a new execution count.

Known synthetic literals are scanned only in diagnostic logs/XML (`raw/diagnostic-redaction-audit.json`). Raw fixture bytes intentionally retain fake source values; input archives and their byte/hash identities are in `raw/green/fixtures/` and `raw/fixture-identities.json`. G2A fixture teardown deletes only its own new bounded temp parents. G1A cleanup state is retained; its tests use Fake Protector, no account DPAPI.

Codex runtime/limits: `raw/codex-usage.json` records the account-wide available quota snapshots (22% then23% used). Per-thread tokens and exact runtime model are not exposed; account change cannot be attributed solely to this task. No account ID is recorded.
