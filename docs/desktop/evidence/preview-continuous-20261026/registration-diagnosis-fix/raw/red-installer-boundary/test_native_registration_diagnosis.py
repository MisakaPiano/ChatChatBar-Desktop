"""Synthetic observations only: all installer/native boundaries are forbidden or mocked."""
import contextlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

PROBE = Path(__file__).with_name('native_package_probe.py')
spec = importlib.util.spec_from_file_location('registration_probe_synthetic', PROBE)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
PRODUCT_CODE = '{11111111-2222-3333-4444-555555555555}'


class NativeRegistrationDiagnosisTest(unittest.TestCase):
    @contextlib.contextmanager
    def attempt(self, observation, related_products=None, result_exit=0, after_related=None):
        with tempfile.TemporaryDirectory(prefix='ccb-registration-synthetic-') as temporary:
            base = Path(temporary)
            info = {'ProductName': probe.PRODUCT, 'ProductCode': PRODUCT_CODE,
                    'ProductVersion': '0.1.1002', 'UpgradeCode': '{' + probe.UPGRADE + '}',
                    'safetyVersion': '1', 'sha256': '0' * 64, 'bytes': 16}
            (base / 'INSTALLED.json').write_text(json.dumps(info), encoding='utf-8')
            (base / 'program').mkdir()
            (base / 'program/KEEP.txt').write_bytes(b'SYNTHETIC_PROGRAM_RETAIN')
            installer = base / 'synthetic-input.msi'
            installer.write_bytes(b'NOT_A_REAL_MSI')
            native_action = mock.Mock(side_effect=AssertionError('INSTALLER_EXECUTION_FORBIDDEN'))
            with contextlib.ExitStack() as stack:
                stack.enter_context(mock.patch.object(probe, 'msi_info', return_value=info))
                stack.enter_context(mock.patch.object(probe, 'safe', side_effect=lambda path: Path(path)))
                stack.enter_context(mock.patch.object(probe, 'identity', return_value={'installerVersion': '0.1.1002'}))
                stack.enter_context(mock.patch.object(probe, 'related', return_value=[] if related_products is None else related_products))
                stack.enter_context(mock.patch.object(probe, 'registration_states', return_value=observation, create=True))
                stack.enter_context(mock.patch.object(probe.subprocess, 'run', native_action))
                stack.enter_context(mock.patch.object(probe.C, 'WinDLL', side_effect=AssertionError('REAL_NATIVE_API_FORBIDDEN')))
                yield base, installer, info, native_action
                self.assertEqual(b'SYNTHETIC_PROGRAM_RETAIN', (base / 'program/KEEP.txt').read_bytes())
                self.assertEqual(b'NOT_A_REAL_MSI', installer.read_bytes())

    def test_advertised_residue_refuses_before_installer(self):
        with self.attempt({'status': 'OBSERVED', 'productState': -1, 'defaultFeatureState': 1}) as (base, installer, _, action):
            with self.assertRaisesRegex(RuntimeError, '^NATIVE_REPAIR_PARTIAL_REGISTRATION$'):
                probe.repair_registration(base, installer, 'synthetic-partial')
            action.assert_not_called()

    def test_unavailable_observation_is_not_absence_or_permission_to_reinstall(self):
        with self.attempt({'status': 'UNAVAILABLE', 'productState': None, 'defaultFeatureState': None}) as (base, installer, _, action):
            with self.assertRaisesRegex(RuntimeError, '^NATIVE_REPAIR_REGISTRATION_UNAVAILABLE$'):
                probe.repair_registration(base, installer, 'synthetic-unavailable')
            action.assert_not_called()


if __name__ == '__main__':
    unittest.main(verbosity=2)
