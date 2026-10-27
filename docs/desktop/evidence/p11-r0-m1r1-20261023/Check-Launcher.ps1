$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
$out=Join-Path $PSScriptRoot 'raw/launcher'
New-Item -ItemType Directory -Force $out | Out-Null
$launcher=Join-Path $repo 'tools/desktop/interop/readonly-r0/Invoke-ReadOnlyTool.ps1'
$results=@{}
foreach($case in @(@('plain','SYNTHETIC-code84-plain.cbbackup',0),@('no-console','SYNTHETIC-code84-encrypted.cbbackup',2))) {
 $file=Join-Path $repo ("app/desktopApp/build/readonly-r0/m1r1/"+$case[1])
 if(-not (Test-Path -LiteralPath $file -PathType Leaf)){throw 'SYNTHETIC_FIXTURE_MISSING'}
 & powershell.exe -NoProfile -File $launcher -SelectedFile $file *> (Join-Path $out ($case[0]+'.log'))
 $results[$case[0]]=$LASTEXITCODE
 if($LASTEXITCODE -ne $case[2]){throw 'LAUNCHER_REGRESSION'}
}
$env:JAVA_TOOL_OPTIONS='-Dccb.synthetic.marker=true'
try {
 & powershell.exe -NoProfile -File $launcher -SelectedFile (Join-Path $repo 'app/desktopApp/build/readonly-r0/m1r1/SYNTHETIC-code84-encrypted.cbbackup') *> (Join-Path $out 'dangerous-options.log')
 $results['dangerous-options']=$LASTEXITCODE
 if($LASTEXITCODE -ne 2){throw 'JVM_OPTION_GUARD_REGRESSION'}
} finally {Remove-Item Env:JAVA_TOOL_OPTIONS}
$results | ConvertTo-Json | Set-Content (Join-Path $out 'exits.json')
Write-Output 'LAUNCHER_SYNTHETIC=PASS'
