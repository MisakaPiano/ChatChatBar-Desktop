$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:JAVA_TOOL_OPTIONS = '-Djava.awt.headless=true'
$repoDir = (Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$runDir = Join-Path $repoDir 'docs/desktop/evidence/p11-s2-g0r-media-delete-20261023/raw'
Set-Location (Join-Path $repoDir 'app')
& .\gradlew.bat :desktopApp:test --tests '*P11S2OwnedMediaSafetyProbeTest' --tests '*DesktopCharacterResourceStoreTest' --tests '*DesktopPhase7R2Test' --tests '*DesktopFullBackupMappingTest' --tests '*DesktopFullBackupArchiveTest' --tests '*DesktopFullBackupServiceTest' --tests '*DesktopCoordinatedSnapshotServiceTest' --tests '*DesktopBackupReadOnlyInspectorTest' --tests '*DesktopReadOnlyBackupRedTest' --tests '*MomentArchiveReadOnlyValidatorTest' --offline --no-configuration-cache --console=plain --no-daemon --max-workers=1 *> (Join-Path $runDir 'final-run.log')
$actualExit = $LASTEXITCODE
Set-Content -LiteralPath (Join-Path $runDir 'final-exit.txt') -Value $actualExit
exit $actualExit
