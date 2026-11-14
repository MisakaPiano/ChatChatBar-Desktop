"""Pure Python LAN6 profile/provenance/derivation guards; never run a native owner."""
import copy
import hashlib
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

import desktop_control as control
import desktop_lan5_control as lan5
import desktop_lan6_control as lan6
import prove_desktop_lan5_lan6 as proof
import desktop_lan6_workflow_plan as workflow_plan

CONTROL_ROOT = Path(os.environ.get('CCB_LAN6_READONLY_CONTROL', Path(__file__).resolve().parents[2]))
CONTROL_KEYS = ('BUILD_KIND', 'UPGRADE_KIND', 'ARTIFACT_NAME', 'MANIFEST_KIND',
                'SERIES', 'VERSION', 'INSTALLER', 'BUILD', 'FILENAME', 'BASE')
NATIVE_KEYS = ('BASE_SERIES', 'CANDIDATE_SERIES', 'RESULT_KIND', 'RESULT_FILE', 'SUCCESS_MARKER')
EXPECTED_BASE = {
    'series': 'lan5', 'version': '0.1.0-lan.5',
    'sourceSha': 'e606bf2a2723fbc896323a8071cfd52dde99a4c6',
    'controlSha': 'fc20ab621cae06e8cb7b6bb0f177751253e51238',
    'runId': 43602954624, 'artifactId': 14117541676,
    'artifactName': 'ccb-lan4-lan5-independent-desktop',
    'outerBytes': 209117556,
    'outerSha256': 'cc0ceff09b889d6f316d627fc9773e310f4d3371e442d01477ff1811b8bf8299',
    'filename': 'CCB-0.1.0-lan.5-independent-windows-x64.zip',
    'innerBytes': 209002343,
    'innerSha256': 'a3620bd2892b8f5fdfe6aaace2fd65b2ac9ee5caad33c0d85adbf73c7f659d84',
    'installerVersion': '0.1.40005', 'buildNumber': '40005',
}


def snapshot():
    return ({key: getattr(control, key) for key in CONTROL_KEYS},
            {key: getattr(lan6.native, key) for key in NATIVE_KEYS})


def synthetic_upgrade():
    candidate = dict(EXPECTED_BASE, series='lan6', version=control.VERSION,
        sourceSha='1' * 40, controlSha='2' * 40, runId=101, artifactId=102,
        artifactName=control.ARTIFACT_NAME, outerBytes=103, outerSha256='3' * 64,
        filename=control.FILENAME, innerBytes=104, innerSha256='4' * 64,
        installerVersion=control.INSTALLER, buildNumber=control.BUILD)
    return {'kind': control.UPGRADE_KIND, 'format': 1,
        'packages': [dict(EXPECTED_BASE), candidate],
        'timestampRule': control.TIMESTAMP_RULE, 'allowedStateMigrations': [],
        'timestampSourceBindings': {
            'baseSourceSha': EXPECTED_BASE['sourceSha'], 'candidateSourceSha': '1' * 40,
            'files': [{'path': path, 'baseSha256': '5' * 64, 'candidateSha256': '5' * 64}
                      for path in control.TIMESTAMP_PATHS]}}


