param([Parameter(Mandatory=$true)][string]$RunId)
$ErrorActionPreference='Stop'
$run=Join-Path $PSScriptRoot "runs/$RunId"
$last=Get-Content -LiteralPath (Join-Path $run 'process-observations.jsonl') -Tail 1 | ConvertFrom-Json
$windows=@()
foreach($jvmPid in $last.jvmPids){
 $identity=@($last.processes | Where-Object {$_.pid -eq $jvmPid})[0]
 $p=Get-Process -Id $jvmPid -ErrorAction Stop
 if($p.Path -ne $identity.executablePath -or [math]::Abs(($p.StartTime.ToUniversalTime()-[datetime]$identity.createdUtc).TotalMilliseconds) -gt 2){throw 'Own JVM identity changed'}
 $windows += [ordered]@{pid=$p.Id;createdUtc=$p.StartTime.ToUniversalTime().ToString('o');windowHandle=$p.MainWindowHandle.ToInt64();image=$identity.image}
}
[ordered]@{run=$RunId;utc=[DateTimeOffset]::UtcNow.ToString('o');windows=$windows} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $run 'window-process.json') -Encoding utf8
Write-Output "Recorded own window metadata: $RunId"
