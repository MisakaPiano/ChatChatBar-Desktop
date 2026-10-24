"""Ordinary-permission Windows cleanup guards on newly created fake files only.
Not LR6-B durability, protector, VM or production transaction evidence.
"""
from contextlib import ExitStack
from pathlib import Path
import unittest
import uuid
import StorageAudit as a
import RetireAuthorizedAvds as cleanup


class CleanupGuardTest(unittest.TestCase):
    def setUp(self):
        local = Path((a.REPO / 'app/desktopApp/build/p11-lr6-private-space/current-task-pointer.txt').read_text().strip())
        self.root = local / 'cleanup-guard-fixtures' / str(uuid.uuid4())
        self.root.mkdir(parents=True)
        self.file = self.root / 'synthetic-only.tmp'
        self.file.write_bytes(b'SYNTHETIC_ONLY')
        self.row = dict(kind='FILE', identity=list(a.identity(self.file.lstat())))
        self.native = cleanup.NativeGuard()

    def test_pinned_fake_file_deletion(self):
        with ExitStack() as stack:
            h = self.native.open(self.file, self.row, stack)
            self.native.delete_file(h)
        self.assertFalse(self.file.exists())

    def test_exclusive_open_blocks_second_cleanup(self):
        with ExitStack() as first, ExitStack() as second:
            self.native.open(self.file, self.row, first)
            with self.assertRaisesRegex(RuntimeError, 'SKIP_ACTIVE'):
                self.native.open(self.file, self.row, second)
        self.assertTrue(self.file.exists())

    def test_changed_file_identity_or_metadata_blocks(self):
        self.file.write_bytes(b'SYNTHETIC_CHANGED')
        with ExitStack() as stack:
            with self.assertRaisesRegex(RuntimeError, 'SKIP_CHANGED'):
                self.native.open(self.file, self.row, stack)
        self.assertTrue(self.file.exists())

    def test_alternate_stream_blocks_and_is_retained(self):
        with open(str(self.file) + ':synthetic-extra', 'wb') as f:
            f.write(b'SYNTHETIC_ALTERNATE_ONLY')
        self.row['identity'] = list(a.identity(self.file.lstat()))
        with ExitStack() as stack:
            with self.assertRaisesRegex(RuntimeError, 'ALTERNATE_STREAM'):
                self.native.open(self.file, self.row, stack)
        self.assertTrue(self.file.exists())

    def test_general_directory_deletion_is_not_exposed(self):
        with self.assertRaisesRegex(RuntimeError, 'NOT_EXACT_AUTHORIZED_TARGET'):
            cleanup.manifest(a.REPO, self.root.relative_to(a.REPO).as_posix())


if __name__ == '__main__':
    unittest.main()
