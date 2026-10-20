$ErrorActionPreference='Stop'
$run=Join-Path $PSScriptRoot 'runs/manual01'
$path=Join-Path $run 'native-window-changes.jsonl'
if(Test-Path -LiteralPath $path){throw 'Native observer already exists'}
Add-Type -TypeDefinition (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'support-src/capture-native-windows.ps1.cs') -Raw)
$obs=Get-Content -LiteralPath (Join-Path $run 'process-observations.jsonl') -Tail 1 | ConvertFrom-Json
$owned=[System.Collections.Generic.HashSet[uint32]]::new()
foreach($pidValue in $obs.alivePids){$null=$owned.Add([uint32]$pidValue)}
$app=[System.Diagnostics.Process]::GetProcessById([int]$obs.jvmPids[0])
$null=$app.Handle
$previous=$null
while(-not $app.HasExited){
 $windows=[ScreenshotWindowEvidence]::Read($owned)
 $signature=ConvertTo-Json -InputObject @($windows) -Depth 6 -Compress
 if($signature -ne $previous){
  [ordered]@{utc=[DateTimeOffset]::UtcNow.ToString('o');windows=$windows;method='Passive owned-HWND snapshots; no WM_CLOSE interception or UI action'} | ConvertTo-Json -Depth 7 -Compress | Add-Content -LiteralPath $path -Encoding utf8
  $previous=$signature
 }
 Start-Sleep -Milliseconds 250
}
[ordered]@{utc=[DateTimeOffset]::UtcNow.ToString('o');event='OWNED_JVM_EXIT_OBSERVED';exitCode=$app.ExitCode;noKill=$true} | ConvertTo-Json -Compress | Add-Content -LiteralPath $path -Encoding utf8
$app.Dispose()
