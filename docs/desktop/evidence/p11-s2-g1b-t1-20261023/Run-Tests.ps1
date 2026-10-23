param([ValidateSet('first','red','green','final')][string]$Round, [switch]$Focused)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$out=Join-Path $PSScriptRoot "raw/$Round"
if(Test-Path (Join-Path $out 'gradle.log')){throw 'ROUND_ALREADY_RECORDED'}
New-Item -ItemType Directory -Force (Join-Path $out 'source') | Out-Null
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$arguments=@('--offline','--no-daemon','--no-configuration-cache','--max-workers=1','--no-parallel','--console=plain',
 '-Dorg.gradle.jvmargs=-Xmx1024m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8','-Pkotlin.compiler.execution.strategy=in-process',':desktopApp:test',
 '--tests','*DesktopTargetIdentityEvidenceTest')
if(!$Focused){foreach($suite in @('DesktopCredentialImportPlanTest','RecoverableIntentContractTest','DesktopCredentialSourceCensusTest',
 'DesktopDocumentIndexReadEvidenceTest','WindowsSecretRootResolverTest','WindowsSecretStoreTest','DesktopDataRootOwnershipTest',
 'DesktopFullBackupCommitJournalTest','DesktopStartupGateTest','P11S2CredentialTransactionBoundaryProbeTest','T0RecoveryLabTest')){
 $arguments+=@('--tests',"*$suite")}}
$arguments | ConvertTo-Json | Set-Content (Join-Path $out 'arguments.json')
& "$env:JAVA_HOME/bin/java.exe" -version *> (Join-Path $out 'jdk.log')
foreach($file in @('app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/DesktopTargetIdentityEvidence.kt',
 'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/ExplicitSyntheticDirectoryWitnessReader.kt',
 'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/backup/DesktopTargetIdentityEvidenceTest.kt')){
 Copy-Item (Join-Path $repo $file) (Join-Path $out "source/$([IO.Path]::GetFileName($file))")}
Push-Location (Join-Path $repo 'app')
try{
 & ./gradlew.bat @arguments *> (Join-Path $out 'gradle.log')
 $code=$LASTEXITCODE; $code | Set-Content (Join-Path $out 'exit.txt')
 if(Select-String -LiteralPath (Join-Path $out 'gradle.log') -Pattern '^> Task :desktopApp:test( |$)' -Quiet){
  Copy-Item 'desktopApp/build/test-results/test' (Join-Path $out 'junit') -Recurse}
 Write-Output "ROUND=$Round EXIT=$code"; exit $code
}finally{Pop-Location}
