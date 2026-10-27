"""Exercise interpreter admission only. Never invokes an installer or native action."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

PROBE = Path(__file__).resolve().with_name('native_package_probe.py')
REFUSAL = b'NATIVE_PACKAGE_PROBE_REQUIRES_ENABLED_VALIDATION'


class NativeProbeValidationTest(unittest.TestCase):
    def run_only(self, flags, tail):
        with tempfile.TemporaryDirectory(prefix='ccb-probe-admission-synthetic-') as temporary:
            marker = Path(temporary) / 'SYNTHETIC_KEEP.txt'
            marker.write_bytes(b'NO_NATIVE_ACTION_AUTHORIZED')
            result = subprocess.run([sys.executable, '-I', '-B', *flags, *tail], cwd=temporary,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
            self.assertEqual([marker.name], sorted(path.name for path in Path(temporary).iterdir()))
            self.assertEqual(b'NO_NATIVE_ACTION_AUTHORIZED', marker.read_bytes())
            return result

    @unittest.skipUnless(os.name == 'nt', 'The existing native probe imports Windows-only msvcrt.')
    def test_normal_help_remains_available_without_native_action(self):
        result = self.run_only([], [str(PROBE), '--help'])
        self.assertEqual(0, result.returncode)
        self.assertIn(b'repair-registration', result.stdout)

    def test_optimized_cli_refuses_before_parsing_or_native_action(self):
        for flag in ['-O', '-OO']:
            with self.subTest(flag=flag):
                result = self.run_only([flag], [str(PROBE), '--help'])
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(REFUSAL, result.stderr.strip())
                self.assertEqual(b'', result.stdout)

    def test_optimized_import_cannot_expose_assertion_free_actions(self):
        result = self.run_only(['-O'], ['-c',
            "import runpy,sys; runpy.run_path(sys.argv[1],run_name='probe_import_only')", str(PROBE)])
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(REFUSAL, result.stderr.strip())
        self.assertEqual(b'', result.stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
