"""Explicit Windows synthetic installer/lifecycle probe; never accepts the ordinary Desktop product."""
import argparse
import ctypes as C
from ctypes import wintypes as W
import hashlib
import json
import msvcrt
import os
import re
from pathlib import Path
import subprocess
import time
import uuid
import zipfile

REPO = Path(__file__).resolve().parents[3]
TASKS = REPO / "app/desktopApp/build/preview-continuous-private-20261025"
UPGRADE = "58e95fd1-709d-5813-9b5b-f518a4612bae"
PRODUCT = "ChatChatBarDesktopSynthetic"


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def safe(path):
    path = Path(os.path.abspath(path))
    kernel = C.WinDLL("kernel32", use_last_error=True)
    kernel.GetFileAttributesW.argtypes = [W.LPCWSTR]
    kernel.GetFileAttributesW.restype = W.DWORD
    for part in [path, *path.parents]:
        if part.exists():
            assert not part.is_symlink() and not (hasattr(part, "is_junction") and part.is_junction()), "REPARSE_REFUSED"
            flags = kernel.GetFileAttributesW(str(part))
            assert flags != 0xFFFFFFFF and not flags & 0x400, "REPARSE_OR_UNREADABLE_PATH"
    return path


def task(base):
    base = safe(base)
    assert base.is_relative_to(TASKS) and base.name.startswith("windows-runtime-")
    owner = json.loads((base / "TASK_OWNER.json").read_text())
    assert owner["kind"] == "CCB_DESKTOP_SYNTHETIC_WINDOWS_RUNTIME"
    return base


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def msi_info(path):
    path = safe(path)
    assert path.is_relative_to(REPO / "app/desktopApp/build") and path.suffix.lower() == ".msi"
    lib = C.WinDLL("msi")
    lib.MsiOpenDatabaseW.argtypes = [W.LPCWSTR, W.LPCWSTR, C.POINTER(W.UINT)]
    lib.MsiDatabaseOpenViewW.argtypes = [W.UINT, W.LPCWSTR, C.POINTER(W.UINT)]
    lib.MsiRecordGetStringW.argtypes = [W.UINT, W.UINT, W.LPWSTR, C.POINTER(W.DWORD)]
    db = W.UINT()
    assert lib.MsiOpenDatabaseW(str(path), None, C.byref(db)) == 0
    values = {}
    try:
        view = W.UINT()
        assert lib.MsiDatabaseOpenViewW(db, "SELECT `Property`,`Value` FROM `Property`", C.byref(view)) == 0
        try:
            assert lib.MsiViewExecute(view, 0) == 0
            while True:
                record = W.UINT()
                result = lib.MsiViewFetch(view, C.byref(record))
                if result == 259:
                    break
                assert result == 0
                pair = []
                try:
                    for index in [1, 2]:
                        buffer = C.create_unicode_buffer(4096)
                        length = W.DWORD(4096)
                        assert lib.MsiRecordGetStringW(record, index, buffer, C.byref(length)) == 0
                        pair.append(buffer.value)
                finally:
                    lib.MsiCloseHandle(record)
                values[pair[0]] = pair[1]
        finally:
            lib.MsiCloseHandle(view)
    finally:
        lib.MsiCloseHandle(db)
    assert values["ProductName"] == PRODUCT and str(uuid.UUID(values["UpgradeCode"].strip("{}"))) == UPGRADE
    assert re.fullmatch(r"0\.1\.1[0-9]{3}", values["ProductVersion"])
    assert "ALLUSERS" not in values, "PER_USER_PACKAGE_REQUIRED"
    return {key: values[key] for key in ["ProductName", "ProductVersion", "ProductCode", "UpgradeCode"]} | {"sha256": sha(path), "bytes": path.stat().st_size, "safetyVersion": values.get("CCB_DESKTOP_MSI_SAFETY")}


