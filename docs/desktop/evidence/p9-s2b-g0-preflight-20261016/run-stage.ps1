param([string]$Stage, [string[]]$Tasks)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-Location 'H:\ChatChatBar-Desktop\app'
$gate = 'H:\ChatChatBar-Desktop\app\desktopApp\build\p9-s2b-g0-preflight-20261016'
$arguments = @('--offline','--no-daemon','--no-parallel','--max-workers=1','--priority=low','--console=plain','--no-configuration-cache','-Pkotlin.compiler.execution.strategy=in-process') + $Tasks
$env:CCB_G0_EVIDENCE = $gate
$started = Get-Date
& .\gradlew.bat @arguments 2>&1 | Tee-Object -FilePath "$gate\$Stage.log"
$result = $LASTEXITCODE
[ordered]@{stage=$Stage;started=$started.ToString('o');ended=(Get-Date).ToString('o');seconds=((Get-Date)-$started).TotalSeconds;arguments=$arguments;exitCode=$result} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$gate\$Stage.json" -Encoding utf8
exit $result

