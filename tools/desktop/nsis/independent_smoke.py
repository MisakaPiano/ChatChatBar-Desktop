"""Explicit Windows CI native launch/normal-close of one new independent ZIP case.

No provider credentials, data seeding, process killing or user-root repair. Not pixel acceptance.
"""
import argparse
import ctypes as C
from ctypes import wintypes as W
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import zipfile


def require(value, code):
    if not value:
        raise RuntimeError(code)


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def main():
    require(os.name == 'nt' and os.environ.get('GITHUB_ACTIONS') == 'true', 'WINDOWS_CI_REQUIRED')
    require(os.environ.get('GITHUB_REPOSITORY') == 'MisakaPiano/ChatChatBar-Desktop', 'REPOSITORY_REQUIRED')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--archive', required=True, type=Path)
    parser.add_argument('--receipt', required=True, type=Path)
    parser.add_argument('--audit', required=True, type=Path)
    parser.add_argument('--case', required=True, type=Path)
    parser.add_argument('--version', required=True)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--outside-bootstrap', required=True, type=Path)
    args = parser.parse_args()
    source = args.source.resolve(strict=True)
    require(source == Path(os.environ['CCB_CI_SOURCE']).resolve(strict=True), 'SOURCE_SCOPE')
    require(subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() == args.commit, 'SOURCE_IDENTITY')
    approved = source / 'app/desktopApp/build/preview-continuous-private-20261025'
    case = args.case.absolute()
    require(case.parent == approved and case.name.startswith('windows-runtime-independent-'), 'CASE_SCOPE')
    require(approved.resolve(strict=True) == approved and not case.exists(), 'NEW_CANONICAL_CASE_REQUIRED')
    receipt = json.loads(args.receipt.read_text(encoding='utf-8'))
    require(receipt['version'] == args.version and receipt['commit'] == args.commit and sha(args.archive) == receipt['sha256'], 'ARCHIVE_IDENTITY')
    audit = json.loads(args.audit.read_text(encoding='utf-8'))
    case.mkdir()
    (case / 'TASK_OWNER.json').write_text(json.dumps({'kind': 'CCB_DESKTOP_CI_INDEPENDENT_NATIVE_CASE', 'source': args.commit}), encoding='utf-8')
    root = case / 'independent'
    root.mkdir()
    with zipfile.ZipFile(args.archive) as archive:
        require(archive.testzip() is None, 'ARCHIVE_CRC')
        for item in archive.infolist():
            require((root / item.filename).resolve().is_relative_to(root.resolve()), 'ARCHIVE_ESCAPE')
            require((item.external_attr >> 16) & 0o170000 != 0o120000, 'ARCHIVE_LINK')
        archive.extractall(root)
    for item in audit['files']:
        path = root / 'Program' / item['path']
        require(path.resolve().is_relative_to((root / 'Program').resolve()), 'PROGRAM_ESCAPE')
        require(path.stat().st_size == item['bytes'] and sha(path) == item['sha256'], 'PROGRAM_IDENTITY')
    require(not (root / 'Program/portable.flag').exists() and not list((root / 'UserData').iterdir()), 'FRESH_INDEPENDENT_PROFILE_REQUIRED')
    outside = args.outside_bootstrap.absolute()
    require(outside.resolve().is_relative_to(approved), 'OUTSIDE_CONTROL_SCOPE')
    before = sha(outside) if outside.is_file() else None
    kernel = C.WinDLL('kernel32', use_last_error=True)
    user = C.WinDLL('user32', use_last_error=True)
    psapi = C.WinDLL('psapi')
    kernel.OpenProcess.restype = W.HANDLE
    kernel.OpenProcess.argtypes = [W.DWORD, W.BOOL, W.DWORD]
    kernel.CloseHandle.argtypes = [W.HANDLE]
    kernel.QueryFullProcessImageNameW.argtypes = [W.HANDLE, W.DWORD, W.LPWSTR, C.POINTER(W.DWORD)]
    user.PostMessageW.argtypes = [W.HWND, W.UINT, W.WPARAM, W.LPARAM]
    program_prefix = str((root / 'Program').resolve()).lower() + os.sep

    def owned():
        ids = (W.DWORD * 4096)()
        used = W.DWORD()
        require(psapi.EnumProcesses(ids, C.sizeof(ids), C.byref(used)), 'PROCESS_ENUMERATION')
        result = []
        for pid in ids[:used.value // C.sizeof(W.DWORD)]:
            handle = kernel.OpenProcess(0x1000, False, pid)
            if not handle:
                continue
            try:
                text = C.create_unicode_buffer(32768)
                size = W.DWORD(len(text))
                if kernel.QueryFullProcessImageNameW(handle, 0, text, C.byref(size)) and text.value.lower().startswith(program_prefix):
                    result.append(pid)
            finally:
                kernel.CloseHandle(handle)
        return result

    callback = C.WINFUNCTYPE(W.BOOL, W.HWND, W.LPARAM)
    user.EnumWindows.argtypes = [callback, W.LPARAM]
    user.IsWindowVisible.argtypes = [W.HWND]
    user.GetWindowThreadProcessId.argtypes = [W.HWND, C.POINTER(W.DWORD)]
    user.GetWindowTextW.argtypes = [W.HWND, W.LPWSTR, C.c_int]

    def windows():
        pids = set(owned())
        result = []

        @callback
        def visit(hwnd, unused):
            pid = W.DWORD()
            user.GetWindowThreadProcessId(hwnd, C.byref(pid))
            if pid.value in pids and user.IsWindowVisible(hwnd):
                title = C.create_unicode_buffer(256)
                user.GetWindowTextW(hwnd, title, 256)
                if title.value == 'ChatChatBar':
                    result.append((hwnd, pid.value))
            return True
        user.EnumWindows(visit, 0)
        return result

    result = dict(kind='CI_INDEPENDENT_ZIP_ACTUAL_LAUNCHER', version=args.version, source=args.commit,
                  passed=False, programFilesVerified=len(audit['files']), userDataSeeded=False,
                  providerCalls=False, manualUserAcceptance=False, pixelsVerified=False,
                  processDrainScope='EXECUTABLE_PATH_UNDER_OWN_PROGRAM', externalExecutableDescendantsVerified=False)
    try:
        launch = subprocess.run([os.environ.get('COMSPEC', 'C:/Windows/System32/cmd.exe'), '/d', '/c', str(root / '启动独立资料版.cmd')],
                                cwd=root, creationflags=subprocess.CREATE_NO_WINDOW,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
        require(launch.returncode == 0, 'LAUNCHER_EXIT')
        deadline = time.monotonic() + 120
        found = []
        while time.monotonic() < deadline:
            found = windows()
            if found and (root / 'UserData/Local/ChatChatBarDesktop/entities').is_dir():
                break
            time.sleep(.25)
        require(len(found) == 1 and (root / 'UserData/Local/ChatChatBarDesktop/.ccb-desktop.lock').is_file(), 'INDEPENDENT_WINDOW_AND_ROOT')
        result['ownDataAndLockCreated'] = True
        require(user.PostMessageW(found[0][0], 0x10, 0, 0), 'NORMAL_CLOSE')
        deadline = time.monotonic() + 45
        while owned() and time.monotonic() < deadline:
            time.sleep(.25)
        require(not owned(), 'PROCESS_DRAIN')
        result['normalCloseAndAllProgramProcessesExited'] = True
        result['passed'] = True
    finally:
        result['outsideBootstrapUnchanged'] = (sha(outside) if outside.is_file() else None) == before
        result['passed'] = result['passed'] and result['outsideBootstrapUnchanged']
        (case / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        print(json.dumps(result), flush=True)
    require(result['passed'], 'INDEPENDENT_VALIDATION_FAILED')


if __name__ == '__main__':
    main()
