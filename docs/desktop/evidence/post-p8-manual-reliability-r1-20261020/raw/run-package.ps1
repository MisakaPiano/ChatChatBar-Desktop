$ErrorActionPreference = 'Stop'
$scope = 'H:\ChatChatBar-Desktop\app\desktopApp\build\post-p8-manual-reliability-r1-20261020'
$stage = Join-Path $scope '06-package'
if (Test-Path -LiteralPath $stage) { throw 'Package stage already exists' }
New-Item -ItemType Directory -Path $stage | Out-Null
$os = Get-CimInstance Win32_OperatingSystem
$mem = Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory
$disk = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='H:'"
@{utc=[DateTime]::UtcNow.ToString('o');freePhysicalKiB=$os.FreePhysicalMemory;committedBytes=$mem.CommittedBytes;commitLimit=$mem.CommitLimit;freeDiskBytes=$disk.FreeSpace;javaProcesses=@(Get-Process java,javaw -ErrorAction SilentlyContinue | Select-Object Id,ProcessName)} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $stage 'resources-before.json') -Encoding utf8
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$arguments = @('--offline','--no-daemon','--no-parallel','--max-workers=1','--priority=low','--console=plain','--no-configuration-cache','-Pkotlin.compiler.execution.strategy=in-process','-I',"$scope\package.init.gradle",':desktopApp:createDistributable')
$arguments | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stage 'command.json') -Encoding utf8
$start = [DateTime]::UtcNow
$process = Start-Process -FilePath 'H:\ChatChatBar-Desktop\app\gradlew.bat' -ArgumentList $arguments -WorkingDirectory 'H:\ChatChatBar-Desktop\app' -WindowStyle Hidden -RedirectStandardOutput (Join-Path $stage 'stdout.log') -RedirectStandardError (Join-Path $stage 'stderr.log') -PassThru
$process.WaitForExit()
@{started=$start.ToString('o');finished=[DateTime]::UtcNow.ToString('o');exitCode=$process.ExitCode;pid=$process.Id;source='60cc44d0159528d2e6824fcaf88ee384964da307';guiStarted=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stage 'result.json') -Encoding utf8
Get-Content -LiteralPath (Join-Path $stage 'result.json')
if ($process.ExitCode -ne 0) { throw 'Package build failed; raw logs preserved' }
