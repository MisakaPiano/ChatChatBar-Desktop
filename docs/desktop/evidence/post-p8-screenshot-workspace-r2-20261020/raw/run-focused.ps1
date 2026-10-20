param([string]$Stage, [string[]]$Suites)
$ErrorActionPreference = 'Stop'
$root = 'H:\ChatChatBar-Desktop\app\desktopApp\build\post-p8-screenshot-workspace-r2-20261020'
$out = Join-Path $root $Stage
if (Test-Path -LiteralPath $out) { throw 'Stage already exists' }
New-Item -ItemType Directory -Path $out | Out-Null
$os = Get-CimInstance Win32_OperatingSystem
$disk = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='H:'"
@{utc=[DateTime]::UtcNow.ToString('o');freePhysicalKiB=$os.FreePhysicalMemory;freeDiskBytes=$disk.FreeSpace} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'resources.json') -Encoding utf8
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$arguments = @('--offline','--no-daemon','--no-parallel','--max-workers=1','--priority=low','--console=plain','--no-configuration-cache','-Pkotlin.compiler.execution.strategy=in-process',':desktopApp:compileKotlin',':desktopApp:test')
foreach ($suite in $Suites) { $arguments += @('--tests',"com.example.chatbar.desktop.$suite") }
$arguments | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'command.json') -Encoding utf8
$started = [DateTime]::UtcNow
$p = Start-Process -FilePath 'H:\ChatChatBar-Desktop\app\gradlew.bat' -ArgumentList $arguments -WorkingDirectory 'H:\ChatChatBar-Desktop\app' -WindowStyle Hidden -RedirectStandardOutput (Join-Path $out 'stdout.log') -RedirectStandardError (Join-Path $out 'stderr.log') -PassThru
$p.WaitForExit()
@{started=$started.ToString('o');finished=[DateTime]::UtcNow.ToString('o');exitCode=$p.ExitCode} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'result.json') -Encoding utf8
$freshXml = @(Get-ChildItem -LiteralPath 'H:\ChatChatBar-Desktop\app\desktopApp\build\test-results\test' -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTimeUtc -ge $started })
$freshXml | Copy-Item -Destination $out
@{onlyXmlWrittenAfterStageStart=$true;files=@($freshXml.Name)} | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $out 'xml-provenance.json') -Encoding utf8
Get-Content -LiteralPath (Join-Path $out 'result.json')
