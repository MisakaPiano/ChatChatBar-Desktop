"""Opt-in Windows NSIS/app lifecycle driver using an existing synthetic companion.

Only creates a new task-owned profile or reuses its explicit owner. No provider calls,
process killing, real-user data, installer registry repair, or recursive cleanup.
"""
import argparse
import ctypes as C
from ctypes import wintypes as W
import json
import msvcrt
import os
from pathlib import Path
import shutil
import subprocess
import time

JAVA = Path(os.environ.get('JAVA_HOME', 'C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot')) / 'bin/java.exe'
OWNER = 'CCB_DESKTOP_SYNTHETIC_WINDOWS_RUNTIME'


def require(value, code):
    if not value:
        raise RuntimeError(code)


def save(path, value):
    path.write_text(json.dumps(value, indent=2), encoding='utf-8')


def environment(case):
    env = dict(os.environ)
    for key in ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CCB_DESKTOP_READY_PATH', 'CCB_DESKTOP_READY_NONCE']:
        env.pop(key, None)
    env.update(LOCALAPPDATA=str(case / 'local-appdata'), APPDATA=str(case / 'roaming'),
               TEMP=str(case / 'temp'), TMP=str(case / 'temp'), CCB_SHUTDOWN_TRACE='1')
    env['JAVA_TOOL_OPTIONS'] = '-Duser.home=' + str(case / 'home') + ' -Djava.io.tmpdir=' + str(case / 'temp') + ' -Dfile.encoding=UTF-8'
    return env


