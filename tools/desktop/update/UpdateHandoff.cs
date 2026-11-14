using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Threading;
using System.Windows.Forms;
using System.Collections.Generic;
using System.Web.Script.Serialization;
using System.Text.RegularExpressions;

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
    static int Complete(string[] args) {
        // Started by the new installer after READY; capture its process before acknowledging.
        // No marker is published until this exact installer exits with code zero.
        Require(args.Length == 8, "UPDATE_COMPLETION_ARGUMENTS");
        var root = Safe(args[1]); var id = args[2]; int pid;
        Require(Regex.IsMatch(id, @"^[0-9]+\.[0-9]+\.[0-9]+-preview\.[1-9][0-9]*-[a-f0-9]{12}(?:-r[a-f0-9]{32})?$"), "UPDATE_COMPLETION_ID");
        Require(Int32.TryParse(args[3], out pid), "UPDATE_COMPLETION_PID");
        using (var installer = Process.GetProcessById(pid)) {
            Require(String.Equals(Safe(installer.MainModule.FileName), Safe(args[4]), StringComparison.OrdinalIgnoreCase), "UPDATE_COMPLETION_PROCESS");
            var ackPath = Safe(args[5]);
            Require(Path.GetFileName(ackPath) == "completion.ready" && ackPath.StartsWith(Safe(Path.GetTempPath()).TrimEnd('\\') + "\\", StringComparison.OrdinalIgnoreCase), "UPDATE_COMPLETION_ACK");
            using (var ack = new FileStream(ackPath, FileMode.CreateNew, FileAccess.Write, FileShare.Read)) ack.WriteByte(1);
            // A completed interactive installer may stay on its completion page indefinitely.
            // Keep this captured process handle; elapsed UI dwell time is not failure or success.
            installer.WaitForExit();
            Require(installer.ExitCode == 0, "UPDATE_COMPLETION_INSTALLER_UNCONFIRMED");
        }
        Require(File.ReadAllText(Safe(Path.Combine(root, "install-owner.txt"))) == "ccb-desktop-nsis-v1" &&
                File.ReadAllText(Safe(Path.Combine(root, "current.txt"))).Trim() == id, "UPDATE_COMPLETION_CURRENT");
        var package = Safe(Path.Combine(root, "versions", id));
        var manifest = Safe(Path.Combine(package, "install-manifest.json"));
        Require(new FileInfo(manifest).Length <= 2097152, "UPDATE_COMPLETION_MANIFEST");
        string digest; using (var stream = File.OpenRead(manifest)) digest = Hash(stream);
        Require(File.ReadAllText(Safe(Path.Combine(root, "receipts", id + ".sha256"))) == digest, "UPDATE_COMPLETION_MANIFEST");
        var json = new JavaScriptSerializer { MaxJsonLength = 2097152 };
        var m = json.Deserialize<Dictionary<string, object>>(File.ReadAllText(manifest));
        Require((string)m["version"] == args[6] && (string)m["commit"] == args[7] && Regex.IsMatch(args[7], "^[a-f0-9]{40}$"), "UPDATE_COMPLETION_SOURCE");
        var config = Safe(Path.Combine(package, "app", "desktop-updater", "update-config.json"));
        Require(new FileInfo(config).Length <= 16384, "UPDATE_COMPLETION_CONFIG");
        var c = json.Deserialize<Dictionary<string, object>>(File.ReadAllText(config));
        Require((string)m["product"] == "ccb-desktop-nsis-v1", "UPDATE_COMPLETION_PRODUCT");
        if (m.ContainsKey("buildNumber")) Require(Convert.ToInt64(m["buildNumber"]) == Convert.ToInt64(c["version"]), "UPDATE_COMPLETION_BUILD");
        Require((string)c["product"] == "ccb-desktop-nsis-v1" && (string)c["displayVersion"] == args[6] &&
                (string)c["sourceSha"] == args[7] && Convert.ToInt64(c["version"]) > 0, "UPDATE_COMPLETION_CONFIG");
#if HANDOFF_TEST
        var cache = Safe(Environment.GetEnvironmentVariable("CCB_UPDATE_TEST_CACHE"));
#else
        var cache = Safe(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ChatChatBarDesktopUpdates"));
#endif
        if (!Directory.Exists(cache)) return 0;
        Require(File.ReadAllText(Safe(Path.Combine(cache, "owner.txt"))) == "ccb-desktop-nsis-v1", "UPDATE_COMPLETION_CACHE");
        var confirmations = Safe(Path.Combine(cache, "confirmations")); Directory.CreateDirectory(confirmations);
        var value = json.Serialize(new { format = 1, product = "ccb-desktop-nsis-v1", build = Convert.ToInt64(c["version"]),
            displayVersion = args[6], sourceSha = args[7], root = root, current = id, manifestSha256 = digest });
        var receipt = Safe(Path.Combine(confirmations, Guid.NewGuid().ToString("N") + ".json"));
        using (var output = new FileStream(receipt, FileMode.CreateNew, FileAccess.Write, FileShare.None)) {
            var raw = System.Text.Encoding.UTF8.GetBytes(value); output.Write(raw, 0, raw.Length); output.Flush(true);
        }
        // The old updater may still hold the used EXE briefly; exclusive deletion refuses it.
        // Retry once after normal handoff drain; manual cleanup consumes the same retained marker.
        for (var attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) Thread.Sleep(1500);
            var python = Safe(Path.Combine(package, "app", "desktop-updater", "python.exe"));
            var helper = Safe(Path.Combine(package, "app", "desktop-updater", "lib", "desktop_update.py"));
            using (var clean = Process.Start(new ProcessStartInfo(python,
                "-B \"" + helper + "\" --package \"" + package + "\" --cache \"" + cache + "\" --clean-cache") {
                UseShellExecute = false, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden })) {
                Require(clean.WaitForExit(120000), "UPDATE_COMPLETION_CLEANUP_PENDING");
                if (attempt == 1) Require(clean.ExitCode == 0, "UPDATE_COMPLETION_CLEANUP_PENDING");
            }
        }
        return 0;
    }
    [STAThread] static int Main(string[] args) {
        try {
            if (args.Length > 0 && args[0] == "--complete") return Complete(args);
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
#if HANDOFF_TEST
            Console.Error.WriteLine(code);
            var marker = Environment.GetEnvironmentVariable("CCB_UPDATE_TEST_MARKER");
            if (marker != null) File.WriteAllText(marker + ".error", code + "\n" + failure.GetType().FullName);
#endif
#if !HANDOFF_TEST
            if (args.Length > 0 && args[0] == "--complete") return 2;
            MessageBox.Show("更新未确认完成：" + code + "\r\n原资料保留。请从桌面入口打开；也可使用已下载的安装包重试。",
                "ChatChatBar 更新", MessageBoxButtons.OK, MessageBoxIcon.Warning);
#endif
            return 2;
        }
    }
}
