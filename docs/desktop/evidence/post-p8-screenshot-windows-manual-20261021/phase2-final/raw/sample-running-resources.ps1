$ErrorActionPreference='Stop'
$run=Join-Path $PSScriptRoot 'runs/manual01'
$output=Join-Path $run 'resource-observations.jsonl'
if(Test-Path -LiteralPath $output){throw 'Resource observer already exists; no duplicate sampler'}
while($true){
 try {
  $obs=Get-Content -LiteralPath (Join-Path $run 'process-observations.jsonl') -Tail 1 | ConvertFrom-Json
  $owned=@(foreach($pidValue in $obs.alivePids){Get-Process -Id $pidValue -ErrorAction SilentlyContinue | Select-Object Id,Responding,WorkingSet64,PrivateMemorySize64,PeakWorkingSet64})
  $os=Get-CimInstance Win32_OperatingSystem
  $mem=Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory
  $allocationFailure=@(Select-String -LiteralPath (Join-Path $run 'stderr.log') -Pattern 'OutOfMemoryError|Native memory allocation .* failed|There is insufficient memory' -ErrorAction SilentlyContinue).Count -gt 0
  [ordered]@{utc=[DateTimeOffset]::UtcNow.ToString('o');ownedProcesses=$owned;freePhysicalKiB=$os.FreePhysicalMemory;commitHeadroomBytes=([int64]$mem.CommitLimit-[int64]$mem.CommittedBytes);pagesPerSecond=$mem.PagesPersec;allocationFailureObserved=$allocationFailure;memoryFigureAloneBlocks=$false} | ConvertTo-Json -Depth 5 -Compress | Add-Content -LiteralPath $output -Encoding utf8
  if($allocationFailure){[ordered]@{reason='Allocation failure in owned application stderr';utc=[DateTimeOffset]::UtcNow.ToString('o');action='Stop further manual steps; retain app and evidence; no kill or retry'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run 'resource-fault-attention.json') -Encoding utf8}
  if($owned.Count -eq 0){break}
 } catch {
  [ordered]@{utc=[DateTimeOffset]::UtcNow.ToString('o');observationErrorType=$_.Exception.GetType().Name} | ConvertTo-Json -Compress | Add-Content -LiteralPath $output -Encoding utf8
 }
 Start-Sleep -Seconds 5
}
