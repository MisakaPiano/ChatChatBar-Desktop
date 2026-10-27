param(
    [Parameter(Mandatory=$true)][string]$Python,
    [Parameter(Mandatory=$true)][string]$LegacyFixture,
    [Parameter(Mandatory=$true)][string]$Evidence
)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -ne 5 -or $PSVersionTable.PSVersion.Minor -ne 1) { throw 'WINDOWS_POWERSHELL_51_REQUIRED' }
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$scope = [IO.Path]::GetFullPath((Join-Path $repo 'app/desktopApp/build')) + [IO.Path]::DirectorySeparatorChar
$round = [IO.Path]::GetFullPath($Evidence)
if (-not $round.StartsWith($scope, [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $round)) { throw 'FRESH_OWNED_EVIDENCE_REQUIRED' }
if (-not [IO.File]::Exists($Python) -or -not [IO.File]::Exists($LegacyFixture)) { throw 'EXPLICIT_INPUT_REQUIRED' }
$fixtureHash = (Get-FileHash -LiteralPath $LegacyFixture -Algorithm SHA256).Hash.ToLowerInvariant()
if ($fixtureHash -ne '388f4e8f83377f17bc1194940ec77a7e5feda394d3bf585b570b44aff42b6197') { throw 'PINNED_SYNTHETIC_A_REQUIRED' }
New-Item -ItemType Directory -Path $round | Out-Null
$source = Join-Path $round 'source'
New-Item -ItemType Directory -Path $source | Out-Null
$files = @('msi_safety.py','directory_guard.c','build_directory_guard.py','test_msi_directory_guard.py','run_directory_guard_tests.py','Invoke-DirectoryGuardChecks.ps1','native_package_probe.py','test_native_probe_validation.py')
$rows = @()
foreach ($name in $files) {
    $path = Join-Path $PSScriptRoot $name
    Copy-Item -LiteralPath $path -Destination (Join-Path $source $name)
    $rows += @{file=$name;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
}
Copy-Item -LiteralPath (Join-Path $repo 'app/desktopApp/build.gradle.kts') -Destination (Join-Path $source 'build.gradle.kts')
$rows | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $round 'SOURCE_SHA.json') -Encoding UTF8
function Invoke-Recorded([string]$Label, [string[]]$Arguments) {
    $Arguments | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $round ($Label + '.arguments.json')) -Encoding UTF8
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Python @Arguments > (Join-Path $round ($Label + '.stdout.log')) 2> (Join-Path $round ($Label + '.stderr.log'))
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    [IO.File]::WriteAllText((Join-Path $round ($Label + '.exit')), [string]$code, [Text.Encoding]::ASCII)
    if ($code -ne 0) { throw ('CHECK_FAILED_' + $Label) }
}
Invoke-Recorded 'production-compile' @('-I',(Join-Path $PSScriptRoot 'build_directory_guard.py'),'--output',(Join-Path $round 'production'))
Invoke-Recorded 'test-compile' @('-I',(Join-Path $PSScriptRoot 'build_directory_guard.py'),'--output',(Join-Path $round 'test'),'--test')
$env:CCB_GUARD_PRODUCTION_DLL = Join-Path $round 'production/directory_guard.dll'
$env:CCB_GUARD_TEST_DLL = Join-Path $round 'test/directory_guard.dll'
$env:CCB_LEGACY_MSI_FIXTURE = [IO.Path]::GetFullPath($LegacyFixture)
Invoke-Recorded 'tests' @('-I',(Join-Path $source 'run_directory_guard_tests.py'),'--evidence',$round)
if ((Get-FileHash -LiteralPath $LegacyFixture -Algorithm SHA256).Hash.ToLowerInvariant() -ne $fixtureHash) { throw 'SOURCE_MSI_CHANGED' }
foreach ($row in $rows) {
    if ((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $row.file) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $row.sha256) { throw 'SOURCE_CHANGED_DURING_TEST' }
}
[IO.File]::WriteAllText((Join-Path $round 'PS51_COMPLETE.txt'), 'PS51_COMPLETE_NO_INSTALLER', [Text.Encoding]::ASCII)
Write-Output 'DIRECTORY_GUARD_CHECKS_COMPLETE_NO_INSTALLER'