def related():
    lib = C.WinDLL("msi")
    lib.MsiEnumRelatedProductsW.argtypes = [W.LPCWSTR, W.DWORD, W.DWORD, W.LPWSTR]
    products = []
    for index in range(16):
        buffer = C.create_unicode_buffer(39)
        code = lib.MsiEnumRelatedProductsW("{" + UPGRADE + "}", 0, index, buffer)
        if code == 259:
            return products
        assert code == 0
        products.append(buffer.value)
    raise AssertionError("UNEXPECTED_RELATED_PRODUCT_COUNT")


def identity(program):
    values = []
    for path in (program / "app").glob("*.jar"):
        safe(path)
        with zipfile.ZipFile(path) as archive:
            if "desktop-build.properties" in archive.namelist():
                assert archive.getinfo("desktop-build.properties").file_size <= 4096
                values.append(dict(line.split("=", 1) for line in archive.read("desktop-build.properties").decode().splitlines()))
    assert len(values) == 1 and values[0]["packagingProfile"] == "synthetic"
    assert values[0]["installerVersionAssigned"] == "true" and values[0]["commit"] != "development"
    return values[0]


def unmanaged(program):
    return {str(path.relative_to(program)): sha(safe(path)) for path in program.rglob("UNMANAGED_SYNTHETIC*") if path.is_file()}


def install(base, installer, label, fail_after_remove=False):
    assert not (base / f"{label}-command.json").exists(), "NEW_EVIDENCE_LABEL_REQUIRED"
    info = msi_info(installer)
    assert info["safetyVersion"] == "1", "UNCORRECTED_MSI_CANNOT_BE_INSTALLED"
    previous = base / "INSTALLED.json"
    known = json.loads(previous.read_text()) if previous.exists() else None
    assert related() == ([] if known is None else [known["ProductCode"]]), "UNKNOWN_EXISTING_SYNTHETIC_INSTALLATION"
    program = base / "program"
    assert known is not None or not program.exists(), "NEW_INSTALL_TARGET_REQUIRED"
    before_unknown = unmanaged(program)
    if fail_after_remove:
        assert known is not None, "ROLLBACK_REQUIRES_OWNED_EXISTING_PRODUCT"
        assert tuple(map(int, info["ProductVersion"].split("."))) > tuple(map(int, known["ProductVersion"].split(".")))
        before_identity = identity(program)
        before_files = {str(path.relative_to(program)): sha(safe(path)) for path in program.rglob("*") if path.is_file()}
    command = [str(Path(os.environ["SystemRoot"]) / "System32/msiexec.exe"), "/i", str(installer), "/qn", "/norestart",
               "/l*v", str(base / f"{label}-msi-private.log"), "INSTALLDIR=" + str(program), "REBOOT=ReallySuppress", "MSIRESTARTMANAGERCONTROL=Disable"]
    if fail_after_remove:
        command.append("CCB_DESKTOP_SYNTHETIC_FAIL_AFTER_REMOVE=1")
    save(base / f"{label}-command.json", command)
    result = subprocess.run(command, timeout=180, creationflags=subprocess.CREATE_NO_WINDOW)
    save(base / f"{label}-result.json", {"exit": result.returncode, "source": info, "relatedProducts": related()})
    assert unmanaged(program) == before_unknown, "UNMANAGED_FILES_CHANGED"
    if fail_after_remove:
        assert result.returncode == 1603 and related() == [known["ProductCode"]], "ROLLBACK_PRODUCT_NOT_RESTORED"
        assert identity(program) == before_identity, "ROLLBACK_IDENTITY_CHANGED"
        after_files = {str(path.relative_to(program)): sha(safe(path)) for path in program.rglob("*") if path.is_file()}
        assert after_files == before_files, "ROLLBACK_PROGRAM_BYTES_CHANGED"
        save(base / f"{label}-rollback.json", {"allProgramFilesUnchanged": True, "files": len(before_files), "unknownFiles": len(before_unknown), "oldProductRestored": True})
        return
    assert result.returncode == 0, "NATIVE_INSTALL_NOT_COMPLETE"
    assert related() == [info["ProductCode"]]
    built = identity(program)
    assert built["installerVersion"] == info["ProductVersion"]
    assert built["commit"] == json.loads((base / "TASK_OWNER.json").read_text())["sourceCommit"]
    save(base / "INSTALLED.json", info)
    save(base / f"{label}-identity.json", built)


