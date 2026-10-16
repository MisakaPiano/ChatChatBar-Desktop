$ErrorActionPreference='Stop'
$gate=$PSScriptRoot
$env:JAVA_HOME='<JDK17>'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
Set-Location '<WORKSPACE>\app'
$gradleArgs=@('--offline','--no-daemon','--no-parallel','--max-workers=1','--priority=low','--console=plain','--no-configuration-cache','-Pkotlin.compiler.execution.strategy=in-process',':desktopApp:createDistributable')
$started=[DateTimeOffset]::UtcNow
[ordered]@{startedUtc=$started.ToString('o');sourceHead='5294cf4502d91f810f8ad6b2b6f7d2ae2eeccd03';productionSource='a8990b34073647169e7e1a62b2d0e969323cddf6';arguments=$gradleArgs;workingDirectory=(Get-Location).Path} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $gate 'build-invocation.json') -Encoding utf8
$ErrorActionPreference='Continue'
& .\gradlew.bat @gradleArgs 2>&1 | Tee-Object -FilePath (Join-Path $gate 'build.log')
$exitCode=$LASTEXITCODE
$ended=[DateTimeOffset]::UtcNow
[ordered]@{startedUtc=$started.ToString('o');endedUtc=$ended.ToString('o');durationSeconds=($ended-$started).TotalSeconds;exitCode=$exitCode;arguments=$gradleArgs} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $gate 'build-result.json') -Encoding utf8
exit $exitCode
