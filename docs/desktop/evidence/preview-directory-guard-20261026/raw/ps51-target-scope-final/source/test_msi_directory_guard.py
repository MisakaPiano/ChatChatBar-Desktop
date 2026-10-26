"""No installation: read-only legacy MSI admission and native policy with injected fake APIs."""
import ctypes as C
from ctypes import wintypes as W
import json
import os
from pathlib import Path
import unittest
import sqlite3
import hashlib
from unittest.mock import patch
import msi_safety
import native_package_probe


class DirectoryTables(unittest.TestCase):
    def test_rollback_target_trace_never_promotes_unchanged_final_state(self):
        source = "MSI (s) (11:22) [00:00:00]: Command Line: UPGRADINGPRODUCTCODE={synthetic} REMOVE=ALL\n"
        for folder, expected in (("C:\\Synthetic\\Program\\", "TARGET_TRACE_ONLY"),
                                 ("C:\\Synthetic\\Default\\", "REMOVAL_TARGET_MISMATCH"),
                                 ("C:\\Synthetic\\Program2\\", "REMOVAL_TARGET_MISMATCH")):
            text = source + "MSI (s) (11:22) [00:00:01]: Executing op: SetTargetFolder(Folder=" + folder + ")\n"
            text += "MSI (s) (11:22) [00:00:02]: Executing op: FileRemove(,FileName=synthetic.exe)\n"
            result = native_package_probe.rollback_target_scope(text, "C:\\Synthetic\\Program")
            self.assertEqual(expected, result["status"])
            self.assertFalse(result["physicalRestorationProven"])
        self.assertEqual("UNKNOWN_CHILD_SESSION", native_package_probe.rollback_target_scope("", "C:\\Synthetic\\Program")["status"])

    def test_safety1_legacy_package_is_not_current_directory_contract(self):
        fixture = os.environ["CCB_LEGACY_MSI_FIXTURE"]
        with msi_safety.Database(fixture) as db:
            with self.assertRaisesRegex(AssertionError, "DIRECTORY_CONTRACT_REQUIRED"):
                msi_safety.verify(db, "synthetic")

    def test_authored_contract_and_existing_safety_rules_on_in_memory_tables(self):
        db = SyntheticTables()
        msi_safety.install_directory_contract(db, dict(db.rows("Property", 2)), Path(os.environ["CCB_GUARD_PRODUCTION_DLL"]))
        self.assertEqual("2", msi_safety.verify(db, "synthetic")["CCB_DIRECTORY_CONTRACT"])
        db.sql("UPDATE `InstallExecuteSequence` SET `Sequence`=799 WHERE `Action`='CcbResolveInstallDirectory'")
        with self.assertRaisesRegex(AssertionError, "DIRECTORY_RESOLUTION_SEQUENCE_CHANGED"):
            msi_safety.verify(db, "synthetic")

    def test_unknown_saved_registry_layout_or_colliding_sequence_refuses(self):
        for mutation in ("UPDATE `Registry` SET `Root`='2' WHERE `Value`='[INSTALLDIR]'",
                         "INSERT INTO `InstallExecuteSequence` VALUES ('SyntheticCollision','','60')"):
            db = SyntheticTables(); db.sql(mutation)
            with self.assertRaises(AssertionError):
                msi_safety.install_directory_contract(db, dict(db.rows("Property", 2)), Path(os.environ["CCB_GUARD_PRODUCTION_DLL"]))
            self.assertNotIn("CcbDirectoryGuard", db.binaries)

    def test_production_binary_has_no_test_export(self):
        library = C.WinDLL(os.environ["CCB_GUARD_PRODUCTION_DLL"])
        self.assertIsNotNone(library.CcbResolveInstallDirectory)
        self.assertIsNotNone(library.CcbRevalidateInstallDirectory)
        self.assertFalse(hasattr(library, "CcbTestDirectory"))

    def test_binary_stream_api_releases_handles_on_success_and_failure(self):
        class Call:
            def __init__(self, function):
                self.function = function
            def __call__(self, *args):
                return self.function(*args)
        for stream_result in (0, 5):
            closed = []
            def open_view(handle, sql, pointer):
                C.cast(pointer, C.POINTER(W.UINT))[0] = 12
                return 0
            library = type("FakeMsi", (), {})()
            library.MsiCreateRecord = Call(lambda fields: 14)
            library.MsiDatabaseOpenViewW = Call(open_view)
            library.MsiRecordSetStringW = Call(lambda *args: 0)
            library.MsiRecordSetStreamW = Call(lambda *args: stream_result)
            library.MsiViewExecute = Call(lambda *args: 0)
            library.MsiCloseHandle = Call(lambda handle: closed.append(handle.value if hasattr(handle, "value") else handle))
            db = msi_safety.Database.__new__(msi_safety.Database)
            db.lib = library; db.handle = W.UINT(7)
            if stream_result:
                with self.assertRaises(AssertionError):
                    db.add_binary("CcbDirectoryGuard", Path("synthetic-not-opened.dll"))
            else:
                db.add_binary("CcbDirectoryGuard", Path("synthetic-not-opened.dll"))
            self.assertEqual([12, 14], closed)


