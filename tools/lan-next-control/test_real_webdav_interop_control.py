"""Pure Python/FS control guards; never invoke bash, rclone, Gradle or SDK."""
from copy import deepcopy
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET

from real_webdav_interop_control import (RealWebDavControl, exact_xml, CLASS, METHOD, SOURCE, TARGET, XML,
    FIXTURE, SCRIPT, PROCESS_OWNER, FORMAT, VERSION, ARCHIVE, ARCHIVE_SHA, URL, ARCHIVE_FILE, METHOD_FILE)

HERE = Path(__file__).resolve().parent
CONTROL = Path(os.environ.get('CCB_EXISTING_CONTROL_ROOT', str(HERE.parents[1])))


def xml(**fields):
    values = dict(tests='1', failures='0', errors='0', skipped='0', suite=CLASS, name=METHOD, classname=CLASS,
                  children='', casesuffix='', time='0.125')
    values.update(fields)
    return ('<testsuite name="{suite}" tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}">'
            '<testcase name="{name}" classname="{classname}" time="{time}">{children}</testcase>{casesuffix}'
            '<system-out>not an acceptance marker</system-out></testsuite>').format(**values).encode('utf-8')


class FixtureControl(RealWebDavControl):
    """Only Git/cloud metadata boundaries are injected; actual FS logic runs."""
    head = SOURCE
    extra_status = ''
    tracked_target = ''
    def cloud(self): pass
    def control_binding(self): return {'controlSha': 'a' * 40, 'filesSha256': {'synthetic-boundary': 'b' * 64}}
    def git(self, root, *args):
        if args == ('rev-parse', 'HEAD'): return self.head
        if args == ('cat-file', '-t', SOURCE): return 'commit'
        if args == ('status', '--porcelain', '--untracked-files=all'):
            return ('?? ' + TARGET if (root / TARGET).exists() else '') + self.extra_status
        if args == ('ls-tree', '--full-name', SOURCE, '--', TARGET): return self.tracked_target
        raise AssertionError('Unexpected synthetic Git query')


