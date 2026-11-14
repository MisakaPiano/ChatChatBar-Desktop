"""Pure Python/small temporary files only; no SDK, signing, JVM, device or network."""
from contextlib import contextmanager
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import contract as old
import candidate84_profile as profile
import candidate84_build as build
import candidate84_sign as signing
import candidate84_upgrade as upgrade

SOURCE = '5847faa52404cf7f6a67515d3cecf00103ddafe5'


class Candidate84Tests(unittest.TestCase):
    def test_official_verification_requires_exactly_one_complete_signer_count_and_digest(self):
        text = ('Verified using v2 scheme (APK Signature Scheme v2): true\n'
                'Number of signers: 1\nSigner #1 certificate SHA-256 digest: ' + profile.CERTIFICATE + '\n')
        signing.require_single_v2(text)
        signing.require_single_v2(text.replace('\n', '\r\n'))
        for wrong in (text.replace('signers: 1', 'signers: 10'), text.replace('signers: 1', 'signers: 11'),
                      text.replace('signers: 1', 'signers: 1 extra'), text + 'Number of signers: 1\n',
                      text + 'Signer #2 certificate SHA-256 digest: ' + profile.CERTIFICATE + '\n',
                      text.replace('true\n', 'true extra\n'), text.replace(profile.CERTIFICATE, '0' * 64),
                      text.replace('Signer #1', 'Signer #2')):
            with self.subTest(wrong=wrong), self.assertRaises(ValueError):
                signing.require_single_v2(wrong)

    def test_state_parser_import_is_exact_inert_next_module_despite_old_assembler_path(self):
        self.assertEqual(upgrade.package_state.__module__, '_candidate84_existing_install_state')
        value = upgrade.package_state('userId=123\nversionCode=83\nfirstInstallTime=same\ndataDir=/data/user/0/app\n')
        self.assertEqual(value['versionCode'], 83)

    def test_fixed_v4_base_and_new_request_are_distinct_from_old_contract(self):
        value = profile.build_request(SOURCE)
        self.assertEqual(value['versions'], [83, 84])
        self.assertEqual(value['deliveredBase']['deliveredBytes'], 108385763)
        self.assertEqual(value['deliveredBase']['deliveredSha256'],
                         'ee602f895d31cb5f965821a6107c6584e057c5f0192faf8225a5e84e96e4af7b')
        self.assertEqual(profile.validate_build(value), value)
        self.assertEqual(old.build_request(SOURCE)['versions'], [82, 83])
        with self.assertRaises(ValueError): profile.validate_build(old.build_request(SOURCE))
        with self.assertRaises(ValueError): old.validate_build(value)

    def test_request_refuses_wrong_version_source_package_fixture_and_migration(self):
        value = profile.build_request(SOURCE)
        changes = [('versions', [82, 84]), ('package', 'com.example.chatbar.lansync'),
                   ('format', True), ('format', 2), ('sourceSha', 'HEAD'),
                   ('certificateSha256', '0' * 64), ('allowedStateMigrations', ['ignore'])]
        for key, item in changes:
            bad = deepcopy(value); bad[key] = item
            with self.subTest(key=key, item=item), self.assertRaises(ValueError): profile.validate_build(bad)
        for key in ('api', 'fixturePath', 'target', 'identityFile'):
            bad = deepcopy(value); bad[key] = 'arbitrary'
            with self.subTest(key=key), self.assertRaises(ValueError): profile.validate_build(bad)
        bad = deepcopy(value); bad['fixtures']['verify84']['path'] = '../outside.kt'
        with self.assertRaises(ValueError): profile.validate_build(bad)
        bad = deepcopy(value); bad['deliveredBase']['rawSha256'] = '0' * 64
        with self.assertRaises(ValueError): profile.validate_build(bad)

    def test_exact_published83_public_block_is_reused_without_opening_an_identity(self):
        block = profile.base_public_block()
        self.assertEqual(block['certificateSha256'], profile.CERTIFICATE)
        self.assertEqual(block['sourceSha256'], profile.BASE['rawSha256'])
        profile.block(block, profile.BASE['rawSha256'], True)
        for key, item in [('certificateSha256', '0' * 64), ('bytes', 1),
                          ('outputSha256', '0' * 64), ('format', True)]:
            bad = deepcopy(block); bad[key] = item
            with self.subTest(key=key), self.assertRaises(ValueError): profile.block(bad, profile.BASE['rawSha256'], True)

    def sign_request(self):
        base = profile.base_public_block()
        entries = {}
        for index, (role, (filename, _, _)) in enumerate(profile.ROLES.items(), 1):
            public = deepcopy(base)
            if role != 'base83':
                public.update(sourceSha256=str(index) * 64, outputSha256='a' * 64, bytes=1)
            entries[role] = {'filename': filename, 'publicSigning': public}
        return {'kind': profile.SIGN_KIND, 'format': 1, 'buildRequest': profile.build_request(SOURCE),
                'originalArtifact': {'id': 1, 'runId': 2, 'name': profile.ARTIFACT_NAME, 'bytes': 3,
                                     'outerSha256': 'b' * 64, 'controlSha': 'c' * 40},
                'rawManifest': {'bytes': 1, 'sha256': 'd' * 64}, 'entries': entries}

    def test_sign_request_binds_fixed_roles_real_base_and_new_artifact_namespace(self):
        value = self.sign_request()
        profile.validate_sign(value)
        bad = deepcopy(value); bad['entries']['candidate84']['filename'] = '../other.apk'
        with self.assertRaises(ValueError): profile.validate_sign(bad)
        bad = deepcopy(value); bad['originalArtifact']['name'] = old.REUSED_ORIGINAL_ARTIFACT['name']
        with self.assertRaises(ValueError): profile.validate_sign(bad)
        bad = deepcopy(value); bad['entries']['base83']['publicSigning']['blockBase64'] += 'AAAA'
        with self.assertRaises(ValueError): profile.validate_sign(bad)
        bad = deepcopy(value); bad['rawManifest']['sha256'] = 'not-a-hash'
        with self.assertRaises(ValueError): profile.validate_sign(bad)

    @contextmanager
    def synthetic_raw(self):
        # Explicit test-only replacement of the large base descriptor; all patches
        # restore afterward. Production profile and its V4 bytes are never changed.
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            base = deepcopy(profile.BASE)
            base.update(rawBytes=4, rawSha256=hashlib.sha256(b'base').hexdigest())
            with patch.object(profile, 'BASE', base), patch.object(build, 'BASE', base):
                request = profile.build_request(SOURCE)
                rows = []
                for role, (filename, package, version) in profile.ROLES.items():
                    content = b'base' if role == 'base83' else role.encode('ascii')
                    (root / filename).write_bytes(content)
                    rows.append({'role': role, 'filename': filename, 'package': package, 'versionCode': version,
                        'sourceSha': base['sourceSha'] if role in ('base83', 'seed83') else SOURCE,
                        'testFixture': request['fixtures'].get(role), 'bytes': len(content),
                        'sha256': hashlib.sha256(content).hexdigest(), 'deliveredBase': base if role == 'base83' else None})
                manifest = {'kind': profile.RAW_KIND, 'sourceSha': SOURCE, 'controlSha': 'c' * 40,
                    'buildRequest': request, 'baseArtifact': profile.base_artifact(), 'inputs': rows}
                profile.write_new(root / 'source-request.json', request)
                profile.write_new(root / 'raw-manifest.json', manifest)
                yield root, request, manifest

    def test_actual_small_file_validation_rejects_changed_bytes_and_cross_source(self):
        with self.synthetic_raw() as (root, request, manifest):
            build.validate_raw(root, request, 'c' * 40)
            (root / 'candidate84-input.apk').write_bytes(b'changed')
            with self.assertRaises(ValueError): build.validate_raw(root, request, 'c' * 40)
        with self.synthetic_raw() as (root, request, manifest):
            manifest['inputs'][2]['sourceSha'] = profile.BASE['sourceSha']
            (root / 'raw-manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
            with self.assertRaises(ValueError): build.validate_raw(root, request)

    def test_raw_validation_rejects_role_alias_duplicate_and_wrong_control(self):
        for mode in ('alias', 'duplicate', 'control'):
            with self.subTest(mode=mode), self.synthetic_raw() as (root, request, manifest):
                if mode == 'alias': manifest['inputs'][0]['filename'] = '../base83-input.apk'
                if mode == 'duplicate': manifest['inputs'][-1] = manifest['inputs'][0]
                (root / 'raw-manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
                with self.assertRaises(ValueError): build.validate_raw(root, request, 'd' * 40 if mode == 'control' else 'c' * 40)

    def test_ordinary_install_identity_must_survive_exact83_to84_pair(self):
        before = {'uid': 123, 'versionCode': 83, 'firstInstallTime': 'same', 'dataDir': '/data/user/0/app'}
        after = dict(before, versionCode=84)
        upgrade.unchanged_install(before, after)
        for key, item in [('uid', 999), ('firstInstallTime', 'new'), ('dataDir', '/new'), ('versionCode', 83)]:
            with self.subTest(key=key), self.assertRaises(ValueError): upgrade.unchanged_install(before, dict(after, **{key: item}))

    def test_cloud_guards_precede_any_sdk_adb_or_download(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(upgrade.subprocess, 'run') as call:
            with self.assertRaises(ValueError): upgrade.run(Path('does-not-exist'))
            with self.assertRaises(ValueError): signing.fetch({}, Path('missing'), Path('missing'), Path('missing'))
            with self.assertRaises(ValueError): build.prepare({}, Path('missing'), Path('missing'), Path('missing'))
            call.assert_not_called()

    def test_canonical_fixtures_are_one_stage_each_and_verifier_never_seeds(self):
        seed = (profile.HERE / 'candidate84_seed83.kt').read_text('utf-8')
        verify = (profile.HERE / 'candidate84_verify84.kt').read_text('utf-8')
        self.assertEqual(seed.count('@Test fun '), 1)
        self.assertEqual(verify.count('@Test fun '), 1)
        self.assertNotIn('seedSyntheticRepositoryAndProtectedPeerIdentity', verify)
        self.assertIn('context(83)', seed)
        self.assertIn('context(84)', verify)
        self.assertIn('Existing Keystore alias is required', verify)
        self.assertIn('capture-scratch/resources/', verify)
        self.assertIn('capturedResources[hash]', verify)
        for text in (seed, verify):
            self.assertIn(profile.ARGUMENT, text)
            self.assertIn(profile.PROOF_ROOT, text)
            self.assertNotIn('\r', text)

    def test_duplicate_request_keys_are_rejected(self):
        with self.assertRaises(ValueError): json.loads('{"format":1,"format":2}', object_pairs_hook=profile.unique_object)

    def test_sample_absence_accepts_shell_v2_stderr_but_not_permission_or_other_failure(self):
        path = upgrade.SAMPLE_PATHS[0]
        missing = 'ls: ' + path + ': No such file or directory\n'
        upgrade.require_sample_absent('', missing.encode('utf-8'), 1, path)
        upgrade.require_sample_absent(missing, b'', 1, path)
        for stdout, stderr, code in [('', ('ls: ' + path + ': Permission denied').encode(), 1),
                                     ('', missing.encode(), 0), ('', missing.encode(), 127),
                                     ('', b'ls: other: No such file or directory', 1)]:
            with self.subTest(code=code, stderr=stderr), self.assertRaises(ValueError):
                upgrade.require_sample_absent(stdout, stderr, code, path)

    def test_instrument_caller_uses_exact_stage_and_strict_execution_not_ok_marker(self):
        def status(code):
            return ('INSTRUMENTATION_STATUS: numtests=1\n'
                    'INSTRUMENTATION_STATUS: stream=.\n'
                    'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n'
                    'INSTRUMENTATION_STATUS: test=' + profile.VERIFY_METHOD + '\n'
                    'INSTRUMENTATION_STATUS: class=' + profile.FIXTURE_CLASS + '\n'
                    'INSTRUMENTATION_STATUS: current=1\n'
                    'INSTRUMENTATION_STATUS_CODE: ' + str(code) + '\n')
        report = status(1) + status(0) + 'INSTRUMENTATION_RESULT: stream=\n\nTime: 1.25\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n'
        device = upgrade.Device(Path('unused-synthetic-path'))
        with patch.object(device, 'command', return_value=(report, b'', 0)) as command:
            result = device.instrument(profile.FIXTURE_CLASS, profile.VERIFY_METHOD, 'verify', profile.ARGUMENT)
            self.assertEqual(result['rawStatuses'], [1, 0])
            self.assertEqual(result['seconds'], 1.25)
            args = command.call_args.args[0]
            self.assertIn(profile.FIXTURE_CLASS + '#' + profile.VERIFY_METHOD, args)
            self.assertIn(profile.ARGUMENT, args)
        for bad in (report.replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: -3'),
                    report.replace('class=' + profile.FIXTURE_CLASS, 'class=other.Class'), 'OK (1 test)\n'):
            with self.subTest(report=bad), patch.object(device, 'command', return_value=(bad, b'', 0)):
                with self.assertRaises(ValueError): device.instrument(profile.FIXTURE_CLASS, profile.VERIFY_METHOD, 'verify', profile.ARGUMENT)
        with patch.object(device, 'command', return_value=(report, b'unexpected error', 0)):
            with self.assertRaises(ValueError): device.instrument(profile.FIXTURE_CLASS, profile.VERIFY_METHOD, 'verify', profile.ARGUMENT)


if __name__ == '__main__': unittest.main()
