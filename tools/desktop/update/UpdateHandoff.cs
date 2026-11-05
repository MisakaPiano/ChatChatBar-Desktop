using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Threading;
using System.Windows.Forms;

// A separate process waits for a normal, drained JVM exit. It never kills a writer.
static class DesktopUpdateHandoff {
    static void Require(bool condition, string code) { if (!condition) throw new IOException(code); }
    static string Safe(string path) {
        path = Path.GetFullPath(path);
        for (var p = path; !String.IsNullOrEmpty(p); p = Path.GetDirectoryName(p))
            if (Directory.Exists(p) || File.Exists(p)) Require((File.GetAttributes(p) & FileAttributes.ReparsePoint) == 0, "UPDATE_REPARSE_REFUSED");
        Require(path.IndexOfAny(new[] { '"', '\r', '\n', '\0' }) < 0, "UPDATE_ARGUMENT_REFUSED");
        return path;
    }
    static string Hash(Stream stream) {
        stream.Position = 0;
        using (var hash = SHA256.Create()) return BitConverter.ToString(hash.ComputeHash(stream)).Replace("-", "").ToLowerInvariant();
    }
    static void WaitForLeaseRelease(string root) {
        var deadline = DateTime.UtcNow.AddSeconds(15);
        while (true) {
            try {
                foreach (var directory in Directory.GetDirectories(Safe(Path.Combine(root, "versions")))) {
                    var manifest = Safe(Path.Combine(directory, "install-manifest.json"));
                    if (File.Exists(manifest)) using (File.Open(manifest, FileMode.Open, FileAccess.Read, FileShare.None)) {}
                }
                return;
            } catch (IOException) {
                Require(DateTime.UtcNow < deadline, "UPDATE_OTHER_PROGRAM_STILL_RUNNING");
                Thread.Sleep(100);
            }
        }
    }
    [STAThread] static int Main(string[] args) {
        try {
            Require(args.Length == 7, "UPDATE_ARGUMENT_REFUSED");
            var root = Safe(args[0]); var package = Safe(args[1]); var digest = args[2];
            long bytes = 0, started = 0; int pid = 0;
            Require(Int64.TryParse(args[3], out bytes) && bytes > 0 && bytes <= 1073741824 &&
                    Int32.TryParse(args[4], out pid) && Int64.TryParse(args[5], out started), "UPDATE_ARGUMENT_REFUSED");
            Require(System.Text.RegularExpressions.Regex.IsMatch(digest, "^[a-f0-9]{64}$"), "UPDATE_HASH_REFUSED");
            Require(File.ReadAllText(Safe(Path.Combine(root, "install-owner.txt"))) == "ccb-desktop-nsis-v1", "UPDATE_INSTALL_ROOT_REFUSED");
            var pointer = Safe(Path.Combine(root, "current.txt"));
            Require(File.ReadAllText(pointer).Trim() == args[6], "UPDATE_CURRENT_CHANGED");
            Require(!package.StartsWith(root.TrimEnd('\\') + "\\", StringComparison.OrdinalIgnoreCase), "UPDATE_CACHE_OVERLAP");
            using (var locked = new FileStream(package, FileMode.Open, FileAccess.Read, FileShare.Read)) {
                Require(locked.Length == bytes && Hash(locked) == digest, "UPDATE_PACKAGE_CHANGED");
                try {
                    using (var old = Process.GetProcessById(pid)) {
                        var milliseconds = (long)(old.StartTime.ToUniversalTime() - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
                        Require(Math.Abs(milliseconds - started) < 1000, "UPDATE_PROCESS_IDENTITY_CHANGED");
                        Require(old.WaitForExit(120000), "UPDATE_NORMAL_EXIT_TIMEOUT");
                    }
                } catch (ArgumentException) { /* The confirmed process already exited normally. */ }
                WaitForLeaseRelease(root);
                Require(File.ReadAllText(pointer).Trim() == args[6], "UPDATE_CURRENT_CHANGED");
                Require(locked.Length == bytes && Hash(locked) == digest, "UPDATE_PACKAGE_CHANGED");
                using (var installer = Process.Start(new ProcessStartInfo(package, "/S /D=" + root) {
                    UseShellExecute = false, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden,
                    WorkingDirectory = Path.GetDirectoryName(package)
                })) {
                    Require(installer.WaitForExit(300000), "UPDATE_INSTALLER_STILL_RUNNING");
                    Require(installer.ExitCode == 0, "UPDATE_INSTALLER_FAILED");
                }
            }
            return 0;
        } catch (Exception failure) {
            var code = failure is IOException ? failure.Message : "UPDATE_HANDOFF_FAILED";
#if !HANDOFF_TEST
            MessageBox.Show("更新未确认完成：" + code + "\r\n原资料保留。请从桌面入口打开；也可使用已下载的安装包重试。",
                "ChatChatBar 更新", MessageBoxButtons.OK, MessageBoxIcon.Warning);
#endif
            return 2;
        }
    }
}