class RealWebDavControlTest(unittest.TestCase):
    def setUp(self):
        self.control = RealWebDavControl(CONTROL, HERE)

    def temp(self):
        value = tempfile.TemporaryDirectory(prefix='interop-guard-', dir=HERE.parents[1])
        self.addCleanup(value.cleanup)
        path = Path(value.name).resolve()
        if os.name == 'nt':
            # This authorized scratch prefix plus a real Kotlin package path
            # exceeds legacy MAX_PATH. Keep the same owned directory and use
            # Win32 extended paths only for these local pure-FS guard fixtures.
            path = Path('\\\\?\\' + str(path))
            self.addCleanup(lambda: shutil.rmtree(path) if path.exists() else None)
        return path

    def synthetic_roots(self):
        root = self.temp(); source = root / 'source'; source.mkdir()
        before = os.environ.get('RUNNER_TEMP')
        os.environ['RUNNER_TEMP'] = str(root)
        def restore():
            if before is None: os.environ.pop('RUNNER_TEMP', None)
            else: os.environ['RUNNER_TEMP'] = before
        self.addCleanup(restore)
        return source, root / 'evidence'

    def test_fixed_request_rejects_wrong_source_tool_url_and_unknown_fields(self):
        good = self.control.expected_request()
        self.assertEqual(self.control.validate_request(good), good)
        for key, value in (('sourceSha', '0' * 40), ('fixtureSha256', '0' * 64), ('format', True), ('format', 1), ('format', 2),
                           ('endpoint', 'https://example.invalid/'), ('rcloneVersion', 'latest'), ('class', 'other')):
            bad = dict(good); bad[key] = value
            with self.assertRaises(ValueError): self.control.validate_request(bad)

    def test_format3_chat_variant_and_process_owner_are_bound(self):
        self.assertEqual(FORMAT, 3)
        self.assertEqual(self.control.expected_request()['format'], 3)
        self.assertEqual(self.control.expected_request()['fixtureSha256'],
                         'f8203723c9e33f888c6ffdea68b40eb1b80a81d8d06044073e58d02b82214b6a')
        home = self.temp()
        for name in ('real_webdav_interop_control.py', SCRIPT, FIXTURE, PROCESS_OWNER):
            target = home / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((HERE / name).read_bytes())
        control = RealWebDavControl(CONTROL, home)
        def synthetic_git(root, *args):
            if args == ('rev-parse', 'HEAD'): return 'a' * 40
            if args == ('status', '--porcelain', '--untracked-files=all'): return ''
            raise AssertionError('unexpected Git boundary')
        with mock.patch.object(control, 'git', side_effect=synthetic_git), \
             mock.patch.dict(os.environ, {'GITHUB_SHA': 'a' * 40, 'GITHUB_WORKSPACE': str(CONTROL.resolve())}):
            before = control.control_binding()
            self.assertEqual(before['filesSha256'][PROCESS_OWNER], control.common.file_hash(home / PROCESS_OWNER))
            (home / PROCESS_OWNER).write_bytes(b'synthetic changed module')
            self.assertNotEqual(before, control.control_binding())

    def test_shell_delegates_server_and_gradle_without_jobs_or_unbounded_wait(self):
        body = (HERE / SCRIPT).read_text(encoding='utf-8')
        self.assertNotIn('jobs -', body)
        self.assertNotIn('trap ', body)
        self.assertNotIn('wait "$server_pid"', body)
        self.assertNotIn('serve webdav', body)
        self.assertNotIn('bash ./gradlew', body)
        call = body.index('python3 "$control_dir/real_webdav_process_owner.py"')
        self.assertLess(body.rindex('test ! -e "$source_dir/$report"'), call)
        self.assertIn('check_source', body[call:])

    def test_actual_modified_fixture_hash_rejected_without_touching_frozen_fixture(self):
        home = self.temp(); (home / 'fixtures').mkdir()
        (home / FIXTURE).write_bytes((HERE / FIXTURE).read_bytes())
        clone = RealWebDavControl(CONTROL, home)
        request = clone.expected_request()
        (home / FIXTURE).write_bytes(b'synthetic changed fixture')
        with self.assertRaisesRegex(ValueError, 'CONTROL_FIXTURE_SHA'): clone.validate_request(request)

    def test_local_native_guard_fails_before_process_or_download(self):
        before = os.environ.get('GITHUB_ACTIONS')
        os.environ.pop('GITHUB_ACTIONS', None)
        try:
            with self.assertRaisesRegex(ValueError, 'CLOUD_LINUX_X64_ONLY'): self.control.cloud()
        finally:
            if before is not None: os.environ['GITHUB_ACTIONS'] = before

    def test_request_is_validated_before_cloud_gate(self):
        control = FixtureControl(CONTROL, HERE)
        def denied(): raise AssertionError('Gate must not be called for invalid input')
        control.cloud = denied
        bad = self.control.expected_request(); bad['profile'] = 'other'
        with self.assertRaisesRegex(ValueError, 'REQUEST_FIELDS'): control.prepare(bad, None, None)

    def test_actual_single_overlay_and_metadata_without_production_writes(self):
        source, output = self.synthetic_roots(); control = FixtureControl(CONTROL, HERE)
        request = control.expected_request(); control.prepare(request, source, output)
        self.assertEqual([p.relative_to(source).as_posix() for p in source.rglob('*') if p.is_file()], [TARGET])
        self.assertEqual(control.common.fixture_hash(source / TARGET), request['fixtureSha256'])
        self.assertEqual(json.loads((output / 'prepared.json').read_text())['productionFilesChanged'], [])
        with self.assertRaises(ValueError): control.prepare(request, source, source.parent / 'second-output')

    def test_wrong_source_and_dirty_or_tracked_target_refused_before_overlay(self):
        for mode in ('head', 'dirty', 'tracked'):
            source, output = self.synthetic_roots(); control = FixtureControl(CONTROL, HERE)
            if mode == 'head': control.head = '0' * 40
            if mode == 'dirty': control.extra_status = ' M production.kt'
            if mode == 'tracked': control.tracked_target = '100644 blob ' + 'a' * 40 + '\t' + TARGET
            with self.assertRaises(ValueError): control.prepare(control.expected_request(), source, output)
            self.assertFalse((source / TARGET).exists()); self.assertFalse(output.exists())

    def test_preexisting_original_xml_refused_never_deleted(self):
        source, output = self.synthetic_roots(); path = source / XML
        path.parent.mkdir(parents=True); path.write_bytes(b'old original XML evidence')
        control = FixtureControl(CONTROL, HERE)
        with self.assertRaisesRegex(ValueError, 'PREEXISTING_JUNIT_EVIDENCE'):
            control.prepare(control.expected_request(), source, output)
        self.assertEqual(path.read_bytes(), b'old original XML evidence')

    def test_existing_overlay_is_not_overwritten(self):
        source, output = self.synthetic_roots(); target = source / TARGET
        target.parent.mkdir(parents=True); target.write_bytes(b'original synthetic file')
        control = FixtureControl(CONTROL, HERE)
        with self.assertRaisesRegex(ValueError, 'SOURCE_NOT_EXCLUSIVE'):
            control.prepare(control.expected_request(), source, output)
        self.assertEqual(target.read_bytes(), b'original synthetic file')

    def test_exact_junit_one_method_pass_required(self):
        result = exact_xml(xml())
        self.assertEqual((result['tests'], result['passed'], result['failures'], result['errors'], result['skipped']), (1, 1, 0, 0, 0))
        for bad in (b'OK 1 PASS', xml(tests='0'), xml(tests='2'), xml(failures='1'), xml(errors='1'), xml(skipped='1'),
                    xml(name='another'), xml(classname='another'), xml(suite='another')):
            with self.assertRaises((ValueError, ET.ParseError)): exact_xml(bad)

    def test_xml_hidden_skip_failure_duplicate_case_and_invalid_time_rejected(self):
        for bad in (xml(children='<skipped/>'), xml(children='<failure/>'), xml(children='<error/>'),
                    xml(casesuffix='<testcase name="other"/>'), xml(time='NaN'), xml(time='-1'),
                    b'<!DOCTYPE testsuite><testsuite/>'):
            with self.assertRaises(ValueError): exact_xml(bad)

    def test_junit_case_must_be_direct_and_not_hidden_inside_properties(self):
        hidden = ('<properties><testcase classname="' + CLASS + '" name="' + METHOD +
                  '" time="0.1"/></properties>')
        nested_only = ('<testsuite name="' + CLASS +
            '" tests="1" failures="0" errors="0" skipped="0">' + hidden + '</testsuite>').encode('utf-8')
        for bad in (nested_only, xml(casesuffix=hidden)):
            with self.assertRaisesRegex(ValueError, 'JUNIT_EXACT_CASE'):
                exact_xml(bad)

    def test_failed_original_xml_is_retained_byte_for_byte_before_interpretation(self):
        source, output = self.synthetic_roots(); output.mkdir()
        path = source / XML; path.parent.mkdir(parents=True)
        failed = xml(failures='1', children='<failure message="synthetic failure"/>'); path.write_bytes(failed)
        self.assertTrue(self.control.retained_xml(source, output))
        self.assertEqual((output / path.name).read_bytes(), failed)
        with self.assertRaises(FileExistsError): self.control.retained_xml(source, output)

    def valid_reports(self, output):
        output.mkdir()
        (output / Path(XML).name).write_bytes(xml())
        (output / 'rclone-version.txt').write_text('rclone v1.76.0\nsynthetic report metadata only\n', encoding='utf-8')
        archive = {'kind': 'CCB_RCLONE_PINNED_ARCHIVE', 'version': VERSION, 'filename': ARCHIVE,
                   'downloadUrl': URL, 'bytes': 1234, 'sha256': ARCHIVE_SHA}
        counts = {'kind': 'CCB_REAL_WEBDAV_RUNTIME_CHAT_RECOVERY_COUNTS', 'sourceSha': SOURCE,
                  'documentBytes': 131113, 'documentSha256': '4d54264dec430f62d42fa8430325fd4925474c1a11040045a77cf690f20d4e45',
                  'sessionCount': 1, 'messageCount': 4, 'conversationValueHash': 'c' * 64,
                  'uploadedObjects': 9, 'sourceAuthorSequence': 1, 'recoveredAuthorSequence': 0,
                  'saveConnection': False, 'coldHttpRequests': 0,
                  'senderMethods': {'GET': 1, 'PUT': 1, 'PROPFIND': 1, 'MKCOL': 1},
                  'recoveryMethods': {'GET': 1, 'PROPFIND': 1},
                  'paths': {'/weak-chunks-v1/x/': 1, '/weak-assemblies-v1/y/': 1}}
        (output / ARCHIVE_FILE).write_text(json.dumps(archive), encoding='utf-8')
        (output / METHOD_FILE).write_text(json.dumps(counts), encoding='utf-8')
        return archive, counts

    def test_report_metadata_exact_tool_and_required_methods(self):
        source, output = self.synthetic_roots(); archive, counts = self.valid_reports(output)
        self.control.verify_reports(source, output, self.control.expected_request())
        for key, value in (('version', 'latest'), ('sha256', '0' * 64), ('bytes', True), ('downloadUrl', 'https://other.invalid/')):
            bad = dict(archive); bad[key] = value
            (output / ARCHIVE_FILE).write_text(json.dumps(bad), encoding='utf-8')
            with self.assertRaises(ValueError): self.control.verify_reports(source, output, self.control.expected_request())
        (output / ARCHIVE_FILE).write_text(json.dumps(archive), encoding='utf-8')
        for key in counts['senderMethods']:
            bad = deepcopy(counts); del bad['senderMethods'][key]
            (output / METHOD_FILE).write_text(json.dumps(bad), encoding='utf-8')
            with self.assertRaises(ValueError): self.control.verify_reports(source, output, self.control.expected_request())

    def test_report_payload_config_and_header_paths_never_accepted_for_upload(self):
        source, output = self.synthetic_roots(); _, counts = self.valid_reports(output)
        (output / 'server.log').write_bytes(b'synthetic private server log')
        with self.assertRaisesRegex(ValueError, 'REPORT_DIRECTORY_NOT_EXCLUSIVE'):
            self.control.verify_reports(source, output, self.control.expected_request())
        (output / 'server.log').unlink()
        counts['paths'] = {'/Authorization:synthetic': 1}
        (output / METHOD_FILE).write_text(json.dumps(counts), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'HEADER_FREE_PATHS'):
            self.control.verify_reports(source, output, self.control.expected_request())

    def test_format3_rejects_old_opaque_schema_or_extra_business_and_credential_fields(self):
        source, output = self.synthetic_roots(); _, counts = self.valid_reports(output)
        old = {'kind': 'CCB_REAL_WEBDAV_METHOD_COUNTS', 'payloadBytes': 196731, 'physicalChunks': 7,
               'methods': {'GET': 1, 'PUT': 1, 'PROPFIND': 1, 'MKCOL': 1}, 'paths': {'/weak-chunks-v1/x/': 1}}
        candidates = [old]
        for key in ('headers', 'password', 'recoveryCode', 'messageText', 'payloadBytes', 'physicalChunks'):
            value = deepcopy(counts); value[key] = 'synthetic forbidden field'; candidates.append(value)
        for value in candidates:
            (output / METHOD_FILE).write_text(json.dumps(value), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'METHOD_SCOPE'):
                self.control.verify_reports(source, output, self.control.expected_request())

    def test_format3_requires_every_new_metadata_field(self):
        source, output = self.synthetic_roots(); _, counts = self.valid_reports(output)
        for key in counts:
            with self.subTest(key=key):
                bad = deepcopy(counts); del bad[key]
                (output / METHOD_FILE).write_text(json.dumps(bad), encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'METHOD_SCOPE'):
                    self.control.verify_reports(source, output, self.control.expected_request())

    def test_format3_metadata_must_be_an_object(self):
        source, output = self.synthetic_roots(); self.valid_reports(output)
        for value in (None, [], 1, 'synthetic invalid root'):
            (output / METHOD_FILE).write_text(json.dumps(value), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'METHOD_SCOPE'):
                self.control.verify_reports(source, output, self.control.expected_request())

    def test_chat_document_counts_hashes_and_scalar_types_are_strict(self):
        source, output = self.synthetic_roots(); _, counts = self.valid_reports(output)
        mutations = [('kind', 'other'), ('sourceSha', 'a' * 40), ('documentBytes', 131112), ('documentBytes', True),
                     ('documentSha256', '0' * 64), ('sessionCount', 2), ('sessionCount', True),
                     ('messageCount', 3), ('messageCount', True), ('conversationValueHash', 'C' * 64),
                     ('conversationValueHash', 'c' * 63), ('conversationValueHash', 1), ('uploadedObjects', 0),
                     ('uploadedObjects', True), ('sourceAuthorSequence', 2), ('sourceAuthorSequence', True),
                     ('recoveredAuthorSequence', 1), ('recoveredAuthorSequence', False),
                     ('saveConnection', True), ('saveConnection', 0), ('coldHttpRequests', 1), ('coldHttpRequests', False)]
        for key, value in mutations:
            with self.subTest(key=key, value=value):
                bad = deepcopy(counts); bad[key] = value
                (output / METHOD_FILE).write_text(json.dumps(bad), encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'METHOD_SCOPE'):
                    self.control.verify_reports(source, output, self.control.expected_request())

    def test_separate_method_maps_and_restore_only_forbid_any_recovery_put(self):
        source, output = self.synthetic_roots(); _, counts = self.valid_reports(output)
        bad_values = []
        for group, required in (('senderMethods', ('PUT', 'GET', 'PROPFIND', 'MKCOL')),
                                ('recoveryMethods', ('GET', 'PROPFIND'))):
            for method in required:
                bad = deepcopy(counts); del bad[group][method]; bad_values.append(bad)
            bad = deepcopy(counts); bad[group]['GET'] = True; bad_values.append(bad)
            bad = deepcopy(counts); bad[group] = []; bad_values.append(bad)
        for count in (0, 1):
            bad = deepcopy(counts); bad['recoveryMethods']['PUT'] = count; bad_values.append(bad)
        for bad in bad_values:
            (output / METHOD_FILE).write_text(json.dumps(bad), encoding='utf-8')
            with self.assertRaises(ValueError):
                self.control.verify_reports(source, output, self.control.expected_request())

    def test_paths_require_both_physical_namespaces_and_positive_header_free_counts(self):
        source, output = self.synthetic_roots(); _, counts = self.valid_reports(output)
        for paths in ({'/weak-chunks-v1/x/': 1}, {'/weak-assemblies-v1/y/': 1},
                      {'/weak-chunks-v1/x/': True, '/weak-assemblies-v1/y/': 1},
                      {'/Authorization:synthetic': 1}, {'http://127.0.0.1/': 1}):
            bad = deepcopy(counts); bad['paths'] = paths
            (output / METHOD_FILE).write_text(json.dumps(bad), encoding='utf-8')
            with self.assertRaises(ValueError):
                self.control.verify_reports(source, output, self.control.expected_request())

    def test_old_opaque_junit_cannot_be_promoted_by_new_chat_metadata(self):
        source, output = self.synthetic_roots(); self.valid_reports(output)
        old_class = 'com.example.chatbar.domain.syncStoreProof.RealWebDavInteropSmokeTest'
        (output / Path(XML).name).write_bytes(xml(suite=old_class, classname=old_class,
            name='realServerMethodsAndColdDifferentLocalRoutePreserveCompleteOpaquePayload'))
        with self.assertRaises(ValueError):
            self.control.verify_reports(source, output, self.control.expected_request())

    def test_process_owner_uses_only_fixed_new_identity_and_existing_bounded_cleanup(self):
        import real_webdav_process_owner as owner
        self.assertEqual(SOURCE, owner.SOURCE); self.assertEqual(TARGET, owner.TARGET); self.assertEqual(XML, owner.XML)
        self.assertEqual(CLASS + '.' + METHOD, owner.SELECTOR)
        self.assertEqual(self.control.expected_request()['fixtureSha256'], owner.FIXTURE_SHA)
        self.assertEqual(5, owner.STOP_SECONDS)

    def test_diagnostic_variant_has_only_safe_type_and_original_stack_locations(self):
        body = (HERE / FIXTURE).read_text(encoding='utf-8')
        self.assertIn('catch (failure: Throwable)', body)
        self.assertIn('type=${failure.javaClass.simpleName}', body)
        self.assertIn('safe.stackTrace = failure.stackTrace', body)
        self.assertNotIn('failure.message', body); self.assertNotIn('failure.cause', body)
        self.assertNotIn('failure.suppressed', body); self.assertNotIn('safe.initCause', body)
        self.assertEqual(1, body.count('@Test fun'))

    def test_workflow_fixed_source_and_exact_eight_report_allowlist(self):
        body = (HERE.parents[1] / '.github/workflows/lan-next-real-webdav-interop.yml').read_text(encoding='utf-8')
        self.assertIn('test "$source_sha" = ' + SOURCE, body)
        prefix = '${{ runner.temp }}/ccb-real-webdav-reports/'
        actual = [line.strip()[len(prefix):] for line in body.splitlines() if line.strip().startswith(prefix)]
        expected = {'prepared.json', 'execution.json', 'runner-stdout.txt', 'runner-stderr.txt', 'rclone-version.txt',
                    ARCHIVE_FILE, METHOD_FILE, Path(XML).name}
        self.assertEqual(8, len(actual)); self.assertEqual(expected, set(actual))
        self.assertIn('if: always()', body)

    def test_roots_must_be_distinct_owned_runner_temporaries(self):
        source, output = self.synthetic_roots()
        self.control.roots(source, output)
        with self.assertRaisesRegex(ValueError, 'OWNED_DISTINCT_TEMP_ROOTS'): self.control.roots(source, source / 'evidence')
        with self.assertRaisesRegex(ValueError, 'OWNED_DISTINCT_TEMP_ROOTS'): self.control.roots(source, HERE / 'outside-runner')

    def test_timeout_terminates_only_new_child_group_then_bounds_drain(self):
        child = mock.Mock(pid=32145)
        child.wait.side_effect = [subprocess.TimeoutExpired('synthetic child', 1080), 137]
        child.poll.return_value = 0  # Leader exited; a same-PGID descendant survives TERM.
        # No process/signal executes: these replace only the OS boundary, while
        # the actual finite timeout/escalation/failure logic is exercised.
        with mock.patch('real_webdav_interop_control.subprocess.Popen', return_value=child) as spawn, \
             mock.patch('real_webdav_interop_control.os.killpg', create=True) as kill, \
             mock.patch('real_webdav_interop_control.signal.SIGKILL', 9, create=True), \
             mock.patch('real_webdav_interop_control.time.monotonic', side_effect=[0, 16]):
            with self.assertRaises(subprocess.TimeoutExpired):
                self.control.child(['synthetic-not-executed'], None, None)
            self.assertTrue(spawn.call_args.kwargs['start_new_session'])
            self.assertEqual([call.args[0] for call in kill.call_args_list], [32145, 32145, 32145])
            self.assertEqual([call.args[1] for call in kill.call_args_list][-2:], [0, 9])
            self.assertEqual([call.kwargs['timeout'] for call in child.wait.call_args_list], [1080, 15])


if __name__ == '__main__':
    unittest.main(verbosity=2)
