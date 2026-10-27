"""Synthetic observations only: all installer/native boundaries are forbidden or mocked."""
import contextlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import types
import unittest
from unittest import mock

PROBE = Path(__file__).with_name('native_package_probe.py')
probe = None
if os.name == 'nt':
    spec = importlib.util.spec_from_file_location('registration_probe_synthetic', PROBE)
    probe = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(probe)
PRODUCT_CODE = '{11111111-2222-3333-4444-555555555555}'


@unittest.skipUnless(os.name == 'nt', 'The existing probe imports Windows-only msvcrt; native calls are still mocked.')
class NativeRegistrationDiagnosisTest(unittest.TestCase):
    @contextlib.contextmanager
    def attempt(self, observation, related_products=None):
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

    def test_registered_product_without_upgrade_association_is_partial(self):
        with self.attempt({'status': 'OBSERVED', 'productState': 5, 'defaultFeatureState': 1}) as (base, installer, _, action):
            with self.assertRaisesRegex(RuntimeError, '^NATIVE_REPAIR_PARTIAL_REGISTRATION$'):
                probe.repair_registration(base, installer, 'synthetic-registered')
            report = json.loads((base / 'synthetic-registered-registration-diagnosis.json').read_text())
            self.assertEqual(5, report['productState'])
            self.assertFalse(report['expectedAssociationPresent'])
            self.assertFalse(report['installerExecuted'])
            self.assertFalse(report['owner1307Resolved'])
            action.assert_not_called()

    def test_local_feature_without_product_association_is_not_clean_absence(self):
        result = probe.registration_diagnosis(PRODUCT_CODE, [],
            {'status': 'OBSERVED', 'productState': -1, 'defaultFeatureState': 3})
        self.assertEqual('NATIVE_REPAIR_PARTIAL_REGISTRATION', result['code'])
        self.assertFalse(result['repairAuthorized'])

    def test_consistent_observation_does_not_authorize_repair_or_prove_os_health(self):
        result = probe.registration_diagnosis(PRODUCT_CODE, [PRODUCT_CODE],
            {'status': 'OBSERVED', 'productState': 5, 'defaultFeatureState': 3})
        self.assertEqual('NATIVE_REPAIR_CONSISTENT_REGISTRATION_OBSERVED', result['code'])
        self.assertFalse(result['repairAuthorized'])
        self.assertFalse(result['owner1307Resolved'])

    def test_advertised_registration_is_not_installed_success(self):
        result = probe.registration_diagnosis(PRODUCT_CODE, [PRODUCT_CODE],
            {'status': 'OBSERVED', 'productState': 1, 'defaultFeatureState': 1})
        self.assertEqual('NATIVE_REPAIR_ADVERTISED_REGISTRATION_OBSERVED', result['code'])
        self.assertFalse(result['repairAuthorized'])

    def test_nothing_reported_is_not_a_verified_safe_reinstall(self):
        with self.attempt({'status': 'OBSERVED', 'productState': -1, 'defaultFeatureState': -1}) as (base, installer, _, action):
            with self.assertRaisesRegex(RuntimeError, '^NATIVE_REPAIR_NO_REGISTRATION_REPORTED$'):
                probe.repair_registration(base, installer, 'synthetic-not-reported')
            result = json.loads((base / 'synthetic-not-reported-registration-diagnosis.json').read_text())
            self.assertFalse(result['repairAuthorized'])
            action.assert_not_called()

    def test_other_native_states_remain_unknown_not_absent(self):
        for state in [-7, -6, -5, -4, -3, -2, 0, 2, 3, 4]:
            with self.subTest(state=state):
                result = probe.registration_diagnosis(PRODUCT_CODE, [],
                    {'status': 'OBSERVED', 'productState': state, 'defaultFeatureState': -1})
                self.assertEqual('NATIVE_REPAIR_REGISTRATION_UNKNOWN', result['code'])
                self.assertFalse(result['repairAuthorized'])

    def test_malformed_observations_are_redacted_unavailable(self):
        marker = 'SYNTHETIC_SECRET_MUST_NOT_ESCAPE'
        for product, feature in [(marker, 1), (5.0, 1), (True, 1), (5, None), (5, 99), (5, False)]:
            with self.subTest(productType=type(product).__name__, featureType=type(feature).__name__):
                result = probe.registration_diagnosis(PRODUCT_CODE, [],
                    {'status': 'OBSERVED', 'productState': product, 'defaultFeatureState': feature})
                self.assertEqual('NATIVE_REPAIR_REGISTRATION_UNAVAILABLE', result['code'])
                self.assertNotIn(marker, json.dumps(result))
                self.assertIsNone(result['productState'])

    def test_foreign_duplicate_or_multiple_associations_are_identity_conflicts(self):
        foreign = '{99999999-2222-3333-4444-555555555555}'
        for products in [[foreign], [PRODUCT_CODE, foreign], [PRODUCT_CODE, PRODUCT_CODE]]:
            with self.subTest(count=len(products)):
                result = probe.registration_diagnosis(PRODUCT_CODE, products,
                    {'status': 'OBSERVED', 'productState': 5, 'defaultFeatureState': 3})
                self.assertEqual('NATIVE_REPAIR_REGISTRATION_IDENTITY_CONFLICT', result['code'])
                self.assertFalse(result['repairAuthorized'])

    def test_association_change_during_observation_refuses(self):
        with self.attempt({'status': 'OBSERVED', 'productState': 5, 'defaultFeatureState': 3}) as (base, installer, _, action):
            probe.related.side_effect = [[], [PRODUCT_CODE]]
            with self.assertRaisesRegex(RuntimeError, '^NATIVE_REPAIR_REGISTRATION_CHANGED$'):
                probe.repair_registration(base, installer, 'synthetic-changed')
            action.assert_not_called()

    def test_exact_source_identity_and_safety_guards_precede_observation(self):
        for field, value in [('sha256', 'f' * 64), ('bytes', 17), ('ProductName', 'ForeignSynthetic'),
                             ('ProductCode', '{99999999-2222-3333-4444-555555555555}'),
                             ('ProductVersion', '0.1.1003'), ('safetyVersion', '0')]:
            with self.subTest(field=field):
                with self.attempt({'status': 'OBSERVED', 'productState': 5, 'defaultFeatureState': 1}) as (base, installer, info, action):
                    info[field] = value
                    with self.assertRaises(AssertionError):
                        probe.repair_registration(base, installer, 'synthetic-identity')
                    probe.registration_states.assert_not_called()
                    action.assert_not_called()

    def test_existing_association_guard_is_not_relaxed(self):
        with self.attempt({'status': 'OBSERVED', 'productState': 5, 'defaultFeatureState': 3}, [PRODUCT_CODE]) as (base, installer, _, action):
            with self.assertRaises(AssertionError):
                probe.repair_registration(base, installer, 'synthetic-associated')
            probe.registration_states.assert_not_called()
            action.assert_not_called()

    def test_program_version_guard_is_not_relaxed(self):
        with self.attempt({'status': 'OBSERVED', 'productState': 5, 'defaultFeatureState': 1}) as (base, installer, _, action):
            probe.identity.return_value = {'installerVersion': '0.1.1003'}
            with self.assertRaises(AssertionError):
                probe.repair_registration(base, installer, 'synthetic-version')
            probe.registration_states.assert_not_called()
            action.assert_not_called()

    def test_used_labels_cannot_overwrite_command_result_or_diagnosis(self):
        for suffix in ['command.json', 'result.json', 'registration-diagnosis.json']:
            with self.subTest(suffix=suffix):
                with self.attempt({'status': 'OBSERVED', 'productState': 5, 'defaultFeatureState': 1}) as (base, installer, _, action):
                    retained = base / ('synthetic-used-' + suffix)
                    retained.write_bytes(b'SYNTHETIC_RETAINED_EVIDENCE')
                    with self.assertRaisesRegex(AssertionError, 'NEW_EVIDENCE_LABEL_REQUIRED'):
                        probe.repair_registration(base, installer, 'synthetic-used')
                    self.assertEqual(b'SYNTHETIC_RETAINED_EVIDENCE', retained.read_bytes())
                    probe.registration_states.assert_not_called()
                    action.assert_not_called()

    def test_unsafe_label_refuses_without_creating_outside_report(self):
        with self.attempt({'status': 'OBSERVED', 'productState': 5, 'defaultFeatureState': 1}) as (base, installer, _, action):
            with self.assertRaisesRegex(AssertionError, 'INVALID_EVIDENCE_LABEL'):
                probe.repair_registration(base, installer, '../synthetic-escape')
            probe.registration_states.assert_not_called()
            action.assert_not_called()

    def test_reader_only_queries_the_explicit_product_and_default_feature(self):
        native = types.SimpleNamespace(MsiQueryProductStateW=mock.Mock(return_value=-1),
                                       MsiQueryFeatureStateW=mock.Mock(return_value=1))
        with mock.patch.object(probe.C, 'WinDLL', return_value=native) as factory:
            result = probe.registration_states(PRODUCT_CODE)
        factory.assert_called_once_with('msi')
        native.MsiQueryProductStateW.assert_called_once_with(PRODUCT_CODE)
        native.MsiQueryFeatureStateW.assert_called_once_with(PRODUCT_CODE, 'DefaultFeature')
        self.assertEqual({'status': 'OBSERVED', 'productState': -1, 'defaultFeatureState': 1}, result)

    def test_reader_permission_error_is_unavailable_without_exception_value(self):
        with mock.patch.object(probe.C, 'WinDLL', side_effect=PermissionError('SYNTHETIC_SECRET_MUST_NOT_ESCAPE')):
            result = probe.registration_states(PRODUCT_CODE)
        self.assertEqual('UNAVAILABLE', result['status'])
        self.assertIsNone(result['productState'])
        self.assertNotIn('SYNTHETIC_SECRET', json.dumps(result))

    def test_partial_reader_failure_does_not_keep_a_successful_half_observation(self):
        native = types.SimpleNamespace(MsiQueryProductStateW=mock.Mock(return_value=5),
                                       MsiQueryFeatureStateW=mock.Mock(side_effect=OSError('SYNTHETIC_ERROR')))
        with mock.patch.object(probe.C, 'WinDLL', return_value=native):
            result = probe.registration_states(PRODUCT_CODE)
        self.assertEqual({'status': 'UNAVAILABLE', 'productState': None, 'defaultFeatureState': None}, result)

    def test_second_association_read_failure_is_unavailable_and_redacted(self):
        with self.attempt({'status': 'OBSERVED', 'productState': 5, 'defaultFeatureState': 1}) as (base, installer, _, action):
            probe.related.side_effect = [[], OSError('SYNTHETIC_SECRET_MUST_NOT_ESCAPE')]
            with self.assertRaisesRegex(RuntimeError, '^NATIVE_REPAIR_REGISTRATION_UNAVAILABLE$'):
                probe.repair_registration(base, installer, 'synthetic-read-error')
            report = (base / 'synthetic-read-error-registration-diagnosis.json').read_text()
            self.assertNotIn('SYNTHETIC_SECRET', report)
            self.assertIsNone(json.loads(report)['relatedProductCount'])
            action.assert_not_called()

    def test_cancellation_propagates_without_installer_or_success_report(self):
        with self.attempt({'status': 'OBSERVED', 'productState': 5, 'defaultFeatureState': 1}) as (base, installer, _, action):
            probe.registration_states.side_effect = KeyboardInterrupt()
            with self.assertRaises(KeyboardInterrupt):
                probe.repair_registration(base, installer, 'synthetic-cancel')
            self.assertFalse((base / 'synthetic-cancel-registration-diagnosis.json').exists())
            action.assert_not_called()

    def test_retained_success_oracle_accepts_only_all_matching_synthetic_conditions(self):
        before = {'program.bin': 'synthetic-hash'}
        probe.assert_repair_success(0, [PRODUCT_CODE], PRODUCT_CODE, before, dict(before))

    def test_retained_success_oracle_rejects_nonzero_exit(self):
        with self.assertRaisesRegex(AssertionError, 'NATIVE_REPAIR_NOT_COMPLETE'):
            probe.assert_repair_success(1603, [PRODUCT_CODE], PRODUCT_CODE, {'a': 'x'}, {'a': 'x'})

    def test_retained_success_oracle_rejects_missing_foreign_or_duplicate_association(self):
        for products in [[], ['{99999999-2222-3333-4444-555555555555}'], [PRODUCT_CODE, PRODUCT_CODE]]:
            with self.subTest(products=len(products)):
                with self.assertRaises(AssertionError):
                    probe.assert_repair_success(0, products, PRODUCT_CODE, {'a': 'x'}, {'a': 'x'})

    def test_retained_success_oracle_rejects_modified_added_removed_or_renamed_program(self):
        for after in [{'a': 'changed'}, {'a': 'x', 'extra': 'y'}, {}, {'renamed': 'x'}]:
            with self.subTest(fileCount=len(after)):
                with self.assertRaises(AssertionError):
                    probe.assert_repair_success(0, [PRODUCT_CODE], PRODUCT_CODE, {'a': 'x'}, after)


if __name__ == '__main__':
    unittest.main(verbosity=2)
