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
    [DllImport("user32.dll", CharSet=CharSet.Unicode, ExactSpelling=true)] private static extern bool EnumDisplaySettingsW(string device, int index, IntPtr mode);
    [DllImport("user32.dll", CharSet=CharSet.Unicode, ExactSpelling=true)] private static extern int ChangeDisplaySettingsExW(string device, IntPtr mode, IntPtr hwnd, uint flags, IntPtr parameters);
    [DllImport("user32.dll")] public static extern int GetSystemMetrics(int index);
    [DllImport("winmm.dll")] public static extern uint waveOutGetNumDevs();
    public sealed class Record {
        public Mode Public;
        public byte[] Bytes;
        public uint width { get { return Public.width; } }
        public uint height { get { return Public.height; } }
        public uint bitsPerPel { get { return Public.bitsPerPel; } }
    }
    public static Record Read(int index) {
        int publicSize = Marshal.SizeOf<Mode>();
        if (publicSize != 220) throw new InvalidOperationException("DISPLAY_PUBLIC_STRUCT_SIZE_" + publicSize);
        // A display driver may return private bytes after DEVMODEW. Preserve them instead of
        // refusing a valid mode or passing a nonzero dmDriverExtra with a too-small buffer.
        int capacity = publicSize + ushort.MaxValue;
        IntPtr pointer = Marshal.AllocHGlobal(capacity);
        try {
            Marshal.Copy(new byte[capacity], 0, pointer, capacity);
            Marshal.WriteInt16(pointer, 68, (short)publicSize);
            Marshal.WriteInt16(pointer, 70, unchecked((short)ushort.MaxValue));
            if (!EnumDisplaySettingsW(null, index, pointer)) throw new InvalidOperationException("DISPLAY_MODE_UNAVAILABLE_" + index);
            Mode mode = Marshal.PtrToStructure<Mode>(pointer);
            // Some display drivers return the older public prefix ending at frequency (188).
            // dmSize, not our maximum declaration, determines where private driver data starts.
            if (mode.size != 188 && mode.size != publicSize) throw new InvalidOperationException("DISPLAY_RETURNED_SIZE_" + mode.size + "_SUPPORTED_188_OR_220");
            byte[] bytes = new byte[mode.size + mode.driverExtra];
            Marshal.Copy(pointer, bytes, 0, bytes.Length);
            return new Record { Public=mode, Bytes=bytes };
        } finally { Marshal.FreeHGlobal(pointer); }
    }
    public static Record FromBytes(byte[] bytes) {
        if (bytes.Length < 188 || bytes.Length > 220 + ushort.MaxValue) throw new InvalidOperationException("DISPLAY_MODE_SIZE");
        int allocation = Math.Max(220, bytes.Length);
        var pointer = Marshal.AllocHGlobal(allocation);
        try {
            Marshal.Copy(new byte[allocation], 0, pointer, allocation);
            Marshal.Copy(bytes, 0, pointer, bytes.Length);
            Mode mode = Marshal.PtrToStructure<Mode>(pointer);
            if ((mode.size != 188 && mode.size != 220) || bytes.Length != mode.size + mode.driverExtra) throw new InvalidOperationException("DISPLAY_MODE_PRIVATE_SIZE");
            return new Record { Public=mode, Bytes=bytes };
        } finally { Marshal.FreeHGlobal(pointer); }
    }
    public static int Change(Record mode, uint flags) {
        var pointer = Marshal.AllocHGlobal(mode.Bytes.Length);
        try {
            Marshal.Copy(mode.Bytes, 0, pointer, mode.Bytes.Length);
            return ChangeDisplaySettingsExW(null, pointer, IntPtr.Zero, flags, IntPtr.Zero);
        } finally { Marshal.FreeHGlobal(pointer); }
    }
}
'@
$originalPath = Join-Path $evidence 'display-original.json'
if ($Restore) {
    if (!(Test-Path -LiteralPath $originalPath -PathType Leaf)) { throw 'ORIGINAL_PROBE_DISPLAY_RECORD_REQUIRED' }
    $saved = Get-Content -LiteralPath $originalPath -Raw | ConvertFrom-Json
    if ($saved.run -ne $env:GITHUB_RUN_ID) { throw 'PROBE_DISPLAY_RECORD_RUN_MISMATCH' }
    $mode = [CcbCloudDisplay]::FromBytes([Convert]::FromBase64String($saved.modeBytes))
    $result = [CcbCloudDisplay]::Change($mode, 0)
    @{ run=$env:GITHUB_RUN_ID; result=$result; width=[CcbCloudDisplay]::GetSystemMetrics(0); height=[CcbCloudDisplay]::GetSystemMetrics(1) } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'display-restored.json') -Encoding utf8
    if ($result -ne 0) { throw 'PROBE_DISPLAY_RESTORE_FAILED' }
    exit 0
}
$before = [CcbCloudDisplay]::Read(-1)
if (Test-Path -LiteralPath $originalPath) { throw 'PROBE_DISPLAY_ALREADY_PREPARED' }
@{ run=$env:GITHUB_RUN_ID; width=$before.width; height=$before.height; modeBytes=[Convert]::ToBase64String($before.Bytes) } |
    ConvertTo-Json | Set-Content -LiteralPath $originalPath -Encoding utf8
$modes = [Collections.Generic.List[object]]::new()
for ($index=0; $index -lt 4096; $index++) {
    try { $mode = [CcbCloudDisplay]::Read($index) } catch { break }
    if ($mode.width -ge 1600 -and $mode.height -ge 1000 -and $mode.width -le 2560 -and $mode.height -le 1600 -and $mode.bitsPerPel -eq 32) { $modes.Add($mode) }
}
$selected = $modes | Sort-Object @{Expression={ if ($_.width -eq 1920 -and $_.height -eq 1080) { 0 } else { 1 } }}, @{Expression={$_.width * $_.height}} | Select-Object -First 1
$testResult = $null; $applyResult = $null
if ($selected) {
    $testResult = [CcbCloudDisplay]::Change($selected, 2)
    if ($testResult -eq 0) { $applyResult = [CcbCloudDisplay]::Change($selected, 0) }
}
$deadline = [DateTime]::UtcNow.AddSeconds(5)
do {
    $width = [CcbCloudDisplay]::GetSystemMetrics(0); $height = [CcbCloudDisplay]::GetSystemMetrics(1)
    if ($width -ge 1600 -and $height -ge 1000) { break }
    Start-Sleep -Milliseconds 100
} while ([DateTime]::UtcNow -lt $deadline)
[ordered]@{ run=$env:GITHUB_RUN_ID; beforeWidth=$before.width; beforeHeight=$before.height; publicModeSize=$before.Public.size; privateModeBytes=$before.Public.driverExtra; width=$width; height=$height;
    supportedModes=$modes.Count; testResult=$testResult; applyResult=$applyResult; waveOutputDevices=[CcbCloudDisplay]::waveOutGetNumDevs();
    userInteractive=[Environment]::UserInteractive; processSession=(Get-Process -Id $PID).SessionId; registryUpdated=$false } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'display-and-audio.json') -Encoding utf8
if ($width -lt 1600 -or $height -lt 1000) { throw 'CLOUD_NATIVE_PROBE_DISPLAY_TOO_SMALL_NO_INPUT_RETRY' }
