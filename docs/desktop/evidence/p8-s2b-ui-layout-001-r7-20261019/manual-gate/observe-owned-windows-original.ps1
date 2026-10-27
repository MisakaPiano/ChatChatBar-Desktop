param([Parameter(Mandatory=$true)][string]$Checkpoint)
$ErrorActionPreference='Stop'
# R7-B only. Read-only geometry; never changes focus, size, cursor or input.
$launch=Join-Path $PSScriptRoot 'runs/manual01/launch.json'
if(-not (Test-Path -LiteralPath $launch)){throw 'R7 application has not been explicitly started'}
$exe=Join-Path $PSScriptRoot 'runtime-image/ChatChatBarDesktop.exe'
$owned=@(Get-CimInstance Win32_Process -Filter "Name='ChatChatBarDesktop.exe'" | Where-Object {$_.ExecutablePath -eq $exe} | ForEach-Object {[uint32]$_.ProcessId})
Add-Type @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class R7WindowReadOnly {
 public delegate bool Callback(IntPtr h,IntPtr l);
 [StructLayout(LayoutKind.Sequential)] public struct Rect{public int L,T,R,B;}
 [StructLayout(LayoutKind.Sequential)] public struct Point{public int X,Y;}
 [DllImport("user32.dll")] public static extern bool EnumWindows(Callback f,IntPtr l);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h,out Rect r);
 [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h,out Rect r);
 [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h,ref Point p);
 [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")] public static extern bool IsWindowEnabled(IntPtr h);
 [DllImport("user32.dll")] public static extern bool IsZoomed(IntPtr h);
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
 public static IntPtr[] All(){var h=new List<IntPtr>();EnumWindows((w,l)=>{h.Add(w);return true;},IntPtr.Zero);return h.ToArray();}
}
'@
$rows=@(foreach($handle in [R7WindowReadOnly]::All()){
 [uint32]$ownerPid=0; $null=[R7WindowReadOnly]::GetWindowThreadProcessId($handle,[ref]$ownerPid)
 if($owned -notcontains $ownerPid){continue}
 $rect=[R7WindowReadOnly+Rect]::new();$client=[R7WindowReadOnly+Rect]::new();$point=[R7WindowReadOnly+Point]::new()
 if(-not [R7WindowReadOnly]::GetWindowRect($handle,[ref]$rect)){continue}
 $null=[R7WindowReadOnly]::GetClientRect($handle,[ref]$client);$null=[R7WindowReadOnly]::ClientToScreen($handle,[ref]$point)
 $dpi=[R7WindowReadOnly]::GetDpiForWindow($handle)
 [ordered]@{pid=$ownerPid;hwnd=$handle.ToInt64();visible=[R7WindowReadOnly]::IsWindowVisible($handle);enabled=[R7WindowReadOnly]::IsWindowEnabled($handle);maximized=[R7WindowReadOnly]::IsZoomed($handle);foreground=([R7WindowReadOnly]::GetForegroundWindow() -eq $handle);frame=@($rect.L,$rect.T,$rect.R-$rect.L,$rect.B-$rect.T);client=@($point.X,$point.Y,$client.R-$client.L,$client.B-$client.T);dpi=$dpi;clientWidthDp=if($dpi){($client.R-$client.L)*96.0/$dpi}else{$null}}
})
[ordered]@{checkpoint=$Checkpoint;utc=[DateTimeOffset]::UtcNow.ToString('o');ownedWindows=$rows;scope='Read-only owned process geometry only; no other application contents or titles'} | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $PSScriptRoot "runs/manual01/$Checkpoint-windows.json") -Encoding utf8
