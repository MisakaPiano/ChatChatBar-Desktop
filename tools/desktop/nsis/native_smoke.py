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
import hashlib
from native_cwd_policy import owned_update_helper_cwd

JAVA = Path(os.environ.get('JAVA_HOME', 'C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot')) / 'bin/java.exe'
OWNER = 'CCB_DESKTOP_SYNTHETIC_WINDOWS_RUNTIME'


def require(value, code):
    if not value:
        raise RuntimeError(code)


def save(path, value):
    path.write_text(json.dumps(value, indent=2), encoding='utf-8')


def install_log(case, root):
    key = hashlib.sha256(str(root.absolute()).upper().encode('utf-8')).hexdigest().upper()
    path = case / 'local-appdata/ChatChatBarDesktopNSISInstallState' / (key + '.log')
    return path if path.exists() else root / 'lifecycle.log'  # pre-release1201–1203 evidence only


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
kernel.WaitForSingleObject.argtypes = [W.HANDLE, W.DWORD]
kernel.WaitForSingleObject.restype = W.DWORD
kernel.OpenProcess.restype = W.HANDLE
kernel.OpenProcess.argtypes = [W.DWORD, W.BOOL, W.DWORD]
kernel.ReadProcessMemory.argtypes = [W.HANDLE, C.c_void_p, C.c_void_p, C.c_size_t, C.POINTER(C.c_size_t)]
kernel.QueryFullProcessImageNameW.argtypes = [W.HANDLE, W.DWORD, W.LPWSTR, C.POINTER(W.DWORD)]
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


def process_image(pid):
    handle = kernel.OpenProcess(0x101000, False, pid)  # QUERY_LIMITED_INFORMATION | SYNCHRONIZE
    require(handle, 'PROCESS_IMAGE_OPEN_FAILED')
    try:
        text = C.create_unicode_buffer(32768)
        size = W.DWORD(len(text))
        if not kernel.QueryFullProcessImageNameW(handle, 0, text, C.byref(size)):
            error = C.get_last_error()
            code = W.DWORD()
            exit_read = bool(kernel.GetExitCodeProcess(handle, C.byref(code)))
            wait = kernel.WaitForSingleObject(handle, 0)
            # Observe the same process object; neither an exited nor an unidentified process
            # is silently accepted. Retain the original refusal with exact native diagnostics.
            raise RuntimeError(f'PROCESS_IMAGE_QUERY_FAILED pid={pid} win32={error} '
                               f'wait={wait} exitRead={exit_read} exitCode={code.value if exit_read else None}')
        return text.value
    finally:
        kernel.CloseHandle(handle)


def external_pending(case, install_root):
    key = hashlib.sha256(str(install_root.absolute()).lower().encode('utf-8')).hexdigest()
    journal = case / 'local-appdata/ChatChatBarDesktopActivation' / key / 'pending.json'
    return journal, json.loads(journal.read_text(encoding='utf-8'))['files'] if journal.exists() else []


