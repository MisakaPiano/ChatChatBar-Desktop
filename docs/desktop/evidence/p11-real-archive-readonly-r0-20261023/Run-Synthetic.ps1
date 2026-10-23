param([Parameter(Mandatory=$true)][ValidateSet('first','green')][string]$Round)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$out = Join-Path $PSScriptRoot "raw/$Round"
New-Item -ItemType Directory -Force $out | Out-Null
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
$argsList = @('--offline','--no-daemon','--no-build-cache','--no-configuration-cache','--max-workers=1','--no-parallel','--console=plain',
 '-Dorg.gradle.jvmargs=-Xmx1024m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8','-Pkotlin.compiler.execution.strategy=in-process',
 "-Dccb.readonly.sources=$repo/tools/desktop/interop/readonly-r0/src",'-I',"$repo/tools/desktop/interop/readonly-r0/readonly.init.gradle",':desktopApp:test',
 '--tests','*RealArchiveReadOnlyToolTest','--tests','*DesktopBackupReadOnlyInspectorTest','--tests','*DesktopReadOnlyBackupRedTest',
 '--tests','*MomentArchiveReadOnlyValidatorTest','--tests','*DesktopCredentialSourceCensusTest',
 '--tests','*DesktopFullBackupArchiveTest','--tests','*DesktopCredentialImportPlanTest',':desktopApp:prepareRealArchiveReadOnlyR0')
($argsList | ConvertTo-Json) | Set-Content -Encoding UTF8 (Join-Path $out 'arguments.json')
& "$env:JAVA_HOME/bin/java.exe" -version *> (Join-Path $out 'jdk.log')
Push-Location (Join-Path $repo 'app')
try {
 & ./gradlew.bat @argsList *> (Join-Path $out 'gradle.log')
 $code = $LASTEXITCODE
 [IO.File]::WriteAllText((Join-Path $out 'exit.txt'), [string]$code)
 if (Test-Path 'desktopApp/build/test-results/test') { Copy-Item 'desktopApp/build/test-results/test' (Join-Path $out 'junit') -Recurse }
 Write-Output "ROUND=$Round EXIT=$code"
 exit $code
} finally { Pop-Location }
