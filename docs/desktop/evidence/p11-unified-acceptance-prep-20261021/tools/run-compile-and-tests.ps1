param([string]$Stage='02-service-regressions')
$ErrorActionPreference='Stop'
$repo='H:\ChatChatBar-Desktop'
$scope=Join-Path $repo 'app\desktopApp\build\p11-unified-acceptance-prep-20261021'
$out=Join-Path $scope $Stage
if(Test-Path -LiteralPath $out){throw 'Stage already exists'}
New-Item -ItemType Directory -Path $out | Out-Null
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$args=@('--offline','--no-daemon','--no-parallel','--max-workers=1','--priority=low','--console=plain','--no-configuration-cache','-Pkotlin.compiler.execution.strategy=in-process','-I',"$scope\harness.init.gradle",':desktopApp:exportUnifiedClasspath',':desktopApp:test')
foreach($suite in @('DesktopSaveSlotServiceTest','DesktopSaveSlotArchiveTest','DesktopFullBackupArchiveTest','DesktopFullBackupMappingTest','DesktopFullBackupServiceTest','DesktopFullBackupCommitJournalTest','DesktopSessionCommitJournalTest','DesktopStartupGateTest')){$args+=@('--tests',"com.example.chatbar.desktop.$suite")}
$args | ConvertTo-Json | Set-Content -LiteralPath "$out/command.json" -Encoding utf8
$started=[DateTime]::UtcNow
$p=Start-Process -FilePath "$repo\app\gradlew.bat" -ArgumentList $args -WorkingDirectory "$repo\app" -WindowStyle Hidden -RedirectStandardOutput "$out/stdout.log" -RedirectStandardError "$out/stderr.log" -PassThru
$p.WaitForExit()
@{exitCode=$p.ExitCode;started=$started.ToString('o');finished=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath "$out/result.json" -Encoding utf8
Get-ChildItem -LiteralPath "$repo/app/desktopApp/build/test-results/test" -Filter 'TEST-*.xml' | Where-Object {$_.LastWriteTimeUtc -ge $started} | Copy-Item -Destination $out
Get-Content -LiteralPath "$out/result.json"
