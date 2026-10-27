"""One-time cleanup of TWO explicitly user-approved historical synthetic AVDs.

No Android/ADB invocation. No arbitrary path argument. Ordinary permissions only.
Plan and apply are separate invocations. Private manifest never enters Git.
"""
import ctypes as C
from ctypes import wintypes as W
from contextlib import ExitStack
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import StorageAudit as a

TARGETS = ('app/desktopApp/build/p11-interop-20261022/avds',
           'app/desktopApp/build/p11-moments-poc-20261022/avds')

def active_processes():
    command = "@(Get-Process | Where-Object { $_.ProcessName -match '^(java|javaw|gradle|kotlinc|idea64|emulator|qemu.*|studio64|adb)$' } | Select-Object -ExpandProperty ProcessName) | ConvertTo-Json -Compress"
    result = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', command], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError('PROCESS_OBSERVATION_UNAVAILABLE')
    return json.loads(result.stdout) if result.stdout.strip() else []

def manifest(repo, target):
    if target not in TARGETS or not a.contained(repo, repo / target):
        raise RuntimeError('NOT_EXACT_AUTHORIZED_TARGET')
    root = repo / target
    nodes = [(root, root.lstat(), 'DIR'), *a.walk(root)]
    result = []
    for p, s, kind in nodes:
        if kind not in ('DIR', 'FILE') or not s.st_ino or (kind == 'FILE' and s.st_nlink != 1):
            raise RuntimeError('UNSAFE_OR_UNKNOWN_NODE')
        result.append(dict(path=p.relative_to(repo).as_posix(), kind=kind, identity=list(a.identity(s))))
    return sorted(result, key=lambda r: r['path'])

def evidence_binding():
    prefixes = ['docs/desktop/evidence/p11-interop-20261022', 'docs/desktop/evidence/p11-moments-poc-20261022']
    tree = subprocess.check_output(['git', 'ls-tree', '-r', 'HEAD', '--', *prefixes], cwd=a.REPO).decode()
    counts = dict(files=0, bytes=0, exactRaw=0, canonicalText=0)
    for line in tree.splitlines():
        meta, relative = line.split('\t', 1)
        blob = meta.split()[2]
        p = a.REPO / relative
        if not a.contained(a.REPO, p):
            raise RuntimeError('RETAINED_EVIDENCE_PATH_UNSAFE')
        b = p.read_bytes()
        def git_hash(raw):
            return hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        if git_hash(b) == blob:
            counts['exactRaw'] += 1
        elif b'\0' not in b and git_hash(b.replace(b'\r\n', b'\n')) == blob:
            counts['canonicalText'] += 1
        else:
            raise RuntimeError('RETAINED_EVIDENCE_CHANGED')
        counts['files'] += 1
        counts['bytes'] += len(b)
    if counts['files'] < 100:
        raise RuntimeError('RETAINED_EVIDENCE_INCOMPLETE')
    return counts