class Lan6DraftTests(unittest.TestCase):
    def assert_restored(self, expected):
        actual = snapshot()
        self.assertEqual(actual, expected)
        self.assertIs(control.BASE, expected[0]['BASE'])

    def test_fixed_interim_delivered_base_and_exact_lan6_identity(self):
        self.assertEqual(dict(lan6.DELIVERED_V5), EXPECTED_BASE)
        with self.assertRaises(TypeError):
            lan6.DELIVERED_V5['sourceSha'] = '9' * 40
        before = snapshot()
        with lan6.profile():
            # An in-memory synthetic contract only; no request JSON is written.
            request = control.build_request('1' * 40)
            control.validate_build(request)
            self.assertEqual(request['deliveredBase'], EXPECTED_BASE)
            self.assertEqual((request['version'], request['installerVersion'], request['buildNumber']),
                             ('0.1.0-lan.6', '0.1.40006', '40006'))
            self.assertEqual((lan6.native.BASE_SERIES, lan6.native.CANDIDATE_SERIES), ('lan5', 'lan6'))
            self.assertEqual(lan6.native.RESULT_FILE, 'native-lan5-lan6-upgrade-result.json')
        self.assert_restored(before)

    def test_every_baseline_field_and_request_profile_override_is_rejected(self):
        with lan6.profile():
            original = control.build_request('1' * 40)
            for key, value in EXPECTED_BASE.items():
                changed = copy.deepcopy(original)
                changed['deliveredBase'][key] = value + 1 if type(value) is int else value + '-changed'
                with self.subTest(field=key), self.assertRaises(ValueError):
                    control.validate_build(changed)
            for key, value in [('profile', 'lan5'), ('version', '0.1.0-lan.5'),
                               ('sourceSha', EXPECTED_BASE['sourceSha'])]:
                changed = copy.deepcopy(original); changed[key] = value
                with self.assertRaises(ValueError):
                    control.validate_build(changed)

    def test_upgrade_refuses_old_roles_hashes_waivers_and_unbound_source(self):
        with lan6.profile():
            original = synthetic_upgrade(); control.validate_upgrade(original)
            changes = [
                lambda r: r.update(kind='CCB_EXACT_LAN4_LAN5_DESKTOP_PROFILE_UPGRADE_V1'),
                lambda r: r['packages'][0].update(innerSha256='9' * 64),
                lambda r: r['packages'][0].update(sourceSha='9' * 40),
                lambda r: r['packages'][1].update(series='lan5'),
                lambda r: r['packages'][1].update(buildNumber='40005'),
                lambda r: r['packages'][1].update(artifactName='ccb-lan4-lan5-independent-desktop'),
                lambda r: r.update(allowedStateMigrations=['*']),
                lambda r: r.update(extra=True),
                lambda r: r['timestampSourceBindings'].update(baseSourceSha='9' * 40),
                lambda r: r['timestampSourceBindings'].update(candidateSourceSha='9' * 40),
            ]
            for change in changes:
                changed = copy.deepcopy(original); change(changed)
                with self.assertRaises(ValueError):
                    control.validate_upgrade(changed)

    def test_original_api_ids_control_digest_and_bytes_are_still_required(self):
        row = EXPECTED_BASE
        run = {'id': row['runId'], 'head_sha': row['controlSha'], 'conclusion': 'success'}
        artifact = {'id': row['artifactId'], 'name': row['artifactName'], 'expired': False,
                    'workflow_run': {'id': row['runId'], 'head_sha': row['controlSha']},
                    'size_in_bytes': row['outerBytes'], 'digest': 'sha256:' + row['outerSha256']}
        control.metadata(row, artifact, run)
        for field, wrong in [('id', 1), ('name', 'ccb-lan5-lan6-independent-desktop'),
                             ('expired', True), ('size_in_bytes', row['outerBytes'] + 1),
                             ('digest', 'sha256:' + '9' * 64)]:
            changed = copy.deepcopy(artifact); changed[field] = wrong
            with self.assertRaises(ValueError):
                control.metadata(row, changed, run)
        for field, wrong in [('id', 1), ('head_sha', '9' * 40), ('conclusion', 'failure')]:
            changed = dict(run); changed[field] = wrong
            with self.assertRaises(ValueError):
                control.metadata(row, artifact, changed)

    def test_nested_profiles_and_exception_restore_every_old_default(self):
        original = snapshot()
        with lan5.profile():
            old_five = snapshot()
            self.assertEqual((control.SERIES, lan6.native.BASE_SERIES), ('lan5', 'lan4'))
            with self.assertRaisesRegex(RuntimeError, 'synthetic profile refusal'):
                with lan6.profile():
                    six = snapshot()
                    with lan5.profile():
                        self.assertEqual(control.SERIES, 'lan5')
                    self.assert_restored(six)
                    raise RuntimeError('synthetic profile refusal')
            self.assert_restored(old_five)
        self.assert_restored(original)

    def test_proof_entry_reuses_five_launch_engine_and_restores_on_refusal(self):
        original = snapshot(); observed = []
        def refuse(request, output):
            observed.append((request, output, control.SERIES,
                             lan6.native.BASE_SERIES, lan6.native.CANDIDATE_SERIES))
            raise RuntimeError('synthetic engine refusal')
        sentinel = object()
        with patch.object(proof, 'load', return_value=sentinel), \
             patch.object(lan6.native, 'run', side_effect=refuse), \
             patch.object(sys, 'argv', ['draft', '--request', 'not-created.json', '--output', 'not-created']):
            with self.assertRaisesRegex(RuntimeError, 'synthetic engine refusal'):
                proof.main()
        self.assertEqual(observed, [(sentinel, Path('not-created'), 'lan6', 'lan5', 'lan6')])
        self.assert_restored(original)

    def test_timestamp_exception_remains_exact_and_source_bound(self):
        with lan6.profile():
            request = synthetic_upgrade()
            self.assertEqual(request['timestampRule'],
                             'OBSERVED_OLD_CONTROL_CAPTURED_AT_ONLY_WITH_IDENTICAL_SOURCE')
            control.validate_upgrade(request)
            self.assertTrue(control.timestamp_exception_enabled(request))
            request['timestampSourceBindings']['files'][0]['candidateSha256'] = '9' * 64
            self.assertFalse(control.timestamp_exception_enabled(request))
            request['allowedStateMigrations'] = ['capturedAt']
            with self.assertRaises(ValueError):
                control.validate_upgrade(request)

    def test_workflows_derive_only_from_exact_templates_with_old_guards_retained(self):
        plan = json.loads(workflow_plan.PLAN.read_text(encoding='utf8'))
        rendered = workflow_plan.render_workflows(CONTROL_ROOT)
        self.assertEqual(set(rendered), {spec['output'] for spec in plan['workflows']})
        for spec in plan['workflows']:
            original = workflow_plan.lf_text((CONTROL_ROOT / spec['template']).read_bytes())
            derived = rendered[spec['output']]
            self.assertNotEqual(spec['template'], spec['output'])
            self.assertEqual(hashlib.sha256(original.encode()).hexdigest(), spec['templateLfSha256'])
            self.assertNotIn('desktop_lan5_control.py', derived)
            self.assertNotIn('prove_desktop_lan4_lan5.py', derived)
            self.assertIn('desktop_lan6_control.py', derived)
            with self.assertRaisesRegex(ValueError, 'WORKFLOW_TEMPLATE_CHANGED'):
                workflow_plan.render_one(original + '# changed\n', spec)
            bad = copy.deepcopy(spec); bad['replacements'][0][2] += 1
            with self.assertRaisesRegex(ValueError, 'WORKFLOW_REPLACEMENT_COUNT'):
                workflow_plan.render_one(original, bad)
        build = rendered['.github/workflows/lan-next-sync-desktop-lan6-build.yml']
        for required in ('persist-credentials: false', 'verifyDesktopInstallerIdentity',
                         'attachDesktopUpdater', 'audit_app_image.py', 'build_independent_zip.py',
                         'independent_smoke.py', 'desktop_lan6_control.py collect',
                         'ccb-lan5-lan6-independent-desktop',
                         '.github/lan-next-sync-desktop-lan6-build-request.json'):
            self.assertIn(required, build)
        upgrade = rendered['.github/workflows/lan-next-sync-desktop-lan6-upgrade.yml']
        for required in ('actions: read', 'fetch-originals', 'prove_desktop_lan5_lan6.py',
                         'Five actual native launches with Program-only replacement',
                         '.github/lan-next-sync-desktop-lan6-upgrade-request.json'):
            self.assertIn(required, upgrade)


if __name__ == '__main__':
    unittest.main()
