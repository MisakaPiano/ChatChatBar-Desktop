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