def reject_older(base, installer, label):
    assert not (base / f"{label}-result.json").exists(), "NEW_EVIDENCE_LABEL_REQUIRED"
    known = json.loads((base / "INSTALLED.json").read_text())
    old = msi_info(installer)
    assert tuple(map(int, old["ProductVersion"].split("."))) < tuple(map(int, known["ProductVersion"].split(".")))
    assert related() == [known["ProductCode"]]
    before = identity(base / "program")
    command = [str(Path(os.environ["SystemRoot"]) / "System32/msiexec.exe"), "/i", str(installer), "/qn", "/norestart",
               "/l*v", str(base / f"{label}-msi-private.log"), "INSTALLDIR=" + str(base / "program"), "REBOOT=ReallySuppress", "MSIRESTARTMANAGERCONTROL=Disable"]
    save(base / f"{label}-command.json", command)
    result = subprocess.run(command, timeout=180, creationflags=subprocess.CREATE_NO_WINDOW)
    save(base / f"{label}-result.json", {"exit": result.returncode, "source": old, "relatedProducts": related()})
    assert result.returncode != 0 and related() == [known["ProductCode"]], "OLD_INSTALLER_WAS_NOT_REJECTED"
    assert identity(base / "program") == before, "INSTALLED_BUILD_CHANGED_ON_REJECTION"


def uninstall(base, label):
    assert not (base / f"{label}-result.json").exists(), "NEW_EVIDENCE_LABEL_REQUIRED"
    known = json.loads((base / "INSTALLED.json").read_text())
    assert related() == [known["ProductCode"]], "UNKNOWN_SYNTHETIC_INSTALLATION"
    assert identity(base / "program")["installerVersion"] == known["ProductVersion"]
    before_unknown = unmanaged(base / "program")
    command = [str(Path(os.environ["SystemRoot"]) / "System32/msiexec.exe"), "/x", known["ProductCode"], "/qn", "/norestart",
               "/l*v", str(base / f"{label}-msi-private.log"), "REBOOT=ReallySuppress", "MSIRESTARTMANAGERCONTROL=Disable"]
    save(base / f"{label}-command.json", command)
    result = subprocess.run(command, timeout=180, creationflags=subprocess.CREATE_NO_WINDOW)
    save(base / f"{label}-result.json", {"exit": result.returncode, "relatedProducts": related()})
    assert result.returncode == 0 and not related()
    assert not (base / "program" / (PRODUCT + ".exe")).exists()
    assert unmanaged(base / "program") == before_unknown, "UNMANAGED_FILES_CHANGED"
    sentinel = base / "program/UNMANAGED_SYNTHETIC_KEEP.txt"
    assert sentinel.read_bytes() == b"SYNTHETIC_INSTALLER_UNMANAGED_FILE_MUST_SURVIVE"
    (base / "INSTALLED.json").rename(base / f"{label}-uninstalled.json")


class Startup(C.Structure):
    _fields_ = [("cb", W.DWORD), ("lpReserved", W.LPWSTR), ("lpDesktop", W.LPWSTR), ("lpTitle", W.LPWSTR),
                *[(key, W.DWORD) for key in ["dwX", "dwY", "dwXSize", "dwYSize", "dwXCountChars", "dwYCountChars", "dwFillAttribute", "dwFlags"]],
                ("wShowWindow", W.WORD), ("cbReserved2", W.WORD), ("lpReserved2", C.POINTER(C.c_byte)),
                ("hStdInput", W.HANDLE), ("hStdOutput", W.HANDLE), ("hStdError", W.HANDLE)]


class ProcessInfo(C.Structure):
    _fields_ = [("hProcess", W.HANDLE), ("hThread", W.HANDLE), ("pid", W.DWORD), ("tid", W.DWORD)]


