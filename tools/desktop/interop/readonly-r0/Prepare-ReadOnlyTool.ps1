$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
Push-Location (Join-Path $repo 'app')
try {
    & ./gradlew.bat --offline --no-daemon --no-configuration-cache --max-workers=1 --no-parallel --console=plain '-Dorg.gradle.jvmargs=-Xmx1024m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' "-Dccb.readonly.sources=$PSScriptRoot/src" -I (Join-Path $PSScriptRoot 'readonly.init.gradle') :desktopApp:prepareRealArchiveReadOnlyR0
    if ($LASTEXITCODE -ne 0) { throw 'READONLY_R0_BUILD_FAILED' }
} finally { Pop-Location }
