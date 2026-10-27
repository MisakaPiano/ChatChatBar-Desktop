param([Parameter(Mandatory=$true)][string]$Round,[string[]]$Suites=@('FullBackupHiddenMediaSafetyTest'),[switch]$Prepare)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$out=Join-Path $repo "docs/desktop/evidence/prepreview-autonomous-20261023/raw/$Round"
if(Test-Path (Join-Path $out 'gradle.log')){throw 'ROUND_ALREADY_RECORDED'}
New-Item -ItemType Directory -Force $out|Out-Null
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$a=@('--offline','--no-daemon','--no-build-cache','--no-configuration-cache','--max-workers=1','--no-parallel','--console=plain',
 '-Dorg.gradle.jvmargs=-Xmx1024m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8','-Pkotlin.compiler.execution.strategy=in-process',
 "-Dccb.readonly.sources=$PSScriptRoot/src",'-I',(Join-Path $PSScriptRoot '../readonly-r0/readonly.init.gradle'))
if($Prepare){$a+=':desktopApp:prepareRealArchiveReadOnlyR0'}else{
 $a+=@(':desktopApp:test','--rerun');foreach($s in $Suites){$a+=@('--tests',"*$s")}}
$a|ConvertTo-Json|Set-Content (Join-Path $out 'arguments.json')
$startedUtc=[DateTime]::UtcNow
Push-Location (Join-Path $repo 'app')
try{
 & ./gradlew.bat @a *> (Join-Path $out 'gradle.log')
 $code=$LASTEXITCODE;$code|Set-Content (Join-Path $out 'exit.txt')
 if(!$Prepare -and (Select-String -LiteralPath (Join-Path $out 'gradle.log') -Pattern '^> Task :desktopApp:test( |$)' -Quiet)){
  $junit=Join-Path $out 'junit'
  New-Item -ItemType Directory -Force $junit | Out-Null
  foreach($xmlFile in Get-ChildItem 'desktopApp/build/test-results/test' -Filter '*.xml'){
   [xml]$suite=Get-Content -LiteralPath $xmlFile.FullName -Raw
   $stamp=[DateTimeOffset]::Parse($suite.testsuite.timestamp).UtcDateTime
   if($stamp -ge $startedUtc.AddSeconds(-1)) {Copy-Item -LiteralPath $xmlFile.FullName $junit}
  }
 }
 "ROUND=$Round EXIT=$code";exit $code
}finally{Pop-Location}