def launch_close(base, label):
    assert not (base / f"{label}-stdout-private.log").exists(), "NEW_EVIDENCE_LABEL_REQUIRED"
    program = safe(base / "program")
    before = identity(program)
    kernel = C.WinDLL("kernel32", use_last_error=True)
    user = C.WinDLL("user32", use_last_error=True)
    kernel.CreateJobObjectW.restype = W.HANDLE
    kernel.CreateJobObjectW.argtypes = [C.c_void_p, W.LPCWSTR]
    kernel.AssignProcessToJobObject.argtypes = [W.HANDLE, W.HANDLE]
    kernel.QueryInformationJobObject.argtypes = [W.HANDLE, C.c_int, C.c_void_p, W.DWORD, C.c_void_p]
    kernel.ResumeThread.argtypes = [W.HANDLE]
    kernel.WaitForSingleObject.argtypes = [W.HANDLE, W.DWORD]
    kernel.GetExitCodeProcess.argtypes = [W.HANDLE, C.POINTER(W.DWORD)]
    kernel.CloseHandle.argtypes = [W.HANDLE]
    kernel.TerminateProcess.argtypes = [W.HANDLE, W.UINT]
    kernel.CreateProcessW.argtypes = [W.LPCWSTR, W.LPWSTR, C.c_void_p, C.c_void_p, W.BOOL, W.DWORD,
                                    C.c_void_p, W.LPCWSTR, C.POINTER(Startup), C.POINTER(ProcessInfo)]
    user.GetWindowThreadProcessId.argtypes = [W.HWND, C.POINTER(W.DWORD)]
    user.GetWindowTextW.argtypes = [W.HWND, W.LPWSTR, C.c_int]
    user.IsWindowVisible.argtypes = [W.HWND]
    user.PostMessageW.argtypes = [W.HWND, W.UINT, W.WPARAM, W.LPARAM]
    callback = C.WINFUNCTYPE(W.BOOL, W.HWND, W.LPARAM)
    user.EnumWindows.argtypes = [callback, W.LPARAM]
    job = kernel.CreateJobObjectW(None, None)
    assert job
    process = ProcessInfo()
    env = os.environ.copy()
    env.update({"LOCALAPPDATA": str(base / "local-appdata"), "APPDATA": str(base / "roaming"), "TEMP": str(base / "temp"),
                "TMP": str(base / "temp"), "JAVA_TOOL_OPTIONS": f"-Duser.home={base / 'home'} -Djava.io.tmpdir={base / 'temp'} -Dfile.encoding=UTF-8", "CCB_SHUTDOWN_TRACE": "1"})
    for key in ["JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"]:
        env.pop(key, None)
    environment = C.create_unicode_buffer("\0".join(f"{key}={value}" for key, value in sorted(env.items())) + "\0\0")
    exe = program / (PRODUCT + ".exe")
    startup = Startup(); startup.cb = C.sizeof(startup); startup.dwFlags = 0x101; startup.wShowWindow = 4
    with (base / f"{label}-stdout-private.log").open("wb") as out, (base / f"{label}-stderr-private.log").open("wb") as err, open(os.devnull, "rb") as stdin:
        for stream in [out, err, stdin]:
            os.set_inheritable(stream.fileno(), True)
        startup.hStdInput = msvcrt.get_osfhandle(stdin.fileno()); startup.hStdOutput = msvcrt.get_osfhandle(out.fileno()); startup.hStdError = msvcrt.get_osfhandle(err.fileno())
        started = kernel.CreateProcessW(str(exe), C.create_unicode_buffer('"' + str(exe) + '"'), None, None, True, 0x404,
                                        environment, str(program), C.byref(startup), C.byref(process))
        if not started:
            raise C.WinError(C.get_last_error())
        if not kernel.AssignProcessToJobObject(job, process.hProcess):
            failure_code = C.get_last_error()
            kernel.TerminateProcess(process.hProcess, 90)  # Still suspended; no application/data writer ran.
            raise RuntimeError(f"SUSPENDED_TEST_JOB_ASSIGN_FAILED native={failure_code}; cause not inferred")
        save(base / f"{label}-launch.json", {"pid": process.pid, "executable": str(exe), "identity": before,
                                          "jobAssignedBeforeResume": True})
        assert kernel.ResumeThread(process.hThread) != 0xFFFFFFFF
        kernel.CloseHandle(process.hThread)

        def pids():
            data = C.create_string_buffer(8 + C.sizeof(C.c_size_t) * 512)
            assert kernel.QueryInformationJobObject(job, 3, data, C.sizeof(data), None)
            count = W.DWORD.from_buffer(data, 4).value
            assert count <= 512
            return list((C.c_size_t * count).from_buffer(data, 8))

        maximum = 0
        window = None
        deadline = time.monotonic() + 40
        while window is None and time.monotonic() < deadline:
            owned = set(pids()); maximum = max(maximum, len(owned))
            found = []
            @callback
            def collect(hwnd, _):
                pid = W.DWORD(); user.GetWindowThreadProcessId(hwnd, C.byref(pid))
                if pid.value in owned and user.IsWindowVisible(hwnd):
                    text = C.create_unicode_buffer(256); user.GetWindowTextW(hwnd, text, 256)
                    found.append((hwnd, text.value))
                return True
            user.EnumWindows(collect, 0)
            matches = [handle for handle, title in found if title == "ChatChatBar"]
            if matches:
                window = matches[0]
            elif kernel.WaitForSingleObject(process.hProcess, 0) == 0:
                break
            else:
                time.sleep(0.1)
        assert window is not None, "SYNTHETIC_MAIN_WINDOW_NOT_OBSERVED"
        time.sleep(1)
        classpath = (REPO / "app/desktopApp/build/readonly-r0/runtime-classpath.txt").read_text()
        check_root = [r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8",
                      "-cp", classpath, "com.example.chatbar.desktop.DesktopWindowsPackageFixture", "busy", str(base)]
        checked = subprocess.run(check_root, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
        (base / f"{label}-root-busy-private.log").write_bytes(checked.stdout)
        assert checked.returncode == 0 and b"WINDOWS_SYNTHETIC_SELECTED_ROOT_BUSY_PASS" in checked.stdout, "NATIVE_SELECTED_ROOT_NOT_PROVEN"
        assert user.PostMessageW(window, 0x10, 0, 0), "OWNED_WINDOW_CLOSE_FAILED"
        deadline = time.monotonic() + 25
        while pids() and time.monotonic() < deadline:
            maximum = max(maximum, len(pids())); time.sleep(0.1)
        remaining = pids()
        code = W.DWORD(); assert kernel.GetExitCodeProcess(process.hProcess, C.byref(code))
        save(base / f"{label}-lifecycle.json", {"identity": before, "launcherExit": code.value, "maximumOwnedProcesses": maximum,
                                              "remainingOwnedProcesses": remaining, "normalOwnedWindowClose": True,
                                              "jobAssignedBeforeResume": True, "selectedRootBusyVerified": True, "providerActionsTriggered": False})
        assert not remaining and code.value == 0, "NATIVE_DRAIN_NOT_COMPLETE"
    kernel.CloseHandle(process.hProcess); kernel.CloseHandle(job)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["install", "fail-upgrade", "launch-close", "info", "reject-older", "uninstall"])
    parser.add_argument("--base", type=Path)
    parser.add_argument("--installer", type=Path)
    parser.add_argument("--label", default="native")
    args = parser.parse_args()
    assert os.name == "nt" and args.label.replace("-", "").isalnum()
    if args.action == "info":
        print(json.dumps(msi_info(args.installer)))
        return
    base = task(args.base)
    with (base / f"{args.label}-{args.action}-probe-source.py").open("xb") as snapshot:
        snapshot.write(Path(__file__).read_bytes())
    if args.action == "install":
        install(base, safe(args.installer), args.label)
    elif args.action == "fail-upgrade":
        install(base, safe(args.installer), args.label, fail_after_remove=True)
    elif args.action == "reject-older":
        reject_older(base, safe(args.installer), args.label)
    elif args.action == "uninstall":
        uninstall(base, args.label)
    else:
        launch_close(base, args.label)
    print("WINDOWS_SYNTHETIC_" + args.action.upper().replace("-", "_") + "_PASS")


if __name__ == "__main__":
    main()
