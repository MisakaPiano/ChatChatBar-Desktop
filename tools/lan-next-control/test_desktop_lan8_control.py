from contextlib import ExitStack
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import desktop_lan8_control as lan8


class ScrollRetentionTest(unittest.TestCase):
    def setUp(self):
        resources = ExitStack()
        self.addCleanup(resources.close)
        self.bridge = lan8.verify_bridge()
        helpers = resources.enter_context(self.bridge.private_helpers())
        self.old = helpers['prove_desktop_lan3_lan4'].legacy()
        self.directory = Path(resources.enter_context(tempfile.TemporaryDirectory())).resolve()
        self.scroll_path = self.directory / lan8.SCROLL_PATH
        self.scroll_path.parent.mkdir()
        self.other = self.directory / 'chat_sessions/profile-upgrade-session.json'
        self.other.parent.mkdir()
        self.other.write_bytes(b'{"id":"profile-upgrade-session"}')
        self.scroll = lan8.ScrollRetention(self.bridge, self.old.json_bodies)

        self.write_timestamp(10)
        old = self.inventory()
        old_bodies = self.scroll.json_bodies(self.directory, old)
        self.write_timestamp(20)
        self.before = self.inventory()
        self.bodies = self.scroll.json_bodies(self.directory, self.before)
        self.warm = self.old.changes(old, self.before, old_bodies, self.directory)

    def inventory(self):
        return {path.relative_to(self.directory).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                for path in self.directory.rglob('*') if path.is_file()}

    def write_timestamp(self, timestamp, offset=7):
        self.scroll_path.write_bytes((
            '{"sessionId":"profile-upgrade-session","anchorMessageId":"m",'
            f'"fallbackMessageIndex":0,"scrollOffset":{offset},"capturedAt":{timestamp}}}'
        ).encode('ascii'))

    def check_retained(self, inventory=None, observation=None):
        return self.scroll.retain(self.old, {}, self.before,
                                  self.inventory() if inventory is None else inventory,
                                  self.bodies, self.directory,
                                  self.warm if observation is None else observation)

    def test_original_warm_observation_permits_upgraded_and_cold_capture_time_only(self):
        for time in (30, 40):
            self.write_timestamp(time)
            proof = self.check_retained()
            self.assertEqual(proof['rawBytesUnchanged'], 1)
            self.assertEqual(proof['timestampOnlyChanges'][0]['changedField'], 'capturedAt')
        self.assertEqual(self.scroll.completed, 2)

    def test_other_field_and_timestamp_regression_are_refused(self):
        self.write_timestamp(30, offset=8)
        with self.assertRaisesRegex(ValueError, 'SCROLL_NON_TIMESTAMP_BYTES_CHANGED'):
            self.check_retained()
        self.write_timestamp(40)
        self.check_retained()
        self.write_timestamp(30)
        with self.assertRaisesRegex(ValueError, 'SCROLL_CAPTURE_TIME_REGRESSED'):
            self.check_retained()

    def test_added_deleted_or_unobserved_path_is_refused(self):
        self.write_timestamp(30)
        after = self.inventory()
        for changed in (dict(after, **{'new.json': '0' * 64}),
                        {path: digest for path, digest in after.items() if path != lan8.SCROLL_PATH}):
            with self.assertRaisesRegex(ValueError, 'SCROLL_RETENTION_PATHS_CHANGED'):
                self.check_retained(inventory=changed)
        with self.assertRaisesRegex(ValueError, 'SCROLL_CONTROL_NOT_OBSERVED'):
            self.check_retained(observation=[])

    def test_source_ranges_and_build_control_are_bound(self):
        candidate = '1' * 40
        build_control = '2' * 40
        body = b'first\ncritical\nlast\n'
        path = 'fixture.kt'

        def git_blob(_, command, *args):
            if command == 'rev-parse':
                return candidate.encode('ascii') + b'\n'
            return body

        with patch.object(lan8, 'SCROLL_SOURCES', {path: (('critical', 'last'),)}), \
             patch.object(lan8.control, 'git_bytes', side_effect=git_blob):
            expected = lan8.reading_position_binding(self.directory, candidate, build_control)
            self.assertEqual(expected['files'][0]['equalSlicesSha256'],
                             [hashlib.sha256(b'critical\n').hexdigest()])
            self.assertEqual(expected['candidateBuildControlSha'], build_control)
            request = {'packages': [lan8.BASE, {'sourceSha': candidate, 'controlSha': build_control}],
                       'timestampSourceBindings': {}}
            supplied = {'timestampSourceBindings': {}, 'readingPositionEquivalence': expected}

            def rebuild(repo, source, bridge, fixture, control):
                return {'timestampSourceBindings': {},
                        'readingPositionEquivalence': lan8.reading_position_binding(repo, source, control)}

            with patch.object(lan8, 'native_binding', side_effect=rebuild):
                self.assertEqual(lan8.require_native_binding(self.directory, request, supplied, None, None), supplied)
                for alternate in ({**expected, 'candidateSourceSha': '3' * 40},
                                  {**expected, 'candidateBuildControlSha': '4' * 40}):
                    with self.assertRaisesRegex(ValueError, 'EXACT_FROZEN_NATIVE_BINDING'):
                        lan8.require_native_binding(self.directory, request,
                            {**supplied, 'readingPositionEquivalence': alternate}, None, None)

        with patch.object(lan8, 'SCROLL_SOURCES', {path: (('critical', 'last'),)}), \
             patch.object(lan8.control, 'git_bytes',
                          side_effect=[candidate.encode('ascii'), body, body.replace(b'critical\n', b'critical changed\n')]):
            with self.assertRaisesRegex(ValueError, 'SCROLL_SOURCE_CLOSURE_CHANGED'):
                lan8.reading_position_binding(self.directory, candidate, build_control)


if __name__ == '__main__':
    unittest.main()
