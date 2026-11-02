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
using Microsoft.Win32;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Net.Sockets;
using System.Net;

internal static class Launcher {
#if SYNTHETIC
    const string Product = "ccb-desktop-nsis-synthetic-v1";
    const string Label = "ChatChatBar Desktop NSIS Synthetic";
#if LIFECYCLE_TEST
    const string Registration = "ChatChatBarDesktopNSISLifecycleTest";
#else
    const string Registration = "ChatChatBarDesktopNSISSynthetic";
#endif
#else
    const string Product = "ccb-desktop-nsis-v1";
    const string Label = "ChatChatBar Desktop";
    const string Registration = "ChatChatBarDesktopNSIS";
#endif
    internal sealed class Entry { public string path; public string sha256; public long bytes; }
    internal sealed class Manifest { public string product; public string version; public string commit; public Entry[] files; }
    static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = 2097152 };
    static string Root;
    static bool ShellDeferred;
    static string ShellAppId { get { return Product == "ccb-desktop-nsis-v1" ? "MisakaPiano.ChatChatBar.Desktop" : "MisakaPiano.ChatChatBar.Desktop.Synthetic"; } }
    [StructLayout(LayoutKind.Sequential, Pack = 4)] struct ShellPropertyKey { public Guid format; public uint id; }
    [StructLayout(LayoutKind.Explicit, Size = 24)] struct ShellValue {
        [FieldOffset(0)] public ushort kind;
        [FieldOffset(8)] public IntPtr text;
    }
    [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IShellProperties {
        [PreserveSig] int GetCount(out uint count);
        [PreserveSig] int GetAt(uint index, out ShellPropertyKey key);
        [PreserveSig] int GetValue(ref ShellPropertyKey key, out ShellValue value);
        [PreserveSig] int SetValue(ref ShellPropertyKey key, ref ShellValue value);
        [PreserveSig] int Commit();
    }
    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    static extern int SHGetPropertyStoreFromParsingName(string path, IntPtr context, uint flags, ref Guid iid, out IShellProperties properties);
    [DllImport("ole32.dll")] static extern int PropVariantClear(ref ShellValue value);
    static bool ShortcutAppIdentity(string path, bool write) {
        var iid = new Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"); IShellProperties properties;
        Require(SHGetPropertyStoreFromParsingName(path, IntPtr.Zero, write ? 2u : 0u, ref iid, out properties) >= 0, "SHORTCUT_PROPERTIES_UNAVAILABLE");
        var key = new ShellPropertyKey { format = new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"), id = 5 };
        var value = new ShellValue();
        try {
            if (write) {
                value.kind = 31; value.text = Marshal.StringToCoTaskMemUni(ShellAppId);
                Require(properties.SetValue(ref key, ref value) >= 0 && properties.Commit() >= 0, "SHORTCUT_APPID_WRITE_FAILED");
                return true;
            }
            Require(properties.GetValue(ref key, out value) >= 0, "SHORTCUT_APPID_READ_FAILED");
            return value.kind == 31 && Marshal.PtrToStringUni(value.text) == ShellAppId;
        } finally { PropVariantClear(ref value); Marshal.FinalReleaseComObject(properties); }
    }
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
    static bool ResourceEntries(Entry[] entries) {
        var names = entries.Select(e => e.path).OrderBy(x => x).ToArray();
        return names.SequenceEqual(new[] { "ChatChatBar.exe", "Uninstall.exe" }) ||
            names.SequenceEqual(new[] { "ChatChatBar.exe", "OpenChatBarFile.exe", "Uninstall.exe" });
    }
    static string VersionRoot(string id) {
        Require(Regex.IsMatch(id ?? "", @"^[0-9]+\.[0-9]+\.[0-9]+-preview\.[1-9][0-9]*-[a-f0-9]{12}(?:-r[a-f0-9]{32})?$"), "VERSION_ID_REFUSED");
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
    static bool ExactProgramFiles(string directory, Manifest manifest) {
        var expected = new System.Collections.Generic.HashSet<string>(manifest.files.Select(e => e.path.Replace('/', '\\')),
            StringComparer.OrdinalIgnoreCase);
        expected.Add("install-manifest.json");
        var pending = new System.Collections.Generic.Stack<string>(); pending.Push(directory);
        int observed = 0;
        while (pending.Count > 0) {
            var parent = pending.Pop(); Safe(parent);
            foreach (var entry in Directory.EnumerateFileSystemEntries(parent)) {
                Safe(entry); Require(++observed <= 10000, "PROGRAM_TREE_LIMIT");
                if (Directory.Exists(entry)) pending.Push(entry);
                else if (!expected.Remove(entry.Substring(directory.TrimEnd('\\').Length + 1))) return false;
            }
        }
        return expected.Count == 0;
    }
    static string StateDirectory() {
        var local = Environment.GetEnvironmentVariable("LOCALAPPDATA");
        Require(!String.IsNullOrEmpty(local) && Path.IsPathRooted(local), "LOCAL_APP_DATA_UNAVAILABLE");
        var state = Safe(Path.Combine(local, Registration + "InstallState"));
        if (Directory.Exists(state) && Directory.EnumerateFileSystemEntries(state).Any())
            Require(File.ReadAllText(Path.Combine(state, "owner.txt")) == Product, "FOREIGN_INSTALL_STATE");
        else { Directory.CreateDirectory(state); Atomic(Path.Combine(state, "owner.txt"), Product); }
        return state;
    }
    static string LogPath() {
        using (var hash = SHA256.Create()) {
            var key = BitConverter.ToString(hash.ComputeHash(Encoding.UTF8.GetBytes(Root.ToUpperInvariant()))).Replace("-", "");
            return Child(StateDirectory(), key + ".log");
        }
    }
    static void Event(string value) { File.AppendAllText(LogPath(), DateTime.UtcNow.ToString("o") + " " + value + "\r\n"); }
    static string Current() { return File.ReadAllText(Child(Root, "current.txt")).Trim(); }
    static bool ExtractionActive() {
        try { using (var m = Mutex.OpenExisting("Local\\" + Registration + "-Extraction")) return true; }
        catch (WaitHandleCannotBeOpenedException) { return false; }
    }
    static void SecureNewProgramDirectory() {
        // Only the newly selected empty program directory; never alter a data root or its parent.
        var security = new DirectorySecurity();
        security.SetAccessRuleProtection(true, false);
        var inheritance = InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit;
        foreach (var sid in new[] { WindowsIdentity.GetCurrent().User,
            new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
            new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null) })
            security.AddAccessRule(new FileSystemAccessRule(sid, FileSystemRights.FullControl,
                inheritance, PropagationFlags.None, AccessControlType.Allow));
        Directory.SetAccessControl(Root, security);
    }
    static bool Overlaps(string a, string b) {
        a = Path.GetFullPath(a).TrimEnd('\\'); b = Path.GetFullPath(b).TrimEnd('\\');
        return String.Equals(a, b, StringComparison.OrdinalIgnoreCase) ||
            a.StartsWith(b + "\\", StringComparison.OrdinalIgnoreCase) || b.StartsWith(a + "\\", StringComparison.OrdinalIgnoreCase);
    }
    static void ProtectDataRoots() {
        var local = Environment.GetEnvironmentVariable("LOCALAPPDATA");
        Require(!String.IsNullOrEmpty(local) && Path.IsPathRooted(local), "LOCAL_APP_DATA_UNAVAILABLE");
        var protectedPaths = new System.Collections.Generic.List<string> {
            Path.Combine(local, "ChatChatBarDesktop"), Path.Combine(local, "ChatChatBarDesktopCredentials"),
            Path.Combine(local, "ChatChatBarDesktop.bootstrap.json"), Path.Combine(local, Registration + "InstallState"),
            Path.Combine(local, "ChatChatBarDesktopUpdates") };
        var bootstrap = protectedPaths[2];
        if (File.Exists(bootstrap)) {
            Safe(bootstrap); Require(new FileInfo(bootstrap).Length <= 1048576, "BOOTSTRAP_METADATA_LIMIT");
            var document = Json.Deserialize<System.Collections.Generic.Dictionary<string, object>>(File.ReadAllText(bootstrap));
            Require(document != null, "BOOTSTRAP_METADATA_REFUSED");
            object selected;
            if (document.TryGetValue("customRoot", out selected) && selected != null) {
                Require(selected is string && Path.IsPathRooted((string)selected), "BOOTSTRAP_DATA_ROOT_REFUSED");
                Require(!Overlaps((string)selected, protectedPaths[3]), "DATA_INSTALL_STATE_OVERLAP");
                protectedPaths.Add((string)selected);
            }
        }
        foreach (var path in protectedPaths) Require(!Overlaps(Root, Safe(path)), "PROGRAM_DATA_ROOT_OVERLAP");
    }
    static void Prepare(long requiredSpace, bool repair = false) {
        Safe(Root);
        ProtectDataRoots();
        using (var key = Registry.CurrentUser.OpenSubKey("Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\" + Registration)) {
            var registered = key == null ? null : key.GetValue("InstallLocation") as string;
            if (registered != null) Require(String.Equals(Path.GetFullPath(registered), Root, StringComparison.OrdinalIgnoreCase), "OTHER_INSTALLATION_ROOT");
        }
        var drive = new DriveInfo(Path.GetPathRoot(Root));
        Require(drive.DriveType != DriveType.Network && drive.DriveFormat == "NTFS", "INSTALL_REQUIRES_LOCAL_NTFS");
        Require(requiredSpace >= 67108864 && requiredSpace <= 4398046511104L, "INSTALL_SPACE_ARGUMENT_REFUSED");
        Require(drive.AvailableFreeSpace > requiredSpace, "INSTALL_SPACE_REQUIRED");
        using (File.Open(LogPath(), FileMode.OpenOrCreate, FileAccess.Write, FileShare.Read)) {}
        if (Directory.Exists(Root) && Directory.EnumerateFileSystemEntries(Root).Any()) Owned();
        else { Directory.CreateDirectory(Root); SecureNewProgramDirectory(); Atomic(Path.Combine(Root, "install-owner.txt"), Product); }
        // A new installer may not silently replace an incompatible stable entry point.
        var stable = Path.Combine(Root, "ChatChatBar.exe");
        var resourceReceipt = Path.Combine(Root, "generated-resources.json");
        if (File.Exists(stable)) Require(File.Exists(resourceReceipt), "STABLE_LAUNCHER_UNOWNED");
        if (File.Exists(Child(Root, "OpenChatBarFile.exe"))) Require(File.Exists(resourceReceipt), "OPEN_ENTRY_UNOWNED");
        if (File.Exists(resourceReceipt)) {
            var entries = Json.Deserialize<Entry[]>(File.ReadAllText(resourceReceipt));
            Require(ResourceEntries(entries), "RESOURCE_RECEIPT_REFUSED");
            if (!repair) foreach (var e in entries) Require(Hash(Child(Root, e.path)) == e.sha256, "GENERATED_RESOURCE_CHANGED");
            Require(!File.Exists(Child(Root, "OpenChatBarFile.exe")) || entries.Any(e => e.path == "OpenChatBarFile.exe"), "OPEN_ENTRY_UNOWNED");
        }
        var versions = Path.Combine(Root, "versions");
        if (Directory.Exists(versions)) foreach (var directory in Directory.GetDirectories(versions)) {
            var manifest = Child(directory, "install-manifest.json");
            if (File.Exists(manifest)) using (File.Open(manifest, FileMode.Open, FileAccess.Read, FileShare.None)) {}
        }
        if (repair) {
            Require(File.Exists(Child(Root, "current.txt")), "REPAIR_EXISTING_ENTRY_REQUIRED");
            VersionRoot(Current());
            var retained = Child(Root, "repair-retained/" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(retained);
            foreach (var name in new[] { "ChatChatBar.exe", "OpenChatBarFile.exe", "Uninstall.exe", "generated-resources.json", "current.txt" }) {
                var source = Child(Root, name);
                if (File.Exists(source)) {
                    Require(new FileInfo(source).Length <= 16777216, "REPAIR_RESOURCE_LIMIT");
                    File.Copy(source, Child(retained, name), false);
                }
            }
            Event("REPAIR_ORIGINAL_ENTRY_RETAINED");
        }
    }
    static void Shortcut(string path, string receiptName, bool remove) {
        Safe(Path.GetDirectoryName(path));
        var receipt = Child(Root, receiptName);
        if (File.Exists(path)) {
            if (!File.Exists(receipt) || File.ReadAllText(receipt) != Hash(path)) {
                ShellDeferred = true; Event("DEFERRED MODIFIED_OR_FOREIGN_SHORTCUT " + receiptName); return;
            }
            if (remove) { Require(DeleteOwned(path, new FileInfo(path).Length, File.ReadAllText(receipt)), "SHORTCUT_CHANGED"); File.Delete(receipt); }
            else {
                string brandedCopy = null;
                try {
                    if (!ShortcutAppIdentity(path, false)) {
                        var observed = Hash(path);
                        brandedCopy = path + "." + Guid.NewGuid().ToString("N") + ".lnk";
                        File.Copy(path, brandedCopy, false); ShortcutAppIdentity(brandedCopy, true);
                        Require(Hash(path) == observed && observed == File.ReadAllText(receipt), "SHORTCUT_CHANGED");
                        File.Replace(brandedCopy, path, null); brandedCopy = null;
                        Atomic(receipt, Hash(path)); Event("SHORTCUT_APPID_UPDATED " + receiptName);
                    }
                } catch { ShellDeferred = true; Event("DEFERRED SHORTCUT_APPID " + receiptName); }
                finally { if (brandedCopy != null && File.Exists(brandedCopy)) File.Delete(brandedCopy); }
            }
            return;
        }
        if (remove) { if (File.Exists(receipt)) File.Delete(receipt); return; }
        Directory.CreateDirectory(Path.GetDirectoryName(path));
        var type = Type.GetTypeFromProgID("WScript.Shell");
        var shell = Activator.CreateInstance(type);
        var temporary = path + "." + Guid.NewGuid().ToString("N") + ".lnk";
        var link = type.InvokeMember("CreateShortcut", System.Reflection.BindingFlags.InvokeMethod, null, shell, new object[] { temporary });
        var target = Path.Combine(Root, "ChatChatBar.exe");
        var lt = link.GetType();
        foreach (var item in new[] { new[] { "TargetPath", target }, new[] { "WorkingDirectory", Root }, new[] { "Description", Label } })
            lt.InvokeMember(item[0], System.Reflection.BindingFlags.SetProperty, null, link, new object[] { item[1] });
        lt.InvokeMember("Save", System.Reflection.BindingFlags.InvokeMethod, null, link, null);
        try { ShortcutAppIdentity(temporary, true); }
        catch { ShellDeferred = true; Event("DEFERRED SHORTCUT_APPID " + receiptName); }
        File.Move(temporary, path);
        Atomic(receipt, Hash(path));
    }
    static string ShortcutPath(bool desktopEntry) {
        var desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
        var programs = Environment.GetFolderPath(Environment.SpecialFolder.Programs);
#if SYNTHETIC
        var fixture = Environment.GetEnvironmentVariable("CCB_DESKTOP_TEST_SHELL_ROOT");
        if (!String.IsNullOrEmpty(fixture)) { desktop = Safe(Path.Combine(fixture, "Desktop")); programs = Safe(Path.Combine(fixture, "Programs")); }
#endif
        return Path.Combine(desktopEntry ? desktop : programs, Label + ".lnk");
    }
    static void Shortcuts(bool remove) {
        Shortcut(ShortcutPath(true), "desktop-shortcut.sha256", remove);
        Shortcut(ShortcutPath(false), "programs-shortcut.sha256", remove);
        Atomic(Child(Root, "shell-status.txt"), ShellDeferred ? "DEFERRED" : "COMPLETE");
    }
    static bool RetainedRepairOriginal(string id) {
        VersionRoot(id);
        var marker = Child(Root, "receipts/" + id + ".repair-retained");
        if (!File.Exists(marker)) return false;
        Require(new FileInfo(marker).Length <= 256, "REPAIR_RETENTION_MARKER_REFUSED");
        VersionRoot(File.ReadAllText(marker));
        return true;
    }
    static void Cleanup(string keep, string only = null) {
        var shellStatus = Child(Root, "shell-status.txt");
        bool deferred = ShellDeferred || (File.Exists(shellStatus) && File.ReadAllText(shellStatus) == "DEFERRED");
        var versions = Child(Root, "versions");
        foreach (var dir in Directory.GetDirectories(versions)) {
            if (Path.GetFileName(dir) == keep) continue;
            if (only != null && Path.GetFileName(dir) != only) continue;
            try {
                VersionRoot(Path.GetFileName(dir));
                if (RetainedRepairOriginal(Path.GetFileName(dir))) {
                    deferred = true; Event("DEFERRED REPAIR_ORIGINAL_RETAINED " + Path.GetFileName(dir)); continue;
                }
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
    static int Launch(string id, bool activate, string expectedCurrent = null, bool retainRepairOriginal = false) {
        var directory = VersionRoot(id); var m = ReadManifest(directory);
        var identity = m.version + "-" + m.commit.Substring(0, 12);
        Require(id == identity || Regex.IsMatch(id, "^" + Regex.Escape(identity) + "-r[a-f0-9]{32}$"), "VERSION_MISMATCH");
        if (!activate) {
            Require(File.ReadAllText(Child(Root, "receipts/" + id + ".sha256")) == Hash(Child(directory, "install-manifest.json")), "CURRENT_MANIFEST_CHANGED");
            if (TryForward(new string[0])) return 0;
        }
        Verify(directory, m);
        var nonce = Guid.NewGuid().ToString("N");
        var ack = Child(Root, "receipts/" + nonce + ".ready");
        var forwarded = Child(Root, "receipts/" + nonce + ".forwarded");
        Directory.CreateDirectory(Path.GetDirectoryName(ack));
        var psi = new ProcessStartInfo(Child(directory, "ChatChatBarDesktop.exe")) { UseShellExecute = false, WorkingDirectory = Root };
        foreach (var name in new[] { "CCB_DESKTOP_ACTIVATION_PATH", "CCB_DESKTOP_ACTIVATION_NONCE", "CCB_DESKTOP_FORWARD_PATH", "CCB_DESKTOP_FORWARD_NONCE" }) psi.EnvironmentVariables.Remove(name);
        psi.EnvironmentVariables["CCB_DESKTOP_READY_PATH"] = ack;
        psi.EnvironmentVariables["CCB_DESKTOP_READY_NONCE"] = nonce;
        if (!activate) {
            psi.EnvironmentVariables["CCB_DESKTOP_FORWARD_PATH"] = forwarded;
            psi.EnvironmentVariables["CCB_DESKTOP_FORWARD_NONCE"] = nonce;
        }
        using (var process = Process.Start(psi)) {
            var timer = Stopwatch.StartNew();
            while (!File.Exists(ack) && (activate || !File.Exists(forwarded)) && !process.HasExited && timer.ElapsedMilliseconds < 90000) Thread.Sleep(100);
            if (!activate && File.Exists(forwarded)) {
                Require(File.ReadAllText(forwarded) == nonce + "\nFORWARDED\n", "FORWARD_RECEIPT_REFUSED");
                File.Delete(forwarded); Event("FORWARDED " + id); return 0;
            }
            Require(File.Exists(ack), "NEW_VERSION_NOT_READY_OLD_ENTRY_RETAINED");
            var values = File.ReadAllLines(ack);
            Require(values.Length == 4 && values[0] == nonce && values[1] == m.version && values[2] == m.commit, "READY_IDENTITY_REFUSED");
            int pid; Require(Int32.TryParse(values[3], out pid), "READY_PROCESS_REFUSED");
            using (var java = Process.GetProcessById(pid)) {
                Require(!java.HasExited && !process.HasExited, "READY_PROCESS_EXITED");
                Shortcuts(false);
                if (activate) {
                    Require(expectedCurrent == null ? !File.Exists(Child(Root, "current.txt")) : Current() == expectedCurrent,
                        "ACTIVATION_ENTRY_CHANGED");
                    if (retainRepairOriginal) Atomic(Child(Root, "receipts/" + expectedCurrent + ".repair-retained"), id);
                    Atomic(Child(Root, "current.txt"), id);
                }
                OpenWith(false);
                File.Delete(ack); Event("READY " + id);
                if (!activate) try { Cleanup(id); } catch { Event("DEFERRED CLEANUP"); }
            }
            // The stable entry point may return. The running app holds its own program lease.
        }
        return 0;
    }
    // Keep the public entry point small and protocol-stable; each version carries its manager.
    static string QuoteArgument(string value) {
        Require(value != null && value.IndexOf('\0') < 0 && value.Length <= 32768, "OPEN_ARGUMENT_REFUSED");
        var b = new StringBuilder("\""); int slashes = 0;
        foreach (var c in value) {
            if (c == '\\') { slashes++; continue; }
            if (c == '"') b.Append('\\', slashes * 2 + 1); else b.Append('\\', slashes);
            b.Append(c); slashes = 0;
        }
        b.Append('\\', slashes * 2); return b.Append('"').ToString();
    }
    static void WireInt(Stream stream, int value) {
        var bytes = BitConverter.GetBytes(IPAddress.HostToNetworkOrder(value)); stream.Write(bytes, 0, 4);
    }
    static void WireText(Stream stream, string value) {
        var bytes = Encoding.UTF8.GetBytes(value); WireInt(stream, bytes.Length); stream.Write(bytes, 0, bytes.Length);
    }
    // Warm activation executes no new app image. Cold execution below still verifies every file.
    static bool TryForward(string[] files) {
        var local = Environment.GetEnvironmentVariable("LOCALAPPDATA");
        if (String.IsNullOrEmpty(local)) return false;
        var key = BitConverter.ToString(SHA256.Create().ComputeHash(Encoding.UTF8.GetBytes(Root.ToLowerInvariant()))).Replace("-", "").ToLowerInvariant();
        var endpoint = Safe(Path.Combine(local, "ChatChatBarDesktopActivation", key, "endpoint"));
        if (!File.Exists(endpoint)) return false;
        Require(new FileInfo(endpoint).Length <= 160, "OPEN_ENDPOINT_REFUSED");
        var lines = File.ReadAllLines(endpoint); int port;
        Require(lines.Length == 2 && Int32.TryParse(lines[0], out port) && port > 0 && port <= 65535 &&
            Regex.IsMatch(lines[1], "^[a-f0-9]{64}$"), "OPEN_ENDPOINT_REFUSED");
        port = Int32.Parse(lines[0]);
        using (var client = new TcpClient()) {
            try {
                var connecting = client.BeginConnect(IPAddress.Loopback, port, null, null);
                using (connecting.AsyncWaitHandle) {
                    if (!connecting.AsyncWaitHandle.WaitOne(500)) return false;
                    client.EndConnect(connecting);
                }
            } catch (SocketException) { return false; } // No frame dispatched: the app ingress may resolve a cold race.
            client.SendTimeout = 3000; client.ReceiveTimeout = 3000;
            try {
                using (var stream = client.GetStream()) {
                    WireInt(stream, 0x43434231); WireText(stream, lines[1]); WireText(stream, Guid.NewGuid().ToString());
                    WireInt(stream, files.Length); foreach (var file in files) WireText(stream, file); stream.Flush();
                    var response = new byte[4]; int count = 0;
                    while (count < 4) { var read = stream.Read(response, count, 4 - count); Require(read > 0, "OPEN_DELIVERY_UNCONFIRMED_DO_NOT_REPLAY"); count += read; }
                    Require(IPAddress.NetworkToHostOrder(BitConverter.ToInt32(response, 0)) == 0, "OPEN_QUEUE_REFUSED");
                }
            } catch (IOException error) {
                if (error.Message == "OPEN_QUEUE_REFUSED") throw;
                throw new IOException("OPEN_DELIVERY_UNCONFIRMED_DO_NOT_REPLAY");
            }
            return true;
        }
    }
    static void OpenWith(bool remove) {
        var bridge = Child(Root, "OpenChatBarFile.exe");
        if (!File.Exists(bridge)) return; // Existing two-resource installations remain valid.
        var suffix = BitConverter.ToString(SHA256.Create().ComputeHash(Encoding.UTF8.GetBytes(Root.ToUpperInvariant()))).Replace("-", "").Substring(0, 12);
        var id = Registration + ".File." + suffix;
        var relative = "Software\\Classes\\" + id;
        var expected = QuoteArgument(bridge) + " \"%1\"";
        var extensions = new[] { ".cbbackup", ".cbsave", ".json", ".png", ".jpg", ".jpeg", ".webp", ".gif" };
        try {
            using (var key = Registry.CurrentUser.OpenSubKey(relative + "\\shell\\open\\command")) {
                var command = key == null ? null : key.GetValue("") as string;
                if (command != null && command != expected) { ShellDeferred = true; Event("DEFERRED OPEN_WITH_CHANGED"); return; }
                if (remove && command == null) return;
            }
            if (remove) {
                foreach (var extension in extensions) using (var key = Registry.CurrentUser.OpenSubKey("Software\\Classes\\" + extension + "\\OpenWithProgids", true)) {
                    if (key != null) key.DeleteValue(id, false);
                }
                Registry.CurrentUser.DeleteSubKeyTree(relative, false);
            } else {
                using (var key = Registry.CurrentUser.CreateSubKey(relative)) { key.SetValue("", Label); key.SetValue("FriendlyAppName", Label); }
                using (var key = Registry.CurrentUser.CreateSubKey(relative + "\\DefaultIcon")) key.SetValue("", QuoteArgument(bridge) + ",0");
                using (var key = Registry.CurrentUser.CreateSubKey(relative + "\\shell\\open\\command")) key.SetValue("", expected);
                foreach (var extension in extensions) using (var key = Registry.CurrentUser.CreateSubKey("Software\\Classes\\" + extension + "\\OpenWithProgids")) key.SetValue(id, "");
            }
        } catch (Exception) { ShellDeferred = true; Event("DEFERRED OPEN_WITH"); }
    }
    static int Delegate(string[] files = null) {
        Require(!ExtractionActive(), "INSTALLATION_BUSY");
        Owned(); var directory = VersionRoot(Current());
        using (var lease = new FileStream(Child(directory, "install-manifest.json"), FileMode.Open, FileAccess.Read, FileShare.Read)) {
            var receipt = Child(Root, "receipts/" + Path.GetFileName(directory) + ".sha256");
            Require(File.ReadAllText(receipt) == Hash(Child(directory, "install-manifest.json")), "CURRENT_MANIFEST_CHANGED");
            var m = ReadManifest(directory); var entry = m.files.Single(x => x.path == "install-manager.exe");
            var manager = Child(directory, entry.path);
            using (var program = new FileStream(manager, FileMode.Open, FileAccess.Read, FileShare.Read)) {
                Require(program.Length == entry.bytes && Hash(manager) == entry.sha256, "CURRENT_MANAGER_CHANGED");
                var arguments = files == null ? "--launch " + QuoteArgument(Root) :
                    "--open " + QuoteArgument(Root) + " " + String.Join(" ", files.Select(QuoteArgument));
                using (var p = Process.Start(new ProcessStartInfo(manager, arguments) { UseShellExecute = false, WorkingDirectory = Root })) {
                    p.WaitForExit(); return p.ExitCode;
                }
            }
        }
    }
    // Opt-in setup protocol: legacy callers retain exit 2; each code describes THIS invocation.
    static int DiagnosticFailureExit(string code) {
        switch (code) {
            case "PROGRAM_DATA_ROOT_OVERLAP": return 20;
            case "DATA_INSTALL_STATE_OVERLAP": return 21;
            case "INSTALLATION_BUSY": return 22;
            case "INSTALL_BUSY": return 23;
            case "PARENT_BUSY": return 24;
            case "FILE_BUSY": return 25;
            case "INSTALL_SPACE_REQUIRED": return 26;
            case "NEW_VERSION_NOT_READY_OLD_ENTRY_RETAINED": return 27;
            case "READY_IDENTITY_REFUSED": return 28;
            case "READY_PROCESS_REFUSED": return 29;
            case "READY_PROCESS_EXITED": return 30;
            case "PROGRAM_FILES_IN_USE": return 31;
            default: return 2;
        }
    }
    [STAThread] static int Main(string[] args) {
        try {
#if OPEN_WITH
            Root = Safe(AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\'));
            Require(args.Length > 0 && args.Length <= 64, "OPEN_ARGUMENT_REFUSED");
            return Delegate(args.Select(p => Safe(p)).ToArray());
#else
            var mode = args.Length == 0 ? "--launch" : args[0];
            Root = Safe(args.Length >= 2 ? args[1] : AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\'));
#if STABLE
            return Delegate();
#else
            var key = BitConverter.ToString(SHA256.Create().ComputeHash(Encoding.UTF8.GetBytes(Root.ToUpperInvariant()))).Replace("-", "");
            using (var mutex = new Mutex(false, "Local\\CCBDesktopInstall-" + key)) {
                Require(mutex.WaitOne(mode == "--open" ? 15000 : 0), "INSTALL_BUSY");
                try {
                    if (mode == "--prepare" || mode == "--prepare-repair") {
                        long space = 805306368;
                        if (args.Length >= 3 && args[2] != "--quiet") Require(Int64.TryParse(args[2], out space), "INSTALL_SPACE_ARGUMENT_REFUSED");
                        Prepare(space, mode == "--prepare-repair"); return 0;
                    }
                    Owned();
                    if (mode == "--open") {
                        Require(args.Length >= 3 && args.Length <= 67 && !ExtractionActive(), "OPEN_ARGUMENT_REFUSED");
                        var directory = VersionRoot(Current());
                        Require(File.ReadAllText(Child(Root, "receipts/" + Current() + ".sha256")) == Hash(Child(directory, "install-manifest.json")), "CURRENT_MANIFEST_CHANGED");
                        var files = args.Skip(2).Where(p => p != "--quiet").Select(p => Safe(p)).ToArray();
                        Require(files.Length > 0 && files.Length <= 64, "OPEN_ARGUMENT_REFUSED");
                        Require(files.All(p => p.Length <= 32768) && files.Sum(p => Encoding.UTF8.GetByteCount(p)) <= 1048576 - 8 - files.Length * 4, "OPEN_ARGUMENT_REFUSED");
                        if (TryForward(files)) return 0;
                        Verify(directory, ReadManifest(directory));
                        var nonce = Guid.NewGuid().ToString("N"); var receipt = Child(Root, "receipts/" + nonce + ".activation");
                        var request = Child(Root, "receipts/" + nonce + ".open-request");
                        var pendingRequest = request + ".pending";
                        using (var output = new FileStream(pendingRequest, FileMode.CreateNew, FileAccess.Write, FileShare.None)) {
                            WireInt(output, 0x43434232); WireInt(output, files.Length);
                            foreach (var file in files) {
                                var bytes = new UTF8Encoding(false, true).GetBytes(file);
                                WireInt(output, bytes.Length); output.Write(bytes, 0, bytes.Length);
                            }
                            output.Flush(true);
                        }
                        File.Move(pendingRequest, request);
                        var requestHash = Hash(request);
                        var psi = new ProcessStartInfo(Child(directory, "ChatChatBarDesktop.exe"), "--open-request " + nonce + " " + requestHash) { UseShellExecute = false, WorkingDirectory = Root };
                        foreach (var name in new[] { "CCB_DESKTOP_READY_PATH", "CCB_DESKTOP_READY_NONCE", "CCB_DESKTOP_FORWARD_PATH", "CCB_DESKTOP_FORWARD_NONCE" }) psi.EnvironmentVariables.Remove(name);
                        psi.EnvironmentVariables["CCB_DESKTOP_ACTIVATION_PATH"] = receipt;
                        psi.EnvironmentVariables["CCB_DESKTOP_ACTIVATION_NONCE"] = nonce;
                        using (var process = Process.Start(psi)) {
                            var timer = Stopwatch.StartNew();
                            while (!File.Exists(receipt) && !process.HasExited && timer.ElapsedMilliseconds < 15000) Thread.Sleep(50);
                            Require(File.Exists(receipt), "OPEN_DELIVERY_UNCONFIRMED_DO_NOT_REPLAY");
                            Require(File.ReadAllText(receipt) == nonce + "\nQUEUED\n", "OPEN_RECEIPT_REFUSED");
                            File.Delete(receipt);
                        }
                        // QUEUED is already proven. Retain a changed/unremovable request and expose cleanup separately.
                        try {
                            if (Hash(Safe(request)) == requestHash) File.Delete(request);
                            else Event("DEFERRED OPEN_REQUEST_CLEANUP");
                        } catch { Event("DEFERRED OPEN_REQUEST_CLEANUP"); }
                        return 0;
                    }
                    if (mode == "--shortcuts") {
                        Require(args.Length >= 3 && (args[2] == "desktop" || args[2] == "programs"), "SHORTCUT_ARGUMENT_REFUSED");
                        Require(!ExtractionActive(), "INSTALLATION_BUSY");
                        var current = VersionRoot(Current());
                        Require(File.ReadAllText(Child(Root, "receipts/" + Current() + ".sha256")) == Hash(Path.Combine(current, "install-manifest.json")), "CURRENT_MANIFEST_CHANGED");
                        Verify(current, ReadManifest(current));
                        Require(Hash(Path.Combine(current, "install-manager.exe")) == Hash(System.Reflection.Assembly.GetExecutingAssembly().Location), "SHORTCUT_MANAGER_CHANGED");
                        bool desktopEntry = args[2] == "desktop";
                        Shortcut(ShortcutPath(desktopEntry), desktopEntry ? "desktop-shortcut.sha256" : "programs-shortcut.sha256", false);
                        return ShellDeferred ? 3 : 0;
                    }
                    if (mode == "--stage-repair" && args.Length >= 4) {
                        var input = Safe(args[3]); Require(Path.GetFileName(input) == "install-manifest.json", "STAGING_MANIFEST_NAME");
                        var manifest = ReadManifest(Path.GetDirectoryName(input));
                        var baseId = manifest.version + "-" + manifest.commit.Substring(0, 12);
                        Require(args[2] == baseId, "STAGING_IDENTITY_REFUSED");
                        var current = Current(); VersionRoot(current);
                        var oldBase = Regex.Replace(current, "-r[a-f0-9]{32}$", "");
                        var oldDisplay = oldBase.Substring(0, oldBase.Length - 13);
                        var oldVersion = Version.Parse(oldDisplay.Replace("-preview.", "."));
                        var newVersion = Version.Parse(manifest.version.Replace("-preview.", "."));
                        Require(newVersion >= oldVersion, "REPAIR_DOWNGRADE_REFUSED");
                        if (newVersion == oldVersion) Require(baseId == oldBase &&
                            File.ReadAllText(Child(Root, "receipts/" + current + ".sha256")) == Hash(input), "REPAIR_PACKAGE_IDENTITY_CHANGED");
                        var id = baseId + "-r" + Guid.NewGuid().ToString("N");
                        var directory = VersionRoot(id);
                        Require(!Directory.Exists(directory), "REPAIR_FRESH_STAGE_REQUIRED");
                        Directory.CreateDirectory(directory);
                        File.Copy(input, Child(directory, "install-manifest.json"), false);
                        Directory.CreateDirectory(Child(Root, "receipts"));
                        Atomic(Child(Root, "receipts/" + id + ".sha256"), Hash(input));
                        Atomic(Child(Root, "receipts/" + id + ".repair-current"), current);
                        Atomic(Child(Root, "pending-repair.txt"), id);
                        Event("REPAIR_FRESH_STAGE_PREPARED"); return 0;
                    }
                    if (mode == "--stage" && args.Length >= 4) {
                        var directory = VersionRoot(args[2]);
                        var input = Safe(args[3]); Require(Path.GetFileName(input) == "install-manifest.json", "STAGING_MANIFEST_NAME");
                        var manifest = ReadManifest(Path.GetDirectoryName(input));
                        Require(args[2] == manifest.version + "-" + manifest.commit.Substring(0, 12), "STAGING_IDENTITY_REFUSED");
                        if (Directory.Exists(directory)) {
                            if (File.Exists(Path.Combine(Root, "current.txt")) && Current() == args[2]) {
                                Require(Hash(input) == Hash(Child(directory, "install-manifest.json")) &&
                                    File.ReadAllText(Child(Root, "receipts/" + args[2] + ".sha256")) == Hash(input), "CURRENT_MANIFEST_CHANGED");
                                Verify(directory, manifest); return 10; // confirm without overlaying a live version
                            }
                            var receipt = Child(Root, "receipts/" + args[2] + ".sha256");
                            if (File.Exists(receipt) && File.ReadAllText(receipt) == Hash(input) &&
                                File.Exists(Child(directory, "install-manifest.json")) && Hash(input) == Hash(Child(directory, "install-manifest.json"))) {
                                try {
                                    Verify(directory, manifest);
                                    if (ExactProgramFiles(directory, manifest)) { Event("VERIFIED_STAGE_REUSED " + args[2]); return 10; }
                                } catch (IOException) { Event("STAGED_TARGET_REVALIDATION_FAILED " + args[2]); }
                            }
                            Cleanup(null, args[2]);
                        }
                        Require(!Directory.Exists(directory) && !File.Exists(directory), "STAGING_OCCUPIED");
                        Directory.CreateDirectory(directory);
                        File.Copy(input, Child(directory, "install-manifest.json"), false);
                        Directory.CreateDirectory(Child(Root, "receipts"));
                        Atomic(Child(Root, "receipts/" + args[2] + ".sha256"), Hash(input)); return 0;
                    }
                    if (mode == "--register") {
                        var names = new[] { "ChatChatBar.exe", "Uninstall.exe" }.Concat(File.Exists(Child(Root, "OpenChatBarFile.exe")) ? new[] { "OpenChatBarFile.exe" } : new string[0]);
                        var resources = names.Select(p => new Entry {
                            path = p, bytes = new FileInfo(Child(Root, p)).Length, sha256 = Hash(Child(Root, p)) }).ToArray();
                        Require(ResourceEntries(resources), "RESOURCE_RECEIPT_REFUSED");
                        Atomic(Child(Root, "generated-resources.json"), Json.Serialize(resources)); return 0;
                    }
                    if (mode == "--activate" && args.Length >= 3) {
                        var directory = VersionRoot(args[2]); var m = ReadManifest(directory); Verify(directory, m);
                        var expectedCurrent = File.Exists(Path.Combine(Root, "current.txt")) ? Current() : null;
                        var repairReceipt = Child(Root, "receipts/" + args[2] + ".repair-current");
                        var repair = File.Exists(repairReceipt);
                        if (repair) Require(expectedCurrent != null && File.ReadAllText(repairReceipt) == expectedCurrent, "REPAIR_ENTRY_CHANGED");
                        if (File.Exists(Path.Combine(Root, "current.txt"))) {
                            var oldDisplay = repair ? Regex.Replace(expectedCurrent, "-[a-f0-9]{12}(?:-r[a-f0-9]{32})?$", "") :
                                ReadManifest(VersionRoot(Current())).version;
                            var oldVersion = Version.Parse(oldDisplay.Replace("-preview.", "."));
                            var newVersion = Version.Parse(m.version.Replace("-preview.", "."));
                            Require(newVersion > oldVersion || args[2] == Current() || repair && newVersion == oldVersion, "DOWNGRADE_OR_REINSTALL_REFUSED");
                        }
                        Directory.CreateDirectory(Child(Root, "receipts"));
                        var receipt = Child(Root, "receipts/" + args[2] + ".sha256");
                        Require(File.ReadAllText(receipt) == Hash(Child(directory, "install-manifest.json")), "STAGED_MANIFEST_CHANGED");
                        return Launch(args[2], true, expectedCurrent, repair);
                    }
                    if (mode == "--launch") { Require(!ExtractionActive(), "INSTALLATION_BUSY"); return Launch(Current(), false); }
                    if (mode == "--cleanup") { Cleanup(Current()); return 0; }
                    if (mode == "--uninstall") {
                        // Preflight every lease before removing any version or shell integration.
                        foreach (var dir in Directory.GetDirectories(Child(Root, "versions"))) {
                            var manifest = Child(dir, "install-manifest.json");
                            if (File.Exists(manifest) || !RetainedRepairOriginal(Path.GetFileName(dir)))
                                using (File.Open(manifest, FileMode.Open, FileAccess.Read, FileShare.None)) {}
                        }
                        var resources = Json.Deserialize<Entry[]>(File.ReadAllText(Child(Root, "generated-resources.json")));
                        Require(ResourceEntries(resources), "RESOURCE_RECEIPT_REFUSED");
                        foreach (var e in resources) Require(Hash(Child(Root, e.path)) == e.sha256, "GENERATED_RESOURCE_CHANGED");
                        Cleanup(null);
                        Require(!Directory.GetDirectories(Child(Root, "versions")).Any(dir => !RetainedRepairOriginal(Path.GetFileName(dir))),
                            "UNINSTALL_DEFERRED_PROGRAMS_RETAINED");
                        Shortcuts(true); OpenWith(true);
                        if (ShellDeferred) Atomic(Child(Root, "cleanup-status.txt"), "DEFERRED");
                        foreach (var e in resources) Require(DeleteOwned(Child(Root, e.path), e.bytes, e.sha256), "RESOURCE_CHANGED");
                        File.Delete(Child(Root, "current.txt"));
                        File.Delete(Child(Root, "generated-resources.json"));
                        Event("UNINSTALL_PROGRAMS_REMOVED_DATA_RETAINED"); return 0;
                    }
                    throw new IOException("ARGUMENTS_REFUSED");
                } finally { mutex.ReleaseMutex(); }
            }
#endif
#endif
        } catch (Exception e) {
            var code = e is IOException ? e.Message : "INSTALL_OR_LAUNCH_FAILED";
            if (e is IOException && ((e.HResult & 0xffff) == 32 || (e.HResult & 0xffff) == 33)) code = "PROGRAM_FILES_IN_USE";
            if (Root != null && code != "DATA_INSTALL_STATE_OVERLAP") { try { Event("FAILED " + code); } catch {} }
            var notice = code == "OPEN_DELIVERY_UNCONFIRMED_DO_NOT_REPLAY" ?
                "文件投递结果尚未确认，请先查看已打开程序的导入队列。不要重复打开同一批文件。原文件和用户资料已保留。" :
                "请正常关闭 ChatChatBar 后重试。旧入口及用户资料已保留。";
            if (!args.Contains("--quiet")) MessageBox.Show(code + "\r\n" + notice, Label, MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return args.Contains("--diagnostic-exit") ? DiagnosticFailureExit(code) : 2;
        }
    }
}
