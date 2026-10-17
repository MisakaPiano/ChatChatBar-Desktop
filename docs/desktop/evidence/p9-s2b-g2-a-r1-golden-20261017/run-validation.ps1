param([string]$Stage,[string[]]$Tests)
$ErrorActionPreference='Stop'
$gate=Join-Path $PSScriptRoot $Stage
New-Item -ItemType Directory -Path $gate -ErrorAction Stop | Out-Null
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:CCB_G2_INSPECTOR_EVIDENCE=$gate
$gradleArgs=@('--offline','--no-daemon','--no-parallel','--max-workers=1','--priority=low','--console=plain','--no-configuration-cache','-Pkotlin.compiler.execution.strategy=in-process',':desktopApp:test')
foreach($test in $Tests){$gradleArgs+=@('--tests',$test)}
$command='.\gradlew.bat '+($gradleArgs -join ' ')
[IO.File]::WriteAllText((Join-Path $gate 'command.txt'),$command,[Text.UTF8Encoding]::new($false))
$started=[DateTimeOffset]::UtcNow
$process=Start-Process -FilePath $env:ComSpec -ArgumentList @('/d','/c',$command) -WorkingDirectory 'H:\ChatChatBar-Desktop\app' -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $gate 'gradle.stdout.log') -RedirectStandardError (Join-Path $gate 'gradle.stderr.log')
$identity=[pscustomobject]@{pid=$process.Id;startedUtc=$process.StartTime.ToUniversalTime().ToString('o');category='owned-gradle-command'}
$identity | ConvertTo-Json | Set-Content (Join-Path $gate 'process.json') -Encoding UTF8
do {
    $os=Get-CimInstance Win32_OperatingSystem
    $memory=Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory
    $java=@(Get-Process java,javaw -ErrorAction SilentlyContinue | ForEach-Object{[pscustomobject]@{pid=$_.Id;workingSetBytes=$_.WorkingSet64;privateBytes=$_.PrivateMemorySize64}})
    [pscustomobject]@{utc=[DateTimeOffset]::UtcNow.ToString('o');availableBytes=[long]$os.FreePhysicalMemory*1024;totalBytes=[long]$os.TotalVisibleMemorySize*1024;commitBytes=[long]$memory.CommittedBytes;commitLimitBytes=[long]$memory.CommitLimit;pagesPerSecond=[long]$memory.PagesPersec;java=$java} | ConvertTo-Json -Compress -Depth 5 | Add-Content (Join-Path $gate 'resources.jsonl') -Encoding UTF8
    if(!$process.HasExited){Start-Sleep -Seconds 10}
    $process.Refresh()
}while(!$process.HasExited)
$process.WaitForExit()
$exit=$process.ExitCode
$finished=[DateTimeOffset]::UtcNow
$xmlDirectory=Join-Path $gate 'junit'
New-Item -ItemType Directory -Path $xmlDirectory | Out-Null
Get-ChildItem 'H:\ChatChatBar-Desktop\app\desktopApp\build\test-results\test\TEST-*.xml' | Copy-Item -Destination $xmlDirectory
[pscustomobject]@{startedUtc=$started.ToString('o');finishedUtc=$finished.ToString('o');durationSeconds=($finished-$started).TotalSeconds;exitCode=$exit;command=$command;jdk='Temurin 17.0.20.1';gradle='9.1.0';heap='-Xmx2048m';tests=$Tests} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $gate 'stage.json') -Encoding UTF8
Write-Output "Stage $Stage exit=$exit durationSeconds=$(($finished-$started).TotalSeconds)"
Get-Content (Join-Path $gate 'gradle.stdout.log') -Tail 22
exit $exit
