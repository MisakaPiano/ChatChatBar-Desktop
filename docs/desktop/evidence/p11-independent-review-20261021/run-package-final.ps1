param([string]$Stage = '07-windows-package')
$ErrorActionPreference = 'Stop'
$repo = 'H:\ChatChatBar-Desktop'
$scope = Join-Path $repo 'app\desktopApp\build\p11-review-fixes-20261021'
$stageRoot = Join-Path $scope $Stage
if (Test-Path -LiteralPath $stageRoot) { throw 'Package stage already exists' }
if ((git -C $repo branch --show-current) -ne 'feature/phase8-fish-audio') { throw 'Wrong branch' }
git -C $repo diff --quiet -- app .agents
if ($LASTEXITCODE -ne 0) { throw 'Uncommitted source edits' }
git -C $repo diff --cached --quiet -- app .agents
if ($LASTEXITCODE -ne 0) { throw 'Staged source edits' }
$sourceCommit = (git -C $repo rev-parse HEAD).Trim()
$build = Join-Path $scope ('package-' + $sourceCommit.Substring(0,8) + '-build')
if (Test-Path -LiteralPath $build) { throw 'Package build directory already exists' }
New-Item -ItemType Directory -Path $stageRoot | Out-Null
$init = Join-Path $stageRoot 'package.init.gradle'
$forward = $build.Replace('\','/')
@"
gradle.beforeProject { p ->
    if (p.path == ':desktopApp') p.layout.buildDirectory.set(new File('$forward'))
}
"@ | Set-Content -LiteralPath $init -Encoding utf8
$os = Get-CimInstance Win32_OperatingSystem
$disk = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='H:'"
@{utc=[DateTime]::UtcNow.ToString('o');freePhysicalKiB=$os.FreePhysicalMemory;freeDiskBytes=$disk.FreeSpace} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stageRoot 'resources.json') -Encoding utf8
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$arguments = @('--offline','--no-daemon','--no-parallel','--max-workers=1','--priority=low','--console=plain','--no-configuration-cache','-Pkotlin.compiler.execution.strategy=in-process','-I',$init,':desktopApp:createDistributable')
$arguments | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stageRoot 'command.json') -Encoding utf8
$started=[DateTime]::UtcNow
$p=Start-Process -FilePath (Join-Path $repo 'app\gradlew.bat') -ArgumentList $arguments -WorkingDirectory (Join-Path $repo 'app') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $stageRoot 'stdout.log') -RedirectStandardError (Join-Path $stageRoot 'stderr.log') -PassThru
$p.WaitForExit()
@{started=$started.ToString('o');finished=[DateTime]::UtcNow.ToString('o');exitCode=$p.ExitCode;sourceCommit=$sourceCommit;buildDirectory=$build;guiStarted=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stageRoot 'result.json') -Encoding utf8
Get-Content -LiteralPath (Join-Path $stageRoot 'result.json')
if ($p.ExitCode -ne 0) { throw 'Package build failed; raw output preserved' }
