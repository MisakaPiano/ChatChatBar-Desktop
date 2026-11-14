"""Small synthetic metadata/ZIP/retention regressions; no JVM, SDK or native launch."""
import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import desktop_control as d
from contract import file_hash, load, write_new
from prove_desktop_lan3_lan4 import legacy, retained_entities

SOURCE = '1' * 40
CONTROL = '2' * 40


def bindings():
    return {'baseSourceSha': d.BASE['sourceSha'], 'candidateSourceSha': SOURCE,
            'files': [{'path': p, 'baseSha256': '3' * 64, 'candidateSha256': '3' * 64} for p in d.TIMESTAMP_PATHS]}


def bundle(root):
    request = d.build_request(SOURCE)
    (root / d.FILENAME).write_bytes(b'artificial archive bytes; never an application')
    archive = d.descriptor(root / d.FILENAME)
    values = {
        'source-request.json': request,
        'app-image-audit.json': {'identity': d.identity(request), 'fileCount': 1, 'files': [{'path': 'synthetic.txt'}]},
        d.FILENAME[:-4] + '.json': {'version': d.VERSION, 'commit': SOURCE, 'bytes': archive['bytes'], 'sha256': archive['sha256'], 'programFiles': 1},
        'native-result.json': {'kind': 'CI_INDEPENDENT_ZIP_ACTUAL_LAUNCHER', 'version': d.VERSION, 'source': SOURCE,
                               'programFilesVerified': 1, 'passed': True, 'ownDataAndLockCreated': True,
                               'normalCloseAndAllProgramProcessesExited': True, 'outsideBootstrapUnchanged': True},
    }
    for name, value in values.items(): write_new(root / name, value)
    manifest = {'kind': 'CCB_LAN4_INDEPENDENT_DESKTOP_ORIGINAL_V1', 'format': 1, 'sourceSha': SOURCE, 'controlSha': CONTROL,
                'buildRequest': request, 'timestampSourceBindings': bindings(), 'archive': archive,
                'files': [d.descriptor(root / name) for name in values], 'applicationImageBuiltFromFrozenSource': True,
                'oldDeliveredPackageRebuilt': False, 'profileUpgradeTested': False, 'humanAcceptance': False, 'userDelivered': False}
    write_new(root / 'desktop-package-manifest.json', manifest)
    run = {'id': 222, 'head_sha': CONTROL, 'conclusion': 'success'}
    artifact = {'id': 333, 'name': d.ARTIFACT_NAME, 'size_in_bytes': 444, 'digest': 'sha256:' + '4' * 64,
                'expired': False, 'workflow_run': {'id': 222, 'head_sha': CONTROL}}
    return run, artifact