def probe_external_files(case, install_root, label, window, previous, cold_paths=()):
    """Delivery only: actual packaged receiver and queue, never import confirmation."""
    journal, recovered = external_pending(case, install_root)
    require(recovered[:len(previous)] == previous, 'EXTERNAL_PENDING_CHANGED_DURING_REOPEN')
    cold = recovered[len(previous):]
    require([entry['path'] for entry in cold] == list(map(str, cold_paths)) and
            all(entry['attempted'] is False for entry in cold), 'PACKAGED_COLD_UNICODE_DELIVERY_NOT_CONFIRMED')
    require(all(path.read_bytes() == b'SYNTHETIC_COLD_EXTERNAL_DELIVERY' for path in cold_paths), 'COLD_EXTERNAL_SOURCE_CHANGED')
    previous_for_warm = recovered
    paths = [case / (label + '-角色 & one.json'), case / (label + '-第二份 file.cbsave')]
    content = b'SYNTHETIC_EXTERNAL_DELIVERY_ONLY_NOT_AN_IMPORT_PACKAGE'
    for path in paths:
        require(not path.exists(), 'EXTERNAL_PROBE_INPUT_ALREADY_EXISTS')
        path.write_bytes(content)
    original = (install_root / 'current.txt').read_bytes()
    pid = W.DWORD(); user.GetWindowThreadProcessId(window, C.byref(pid))
    result = subprocess.run([str(install_root / 'OpenChatBarFile.exe'), *map(str, paths)],
                            env=environment(case), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
    require(result.returncode == 0, 'PACKAGED_EXTERNAL_DELIVERY_FAILED')
    data = json.loads(journal.read_text(encoding='utf-8'))
    require(data['version'] == 1 and data['files'][:len(previous_for_warm)] == previous_for_warm, 'PENDING_QUEUE_NOT_RETAINED')
    added = data['files'][len(previous_for_warm):]
    require([entry['path'] for entry in added] == list(map(str, paths)) and
            all(entry['attempted'] is False for entry in added), 'EXTERNAL_ARGUMENTS_OR_CONFIRMATION_CHANGED')
    # A warm normal entry must also return while the same real business window stays alive.
    focus = subprocess.run([str(install_root / 'ChatChatBar.exe')], env=environment(case),
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
    after = W.DWORD(); user.GetWindowThreadProcessId(window, C.byref(after))
    require(focus.returncode == 0 and user.IsWindowVisible(window) and after.value == pid.value,
            'PACKAGED_WARM_FOCUS_NOT_CONFIRMED')
    require((install_root / 'current.txt').read_bytes() == original, 'EXTERNAL_DELIVERY_CHANGED_CURRENT')
    require(all(path.read_bytes() == content for path in paths), 'EXTERNAL_SOURCE_CHANGED')
    require(json.loads(journal.read_text(encoding='utf-8'))['files'] == data['files'], 'WARM_FOCUS_CHANGED_QUEUE')
    save(case / (label + '-external-delivery.json'), dict(kind='PACKAGED_EXTERNAL_DELIVERY_ONLY',
         previousPending=len(previous), preLaunchFilesVerified=previous, added=2, orderedUnicodePaths=True, noImportAttempted=True,
         coldDeliveredPaths=list(map(str, cold_paths)), coldOriginalUnchanged=True, preWarmFilesVerified=previous_for_warm,
         unchangedSources=True, sameVisibleBusinessPid=pid.value, normalWarmEntryReturned=True,
         importOrHumanAcceptance=False, providerCalls=False))


def launch_close(case, image, executable, arguments, install_root, label, default_profile=False, expect_failure=False, busy_installer=None, allow_update_helper=False, external_files=False, cold_paths=(), readiness_failure='NEW_VERSION_NOT_READY_OLD_ENTRY_RETAINED'):
    previous_pending = external_pending(case, install_root)[1] if external_files else None
    if external_files:
        save(case / (label + '-external-before-launch.json'), dict(files=previous_pending))
    job = kernel.CreateJobObjectW(None, None)
    require(job, 'JOB_CREATE_FAILED')
    startup = Startup(); startup.cb = C.sizeof(startup); startup.flags = 0x100
    process = Process()
    previous = (install_root / 'current.txt').read_text().strip() if expect_failure else None
    log_path = install_log(case, install_root)
    previous_log = log_path.read_text(encoding='utf-8') if log_path.exists() else ''
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

        if expect_failure:
            deadline = time.monotonic() + 150
            while pids() and time.monotonic() < deadline:
                time.sleep(.1)
            code = W.DWORD(); kernel.GetExitCodeProcess(process.handle, C.byref(code))
            current = (install_root / 'current.txt').read_text().strip()
            log = install_log(case, install_root).read_text(encoding='utf-8')
            result = dict(exitCode=code.value, remaining=pids(), oldEntryPreserved=current == previous,
                          newAppNotReadyObserved=('FAILED ' + readiness_failure) in log[len(previous_log):],
                          expectedReadinessFailure=readiness_failure, freshDefaultProfile=default_profile,
                          fakeCredentialRetentionVerified=False if default_profile else None)
            save(case / (label + '-result.json'), result)
            require(not result['remaining'] and code.value not in (0, 259) and result['oldEntryPreserved'] and result['newAppNotReadyObserved'], 'NATIVE_FAILURE_RETENTION_NOT_PROVEN')
            kernel.CloseHandle(process.handle); kernel.CloseHandle(job)
            if not default_profile:
                fixture(case, install_root / 'versions' / current, 'verify', label)
            print('NSIS_NATIVE_NEW_VERSION_FAILURE_RETAINED_PASS ' + label)
            return

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
        if window is None:
            code = W.DWORD()
            code_read = bool(kernel.GetExitCodeProcess(process.handle, C.byref(code)))
            after_log = install_log(case, install_root)
            log_text = after_log.read_text(encoding='utf-8') if after_log.exists() else ''
            pending_error = None
            try:
                pending = external_pending(case, install_root)[1] if external_files else None
            except Exception as failure:
                pending = None; pending_error = type(failure).__name__
            save(case / (label + '-startup-failure-result.json'), dict(
                passed=False, failure='MAIN_WINDOW_AND_ENTRY_NOT_CONFIRMED', initialPid=process.pid,
                exitCodeRead=code_read, exitCode=code.value if code_read else None, remaining=pids(),
                afterQueue=pending, queueReadErrorType=pending_error,
                thisCallManagerLog=log_text[len(previous_log):] if log_text.startswith(previous_log) else None,
                managerLogPrefixRetained=log_text.startswith(previous_log)))
            raise RuntimeError('MAIN_WINDOW_AND_ENTRY_NOT_CONFIRMED')
        current = (install_root / 'current.txt').read_text().strip()
        program = install_root / 'versions' / current
        if default_profile:
            require((case / 'local-appdata/ChatChatBarDesktop').is_dir(), 'DEFAULT_DATA_ROOT_NOT_CREATED')
        else:
            fixture(case, program, 'busy', label)
        if busy_installer:
            original_manifest = (program / 'install-manifest.json').read_bytes()
            blocked = subprocess.run([str(busy_installer), '/S', '/D=' + str(install_root)],
                                     env=environment(case), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
            save(case / (label + '-busy-installer.json'), dict(exitCode=blocked.returncode,
                 currentUnchanged=(install_root / 'current.txt').read_text().strip() == current,
                 manifestUnchanged=(program / 'install-manifest.json').read_bytes() == original_manifest))
            require(blocked.returncode == 2 and (install_root / 'current.txt').read_text().strip() == current and
                    (program / 'install-manifest.json').read_bytes() == original_manifest, 'RUNNING_PROGRAM_INSTALL_NOT_REFUSED')
        if external_files:
            probe_external_files(case, install_root, label, window, previous_pending, cold_paths)
        active = pids()
        try:
            directories = {str(pid): cwd(pid) for pid in active}
            images = {str(pid): process_image(pid) for pid in active}
        except RuntimeError as error:
            save(case / (label + '-process-query-result.json'), dict(
                initialJobPids=active, currentJobPids=pids(), failure=str(error), passed=False))
            raise
        update_helpers = {pid: directory for pid, directory in directories.items()
                          if Path(directory).resolve().is_relative_to(program.resolve()) and allow_update_helper and
                          owned_update_helper_cwd(program, images[pid], directory)}
        save(case / (label + '-running.json'), dict(pids=active, nativeCurrentDirectories=directories,
             processImages=images, current=current, installRoot=str(install_root), jobAssignedBeforeResume=True,
             verifiedTemporaryUpdaterCwds=update_helpers, allVersionCwdsClear=not any(
                 Path(d).resolve().is_relative_to(program.resolve()) for d in directories.values())))
        require(all(pid in update_helpers or not Path(d).resolve().is_relative_to(program.resolve())
                    for pid, d in directories.items()), 'VERSION_CWD_STILL_OWNED')
        require(user.PostMessageW(window, 0x10, 0, 0), 'NORMAL_CLOSE_FAILED')
        deadline = time.monotonic() + 45
        while pids() and time.monotonic() < deadline:
            time.sleep(.1)
        remaining = pids()
        code = W.DWORD(); kernel.GetExitCodeProcess(process.handle, C.byref(code))
        save(case / (label + '-result.json'), dict(exitCode=code.value, remaining=remaining, current=current,
             normalWindowClose=True, rootBusyVerified=not default_profile, freshDefaultProfile=default_profile,
             verifiedTemporaryUpdaterCwds=update_helpers, providerCalls=False))
        require(not remaining and code.value == 0, 'NATIVE_NORMAL_SHUTDOWN_FAILED')
    kernel.CloseHandle(process.handle); kernel.CloseHandle(job)
    if not default_profile:
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
    parser.add_argument('--fresh-default-profile', action='store_true', help='New empty profile, no companion/data/credential claim')
    parser.add_argument('--expect-install-failure', action='store_true', help='Explicit task-owned fault installer only; old entry/data must remain')
    parser.add_argument('--ci-fresh-readiness-failure', choices=['NEW_VERSION_NOT_READY_OLD_ENTRY_RETAINED', 'READY_IDENTITY_REFUSED'],
                        help='Cloud-only fresh-profile fault probe; no companion, credential or custom-data retention claim')
    parser.add_argument('--probe-busy-installer', type=Path, help='While the owned app is running, this installer must reject before changing the current version')
    parser.add_argument('--allow-owned-update-helper-cwd', action='store_true', help='Explicitly classify only the inventoried updater Python; full Job exit still required')
    parser.add_argument('--probe-external-files', action='store_true', help='Deliver synthetic paths to the actual packaged warm receiver without confirming any import')
    parser.add_argument('--cold-external-file', action='store_true', help='Open a synthetic Unicode path through the installed bridge after the previous process has exited')
    parser.add_argument('--cold-external-ascii', action='store_true', help='Diagnostic control for cold delivery using only an ASCII filename; requires --cold-external-file')
    parser.add_argument('--cold-external-matrix', action='store_true', help='One actual cold batch with ASCII ampersand and Unicode names with and without ampersands')
    parser.add_argument('--ci-source', type=Path, help='Frozen source namespace only in this repository Windows CI')
    args = parser.parse_args()
    approved = Path(__file__).resolve().parents[3] / 'app/desktopApp/build/preview-continuous-private-20261025'
    if args.ci_source:
        require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('GITHUB_REPOSITORY') == 'MisakaPiano/ChatChatBar-Desktop', 'CI_SOURCE_REQUIRED')
        source = args.ci_source.resolve(strict=True)
        require(source == Path(os.environ['CCB_CI_SOURCE']).resolve(strict=True), 'CI_SOURCE_SCOPE')
        require(subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() == os.environ['CCB_CANDIDATE_SOURCE'], 'CI_SOURCE_IDENTITY')
        approved = source / 'app/desktopApp/build/preview-continuous-private-20261025'
    case = args.case.absolute()
    require(case.is_relative_to(approved) and case.name.startswith('windows-runtime-nsis-'), 'SYNTHETIC_CASE_SCOPE')
    if not case.exists():
        require(args.fresh_default_profile or (args.companion and args.companion.is_file()), 'COMPANION_REQUIRED')
        case.mkdir()
        save(case / 'TASK_OWNER.json', dict(kind=OWNER))
        for name in ['local-appdata', 'roaming', 'temp', 'home']:
            (case / name).mkdir()
        if not args.fresh_default_profile:
            shutil.copyfile(args.companion, case / 'native-fixture.jar')
            fixture(case, args.image, 'seed', 'seed')
    require(json.loads((case / 'TASK_OWNER.json').read_text())['kind'] == OWNER, 'CASE_OWNER_REFUSED')
    exe = args.installer if args.installer else args.root / 'ChatChatBar.exe'
    arguments = ['/S', '/D=' + str(args.root)] if args.installer else []
    cold_paths = []
    require(not args.cold_external_ascii or args.cold_external_file, 'ASCII_CONTROL_REQUIRES_COLD')
    require(not args.cold_external_matrix or (args.cold_external_file and not args.cold_external_ascii), 'MATRIX_REQUIRES_COLD_WITHOUT_ASCII_CONTROL')
    if args.cold_external_file:
        require(args.installer is None and args.probe_external_files, 'COLD_EXTERNAL_REQUIRES_EXISTING_INSTALL_AND_QUEUE_PROBE')
        suffixes = ['-ASCII & source.json', '-中文 source.json', '-中文 & source.json', '-日本語 😀 source.json'] if args.cold_external_matrix else [
            '-cold source.json' if args.cold_external_ascii else '-冷启动角色 & source.json']
        cold_paths = [case / (args.label + suffix) for suffix in suffixes]
        for path in cold_paths:
            require(not path.exists(), 'COLD_EXTERNAL_INPUT_ALREADY_EXISTS')
            path.write_bytes(b'SYNTHETIC_COLD_EXTERNAL_DELIVERY')
        exe = args.root / 'OpenChatBarFile.exe'; arguments = list(map(str, cold_paths))
    if args.ci_fresh_readiness_failure:
        require(args.ci_source and args.fresh_default_profile and args.expect_install_failure and args.installer and
                (args.root / 'current.txt').is_file(), 'CI_FRESH_FAILURE_REQUIRES_EXISTING_OWNED_INSTALL')
    require(not args.expect_install_failure or (args.installer and
            (not args.fresh_default_profile or args.ci_fresh_readiness_failure)), 'FAILURE_DRIVER_REQUIRES_INSTALLED_SYNTHETIC_PROFILE')
    launch_close(case, args.image, exe, arguments, args.root, args.label, args.fresh_default_profile, args.expect_install_failure,
                 args.probe_busy_installer, args.allow_owned_update_helper_cwd, args.probe_external_files, cold_paths,
                 args.ci_fresh_readiness_failure or 'NEW_VERSION_NOT_READY_OLD_ENTRY_RETAINED')


if __name__ == '__main__':
    main()
