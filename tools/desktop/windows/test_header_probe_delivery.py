"""Frozen bundle integrity and real PS5.1 refusal/parser tests, never MSI execution."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import unittest
import zipfile


class FrozenHeaderProbeDelivery(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.bundle = Path(os.environ['CCB_HEADER_PROBE_BUNDLE'])
        cls.evidence = Path(os.environ['CCB_HEADER_DELIVERY_TEST_EVIDENCE'])
        cls.evidence.mkdir()
        cls.ps = r'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'

    def command(self, label, arguments, expected):
        command = [self.ps, '-NoProfile', '-ExecutionPolicy', 'Bypass', *arguments]
        (self.evidence / (label + '-command.json')).write_text(json.dumps(command) + '\n', encoding='utf-8')
        env = {k: v for k, v in os.environ.items() if k.lower() != 'psmodulepath'}
        env['PSModulePath'] = r'C:\Windows\System32\WindowsPowerShell\v1.0\Modules'
        with (self.evidence / (label + '-stdout.log')).open('xb') as out, (self.evidence / (label + '-stderr.log')).open('xb') as err:
            result = subprocess.run(command, env=env, stdout=out, stderr=err, timeout=90)
        (self.evidence / (label + '-exit.txt')).write_text(str(result.returncode), encoding='ascii')
        self.assertEqual(expected, result.returncode)
        return ((self.evidence / (label + '-stdout.log')).read_bytes() + (self.evidence / (label + '-stderr.log')).read_bytes())

    def test_hash_only_verifier_in_real_windows_powershell_51(self):
        output = self.command('verify-no-msi', ['-File', str(self.bundle / 'Verify-HeaderProbe-Bundle.ps1')], 0)
        self.assertIn(b'FROZEN_HEADER_PROBE_DELIVERY_VERIFIED_NO_INSTALL_OR_VM_CHANGE', output)

    def test_real_entry_without_confirmation_refuses_before_loading_installer_helper(self):
        output = self.command('entry-no-confirm-no-msi', ['-File', str(self.bundle / 'Run-HeaderProbe-Once.ps1')], 1)
        self.assertIn(b'EXPLICIT_NEW_GUEST_CONFIRMATION_REQUIRED', output)

    def test_bad_manifest_refuses_before_helper_loading(self):
        target = self.evidence / 'tamper-fixture';target.mkdir()
        (target / 'Run-HeaderProbe-Once.ps1').write_bytes((self.bundle / 'Run-HeaderProbe-Once.ps1').read_bytes())
        (target / 'DELIVERY_IDENTITY.json').write_bytes(b'{"synthetic":"changed"}')
        output = self.command('entry-bad-manifest-no-msi', ['-File', str(target / 'Run-HeaderProbe-Once.ps1'), '-ConfirmNewIndependentGuest'], 1)
        self.assertIn(b'FIXED_MANIFEST_MISMATCH', output)

    def test_all_distributed_scripts_parse_in_ps51(self):
        script = self.evidence / 'parse-only.ps1'
        script.write_text("$ErrorActionPreference='Stop'\nif($PSVersionTable.PSVersion.Major -ne 5){throw 'PS51_REQUIRED'}\n" +
            "$files=Get-ChildItem -LiteralPath '" + str(self.bundle).replace("'", "''") + "' -Filter '*.ps1' -File\n" +
            "if($files.Count -ne 3){throw 'EXACT_THREE_SCRIPTS_REQUIRED'}\nforeach($file in $files){$tokens=$null;$errors=$null;" +
            "$null=[Management.Automation.Language.Parser]::ParseFile($file.FullName,[ref]$tokens,[ref]$errors);" +
            "if($errors.Count){throw 'PARSE_FAILURE'}}\nWrite-Output 'THREE_SCRIPTS_PS51_PARSE_ONLY_PASS'\n", encoding='ascii')
        self.assertIn(b'THREE_SCRIPTS_PS51_PARSE_ONLY_PASS', self.command('parser-no-execution', ['-File', str(script)], 0))

    def test_zip_is_exact_frozen_directory_and_inputs_still_match_manifest(self):
        receipt = json.loads(self.bundle.with_name(self.bundle.name + '-FREEZE.json').read_text(encoding='utf-8'))
        archive = self.bundle.with_suffix('.zip')
        self.assertEqual(receipt['archive']['sha256'], hashlib.sha256(archive.read_bytes()).hexdigest())
        with zipfile.ZipFile(archive) as z:
            self.assertEqual(sorted(f.name for f in self.bundle.iterdir()), sorted(z.namelist()))
            for row in receipt['files']:
                data = (self.bundle / row['name']).read_bytes()
                self.assertEqual(data, z.read(row['name']))
                self.assertEqual(len(data), row['bytes'])
                self.assertEqual(hashlib.sha256(data).hexdigest(), row['sha256'])


if __name__ == '__main__':
    unittest.main()
