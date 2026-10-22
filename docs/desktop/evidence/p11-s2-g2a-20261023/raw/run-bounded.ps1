$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=true'
Set-Location -LiteralPath 'H:\ChatChatBar-Desktop\app'
& .\gradlew.bat :desktopApp:test --tests '*DesktopCredentialImportPlanTest' --tests '*RecoverableIntentContractTest' --tests '*G2AOfficialWireTest' --tests '*P11S2CredentialTransactionBoundaryProbeTest' --tests '*WindowsSecretStoreTest' --tests '*DesktopSecureModelRepositoryTest' --tests '*DesktopSecureEmbeddingRepositoryTest' --tests '*DesktopFullBackupArchiveTest' --tests '*DesktopBackupReadOnlyInspectorTest' --tests '*MomentArchiveReadOnlyValidatorTest' --tests '*DesktopFullBackupCommitJournalTest' --tests '*DesktopStartupGateTest' --tests '*DesktopReadOnlyBackupRedTest' --offline --no-configuration-cache --console=plain --no-daemon --max-workers=1 *> '..\docs\desktop\evidence\p11-s2-g2a-20261023\raw\bounded.log'
$g2aExit=$LASTEXITCODE
Set-Content -LiteralPath '..\docs\desktop\evidence\p11-s2-g2a-20261023\raw\bounded-exit.txt' -Value $g2aExit
exit $g2aExit


