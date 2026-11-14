"""Profile/provenance guard checks; no native execution or real package bytes."""
import copy
import unittest

import desktop_control as control
from desktop_lan5_control import DELIVERED_V4, native, profile


def upgrade_request():
    candidate = dict(DELIVERED_V4, series='lan5', version=control.VERSION,
                     sourceSha='1' * 40, controlSha='2' * 40, runId=101, artifactId=102,
                     artifactName=control.ARTIFACT_NAME, outerBytes=103, outerSha256='3' * 64,
                     filename=control.FILENAME, innerBytes=104, innerSha256='4' * 64,
                     installerVersion=control.INSTALLER, buildNumber=control.BUILD)
    return {'kind': control.UPGRADE_KIND, 'format': 1, 'packages': [dict(DELIVERED_V4), candidate],
            'timestampRule': control.TIMESTAMP_RULE, 'allowedStateMigrations': [],
            'timestampSourceBindings': {
                'baseSourceSha': DELIVERED_V4['sourceSha'], 'candidateSourceSha': '1' * 40,
                'files': [{'path': p, 'baseSha256': '5' * 64, 'candidateSha256': '5' * 64}
                          for p in control.TIMESTAMP_PATHS]}}


class Lan5ProfileTests(unittest.TestCase):
    def test_new_profile_cannot_change_the_exact_delivered_base(self):
        with profile():
            request = control.build_request('1' * 40)
            self.assertEqual(request['deliveredBase'], dict(DELIVERED_V4))
            self.assertEqual((request['version'], request['installerVersion'], request['buildNumber']),
                             ('0.1.0-lan.5', '0.1.40005', '40005'))
            control.validate_build(request)
            for key, value in [('sourceSha', '9' * 40), ('innerSha256', '9' * 64),
                               ('artifactId', 1), ('outerBytes', 1), ('series', 'lan3')]:
                changed = copy.deepcopy(request)
                changed['deliveredBase'][key] = value
                with self.assertRaises(ValueError):
                    control.validate_build(changed)

    def test_old_profile_and_waivers_cannot_be_substituted(self):
        with profile():
            request = upgrade_request()
            control.validate_upgrade(request)
            for change in (lambda r: r.update(kind='CCB_EXACT_LAN3_LAN4_DESKTOP_PROFILE_UPGRADE_V1'),
                           lambda r: r['packages'][1].update(series='lan4'),
                           lambda r: r['packages'][1].update(buildNumber='40004'),
                           lambda r: r['packages'][0].update(controlSha='6' * 40),
                           lambda r: r.update(allowedStateMigrations=['*']),
                           lambda r: r['timestampSourceBindings'].update(baseSourceSha='7' * 40)):
                changed = copy.deepcopy(request)
                change(changed)
                with self.assertRaises(ValueError):
                    control.validate_upgrade(changed)

    def test_exact_api_and_byte_provenance_remains_required(self):
        with profile():
            row = upgrade_request()['packages'][1]
            run = {'id': row['runId'], 'head_sha': row['controlSha'], 'conclusion': 'success'}
            artifact = {'id': row['artifactId'], 'workflow_run': dict(id=row['runId'], head_sha=row['controlSha']),
                        'name': row['artifactName'], 'expired': False, 'size_in_bytes': row['outerBytes'],
                        'digest': 'sha256:' + row['outerSha256']}
            control.metadata(row, artifact, run)
            for key, value in [('id', 999), ('name', 'ccb-lan3-lan4-independent-desktop'),
                               ('size_in_bytes', 999), ('expired', True), ('digest', 'sha256:' + '8' * 64)]:
                wrong = copy.deepcopy(artifact)
                wrong[key] = value
                with self.assertRaises(ValueError):
                    control.metadata(row, wrong, run)

    def test_old_defaults_return_even_when_profile_refuses(self):
        old = control.BASE, control.SERIES, control.MANIFEST_KIND, native.BASE_SERIES, native.RESULT_FILE
        with self.assertRaisesRegex(ValueError, 'synthetic refusal'):
            with profile():
                self.assertEqual((native.BASE_SERIES, native.CANDIDATE_SERIES), ('lan4', 'lan5'))
                self.assertEqual(native.RESULT_FILE, 'native-lan4-lan5-upgrade-result.json')
                raise ValueError('synthetic refusal')
        self.assertEqual((control.BASE, control.SERIES, control.MANIFEST_KIND, native.BASE_SERIES, native.RESULT_FILE), old)


if __name__ == '__main__':
    unittest.main()
