$ErrorActionPreference='Stop'
$gate=$PSScriptRoot
$sampleNumber=0
while($true) {
    $sampleNumber++
    try {
        $os=Get-CimInstance Win32_OperatingSystem
        $mem=Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory
        $javaRows=@(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe' OR Name='jpackage.exe' OR Name='jlink.exe'" | ForEach-Object {
            $category=if($_.CommandLine -match 'GradleDaemon|GradleWrapperMain|GradleWorkerMain'){'GRADLE_BUILD'}elseif($_.CommandLine -match 'KotlinCompileDaemon'){'KOTLIN_BUILD'}elseif($_.Name -match 'jpackage|jlink'){'PACKAGE_BUILD'}else{'JAVA_OTHER_OR_UNKNOWN'}
            [ordered]@{pid=$_.ProcessId;ppid=$_.ParentProcessId;name=$_.Name;createdUtc=$_.CreationDate.ToUniversalTime().ToString('o');category=$category;workingSetBytes=[long]$_.WorkingSetSize;privateBytes=[long]$_.PrivatePageCount}
        })
        $row=[ordered]@{sample=$sampleNumber;utc=[DateTimeOffset]::UtcNow.ToString('o');availablePhysicalBytes=[long]$os.FreePhysicalMemory*1024;totalPhysicalBytes=[long]$os.TotalVisibleMemorySize*1024;committedBytes=[long]$mem.CommittedBytes;commitLimitBytes=[long]$mem.CommitLimit;commitHeadroomBytes=([long]$mem.CommitLimit-[long]$mem.CommittedBytes);pagesPerSecond=[long]$mem.PagesPersec;pagesInputPerSecond=[long]$mem.PagesInputPersec;pagesOutputPerSecond=[long]$mem.PagesOutputPersec;javaProcesses=$javaRows}
        $row | ConvertTo-Json -Depth 6 -Compress | Add-Content -LiteralPath (Join-Path $gate 'resources.jsonl') -Encoding utf8
        [ordered]@{sample=$sampleNumber;availableGiB=[math]::Round($row.availablePhysicalBytes/1GB,3);commitHeadroomGiB=[math]::Round($row.commitHeadroomBytes/1GB,3);pagesPerSecond=$row.pagesPerSecond;javaCount=$javaRows.Count} | ConvertTo-Json -Compress | Write-Output
    } catch {
        [ordered]@{sample=$sampleNumber;utc=[DateTimeOffset]::UtcNow.ToString('o');samplingFailure=$_.Exception.GetType().FullName} | ConvertTo-Json -Compress | Add-Content -LiteralPath (Join-Path $gate 'resources.jsonl') -Encoding utf8
    }
    if(Test-Path -LiteralPath (Join-Path $gate '01-seven-class-validation-result.json')){break}
    Start-Sleep -Seconds 10
}
