$ErrorActionPreference = 'Stop'
$scope = 'H:\ChatChatBar-Desktop\app\desktopApp\build\post-p8-screenshot-workspace-r2-20261020'
$stage = Join-Path $scope '08-package'
if (Test-Path -LiteralPath $stage) { throw 'Package stage already exists' }
New-Item -ItemType Directory -Path $stage | Out-Null
$os = Get-CimInstance Win32_OperatingSystem
$disk = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='H:'"
@{utc=[DateTime]::UtcNow.ToString('o');freePhysicalKiB=$os.FreePhysicalMemory;freeDiskBytes=$disk.FreeSpace} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stage 'resources.json') -Encoding utf8
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$arguments = @('--offline','--no-daemon','--no-parallel','--max-workers=1','--priority=low','--console=plain','--no-configuration-cache','-Pkotlin.compiler.execution.strategy=in-process','-I',"$scope\package.init.gradle",':desktopApp:createDistributable')
$arguments | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stage 'command.json') -Encoding utf8
$started = [DateTime]::UtcNow
$p = Start-Process -FilePath 'H:\ChatChatBar-Desktop\app\gradlew.bat' -ArgumentList $arguments -WorkingDirectory 'H:\ChatChatBar-Desktop\app' -WindowStyle Hidden -RedirectStandardOutput (Join-Path $stage 'stdout.log') -RedirectStandardError (Join-Path $stage 'stderr.log') -PassThru
$p.WaitForExit()
@{started=$started.ToString('o');finished=[DateTime]::UtcNow.ToString('o');exitCode=$p.ExitCode;sourceCommit='3ece1366b142210f2db7c61a9c479cd156534b69';guiStarted=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stage 'result.json') -Encoding utf8
Get-Content -LiteralPath (Join-Path $stage 'result.json')
if ($p.ExitCode -ne 0) { throw 'Package build failed; logs preserved' }
