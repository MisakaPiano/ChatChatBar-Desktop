if not __debug__: raise SystemExit("VALIDATION_REQUIRED")
import ctypes as C
from ctypes import wintypes as W
import json,msvcrt,os,subprocess,time,zipfile
from pathlib import Path
REPO=Path('H:\\ChatChatBar-Desktop')
TASKS=REPO/"app/desktopApp/build/preview-continuous-private-20261025"
PRODUCT="ChatChatBarDesktop"
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

def identity(program):
    values = []
    for path in (program / "app").glob("*.jar"):
        safe(path)
        with zipfile.ZipFile(path) as archive:
            if "desktop-build.properties" in archive.namelist():
                assert archive.getinfo("desktop-build.properties").file_size <= 4096
                values.append(dict(line.split("=", 1) for line in archive.read("desktop-build.properties").decode().splitlines()))
    assert len(values) == 1 and values[0]["packagingProfile"] == "desktop"
    assert values[0]["installerVersionAssigned"] == "true" and values[0]["commit"] != "development"
    return values[0]

class Startup(C.Structure):
    _fields_ = [("cb", W.DWORD), ("lpReserved", W.LPWSTR), ("lpDesktop", W.LPWSTR), ("lpTitle", W.LPWSTR),
                *[(key, W.DWORD) for key in ["dwX", "dwY", "dwXSize", "dwYSize", "dwXCountChars", "dwYCountChars", "dwFillAttribute", "dwFlags"]],
                ("wShowWindow", W.WORD), ("cbReserved2", W.WORD), ("lpReserved2", C.POINTER(C.c_byte)),
                ("hStdInput", W.HANDLE), ("hStdOutput", W.HANDLE), ("hStdError", W.HANDLE)]

class ProcessInfo(C.Structure):
    _fields_ = [("hProcess", W.HANDLE), ("hThread", W.HANDLE), ("pid", W.DWORD), ("tid", W.DWORD)]

def launch_close(base, label):
    assert not (base / f"{label}-stdout-private.log").exists(), "NEW_EVIDENCE_LABEL_REQUIRED"
    assert base == Path('H:\\ChatChatBar-Desktop\\app\\desktopApp\\build\\preview-continuous-private-20261025\\windows-runtime-candidate-1010')
    program = safe(Path('I:\\CCB-Desktop-Preview-Private-Candidate-1010-20261025\\compose\\binaries\\main\\app\\ChatChatBarDesktop'))
    before = identity(program)
    assert before["commit"] == '946c3791153634b0af65286313a497d141d5c598' and before["version"] == "0.1.0-preview.1010" and before["installerVersion"] == "0.1.1010"
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
        deadline = time.monotonic() + 180
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
        classpath = (base / "frozen-classpath.private.txt").read_text(encoding="utf-8")
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

if __name__ == "__main__":
    import sys
    assert len(sys.argv)==2 and sys.argv[1] in ("first", "second")
    launch_close(task(Path('H:\\ChatChatBar-Desktop\\app\\desktopApp\\build\\preview-continuous-private-20261025\\windows-runtime-candidate-1010')), "candidate-1010-"+sys.argv[1])
    print("CANDIDATE_1010_NATIVE_LIFECYCLE_PASS")