class NativeGuard:
    class Stream(C.Structure):
        _fields_ = [('size', C.c_longlong), ('name', W.WCHAR * 296)]
    class FileInfo(C.Structure):
        _fields_ = [('attributes', W.DWORD), ('creation', W.FILETIME), ('access', W.FILETIME),
                    ('write', W.FILETIME), ('volume', W.DWORD), ('sizeHigh', W.DWORD),
                    ('sizeLow', W.DWORD), ('links', W.DWORD), ('indexHigh', W.DWORD), ('indexLow', W.DWORD)]
    def __init__(self):
        if os.name != 'nt':
            raise RuntimeError('WINDOWS_ONLY')
        self.k = C.WinDLL('kernel32', use_last_error=True)
        self.k.CreateFileW.argtypes = [W.LPCWSTR, W.DWORD, W.DWORD, C.c_void_p, W.DWORD, W.DWORD, W.HANDLE]
        self.k.CreateFileW.restype = W.HANDLE
        self.k.CloseHandle.argtypes = [W.HANDLE]
        self.k.FindFirstStreamW.argtypes = [W.LPCWSTR, W.DWORD, C.c_void_p, W.DWORD]
        self.k.FindFirstStreamW.restype = W.HANDLE
        self.k.FindNextStreamW.argtypes = [W.HANDLE, C.c_void_p]
        self.k.FindClose.argtypes = [W.HANDLE]
        self.k.GetFileInformationByHandle.argtypes = [W.HANDLE, C.c_void_p]
        self.k.SetFileInformationByHandle.argtypes = [W.HANDLE, C.c_int, C.c_void_p, W.DWORD]
        self.invalid = C.c_void_p(-1).value

    def streams(self, p):
        data = self.Stream()
        h = self.k.FindFirstStreamW(str(p), 0, C.byref(data), 0)
        if h == self.invalid:
            if C.get_last_error() == 38:  # no streams (directory)
                return
            raise RuntimeError('STREAM_OBSERVATION_UNAVAILABLE')
        try:
            while True:
                if data.name != '::$DATA':
                    raise RuntimeError('ALTERNATE_STREAM_RETAIN')
                if not self.k.FindNextStreamW(h, C.byref(data)):
                    if C.get_last_error() != 38:
                        raise RuntimeError('STREAM_ENUMERATION_INCOMPLETE')
                    break
        finally:
            self.k.FindClose(h)

    def open(self, p, row, stack):
        directory = row['kind'] == 'DIR'
        # Directory handles deny rename/delete; file handles deny ALL sharing.
        h = self.k.CreateFileW(str(p), 0x80 | (0 if directory else 0x10000),
                               3 if directory else 0, None, 3,
                               0x00200000 | (0x02000000 if directory else 0), None)
        if h == self.invalid:
            raise RuntimeError('SKIP_ACTIVE_OR_PERMISSION_UNAVAILABLE')
        stack.callback(self.k.CloseHandle, h)
        info = self.FileInfo()
        if not self.k.GetFileInformationByHandle(h, C.byref(info)):
            raise RuntimeError('HANDLE_IDENTITY_UNAVAILABLE')
        s = p.lstat()
        if (list(a.identity(s)) != row['identity'] or info.attributes & 0x400 or
                ((info.indexHigh << 32) | info.indexLow) != s.st_ino or
                info.volume != s.st_dev or (not directory and info.links != 1)):
            raise RuntimeError('SKIP_CHANGED_HANDLE_IDENTITY')
        self.streams(p)
        return h

    def delete_file(self, handle):
        # FILE_DISPOSITION_INFO.DeleteFile (BOOLEAN), operating on the pinned file.
        flag = C.c_ubyte(1)
        if not self.k.SetFileInformationByHandle(handle, 4, C.byref(flag), C.sizeof(flag)):
            raise RuntimeError('DELETE_REFUSED_RETAIN_REMAINDER')

