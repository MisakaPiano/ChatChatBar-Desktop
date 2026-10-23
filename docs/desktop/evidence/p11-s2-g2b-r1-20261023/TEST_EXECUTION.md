# R1 actual execution record

JDK17: `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`. Gradle cwd `H:\ChatChatBar-Desktop\app`. Headless `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`; offline, no configuration cache, plain console, single-use daemon/max-workers1. Exact runnable commands and exits retained in raw/run-*.ps1 and matching log/exit files.

| Run | Actual result | Evidence |
|---|---|---|
| Unchanged-production R1 repro |1 suite/3 tests:2 PASS,1 FAIL,0 errors/skips;exit1;44s|run-red.ps1,red.log,red-exit.txt;raw/red source/XML/fixtures|
| String-only guard intermediate |15 suites/182 tests:181 PASS,1 FAIL,0 errors/skips;exit1;1m48s|run-bounded.ps1,bounded.log,bounded-exit.txt;raw/intermediate full15 XML/source/fixtures|
| Final integer-token guard |15 suites/182 tests:182 PASS,0 failures/errors/skips;exit0;1m44s|run-final.ps1,final.log,final-exit.txt;raw/green full15 XML/source/fixtures|

First repro failure is quoted123 incorrectly complete. Intermediate failure is exponent1e2 still accepted by longOrNull after !isString was added. Final token grammar plus Long range rejects both. No artificial RED or disabled assertions. The negative loop stops at the first failing case in each RED; the final run executes all11 invalid values.5 valid cases and future-field case complete17 independent wire variants inside3 actual JUnit tests, not17 separately counted tests.

Final command:

```powershell
.\gradlew.bat :desktopApp:test --tests '*DesktopCredentialSourceCensusTest' --tests '*DesktopCredentialImportPlanTest' --tests '*RecoverableIntentContractTest' --tests '*G2AOfficialWireTest' --tests '*P11S2CredentialTransactionBoundaryProbeTest' --tests '*WindowsSecretStoreTest' --tests '*DesktopSecureModelRepositoryTest' --tests '*DesktopSecureEmbeddingRepositoryTest' --tests '*DesktopFullBackupArchiveTest' --tests '*DesktopFullBackupMappingTest' --tests '*DesktopBackupReadOnlyInspectorTest' --tests '*MomentArchiveReadOnlyValidatorTest' --tests '*DesktopFullBackupCommitJournalTest' --tests '*DesktopStartupGateTest' --tests '*DesktopReadOnlyBackupRedTest' --offline --no-configuration-cache --console=plain --no-daemon --max-workers=1
```

| Final class | tests/PASS | FAIL/errors/skips |
|---|---:|---:|
| DesktopCredentialSourceCensusTest |30|0/0/0|
| DesktopCredentialImportPlanTest |17|0/0/0|
| RecoverableIntentContractTest |20|0/0/0|
| G2AOfficialWireTest |5|0/0/0|
| P11S2CredentialTransactionBoundaryProbeTest |17|0/0/0|
| WindowsSecretStoreTest |7|0/0/0|
| DesktopSecureModelRepositoryTest |5|0/0/0|
| DesktopSecureEmbeddingRepositoryTest |8|0/0/0|
| DesktopFullBackupArchiveTest |12|0/0/0|
| DesktopFullBackupMappingTest |2|0/0/0|
| DesktopBackupReadOnlyInspectorTest |18|0/0/0|
| MomentArchiveReadOnlyValidatorTest |16|0/0/0|
| DesktopFullBackupCommitJournalTest |4|0/0/0|
| DesktopStartupGateTest |20|0/0/0|
| DesktopReadOnlyBackupRedTest |1|0/0/0|

Original XML is authoritative. Historical179/24 and earlier runs are not accumulated into182. Gradle SKIPPED configuration tasks are not skipped tests. Scripts retain actual exits. Current source/fixture/test/official Git blob/SHA/length identities and raw byte index are supplied; only two source files changed. Main-tree diff verification rejects any other production change.

Four known synthetic literals were scanned in34 diagnostic log/XML files with zero hits. Explicit raw source/fixture test bytes intentionally contain fake values. Every R1 input is retained with hash/length and unchanged-source/owned-temp cleanup evidence. This does not prove arbitrary future adapters cannot leak secrets. No real archive/key/password/root/DPAPI, GUI/Android/provider/installer or new transaction executed. Existing bounded census and synthetic-observer limitations persist.
