"""Native Windows lifecycle tests with a tiny fake app; not Desktop GUI acceptance."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import sys
import socket
import struct
from concurrent.futures import ThreadPoolExecutor
from installer_diagnostics import DIAGNOSTICS, decorate_manager_calls, diagnostic_function

BASE = Path(__file__).parent
sys.path.insert(0, str(BASE.parent / 'update'))
from version_identity import ReleaseIdentity
CSC = Path(os.environ.get('WINDIR', 'C:/Windows')) / 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
FAKE = r'''
using System; using System.IO; using System.Threading; using System.Diagnostics;
class Fake {
 static void Main(string[] args) {
  var capture=Environment.GetEnvironmentVariable("CCB_TEST_OPEN_CAPTURE");
  if (capture!=null && args.Length>0) {
   var rawLines=new System.Collections.Generic.List<string>();
   foreach(var arg in args) rawLines.Add(Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(arg)));
   File.WriteAllLines(capture+".argv",rawLines.ToArray());
   var receipt=Environment.GetEnvironmentVariable("CCB_DESKTOP_ACTIVATION_PATH");
   if(args[0]=="--open-request") {
    if(args.Length!=3 || args[1]!=Environment.GetEnvironmentVariable("CCB_DESKTOP_ACTIVATION_NONCE")) throw new Exception("REQUEST_BINDING");
    var bytes=File.ReadAllBytes(Path.ChangeExtension(receipt,"open-request"));
    using(var sha=System.Security.Cryptography.SHA256.Create()) {
     if(BitConverter.ToString(sha.ComputeHash(bytes)).Replace("-","").ToLowerInvariant()!=args[2]) throw new Exception("REQUEST_HASH");
    }
    using(var input=new BinaryReader(new MemoryStream(bytes))) {
     if(System.Net.IPAddress.NetworkToHostOrder(input.ReadInt32())!=0x43434232) throw new Exception("REQUEST_VERSION");
     var count=System.Net.IPAddress.NetworkToHostOrder(input.ReadInt32());
     if(count<1 || count>64) throw new Exception("REQUEST_COUNT");
     var decoded=new System.Collections.Generic.List<string>();decoded.Add("--open-files");
     for(var i=0;i<count;i++) {
      var size=System.Net.IPAddress.NetworkToHostOrder(input.ReadInt32());
      if(size<1 || size>131072) throw new Exception("REQUEST_LENGTH");
      decoded.Add(new System.Text.UTF8Encoding(false,true).GetString(input.ReadBytes(size)));
     }
     if(input.BaseStream.Position!=input.BaseStream.Length) throw new Exception("REQUEST_TRAILER");
     args=decoded.ToArray();
    }
   }
   var lines=new System.Collections.Generic.List<string>();
   foreach(var arg in args) lines.Add(Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(arg)));
   File.WriteAllLines(capture,lines.ToArray());
   File.WriteAllText(receipt+".pending",Environment.GetEnvironmentVariable("CCB_DESKTOP_ACTIVATION_NONCE")+"\nQUEUED\n");
   File.Move(receipt+".pending",receipt); return;
  }
  var forwarded=Environment.GetEnvironmentVariable("CCB_DESKTOP_FORWARD_PATH");
  if (forwarded!=null && Environment.GetEnvironmentVariable("CCB_TEST_FORWARD")=="1") {
   File.WriteAllText(forwarded+".pending",Environment.GetEnvironmentVariable("CCB_DESKTOP_FORWARD_NONCE")+"\nFORWARDED\n");
   File.Move(forwarded+".pending",forwarded); return;
  }
  var p=Environment.GetEnvironmentVariable("CCB_DESKTOP_READY_PATH");
  var root=AppDomain.CurrentDomain.BaseDirectory;
  var v=File.ReadAllLines(Path.Combine(root,"identity.txt"));
  if (v[0]=="fail") return;
  File.WriteAllLines(p+".pending",new[]{Environment.GetEnvironmentVariable("CCB_DESKTOP_READY_NONCE"),v[0],v[1],Process.GetCurrentProcess().Id.ToString()});
  File.Move(p+".pending",p);
  Thread.Sleep(2000);
 }
}'''


class DiagnosticProtocol(unittest.TestCase):
    def test_setup_binds_each_return_code_to_its_own_dispatch_without_reading_retained_logs(self):
        lines = [r'''ExecWait '"$PLUGINSDIR\manager.exe" --prepare "$INSTDIR" 805306368 --quiet' $0''',
                 'IntCmp $0 0 +2', 'Goto failed',
                 r'''ExecWait '"$PLUGINSDIR\manager.exe" --activate "$INSTDIR" "$TargetId" --quiet' $0''']
        generated = decorate_manager_calls(lines)
        calls = [i for i, line in enumerate(generated) if line.startswith('ExecWait ')]
        self.assertEqual(2, len(calls))
        for index in calls:
            self.assertIn('--diagnostic-exit', generated[index])
            self.assertEqual('StrCpy $DiagnosticExit $0', generated[index + 1])
            self.assertEqual('StrCpy $0 -1', generated[index - 2])
        self.assertIn('IntCmp $0 0 +2', generated)
        self.assertIn('StrCpy $DiagnosticPhase "--activate"', generated)
        dialog = '\n'.join(diagnostic_function('ChatChatBarDesktopNSIS'))
        self.assertNotIn('FileRead', dialog)
        self.assertIn('ReadEnvStr $DiagnosticLog "LOCALAPPDATA"', dialog)
        for code, (identity, _) in DIAGNOSTICS.items():
            self.assertIn(f'StrCmp $DiagnosticExit {code} diagnostic_{code}', dialog)
            self.assertIn(f'StrCpy $DiagnosticCode "{identity}"', dialog)


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
        cls.open_bridge = cls.base / 'OpenChatBarFile.exe'
        cls.legacy_stable = cls.base / 'legacy-stable.exe'
        cls.legacy_manager = cls.base / 'legacy-manager.exe'
        cls.public1233_open_bridge = cls.base / 'public1233-open.exe'
        cls.fake = cls.base / 'ChatChatBarDesktop.exe'
        (cls.base / 'Fake.cs').write_text(FAKE)
        for source, target, refs in [(BASE / 'Launcher.cs', cls.launcher,
                ['/reference:System.Windows.Forms.dll', '/reference:System.Web.Extensions.dll', '/define:SYNTHETIC', '/define:LIFECYCLE_TEST']),
                (cls.base / 'Fake.cs', cls.fake, [])]:
            subprocess.run([str(CSC), '/nologo', '/target:winexe', '/platform:x64', '/out:' + str(target), *refs, str(source.absolute())],
                           check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        subprocess.run([str(CSC), '/nologo', '/target:winexe', '/platform:x64', '/out:' + str(cls.stable),
                        '/reference:System.Windows.Forms.dll', '/reference:System.Web.Extensions.dll',
                        '/define:SYNTHETIC', '/define:LIFECYCLE_TEST', '/define:STABLE', str((BASE / 'Launcher.cs').absolute())],
                       check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        subprocess.run([str(CSC), '/nologo', '/target:winexe', '/platform:x64', '/out:' + str(cls.open_bridge),
                        '/reference:System.Windows.Forms.dll', '/reference:System.Web.Extensions.dll',
                        '/define:SYNTHETIC', '/define:LIFECYCLE_TEST', '/define:STABLE;OPEN_WITH', str((BASE / 'Launcher.cs').absolute())],
                       check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        legacy = BASE / 'fixtures' / 'Launcher-1229.cs'
        origin = json.loads(legacy.with_suffix('.origin.json').read_text())
        if digest(legacy) != origin['sha256']:
            raise AssertionError('Frozen public stable source identity changed')
        subprocess.run([str(CSC), '/nologo', '/target:winexe', '/platform:x64', '/out:' + str(cls.legacy_stable),
                        '/reference:System.Windows.Forms.dll', '/reference:System.Web.Extensions.dll',
                        '/define:SYNTHETIC', '/define:LIFECYCLE_TEST', '/define:STABLE', str(legacy.absolute())],
                       check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        subprocess.run([str(CSC), '/nologo', '/target:winexe', '/platform:x64', '/out:' + str(cls.legacy_manager),
                        '/reference:System.Windows.Forms.dll', '/reference:System.Web.Extensions.dll',
                        '/define:SYNTHETIC', '/define:LIFECYCLE_TEST', str(legacy.absolute())],
                       check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        public1233 = BASE / 'fixtures' / 'Launcher-1233.cs'
        if digest(public1233) != json.loads(public1233.with_suffix('.origin.json').read_text())['sha256']:
            raise AssertionError('Frozen public1233 source identity changed')
        subprocess.run([str(CSC), '/nologo', '/target:winexe', '/platform:x64', '/out:' + str(cls.public1233_open_bridge),
                        '/reference:System.Windows.Forms.dll', '/reference:System.Web.Extensions.dll',
                        '/define:SYNTHETIC', '/define:LIFECYCLE_TEST', '/define:STABLE;OPEN_WITH', str(public1233.absolute())],
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

    def run_manager(self, mode, *args, expected=0, manager=None):
        environment = dict(os.environ, LOCALAPPDATA=str(self.local), CCB_DESKTOP_TEST_SHELL_ROOT=str(Path(self.case.name) / 'shell'))
        result = subprocess.run([str(manager or self.launcher), mode, str(self.root), *args, '--quiet'], timeout=20, env=environment)
        key = hashlib.sha256(str(self.root.absolute()).upper().encode('utf-8')).hexdigest().upper()
        log = self.local / 'ChatChatBarDesktopNSISLifecycleTestInstallState' / (key + '.log')
        self.assertEqual(expected, result.returncode, mode + '\n' + (log.read_text(encoding='utf-8') if log.exists() else 'no lifecycle log'))
        return log.read_text(encoding='utf-8') if log.exists() else ''

    def test_diagnostic_overlap_is_current_invocation_and_legacy_status_is_unchanged(self):
        data = self.root / 'input' / 'synthetic-data'
        data.mkdir(parents=True)
        witness = data / 'retain.txt'; witness.write_text('synthetic data stays')
        bootstrap = self.local / 'ChatChatBarDesktop.bootstrap.json'
        bootstrap.write_text(json.dumps(dict(customRoot=str(data))))
        self.run_manager('--prepare', '805306368', '--diagnostic-exit', expected=20)
        self.run_manager('--prepare', '805306368', expected=2)
        external = self.local / 'separate-data'; external.mkdir()
        bootstrap.write_text(json.dumps(dict(customRoot=str(external))))
        # A retained FAILED overlap line must not label the next, successful invocation as failed.
        self.run_manager('--prepare', '805306368', '--diagnostic-exit')
        self.assertEqual('synthetic data stays', witness.read_text())

    def version(self, number, fail=False, display=None, manager=None):
        version = display or '0.1.0-preview.' + str(number)
        release = ReleaseIdentity.create(version, number)
        commit = ('a' if number == 1 else 'b') * 40
        identity = release.storage_version + '-' + commit[:12]
        directory = self.root / 'versions' / identity
        payload = Path(self.case.name) / ('payload-' + str(number))
        payload.mkdir()
        (payload / 'ChatChatBarDesktop.exe').write_bytes(self.fake.read_bytes())
        (payload / 'install-manager.exe').write_bytes((manager or self.launcher).read_bytes())
        (payload / 'identity.txt').write_text(('fail' if fail else version) + '\n' + commit)
        entries = [dict(path=p.name, bytes=p.stat().st_size, sha256=digest(p)) for p in payload.iterdir()]
        manifest = payload / 'install-manifest.json'
        value = dict(product='ccb-desktop-nsis-synthetic-v1', version=version, commit=commit, files=entries)
        if display is not None:
            value.update(buildNumber=release.build, channel=release.channel, storageVersion=release.storage_version)
        manifest.write_text(json.dumps(value))
        self.run_manager('--stage', identity, str(manifest), manager=manager)
        for entry in entries:
            (directory / entry['path']).write_bytes((payload / entry['path']).read_bytes())
        return identity, directory

    def test_frozen_preview_root_and_manager_bridge_to_real_stable_then_failed_and_successful_next_stable(self):
        import time
        import base64
        (self.root / 'ChatChatBar.exe').write_bytes(self.legacy_stable.read_bytes())
        (self.root / 'OpenChatBarFile.exe').write_bytes(self.public1233_open_bridge.read_bytes())
        self.run_manager('--register')
        roots = {name: (self.root / name).read_bytes() for name in ('ChatChatBar.exe', 'OpenChatBarFile.exe', 'Uninstall.exe')}
        data = Path(self.case.name) / 'user-data'; data.mkdir()
        witness = data / 'draft-and-credentials-canary'; witness.write_bytes(b'SYNTHETIC retained source')
        bootstrap = self.local / 'ChatChatBarDesktop.bootstrap.json'; bootstrap.write_text(json.dumps(dict(customRoot=str(data))))
        original_bootstrap = bootstrap.read_bytes()
        old, _ = self.version(1229, manager=self.legacy_manager)
        self.run_manager('--activate', old, manager=self.legacy_manager)
        self.assertFalse((self.root / 'receipts' / (old + '.identity.json')).exists())
        time.sleep(2.1)
        bridge, _ = self.version(1234, display='0.1.0-preview.1234')
        self.run_manager('--activate', bridge); time.sleep(2.1)
        stable, stable_home = self.version(1235, display='1.0.0')
        self.run_manager('--activate', stable); time.sleep(2.1)
        self.assertEqual('1.0.0', json.loads((stable_home / 'install-manifest.json').read_text())['version'])
        env = dict(os.environ, LOCALAPPDATA=str(self.local), CCB_DESKTOP_TEST_SHELL_ROOT=str(Path(self.case.name) / 'shell'))
        self.assertEqual(0, subprocess.run([str(self.root / 'ChatChatBar.exe')], env=env, timeout=20).returncode)
        time.sleep(2.1)
        paths = [Path(self.case.name) / '合成 & stable.json', Path(self.case.name) / 'synthetic save.cbsave']
        for path in paths: path.write_bytes(b'SYNTHETIC retained input')
        capture = Path(self.case.name) / 'stable-cold-delivery'
        delivered = subprocess.run([str(self.root / 'OpenChatBarFile.exe'), *map(str, paths)],
                                   env=dict(env, CCB_TEST_OPEN_CAPTURE=str(capture)), timeout=20)
        self.assertEqual(0, delivered.returncode)
        self.assertEqual(['--open-files', *map(str, paths)],
                         [base64.b64decode(value).decode('utf-8') for value in capture.read_text(encoding='utf-8-sig').splitlines()])
        self.assertEqual([], list((self.root / 'receipts').glob('*.open-request')))
        self.assertTrue(all(path.read_bytes() == b'SYNTHETIC retained input' for path in paths))
        time.sleep(2.1)
        failed, _ = self.version(1236, fail=True, display='1.0.1')
        self.run_manager('--activate', failed, expected=2)
        self.assertEqual(stable, (self.root / 'current.txt').read_text())
        later, _ = self.version(1237, display='1.0.1')
        self.run_manager('--activate', later); time.sleep(2.1)
        self.assertEqual(0, subprocess.run([str(self.root / 'ChatChatBar.exe')], env=env, timeout=20).returncode)
        self.assertEqual(later, (self.root / 'current.txt').read_text())
        self.assertEqual(roots, {name: (self.root / name).read_bytes() for name in roots})
        self.assertEqual(original_bootstrap, bootstrap.read_bytes())
        self.assertEqual(b'SYNTHETIC retained source', witness.read_bytes())

    def test_stable_same_package_repair_uses_sealed_display_identity_and_keeps_damaged_original(self):
        import time
        first, old = self.version(1235, display='1.0.0')
        self.run_manager('--activate', first); time.sleep(2.1)
        (old / 'install-manifest.json').write_text('{damaged stable manifest')
        payload = Path(self.case.name) / 'payload-1235'
        self.run_manager('--prepare-repair')
        self.run_manager('--stage-repair', first, str(payload / 'install-manifest.json'))
        repaired = (self.root / 'pending-repair.txt').read_text(); new = self.root / 'versions' / repaired
        for path in payload.iterdir():
            if path.name != 'install-manifest.json': (new / path.name).write_bytes(path.read_bytes())
        self.run_manager('--activate', repaired); time.sleep(2.1)
        self.assertEqual(repaired, (self.root / 'current.txt').read_text())
        self.assertEqual('{damaged stable manifest', (old / 'install-manifest.json').read_text())
        descriptor = self.root / 'receipts' / (repaired + '.identity.json')
        original_seal = (self.root / 'receipts' / (repaired + '.identity.sha256')).read_bytes()
        descriptor.write_text(descriptor.read_text().replace('1.0.0', '0.9.9'))
        self.run_manager('--prepare-repair')
        self.run_manager('--stage-repair', first, str(payload / 'install-manifest.json'), expected=2)
        self.assertEqual(repaired, (self.root / 'current.txt').read_text())
        self.assertEqual(original_seal, (self.root / 'receipts' / (repaired + '.identity.sha256')).read_bytes())

    def test_stable_manifest_storage_build_channel_and_semantic_downgrade_are_refused_without_pointer_changes(self):
        import time
        first, _ = self.version(1235, display='1.0.0')
        self.run_manager('--activate', first); time.sleep(2.1)
        candidate, _ = self.version(1236, display='1.0.1')
        source = Path(self.case.name) / 'payload-1236' / 'install-manifest.json'
        valid = json.loads(source.read_text()); original = (self.root / 'current.txt').read_bytes()
        for change in (dict(buildNumber=True), dict(storageVersion='1.0.1-preview.9999'), dict(channel='preview'), dict(version='1.0.1\n')):
            source.write_text(json.dumps(dict(valid, **change)))
            self.run_manager('--stage', candidate, str(source), expected=2)
            self.assertEqual(original, (self.root / 'current.txt').read_bytes())
        source.write_text(json.dumps(valid))
        downgrade, _ = self.version(1237, display='0.9.9')
        self.run_manager('--activate', downgrade, expected=2)
        self.assertEqual(original, (self.root / 'current.txt').read_bytes())

    def test_same_trusted_stage_finishes_its_interrupted_identity_seal_but_refuses_foreign_half_records(self):
        import time
        first, _ = self.version(1)
        self.run_manager('--activate', first); time.sleep(2.1)
        candidate, home = self.version(1235, display='1.0.0')
        source = Path(self.case.name) / 'payload-1235' / 'install-manifest.json'
        record = self.root / 'receipts' / (candidate + '.identity.json')
        seal = self.root / 'receipts' / (candidate + '.identity.sha256')
        original_record, original_seal = record.read_bytes(), seal.read_bytes()
        current = (self.root / 'current.txt').read_bytes()
        canary = Path(self.case.name) / 'retained-data'; canary.write_bytes(b'unchanged synthetic data')
        seal.unlink()
        self.run_manager('--stage', candidate, str(source), expected=10)
        self.assertEqual(original_record, record.read_bytes()); self.assertEqual(original_seal, seal.read_bytes())
        record.unlink()
        self.run_manager('--stage', candidate, str(source), expected=2)
        self.assertFalse(record.exists()); self.assertEqual(original_seal, seal.read_bytes())
        record.write_bytes(original_record); seal.unlink()
        record.write_text(record.read_text().replace('1.0.0', '0.9.9'))
        altered = record.read_bytes()
        self.run_manager('--stage', candidate, str(source), expected=2)
        self.assertEqual(altered, record.read_bytes()); self.assertFalse(seal.exists())
        self.assertEqual(current, (self.root / 'current.txt').read_bytes())
        self.assertEqual(b'unchanged synthetic data', canary.read_bytes())
        self.assertTrue((home / 'ChatChatBarDesktop.exe').is_file())

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
        self.run_manager('--activate', second, '--diagnostic-exit', expected=27)
        self.assertEqual(first, (self.root / 'current.txt').read_text())
        self.assertTrue((old / 'ChatChatBarDesktop.exe').is_file())

    def test_fresh_installer_repairs_corrupt_same_version_without_running_damaged_program(self):
        import time
        first, old = self.version(1)
        self.run_manager('--activate', first)
        time.sleep(2.1)
        (old / 'ChatChatBarDesktop.exe').write_bytes(b'damaged executable never launched')
        (old / 'install-manifest.json').write_text('{broken')
        witness = Path(self.case.name) / 'valuable-data.txt'
        witness.write_text('unchanged synthetic data')
        self.run_manager('--prepare-repair')
        payload = Path(self.case.name) / 'payload-1'
        self.run_manager('--stage-repair', first, str(payload / 'install-manifest.json'))
        repaired = (self.root / 'pending-repair.txt').read_text()
        self.assertTrue(repaired.startswith(first + '-r'))
        new = self.root / 'versions' / repaired
        for p in payload.iterdir():
            if p.name != 'install-manifest.json': (new / p.name).write_bytes(p.read_bytes())
        self.assertEqual(first, (self.root / 'current.txt').read_text())
        self.run_manager('--activate', repaired)
        self.assertEqual(repaired, (self.root / 'current.txt').read_text())
        self.assertEqual(b'damaged executable never launched', (old / 'ChatChatBarDesktop.exe').read_bytes())
        self.assertEqual('{broken', (old / 'install-manifest.json').read_text())
        self.assertEqual('unchanged synthetic data', witness.read_text())
        self.assertEqual(1, len(list((self.root / 'repair-retained').iterdir())))
        original_files = {p.name: p.read_bytes() for p in old.iterdir() if p.is_file()}
        self.run_manager('--cleanup')
        self.assertEqual(original_files, {p.name: p.read_bytes() for p in old.iterdir() if p.is_file()})
        self.run_manager('--launch')
        time.sleep(2.1)
        self.run_manager('--uninstall')
        self.assertFalse((self.root / 'ChatChatBar.exe').exists())
        self.assertEqual(original_files, {p.name: p.read_bytes() for p in old.iterdir() if p.is_file()})
        self.assertEqual('unchanged synthetic data', witness.read_text())

    def test_same_version_repair_requires_exact_original_package_receipt(self):
        first, _ = self.version(1)
        self.run_manager('--activate', first)
        payload = Path(self.case.name) / 'payload-1'
        manifest = payload / 'install-manifest.json'
        changed = json.loads(manifest.read_text())
        changed['files'][0]['sha256'] = 'f' * 64
        manifest.write_text(json.dumps(changed))
        self.run_manager('--stage-repair', first, str(manifest), expected=2)
        self.assertEqual(first, (self.root / 'current.txt').read_text())
        self.assertFalse((self.root / 'pending-repair.txt').exists())

    def test_complete_authenticated_stage_is_reused_without_overlay(self):
        first, _ = self.version(1)
        self.run_manager('--activate', first)
        second, staged = self.version(2)
        witness = staged / 'identity.txt'
        stamp = witness.stat().st_mtime_ns
        original = witness.read_bytes()
        manifest = Path(self.case.name) / 'payload-2/install-manifest.json'
        self.run_manager('--stage', second, str(manifest), expected=10)
        self.assertEqual(stamp, witness.stat().st_mtime_ns)
        self.assertEqual(original, witness.read_bytes())
        self.assertEqual(first, (self.root / 'current.txt').read_text())
        self.run_manager('--activate', second)

    def test_unknown_version_directory_keeps_cleanup_deferred_without_aborting(self):
        first, _ = self.version(1)
        self.run_manager('--activate', first)
        unknown = self.root / 'versions/not-a-program-version'
        unknown.mkdir(); (unknown / 'user-note.txt').write_text('retain')
        self.run_manager('--cleanup')
        self.assertEqual('retain', (unknown / 'user-note.txt').read_text())
        self.assertEqual('DEFERRED', (self.root / 'cleanup-status.txt').read_text())

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
            self.run_manager('--prepare', '805306368', '--diagnostic-exit', expected=31)
            self.run_manager('--prepare-repair', expected=2)
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

    def test_normal_warm_entry_accepts_only_separate_forward_receipt_without_changing_current(self):
        first, _ = self.version(1)
        self.run_manager('--activate', first)
        before = (self.root / 'current.txt').read_bytes()
        env = dict(os.environ, LOCALAPPDATA=str(self.local), CCB_TEST_FORWARD='1',
                   CCB_DESKTOP_TEST_SHELL_ROOT=str(Path(self.case.name) / 'shell'))
        result = subprocess.run([str(self.root / 'ChatChatBar.exe')], env=env, timeout=20)
        self.assertEqual(0, result.returncode)
        self.assertEqual(before, (self.root / 'current.txt').read_bytes())
        self.assertEqual([], list((self.root / 'receipts').glob('*.forwarded')))

    def test_native_warm_delivery_uses_authenticated_socket_without_executing_image_and_never_replays_lost_ack(self):
        import time
        first, version = self.version(1)
        self.run_manager('--activate', first)
        time.sleep(2.1)
        # A warm delivery executes no image; the same damage must still refuse cold launch.
        (version / 'identity.txt').write_text('damaged synthetic image')
        before = (self.root / 'current.txt').read_bytes()
        key = hashlib.sha256(str(self.root.absolute()).lower().encode('utf-8')).hexdigest()
        endpoint = self.local / 'ChatChatBarDesktopActivation' / key / 'endpoint'
        endpoint.parent.mkdir(parents=True)
        token = 'ab' * 32
        paths = [Path(self.case.name) / '角色 & file.json', Path(self.case.name) / 'second file.cbsave']
        for path in paths:
            path.write_bytes(b'synthetic input retained')
        def receive(server, acknowledge):
            with server.accept()[0] as client:
                client.settimeout(5)
                def exact(size):
                    result = b''
                    while len(result) < size:
                        chunk = client.recv(size - len(result))
                        if not chunk:
                            raise AssertionError('Incomplete native activation frame')
                        result += chunk
                    return result
                def integer():
                    return struct.unpack('!i', exact(4))[0]
                def text():
                    length = integer()
                    self.assertLessEqual(length, 131072)
                    self.assertGreaterEqual(length, 0)
                    return exact(length).decode('utf-8')
                self.assertEqual(0x43434231, integer())
                self.assertEqual(token, text())
                self.assertRegex(text(), r'^[a-f0-9-]{36}$')
                received = [text() for _ in range(integer())]
                if acknowledge:
                    client.sendall(struct.pack('!i', 0))
            server.settimeout(.3)
            with self.assertRaises(socket.timeout):
                server.accept()
            return received
        for mode, arguments, acknowledge in [('--launch', [], True), ('--open', paths, True), ('--open', paths, False)]:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server, ThreadPoolExecutor(max_workers=1) as pool:
                server.bind(('127.0.0.1', 0))
                server.listen()
                server.settimeout(10)
                endpoint.write_text(str(server.getsockname()[1]) + '\n' + token + '\n')
                pending = pool.submit(receive, server, acknowledge)
                log_key = hashlib.sha256(str(self.root.absolute()).upper().encode('utf-8')).hexdigest().upper()
                log = self.local / 'ChatChatBarDesktopNSISLifecycleTestInstallState' / (log_key + '.log')
                previous_log = log.read_text(encoding='utf-8') if log.exists() else ''
                current_log = self.run_manager(mode, *map(str, arguments), expected=0 if acknowledge else 2)
                if not acknowledge:
                    self.assertTrue(current_log.startswith(previous_log))
                    self.assertIn('FAILED OPEN_DELIVERY_UNCONFIRMED_DO_NOT_REPLAY', current_log[len(previous_log):])
                self.assertEqual(list(map(str, arguments)), pending.result(timeout=10))
        endpoint.unlink()
        self.run_manager('--launch', expected=2)
        self.assertEqual(before, (self.root / 'current.txt').read_bytes())
        for path in paths:
            self.assertEqual(b'synthetic input retained', path.read_bytes())

    def test_unchanged_public1229_stable_entry_launches_new_manager_with_three_resources_cold_and_warm(self):
        import time
        (self.root / 'ChatChatBar.exe').write_bytes(self.legacy_stable.read_bytes())
        (self.root / 'OpenChatBarFile.exe').write_bytes(self.open_bridge.read_bytes())
        self.run_manager('--register')
        first, _ = self.version(1)
        self.run_manager('--activate', first)
        env = dict(os.environ, LOCALAPPDATA=str(self.local), CCB_DESKTOP_TEST_SHELL_ROOT=str(Path(self.case.name) / 'shell'))
        for forwarded in (False, True):
            chosen = dict(env, **({'CCB_TEST_FORWARD': '1'} if forwarded else {}))
            result = subprocess.run([str(self.root / 'ChatChatBar.exe')], env=chosen, timeout=20)
            self.assertEqual(0, result.returncode)
        time.sleep(2.1)
        self.run_manager('--prepare')
        self.assertEqual(self.legacy_stable.read_bytes(), (self.root / 'ChatChatBar.exe').read_bytes())

    def test_open_bridge_forwards_unicode_and_spaces_and_uninstall_owns_three_resources_without_defaults(self):
        import base64
        import time
        import winreg
        def default_json():
            try:
                with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r'Software\Classes\.json') as key:
                    return winreg.QueryValueEx(key, '')
            except FileNotFoundError:
                return None
        original_default = default_json()
        first, _ = self.version(1)
        (self.root / 'OpenChatBarFile.exe').write_bytes(self.open_bridge.read_bytes())
        self.run_manager('--register')
        resources = json.loads((self.root / 'generated-resources.json').read_text())
        self.assertEqual({'ChatChatBar.exe', 'OpenChatBarFile.exe', 'Uninstall.exe'}, {e['path'] for e in resources})
        self.run_manager('--activate', first)
        paths = [Path(self.case.name) / '角色 & one.json', Path(self.case.name) / '第二份 file.cbsave']
        for path in paths:
            path.write_bytes(b'synthetic unchanged input')
        capture = Path(self.case.name) / 'captured-arguments.txt'
        env = dict(os.environ, LOCALAPPDATA=str(self.local), CCB_TEST_OPEN_CAPTURE=str(capture),
                   CCB_DESKTOP_TEST_SHELL_ROOT=str(Path(self.case.name) / 'shell'))
        result = subprocess.run([str(self.root / 'OpenChatBarFile.exe'), *map(str, paths)], env=env, timeout=20)
        self.assertEqual(0, result.returncode)
        self.assertEqual(['--open-files', *map(str, paths)],
                         [base64.b64decode(value).decode('utf-8') for value in capture.read_text(encoding='utf-8-sig').splitlines()])
        raw_args = [base64.b64decode(value).decode('utf-8') for value in Path(str(capture) + '.argv').read_text(encoding='utf-8-sig').splitlines()]
        self.assertEqual('--open-request', raw_args[0])
        self.assertEqual(3, len(raw_args)); self.assertTrue(all(value.isascii() for value in raw_args))
        self.assertEqual([], list((self.root / 'receipts').glob('*.open-request')))
        self.assertEqual(original_default, default_json())
        suffix = hashlib.sha256(str(self.root.absolute()).upper().encode('utf-8')).hexdigest().upper()[:12]
        key_path = r'Software\Classes\ChatChatBarDesktopNSISLifecycleTest.File.' + suffix
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path + r'\shell\open\command') as key:
            self.assertEqual('"' + str(self.root / 'OpenChatBarFile.exe') + '" "%1"', winreg.QueryValueEx(key, '')[0])
        time.sleep(2.1)
        self.run_manager('--uninstall')
        with self.assertRaises(FileNotFoundError):
            winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path)
        self.assertEqual(original_default, default_json())
        for path in paths:
            self.assertEqual(b'synthetic unchanged input', path.read_bytes())

    def test_partial_owned_root_cannot_adopt_an_unowned_open_bridge(self):
        for name in ('ChatChatBar.exe', 'Uninstall.exe', 'generated-resources.json'):
            (self.root / name).unlink()
        foreign = self.root / 'OpenChatBarFile.exe'
        foreign.write_bytes(b'synthetic foreign program retained')
        before = digest(foreign)
        self.run_manager('--prepare', expected=2)
        self.assertEqual(before, digest(foreign))
        self.assertFalse((self.root / 'generated-resources.json').exists())

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

    def test_app_shortcut_commands_restore_each_entry_without_launch_or_pointer_change(self):
        first, _ = self.version(1)
        self.run_manager('--activate', first)
        pointer = (self.root / 'current.txt').read_bytes()
        for destination, folder in [('desktop', 'Desktop'), ('programs', 'Programs')]:
            link = Path(self.case.name) / 'shell' / folder / 'ChatChatBar Desktop NSIS Synthetic.lnk'
            other = Path(self.case.name) / 'shell' / ('Programs' if folder == 'Desktop' else 'Desktop') / link.name
            other_before = other.read_bytes()
            link.unlink()
            self.run_manager('--shortcuts', destination)
            self.assertTrue(link.is_file())
            self.assertEqual('MisakaPiano.ChatChatBar.Desktop.Synthetic', self.shell_property(link))
            self.assertEqual(other_before, other.read_bytes())
            self.assertEqual(pointer, (self.root / 'current.txt').read_bytes())
            self.assertEqual(digest(link), (self.root / (destination + '-shortcut.sha256')).read_text())
            link.write_bytes(b'user changed shortcut')
            self.run_manager('--shortcuts', destination, expected=3)
            self.assertEqual(b'user changed shortcut', link.read_bytes())

    def test_app_shortcut_command_refuses_changed_program(self):
        first, directory = self.version(1)
        self.run_manager('--activate', first)
        link = Path(self.case.name) / 'shell/Desktop/ChatChatBar Desktop NSIS Synthetic.lnk'
        link.unlink()
        (directory / 'identity.txt').write_text('changed')
        self.run_manager('--shortcuts', 'desktop', expected=2)
        self.assertFalse(link.exists())

    def shell_property(self, link, create_legacy=False):
        script = '''
if ($env:CCB_TEST_CREATE_LEGACY -eq '1') {
  $w = New-Object -ComObject WScript.Shell
  $l = $w.CreateShortcut($env:CCB_TEST_LINK)
  $l.TargetPath = $env:CCB_TEST_TARGET
  $l.WorkingDirectory = [System.IO.Path]::GetDirectoryName($env:CCB_TEST_TARGET)
  $l.Save()
}
$s = New-Object -ComObject Shell.Application
$f = $s.Namespace([System.IO.Path]::GetDirectoryName($env:CCB_TEST_LINK))
$i = $f.ParseName([System.IO.Path]::GetFileName($env:CCB_TEST_LINK))
$i.ExtendedProperty('System.AppUserModel.ID')
'''
        env = dict(os.environ, CCB_TEST_LINK=str(link), CCB_TEST_TARGET=str(self.root / 'ChatChatBar.exe'),
                   CCB_TEST_CREATE_LEGACY='1' if create_legacy else '0')
        result = subprocess.run(['powershell.exe', '-NoLogo', '-NoProfile', '-NonInteractive', '-Command', script],
                                env=env, capture_output=True, text=True, check=True, timeout=30,
                                creationflags=subprocess.CREATE_NO_WINDOW)
        return result.stdout.strip()

    def test_taskbar_identity_is_stable_and_legacy_owned_shortcut_is_upgraded(self):
        first, _ = self.version(1)
        self.run_manager('--activate', first)
        link = Path(self.case.name) / 'shell/Programs/ChatChatBar Desktop NSIS Synthetic.lnk'
        app_id = 'MisakaPiano.ChatChatBar.Desktop.Synthetic'
        self.assertEqual(app_id, self.shell_property(link))
        # Reconstruct the old release's own unbranded link, with its original ownership receipt.
        link.unlink()
        self.assertEqual('', self.shell_property(link, create_legacy=True))
        (self.root / 'programs-shortcut.sha256').write_text(digest(link))
        second, _ = self.version(2)
        self.run_manager('--activate', second)
        self.assertEqual(app_id, self.shell_property(link))
        self.assertEqual(digest(link), (self.root / 'programs-shortcut.sha256').read_text())
        key = hashlib.sha256(str(self.root.absolute()).upper().encode('utf-8')).hexdigest().upper()
        log = self.local / 'ChatChatBarDesktopNSISLifecycleTestInstallState' / (key + '.log')
        self.assertIn('SHORTCUT_APPID_UPDATED programs-shortcut.sha256', log.read_text())

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
        handle = kernel.CreateMutexW(None, 0, 'Local\\ChatChatBarDesktopNSISLifecycleTest-Extraction')
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
        logs = list((self.local / 'ChatChatBarDesktopNSISLifecycleTestInstallState').glob('*.log'))
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