class SyntheticTables:
    """SQLite table adapter only; never edits a real MSI or invokes installer APIs."""
    def __init__(self):
        self.connection = sqlite3.connect(":memory:")
        self.binaries = {}
        columns = {
            "Property": ("Property", "Value"), "Registry": ("Registry", "Root", "Key", "Name", "Value", "Component_"),
            "Component": ("Component", "ComponentId", "Directory_", "Attributes", "Condition", "KeyPath"),
            "CustomAction": ("Action", "Type", "Source", "Target"),
            "InstallExecuteSequence": ("Action", "Condition", "Sequence"), "InstallUISequence": ("Action", "Condition", "Sequence"),
            "RemoveFile": ("FileKey", "Component_", "FileName", "DirProperty", "InstallMode"),
            "WixRemoveFolderEx": ("RemoveFolderEx", "Component_", "Property", "InstallMode"), "Binary": ("Name", "Data"),
            "_Tables": ("Name",),
        }
        with msi_safety.Database(os.environ["CCB_LEGACY_MSI_FIXTURE"]) as source:
            for table, names in columns.items():
                self.connection.execute("CREATE TABLE `" + table + "` (" + ",".join("`" + n + "` TEXT" for n in names) + ")")
                if table == "Binary":
                    rows = [[r[0], "not-executed"] for r in source.sql("SELECT `Name` FROM `Binary`", 1)]
                else:
                    rows = source.rows(table, len(names))
                self.connection.executemany("INSERT INTO `" + table + "` VALUES (" + ",".join("?" for _ in names) + ")", rows)

    def rows(self, table, columns):
        return self.sql("SELECT * FROM `" + table + "`", columns)

    def sql(self, statement, columns=0, streams=False):
        if streams:
            return [[name, proof] for name, proof in self.binaries.items()]
        cursor = self.connection.execute(statement)
        return [["" if v is None else str(v) for v in row] for row in cursor.fetchall()] if columns else []

    def add_binary(self, name, path):
        data = path.read_bytes()
        self.connection.execute("INSERT INTO Binary VALUES (?,?)", (name, "not-executed"))
        self.binaries[name] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


GET = C.WINFUNCTYPE(C.c_int, C.c_void_p, W.LPCWSTR, C.c_void_p, W.DWORD)
SET = C.WINFUNCTYPE(C.c_int, C.c_void_p, W.LPCWSTR, W.LPCWSTR)
SAVED = C.WINFUNCTYPE(C.c_int, C.c_void_p, W.LPCWSTR, W.LPCWSTR, W.LPCWSTR, C.c_void_p, W.DWORD)
OLD = SAVED
ATTRS = C.WINFUNCTYPE(W.DWORD, C.c_void_p, W.LPCWSTR, C.POINTER(W.DWORD))
EMPTY = C.WINFUNCTYPE(C.c_int, C.c_void_p, W.LPCWSTR)
LOG = C.WINFUNCTYPE(None, C.c_void_p, W.LPCWSTR)


class Api(C.Structure):
    _fields_ = [("context", C.c_void_p), ("get", GET), ("set", SET), ("saved", SAVED),
                ("old", OLD), ("attrs", ATTRS), ("empty", EMPTY), ("log", LOG)]


