param(
    [Parameter(Mandatory=$true)][string]$Scope,
    [Parameter(Mandatory=$true)][ValidateSet('produce','consume','regression')][string]$Phase
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$scopePath = [IO.Path]::GetFullPath($Scope)
if (-not $scopePath.StartsWith((Join-Path $repo 'app/desktopApp/build/'), [StringComparison]::OrdinalIgnoreCase)) {
    # Normalize separators before checking the owned build workspace.
    $prefix = [IO.Path]::GetFullPath((Join-Path $repo 'app/desktopApp/build')) + [IO.Path]::DirectorySeparatorChar
    if (-not $scopePath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Scope must be beneath desktopApp/build' }
}
if (-not (Test-Path -LiteralPath $scopePath -PathType Container)) { throw 'Create a new synthetic scope first' }
$harness = Join-Path $scopePath 'desktop-harness'
New-Item -ItemType Directory -Force $harness | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'DesktopInteropHarness.kt') -Destination $harness
$run = Join-Path $scopePath ("run-$Phase-" + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory $run | Out-Null
$tests = switch ($Phase) {
    'produce' { @('*DesktopInteropHarnessTest.produceDesktopMaterials') }
    'consume' { @('*DesktopInteropHarnessTest.consumeOfficialAndroidMaterials') }
    'regression' { @('*DesktopBackupCompatibilityTest','*DesktopFullBackup*Test','*DesktopSaveSlot*Test','*DesktopSessionCommitJournalTest','*DesktopStartupGateTest') }
}
$arguments = @('--offline','--no-build-cache','--no-daemon','--no-configuration-cache','--max-workers=1','--no-parallel','--console=plain',
    '-Dorg.gradle.jvmargs=-Xmx1024m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8',
    '-Pkotlin.compiler.execution.strategy=in-process',"-Dccb.interop.scope=$scopePath","-Dccb.interop.harness=$harness",
    '-I',(Join-Path $PSScriptRoot 'desktop.init.gradle'),':desktopApp:test')
foreach ($test in $tests) { $arguments += @('--tests',$test) }
Push-Location (Join-Path $repo 'app')
try {
    & ./gradlew.bat @arguments *> (Join-Path $run 'output.log')
    $code = $LASTEXITCODE
    Copy-Item -LiteralPath 'desktopApp/build/test-results/test' -Destination (Join-Path $run 'junit') -Recurse
    @{exitCode=$code;phase=$Phase} | ConvertTo-Json | Set-Content (Join-Path $run 'result.json')
    if ($code -ne 0) { throw "Tests failed; retained $run" }
    if ($Phase -ne 'regression') {
        $xml = [xml](Get-Content -Raw (Join-Path $run 'junit/TEST-com.example.chatbar.desktop.DesktopInteropHarnessTest.xml'))
        if ([int]$xml.testsuite.tests -ne 1 -or [int]$xml.testsuite.failures -ne 0) { throw 'Expected interoperability test did not execute successfully' }
    }
    Write-Output "Evidence: $run"
} finally { Pop-Location }