class DesktopControlTest(unittest.TestCase):
    def test_frozen_lan3_matches_the_original_frozen_request(self):
        frozen = load(Path(__file__).resolve().parents[2] / '.github/lan-desktop-profile-upgrade-request.json')['packages'][1]
        self.assertEqual(frozen, {k: d.BASE[k] for k in frozen})
        self.assertEqual(d.BASE['artifactId'], 14002799032)

    def test_exact_lan4_build_identity_and_source_are_required(self):
        request = d.build_request(SOURCE); self.assertEqual(d.validate_build(request), request)
        for source in ('abc', 'A' * 40, d.BASE['sourceSha']):
            with self.assertRaises(ValueError): d.build_request(source)
        for key, value in [('version', '0.1.0-lan.3'), ('installerVersion', '0.1.40003'), ('buildNumber', '40005')]:
            changed = copy.deepcopy(request); changed[key] = value
            with self.assertRaises(ValueError): d.validate_build(changed)
        with tempfile.TemporaryDirectory(prefix='ccb-control-request-') as temporary:
            path = Path(temporary) / 'request.json'; write_new(path, request)
            with self.assertRaises(FileExistsError): write_new(path, request)

    def test_candidate_bundle_binds_source_audit_receipt_lifecycle_and_all_bytes(self):
        with tempfile.TemporaryDirectory(prefix='ccb-control-bundle-') as temporary:
            root = Path(temporary); bundle(root); d.validate_bundle(root)
            (root / d.FILENAME).write_bytes(b'changed candidate')
            with self.assertRaises(ValueError): d.validate_bundle(root)

    def test_upgrade_request_comes_from_actual_bundle_and_successful_metadata(self):
        with tempfile.TemporaryDirectory(prefix='ccb-control-metadata-') as temporary:
            root = Path(temporary); run, artifact = bundle(root)
            request = d.upgrade_request(root, artifact, run); d.validate_upgrade(request)
            self.assertEqual(request['packages'][0], d.BASE)
            for field, value in [('id', 334), ('digest', 'sha256:' + '5' * 64), ('size_in_bytes', 445), ('name', 'wrong')]:
                changed = copy.deepcopy(artifact); changed[field] = value
                with self.assertRaises(ValueError): d.metadata(request['packages'][1], changed, run)
            for field, value in [('id', 223), ('head_sha', '6' * 40), ('conclusion', 'failure')]:
                changed = dict(run); changed[field] = value
                with self.assertRaises(ValueError): d.upgrade_request(root, artifact, changed)

    def test_old_rebuild_substitution_migration_waiver_and_extra_fields_refuse(self):
        with tempfile.TemporaryDirectory(prefix='ccb-control-substitute-') as temporary:
            root = Path(temporary); run, artifact = bundle(root); request = d.upgrade_request(root, artifact, run)
            for mutate in (lambda r: r['packages'][0].update(innerSha256='7' * 64),
                           lambda r: r['packages'][1].update(sourceSha=d.BASE['sourceSha']),
                           lambda r: r.update(allowedStateMigrations=['*']),
                           lambda r: r.update(extra='not part of the contract')):
                changed = copy.deepcopy(request); mutate(changed)
                with self.assertRaises(ValueError): d.validate_upgrade(changed)

    def test_missing_or_failed_native_lifecycle_cannot_become_an_original(self):
        with tempfile.TemporaryDirectory(prefix='ccb-control-native-result-') as temporary:
            root = Path(temporary); run, artifact = bundle(root)
            native = load(root / 'native-result.json'); native['passed'] = False
            (root / 'native-result.json').write_text(json.dumps(native), encoding='utf8')
            manifest = load(root / 'desktop-package-manifest.json')
            manifest['files'][-1] = d.descriptor(root / 'native-result.json')
            (root / 'desktop-package-manifest.json').write_text(json.dumps(manifest), encoding='utf8')
            with self.assertRaises(ValueError): d.upgrade_request(root, artifact, run)

    def test_actual_outer_and_inner_bytes_and_zip_paths_are_checked(self):
        with tempfile.TemporaryDirectory(prefix='ccb-control-zip-') as temporary:
            root = Path(temporary); archive = root / 'original.zip'; inner = b'artificial original'
            with zipfile.ZipFile(archive, 'x') as z: z.writestr(d.FILENAME, inner)
            import hashlib
            row = {'filename': d.FILENAME, 'series': 'lan4', 'outerBytes': archive.stat().st_size,
                   'outerSha256': file_hash(archive), 'innerBytes': len(inner), 'innerSha256': hashlib.sha256(inner).hexdigest()}
            d.verify_outer_and_extract(archive, root / 'valid', row)
            changed = dict(row, outerSha256='8' * 64)
            with self.assertRaises(ValueError): d.verify_outer_and_extract(archive, root / 'bad', changed)
            escaping = root / 'escape.zip'
            with zipfile.ZipFile(escaping, 'x') as z: z.writestr('../escaped.txt', inner)
            changed = dict(row, outerBytes=escaping.stat().st_size, outerSha256=file_hash(escaping))
            with self.assertRaises(ValueError): d.verify_outer_and_extract(escaping, root / 'unsafe', changed)
            self.assertFalse((root / 'escaped.txt').exists())

    def test_original_nested_inner_is_verified_without_flattening(self):
        with tempfile.TemporaryDirectory(prefix='ccb-control-nested-') as temporary:
            root = Path(temporary); archive = root / 'nested.zip'; filename = d.BASE['filename']; inner = b'artificial old original'
            with zipfile.ZipFile(archive, 'x') as z: z.writestr('output/' + filename, inner)
            import hashlib
            row = {'filename': filename, 'series': 'lan3', 'outerBytes': archive.stat().st_size,
                   'outerSha256': file_hash(archive), 'innerBytes': len(inner), 'innerSha256': hashlib.sha256(inner).hexdigest()}
            destination = root / 'nested-input'
            result = d.verify_outer_and_extract(archive, destination, row)
            self.assertEqual(result['innerRelativePath'], 'output/' + filename)
            self.assertEqual((destination / 'output' / filename).read_bytes(), inner)
            self.assertFalse((destination / filename).exists())
            with self.assertRaisesRegex(ValueError, 'EXACT_ORIGINAL_INNER_BYTES'):
                d.verify_outer_and_extract(archive, root / 'wrong-inner-hash', dict(row, innerSha256='0' * 64))

    def test_two_nested_matching_original_filenames_are_ambiguous(self):
        with tempfile.TemporaryDirectory(prefix='ccb-control-ambiguous-') as temporary:
            root = Path(temporary); archive = root / 'ambiguous.zip'; filename = d.BASE['filename']; inner = b'artificial old original'
            with zipfile.ZipFile(archive, 'x') as z:
                z.writestr('one/' + filename, inner); z.writestr('two/' + filename, inner)
            import hashlib
            row = {'filename': filename, 'series': 'lan3', 'outerBytes': archive.stat().st_size,
                   'outerSha256': file_hash(archive), 'innerBytes': len(inner), 'innerSha256': hashlib.sha256(inner).hexdigest()}
            with self.assertRaisesRegex(ValueError, 'SINGLE_ORIGINAL_INNER_REQUIRED'):
                d.verify_outer_and_extract(archive, root / 'ambiguous-input', row)

    def test_timestamp_exception_needs_identical_sources_and_observed_old_typed_change(self):
        old = legacy()
        with tempfile.TemporaryDirectory(prefix='ccb-control-timestamp-') as temporary:
            root = Path(temporary); run, artifact = bundle(root); request = d.upgrade_request(root, artifact, run)
            directory = root / 'entities'; path = 'chat_scroll_positions/session.json'; file = directory / path
            file.parent.mkdir(parents=True); previous = {'sessionId': 'session', 'anchorMessageId': 'm', 'fallbackMessageIndex': 0, 'scrollOffset': 0, 'capturedAt': 1}
            file.write_text(json.dumps(previous), encoding='utf8'); before = {path: file_hash(file)}
            current = dict(previous, capturedAt=2); file.write_text(json.dumps(current), encoding='utf8'); after = {path: file_hash(file)}
            control = [{'path': path, 'changedTopLevelFields': ['capturedAt']}]
            self.assertEqual(len(retained_entities(old, request, before, after, {path: previous}, directory, control)['timestampOnlyChanges']), 1)
            for value in (dict(current, scrollOffset=1), dict(current, capturedAt=True)):
                file.write_text(json.dumps(value), encoding='utf8')
                with self.assertRaises(RuntimeError): retained_entities(old, request, before, {path: file_hash(file)}, {path: previous}, directory, control)
            file.write_text(json.dumps(current), encoding='utf8')
            with self.assertRaises(RuntimeError): retained_entities(old, request, before, after, {path: previous}, directory, [])
            request['timestampSourceBindings']['files'][0]['candidateSha256'] = '9' * 64
            with self.assertRaises(ValueError): retained_entities(old, request, before, after, {path: previous}, directory, control)

    def test_cloud_execution_guard_refuses_local_native_or_download_work(self):
        with patch.dict(os.environ, {'GITHUB_ACTIONS': 'false'}, clear=False):
            with self.assertRaises(ValueError): d.cloud()

    def test_other_preexisting_entity_changes_or_deletions_never_get_a_timestamp_waiver(self):
        old = legacy()
        with tempfile.TemporaryDirectory(prefix='ccb-control-no-waiver-') as temporary:
            root = Path(temporary); run, artifact = bundle(root); request = d.upgrade_request(root, artifact, run)
            path = 'character_cards/card.json'; file = root / path; file.parent.mkdir()
            previous = {'name': 'synthetic retained text'}; file.write_text(json.dumps(previous), encoding='utf8')
            before = {path: file_hash(file)}; file.write_text(json.dumps({'name': 'changed text'}), encoding='utf8')
            with self.assertRaises(RuntimeError): retained_entities(old, request, before, {path: file_hash(file)}, {path: previous}, root, [])
            with self.assertRaises(RuntimeError): retained_entities(old, request, before, {}, {path: previous}, root, [])


if __name__ == '__main__': unittest.main()
