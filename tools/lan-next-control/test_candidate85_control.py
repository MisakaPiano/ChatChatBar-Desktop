"""Pure Python/FS guards. Synthetic rows are not APK/SDK/native evidence."""
from copy import deepcopy
from dataclasses import FrozenInstanceError, replace
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
from types import MappingProxyType
from types import SimpleNamespace
import unittest

from candidate85_profile import PROFILE
from upgrade_series_engine import LegacyTools, SeriesEngine

HERE = Path(__file__).resolve().parent
CONTROL = Path(os.environ.get('CCB_EXISTING_CONTROL_ROOT', HERE.parents[1]))


def transcript(class_name, method, terminal=-1, finish=0):
    def block(code):
        return '\n'.join(('INSTRUMENTATION_STATUS: class=' + class_name,
            'INSTRUMENTATION_STATUS: test=' + method, 'INSTRUMENTATION_STATUS: numtests=1',
            'INSTRUMENTATION_STATUS: current=1', 'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner',
            'INSTRUMENTATION_STATUS_CODE: ' + str(code)))
    return block(1) + '\n' + block(finish) + '\nINSTRUMENTATION_RESULT: stream=\nTime: 0.125\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: ' + str(terminal) + '\n'


class Candidate85ControlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tools = LegacyTools(CONTROL)
        cls.engine = SeriesEngine(PROFILE, cls.tools, HERE)

    def temp(self):
        directory = tempfile.TemporaryDirectory(prefix='guard-', dir=HERE.parents[1])
        self.addCleanup(directory.cleanup)
        return Path(directory.name)

    def test_profile_is_immutable_and_roles_explicit(self):
        with self.assertRaises(FrozenInstanceError): PROFILE.base_role = 'base83'
        with self.assertRaises(TypeError): PROFILE.base['sourceSha'] = 'a' * 40
        with self.assertRaises(ValueError): replace(PROFILE, roles=tuple(reversed(PROFILE.roles)))
        self.assertEqual(tuple(r.name for r in PROFILE.roles), ('base84', 'seed84', 'candidate85', 'verify85'))

    def test_strict_build_rejects_cross_profile_and_unknown_fields(self):
        request = self.engine.build_request('a' * 40)
        self.assertEqual(self.engine.validate_build(request), request)
        bad = deepcopy(request); bad['versions'] = [83, 84]
        with self.assertRaises(ValueError): self.engine.validate_build(bad)
        bad = deepcopy(request); bad['profile'] = 'candidate84'
        with self.assertRaises(ValueError): self.engine.validate_build(bad)
        with self.assertRaises(ValueError): self.engine.validate_build(self.tools.candidate84_profile.build_request('a' * 40))
        with self.assertRaises(ValueError): self.tools.candidate84_profile.validate_build(request)

    def test_old_defaults_unchanged_and_namespaces_not_rebound(self):
        old = self.tools.candidate84_profile
        snapshot = deepcopy((old.BASE, old.ROLES, old.BUILD_KIND, old.PROOF_ROOT))
        common_base = deepcopy(self.tools.contract.BASE)
        before = list(sys.path)
        self.engine.build_request('b' * 40); self.engine.base_public_block()
        LegacyTools(CONTROL)
        self.assertEqual(snapshot, (old.BASE, old.ROLES, old.BUILD_KIND, old.PROOF_ROOT))
        self.assertEqual(common_base, self.tools.contract.BASE)
        self.assertEqual(before, sys.path)
        self.assertEqual(old.BASE['versionCode'], 83)
        self.assertEqual(self.tools.candidate84_build.validate_raw.__globals__['BASE']['versionCode'], 83)

    def test_frozen_public_base_is_exact_original84_block(self):
        block = self.engine.base_public_block()
        self.assertEqual(block['sourceSha256'], PROFILE.base['rawSha256'])
        self.assertEqual(block['outputSha256'], PROFILE.base['deliveredSha256'])
        self.assertEqual(block['bytes'], PROFILE.base['deliveredBytes'])
        self.assertIs(block['officialVerifierPassed'], False)
        block['bytes'] = 1
        self.assertEqual(self.engine.base_public_block()['bytes'], 108959291)

    def test_wrong_public_request_pin_and_raw_source_rejected(self):
        bad = replace(PROFILE, base_public_sha256='0' * 64)
        with self.assertRaisesRegex(ValueError, 'FROZEN_PUBLIC_REQUEST_CHANGED'):
            SeriesEngine(bad, self.tools, HERE).base_public_block()
        good = self.engine.base_public_block()
        with self.assertRaises(ValueError): self.engine.block(good, '0' * 64, True)
        bad = deepcopy(good); bad['bytes'] += 1
        with self.assertRaises(ValueError): self.engine.block(bad, PROFILE.base['rawSha256'], True)

    def test_wrong_base_manifest_rejected_before_old84_original_reader(self):
        directory = self.temp()
        (directory / 'raw-manifest.json').write_text('{}', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'EXACT_BASE_MANIFEST'):
            self.engine.validate_base(directory)
        self.assertEqual(PROFILE.base_manifest['bytes'], 4587)
        self.assertEqual(self.engine.base_artifact()['id'], 14117481315)

    def test_artifact_identity_and_sign_cross_profile_fields_rejected(self):
        value = {'id': 1, 'runId': 2, 'name': PROFILE.artifact_name, 'bytes': 3,
                 'outerSha256': 'a' * 64, 'controlSha': 'b' * 40}
        self.engine.artifact_metadata(value)
        for key, replacement in (('name', self.tools.candidate84_profile.ARTIFACT_NAME), ('id', True)):
            bad = dict(value); bad[key] = replacement
            with self.assertRaises(ValueError): self.engine.artifact_metadata(bad)
        with self.assertRaisesRegex(ValueError, 'SIGN_KIND'):
            self.engine.validate_sign({'kind': self.tools.candidate84_profile.SIGN_KIND, 'format': 1,
                'buildRequest': {}, 'originalArtifact': {}, 'rawManifest': {}, 'entries': {}})

    def test_single_signer_v2_guard_reused_without_relaxation(self):
        text = 'Verified using v2 scheme (APK Signature Scheme v2): true\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: ' + PROFILE.certificate + '\n'
        guard = self.tools.candidate84_sign.require_single_v2
        guard(text)
        for bad in (text.replace('true', 'false'), text.replace('signers: 1', 'signers: 2'),
                    text.replace(PROFILE.certificate, '0' * 64), text + 'Signer #2 certificate SHA-256 digest: ' + PROFILE.certificate + '\n'):
            with self.assertRaises(ValueError): guard(bad)

    def test_strict_transcript_rejects_summary_skip_wrongcase_and_extra_result(self):
        parser = self.tools.instrumentation_report.execution
        good = transcript(PROFILE.fixture_class, PROFILE.verify_method)
        self.assertEqual(parser(good, PROFILE.fixture_class, PROFILE.verify_method)['passed'], 1)
        for bad in ('OK (1 test)\n', transcript(PROFILE.fixture_class, PROFILE.verify_method, finish=-3),
                    transcript(PROFILE.fixture_class, PROFILE.verify_method, terminal=0),
                    good.replace(PROFILE.verify_method, PROFILE.seed_method), good + 'INSTRUMENTATION_CODE: -1\n'):
            with self.assertRaises(ValueError): parser(bad, PROFILE.fixture_class, PROFILE.verify_method)

    def test_device_subclass_changes_only_proof_root_and_uses_existing_io(self):
        device = self.engine.device(self.temp())
        old = self.tools.candidate84_upgrade.Device
        for name in ('command', 'select', 'install', 'state', 'instrument'):
            self.assertIs(getattr(type(device), name), getattr(old, name))
        calls = []
        # Instance-only inert recorder; no adb subprocess is invoked.
        device.command = lambda argv, name: (calls.append((argv, name)) or ('proof', b'', 0))
        self.assertEqual(device.proof('guard-proof'), 'proof')
        self.assertEqual(calls, [(['shell', 'run-as', PROFILE.package, 'cat',
            'files/lan-delivered84-upgrade-proof-v1/proof.json'], 'guard-proof')])

    def test_install_identity_exact_pair_and_uid_dir_time(self):
        old = {'versionCode': 84, 'uid': 12, 'firstInstallTime': 'unchanged', 'dataDir': '/synthetic'}
        new = dict(old, versionCode=85)
        self.engine.unchanged_install(old, new)
        for key, value in (('versionCode', 84), ('uid', 13), ('firstInstallTime', 'later'), ('dataDir', '/other')):
            bad = dict(new); bad[key] = value
            with self.assertRaises(ValueError): self.engine.unchanged_install(old, bad)

    def test_prepare_real_overlay_writes_only_companions_and_refuses_existing(self):
        root = self.temp()
        seed, candidate = root / 'seed', root / 'candidate'
        seed.mkdir(); candidate.mkdir()
        build = self.engine.build_request('a' * 40)
        def fake_git(directory, *argv):
            # Only HEAD/status are injected; actual path validation, exclusive
            # writes, hash rechecks and output JSON run on these scratch roots.
            if argv == ('rev-parse', 'HEAD'):
                return PROFILE.base['sourceSha'] if directory == seed else build['sourceSha']
            self.assertEqual(argv, ('status', '--porcelain', '--untracked-files=all'))
            return '?? ' + PROFILE.fixture_target if self.engine.overlay_path(directory).exists() else ''
        fake = SimpleNamespace(**vars(self.tools))
        fake.build_control = SimpleNamespace(git=fake_git)
        fake.candidate84_build = SimpleNamespace(cloud=lambda: None)
        engine = SeriesEngine(PROFILE, fake, HERE)
        engine.prepare(build, seed, candidate, root / 'raw')
        for role, directory in ((PROFILE.seed_role, seed), (PROFILE.verify_role, candidate)):
            paths = [p for p in directory.rglob('*') if p.is_file()]
            self.assertEqual(paths, [engine.overlay_path(directory)])
            self.assertEqual(engine.c.fixture_hash(paths[0]), build['fixtures'][role]['sha256'])
        with self.assertRaisesRegex(ValueError, 'SOURCE_NOT_EXCLUSIVE'):
            engine.prepare(build, seed, candidate, root / 'second-output')
        self.assertEqual(engine.load(root / 'raw/fixture-overlays.json')['productionFilesChanged'], [])

    def test_duplicate_json_keys_fail_closed(self):
        path = self.temp() / 'duplicate.json'
        path.write_bytes(b'{"format":1,"format":1}')
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_JSON_KEY'): self.engine.load(path)

    def test_raw_rows_exact_hash_role_and_source_with_small_synthetic_bytes(self):
        directory = self.temp()
        payload = b'synthetic raw, not an APK'
        digest = hashlib.sha256(payload).hexdigest()
        base = dict(PROFILE.base, rawBytes=len(payload), rawSha256=digest)
        profile = replace(PROFILE, base=MappingProxyType(base))
        engine = SeriesEngine(profile, self.tools, HERE)
        build = engine.build_request('a' * 40)
        rows = []
        for role in profile.roles:
            (directory / role.filename).write_bytes(payload)
            rows.append({'role': role.name, 'filename': role.filename, 'package': role.package,
                'versionCode': role.version or 1,
                'sourceSha': base['sourceSha'] if role.name in (profile.base_role, profile.seed_role) else build['sourceSha'],
                'testFixture': build['fixtures'].get(role.name), 'deliveredBase': base if role.name == profile.base_role else None,
                'bytes': len(payload), 'sha256': digest})
        manifest = {'kind': profile.kinds['raw'], 'sourceSha': build['sourceSha'], 'controlSha': 'b' * 40,
            'buildRequest': build, 'baseArtifact': engine.base_artifact(), 'inputs': rows}
        engine.c.write_new(directory / 'source-request.json', build)
        manifest_path = directory / 'raw-manifest.json'
        manifest_path.write_text(json.dumps(manifest), encoding='utf-8')
        engine.validate_raw(directory, build, 'b' * 40)
        for key, value in (('sourceSha', 'c' * 40), ('sha256', '0' * 64), ('filename', '../evil.apk')):
            bad = deepcopy(manifest); bad['inputs'][2][key] = value
            manifest_path.write_text(json.dumps(bad), encoding='utf-8')
            with self.assertRaises(ValueError): engine.validate_raw(directory, build, 'b' * 40)

    def test_native_entry_refuses_local_before_device_or_sdk(self):
        before = {key: os.environ.get(key) for key in ('GITHUB_ACTIONS', 'RUNNER_OS')}
        try:
            os.environ.pop('GITHUB_ACTIONS', None)
            with self.assertRaisesRegex(ValueError, 'CLOUD_LINUX_ONLY'): self.engine.native_control()
        finally:
            for key, value in before.items():
                if value is None: os.environ.pop(key, None)
                else: os.environ[key] = value


if __name__ == '__main__':
    unittest.main(verbosity=2)
