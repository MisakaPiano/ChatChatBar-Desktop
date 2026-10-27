$ErrorActionPreference = 'Stop'
$gate = $PSScriptRoot
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:CCB_G1A_EVIDENCE = $gate
$env:CCB_G0_EVIDENCE = $gate
Set-Location 'H:\ChatChatBar-Desktop\app'
$gradleArgs = @('--offline','--no-daemon','--no-parallel','--max-workers=1','--priority=low','--console=plain','--no-configuration-cache','-Pkotlin.compiler.execution.strategy=in-process',':desktopApp:test',
    '--tests','com.example.chatbar.desktop.DesktopStartupGateTest',
    '--tests','com.example.chatbar.desktop.DesktopApplicationLifecycleTest',
    '--tests','com.example.chatbar.desktop.DesktopProcessLifecycleTest',
    '--tests','com.example.chatbar.desktop.DesktopDataDirectoryTest',
    '--tests','com.example.chatbar.desktop.DesktopPortableRootTest',
    '--tests','com.example.chatbar.desktop.DesktopDataRootOwnershipTest',
    '--tests','com.example.chatbar.desktop.DesktopIndexStartupPreflightTest')
$priorXml = @(Get-ChildItem -LiteralPath 'H:\ChatChatBar-Desktop\app\desktopApp\build\test-results\test' -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue | ForEach-Object { [ordered]@{name=$_.Name;bytes=$_.Length;lastWriteUtc=$_.LastWriteTimeUtc.ToString('o');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLower()} })
ConvertTo-Json -InputObject $priorXml -Depth 5 | Set-Content -LiteralPath (Join-Path $gate 'preexisting-junit-inventory.json') -Encoding utf8
$ErrorActionPreference = 'Continue'
& "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | Tee-Object -FilePath (Join-Path $gate 'jdk-version.log')
$jdkExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if($jdkExit -ne 0){throw 'JDK version check failed'}
$started = [DateTimeOffset]::UtcNow
[ordered]@{startedUtc=$started.ToString('o');workingDirectory=(Get-Location).Path;JAVA_HOME=$env:JAVA_HOME;arguments=$gradleArgs;resourceHardGate='REMOVED by user';rerunTasks=$false;clean=$false} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $gate 'invocation.json') -Encoding utf8
$ErrorActionPreference = 'Continue'
& .\gradlew.bat @gradleArgs 2>&1 | Tee-Object -FilePath (Join-Path $gate '02-seven-class-validation.log')
$exitCode = $LASTEXITCODE
$ended = [DateTimeOffset]::UtcNow
$ErrorActionPreference = 'Stop'
$xmlDir=Join-Path $gate 'junit'
New-Item -ItemType Directory -Path $xmlDir -ErrorAction Stop | Out-Null
$copied=@()
Get-ChildItem -LiteralPath 'H:\ChatChatBar-Desktop\app\desktopApp\build\test-results\test' -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTimeUtc -ge $started.UtcDateTime } | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $xmlDir $_.Name) -ErrorAction Stop
    $copied += [ordered]@{name=$_.Name;bytes=$_.Length;lastWriteUtc=$_.LastWriteTimeUtc.ToString('o');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLower()}
}
[ordered]@{startedUtc=$started.ToString('o');endedUtc=$ended.ToString('o');durationSeconds=($ended-$started).TotalSeconds;exitCode=$exitCode;arguments=$gradleArgs;copiedFreshJUnitXml=$copied} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $gate '02-seven-class-validation-result.json') -Encoding utf8
Write-Output "G1B_A_EXIT_CODE=$exitCode"
exit $exitCode
