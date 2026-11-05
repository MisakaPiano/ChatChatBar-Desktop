import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from pinned_drift import build_report, parse_diff, read_tree, path_flags


class PinnedDriftTest(unittest.TestCase):
    def test_domain_flags_identify_original_high_risk_owners_as_path_review_candidates(self):
        prefix = 'app/app/src/main/java/com/example/chatbar/domain/'
        for path, flag in (
            ('memory/MemoryCoordinator.kt', 'MEMORY_RAG_REVIEW'),
            ('rag/Retrieval.kt', 'MEMORY_RAG_REVIEW'),
            ('worldbook/WorldBookPolicy.kt', 'WORLD_BOOK_REVIEW'),
            ('chat/StreamingChatService.kt', 'PROVIDER_TRANSPORT_REVIEW'),
            ('chat/MessageAlternativeVersionPolicy.kt', 'PROMPT_CONTEXT_HISTORY_REVIEW'),
            ('chat/RoleplayContentSegments.kt', 'PROMPT_CONTEXT_HISTORY_REVIEW'),
            ('backup/SaveSlot.kt', 'SAVE_SLOT_RECOVERY_REVIEW'),
            ('prompt/PromptTemplates.kt', 'PROMPT_REVIEW_CANDIDATE_NOT_LITERAL_DIFF_PROOF')):
            self.assertIn(flag, path_flags(prefix + path))

    def test_nul_records_preserve_tabs_newlines_and_rename_identity(self):
        before, after = '1' * 40, '2' * 40
        raw = (f':100644 100644 {before} {after} R091\0old\tname.kt\0new\n名字.kt\0').encode()
        row = parse_diff(raw)[0]
        self.assertEqual('old\tname.kt', row['beforePath'])
        self.assertEqual('new\n名字.kt', row['afterPath'])
        self.assertEqual(before, row['beforeBlob'])
        self.assertEqual(after, row['afterBlob'])
        tree = read_tree((f'100644 blob {after}\tnew\n名字.kt\0').encode())
        self.assertEqual(after, tree[row['afterPath']]['blob'])
        with self.assertRaises(ValueError):
            parse_diff((f':100644 100644 {before} {after} R091\0old\0').encode())

    def test_actual_git_report_maps_moved_shared_owner_without_claiming_android_path_or_absence_is_parity(self):
        with tempfile.TemporaryDirectory(prefix='pinned-drift-') as folder:
            root = Path(folder)
            def git(*args):
                return subprocess.check_output(['git', '-C', str(root), *args], stderr=subprocess.PIPE).decode().strip()
            git('init', '-q')
            git('config', 'user.name', 'Synthetic Fixture')
            git('config', 'user.email', 'fixture@example.invalid')
            git('config', 'core.autocrlf', 'false')
            git('config', 'commit.gpgSign', 'false')
            git('config', 'core.hooksPath', str(root / '.git/no-fixture-hooks'))
            def write(path, text):
                file = root / path
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(text, encoding='utf-8')
            def save():
                git('add', '--all')
                git('commit', '-qm', 'synthetic')
                return git('rev-parse', 'HEAD')
            android = 'app/app/src/main/java/com/example/chatbar/'
            shared = 'app/sharedCore/src/main/kotlin/com/example/chatbar/'
            test = 'app/app/src/test/java/com/example/chatbar/LogicTest.kt'
            write(android + 'domain/Shared.kt', 'before-shared')
            write(android + 'ui/Main.kt', 'before-ui')
            write(test, 'before-test')
            write('docs/old name.txt', 'rename-evidence')
            baseline = save()
            write(android + 'domain/Shared.kt', 'after-shared')
            write(android + 'ui/Main.kt', 'after-ui')
            write(test, 'after-test')
            write(android + 'domain/NewFeature.kt', 'upstream-only')
            git('mv', 'docs/old name.txt', 'docs/new name.txt')
            target = save()
            git('checkout', '--detach', baseline)
            (root / (android + 'domain/Shared.kt')).unlink()
            (root / test).unlink()
            write(shared + 'domain/Shared.kt', 'after-shared')
            write('app/sharedCore/src/test/kotlin/com/example/chatbar/LogicTest.kt', 'desktop-test-under-review')
            write('docs/desktop/evidence/synthetic/official-source/NewFeature.kt.txt', 'upstream-only')
            write('docs/desktop/10_UPSTREAM_BASELINE.json', json.dumps({'upstream': {
                'commit': baseline, 'releaseVersion': 'synthetic-1', 'repository': 'Synthetic/Upstream'}}))
            desktop = save()
            status = git('status', '--porcelain')
            report = build_report(root, baseline, target, desktop)
            with self.assertRaises(ValueError):
                build_report(root, 'HEAD', target, desktop)
            self.assertEqual(5, report['counts']['changedEntries'])
            self.assertTrue(report['baselineMatchesDeclared'])
            rows = {row['afterPath']: row for row in report['changes']}
            moved = rows[android + 'domain/Shared.kt']
            self.assertEqual('SHARED_PACKAGE_PATH', moved['candidates'][0]['role'])
            self.assertTrue(moved['candidates'][0]['exactTargetBlob'])
            self.assertEqual('SHARED_TEST_PACKAGE_PATH', rows[test]['candidates'][0]['role'])
            self.assertFalse(rows[test]['candidates'][0]['exactTargetBlob'])
            ui = rows[android + 'ui/Main.kt']
            self.assertEqual('SAME_REPOSITORY_PATH', ui['candidates'][0]['role'])
            self.assertFalse(ui['candidates'][0]['exactTargetBlob'])
            absent = rows[android + 'domain/NewFeature.kt']
            self.assertEqual([], absent['candidates'])
            self.assertEqual('ARCHIVED_REFERENCE_NOT_ACTIVE_SOURCE', absent['archivedBlobReferences'][0]['role'])
            self.assertEqual('NOT_DEDUCED_FROM_PATHS', absent['desktopFeaturePresence'])
            self.assertTrue(rows['docs/new name.txt']['status'].startswith('R'))
            self.assertEqual('docs/old name.txt', rows['docs/new name.txt']['candidates'][0]['path'])
            self.assertTrue(all(row['behaviorCompatibility'] == 'NOT_EVALUATED' for row in report['changes']))
            output = root / 'report.json'
            command = [sys.executable, str(Path(__file__).with_name('pinned_drift.py')), '--repository', str(root),
                '--baseline', baseline, '--target', target, '--desktop', desktop, '--output', str(output)]
            result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.assertEqual(0, result.returncode, result.stderr.decode())
            original = output.read_bytes()
            refused = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.assertNotEqual(0, refused.returncode)
            self.assertEqual(original, output.read_bytes())
            output.unlink()
            self.assertEqual(desktop, git('rev-parse', 'HEAD'))
            self.assertEqual(status, git('status', '--porcelain'))


if __name__ == '__main__':
    unittest.main()
