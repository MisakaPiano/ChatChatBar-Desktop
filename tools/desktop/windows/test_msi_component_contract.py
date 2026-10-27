"""Component identity regression against explicit synthetic MSI inputs; never installs a product."""
import os
import ctypes as C
from ctypes import wintypes as W
from pathlib import Path
import unittest
import msi_safety
from test_msi_directory_guard import SyntheticTables


class ComponentAdmission(unittest.TestCase):
    def test_directory2_marker_cannot_admit_known_shared_keypath_layout(self):
        path = Path(os.environ["CCB_DIRECTORY2_MSI_FIXTURE"])
        before = msi_safety.sha(path)
        self.assertEqual("cf7283ff4005ca5ce4aeeb00ee174c715e9edff1ee42c820731237608c15aba0", before)
        with msi_safety.Database(path) as db:
            # This exact accepted directory2 fixture has 217 components sharing one
            # version-dependent key path. Its directory marker is not compatibility.
            with self.assertRaisesRegex(AssertionError, "COMPONENT_CONTRACT_REQUIRED"):
                msi_safety.verify(db, "synthetic")
        self.assertEqual(before, msi_safety.sha(path))


class ComponentFixtures:
    def fresh(self):
        db = SyntheticTables()
        self.addCleanup(db.connection.close)
        return db

    def corrected(self):
        db = self.fresh()
        msi_safety.install_component_contract(db, dict(db.rows("Property", 2)))
        return db


class ComponentLayout(ComponentFixtures, unittest.TestCase):
    def test_unique_stable_keypaths_preserve_guids_files_and_directory_ownership(self):
        a, b = self.fresh(), self.fresh()
        properties = dict(b.rows("Property", 2))
        old_key, owner = msi_safety.validate_directory_record(b, properties)
        new_version = "0.1.1201"
        new_key = old_key.rsplit("\\", 1)[0] + "\\" + new_version
        b.sql("UPDATE Property SET Value='" + new_version + "' WHERE Property='ProductVersion'")
        b.sql("UPDATE Registry SET `Key`='" + new_key + "'")
        b.sql("UPDATE Component SET ComponentId='{12345678-1234-4234-8234-123456789ABC}' WHERE Component='" + owner + "'")
        original_components = a.rows("Component", 6)
        original_files = a.rows("File", 8)
        for db in (a, b):
            msi_safety.install_component_contract(db, dict(db.rows("Property", 2)))
            msi_safety.validate_component_contract(db, dict(db.rows("Property", 2)))
        self.assertEqual(original_components, a.rows("Component", 6))
        self.assertEqual(original_files, a.rows("File", 8))
        def ordinary(db):
            props = dict(db.rows("Property", 2))
            records, _, directory_owner = msi_safety.component_records(db, props)
            return {row[1]: entry[1:5] for row, entry in records if row[0] != directory_owner}
        self.assertEqual(ordinary(a), ordinary(b))
        for db in (a, b):
            records, _, directory_owner = msi_safety.component_records(db, dict(db.rows("Property", 2)))
            addresses = [tuple(x.lower() for x in entry[1:4]) for _, entry in records]
            self.assertEqual(len(addresses), len(set(addresses)))
            self.assertEqual(directory_owner, db.sql("SELECT Component_ FROM Registry WHERE Registry='CcbComponentProductOwnerV1'", 1)[0][0])

    def test_unrecognized_raw_layout_refuses_before_any_update(self):
        mutations = [
            "UPDATE Component SET Attributes='4'",
            "UPDATE Component SET ComponentId='{00000000-0000-4000-8000-000000000000}'",
            "UPDATE Component SET KeyPath='not-present'",
            "UPDATE Registry SET Root='2'",
            "UPDATE Registry SET Value='unexpected' WHERE Name='ProductCode'",
            "INSERT INTO Registry VALUES ('unknown','1','unknown','Key','value','unknown')",
        ]
        for sql in mutations:
            with self.subTest(sql=sql):
                db = self.fresh(); db.sql(sql)
                before = list(db.connection.iterdump())
                with self.assertRaises(AssertionError):
                    msi_safety.install_component_contract(db, dict(db.rows("Property", 2)))
                self.assertEqual(before, list(db.connection.iterdump()))

    def test_capability_marker_cannot_hide_modified_addresses_or_missing_owner(self):
        for sql in ["UPDATE Registry SET `Key`='changed' WHERE Name='Installed'",
                    "UPDATE Registry SET `Name`='ProductCode' WHERE Name='Installed'",
                    "UPDATE Registry SET Component_='unknown' WHERE Registry='CcbComponentProductOwnerV1'",
                    "DELETE FROM Registry WHERE Registry='CcbComponentProductOwnerV1'",
                    "INSERT INTO Registry VALUES ('extra','1','private','extra','x','unknown')"]:
            with self.subTest(sql=sql):
                db = self.corrected(); db.sql(sql)
                with self.assertRaises(AssertionError):
                    msi_safety.validate_component_contract(db, dict(db.rows("Property", 2)))


