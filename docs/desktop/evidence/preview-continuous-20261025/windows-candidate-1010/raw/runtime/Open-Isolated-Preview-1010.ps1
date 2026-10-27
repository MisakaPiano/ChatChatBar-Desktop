param([switch]$PreflightOnly)
$ErrorActionPreference = 'Stop'

function Get-IsolatedSha256([string]$LiteralPath) {
    $stream = [IO.File]::OpenRead($LiteralPath)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
    finally { $algorithm.Dispose(); $stream.Dispose() }
}
$base = 'H:\ChatChatBar-Desktop\app\desktopApp\build\preview-continuous-private-20261025\windows-runtime-candidate-1010'
$owner = Get-Content -LiteralPath (Join-Path $base 'TASK_OWNER.json') -Raw | ConvertFrom-Json
if ($owner.kind -ne 'CCB_DESKTOP_SYNTHETIC_WINDOWS_RUNTIME' -or $owner.sourceCommit -ne '946c3791153634b0af65286313a497d141d5c598') { throw 'ISOLATED_PREVIEW_IDENTITY_MISMATCH' }
if ([IO.Path]::GetFullPath($owner.applicationImage) -ne 'I:\CCB-Desktop-Preview-Private-Candidate-1010-20261025\compose\binaries\main\app\ChatChatBarDesktop') { throw 'RETAINED_PROFILE_IMAGE_IDENTITY_MISMATCH' }
$image = [IO.Path]::GetFullPath('I:\CCB-Desktop-Preview-Private-Candidate-1010-20261025\compose\binaries\main\app\ChatChatBarDesktop')
$expected = 'I:\CCB-Desktop-Preview-Private-Candidate-1010-20261025\compose\binaries\main\app\ChatChatBarDesktop'
if ($image -ne $expected) { throw 'ISOLATED_IMAGE_PATH_MISMATCH' }
$inventory = Join-Path (Split-Path $base) 'windows-package-candidate-1010/PACKAGE_FILES.json'
$rows = Get-Content -LiteralPath $inventory -Raw | ConvertFrom-Json
if ($rows.Count -ne 217) { throw 'ISOLATED_PACKAGE_INVENTORY_MISMATCH' }
foreach ($row in $rows) {
    $file = [IO.Path]::GetFullPath((Join-Path $image $row.path))
    if (-not $file.StartsWith($image + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'PACKAGE_PATH_REFUSED' }
    $item = Get-Item -LiteralPath $file
    $cursor = $item
    while ($null -ne $cursor) {
        if (($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'PACKAGE_REPARSE_REFUSED' }
        $cursor = if ($cursor -is [IO.FileInfo]) { $cursor.Directory } else { $cursor.Parent }
    }
    if ($item.Length -ne $row.bytes -or (Get-IsolatedSha256 -LiteralPath $file) -ne $row.sha256) { throw 'PACKAGE_BYTES_CHANGED' }
}
foreach ($name in @('data','local-appdata','roaming','home','temp')) {
    $directory = Get-Item -LiteralPath (Join-Path $base $name)
    if (-not $directory.PSIsContainer -or ($directory.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'ISOLATED_PROFILE_REFUSED' }
}
$bootstrap = Join-Path $base 'local-appdata/ChatChatBarDesktop.bootstrap.json'
$bootstrapItem = Get-Item -LiteralPath $bootstrap
if ($bootstrapItem.PSIsContainer -or ($bootstrapItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'RETAINED_BOOTSTRAP_UNSAFE' }
$baseline = Get-Content -LiteralPath (Join-Path $base 'PRIVATE_BASELINE.json') -Raw | ConvertFrom-Json
if ((Get-IsolatedSha256 -LiteralPath $bootstrap) -ne $baseline.bootstrapSha256) { throw 'RETAINED_SYNTHETIC_BOOTSTRAP_CHANGED' }
if ($PreflightOnly) { Write-Output 'ISOLATED_PREVIEW_1010_READY_NO_APPLICATION_LAUNCHED'; exit 0 }
$start = New-Object Diagnostics.ProcessStartInfo
$start.FileName = Join-Path $image 'ChatChatBarDesktop.exe'
$start.WorkingDirectory = $image
$start.UseShellExecute = $false
$start.WindowStyle = [Diagnostics.ProcessWindowStyle]::Normal
$start.EnvironmentVariables['LOCALAPPDATA'] = Join-Path $base 'local-appdata'
$start.EnvironmentVariables['APPDATA'] = Join-Path $base 'roaming'
$start.EnvironmentVariables['TEMP'] = Join-Path $base 'temp'
$start.EnvironmentVariables['TMP'] = Join-Path $base 'temp'
$start.EnvironmentVariables.Remove('JDK_JAVA_OPTIONS')
$start.EnvironmentVariables.Remove('_JAVA_OPTIONS')
$start.EnvironmentVariables['JAVA_TOOL_OPTIONS'] = "-Duser.home=$(Join-Path $base 'home') -Djava.io.tmpdir=$(Join-Path $base 'temp') -Dfile.encoding=UTF-8"
$process = [Diagnostics.Process]::Start($start)
if ($null -eq $process) { throw 'ISOLATED_APPLICATION_NOT_STARTED' }
Write-Output 'ISOLATED_PREVIEW_1010_OPENED_FAKE_ACCOUNTS_CLOSE_WINDOW_NORMALLY'
