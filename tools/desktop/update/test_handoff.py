"""Native wait/hash/identity boundary with tiny invented executables, no NSIS install."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest

CSC = Path(os.environ.get('WINDIR', 'C:/Windows')) / 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
SOURCE = Path(__file__).resolve().parent / 'UpdateHandoff.cs'
FIXTURE = r'''
using System; using System.Diagnostics; using System.IO; using System.Threading;
class Fixture {
 static void Main(string[] args) {
  var marker=Environment.GetEnvironmentVariable("CCB_UPDATE_TEST_MARKER");
  if (args.Length>0 && args[0]=="old") {
   var ms=(long)(Process.GetCurrentProcess().StartTime.ToUniversalTime()-new DateTime(1970,1,1,0,0,0,DateTimeKind.Utc)).TotalMilliseconds;
   File.WriteAllText(marker+".start",ms.ToString()); Thread.Sleep(1700); return;
  }
  File.WriteAllText(marker,"SYNTHETIC INSTALLER CALLED");
 }
}'''


@unittest.skipUnless(os.name == 'nt', 'Native Windows handoff')
class HandoffTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory(prefix='ccb-update-handoff-')
        cls.base = Path(cls.workspace.name)
        fixture = cls.base / 'Fixture.cs'; fixture.write_text(FIXTURE)
        cls.handoff, cls.fixture = cls.base / 'handoff.exe', cls.base / 'fixture.exe'
        for source, target, extra in ((SOURCE, cls.handoff, ['/reference:System.Windows.Forms.dll', '/define:HANDOFF_TEST']),
                                      (fixture, cls.fixture, [])):
            subprocess.run([str(CSC), '/nologo', '/target:winexe', '/platform:x64', '/out:' + str(target), *extra, str(source)],
                           check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    @classmethod
    def tearDownClass(cls): cls.workspace.cleanup()

    def run_case(self, defect=None, lock_check=False):
        with tempfile.TemporaryDirectory(dir=self.base) as tmp:
            case = Path(tmp); root = case / 'program'; root.mkdir()
            (root / 'versions').mkdir(); (root / 'install-owner.txt').write_text('ccb-desktop-nsis-v1')
            (root / 'current.txt').write_text('original')
            marker = case / 'executed.txt'; package = case / 'installer.exe'; package.write_bytes(self.fixture.read_bytes())
            digest = hashlib.sha256(package.read_bytes()).hexdigest(); size = package.stat().st_size
            environment = dict(os.environ, CCB_UPDATE_TEST_MARKER=str(marker))
            old = subprocess.Popen([str(self.fixture), 'old'], env=environment)
            try:
                deadline = time.monotonic() + 10
                while not Path(str(marker) + '.start').exists():
                    self.assertLess(time.monotonic(), deadline); time.sleep(.02)
                started = Path(str(marker) + '.start').read_text()
                if defect == 'hash': package.write_bytes(b'CHANGED')
                if defect == 'root': (root / 'install-owner.txt').write_text('FOREIGN')
                expected = 'different' if defect == 'pointer' else 'original'
                helper = subprocess.Popen([str(self.handoff), str(root), str(package), digest, str(size), str(old.pid), started, expected], env=environment)
                time.sleep(.25)
                self.assertFalse(marker.exists(), 'Installer must wait for the old process normal exit')
                self.assertIsNone(old.poll(), 'Handoff must never kill the old process')
                if lock_check:
                    with self.assertRaises(PermissionError): package.write_bytes(b'CHANGED WHILE WAITING')
                self.assertEqual(2 if defect else 0, helper.wait(timeout=15))
                self.assertEqual(defect is None, marker.exists())
                self.assertEqual('original', (root / 'current.txt').read_text())
            finally:
                self.assertEqual(0, old.wait(timeout=10))

    def test_waits_for_normal_exit_and_locks_exact_download(self): self.run_case(lock_check=True)
    def test_changed_package_never_runs(self): self.run_case('hash')
    def test_foreign_installation_never_runs(self): self.run_case('root')
    def test_changed_current_pointer_never_runs(self): self.run_case('pointer')


if __name__ == '__main__': unittest.main()