CLIENT = C.WINFUNCTYPE(W.UINT, W.LPCWSTR, W.DWORD, C.c_void_p, C.POINTER(W.DWORD))
COMPONENT_ENUM = C.WINFUNCTYPE(W.UINT, W.DWORD, C.c_void_p, C.POINTER(W.DWORD))


def micro_database(source, path):
    """Creates only explicit synthetic MSI metadata tables, never an install session."""
    lib = C.WinDLL("msi")
    lib.MsiOpenDatabaseW.argtypes = [W.LPCWSTR, W.LPCWSTR, C.POINTER(W.UINT)]
    handle = W.UINT()
    assert lib.MsiOpenDatabaseW(str(path), C.cast(C.c_void_p(3), W.LPCWSTR), C.byref(handle)) == 0
    try:
        assert lib.MsiDatabaseCommit(handle) == 0
    finally:
        lib.MsiCloseHandle(handle)
    columns = {
        "Property": ("Property", "Value"),
        "Component": ("Component", "ComponentId", "Directory_", "Attributes", "Condition", "KeyPath"),
        "Registry": ("Registry", "Root", "Key", "Name", "Value", "Component_"),
    }
    with msi_safety.Database(path, writable=True) as db:
        for table, names in columns.items():
            fields = []
            for index, name in enumerate(names):
                kind = "SHORT" if name in ("Root", "Attributes") else "CHAR(255)"
                fields.append("`" + name + "` " + kind + (" NOT NULL" if index == 0 else ""))
            db.sql("CREATE TABLE `" + table + "` (" + ",".join(fields) + " PRIMARY KEY `" + names[0] + "`)")
            for row in source.rows(table, len(names)):
                if table == "Property" and row[0] not in ("ProductName", "ProductVersion", "ProductCode", "CCB_COMPONENT_CONTRACT"):
                    continue
                values = [str(int(v)) if name in ("Root", "Attributes") else "'" + v.replace("'", "''") + "'" for name, v in zip(names, row)]
                db.sql("INSERT INTO `" + table + "` (" + ",".join("`" + n + "`" for n in names) + ") VALUES (" + ",".join(values) + ")")
        assert db.lib.MsiDatabaseCommit(db.handle) == 0


