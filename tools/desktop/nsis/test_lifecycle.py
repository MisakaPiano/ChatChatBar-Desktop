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
        cls.fake = cls.base / 'ChatChatBarDesktop.exe'
        (cls.base / 'Fake.cs').write_text(FAKE)
        for source, target, refs in [(BASE / 'Launcher.cs', cls.launcher,
                ['/reference:System.Windows.Forms.dll', '/reference:System.Web.Extensions.dll', '/define:SYNTHETIC']),
                (cls.base / 'Fake.cs', cls.fake, [])]:
            subprocess.run([str(CSC), '/nologo', '/target:winexe', '/platform:x64', '/out:' + str(target), *refs, str(source.absolute())],
                           check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        self.case = tempfile.TemporaryDirectory(dir=self.base)
        self.root = Path(self.case.name) / 'install'
        self.run_manager('--prepare')
        (self.root / 'ChatChatBar.exe').write_bytes(self.launcher.read_bytes())
        (self.root / 'Uninstall.exe').write_bytes(b'fake generated uninstaller')
        self.run_manager('--register')

    def tearDown(self):
        # The fake exits itself. No real app or data root is opened by this suite.
        import time
        time.sleep(2.1)
        self.case.cleanup()

    def run_manager(self, mode, *args, expected=0):
        environment = dict(os.environ, CCB_DESKTOP_TEST_SHELL_ROOT=str(Path(self.case.name) / 'shell'))
        result = subprocess.run([str(self.launcher), mode, str(self.root), *args, '--quiet'], timeout=20, env=environment)
        log = self.root / 'lifecycle.log'
        self.assertEqual(expected, result.returncode, mode + '\n' + (log.read_text(encoding='utf-8') if log.exists() else 'no lifecycle log'))

    def version(self, number, fail=False):
        version = '0.1.0-preview.' + str(number)
        commit = ('a' if number == 1 else 'b') * 40
        identity = version + '-' + commit[:12]
        directory = self.root / 'versions' / identity
        payload = Path(self.case.name) / ('payload-' + str(number))
        payload.mkdir()
        (payload / 'ChatChatBarDesktop.exe').write_bytes(self.fake.read_bytes())
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


if __name__ == '__main__':
    unittest.main()
