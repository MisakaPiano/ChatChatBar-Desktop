param(
    [Parameter(Mandatory = $true)][uint32] $ExpectedProcessId,
    [Parameter(Mandatory = $true)][long] $OwnerHandle,
    [Parameter(Mandatory = $true)][string] $SelectedPath,
    [Parameter(Mandatory = $true)][ValidateSet('save', 'close')][string] $Operation
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ownerTitle = 'CCB invitation dialog synthetic owner'
$dialogTitle = '保存邀请文件 · 请使用新文件名'

try {
    if ($env:GITHUB_ACTIONS -cne 'true' -or $env:CCB_WINDOWS_INVITE_DIALOG_PROOF -cne 'true') {
        throw 'INVITE_DIALOG_GRAPHICAL_CLOUD_REQUIRED'
    }
    $case = [IO.DirectoryInfo]::new([IO.Path]::GetDirectoryName($SelectedPath))
    $leaf = [IO.Path]::GetFileName($SelectedPath)
    if (-not [string]::Equals($SelectedPath, [IO.Path]::Combine($case.FullName, $leaf), [StringComparison]::OrdinalIgnoreCase) -or
        -not $case.Name.StartsWith('ccb-win-invite-dialog-', [StringComparison]::Ordinal) -or
        -not [string]::Equals($case.Parent.FullName, [IO.Path]::GetFullPath($env:RUNNER_TEMP).TrimEnd([char] '\'), [StringComparison]::OrdinalIgnoreCase) -or
        ($case.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        $leaf -cnotin @('synthetic-existing.ccbinvite', 'synthetic-new.ccbinvite')) {
        throw 'INVITE_DIALOG_DIRECTORY_SCOPE'
    }

    Add-Type -TypeDefinition @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class CcbInviteDialogNative {
    public delegate bool EnumProc(IntPtr window, IntPtr value);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc visit, IntPtr value);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr window);
    [DllImport("user32.dll")] public static extern bool IsWindowEnabled(IntPtr window);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window, out uint pid);
    [DllImport("user32.dll")] public static extern IntPtr GetWindow(IntPtr window, uint command);
    [DllImport("user32.dll")] public static extern IntPtr GetDlgItem(IntPtr dialog, int id);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr window, StringBuilder output, int count);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassName(IntPtr window, StringBuilder output, int count);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern bool PostMessage(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);
}
"@

    $owner = [IntPtr]::new($OwnerHandle)
    function Get-WindowProcess([IntPtr] $Window) {
        [uint32] $pidValue = 0
        [void] [CcbInviteDialogNative]::GetWindowThreadProcessId($Window, [ref] $pidValue)
        return $pidValue
    }
    function Get-WindowCaption([IntPtr] $Window) {
        $caption = [Text.StringBuilder]::new(256)
        [void] [CcbInviteDialogNative]::GetWindowText($Window, $caption, $caption.Capacity)
        return $caption.ToString()
    }
    function Test-OwnedDialog([IntPtr] $Window) {
        if ($Window -eq [IntPtr]::Zero -or -not [CcbInviteDialogNative]::IsWindowVisible($Window) -or
            (Get-WindowProcess $Window) -ne $ExpectedProcessId -or
            [CcbInviteDialogNative]::GetWindow($Window, 4) -ne $owner -or
            (Get-WindowCaption $Window) -cne $dialogTitle) { return $false }
        $class = [Text.StringBuilder]::new(64)
        [void] [CcbInviteDialogNative]::GetClassName($Window, $class, $class.Capacity)
        return $class.ToString() -ceq '#32770'
    }
    function Assert-Owner {
        if ($owner -eq [IntPtr]::Zero -or (Get-WindowProcess $owner) -ne $ExpectedProcessId -or
            -not [CcbInviteDialogNative]::IsWindowVisible($owner) -or (Get-WindowCaption $owner) -cne $ownerTitle) {
            throw 'INVITE_DIALOG_OWNER_MISMATCH'
        }
    }

    Assert-Owner
    $matches = [Collections.Generic.List[IntPtr]]::new()
    $visit = [CcbInviteDialogNative+EnumProc] {
        param([IntPtr] $window, [IntPtr] $unused)
        if (Test-OwnedDialog $window) { $matches.Add($window) }
        return $true
    }
    $wait = if ($Operation -ceq 'save') { 12 } else { 2 }
    $deadline = [Diagnostics.Stopwatch]::StartNew()
    do {
        $matches.Clear()
        if (-not [CcbInviteDialogNative]::EnumWindows($visit, [IntPtr]::Zero)) { throw 'INVITE_DIALOG_ENUMERATION_FAILED' }
        if ($matches.Count -gt 1) { throw 'INVITE_DIALOG_AMBIGUOUS' }
        if ($matches.Count -eq 1) { break }
        Start-Sleep -Milliseconds 100
    } while ($deadline.Elapsed.TotalSeconds -lt $wait)
    if ($matches.Count -eq 0) {
        if ($Operation -ceq 'close') {
            [Console]::WriteLine('INVITE_DIALOG_CLEANUP_COMPLETE')
            exit 0
        }
        throw 'INVITE_DIALOG_NOT_OBSERVED'
    }

    $dialog = $matches[0]
    Assert-Owner
    if (-not (Test-OwnedDialog $dialog)) { throw 'INVITE_DIALOG_TARGET_CHANGED' }
    if ($Operation -ceq 'close') {
        if (-not [CcbInviteDialogNative]::PostMessage($dialog, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero)) {
            throw 'INVITE_DIALOG_CLEANUP_REFUSED'
        }
        [Console]::WriteLine('INVITE_DIALOG_CLEANUP_COMPLETE')
        exit 0
    }

    Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes

    # IDOK is Windows' standard affirmative control. This does not dismiss any confirmation popup.
    $saveButton = [CcbInviteDialogNative]::GetDlgItem($dialog, 1)
    if ($saveButton -eq [IntPtr]::Zero -or (Get-WindowProcess $saveButton) -ne $ExpectedProcessId -or
        -not [CcbInviteDialogNative]::IsWindowEnabled($saveButton) -or -not [CcbInviteDialogNative]::IsWindowVisible($saveButton)) {
        throw 'INVITE_DIALOG_SAVE_CONTROL_UNAVAILABLE'
    }
    $save = [System.Windows.Automation.AutomationElement]::FromHandle($saveButton)
    $invoke = $null
    if (-not $save.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref] $invoke)) {
        throw 'INVITE_DIALOG_SAVE_CONTROL_UNAVAILABLE'
    }

    Assert-Owner
    if (-not (Test-OwnedDialog $dialog) -or [CcbInviteDialogNative]::GetDlgItem($dialog, 1) -ne $saveButton -or
        (Get-WindowProcess $saveButton) -ne $ExpectedProcessId -or -not [CcbInviteDialogNative]::IsWindowEnabled($saveButton) -or
        -not [CcbInviteDialogNative]::IsWindowVisible($saveButton)) { throw 'INVITE_DIALOG_TARGET_CHANGED' }
    $invoke.Invoke()
    [Console]::WriteLine('INVITE_DIALOG_SAVE_INVOKED')
} catch {
    $code = $_.Exception.Message
    if ($code -cnotmatch '^INVITE_DIALOG_[A-Z_]+$') { $code = 'INVITE_DIALOG_AUTOMATION_FAILED' }
    [Console]::Error.WriteLine($code)
    exit 2
}