class ComponentNative(ComponentFixtures, unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.library = C.WinDLL(os.environ["CCB_GUARD_TEST_DLL"])
        cls.entry = cls.library.CcbTestComponentContract
        cls.entry.argtypes = [W.UINT, C.c_int, W.LPCWSTR, CLIENT]
        cls.entry.restype = C.c_int
        cls.directory = Path(os.environ["CCB_COMPONENT_TEST_DIRECTORY"]) / "component-only"
        cls.directory.mkdir(parents=True, exist_ok=False)
        cls.counter = 0

    def invoke(self, db, clients=(), installed=False, old="", inventory=None):
        type(self).counter += 1
        path = self.directory / ("metadata-%02d.msi" % type(self).counter)
        micro_database(db, path)
        def client(component, index, output, context):
            entries = clients.get(component, clients.get("default", ())) if isinstance(clients, dict) else clients
            if index >= len(entries): return 259
            status, product, ctx = entries[index]
            if status: return status
            context[0] = ctx
            text = C.create_unicode_buffer(product)
            C.memmove(output, text, C.sizeof(text))
            return 0
        callback = CLIENT(client)
        self.inventory_calls = []
        def component(index, output, context):
            self.inventory_calls.append(index)
            if index >= len(inventory): return 259
            status, code, ctx = inventory[index]
            if status: return status
            context[0] = ctx
            text = C.create_unicode_buffer(code)
            C.memmove(output, text, C.sizeof(text))
            return 0
        with msi_safety.Database(path) as native:
            if inventory is not None:
                entry = self.library.CcbTestComponentContractWithProof
                entry.argtypes = [W.UINT, C.c_int, W.LPCWSTR, CLIENT, COMPONENT_ENUM]
                entry.restype = C.c_int
                return entry(native.handle, int(installed), old, callback, COMPONENT_ENUM(component))
            return self.entry(native.handle, int(installed), old, callback)

    def test_windows10_missing_first_client_requires_independent_complete_absence(self):
        db = self.corrected()
        # The old provider must keep rejecting2 without independent evidence.
        self.assertEqual(0, self.invoke(db, [(2, "", 0)]))
        self.assertEqual(1, self.invoke(db, [(2, "", 0)], inventory=[]))
        self.assertEqual([0], self.inventory_calls)  # one cached complete snapshot
        component = db.rows("Component", 6)[0][1]
        self.assertEqual(0, self.invoke(db, [(2, "", 0)], inventory=[(0, component, 2)]))
        unrelated = "{99999999-9999-4999-8999-999999999999}"
        for context in (1, 2, 4):
            self.assertEqual(1, self.invoke(db, [(2, "", 0)], inventory=[(0, unrelated, context)]))
            self.assertEqual([0, 1], self.inventory_calls)

    def test_independent_snapshot_errors_or_malformed_rows_never_prove_absence(self):
        db = self.corrected()
        for status in (2, 3, 5, 87, 234, 1607, 1610):
            with self.subTest(status=status):
                self.assertEqual(0, self.invoke(db, [(2, "", 0)], inventory=[(status, "", 0)]))
        for code, context in (("bad-guid", 2), ("{99999999-9999-4999-8999-999999999999}", 0),
                              ("{99999999-9999-4999-8999-999999999999}", 7)):
            self.assertEqual(0, self.invoke(db, [(2, "", 0)], inventory=[(0, code, context)]))
        self.assertEqual(0, self.invoke(db, [(2, "", 0)], inventory=[(0, "{99999999-9999-4999-8999-999999999999}", 2)] * 65536))

    def test_missing_clients_during_maintenance_or_after_observed_client_still_refuse(self):
        db = self.corrected(); product = dict(db.rows("Property", 2))["ProductCode"]
        self.assertEqual(0, self.invoke(db, [(2, "", 0)], installed=True, inventory=[]))
        self.assertEqual([], self.inventory_calls)
        self.assertEqual(0, self.invoke(db, [(0, product, 2), (2, "", 0)], installed=True, inventory=[]))
        self.assertEqual([], self.inventory_calls)
        for status in (3, 5, 87, 234, 1607, 1610):
            self.assertEqual(0, self.invoke(db, [(status, "", 0)], inventory=[]))
            self.assertEqual([], self.inventory_calls)

    def test_upgrade_preserves_old_client_checks_and_proves_only_new_component_absence(self):
        db = self.corrected(); shared = db.rows("Component", 6)[0][1]
        old = "{22222222-2222-4222-8222-222222222222}"
        clients = {shared: [(0, old, 2)], "default": [(2, "", 0)]}
        self.assertEqual(1, self.invoke(db, clients, old=old, inventory=[(0, shared, 2)]))
        # Machine/managed/foreign clients are still rejected before any proof.
        for context in (1, 4):
            bad = {shared: [(0, old, context)], "default": [(2, "", 0)]}
            self.assertEqual(0, self.invoke(db, bad, old=old, inventory=[]))
        self.assertEqual(0, self.invoke(db, clients, old="{33333333-3333-4333-8333-333333333333}", inventory=[]))
        self.assertEqual(0, self.invoke(db, [(2, "", 0)], old=old, inventory=[(0, shared, 2)]))

    def test_layout_and_identity_failures_cannot_be_hidden_by_empty_snapshot(self):
        db = self.corrected(); db.sql("UPDATE Registry SET `Key`='changed' WHERE Name='Installed'")
        self.assertEqual(0, self.invoke(db, [(2, "", 0)], inventory=[]))
        self.assertEqual([], self.inventory_calls)
        self.assertEqual(0, self.invoke(self.corrected(), [(2, "", 0)], old="unknown;ambiguous", inventory=[]))
        self.assertEqual([], self.inventory_calls)

    def test_real_metadata_queries_accept_only_corrected_layout(self):
        self.assertEqual(1, self.invoke(self.corrected()))
        raw = self.fresh()
        raw.sql("INSERT INTO Property VALUES ('CCB_COMPONENT_CONTRACT','1')")
        self.assertEqual(0, self.invoke(raw))
        changed = self.corrected(); changed.sql("UPDATE Registry SET `Key`='changed' WHERE Name='Installed'")
        self.assertEqual(0, self.invoke(changed))

    def test_clients_require_exact_current_user_unmanaged_identity(self):
        db = self.corrected(); product = dict(db.rows("Property", 2))["ProductCode"]
        other = "{22222222-2222-4222-8222-222222222222}"
        self.assertEqual(0, self.invoke(db, [(0, product, 2)])) # fresh with partial/self registration
        self.assertEqual(1, self.invoke(db, [(0, product, 2)], installed=True))
        self.assertEqual(1, self.invoke(db, [(0, other, 2)], old=other))
        for entries in ([(0, other, 2)], [(0, product, 1)], [(0, product, 4)], [(5, "", 0)], [(1607, "", 0)]):
            with self.subTest(entries=entries):
                self.assertEqual(0, self.invoke(db, entries, installed=True))

    def test_duplicate_guids_missing_keypaths_and_ambiguous_old_identity_refuse(self):
        for sql in ("UPDATE Component SET ComponentId='{00000000-0000-4000-8000-000000000000}'",
                    "UPDATE Component SET KeyPath='missing'",
                    "DELETE FROM Registry WHERE Registry='CcbComponentProductOwnerV1'"):
            db = self.corrected(); db.sql(sql)
            self.assertEqual(0, self.invoke(db))
        self.assertEqual(0, self.invoke(self.corrected(), old="unknown;ambiguous"))


if __name__ == "__main__":
    unittest.main()
