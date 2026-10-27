param([switch]$PreflightOnly)
$ErrorActionPreference = 'Stop'
$base = $PSScriptRoot
$owner = Get-Content -LiteralPath (Join-Path $base 'TASK_OWNER.json') -Raw | ConvertFrom-Json
if ($owner.kind -ne 'CCB_DESKTOP_SYNTHETIC_WINDOWS_RUNTIME' -or $owner.sourceCommit -ne '848abaa2b79ed30c4cadd91cfe0dece6ae193790') { throw 'ISOLATED_PREVIEW_IDENTITY_MISMATCH' }
$image = [IO.Path]::GetFullPath($owner.applicationImage)
$expected = 'H:\ChatChatBar-Desktop\app\desktopApp\build\preview-win-merge-candidate-1006\compose\binaries\main\app\ChatChatBarDesktopSynthetic'
if ($image -ne $expected) { throw 'ISOLATED_IMAGE_PATH_MISMATCH' }
$inventory = Join-Path (Split-Path $base) 'windows-package-merge-candidate-1006/PACKAGE_FILES.json'
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
    if ($item.Length -ne $row.bytes -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $row.sha256) { throw 'PACKAGE_BYTES_CHANGED' }
}
foreach ($name in @('data','local-appdata','roaming','home','temp')) {
    $directory = Get-Item -LiteralPath (Join-Path $base $name)
    if (-not $directory.PSIsContainer -or ($directory.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'ISOLATED_PROFILE_REFUSED' }
}
if ($PreflightOnly) { Write-Output 'ISOLATED_PREVIEW_1006_READY_NO_APPLICATION_LAUNCHED'; exit 0 }
$start = New-Object Diagnostics.ProcessStartInfo
$start.FileName = Join-Path $image 'ChatChatBarDesktopSynthetic.exe'
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
Write-Output 'ISOLATED_PREVIEW_1006_OPENED_FAKE_ACCOUNTS_CLOSE_WINDOW_NORMALLY'
