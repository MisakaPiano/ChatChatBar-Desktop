// Stable, current-user entry point. Program ownership never grants data ownership.
using System;
using System.IO;
using System.Linq;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

internal static class Launcher {
#if SYNTHETIC
    const string Product = "ccb-desktop-nsis-synthetic-v1";
    const string Label = "ChatChatBar Desktop NSIS Synthetic";
#else
    const string Product = "ccb-desktop-nsis-v1";
    const string Label = "ChatChatBar Desktop";
#endif
    internal sealed class Entry { public string path; public string sha256; public long bytes; }
    internal sealed class Manifest { public string product; public string version; public string commit; public Entry[] files; }
    static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = 2097152 };
    static string Root;
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern SafeFileHandle CreateFile(string path, uint access, uint share, IntPtr security, uint creation, uint flags, IntPtr template);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool SetFileInformationByHandle(SafeFileHandle file, int kind, ref byte value, uint size);
    // Pin every parent against rename/reparse replacement; digest and deletion use one file handle.
    static bool DeleteOwned(string path, long bytes, string digest) {
        var parents = new System.Collections.Generic.List<SafeFileHandle>();
        try {
            var chain = new System.Collections.Generic.Stack<string>();
            for (var d = Path.GetDirectoryName(path); !String.IsNullOrEmpty(d); d = Path.GetDirectoryName(d)) chain.Push(d);
            foreach (var d in chain) {
                var h = CreateFile(d, 0x80, 1, IntPtr.Zero, 3, 0x02200000, IntPtr.Zero);
                Require(!h.IsInvalid, "PARENT_BUSY"); parents.Add(h); Safe(d);
            }
            var handle = CreateFile(path, 0x80010000, 1, IntPtr.Zero, 3, 0x00200000, IntPtr.Zero);
            Require(!handle.IsInvalid, "FILE_BUSY");
            using (handle) using (var stream = new FileStream(handle, FileAccess.Read)) using (var hash = SHA256.Create()) {
                Safe(path);
                if (stream.Length != bytes || BitConverter.ToString(hash.ComputeHash(stream)).Replace("-", "").ToLowerInvariant() != digest) return false;
                byte disposition = 1;
                Require(SetFileInformationByHandle(handle, 4, ref disposition, 1), "FILE_DELETE_DEFERRED");
                return true;
            }
        } finally { foreach (var h in parents) h.Dispose(); }
    }
    static string Hash(string p) { using (var f = File.OpenRead(p)) using (var h = SHA256.Create()) return BitConverter.ToString(h.ComputeHash(f)).Replace("-", "").ToLowerInvariant(); }
    static void Require(bool ok, string code) { if (!ok) throw new IOException(code); }
    static string Safe(string p) {
        p = Path.GetFullPath(p);
        for (var v = p; !String.IsNullOrEmpty(v); v = Path.GetDirectoryName(v))
            if (Directory.Exists(v) || File.Exists(v)) Require((File.GetAttributes(v) & FileAttributes.ReparsePoint) == 0, "REPARSE_REFUSED");
        return p;
    }
    static string Child(string parent, string relative) {
        Require(!String.IsNullOrEmpty(relative) && !Path.IsPathRooted(relative) && !relative.Contains(":") &&
            !relative.Split('/', '\\').Any(x => x == ".." || x == "." || x.Length == 0), "PATH_REFUSED");
        var p = Safe(Path.Combine(parent, relative.Replace('/', '\\')));
        Require(p.StartsWith(parent.TrimEnd('\\') + "\\", StringComparison.OrdinalIgnoreCase), "PATH_ESCAPE");
        return p;
    }
    static void Atomic(string p, string text) {
        var tmp = p + "." + Guid.NewGuid().ToString("N") + ".tmp";
        using (var f = new FileStream(tmp, FileMode.CreateNew, FileAccess.Write, FileShare.None)) {
            var b = new UTF8Encoding(false).GetBytes(text); f.Write(b, 0, b.Length); f.Flush(true);
        }
        if (File.Exists(p)) File.Replace(tmp, p, null); else File.Move(tmp, p);
    }
    static void Owned() { Safe(Root); Require(File.ReadAllText(Path.Combine(Root, "install-owner.txt")) == Product, "FOREIGN_INSTALL_ROOT"); }
    static string VersionRoot(string id) {
        Require(Regex.IsMatch(id ?? "", @"^[0-9]+\.[0-9]+\.[0-9]+-preview\.[1-9][0-9]*-[a-f0-9]{12}$"), "VERSION_ID_REFUSED");
        return Child(Root, "versions/" + id);
    }
    static Manifest ReadManifest(string directory) {
        var file = Child(directory, "install-manifest.json");
        Require(new FileInfo(file).Length <= 2097152, "MANIFEST_LIMIT");
        var m = Json.Deserialize<Manifest>(File.ReadAllText(file));
        Require(m.product == Product && m.files != null && m.files.Length > 0 && m.files.Length <= 5000 &&
            Regex.IsMatch(m.commit ?? "", "^[a-f0-9]{40}$"), "MANIFEST_REFUSED");
        Require(m.files.Select(x => x.path.ToLowerInvariant()).Distinct().Count() == m.files.Length, "DUPLICATE_FILE");
        foreach (var e in m.files) { Child(directory, e.path); Require(Regex.IsMatch(e.sha256 ?? "", "^[a-f0-9]{64}$") && e.bytes >= 0, "ENTRY_REFUSED"); }
        return m;
    }
    static void Verify(string directory, Manifest m) {
        foreach (var e in m.files) { var p = Child(directory, e.path); Require(new FileInfo(p).Length == e.bytes && Hash(p) == e.sha256, "PROGRAM_CHANGED"); }
        Require(!File.Exists(Path.Combine(directory, "portable.flag")) && !Directory.Exists(Path.Combine(directory, "UserData")), "INSTALLED_PORTABLE_REFUSED");
    }
    static void Event(string value) { File.AppendAllText(Path.Combine(Root, "lifecycle.log"), DateTime.UtcNow.ToString("o") + " " + value + "\r\n"); }
    static string Current() { return File.ReadAllText(Child(Root, "current.txt")).Trim(); }
    static void Prepare() {
        Safe(Root);
        if (Directory.Exists(Root) && Directory.EnumerateFileSystemEntries(Root).Any()) Owned();
        else { Directory.CreateDirectory(Root); Atomic(Path.Combine(Root, "install-owner.txt"), Product); }
        // A new installer may not silently replace an incompatible stable entry point.
        var stable = Path.Combine(Root, "ChatChatBar.exe");
        var resourceReceipt = Path.Combine(Root, "generated-resources.json");
        if (File.Exists(stable)) Require(File.Exists(resourceReceipt), "STABLE_LAUNCHER_UNOWNED");
        if (File.Exists(resourceReceipt)) {
            var entries = Json.Deserialize<Entry[]>(File.ReadAllText(resourceReceipt));
            Require(entries.Length == 2 && entries.Select(e => e.path).OrderBy(x => x).SequenceEqual(new[] { "ChatChatBar.exe", "Uninstall.exe" }), "RESOURCE_RECEIPT_REFUSED");
            foreach (var e in entries) Require(Hash(Child(Root, e.path)) == e.sha256, "GENERATED_RESOURCE_CHANGED");
        }
        var versions = Path.Combine(Root, "versions");
        if (Directory.Exists(versions)) foreach (var directory in Directory.GetDirectories(versions)) {
            var manifest = Child(directory, "install-manifest.json");
            if (File.Exists(manifest)) using (File.Open(manifest, FileMode.Open, FileAccess.Read, FileShare.None)) {}
        }
        var drive = new DriveInfo(Path.GetPathRoot(Root));
        Require(drive.DriveType != DriveType.Network && drive.DriveFormat == "NTFS", "INSTALL_REQUIRES_LOCAL_NTFS");
        Require(drive.AvailableFreeSpace > 805306368, "INSTALL_SPACE_REQUIRED");
    }
    static void Shortcut(string path, bool remove) {
        Safe(Path.GetDirectoryName(path));
        var type = Type.GetTypeFromProgID("WScript.Shell");
        var shell = Activator.CreateInstance(type);
        var link = type.InvokeMember("CreateShortcut", System.Reflection.BindingFlags.InvokeMethod, null, shell, new object[] { path });
        var target = Path.Combine(Root, "ChatChatBar.exe");
        var lt = link.GetType();
        if (File.Exists(path)) {
            var old = (string)lt.InvokeMember("TargetPath", System.Reflection.BindingFlags.GetProperty, null, link, null);
            Require(String.Equals(old, target, StringComparison.OrdinalIgnoreCase), "FOREIGN_SHORTCUT");
        }
        if (remove) { if (File.Exists(path)) File.Delete(path); return; }
        Directory.CreateDirectory(Path.GetDirectoryName(path));
        foreach (var item in new[] { new[] { "TargetPath", target }, new[] { "WorkingDirectory", Root }, new[] { "Description", Label } })
            lt.InvokeMember(item[0], System.Reflection.BindingFlags.SetProperty, null, link, new object[] { item[1] });
        lt.InvokeMember("Save", System.Reflection.BindingFlags.InvokeMethod, null, link, null);
    }
    static void Shortcuts(bool remove) {
        var desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
        var programs = Environment.GetFolderPath(Environment.SpecialFolder.Programs);
#if SYNTHETIC
        var fixture = Environment.GetEnvironmentVariable("CCB_DESKTOP_TEST_SHELL_ROOT");
        if (!String.IsNullOrEmpty(fixture)) { desktop = Safe(Path.Combine(fixture, "Desktop")); programs = Safe(Path.Combine(fixture, "Programs")); }
#endif
        Shortcut(Path.Combine(desktop, Label + ".lnk"), remove);
        Shortcut(Path.Combine(programs, Label + ".lnk"), remove);
    }
    static void Cleanup(string keep, string only = null) {
        bool deferred = false;
        var versions = Child(Root, "versions");
        foreach (var dir in Directory.GetDirectories(versions)) {
            if (Path.GetFileName(dir) == keep) continue;
            if (only != null && Path.GetFileName(dir) != only) continue;
            try {
                VersionRoot(Path.GetFileName(dir));
                var m = ReadManifest(dir);
                // Manifest identity is pinned at extraction, outside the retirement target.
                var receipt = Child(Root, "receipts/" + Path.GetFileName(dir) + ".sha256");
                Require(File.ReadAllText(receipt) == Hash(Path.Combine(dir, "install-manifest.json")), "OWNERSHIP_CHANGED");
                using (var lease = new FileStream(Path.Combine(dir, "install-manifest.json"), FileMode.Open, FileAccess.Read, FileShare.None)) {
                foreach (var e in m.files) {
                    var p = Child(dir, e.path);
                    if (!File.Exists(p)) continue;
                    if (!DeleteOwned(p, e.bytes, e.sha256)) { deferred = true; Event("DEFERRED MODIFIED_FILE " + Path.GetFileName(dir)); }
                }
                }
                // Never traverse unknown directories to discover deletion candidates.
                var dirs = m.files.Select(x => Path.GetDirectoryName(Child(dir, x.path))).Distinct().OrderByDescending(x => x.Length);
                foreach (var d in dirs) { var p = d; while (p.Length > dir.Length) { Safe(p); if (Directory.Exists(p) && !Directory.EnumerateFileSystemEntries(p).Any()) Directory.Delete(p); p = Path.GetDirectoryName(p); } }
                if (Directory.EnumerateFileSystemEntries(dir).All(x => Path.GetFileName(x) == "install-manifest.json")) {
                    var manifest = Path.Combine(dir, "install-manifest.json");
                    Require(DeleteOwned(manifest, new FileInfo(manifest).Length, File.ReadAllText(receipt)), "MANIFEST_CHANGED");
                    Directory.Delete(dir); File.Delete(receipt); Event("RETIRED " + Path.GetFileName(dir));
                } else { deferred = true; Event("DEFERRED UNKNOWN_OR_MODIFIED " + Path.GetFileName(dir)); }
            } catch (Exception) { deferred = true; Event("DEFERRED BUSY_OR_UNOWNED " + Path.GetFileName(dir)); }
        }
        Atomic(Child(Root, "cleanup-status.txt"), deferred ? "DEFERRED" : "COMPLETE");
    }
    static int Launch(string id, bool activate) {
        var directory = VersionRoot(id); var m = ReadManifest(directory); Verify(directory, m);
        Require(id == m.version + "-" + m.commit.Substring(0, 12), "VERSION_MISMATCH");
        var nonce = Guid.NewGuid().ToString("N");
        var ack = Child(Root, "receipts/" + nonce + ".ready");
        Directory.CreateDirectory(Path.GetDirectoryName(ack));
        var psi = new ProcessStartInfo(Child(directory, "ChatChatBarDesktop.exe")) { UseShellExecute = false, WorkingDirectory = Root };
        psi.EnvironmentVariables["CCB_DESKTOP_READY_PATH"] = ack;
        psi.EnvironmentVariables["CCB_DESKTOP_READY_NONCE"] = nonce;
        using (var process = Process.Start(psi)) {
            var timer = Stopwatch.StartNew();
            while (!File.Exists(ack) && !process.HasExited && timer.ElapsedMilliseconds < 90000) Thread.Sleep(100);
            Require(File.Exists(ack), "NEW_VERSION_NOT_READY_OLD_ENTRY_RETAINED");
            var values = File.ReadAllLines(ack);
            Require(values.Length == 4 && values[0] == nonce && values[1] == m.version && values[2] == m.commit, "READY_IDENTITY_REFUSED");
            int pid; Require(Int32.TryParse(values[3], out pid), "READY_PROCESS_REFUSED");
            using (var java = Process.GetProcessById(pid)) {
                Require(!java.HasExited && !process.HasExited, "READY_PROCESS_EXITED");
                Shortcuts(false);
                if (activate) Atomic(Child(Root, "current.txt"), id);
                File.Delete(ack); Event("READY " + id);
                try { Cleanup(id); } catch { Event("DEFERRED CLEANUP"); }
            }
            // The stable entry point may return. The running app holds its own program lease.
        }
        return 0;
    }
    [STAThread] static int Main(string[] args) {
        try {
            var mode = args.Length == 0 ? "--launch" : args[0];
            Root = Safe(args.Length >= 2 ? args[1] : AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\'));
            var key = BitConverter.ToString(SHA256.Create().ComputeHash(Encoding.UTF8.GetBytes(Root.ToUpperInvariant()))).Replace("-", "");
            using (var mutex = new Mutex(false, "Local\\CCBDesktopInstall-" + key)) {
                Require(mutex.WaitOne(0), "INSTALL_BUSY");
                try {
                    if (mode == "--prepare") { Prepare(); return 0; }
                    Owned();
                    if (mode == "--stage" && args.Length >= 4) {
                        var directory = VersionRoot(args[2]);
                        if (Directory.Exists(directory)) {
                            Require(!File.Exists(Path.Combine(Root, "current.txt")) || Current() != args[2], "CURRENT_VERSION_ALREADY_INSTALLED");
                            Cleanup(null, args[2]);
                        }
                        Require(!Directory.Exists(directory) && !File.Exists(directory), "STAGING_OCCUPIED");
                        var input = Safe(args[3]); Require(Path.GetFileName(input) == "install-manifest.json", "STAGING_MANIFEST_NAME");
                        var manifest = ReadManifest(Path.GetDirectoryName(input));
                        Require(args[2] == manifest.version + "-" + manifest.commit.Substring(0, 12), "STAGING_IDENTITY_REFUSED");
                        Directory.CreateDirectory(directory);
                        File.Copy(input, Child(directory, "install-manifest.json"), false);
                        Directory.CreateDirectory(Child(Root, "receipts"));
                        Atomic(Child(Root, "receipts/" + args[2] + ".sha256"), Hash(input)); return 0;
                    }
                    if (mode == "--register") {
                        var resources = new[] { "ChatChatBar.exe", "Uninstall.exe" }.Select(p => new Entry {
                            path = p, bytes = new FileInfo(Child(Root, p)).Length, sha256 = Hash(Child(Root, p)) }).ToArray();
                        Atomic(Child(Root, "generated-resources.json"), Json.Serialize(resources)); return 0;
                    }
                    if (mode == "--activate" && args.Length >= 3) {
                        var directory = VersionRoot(args[2]); var m = ReadManifest(directory); Verify(directory, m);
                        if (File.Exists(Path.Combine(Root, "current.txt"))) {
                            var current = ReadManifest(VersionRoot(Current()));
                            var oldVersion = Version.Parse(current.version.Replace("-preview.", "."));
                            var newVersion = Version.Parse(m.version.Replace("-preview.", "."));
                            Require(newVersion > oldVersion, "DOWNGRADE_OR_REINSTALL_REFUSED");
                        }
                        Directory.CreateDirectory(Child(Root, "receipts"));
                        var receipt = Child(Root, "receipts/" + args[2] + ".sha256");
                        Require(File.ReadAllText(receipt) == Hash(Child(directory, "install-manifest.json")), "STAGED_MANIFEST_CHANGED");
                        return Launch(args[2], true);
                    }
                    if (mode == "--launch") return Launch(Current(), false);
                    if (mode == "--cleanup") { Cleanup(Current()); return 0; }
                    if (mode == "--uninstall") {
                        // Preflight every lease before removing any version or shell integration.
                        foreach (var dir in Directory.GetDirectories(Child(Root, "versions")))
                            using (File.Open(Child(dir, "install-manifest.json"), FileMode.Open, FileAccess.Read, FileShare.None)) {}
                        var resources = Json.Deserialize<Entry[]>(File.ReadAllText(Child(Root, "generated-resources.json")));
                        Require(resources.Length == 2 && resources.Select(e => e.path).OrderBy(x => x).SequenceEqual(new[] { "ChatChatBar.exe", "Uninstall.exe" }), "RESOURCE_RECEIPT_REFUSED");
                        foreach (var e in resources) Require(Hash(Child(Root, e.path)) == e.sha256, "GENERATED_RESOURCE_CHANGED");
                        Cleanup(null);
                        Require(!Directory.GetDirectories(Child(Root, "versions")).Any(), "UNINSTALL_DEFERRED_PROGRAMS_RETAINED");
                        Shortcuts(true);
                        foreach (var e in resources) Require(DeleteOwned(Child(Root, e.path), e.bytes, e.sha256), "RESOURCE_CHANGED");
                        File.Delete(Child(Root, "current.txt"));
                        File.Delete(Child(Root, "generated-resources.json"));
                        Event("UNINSTALL_PROGRAMS_REMOVED_DATA_RETAINED"); return 0;
                    }
                    throw new IOException("ARGUMENTS_REFUSED");
                } finally { mutex.ReleaseMutex(); }
            }
        } catch (Exception e) {
            var code = e is IOException ? e.Message : "INSTALL_OR_LAUNCH_FAILED";
            if (Root != null && Directory.Exists(Root)) { try { Event("FAILED " + code); } catch {} }
            if (!args.Contains("--quiet")) MessageBox.Show(code + "\r\n请正常关闭 ChatChatBar 后重试。旧入口及用户资料已保留。", Label, MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return 2;
        }
    }
}
