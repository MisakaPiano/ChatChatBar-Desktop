"""Small synthetic checks for false upgrade proofs and request substitution."""
import copy
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
from contract import (BASE, CERTIFICATE, FIXTURE_CLASS, HERE, ROLES, SIGN_KIND, artifact_metadata,
                      build_request, fixture_hash, validate_build, validate_public_block, validate_sign, write_new)
from prove_upgrade import execution, package_state, unchanged_install
from sign_control import validate_raw
import provenance

SOURCE = 'b' * 40


def block(raw='c' * 64):
    return {'format': 1, 'purpose': 'ISOLATED_LAN_NEXT_PREVIEW', 'sourceSha256': raw,
            'outputSha256': 'd' * 64, 'bytes': 1234, 'entries': 3,
            'certificateSha256': CERTIFICATE, 'blockBase64': 'A' * 64,
            'scheme': 'v2', 'algorithm': 'RSA-2048-PKCS1-SHA256', 'officialVerifierPassed': False}


def sign_request():
    entries = {role: {'filename': row[0], 'publicSigning': block()} for role, row in ROLES.items()}
    entries['base82']['publicSigning'].update(sourceSha256=BASE['rawSha256'],
        outputSha256=BASE['deliveredSha256'], bytes=BASE['deliveredBytes'])
    return {'kind': SIGN_KIND, 'format': 1, 'buildRequest': build_request(SOURCE),
            'originalArtifact': {'id': 123, 'runId': 456, 'name': 'ccb-lan-delivered82-candidate83-inputs',
                'bytes': 789, 'outerSha256': 'e' * 64, 'controlSha': 'f' * 40}, 'entries': entries}


def package(version=82, uid=10123, first='2026-11-13 12:34:56', data='/data/user/0/com.example.chatbar.lansyncnext'):
    return f'  userId={uid}\n  versionCode={version} minSdk=26 targetSdk=36\n  firstInstallTime={first}\n  dataDir={data}\n'


def instrument(status='0', method='seedSyntheticRepositoryAndProtectedPeerIdentity'):
    return f'INSTRUMENTATION_STATUS: class={FIXTURE_CLASS}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: {status}\nOK (1 test)\n'


