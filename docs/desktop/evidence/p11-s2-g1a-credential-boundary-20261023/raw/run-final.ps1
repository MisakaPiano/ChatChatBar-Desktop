$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:JAVA_TOOL_OPTIONS = '-Djava.awt.headless=true'
$repoDir = (Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
Set-Location (Join-Path $repoDir 'app')
& .\gradlew.bat :desktopApp:test --tests '*P11S2CredentialTransactionBoundaryProbeTest' --tests '*WindowsSecretStoreTest' --tests '*WindowsSecretRootResolverTest' --tests '*DesktopSecureSettingsRepositoryTest' --tests '*DesktopSecureModelRepositoryTest' --tests '*DesktopSecureEmbeddingRepositoryTest' --tests '*DesktopFullBackupArchiveTest' --tests '*DesktopBackupReadOnlyInspectorTest' --tests '*DesktopFullBackupServiceTest' --tests '*DesktopFullBackupCommitJournalTest' --tests '*DesktopFullBackupProcessTest' --tests '*DesktopStartupGateTest' --offline --no-configuration-cache --console=plain --no-daemon --max-workers=1 *> (Join-Path $PSScriptRoot 'final-run.log')
$actualExit = $LASTEXITCODE
Set-Content -LiteralPath (Join-Path $PSScriptRoot 'final-exit.txt') -Value $actualExit
exit $actualExit
