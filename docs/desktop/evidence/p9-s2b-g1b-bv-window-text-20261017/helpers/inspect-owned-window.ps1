param([Parameter(Mandatory=$true)][string]$RunId)
$ErrorActionPreference='Stop'
$old=Join-Path $PSScriptRoot '../g1b-b-packaged-20261016-01'
$run=Join-Path $old "runs/$RunId"
$last=Get-Content -LiteralPath (Join-Path $run 'process-observations.jsonl') -Tail 1 | ConvertFrom-Json -DateKind String
$rows=@()
foreach($identity in $last.processes){
 try{$p=Get-Process -Id $identity.pid -ErrorAction Stop}catch{continue}
 $expectedUtc=[DateTimeOffset]::Parse($identity.createdUtc).UtcDateTime
 if($p.Path -ne $identity.executablePath -or [math]::Abs(($p.StartTime.ToUniversalTime()-$expectedUtc).TotalMilliseconds) -gt 2){throw 'Owned process identity mismatch'}
 $rows+=[ordered]@{pid=$p.Id;parentPid=$identity.parentPid;createdUtc=$p.StartTime.ToUniversalTime().ToString('o');path=$p.Path;windowHandle=$p.MainWindowHandle.ToInt64();jvmLoaded=(@($p.Modules | ForEach-Object {$_.ModuleName}) -contains 'jvm.dll')}
}
[ordered]@{run=$RunId;utc=[DateTimeOffset]::UtcNow.ToString('o');jsonDateKind='String';processes=$rows} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $PSScriptRoot "$RunId-owned-window.json") -Encoding utf8
Write-Output "Saved owned process/window metadata: $RunId"
