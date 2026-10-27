# G2B execution record

Working directory `H:\ChatChatBar-Desktop\app`; JAVA_HOME `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`, JAVA_TOOL_OPTIONS `-Djava.awt.headless=true`.

| Run | Actual tests | Outcome | Raw |
|---|---|---|---|
| Focused new suite |1 suite/24 tests|23 PASS,1 FAIL,0 errors/skips,exit1,1m35s|run-focused.ps1/focused.log/focused-exit.txt, red/junit and red/source|
| Final bounded |15 suites/179 tests|179 PASS,0 failures/errors/skips,exit0,1m50s|run-bounded.ps1/bounded.log/bounded-exit.txt, green/junit and green/source|

Exact scripts are retained under `raw/`. Final invocation:

```powershell
.\gradlew.bat :desktopApp:test --tests '*DesktopCredentialSourceCensusTest' --tests '*DesktopCredentialImportPlanTest' --tests '*RecoverableIntentContractTest' --tests '*G2AOfficialWireTest' --tests '*P11S2CredentialTransactionBoundaryProbeTest' --tests '*WindowsSecretStoreTest' --tests '*DesktopSecureModelRepositoryTest' --tests '*DesktopSecureEmbeddingRepositoryTest' --tests '*DesktopFullBackupArchiveTest' --tests '*DesktopFullBackupMappingTest' --tests '*DesktopBackupReadOnlyInspectorTest' --tests '*MomentArchiveReadOnlyValidatorTest' --tests '*DesktopFullBackupCommitJournalTest' --tests '*DesktopStartupGateTest' --tests '*DesktopReadOnlyBackupRedTest' --offline --no-configuration-cache --console=plain --no-daemon --max-workers=1
```

| Final class | Executed/PASS | FAIL/errors/skips |
|---|---:|---:|
| DesktopCredentialSourceCensusTest |27|0/0/0|
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

Numbers parsed from original15 XML in `raw/green/junit-summary.json`. New27 + actually rerun152 =179, not old150+179; initial24 is not cumulative. SKIPPED Gradle tasks are not skipped tests.

The real focused failure is documented in PROJECT_HANDOFF. Evidence capture retries only copy/verify the same completed run; they are not new test executions. No source/test edits after the final run. Full default wire positives are synthetic source-backed examples, not APK-exported official archives. Raw byte hashes and cleanup records bind each input to its test fixture.

No GUI/ADB/Android/real credential/DPAPI/provider/installation execution. Existing G1A uses explicit Fake Protector and owned temp roots; secret value buffers in new tests use labelled fake chars. Resource release: own temporary parents are removed, source bytes compared unchanged in finally; source-census copied chars are directly asserted zeroed after close/success/cancel; retained comparator refuses reuse. No claim of whole-JVM secret erasure or native blocking-IO cancellation deadline.