class ControlTests(unittest.TestCase):
    def test_windows_and_linux_fixture_hashes_agree(self):
        with TemporaryDirectory(dir=HERE, prefix='control-test-') as temp:
            windows = Path(temp) / 'windows.kt'; linux = Path(temp) / 'linux.kt'
            windows.write_bytes(b'class Example {\r\n}\r\n'); linux.write_bytes(b'class Example {\n}\n')
            self.assertEqual(fixture_hash(windows), fixture_hash(linux))

    def test_valid_build_and_four_public_inputs(self):
        validate_build(build_request(SOURCE)); validate_sign(sign_request())

    def test_cannot_relabel_old_source_as_candidate(self):
        with self.assertRaises(ValueError): build_request(BASE['sourceSha'])

    def test_delivered_base_and_fixture_substitution_refuse(self):
        for field in ('deliveredBase', 'fixtures'):
            request = build_request(SOURCE)
            if field == 'deliveredBase': request[field]['deliveredSha256'] = '0' * 64
            else: request[field]['seed82']['sha256'] = '0' * 64
            with self.assertRaises(ValueError): validate_build(request)

    def test_no_blanket_state_migration_waiver(self):
        request = build_request(SOURCE); request['allowedStateMigrations'] = ['*']
        with self.assertRaises(ValueError): validate_build(request)

    def test_missing_fourth_input_and_role_swap_refuse(self):
        request = sign_request(); del request['entries']['seed82']
        with self.assertRaises(ValueError): validate_sign(request)
        request = sign_request(); request['entries']['verify83']['filename'] = 'seed82-tests-input.apk'
        with self.assertRaises(ValueError): validate_sign(request)

    def test_changed_base_or_signer_refuse(self):
        for role, field in [('base82', 'outputSha256'), ('candidate83', 'certificateSha256')]:
            request = sign_request(); request['entries'][role]['publicSigning'][field] = '0' * 64
            with self.assertRaises(ValueError): validate_sign(request)

    def test_private_fields_cannot_enter_public_request(self):
        value = block(); value['privateDerHex'] = 'synthetic-forbidden-field'
        with self.assertRaises(ValueError): validate_public_block(value, value['sourceSha256'])

    def test_artifact_rejects_bool_id_and_unbound_name(self):
        for field, replacement in [('id', True), ('name', 'unrelated-artifact')]:
            value = sign_request()['originalArtifact']; value[field] = replacement
            with self.assertRaises(ValueError): artifact_metadata(value)

    def test_actual_method_pass_required_not_assumption_ok(self):
        method = 'seedSyntheticRepositoryAndProtectedPeerIdentity'
        self.assertEqual(execution(instrument(), method)['executed'], 1)
        for status in ('-3', '-4'):
            with self.assertRaises(ValueError): execution(instrument(status), method)
        with self.assertRaises(ValueError): execution(instrument(method='otherMethod'), method)

    def test_uid_first_install_and_data_dir_must_all_survive(self):
        before = package_state(package())
        unchanged_install(before, package_state(package(83)))
        for changed in (package(83, uid=10124), package(83, first='2026-11-14 00:00:00'), package(83, data='/other')):
            with self.assertRaises(ValueError): unchanged_install(before, package_state(changed))

    def test_package_parser_rejects_missing_or_ambiguous_fields(self):
        with self.assertRaises(ValueError): package_state(package().replace('  userId=10123\n', ''))
        with self.assertRaises(ValueError): package_state(package() + '  userId=999\n')

    def test_failed_expired_or_wrong_control_artifact_refuses(self):
        expected = sign_request()['originalArtifact']
        run = {'head_sha': expected['controlSha'], 'conclusion': 'success'}
        artifact = {'id': expected['id'], 'name': expected['name'], 'expired': False,
            'workflow_run': {'id': expected['runId'], 'head_sha': expected['controlSha']},
            'size_in_bytes': expected['bytes'], 'digest': 'sha256:' + expected['outerSha256']}
        with patch.object(provenance, 'get', side_effect=[run, artifact]): provenance.check(expected)
        for change in ('failed', 'expired', 'control'):
            bad_run = dict(run); bad_artifact = copy.deepcopy(artifact)
            if change == 'failed': bad_run['conclusion'] = 'failure'
            elif change == 'expired': bad_artifact['expired'] = True
            else: bad_artifact['workflow_run']['head_sha'] = '0' * 40
            with patch.object(provenance, 'get', side_effect=[bad_run, bad_artifact]):
                with self.assertRaises(ValueError): provenance.check(expected)

    def test_four_raw_sources_and_actual_small_file_tamper(self):
        # Synthetic metadata/path validation only; this is not APK/SDK proof.
        payloads = {role: ('synthetic-' + role).encode() for role in ROLES}
        raw_base_hash = hashlib.sha256(payloads['base82']).hexdigest()
        with patch.dict(BASE, {'rawSha256': raw_base_hash}), TemporaryDirectory(dir=HERE, prefix='control-test-') as temp:
            directory = Path(temp); build = build_request(SOURCE); rows = []
            for role, (filename, package_name, version) in ROLES.items():
                (directory / filename).write_bytes(payloads[role])
                rows.append({'role': role, 'filename': filename, 'package': package_name, 'versionCode': version,
                    'sourceSha': BASE['sourceSha'] if role in ('base82', 'seed82') else SOURCE,
                    'testFixture': build['fixtures'].get(role), 'deliveredBase': dict(BASE) if role == 'base82' else None,
                    'bytes': len(payloads[role]), 'sha256': hashlib.sha256(payloads[role]).hexdigest()})
            write_new(directory / 'source-request.json', build)
            manifest = {'buildRequest': build, 'sourceSha': SOURCE, 'controlSha': 'f' * 40, 'inputs': rows}
            write_new(directory / 'raw-manifest.json', manifest)
            validate_raw(directory, build, 'f' * 40)
            manifest['inputs'][1]['sourceSha'] = SOURCE
            (directory / 'raw-manifest.json').write_text(json.dumps(manifest), encoding='utf8')
            with self.assertRaises(ValueError): validate_raw(directory, build, 'f' * 40)
            manifest['inputs'][1]['sourceSha'] = BASE['sourceSha']
            (directory / 'raw-manifest.json').write_text(json.dumps(manifest), encoding='utf8')
            (directory / ROLES['candidate83'][0]).write_bytes(b'changed executable placeholder')
            with self.assertRaises(ValueError): validate_raw(directory, build, 'f' * 40)


if __name__ == '__main__':
    unittest.main(verbosity=2)
