param([ValidateSet('first','red','receipt-red','green')][string]$Round, [switch]$Focused)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$out=Join-Path $PSScriptRoot "raw/$Round"
if(Test-Path (Join-Path $out 'gradle.log')){throw 'ROUND_ALREADY_RECORDED'}
New-Item -ItemType Directory -Force $out | Out-Null
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$argsList=@('--offline','--no-daemon','--no-configuration-cache','--max-workers=1','--no-parallel','--console=plain',
 '-Dorg.gradle.jvmargs=-Xmx1024m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8','-Pkotlin.compiler.execution.strategy=in-process',':desktopApp:test',
 '--tests','*T0RecoveryLabTest','--tests','*P11S2CredentialTransactionBoundaryProbeTest',
 '--tests','*DesktopCredentialImportPlanTest','--tests','*RecoverableIntentContractTest','--tests','*WindowsSecretStoreTest',
 '--tests','*DesktopFullBackupCommitJournalTest','--tests','*DesktopStartupGateTest','--tests','*DesktopDataOperationCoordinatorTest')
if($Focused){$argsList=$argsList[0..8]+@('--tests','*T0RecoveryLabTest.completedReceiptWithoutDecisionMustRemainBlocked')}
$argsList | ConvertTo-Json | Set-Content (Join-Path $out 'arguments.json')
& "$env:JAVA_HOME/bin/java.exe" -version *> (Join-Path $out 'jdk.log')
New-Item -ItemType Directory -Force (Join-Path $out 'source') | Out-Null
Copy-Item (Join-Path $repo 'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/g1bt0/*.kt') (Join-Path $out 'source')
Push-Location (Join-Path $repo 'app')
try {
 & ./gradlew.bat @argsList *> (Join-Path $out 'gradle.log')
 $code=$LASTEXITCODE; $code | Set-Content (Join-Path $out 'exit.txt')
 if((Select-String -LiteralPath (Join-Path $out 'gradle.log') -Pattern '^> Task :desktopApp:test( |$)' -Quiet) -and (Test-Path 'desktopApp/build/test-results/test')){Copy-Item 'desktopApp/build/test-results/test' (Join-Path $out 'junit') -Recurse}
 Write-Output "ROUND=$Round EXIT=$code";exit $code
}finally{Pop-Location}
