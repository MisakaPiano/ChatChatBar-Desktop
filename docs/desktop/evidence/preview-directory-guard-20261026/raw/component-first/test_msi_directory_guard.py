"""No installation: read-only legacy MSI admission and native policy with injected fake APIs."""
import ctypes as C
from ctypes import wintypes as W
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch
import msi_safety


class DirectoryTables(unittest.TestCase):
    def test_safety1_legacy_package_is_not_current_directory_contract(self):
        fixture = os.environ["CCB_LEGACY_MSI_FIXTURE"]
        with msi_safety.Database(fixture) as db:
            with self.assertRaisesRegex(AssertionError, "DIRECTORY_CONTRACT_REQUIRED"):
                msi_safety.verify(db, "synthetic")


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

    def test_property_and_public_identity_failures_are_redacted(self):
        for field in ("ProductName", "LocalAppDataFolder", "INSTALLDIR"):
            s = FakeSession(); s.unavailable.add(field)
            self.assertEqual(1603, self.invoke(s))
        s = FakeSession(); s.values["ProductName"] = "private-synthetic-name"
        self.assertEqual(1603, self.invoke(s))
        self.assertNotIn("private-synthetic-name", "".join(s.logs))


if __name__ == "__main__":
    unittest.main()
