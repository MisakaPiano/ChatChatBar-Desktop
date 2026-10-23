$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$out = Join-Path $PSScriptRoot 'raw/launcher-green'
New-Item -ItemType Directory -Force $out | Out-Null
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
$fixtureBase = Join-Path $repo 'app/desktopApp/build/readonly-r0/synthetic'
# Search is restricted to invocation-created synthetic materials, never a user directory.
$plain = Join-Path (Get-ChildItem -LiteralPath $fixtureBase -Directory -Filter 'plain-*' | Select-Object -First 1).FullName 'input.cbbackup'
$encrypted = Join-Path (Get-ChildItem -LiteralPath $fixtureBase -Directory -Filter 'encrypted-*' | Select-Object -First 1).FullName 'input.cbbackup'
$launcher = Join-Path $repo 'tools/desktop/interop/readonly-r0/Invoke-ReadOnlyTool.ps1'
$codes = @{}
foreach ($case in @(@('plain',$plain),@('encrypted-no-console',$encrypted))) {
 & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $launcher -SelectedFile $case[1] *> (Join-Path $out ($case[0] + '.log'))
 $codes[$case[0]] = $LASTEXITCODE
}
$oldOptions = [Environment]::GetEnvironmentVariable('JAVA_TOOL_OPTIONS')
try {
 $env:JAVA_TOOL_OPTIONS = '-Dccb.synthetic.launcher.marker=true'
 & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $launcher -SelectedFile $plain *> (Join-Path $out 'inherited-options.log')
 $codes['inherited-options'] = $LASTEXITCODE
} finally { $env:JAVA_TOOL_OPTIONS = $oldOptions }
$codes | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $out 'exits.json')
New-Item -ItemType Directory -Force (Join-Path $out 'source') | Out-Null
Copy-Item -LiteralPath $launcher -Destination (Join-Path $out 'source')
foreach ($name in @('Invoke-ReadOnlyTool.ps1','Prepare-ReadOnlyTool.ps1')) {
 $tokens = $null; $errors = $null
 [Management.Automation.Language.Parser]::ParseFile((Join-Path $repo "tools/desktop/interop/readonly-r0/$name"), [ref]$tokens, [ref]$errors) | Out-Null
 if ($errors.Count -ne 0) { throw 'SCRIPT_PARSE_FAILED' }
}
if ($codes['plain'] -ne 0 -or $codes['encrypted-no-console'] -ne 2 -or $codes['inherited-options'] -ne 2) { throw 'SYNTHETIC_LAUNCHER_CHECK_FAILED' }
Write-Output 'SYNTHETIC_LAUNCHER=PASS;POWERSHELL_PARSE=PASS'
