"""Synthetic Python control regressions, not Android/SDK upgrade evidence."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
import warnings
import zipfile
import contract
import fixture_only_control as control
from contract import (BASE, FIXTURE_TARGET, HERE, ORIGINAL_INPUT_BYTES, REUSED_ORIGINAL_ARTIFACT,
                      REUSED_ROLES, ROLES, VERIFIER_ONLY_SOURCE, build_request, file_hash,
                      fixture_bytes, load, validate_build, validate_sign, verifier_only_request, write_new)


def digest(value):
    return hashlib.sha256(value).hexdigest()


class VerifierOnlyTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.payloads = {role: ('synthetic-original-' + role).encode() for role in ROLES}
        self.base_patch = patch.dict(BASE, {'rawSha256': digest(self.payloads['base82'])})
        self.base_patch.start(); self.addCleanup(self.base_patch.stop)
        self.inputs_patch = patch.dict(ORIGINAL_INPUT_BYTES,
            {role: (len(value), digest(value)) for role, value in self.payloads.items()})
        self.inputs_patch.start(); self.addCleanup(self.inputs_patch.stop)

    def original(self):
        directory = self.root / 'original'; directory.mkdir()
        request = build_request(VERIFIER_ONLY_SOURCE)
        for role, value in self.payloads.items():
            (directory / ROLES[role][0]).write_bytes(value)
        write_new(directory / 'source-request.json', request)
        write_new(directory / 'raw-manifest.json', self.manifest(request, list(control.original_rows().values())))
        return directory

    def manifest(self, request, rows):
        return {'kind': 'CCB_LAN_82_TO_83_FOUR_RAW_INPUTS', 'sourceSha': VERIFIER_ONLY_SOURCE,
            'controlSha': REUSED_ORIGINAL_ARTIFACT['controlSha'], 'buildRequest': request, 'inputs': rows,
            'persistentSigningApplied': False, 'deviceUpgradeTested': False,
            'humanAcceptance': False, 'userDelivered': False}

    def r02(self):
        directory = self.root / 'r02'; directory.mkdir()
        request = verifier_only_request(); rows = control.original_rows()
        for role, value in self.payloads.items():
            if role == 'verify83':
                value = b'synthetic-new-verifier'
                rows[role].update(testFixture=request['fixtures'][role], bytes=len(value), sha256=digest(value))
            (directory / ROLES[role][0]).write_bytes(value)
        manifest = self.manifest(request, list(rows.values())); manifest['reuse'] = control.reuse_ledger()
        write_new(directory / 'source-request.json', request)
        write_new(directory / 'raw-manifest.json', manifest)
        return directory, request, manifest

    def save(self, directory, manifest):
        (directory / 'raw-manifest.json').write_text(json.dumps(manifest), encoding='utf8')

    def test_v1_actual_frozen_requests_still_validate(self):
        # Restore real base constant for these unchanged published requests.
        with patch.dict(BASE, {'rawSha256': '7d65f385078626788215f3230ade235269a4993eb5159bc8e10536c0568ec844'}):
            root = HERE.parent.parent
            validate_build(load(root / '.github/lan-next-sync-control-build-request.json'))
            validate_sign(load(root / '.github/lan-next-sync-control-sign-request.json'))

    def test_v2_pins_source_original_artifact_and_precise_fixture(self):
        request = verifier_only_request(); validate_build(request)
        self.assertEqual(request['reusedOriginalArtifact'], REUSED_ORIGINAL_ARTIFACT)
        self.assertEqual(request['fixtures']['seed82'], build_request(VERIFIER_ONLY_SOURCE)['fixtures']['seed82'])
        self.assertEqual(request['fixtures']['verify83']['path'], 'fixtures/verify83-r02.kt')
        for mutation in ('source', 'artifact', 'fixture', 'migration', 'extra'):
            bad = deepcopy(request)
            if mutation == 'source': bad['sourceSha'] = 'a' * 40
            elif mutation == 'artifact': bad['reusedOriginalArtifact']['id'] += 1
            elif mutation == 'fixture': bad['fixtures']['verify83'] = build_request(VERIFIER_ONLY_SOURCE)['fixtures']['verify83']
            elif mutation == 'migration': bad['allowedStateMigrations'] = ['capture-scratch/*']
            else: bad['allowAnyScratch'] = True
            with self.subTest(mutation=mutation), self.assertRaises(ValueError): validate_build(bad)

    def test_original_four_actual_bytes_and_sources_are_bound(self):
        directory = self.original(); control.validate_original(directory)
        manifest = load(directory / 'raw-manifest.json')
        for role in ROLES:
            bad = deepcopy(manifest)
            next(row for row in bad['inputs'] if row['role'] == role)['sourceSha'] = 'a' * 40
            self.save(directory, bad)
            with self.subTest(role=role), self.assertRaises(ValueError): control.validate_original(directory)
        self.save(directory, manifest)
        (directory / ROLES['seed82'][0]).write_bytes(b'changed-original')
        with self.assertRaises(ValueError): control.validate_original(directory)

    def test_original_self_consistent_replacement_is_not_accepted(self):
        directory = self.original(); manifest = load(directory / 'raw-manifest.json')
        replacement = b'rebuilt-candidate83'
        (directory / ROLES['candidate83'][0]).write_bytes(replacement)
        next(row for row in manifest['inputs'] if row['role'] == 'candidate83').update(
            bytes=len(replacement), sha256=digest(replacement))
        self.save(directory, manifest)
        with self.assertRaisesRegex(ValueError, 'ORIGINAL_FOUR_INPUTS_CHANGED'): control.validate_original(directory)

    def test_r02_exact_three_reused_rows_validate(self):
        directory, request, manifest = self.r02()
        _, rows = control.validate_r02_raw(directory, request, REUSED_ORIGINAL_ARTIFACT['controlSha'])
        for role in REUSED_ROLES:
            self.assertEqual(rows[role], control.original_rows()[role])
            self.assertEqual((directory / ROLES[role][0]).read_bytes(), self.payloads[role])
        self.assertNotEqual(rows['verify83']['sha256'], ORIGINAL_INPUT_BYTES['verify83'][1])

    def test_r02_self_consistent_rebuilt_seed_and_reuse_ledger_tamper_refuse(self):
        directory, request, manifest = self.r02()
        replacement = b'rebuilt-seed82'
        (directory / ROLES['seed82'][0]).write_bytes(replacement)
        next(row for row in manifest['inputs'] if row['role'] == 'seed82').update(
            bytes=len(replacement), sha256=digest(replacement))
        self.save(directory, manifest)
        with self.assertRaisesRegex(ValueError, 'REUSED_INPUT_CHANGED_seed82'):
            control.validate_r02_raw(directory, request)
        (directory / ROLES['seed82'][0]).write_bytes(self.payloads['seed82'])
        manifest['inputs'] = [dict(row) for row in control.original_rows().values()]
        new = b'synthetic-new-verifier'
        manifest['inputs'][-1].update(testFixture=request['fixtures']['verify83'], bytes=len(new), sha256=digest(new))
        manifest['reuse']['artifact']['controlSha'] = 'f' * 40
        self.save(directory, manifest)
        with self.assertRaisesRegex(ValueError, 'REUSE_LEDGER'): control.validate_r02_raw(directory, request)

    def test_r02_new_verifier_bytes_still_must_match_manifest(self):
        directory, request, _ = self.r02()
        (directory / ROLES['verify83'][0]).write_bytes(b'tampered-new-verifier')
        with self.assertRaisesRegex(ValueError, 'RAW_BYTES_HASH'): control.validate_r02_raw(directory, request)

    def test_collect_copies_three_originals_and_only_new_verifier(self):
        original = self.original(); request = verifier_only_request()
        candidate = self.root / 'source'; candidate.mkdir()
        fixture = candidate / FIXTURE_TARGET; fixture.parent.mkdir(parents=True)
        fixture.write_bytes(fixture_bytes(HERE / request['fixtures']['verify83']['path']))
        built = candidate / 'app/app/build/outputs/apk/androidTest/lanPreview/new-verifier.apk'
        built.parent.mkdir(parents=True); built.write_bytes(b'only-new-verifier-build')
        output = self.root / 'collected'; output.mkdir(); write_new(output / 'source-request.json', request)
        def git(_directory, *arguments):
            return VERIFIER_ONLY_SOURCE if arguments[0] == 'rev-parse' else '?? ' + FIXTURE_TARGET
        # No SDK is run; only its independent APK metadata adapter is substituted.
        with patch.object(control, 'git', side_effect=git), patch.object(control, 'inspect_apk', side_effect=lambda p, pkg, v, t, r: v):
            control.collect(request, candidate, original, output, 'f' * 40, self.root / 'unused-sdk')
        manifest, rows = control.validate_r02_raw(output, request, 'f' * 40)
        for role in REUSED_ROLES:
            self.assertEqual((output / ROLES[role][0]).read_bytes(), self.payloads[role])
        self.assertEqual((output / ROLES['verify83'][0]).read_bytes(), built.read_bytes())
        self.assertEqual(manifest['reuse'], control.reuse_ledger())
        self.assertEqual(rows['verify83']['sourceSha'], VERIFIER_ONLY_SOURCE)

    def test_prepare_rejects_wrong_or_dirty_source_before_overlay(self):
        candidate = self.root / 'source'; candidate.mkdir()
        for values in (['a' * 40], [VERIFIER_ONLY_SOURCE, ' M app/build.gradle.kts']):
            with patch.object(control, 'git', side_effect=values), self.assertRaises(ValueError):
                control.prepare(verifier_only_request(), candidate, self.root / 'unused-output')
            self.assertFalse((candidate / FIXTURE_TARGET).exists())

    def test_authenticated_archive_extracts_actual_originals(self):
        original = self.original(); archive = self.root / 'original.zip'
        with zipfile.ZipFile(archive, 'w') as bundle:
            for path in original.iterdir(): bundle.write(path, path.name)
        with patch.dict(REUSED_ORIGINAL_ARTIFACT, {'bytes': archive.stat().st_size, 'outerSha256': file_hash(archive)}):
            output = self.root / 'extracted'; control.extract_original(archive, output)
            for role in ROLES: self.assertEqual((output / ROLES[role][0]).read_bytes(), self.payloads[role])
            archive.write_bytes(archive.read_bytes()[:-1] + b'X')
            with self.assertRaisesRegex(ValueError, 'ORIGINAL_ARCHIVE_DIGEST'):
                control.extract_original(archive, self.root / 'tampered')

    def test_archive_paths_and_duplicates_refuse_before_extraction(self):
        for index, names in enumerate((['../escape'], ['duplicate', 'duplicate'], ['x\\escape'], ['/absolute'])):
            archive = self.root / ('bad' + str(index) + '.zip')
            with warnings.catch_warnings(), zipfile.ZipFile(archive, 'w') as bundle:
                warnings.simplefilter('ignore', UserWarning)
                for name in names:
                    # Windows ZipInfo normally normalizes backslashes: retain
                    # hostile wire names to exercise the actual extractor guard.
                    entry = zipfile.ZipInfo('placeholder')
                    entry.filename = entry.orig_filename = name
                    bundle.writestr(entry, b'synthetic')
            output = self.root / ('bad-output' + str(index))
            with patch.dict(REUSED_ORIGINAL_ARTIFACT, {'bytes': archive.stat().st_size, 'outerSha256': file_hash(archive)}):
                with self.assertRaises(ValueError): control.extract_original(archive, output)
                self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
