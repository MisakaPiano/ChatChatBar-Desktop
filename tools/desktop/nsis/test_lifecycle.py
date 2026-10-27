"""Native Windows lifecycle tests with a tiny fake app; not Desktop GUI acceptance."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

BASE = Path(__file__).parent
CSC = Path(os.environ.get('WINDIR', 'C:/Windows')) / 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
FAKE = r'''
using System; using System.IO; using System.Threading; using System.Diagnostics;
class Fake {
 static void Main() {
  var p=Environment.GetEnvironmentVariable("CCB_DESKTOP_READY_PATH");
  var root=AppDomain.CurrentDomain.BaseDirectory;
  var v=File.ReadAllLines(Path.Combine(root,"identity.txt"));
  if (v[0]=="fail") return;
  File.WriteAllLines(p+".pending",new[]{Environment.GetEnvironmentVariable("CCB_DESKTOP_READY_NONCE"),v[0],v[1],Process.GetCurrentProcess().Id.ToString()});
  File.Move(p+".pending",p);
  Thread.Sleep(2000);
 }
}'''


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


@unittest.skipUnless(os.name == 'nt', 'Native Windows test')
class Lifecycle(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='ccb-nsis-tests-')
        cls.base = Path(cls.temp.name)
        cls.launcher = cls.base / 'manager.exe'
        cls.stable = cls.base / 'stable.exe'
        cls.fake = cls.base / 'ChatChatBarDesktop.exe'
        (cls.base / 'Fake.cs').write_text(FAKE)
        for source, target, refs in [(BASE / 'Launcher.cs', cls.launcher,
                ['/reference:System.Windows.Forms.dll', '/reference:System.Web.Extensions.dll', '/define:SYNTHETIC']),
                (cls.base / 'Fake.cs', cls.fake, [])]:
            subprocess.run([str(CSC), '/nologo', '/target:winexe', '/platform:x64', '/out:' + str(target), *refs, str(source.absolute())],
                           check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        subprocess.run([str(CSC), '/nologo', '/target:winexe', '/platform:x64', '/out:' + str(cls.stable),
                        '/reference:System.Windows.Forms.dll', '/reference:System.Web.Extensions.dll',
                        '/define:SYNTHETIC', '/define:STABLE', str((BASE / 'Launcher.cs').absolute())],
                       check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        self.case = tempfile.TemporaryDirectory(dir=self.base)
        self.local = Path(self.case.name) / 'local-appdata'
        self.local.mkdir()
        self.root = Path(self.case.name) / 'install'
        self.run_manager('--prepare')
        (self.root / 'ChatChatBar.exe').write_bytes(self.stable.read_bytes())
        (self.root / 'Uninstall.exe').write_bytes(b'fake generated uninstaller')
        self.run_manager('--register')

    def tearDown(self):
        # The fake exits itself. No real app or data root is opened by this suite.
        import time
        time.sleep(2.1)
        self.case.cleanup()

    def run_manager(self, mode, *args, expected=0):
        environment = dict(os.environ, LOCALAPPDATA=str(self.local), CCB_DESKTOP_TEST_SHELL_ROOT=str(Path(self.case.name) / 'shell'))
        result = subprocess.run([str(self.launcher), mode, str(self.root), *args, '--quiet'], timeout=20, env=environment)
        key = hashlib.sha256(str(self.root.absolute()).upper().encode('utf-8')).hexdigest().upper()
        log = self.local / 'ChatChatBarDesktopNSISSyntheticInstallState' / (key + '.log')
        self.assertEqual(expected, result.returncode, mode + '\n' + (log.read_text(encoding='utf-8') if log.exists() else 'no lifecycle log'))

    def version(self, number, fail=False):
        version = '0.1.0-preview.' + str(number)
        commit = ('a' if number == 1 else 'b') * 40
        identity = version + '-' + commit[:12]
        directory = self.root / 'versions' / identity
        payload = Path(self.case.name) / ('payload-' + str(number))
        payload.mkdir()
        (payload / 'ChatChatBarDesktop.exe').write_bytes(self.fake.read_bytes())
        (payload / 'install-manager.exe').write_bytes(self.launcher.read_bytes())
        (payload / 'identity.txt').write_text(('fail' if fail else version) + '\n' + commit)
        entries = [dict(path=p.name, bytes=p.stat().st_size, sha256=digest(p)) for p in payload.iterdir()]
        manifest = payload / 'install-manifest.json'
        manifest.write_text(json.dumps(dict(product='ccb-desktop-nsis-synthetic-v1', version=version, commit=commit, files=entries)))
        self.run_manager('--stage', identity, str(manifest))
        for entry in entries:
            (directory / entry['path']).write_bytes((payload / entry['path']).read_bytes())
        return identity, directory

    def test_first_start_and_upgrade_retire_only_owned_files(self):
        first, old = self.version(1)
        self.run_manager('--activate', first)
        (old / 'unknown.txt').write_text('keep')
        second, new = self.version(2)
        self.run_manager('--activate', second)
        self.run_manager('--cleanup')
        self.assertEqual(second, (self.root / 'current.txt').read_text())
        self.assertEqual('keep', (old / 'unknown.txt').read_text())
        self.assertTrue(new.is_dir())
        self.run_manager('--cleanup')

    def test_failed_new_app_keeps_old_entry(self):
        first, old = self.version(1)
        self.run_manager('--activate', first)
        second, _ = self.version(2, fail=True)
        self.run_manager('--activate', second, expected=2)
        self.assertEqual(first, (self.root / 'current.txt').read_text())
        self.assertTrue((old / 'ChatChatBarDesktop.exe').is_file())

    def test_modified_payload_refused_before_switch(self):
        first, _ = self.version(1)
        self.run_manager('--activate', first)
        second, new = self.version(2)
        (new / 'identity.txt').write_text('changed')
        self.run_manager('--activate', second, expected=2)
        self.assertEqual(first, (self.root / 'current.txt').read_text())

    def test_foreign_root_and_path_escape_refused(self):
        (self.root / 'install-owner.txt').write_text('foreign')
        self.run_manager('--prepare', expected=2)
        self.run_manager('--activate', '../outside', expected=2)

    def test_manifest_tamper_prevents_retirement(self):
        first, old = self.version(1)
        self.run_manager('--activate', first)
        second, _ = self.version(2)
        (old / 'install-manifest.json').write_text('{}')
        self.run_manager('--activate', second, expected=2)
        self.assertTrue((old / 'ChatChatBarDesktop.exe').exists())

    def test_busy_manifest_blocks_uninstall_before_deletion(self):
        import ctypes
        first, directory = self.version(1)
        self.run_manager('--activate', first)
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.CreateFileW.restype = ctypes.c_void_p
        kernel.CreateFileW.argtypes = [ctypes.c_wchar_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p]
        kernel.CloseHandle.argtypes = [ctypes.c_void_p]
        handle = kernel.CreateFileW(str(directory / 'install-manifest.json'), 0x80000000, 1, None, 3, 0, None)
        self.assertNotEqual(ctypes.c_void_p(-1).value, handle)
        try:
            self.run_manager('--uninstall', expected=2)
            self.run_manager('--prepare', expected=2)
            self.assertTrue((directory / 'identity.txt').is_file())
        finally:
            kernel.CloseHandle(handle)

    def test_uninstall_keeps_external_data_and_unknown_files(self):
        import time
        first, directory = self.version(1)
        self.run_manager('--activate', first)
        time.sleep(2.1)
        data = Path(self.case.name) / 'user-data.txt'
        data.write_text('valuable synthetic data')
        unknown = self.root / 'user-notes.txt'
        unknown.write_text('keep')
        self.run_manager('--uninstall')
        self.assertFalse(directory.exists())
        self.assertEqual('valuable synthetic data', data.read_text())
        self.assertEqual('keep', unknown.read_text())
        self.assertFalse((self.root / 'ChatChatBar.exe').exists())

    def test_interrupted_stage_can_be_retried_without_touching_current(self):
        first, old = self.version(1)
        self.run_manager('--activate', first)
        second, new = self.version(2)
        (new / 'identity.txt').unlink()  # interrupted extraction, before activation
        manifest = Path(self.case.name) / 'payload-2' / 'install-manifest.json'
        self.run_manager('--stage', second, str(manifest))
        self.assertEqual(['install-manifest.json'], [p.name for p in new.iterdir()])
        self.assertEqual(first, (self.root / 'current.txt').read_text())
        self.assertTrue((old / 'identity.txt').exists())

    def test_unowned_stage_is_not_blessed_by_retry(self):
        first, directory = self.version(1)
        (self.root / 'receipts' / (first + '.sha256')).unlink()
        manifest = Path(self.case.name) / 'payload-1' / 'install-manifest.json'
        self.run_manager('--stage', first, str(manifest), expected=2)
        self.run_manager('--activate', first, expected=2)
        self.assertTrue((directory / 'identity.txt').exists())

    def test_junction_root_refused_without_writing_target(self):
        target = Path(self.case.name) / 'external'
        target.mkdir()
        link = Path(self.case.name) / 'junction'
        made = subprocess.run(['cmd.exe', '/d', '/c', 'mklink', '/J', str(link), str(target)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.assertEqual(0, made.returncode)
        original = self.root
        try:
            self.root = link
            self.run_manager('--prepare', expected=2)
            self.assertEqual([], list(target.iterdir()))
        finally:
            self.root = original
            link.rmdir()

    def test_stable_entry_delegates_to_owned_current_manager(self):
        first, _ = self.version(1)
        self.run_manager('--activate', first)
        env = dict(os.environ, LOCALAPPDATA=str(self.local), CCB_DESKTOP_TEST_SHELL_ROOT=str(Path(self.case.name) / 'shell'))
        result = subprocess.run([str(self.root / 'ChatChatBar.exe')], env=env, timeout=20)
        self.assertEqual(0, result.returncode)

    def test_required_disk_space_refuses_without_creating_root(self):
        import shutil
        original = self.root
        self.root = Path(self.case.name) / 'no-space'
        try:
            self.run_manager('--prepare', str(shutil.disk_usage(self.case.name).free + 1048576), expected=2)
            self.assertFalse(self.root.exists())
        finally:
            self.root = original

    def test_changed_shortcut_is_preserved_during_upgrade(self):
        first, _ = self.version(1)
        self.run_manager('--activate', first)
        link = Path(self.case.name) / 'shell/Desktop/ChatChatBar Desktop NSIS Synthetic.lnk'
        link.write_bytes(b'unknown user modification')
        second, _ = self.version(2)
        self.run_manager('--activate', second)
        self.run_manager('--cleanup')
        self.assertEqual(b'unknown user modification', link.read_bytes())
        self.assertEqual('DEFERRED', (self.root / 'cleanup-status.txt').read_text())

    def test_identical_reinstall_verifies_without_overlay(self):
        first, directory = self.version(1)
        self.run_manager('--activate', first)
        before = digest(directory / 'identity.txt')
        manifest = Path(self.case.name) / 'payload-1/install-manifest.json'
        self.run_manager('--stage', first, str(manifest), expected=10)
        self.run_manager('--activate', first)
        self.assertEqual(before, digest(directory / 'identity.txt'))

    def test_stable_entry_refuses_during_nsis_extraction(self):
        import ctypes
        first, _ = self.version(1)
        self.run_manager('--activate', first)
        kernel = ctypes.WinDLL('kernel32')
        kernel.CreateMutexW.restype = ctypes.c_void_p
        kernel.CreateMutexW.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_wchar_p]
        kernel.CloseHandle.argtypes = [ctypes.c_void_p]
        handle = kernel.CreateMutexW(None, 0, 'Local\\ChatChatBarDesktopNSISSynthetic-Extraction')
        self.assertTrue(handle)
        try:
            env = dict(os.environ, LOCALAPPDATA=str(self.local), CCB_DESKTOP_TEST_SHELL_ROOT=str(Path(self.case.name) / 'shell'))
            result = subprocess.run([str(self.root / 'ChatChatBar.exe'), '--quiet'], timeout=20, env=env)
            self.assertEqual(2, result.returncode)
            self.assertEqual(first, (self.root / 'current.txt').read_text())
        finally:
            kernel.CloseHandle(handle)

    def test_program_cannot_be_nested_in_default_credentials_or_selected_data(self):
        original = self.root
        custom = Path(self.case.name) / 'selected-data'
        for protected in [self.local / 'ChatChatBarDesktop', self.local / 'ChatChatBarDesktopCredentials', custom]:
            protected.mkdir(exist_ok=True)
            witness = protected / 'keep.txt'; witness.write_text('synthetic valuable data')
            (self.local / 'ChatChatBarDesktop.bootstrap.json').write_text(json.dumps(dict(formatVersion=1, mode='CUSTOM', customRoot=str(custom))))
            try:
                self.root = protected / 'new-program'
                self.run_manager('--prepare', expected=2)
                self.assertFalse(self.root.exists())
                self.assertEqual('synthetic valuable data', witness.read_text())
            finally:
                self.root = original

    def test_invalid_custom_root_metadata_does_not_create_program_root(self):
        original = self.root
        (self.local / 'ChatChatBarDesktop.bootstrap.json').write_text('{"customRoot":123}')
        try:
            self.root = Path(self.case.name) / 'not-created'
            self.run_manager('--prepare', expected=2)
            self.assertFalse(self.root.exists())
        finally:
            self.root = original

    def test_install_log_stays_outside_program_directory(self):
        first, _ = self.version(1)
        self.run_manager('--activate', first)
        self.assertFalse((self.root / 'lifecycle.log').exists())
        logs = list((self.local / 'ChatChatBarDesktopNSISSyntheticInstallState').glob('*.log'))
        self.assertEqual(1, len(logs))
        self.assertIn('READY ' + first, logs[0].read_text(encoding='utf-8'))

    def test_new_program_directory_has_private_write_permissions(self):
        command = r'''
$ErrorActionPreference='Stop'
$acl=[IO.Directory]::GetAccessControl($env:CCB_TEST_PROGRAM_ROOT)
$me=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$trusted=@($me,'S-1-5-18','S-1-5-32-544')
$rules=$acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])
$other=@($rules | Where-Object {$_.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and $_.IdentityReference.Value -notin $trusted -and ($_.FileSystemRights -band ([Security.AccessControl.FileSystemRights]::WriteData -bor [Security.AccessControl.FileSystemRights]::Delete -bor [Security.AccessControl.FileSystemRights]::ChangePermissions)) -ne 0})
$self=@($rules | Where-Object {$_.IdentityReference.Value -eq $me -and $_.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -eq [Security.AccessControl.FileSystemRights]::FullControl})
@{protected=$acl.AreAccessRulesProtected;otherWriteGrants=$other.Count;currentUserFullControl=($self.Count -gt 0)} | ConvertTo-Json -Compress
'''
        env = dict(os.environ, CCB_TEST_PROGRAM_ROOT=str(self.root))
        env['PSModulePath'] = str(Path(os.environ.get('WINDIR', 'C:/Windows')) / 'System32/WindowsPowerShell/v1.0/Modules')
        result = subprocess.run(['powershell.exe', '-NoProfile', '-Command', command], env=env,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr.decode('utf-8', errors='replace'))
        permissions = json.loads(result.stdout.decode('utf-8-sig'))
        self.assertTrue(permissions['protected'])
        self.assertTrue(permissions['currentUserFullControl'])
        self.assertEqual(0, permissions['otherWriteGrants'])


if __name__ == '__main__':
    unittest.main()