class FakeSession:
    def __init__(self):
        self.values = {"ProductName": "ChatChatBarDesktopSynthetic", "ProductVersion": "0.1.1100",
                       "ProductCode": "{11111111-1111-4111-8111-111111111111}",
                       "UpgradeCode": "{58E95FD1-709D-5813-9B5B-F518A4612BAE}",
                       "LocalAppDataFolder": "C:\\Synthetic\\Local\\", "WindowsFolder": "C:\\Windows\\"}
        self.saved_status = 0
        self.saved_path = "C:\\Synthetic\\Program"
        self.old_status = 1
        self.old_path = self.saved_path
        self.attributes = {}
        self.empty_status = 1
        self.unavailable = set()
        self.set_failure = False
        self.logs = []
        self.old_calls = 0
        def copy(pointer, text, capacity):
            if len(text) >= capacity:
                return -1
            buffer = C.create_unicode_buffer(text)
            C.memmove(pointer, buffer, C.sizeof(buffer))
            return 1
        def get(_, name, pointer, capacity):
            return -1 if name in self.unavailable else copy(pointer, self.values.get(name, ""), capacity)
        def set_value(_, name, text):
            if self.set_failure:
                return -1
            self.values[name] = text
            return 1
        def saved(_, name, version, product, pointer, capacity):
            return copy(pointer, self.saved_path, capacity) if self.saved_status == 1 else self.saved_status
        def old(_, product, name, upgrade, pointer, capacity):
            self.old_calls += 1
            return copy(pointer, self.old_path, capacity) if self.old_status == 1 else self.old_status
        def attrs(_, path, error):
            value, code = self.attributes.get(path.lower(), (0x10, 0))
            error[0] = code
            return value
        self.callbacks = (GET(get), SET(set_value), SAVED(saved), OLD(old), ATTRS(attrs),
                          EMPTY(lambda _, path: self.empty_status), LOG(lambda _, text: self.logs.append(text)))
        self.api = Api(None, *self.callbacks)


