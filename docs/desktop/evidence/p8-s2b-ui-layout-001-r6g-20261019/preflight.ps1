param([string]$Phase)
$ErrorActionPreference='Stop'
$report=[ordered]@{phase=$Phase;utc=[DateTimeOffset]::UtcNow.ToString('o')}
$report.branch=git branch --show-current
$report.refs=@(git rev-parse HEAD HEAD^ origin/feature/phase8-fish-audio desktop origin/desktop)
$report.live=@(git ls-remote origin refs/heads/feature/phase8-fish-audio refs/heads/desktop)
$report.status=@(git status --porcelain=v1 -uall)
$report.index=@(git diff --cached --name-only)
$report.untracked=@(git ls-files --others --exclude-standard)
$paths=@('app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopFishPickerPresentation.kt','app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopFishPickerWindow.kt','app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/DesktopFishPickerNativeUxTest.kt')
$expected=@('194d6b7fd2e270ca29eb90734303c8a7898825ecf8939cc60ab266e8d16c4917','a003e7ad1e31a0c356df1a4291cc7eb1e4eb9fa958a0a6353ee6a77ac8f88125','8beee5971355cd894b44a4743f89b6ad857c342274ae8410cfab5e99a3bbb423')
$report.source=@(for($i=0;$i -lt $paths.Count;$i++){[pscustomobject]@{path=$paths[$i];bytes=(Get-Item -LiteralPath $paths[$i]).Length;sha256=(Get-FileHash -LiteralPath $paths[$i] -Algorithm SHA256).Hash.ToLower();expected=$expected[$i]}})
$report.samples=@(for($i=0;$i -lt 3;$i++){
 $os=Get-CimInstance Win32_OperatingSystem
 $m=Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory
 [pscustomobject]@{utc=[DateTimeOffset]::UtcNow.ToString('o');availableBytes=[long]$os.FreePhysicalMemory*1024;commitHeadroomBytes=[long]$m.CommitLimit-[long]$m.CommittedBytes;pagesPerSecond=[long]$m.PagesPersec;java=@(Get-Process java,javaw -ErrorAction SilentlyContinue | Select-Object Id,ProcessName,StartTime,WorkingSet64,PrivateMemorySize64)}
 if($i -lt 2){Start-Sleep -Seconds 5}
})
$report.relevantProcesses=@(Get-Process | Where-Object {$_.ProcessName -match '^(java|javaw|ChatChatBar|gradle)$'} | Select-Object Id,ProcessName,StartTime,WorkingSet64)
$report.disk=Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='H:'" | Select-Object DeviceID,FreeSpace,Size
$report.protectedUserFiles='Paths only; no content/hash read, no mutation or staging. No user data root accessed.'
$report | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $PSScriptRoot "$Phase-preflight.json") -Encoding UTF8
$report | ConvertTo-Json -Depth 10
if(@($report.source | Where-Object {$_.sha256 -ne $_.expected}).Count){throw 'SOURCE IDENTITY MISMATCH'}
if($report.branch -ne 'feature/phase8-fish-audio' -or $report.refs[0] -ne '15d35ab8a46decdf710d09d12c9b0d3b87656444' -or $report.index.Count){throw 'GIT CHECKPOINT MISMATCH'}