def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ('plan', 'apply', 'plan-readonly-skip', 'apply-readonly-skip'):
        raise SystemExit('PLAN_OR_APPLY_ONLY')
    local = Path((a.REPO / 'app/desktopApp/build/p11-lr6-private-space/current-task-pointer.txt').read_text().strip())
    if not a.contained(a.REPO, local):
        raise RuntimeError('LOCAL_MANIFEST_UNSAFE')
    if active_processes():
        raise RuntimeError('SKIP_ACTIVE')
    evidence = evidence_binding()
    skip_readonly = sys.argv[1].endswith('-readonly-skip')
    suffix = '-readonly-skip' if skip_readonly else ''
    plan_path = local / f'authorized-avd-plan{suffix}.json'
    if sys.argv[1].startswith('plan'):
        plan = dict(targets={p: manifest(a.REPO, p) for p in TARGETS}, retainedEvidence=evidence)
        if skip_readonly:
            original = json.loads((local / 'authorized-avd-plan.json').read_text())
            for target, rows in plan['targets'].items():
                original_files = {r['path']: r for r in original['targets'][target] if r['kind'] == 'FILE'}
                for row in rows:
                    if row['kind'] == 'FILE' and row != original_files.get(row['path']):
                        raise RuntimeError('ADDITIONAL_OR_CHANGED_FILE_AFTER_PARTIAL_CLEANUP')
        with plan_path.open('x', encoding='utf-8') as f:
            json.dump(plan, f, indent=2)
        public = dict(authorization='USER_DIRECT_SUPPLEMENT_AFTER_199_TWO_EXACT_AVDS_ONLY',
                      processNameMatches=[], retainedEvidence=evidence, candidates=[])
        native = NativeGuard()
        for target, rows in plan['targets'].items():
            with ExitStack() as stack:
                for row in sorted(rows, key=lambda r: (r['kind'] != 'DIR', r['path'])):
                    native.open(a.REPO / row['path'], row, stack)
            public['candidates'].append(dict(candidate=target, files=sum(r['kind'] == 'FILE' for r in rows),
                logicalBytes=sum(r['identity'][3] for r in rows if r['kind'] == 'FILE'),
                decision='SAFE_REGENERABLE_WITH_EXPLICIT_STATE_DISCARD_CONSENT',
                readOnlyFilesRetained=sum(r['kind'] == 'FILE' and bool(r['identity'][6] & 1) for r in rows) if skip_readonly else 0,
                nativeIdentityAndNoReparse=True, exclusiveFileHandles=True, alternateStreamsAbsent=True))
        (a.RAW / f'AVD_CLEANUP_DRY_RUN{suffix}.json').write_text(json.dumps(public, indent=2) + '\n')
        print(json.dumps(public))
        return
    plan = json.loads(plan_path.read_text())
    if set(plan['targets']) != set(TARGETS) or evidence != plan['retainedEvidence']:
        raise RuntimeError('PLAN_OR_EVIDENCE_CHANGED')
    free_before = shutil.disk_usage(a.REPO).free
    receipt = dict(authorization='USER_DIRECT_SUPPLEMENT', candidates=[], deletedLogicalBytes=0, deletedFiles=0,
                   retainedEvidence=evidence, volumeFreeBefore=free_before)
    native = NativeGuard()
    for target in TARGETS:
        rows = plan['targets'][target]
        if active_processes() or manifest(a.REPO, target) != rows:
            raise RuntimeError('SKIP_ACTIVE_OR_CHANGED')
        files = []
        with ExitStack() as stack:
            for row in sorted(rows, key=lambda r: (r['kind'] != 'DIR', r['path'])):
                h = native.open(a.REPO / row['path'], row, stack)
                if row['kind'] == 'FILE' and not (skip_readonly and row['identity'][6] & 1):
                    files.append((h, row))
            for h, row in files:
                native.delete_file(h)
                receipt['deletedFiles'] += 1
                receipt['deletedLogicalBytes'] += row['identity'][3]
        # All pinned file handles closed; remove only now-empty directories.
        for row in sorted((r for r in rows if r['kind'] == 'DIR'), key=lambda r: len(Path(r['path']).parts), reverse=True):
            p = a.REPO / row['path']
            s = p.lstat()
            if a.reparse(s) or list(a.identity(s))[:2] != row['identity'][:2]:
                raise RuntimeError('DIRECTORY_CHANGED_RETAIN')
            if not any(p.iterdir()):
                p.rmdir()
        remains = (a.REPO / target).exists()
        receipt['candidates'].append(dict(candidate=target, decision='PARTIAL_RETAIN_READONLY' if remains else 'DELETE_SUCCESS', remains=remains))
        (a.RAW / f'AVD_CLEANUP_DECISION_LEDGER{suffix}.json').write_text(json.dumps(receipt, indent=2) + '\n')
    receipt['volumeFreeAfter'] = shutil.disk_usage(a.REPO).free
    receipt['volumeFreeDelta'] = receipt['volumeFreeAfter'] - free_before
    receipt['physicalAllocationBytes'] = 'NOT_MEASURED_FREE_DELTA_IS_NOT_LOGICAL_FILE_SIZE'
    (a.RAW / f'AVD_CLEANUP_DECISION_LEDGER{suffix}.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt))

if __name__ == '__main__':
    try:
        main()
    except Exception as e:
        # Fixed category only; no path/content/OS error string printed.
        print(json.dumps(dict(status='BLOCKED_OR_PARTIAL_RETAIN_REMAINDER', category=str(e) if isinstance(e, RuntimeError) else type(e).__name__)))
        raise SystemExit(2)
