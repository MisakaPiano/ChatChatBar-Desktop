"""Tiny, independent synthetic images only; never reads project presets or real data."""
import contextlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import audit_app_image as tool


class AppImageAuditTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='ccb-image-audit-synthetic-')
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.image = self.base / 'ChatChatBarDesktopSynthetic'
        (self.image / 'app').mkdir(parents=True)
        (self.image / 'runtime/bin/server').mkdir(parents=True)
        (self.image / 'ChatChatBarDesktopSynthetic.exe').write_bytes(b'MZ_SYNTHETIC_NOT_EXECUTABLE')
        (self.image / 'runtime/bin/server/jvm.dll').write_bytes(b'SYNTHETIC_NOT_A_JVM')
        self.expected = {'version': '0.1.0-preview.1009', 'commit': 'a' * 40, 'installerVersion': '0.1.1009',
                         'installerVersionAssigned': 'true', 'packagingProfile': 'synthetic'}
        self.raw_identity = ('version=0.1.0-preview.1009\ncommit=' + 'a' * 40 +
                             '\ninstallerVersion=0.1.1009\ninstallerVersionAssigned=true\npackagingProfile=synthetic\n').encode()
        self.jar('main.jar', [('desktop-build.properties', self.raw_identity), ('com/example/Main.class', b'SYNTHETIC_MAIN')])
        self.jar('dependency.jar', [('dependency/Library.class', b'SYNTHETIC_DEPENDENCY')])
        self.cfg = self.image / 'app/ChatChatBarDesktopSynthetic.cfg'
        self.cfg.write_text('[Application]\napp.mainclass=com.example.chatbar.desktop.MainKt\n'
                            'app.classpath=$APPDIR\\main.jar\napp.classpath=$APPDIR\\dependency.jar\n')

    def jar(self, name, entries):
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(self.image / 'app' / name, 'w', compression=zipfile.ZIP_STORED) as archive:
                for path, value in entries:
                    # ZipInfo(path) normalizes Windows backslashes. Preserve the intended wire name.
                    entry = zipfile.ZipInfo('fixture-entry')
                    entry.filename = entry.orig_filename = path
                    archive.writestr(entry, value)

    def files(self):
        return {p.relative_to(self.image).as_posix(): p.read_bytes() for p in self.image.rglob('*') if p.is_file()}

    def refused(self, code):
        with self.assertRaisesRegex(tool.Refused, '^' + code + '$'):
            tool.audit(self.image, self.expected)

    def args(self, output):
        return ['--image', str(self.image), '--commit', self.expected['commit'], '--version', self.expected['version'],
                '--installer-version', self.expected['installerVersion'], '--profile', 'synthetic', '--report', str(output)]

    def test_counts_crc_and_identity_are_observed_without_changing_input(self):
        before = self.files()
        result = tool.audit(self.image, self.expected)
        self.assertEqual((5, 2, 2, 3), (result['fileCount'], result['jarCount'], result['classEntryCount'], result['jarEntryCount']))
        self.assertEqual(self.expected, result['identity'])
        self.assertEqual('NOT_TESTED', result['installerAcceptance'])
        self.assertEqual('NOT_PROVEN', result['arbitrarySecretContentAbsence'])
        self.assertEqual(before, self.files())

    def test_each_expected_identity_field_is_exact(self):
        for key in self.expected:
            with self.subTest(field=key):
                expected = self.expected | {key: 'b' * 40 if key == 'commit' else ('desktop' if key == 'packagingProfile' else 'DIFFERENT')}
                with self.assertRaisesRegex(tool.Refused, 'IMAGE_(LAYOUT_REFUSED|IDENTITY_MISMATCH)'):
                    tool.audit(self.image, expected)

    def test_missing_or_duplicate_identity_is_refused(self):
        self.jar('main.jar', [('Main.class', b'NO_IDENTITY')])
        self.refused('IMAGE_IDENTITY_COUNT')
        self.jar('main.jar', [('desktop-build.properties', self.raw_identity)])
        self.jar('dependency.jar', [('desktop-build.properties', self.raw_identity)])
        self.refused('IMAGE_IDENTITY_COUNT')

    def test_duplicate_identity_fields_do_not_hide_a_second_commit(self):
        self.jar('main.jar', [('desktop-build.properties', self.raw_identity + b'commit=development\n')])
        self.refused('IMAGE_IDENTITY_DUPLICATE_FIELD')

    def test_private_loose_archive_is_refused(self):
        (self.image / 'app/DO_NOT_PUBLISH.cbbackup').write_bytes(b'SYNTHETIC_ARCHIVE')
        self.refused('IMAGE_PRIVATE_ENTRY')

    def test_private_directory_is_refused_without_traversing_it(self):
        (self.image / 'app/UserData').mkdir()
        self.refused('IMAGE_PRIVATE_ENTRY')

    def test_private_jar_resource_is_refused(self):
        self.jar('dependency.jar', [('hidden/TEST_ONLY.cbsave', b'SYNTHETIC_ARCHIVE')])
        self.refused('IMAGE_PRIVATE_ENTRY')

    def test_known_private_external_witness_is_refused(self):
        for name in ['RealRootRelocationLocal.class', 'RealRootRelocationLocal$main$1.class', 'DesktopWindowsPackageFixture.class']:
            with self.subTest(companion=name):
                self.jar('dependency.jar', [('com/example/chatbar/desktop/' + name, b'SYNTHETIC_COMPANION')])
                self.refused('IMAGE_PRIVATE_ENTRY')

    def test_unsafe_jar_paths_are_refused(self):
        for name in ['../raw.txt', '/absolute.txt', 'C:/raw.txt', 'one\\two.txt', 'one/./two.txt', 'one//two.txt', 'raw\x00secret.txt']:
            with self.subTest(name=name):
                self.jar('dependency.jar', [(name, b'SYNTHETIC')])
                with zipfile.ZipFile(self.image / 'app/dependency.jar') as archive:
                    self.assertEqual([name], [entry.orig_filename for entry in archive.infolist()])
                self.refused('IMAGE_JAR_PATH_REFUSED')

    def test_duplicate_jar_entry_is_refused(self):
        self.jar('dependency.jar', [('duplicate.class', b'ONE'), ('duplicate.class', b'TWO')])
        self.refused('IMAGE_DUPLICATE_JAR_ENTRY')

    def test_bad_crc_is_not_hidden_by_a_matching_file_hash(self):
        path = self.image / 'app/dependency.jar'
        path.write_bytes(path.read_bytes().replace(b'SYNTHETIC_DEPENDENCY', b'CORRUPTED_DEPENDENCY', 1))
        with self.assertRaises(zipfile.BadZipFile):
            tool.audit(self.image, self.expected)

    def test_classpath_requires_every_jar_exactly_once(self):
        original = self.cfg.read_text()
        for bad in [original.replace('app.classpath=$APPDIR\\main.jar\n', ''), original + 'app.classpath=C:\\unexpected.jar\n',
                    original + 'app.classpath=$APPDIR\\main.jar\n']:
            with self.subTest(config=bad):
                self.cfg.write_text(bad)
                self.refused('IMAGE_CLASSPATH_REFUSED')

    def test_unknown_image_member_and_missing_runtime_are_refused(self):
        unknown = self.image / 'DO_NOT_SHIP.txt'
        unknown.write_bytes(b'SYNTHETIC')
        self.refused('IMAGE_LAYOUT_REFUSED')
        unknown.unlink()
        (self.image / 'runtime/bin/server/jvm.dll').unlink()
        self.refused('IMAGE_RUNTIME_MISSING')

    def test_budget_limits_refuse_instead_of_skipping_files(self):
        with patch.object(tool, 'MAX_FILES', 4):
            self.refused('IMAGE_FILE_LIMIT')
        with patch.object(tool, 'MAX_FILE', 1):
            self.refused('IMAGE_FILE_LIMIT')
        with patch.object(tool, 'MAX_ENTRIES', 1):
            self.refused('IMAGE_JAR_ENTRY_LIMIT')

    def test_actual_fixture_change_during_read_is_refused(self):
        actual = tool.regular
        calls = 0
        def observe(path, directory=False):
            nonlocal calls
            if path == self.image / 'ChatChatBarDesktopSynthetic.exe':
                calls += 1
                if calls == 3:
                    path.write_bytes(path.read_bytes() + b'_EXTERNAL_FIXTURE_CHANGE')
            return actual(path, directory)
        with patch.object(tool, 'regular', observe):
            self.refused('IMAGE_CHANGED')

    def test_cli_report_is_exclusive_and_outside_image(self):
        inside = self.image / 'app/report.json'
        output = self.base / 'report.json'
        with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
            with patch.object(sys, 'argv', ['audit', *self.args(inside)]):
                self.assertEqual(2, tool.main())
            self.assertFalse(inside.exists())
            with patch.object(sys, 'argv', ['audit', *self.args(output)]):
                self.assertEqual(0, tool.main())
            before = output.read_bytes()
            with patch.object(sys, 'argv', ['audit', *self.args(output)]):
                self.assertEqual(2, tool.main())
            self.assertEqual(before, output.read_bytes())

    def test_subprocess_failure_does_not_print_input_payload_or_path(self):
        self.jar('dependency.jar', [('private.cbbackup', b'SYNTHETIC_ONLY_TOKEN_NOT_REAL')])
        result = subprocess.run([sys.executable, '-I', str(Path(tool.__file__)), *self.args(self.base / 'no-report.json')],
                                capture_output=True)
        self.assertEqual(2, result.returncode)
        self.assertEqual(b'IMAGE_PRIVATE_ENTRY', result.stderr.strip())
        self.assertNotIn(str(self.image).encode(), result.stdout + result.stderr)
        self.assertNotIn(b'SYNTHETIC_ONLY_TOKEN_NOT_REAL', result.stdout + result.stderr)
        self.assertFalse((self.base / 'no-report.json').exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