def fixture(case, image, mode, label):
    jars = sorted((image / 'app').glob('*.jar'))
    require(jars, 'PRODUCTION_JARS_MISSING')
    cp = os.pathsep.join(str(p) for p in [case / 'native-fixture.jar', *jars])
    command = [str(JAVA), '-Djava.awt.headless=true', '-cp', cp,
               'com.example.chatbar.desktop.DesktopWindowsPackageFixture', mode, str(case)]
    result = subprocess.run(command, env=environment(case), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    (case / (label + '-fixture.log')).write_bytes(result.stdout)
    require(result.returncode == 0, 'SYNTHETIC_FIXTURE_' + mode.upper() + '_FAILED')


class Startup(C.Structure):
    _fields_ = [('cb', W.DWORD), ('reserved', W.LPWSTR), ('desktop', W.LPWSTR), ('title', W.LPWSTR),
                ('x', W.DWORD), ('y', W.DWORD), ('xs', W.DWORD), ('ys', W.DWORD), ('xc', W.DWORD),
                ('yc', W.DWORD), ('fill', W.DWORD), ('flags', W.DWORD), ('show', W.WORD),
                ('reserved2', W.WORD), ('bytes2', C.c_void_p), ('stdin', W.HANDLE), ('stdout', W.HANDLE), ('stderr', W.HANDLE)]


class Process(C.Structure):
    _fields_ = [('handle', W.HANDLE), ('thread', W.HANDLE), ('pid', W.DWORD), ('tid', W.DWORD)]


kernel = C.WinDLL('kernel32', use_last_error=True)
user = C.WinDLL('user32', use_last_error=True)
kernel.CreateJobObjectW.restype = W.HANDLE
kernel.CreateJobObjectW.argtypes = [C.c_void_p, W.LPCWSTR]
kernel.CreateProcessW.argtypes = [W.LPCWSTR, W.LPWSTR, C.c_void_p, C.c_void_p, W.BOOL, W.DWORD, C.c_void_p, W.LPCWSTR, C.POINTER(Startup), C.POINTER(Process)]
kernel.AssignProcessToJobObject.argtypes = [W.HANDLE, W.HANDLE]
kernel.QueryInformationJobObject.argtypes = [W.HANDLE, C.c_int, C.c_void_p, W.DWORD, C.c_void_p]
kernel.ResumeThread.argtypes = [W.HANDLE]
kernel.CloseHandle.argtypes = [W.HANDLE]
kernel.GetExitCodeProcess.argtypes = [W.HANDLE, C.POINTER(W.DWORD)]
kernel.OpenProcess.restype = W.HANDLE
kernel.OpenProcess.argtypes = [W.DWORD, W.BOOL, W.DWORD]
kernel.ReadProcessMemory.argtypes = [W.HANDLE, C.c_void_p, C.c_void_p, C.c_size_t, C.POINTER(C.c_size_t)]
callback = C.WINFUNCTYPE(W.BOOL, W.HWND, W.LPARAM)
user.EnumWindows.argtypes = [callback, W.LPARAM]
user.GetWindowThreadProcessId.argtypes = [W.HWND, C.POINTER(W.DWORD)]
user.GetWindowTextW.argtypes = [W.HWND, W.LPWSTR, C.c_int]
user.IsWindowVisible.argtypes = [W.HWND]
user.PostMessageW.argtypes = [W.HWND, W.UINT, W.WPARAM, W.LPARAM]


def cwd(pid):
    require(C.sizeof(C.c_void_p) == 8, 'X64_DRIVER_REQUIRED')
    h = kernel.OpenProcess(0x410, False, pid)
    require(h, 'PROCESS_CWD_OPEN_FAILED')
    try:
        ntdll = C.WinDLL('ntdll')
        ntdll.NtQueryInformationProcess.argtypes = [W.HANDLE, C.c_int, C.c_void_p, W.ULONG, C.c_void_p]
        info = (C.c_void_p * 6)()
        require(ntdll.NtQueryInformationProcess(h, 0, info, C.sizeof(info), None) == 0, 'PROCESS_PEB_FAILED')

        def read(address, length):
            data = C.create_string_buffer(length)
            require(kernel.ReadProcessMemory(h, address, data, length, None), 'PROCESS_MEMORY_FAILED')
            return data.raw

        params = int.from_bytes(read(info[1] + 0x20, 8), 'little')
        value = read(params + 0x38, 16)
        return read(int.from_bytes(value[8:16], 'little'), int.from_bytes(value[:2], 'little')).decode('utf-16-le')
    finally:
        kernel.CloseHandle(h)


def launch_close(case, image, executable, arguments, install_root, label):
    job = kernel.CreateJobObjectW(None, None)
    require(job, 'JOB_CREATE_FAILED')
    startup = Startup(); startup.cb = C.sizeof(startup); startup.flags = 0x100
    process = Process()
    env = C.create_unicode_buffer('\0'.join(k + '=' + v for k, v in sorted(environment(case).items())) + '\0\0')
    with (case / (label + '-stdout.log')).open('wb') as out, (case / (label + '-stderr.log')).open('wb') as err, open(os.devnull, 'rb') as stdin:
        for stream in (out, err, stdin):
            os.set_inheritable(stream.fileno(), True)
        startup.stdin = msvcrt.get_osfhandle(stdin.fileno()); startup.stdout = msvcrt.get_osfhandle(out.fileno()); startup.stderr = msvcrt.get_osfhandle(err.fileno())
        command = subprocess.list2cmdline([str(executable), *arguments])
        require(kernel.CreateProcessW(str(executable), C.create_unicode_buffer(command), None, None, True, 0x404,
                                      env, str(case), C.byref(startup), C.byref(process)), 'CREATE_PROCESS_FAILED')
        require(kernel.AssignProcessToJobObject(job, process.handle), 'SUSPENDED_JOB_ASSIGN_FAILED')
        require(kernel.ResumeThread(process.thread) != 0xffffffff, 'RESUME_FAILED')
        kernel.CloseHandle(process.thread)

        def pids():
            data = C.create_string_buffer(8 + 512 * 8)
            require(kernel.QueryInformationJobObject(job, 3, data, C.sizeof(data), None), 'JOB_QUERY_FAILED')
            count = W.DWORD.from_buffer(data, 4).value
            require(count <= 512, 'JOB_LIMIT')
            return list((C.c_size_t * count).from_buffer(data, 8))

        window = None
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            found = []
            owned = set(pids())

            @callback
            def collect(hwnd, unused):
                pid = W.DWORD(); user.GetWindowThreadProcessId(hwnd, C.byref(pid))
                if pid.value in owned and user.IsWindowVisible(hwnd):
                    title = C.create_unicode_buffer(256); user.GetWindowTextW(hwnd, title, 256)
                    if title.value == 'ChatChatBar':
                        found.append(hwnd)
                return True

            user.EnumWindows(collect, 0)
            pointer = install_root / 'current.txt'
            if found and pointer.exists():
                code = W.DWORD(); kernel.GetExitCodeProcess(process.handle, C.byref(code))
                if not arguments or code.value != 259:
                    require(not arguments or code.value == 0, 'INSTALLER_NONZERO')
                    window = found[0]
                    break
            require(owned, 'ALL_PROCESSES_EXITED_BEFORE_READY')
            time.sleep(.1)
        require(window is not None, 'MAIN_WINDOW_AND_ENTRY_NOT_CONFIRMED')
        current = (install_root / 'current.txt').read_text().strip()
        program = install_root / 'versions' / current
        fixture(case, program, 'busy', label)
        active = pids()
        directories = {str(pid): cwd(pid) for pid in active}
        save(case / (label + '-running.json'), dict(pids=active, nativeCurrentDirectories=directories,
             current=current, installRoot=str(install_root), jobAssignedBeforeResume=True))
        require(all(not Path(d).resolve().is_relative_to(program.resolve()) for d in directories.values()), 'VERSION_CWD_STILL_OWNED')
        require(user.PostMessageW(window, 0x10, 0, 0), 'NORMAL_CLOSE_FAILED')
        deadline = time.monotonic() + 45
        while pids() and time.monotonic() < deadline:
            time.sleep(.1)
        remaining = pids()
        code = W.DWORD(); kernel.GetExitCodeProcess(process.handle, C.byref(code))
        save(case / (label + '-result.json'), dict(exitCode=code.value, remaining=remaining, current=current,
             normalWindowClose=True, rootBusyVerified=True, providerCalls=False))
        require(not remaining and code.value == 0, 'NATIVE_NORMAL_SHUTDOWN_FAILED')
    kernel.CloseHandle(process.handle); kernel.CloseHandle(job)
    fixture(case, program, 'verify', label)
    print('NSIS_NATIVE_LIFECYCLE_PASS ' + label)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case', type=Path, required=True)
    parser.add_argument('--image', type=Path, required=True)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--companion', type=Path)
    parser.add_argument('--installer', type=Path)
    parser.add_argument('--label', required=True)
    args = parser.parse_args()
    approved = Path(__file__).resolve().parents[3] / 'app/desktopApp/build/preview-continuous-private-20261025'
    case = args.case.absolute()
    require(case.is_relative_to(approved) and case.name.startswith('windows-runtime-nsis-'), 'SYNTHETIC_CASE_SCOPE')
    if not case.exists():
        require(args.companion and args.companion.is_file(), 'COMPANION_REQUIRED')
        case.mkdir()
        save(case / 'TASK_OWNER.json', dict(kind=OWNER))
        for name in ['local-appdata', 'roaming', 'temp', 'home']:
            (case / name).mkdir()
        shutil.copyfile(args.companion, case / 'native-fixture.jar')
        fixture(case, args.image, 'seed', 'seed')
    require(json.loads((case / 'TASK_OWNER.json').read_text())['kind'] == OWNER, 'CASE_OWNER_REFUSED')
    exe = args.installer if args.installer else args.root / 'ChatChatBar.exe'
    arguments = ['/S', '/D=' + str(args.root)] if args.installer else []
    launch_close(case, args.image, exe, arguments, args.root, args.label)


if __name__ == '__main__':
    main()
