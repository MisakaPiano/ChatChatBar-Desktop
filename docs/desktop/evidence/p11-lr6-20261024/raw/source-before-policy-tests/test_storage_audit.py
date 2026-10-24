"""Synthetic metadata-policy tests; not native durability or DPAPI evidence."""
import hashlib
import json
import os
from pathlib import Path
import stat
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import uuid
import zipfile
import StorageAudit as audit


class StorageSafetyTest(unittest.TestCase):
    def setUp(self):
        local = Path((audit.REPO / 'app/desktopApp/build/p11-lr6-private-space/current-task-pointer.txt').read_text().strip())
        self.root = local / 'policy-fixtures' / str(uuid.uuid4())
        self.root.mkdir(parents=True)
        # Retained LR6 fixture, never auto-cleaned during this run (199-C).

    def make_round(self, extra=False, changed=False, unsafe=False):
        root = self.root / 'app/desktopApp/build/p11-lr5/first-protocol'
        root.mkdir(parents=True)
        (root / 'fake.dat').write_bytes(b'NEW_FAKE' if changed else b'OLD_FAKE')
        if extra:
            (root / 'unrecorded-note.txt').write_text('synthetic unknown owner')
        zpath = self.root / 'docs/desktop/evidence/p11-lr5-20261024/raw/first-protocol/synthetic-final-state.zip'
        zpath.parent.mkdir(parents=True)
        with zipfile.ZipFile(zpath, 'x') as z:
            z.writestr('../outside' if unsafe else 'fake.dat', b'OLD_FAKE')
        return root

    def test_outside_path_is_rejected(self):
        self.assertFalse(audit.contained(self.root, self.root.parent))

    def test_nonexistent_component_is_not_trusted(self):
        self.assertFalse(audit.contained(self.root, self.root / 'missing'))

    def test_reparse_and_symlink_flags_are_recognized(self):
        self.assertTrue(audit.reparse(SimpleNamespace(st_mode=stat.S_IFDIR, st_file_attributes=0x400)))
        self.assertTrue(audit.reparse(SimpleNamespace(st_mode=stat.S_IFLNK, st_file_attributes=0)))
        self.assertFalse(audit.reparse(SimpleNamespace(st_mode=stat.S_IFREG, st_file_attributes=0)))

    def test_census_counts_logical_bytes_without_reading_content(self):
        (self.root / 'opaque.bin').write_bytes(b'x' * 11)
        ledger = self.root.parent / (str(uuid.uuid4()) + '.csv')
        with patch.object(Path, 'open', autospec=True, side_effect=Path.open) as opened:
            result = audit.census(self.root, ledger)
        self.assertEqual(11, result['totalLogicalBytes'])
        self.assertEqual(1, opened.call_count)  # local output ledger only
        self.assertEqual('LOGICAL_FILE_BYTES_NOT_ALLOCATION', result['measure'])

    def test_private_descendant_names_do_not_enter_public_summary(self):
        (self.root / 'SYNTHETIC_PRIVATE_NAME.bin').write_bytes(b'x')
        result = audit.census(self.root, self.root.parent / (str(uuid.uuid4()) + '.csv'))
        self.assertNotIn('SYNTHETIC_PRIVATE_NAME', json.dumps(result))

    def test_closed_allowlist_and_immutable_categories(self):
        self.assertEqual(7, len(audit.ALLOWLIST))
        self.assertNotIn('app/desktopApp/build', audit.ALLOWLIST)
        self.assertEqual('.git/objects/pack', audit.category(('.git', 'objects', 'pack', 'opaque.pack')))
        self.assertEqual('docs/desktop/evidence', audit.category(('docs', 'desktop', 'evidence', 'raw')))

    def test_matching_snapshot_still_cannot_authorize_deletion(self):
        root = self.make_round()
        before = (root / 'fake.dat').read_bytes()
        result = audit.compare_round(self.root, 'first-protocol')
        self.assertTrue(result['acceptedSnapshotFilesMatch'])
        self.assertEqual('RETAIN_UNKNOWN', result['decision'])
        self.assertEqual(before, (root / 'fake.dat').read_bytes())

    def test_unknown_descendant_retains_entire_candidate(self):
        root = self.make_round(extra=True)
        self.assertEqual('ADDITIONAL_OR_MISSING_FILES', audit.compare_round(self.root, 'first-protocol')['reason'])
        self.assertTrue((root / 'unrecorded-note.txt').exists())

    def test_changed_bytes_retain_candidate(self):
        self.make_round(changed=True)
        self.assertEqual('CONTENT_CHANGED_FROM_ACCEPTED_SNAPSHOT', audit.compare_round(self.root, 'first-protocol')['reason'])

    def test_unsafe_zip_member_is_rejected(self):
        self.make_round(unsafe=True)
        self.assertEqual('UNSAFE_RETAINED_ARCHIVE', audit.compare_round(self.root, 'first-protocol')['reason'])

    def test_unlisted_round_rejected(self):
        with self.assertRaisesRegex(ValueError, 'NOT_ALLOWLISTED'):
            audit.compare_round(self.root, 'other-round')

    def test_missing_round_is_not_deleted_or_created(self):
        self.assertEqual('NOT_PRESENT', audit.compare_round(self.root, 'first-protocol')['decision'])
        self.assertEqual([], list(self.root.iterdir()))


if __name__ == '__main__':
    unittest.main()
