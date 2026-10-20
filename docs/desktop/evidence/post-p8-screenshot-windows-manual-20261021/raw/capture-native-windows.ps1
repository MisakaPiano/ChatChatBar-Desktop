param([Parameter(Mandatory=$true)][string]$Label,[string]$RunId='manual01')
$ErrorActionPreference='Stop'
if($Label -notmatch '^[a-zA-Z0-9_-]{1,80}$'){throw 'Unsafe evidence label'}
$run=Join-Path $PSScriptRoot "runs/$RunId"
$launch=Get-Content -LiteralPath (Join-Path $run 'launch.json') -Raw | ConvertFrom-Json
$ownedPids=[System.Collections.Generic.HashSet[uint32]]::new()
$null=$ownedPids.Add([uint32]$launch.launcherPid)
$observations=Join-Path $run 'process-observations.jsonl'
if(Test-Path -LiteralPath $observations){$last=Get-Content -LiteralPath $observations -Tail 1 | ConvertFrom-Json;foreach($p in $last.alivePids){$null=$ownedPids.Add([uint32]$p)}}
Add-Type @'
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class ScreenshotWindowEvidence {
 public delegate bool Callback(IntPtr window,IntPtr context);
 [DllImport("user32.dll")] static extern bool EnumWindows(Callback callback,IntPtr context);
 [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window,out uint pid);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr window,StringBuilder text,int count);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr window,StringBuilder text,int count);
 [DllImport("user32.dll")] static extern IntPtr GetWindow(IntPtr window,uint command);
 [DllImport("user32.dll")] static extern IntPtr GetAncestor(IntPtr window,uint command);
 [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr window);
 [DllImport("user32.dll")] static extern bool IsWindowEnabled(IntPtr window);
 [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr window,out Rect rect);
 [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
 [StructLayout(LayoutKind.Sequential)] public struct Rect {public int Left,Top,Right,Bottom;}
 public sealed class Row {public long hwnd,owner,rootOwner;public uint pid;public string title,className;public bool visible,enabled,foreground;public Rect bounds;}
 public static Row[] Read(HashSet<uint> owned){
  var rows=new List<Row>();var foreground=GetForegroundWindow();
  EnumWindows((h,c)=>{uint pid;GetWindowThreadProcessId(h,out pid);if(!owned.Contains(pid))return true;
   var title=new StringBuilder(512);var cls=new StringBuilder(128);GetWindowText(h,title,title.Capacity);GetClassName(h,cls,cls.Capacity);Rect r;GetWindowRect(h,out r);
   rows.Add(new Row{hwnd=h.ToInt64(),owner=GetWindow(h,4).ToInt64(),rootOwner=GetAncestor(h,3).ToInt64(),pid=pid,title=title.ToString(),className=cls.ToString(),visible=IsWindowVisible(h),enabled=IsWindowEnabled(h),foreground=h==foreground,bounds=r});return true;},IntPtr.Zero);
  return rows.ToArray();
 }
}
'@
$windows=[ScreenshotWindowEvidence]::Read($ownedPids)
$trace=Join-Path $run 'stderr.log'
$closeEvents=if(Test-Path -LiteralPath $trace){@(Select-String -LiteralPath $trace -Pattern '^CCB_SHUTDOWN .* CLOSE_REQUESTED$' | ForEach-Object {$_.Line})}else{@()}
$output=Join-Path $run "$Label.native-windows.json"
if(Test-Path -LiteralPath $output){throw 'Evidence label already used'}
[ordered]@{utc=[DateTime]::UtcNow.ToString('o');label=$Label;ownedPids=@($ownedPids);windows=$windows;mainCloseRequests=$closeEvents;method='Read-only EnumWindows owner/rect snapshots plus unmodified Main shutdown trace; no WM_CLOSE interception';userResult='NOT TESTED unless separately reported'} | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $output -Encoding utf8
Get-Content -LiteralPath $output
