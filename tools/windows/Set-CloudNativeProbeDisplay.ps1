param([switch]$Restore)
$ErrorActionPreference = 'Stop'
# Never run native display code on the user's machine. This helper is cloud-test infrastructure.
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_OS -ne 'Windows' -or
    $env:GITHUB_REPOSITORY -ne 'MisakaPiano/ChatChatBar-Desktop' -or !$env:RUNNER_TEMP) {
    throw 'OWNED_GITHUB_WINDOWS_RUNNER_REQUIRED'
}
$evidence = [IO.Path]::GetFullPath((Join-Path $env:RUNNER_TEMP 'ccb-native-probe'))
if (![IO.Directory]::Exists($evidence) -or [IO.File]::GetAttributes($evidence).HasFlag([IO.FileAttributes]::ReparsePoint)) {
    throw 'OWNED_PROBE_EVIDENCE_DIRECTORY_REQUIRED'
}
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class CcbCloudDisplay {
    // DEVMODEW display union, from the Win32 SDK, with all public fields preserved.
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
    public struct Mode {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string deviceName;
        public ushort specVersion, driverVersion, size, driverExtra;
        public uint fields;
        public int positionX, positionY;
        public uint displayOrientation, displayFixedOutput;
        public short color, duplex, yResolution, ttOption, collate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string formName;
        public ushort logPixels;
        public uint bitsPerPel, width, height, displayFlags, frequency;
        public uint icmMethod, icmIntent, mediaType, ditherType, reserved1, reserved2, panningWidth, panningHeight;
    }
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern bool EnumDisplaySettingsW(string device, int index, ref Mode mode);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int ChangeDisplaySettingsExW(string device, ref Mode mode, IntPtr hwnd, uint flags, IntPtr parameters);
    [DllImport("user32.dll")] public static extern int GetSystemMetrics(int index);
    [DllImport("winmm.dll")] public static extern uint waveOutGetNumDevs();
    public static Mode Read(int index) {
        var mode = new Mode(); mode.size = (ushort)Marshal.SizeOf<Mode>();
        if (!EnumDisplaySettingsW(null, index, ref mode)) throw new InvalidOperationException("DISPLAY_MODE_UNAVAILABLE");
        if (mode.size != 220 || mode.driverExtra != 0) throw new InvalidOperationException("DISPLAY_MODE_LAYOUT_UNSUPPORTED");
        return mode;
    }
    public static byte[] Bytes(Mode mode) {
        var pointer = Marshal.AllocHGlobal(220);
        try { Marshal.StructureToPtr(mode, pointer, false); var bytes = new byte[220]; Marshal.Copy(pointer, bytes, 0, 220); return bytes; }
        finally { Marshal.FreeHGlobal(pointer); }
    }
    public static Mode FromBytes(byte[] bytes) {
        if (bytes.Length != 220) throw new InvalidOperationException("DISPLAY_MODE_SIZE");
        var pointer = Marshal.AllocHGlobal(220);
        try { Marshal.Copy(bytes, 0, pointer, 220); return Marshal.PtrToStructure<Mode>(pointer); }
        finally { Marshal.FreeHGlobal(pointer); }
    }
}
'@
$originalPath = Join-Path $evidence 'display-original.json'
if ($Restore) {
    if (!(Test-Path -LiteralPath $originalPath -PathType Leaf)) { throw 'ORIGINAL_PROBE_DISPLAY_RECORD_REQUIRED' }
    $saved = Get-Content -LiteralPath $originalPath -Raw | ConvertFrom-Json
    if ($saved.run -ne $env:GITHUB_RUN_ID) { throw 'PROBE_DISPLAY_RECORD_RUN_MISMATCH' }
    $mode = [CcbCloudDisplay]::FromBytes([Convert]::FromBase64String($saved.modeBytes))
    $result = [CcbCloudDisplay]::ChangeDisplaySettingsExW($null, [ref]$mode, [IntPtr]::Zero, 0, [IntPtr]::Zero)
    @{ run=$env:GITHUB_RUN_ID; result=$result; width=[CcbCloudDisplay]::GetSystemMetrics(0); height=[CcbCloudDisplay]::GetSystemMetrics(1) } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'display-restored.json') -Encoding utf8
    if ($result -ne 0) { throw 'PROBE_DISPLAY_RESTORE_FAILED' }
    exit 0
}
$before = [CcbCloudDisplay]::Read(-1)
if (Test-Path -LiteralPath $originalPath) { throw 'PROBE_DISPLAY_ALREADY_PREPARED' }
@{ run=$env:GITHUB_RUN_ID; width=$before.width; height=$before.height; modeBytes=[Convert]::ToBase64String([CcbCloudDisplay]::Bytes($before)) } |
    ConvertTo-Json | Set-Content -LiteralPath $originalPath -Encoding utf8
$modes = [Collections.Generic.List[object]]::new()
for ($index=0; $index -lt 4096; $index++) {
    try { $mode = [CcbCloudDisplay]::Read($index) } catch { break }
    if ($mode.width -ge 1600 -and $mode.height -ge 1000 -and $mode.width -le 2560 -and $mode.height -le 1600 -and $mode.bitsPerPel -eq 32) { $modes.Add($mode) }
}
$selected = $modes | Sort-Object @{Expression={ if ($_.width -eq 1920 -and $_.height -eq 1080) { 0 } else { 1 } }}, @{Expression={$_.width * $_.height}} | Select-Object -First 1
$testResult = $null; $applyResult = $null
if ($selected) {
    [CcbCloudDisplay+Mode]$selectedMode = $selected
    $testResult = [CcbCloudDisplay]::ChangeDisplaySettingsExW($null, [ref]$selectedMode, [IntPtr]::Zero, 2, [IntPtr]::Zero)
    if ($testResult -eq 0) { $applyResult = [CcbCloudDisplay]::ChangeDisplaySettingsExW($null, [ref]$selectedMode, [IntPtr]::Zero, 0, [IntPtr]::Zero) }
}
$deadline = [DateTime]::UtcNow.AddSeconds(5)
do {
    $width = [CcbCloudDisplay]::GetSystemMetrics(0); $height = [CcbCloudDisplay]::GetSystemMetrics(1)
    if ($width -ge 1600 -and $height -ge 1000) { break }
    Start-Sleep -Milliseconds 100
} while ([DateTime]::UtcNow -lt $deadline)
[ordered]@{ run=$env:GITHUB_RUN_ID; beforeWidth=$before.width; beforeHeight=$before.height; width=$width; height=$height;
    supportedModes=$modes.Count; testResult=$testResult; applyResult=$applyResult; waveOutputDevices=[CcbCloudDisplay]::waveOutGetNumDevs();
    userInteractive=[Environment]::UserInteractive; processSession=(Get-Process -Id $PID).SessionId; registryUpdated=$false } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'display-and-audio.json') -Encoding utf8
if ($width -lt 1600 -or $height -lt 1000) { throw 'CLOUD_NATIVE_PROBE_DISPLAY_TOO_SMALL_NO_INPUT_RETRY' }