class DirectoryNative(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.library = C.WinDLL(os.environ["CCB_GUARD_TEST_DLL"])
        cls.entry = cls.library.CcbTestDirectory
        cls.entry.argtypes = [C.POINTER(Api), C.c_int]
        cls.entry.restype = W.UINT

    def invoke(self, session, revalidate=False):
        # No subprocess/installer or real API provider is permitted while native policy runs.
        with patch("subprocess.run", side_effect=AssertionError("INSTALLER_BOUNDARY_FORBIDDEN")):
            return self.entry(C.byref(session.api), int(revalidate))

    def test_fresh_default_and_explicit_safe_directory(self):
        for requested in ("", "D:\\Apps\\CCB"):
            with self.subTest(requested=bool(requested)):
                s = FakeSession(); s.values["INSTALLDIR"] = requested
                self.assertEqual(0, self.invoke(s))
                self.assertEqual(requested or "C:\\Synthetic\\Local\\ChatChatBarDesktopSyntheticApp", s.values["INSTALLDIR"])

    def test_desktop_profile_requires_its_own_upgrade_identity(self):
        s = FakeSession(); s.values["ProductName"] = "ChatChatBarDesktop"
        self.assertEqual(1603, self.invoke(s))
        s.values["UpgradeCode"] = "{8E4FDDBD-141F-5C1D-BFB2-1B48A6E46B81}"
        self.assertEqual(0, self.invoke(s))
        self.assertEqual("C:\\Synthetic\\Local\\ChatChatBarDesktopApp", s.values["INSTALLDIR"])

    def test_session_write_failure_does_not_become_success(self):
        s = FakeSession(); s.set_failure = True
        self.assertEqual(1603, self.invoke(s))
        self.assertNotIn("RESOLVED", "".join(s.logs))

    def test_maintenance_uninstall_and_upgrade_child_restore_saved_directory(self):
        for operation in ({"Installed": "1"}, {"Installed": "1", "REMOVE": "ALL"},
                          {"Installed": "1", "REMOVE": "ALL", "UPGRADINGPRODUCTCODE": "synthetic-new-product"}):
            with self.subTest(operation=operation):
                s = FakeSession(); s.values.update(operation); s.saved_status = 1
                self.assertEqual(0, self.invoke(s))
                self.assertEqual(s.saved_path, s.values["INSTALLDIR"])

    def test_missing_unreadable_and_stale_saved_records_refuse(self):
        for installed, status in (("1", 0), ("1", -1), ("", 1), ("", -1)):
            with self.subTest(installed=installed, status=status):
                s = FakeSession(); s.values["Installed"] = installed; s.saved_status = status
                self.assertEqual(1603, self.invoke(s))

    def test_registered_directory_physically_missing_refuses_before_changes(self):
        s = FakeSession(); s.values["Installed"] = "1"; s.saved_status = 1
        s.attributes[s.saved_path.lower()] = (0xffffffff, 3)
        self.assertEqual(1603, self.invoke(s))
        self.assertNotIn("INSTALLDIR", s.values)

    def test_requested_directory_conflict_never_silently_redirects(self):
        s = FakeSession(); s.values.update(Installed="1", INSTALLDIR="D:\\Other\\Program"); s.saved_status = 1
        self.assertEqual(1603, self.invoke(s)); self.assertIn("CONFLICT", s.logs[-1])

    def test_verified_new_contract_upgrade_and_legacy_refusal(self):
        for status in (1, 0, -1):
            with self.subTest(status=status):
                s = FakeSession(); s.values["JP_UPGRADABLE_FOUND"] = "{22222222-2222-4222-8222-222222222222}"; s.old_status = status
                self.assertEqual(0 if status == 1 else 1603, self.invoke(s))
                self.assertEqual(1, s.old_calls)

    def test_multiple_invalid_or_self_old_product_refuses_before_lookup(self):
        for products in ("invalid", "{22222222-2222-4222-8222-222222222222};{33333333-3333-4333-8333-333333333333}",
                         "{11111111-1111-4111-8111-111111111111}"):
            s = FakeSession(); s.values["JP_UPGRADABLE_FOUND"] = products
            self.assertEqual(1603, self.invoke(s)); self.assertEqual(0, s.old_calls)

    def test_unsafe_paths_refuse(self):
        for path in ("C:\\", "relative", "C:relative", "\\\\server\\share\\app", "\\\\?\\C:\\App", "C:\\A\\..\\B",
                     "C:\\A\\.\\B", "C:\\A\\B ", "C:\\A\\B.", "C:\\A\\CON", "C:\\A\\COM1.txt",
                     "C:\\A\\B:stream", "C:\\A\\\\B", "C:\\Windows\\App", "C:\\Synthetic\\Local\\ChatChatBarDesktop\\data"):
            with self.subTest(path=path):
                s = FakeSession(); s.values["INSTALLDIR"] = path
                self.assertEqual(1603, self.invoke(s))

    def test_reparse_file_denied_and_unavailable_drive_refuse(self):
        for path, value, error in (("c:\\synthetic", 0x410, 0), ("c:\\synthetic", 0x80, 0),
                                    ("c:\\synthetic", 0xffffffff, 5), ("c:\\", 0xffffffff, 3)):
            s = FakeSession(); s.attributes[path] = (value, error)
            self.assertEqual(1603, self.invoke(s))

    def test_fresh_existing_unknown_content_or_unreadable_target_refuses(self):
        for state in (0, -1):
            s = FakeSession(); s.empty_status = state
            self.assertEqual(1603, self.invoke(s))

    def test_revalidation_accepts_trailing_separator_but_refuses_changes(self):
        s = FakeSession(); self.assertEqual(0, self.invoke(s))
        s.values["INSTALLDIR"] += "\\"
        self.assertEqual(0, self.invoke(s, True))
        s.values["INSTALLDIR"] = "D:\\Other\\Program"
        self.assertEqual(1603, self.invoke(s, True))

    def test_revalidation_rechecks_unowned_content_and_reparse(self):
        for changed in ("nonempty", "reparse"):
            s = FakeSession(); self.assertEqual(0, self.invoke(s))
            if changed == "nonempty":
                s.empty_status = 0
            else:
                s.attributes["c:\\synthetic"] = (0x410, 0)
            self.assertEqual(1603, self.invoke(s, True))

    def test_property_and_public_identity_failures_are_redacted(self):
        for field in ("ProductName", "LocalAppDataFolder", "INSTALLDIR"):
            s = FakeSession(); s.unavailable.add(field)
            self.assertEqual(1603, self.invoke(s))
        s = FakeSession(); s.values["ProductName"] = "private-synthetic-name"
        self.assertEqual(1603, self.invoke(s))
        self.assertNotIn("private-synthetic-name", "".join(s.logs))


if __name__ == "__main__":
    unittest.main()
