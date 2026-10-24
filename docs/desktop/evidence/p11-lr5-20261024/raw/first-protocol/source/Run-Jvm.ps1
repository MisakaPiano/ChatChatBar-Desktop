param([Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$Round)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$out=Join-Path $repo "docs/desktop/evidence/p11-lr5-20261024/raw/$Round"
if(Test-Path -LiteralPath $out){throw 'ROUND_ALREADY_EXISTS'}
New-Item -ItemType Directory $out|Out-Null
$snapshot=Join-Path $out 'source';New-Item -ItemType Directory $snapshot|Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'src') -Destination $snapshot -Recurse
Copy-Item -LiteralPath $PSCommandPath -Destination $snapshot
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'lr5.init.gradle') -Destination $snapshot
$build=Join-Path $repo "app/desktopApp/build/p11-lr5/$Round"
if(Test-Path -LiteralPath $build){throw 'SANDBOX_PARENT_EXISTS'}
New-Item -ItemType Directory $build|Out-Null
$child=Join-Path $out 'child';New-Item -ItemType Directory $child|Out-Null
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$a=@('--offline','--no-daemon','--no-build-cache','--no-configuration-cache','--max-workers=1','--no-parallel','--console=plain',
 '-Dorg.gradle.jvmargs=-Xmx1024m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8','-Pkotlin.compiler.execution.strategy=in-process','-Pkotlin.incremental=false',
 "-Dccb.lr5.sources=$PSScriptRoot/src","-Dccb.lr5.sandboxes=$build","-Dccb.lr5.evidence=$child",'-I',(Join-Path $PSScriptRoot 'lr5.init.gradle'),
 ':desktopApp:test','--rerun','--tests','*lr5.SyntheticJournalTest','--tests','*lr5.ExperimentWireTest')
$a|ConvertTo-Json|Set-Content (Join-Path $out 'arguments.json')
$start=[DateTime]::UtcNow
Push-Location (Join-Path $repo 'app')
try{
 $ErrorActionPreference='Continue'
 & ./gradlew.bat @a *> (Join-Path $out 'gradle.log')
 $code=$LASTEXITCODE;$ErrorActionPreference='Stop';$code|Set-Content (Join-Path $out 'exit.txt')
 if(Select-String -LiteralPath (Join-Path $out 'gradle.log') -Pattern '^> Task :desktopApp:test( |$)' -Quiet){
  $junit=Join-Path $out 'junit';New-Item -ItemType Directory $junit|Out-Null
  foreach($f in Get-ChildItem 'desktopApp/build/test-results/test' -Filter '*.xml'){
   [xml]$x=Get-Content -LiteralPath $f.FullName -Raw
   if([DateTimeOffset]::Parse($x.testsuite.timestamp).UtcDateTime -ge $start.AddSeconds(-1)){Copy-Item -LiteralPath $f.FullName $junit}
  }
 }
 "ROUND=$Round EXIT=$code";exit $code
}finally{Pop-Location}
