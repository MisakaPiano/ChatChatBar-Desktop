"""Independent authored resource fixtures; no installer or user data access."""
import copy
import ctypes as C
import os
from pathlib import Path
import shutil
import unittest
import msi_safety


def fixture():
    """Small independent JPackage-shaped graph, not Inspector/production output."""
    return {
        "Directory": [["TARGETDIR", "", "SourceDir"], ["LocalAppDataFolder", "TARGETDIR", "."],
                      ["INSTALLDIR", "LocalAppDataFolder", "SYNTH~1|SyntheticApp"], ["app", "INSTALLDIR", "app"]],
        "Component": [["program", "{00000000-0000-0000-0000-000000000001}", "app", "260", "", "marker"],
                      ["owner", "{00000000-0000-0000-0000-000000000002}", "INSTALLDIR", "260", "", "directory"]],
        "File": [["programFile", "program", "PROGRA~1.JAR|program.jar", "8", "", "", "512", "1"]],
        "Feature": [["Main", "", "Main", "", "1", "1", "", "0"]],
        "FeatureComponents": [["Main", "program"], ["Main", "owner"]],
        "RemoveFile": [["emptyApp", "program", "", "app", "2"]],
        "CreateFolder": [], "Shortcut": [], "Icon": [], "MsiShortcutProperty": [],
        "AppSearch": [["SEARCH", "ownSearch"]],
        "RegLocator": [["ownSearch", "1", "Software\\ChatChatBar\\ChatChatBarDesktopSynthetic\\0.1.1110", "RM_RF" + "A" * 32, "18"]],
        "Signature": [], "Upgrade": [["{58E95FD1-709D-5813-9B5B-F518A4612BAE}", "", "0.1.1110", "", "257", "", "JP_UPGRADABLE_FOUND"],
                                    ["{58E95FD1-709D-5813-9B5B-F518A4612BAE}", "0.1.1110", "", "", "1", "", "JP_DOWNGRADABLE_FOUND"]],
    }


class ResourceGraph(unittest.TestCase):
    def validate(self, tables):
        return msi_safety.operation_resource_graph(tables, {"ProductName": "ChatChatBarDesktopSynthetic", "ProductVersion": "0.1.1110",
            "UpgradeCode": "{58E95FD1-709D-5813-9B5B-F518A4612BAE}"})

    def test_independent_full_graph_and_long_names(self):
        result = self.validate(fixture())
        self.assertEqual({"INSTALLDIR": "", "app": "app"}, result["directories"])
        self.assertEqual({"programFile": "app\\program.jar"}, result["files"])

    def test_directory_cycle_missing_parent_escape_and_alias_collision(self):
        for mutation in (lambda t: t["Directory"].__setitem__(3, ["app", "app", "app"]),
                         lambda t: t["Directory"].__setitem__(3, ["app", "unknown", "app"]),
                         lambda t: t["Directory"].__setitem__(3, ["app", "INSTALLDIR", ".."]),
                         lambda t: t["Directory"].append(["alias", "INSTALLDIR", "APP"])):
            with self.subTest(mutation=mutation):
                tables = fixture(); mutation(tables)
                with self.assertRaises(AssertionError): self.validate(tables)

    def test_unknown_resource_family_even_when_markers_are_known(self):
        for table in ("Environment", "ServiceControl", "RemoveRegistry", "MoveFile", "SelfReg", "CompLocator", "IniLocator"):
            with self.subTest(table=table):
                tables = fixture(); tables[table] = [["unexpected"]]
                with self.assertRaisesRegex(AssertionError, "RESOURCE_TABLE_UNSUPPORTED"): self.validate(tables)

    def test_file_duplicates_unmapped_feature_and_external_remove_directory(self):
        for mutation in (lambda t: t["File"].append(["other", "program", "PROGRAM.JAR", "8", "", "", "512", "2"]),
                         lambda t: t["FeatureComponents"].pop(),
                         lambda t: t["RemoveFile"][0].__setitem__(3, "LocalAppDataFolder"),
                         lambda t: t["RemoveFile"][0].__setitem__(2, "*"),
                         lambda t: t["File"][0].__setitem__(1, "unknown")):
            tables = fixture(); mutation(tables)
            with self.assertRaises(AssertionError): self.validate(tables)

    def test_search_and_upgrade_cannot_target_foreign_ownership(self):
        for mutation in (lambda t: t["RegLocator"][0].__setitem__(2, "Software\\Foreign"),
                         lambda t: t["RegLocator"][0].__setitem__(4, "2"),
                         lambda t: t["Upgrade"][0].__setitem__(0, "{00000000-0000-0000-0000-000000000001}"),
                         lambda t: t["Upgrade"][0].__setitem__(4, "261")):
            tables = fixture(); mutation(tables)
            with self.assertRaises(AssertionError): self.validate(tables)

    def test_fingerprint_preserves_values_and_is_independent_of_row_order(self):
        rows = [["a", "", "中"], ["b", "1", "text"]]
        digest = msi_safety.operation_table_digest(rows)
        self.assertEqual(digest, msi_safety.operation_table_digest(list(reversed(rows))))
        changed = copy.deepcopy(rows); changed[0][1] = "0"
        self.assertNotEqual(digest, msi_safety.operation_table_digest(changed))


def action_fixture():
    actions = [[n, "1", "CcbDirectoryGuard", n, ""] for n in ("CcbResolveInstallDirectory", "CcbValidateOperation")]
    actions += [["CcbDisallowDowngrade", "19", "", "A newer CCB Desktop version is installed. Use a newer installer.", ""],
                ["CcbUnsupportedInstallMode", "19", "", "Administrative and advertised installation is not supported.", ""],
                ["CcbSyntheticFailAfterRemoval", "19", "", "Synthetic installer rollback probe.", ""]]
    actions += [[n, "51", prop, value, ""] for n, prop, value in (
        ("JpSetARPINSTALLLOCATION", "ARPINSTALLLOCATION", "[INSTALLDIR]"),
        ("JpSetARPCOMMENTS", "ARPCOMMENTS", "ChatChatBar Desktop"),
        ("JpSetARPCONTACT", "ARPCONTACT", "ChatChatBar"), ("JpSetARPSIZE", "ARPSIZE", "100"))]
    common = [[n, "", str(pos)] for n, pos in (("FindRelatedProducts",25),("AppSearch",50),("CcbResolveInstallDirectory",60),
        ("CostInitialize",800),("FileCost",900),("CostFinalize",1000),("MigrateFeatureStates",1200))]
    common += [["CcbDisallowDowngrade", "JP_DOWNGRADABLE_FOUND", "26"]]
    execute = copy.deepcopy(common) + [[n, "", str(pos)] for n, pos in (("InstallValidate",1400),("InstallInitialize",1500),
        ("CcbValidateOperation",1501),("RemoveExistingProducts",1510),("ProcessComponents",1600),("InstallFiles",4000),
        ("WriteRegistryValues",5000),("InstallFinalize",6600))]
    execute += [["CcbSyntheticFailAfterRemoval", "CCB_DESKTOP_SYNTHETIC_FAIL_AFTER_REMOVE=1", "1511"]]
    execute += [[n, "Not Installed", str(1001+i)] for i,n in enumerate(("JpSetARPCOMMENTS","JpSetARPCONTACT","JpSetARPINSTALLLOCATION","JpSetARPSIZE"))]
    result = {"CustomAction": actions, "InstallExecuteSequence": execute, "InstallUISequence": common}
    for name in ("AdminExecuteSequence", "AdminUISequence", "AdvtExecuteSequence"):
        result[name] = [["CcbUnsupportedInstallMode", "", "1"]]
    return result


class ActionGraph(unittest.TestCase):
    def test_final_admission_after_validate_initialize_before_removal_and_scripts(self):
        msi_safety.validate_operation_actions(action_fixture(), "synthetic")

    def test_early_or_conditional_admission_late_rewrite_and_unknown_action_refuse(self):
        for mutation in (lambda t: t["InstallExecuteSequence"].__setitem__(8, ["InstallValidate", "", "1502"]),
                         lambda t: next(r for r in t["InstallExecuteSequence"] if r[0]=="CcbValidateOperation").__setitem__(2,"1399"),
                         lambda t: next(r for r in t["InstallExecuteSequence"] if r[0]=="CcbValidateOperation").__setitem__(1,"0"),
                         lambda t: next(r for r in t["InstallExecuteSequence"] if r[0]=="JpSetARPINSTALLLOCATION").__setitem__(2,"5001"),
                         lambda t: t["CustomAction"].append(["Unknown", "51", "INSTALLDIR", "C:\\Other", ""]),
                         lambda t: t["CustomAction"][0].__setitem__(4,"1")):
            tables = action_fixture(); mutation(tables)
            with self.assertRaises(AssertionError): msi_safety.validate_operation_actions(tables, "synthetic")

    def test_admin_and_advertise_cannot_omit_explicit_rejection(self):
        for name in ("AdminExecuteSequence", "AdminUISequence", "AdvtExecuteSequence"):
            tables = action_fixture(); tables[name] = []
            with self.assertRaisesRegex(AssertionError, "UNSUPPORTED_MODE_NOT_BLOCKED"):
                msi_safety.validate_operation_actions(tables, "synthetic")


def write_probe_fixture():
    tables=fixture();tables.update(action_fixture())
    tables['Property']=[['ProductName','ChatChatBarDesktopSynthetic'],['ProductVersion','0.1.1110'],
        ['UpgradeCode','{58E95FD1-709D-5813-9B5B-F518A4612BAE}'],['ProductCode','{11111111-1111-4111-8111-111111111111}'],
        ['CCB_SYNTHETIC_POSTWRITE_PROBE','1'],['CCB_SYNTHETIC_POSTWRITE_FILE','programFile'],
        ['CCB_SYNTHETIC_POSTWRITE_SHA256','c'*64],['CCB_SYNTHETIC_POSTWRITE_BINARY_SHA256','d'*64]]
    data='CCB_SYNTHETIC_AFTER_WRITES_V1|{11111111-1111-4111-8111-111111111111}|0.1.1110|[INSTALLDIR]|app\\program.jar|'+'c'*64
    tables['CustomAction'] += [['CcbSetSyntheticWriteProbeData','51','CcbSyntheticFailAfterWrites',data,''],
        ['CcbSyntheticFailAfterWrites','1025','CcbSyntheticWriteProbe','CcbSyntheticFailAfterWrites','']]
    condition='CCB_SYNTHETIC_FAIL_AFTER_WRITES=1 AND NOT Installed AND NOT REMOVE~="ALL"'
    tables['InstallExecuteSequence'] += [['CcbSetSyntheticWriteProbeData',condition,'5001'],['CcbSyntheticFailAfterWrites',condition,'5002']]
    return tables


class SyntheticPostwriteAuthoring(unittest.TestCase):
    def test_exact_synchronous_impersonated_deferred_probe_after_file_and_registry(self):
        msi_safety.validate_operation_actions(write_probe_fixture(),'synthetic')

    def test_probe_never_belongs_to_ordinary_profile(self):
        with self.assertRaisesRegex(AssertionError,'SYNTHETIC_WRITE_PROBE_ONLY'):
            msi_safety.validate_operation_actions(write_probe_fixture(),'desktop')

    def test_immediate_async_nonimpersonated_rollback_or_commit_types_refuse(self):
        for kind in ['1','1089','3073','1281','1537']:
            tables=write_probe_fixture();next(r for r in tables['CustomAction'] if r[0]=='CcbSyntheticFailAfterWrites')[1]=kind
            with self.assertRaises(AssertionError):msi_safety.validate_operation_actions(tables,'synthetic')

    def test_missing_late_or_unconditional_probe_is_not_equivalent(self):
        for index,value in [(1,''),(1,'CCB_SYNTHETIC_FAIL_AFTER_WRITES=1 AND NOT REMOVE~="ALL"'),(2,'4001'),(2,'6601')]:
            tables=write_probe_fixture();next(r for r in tables['InstallExecuteSequence'] if r[0]=='CcbSyntheticFailAfterWrites')[index]=value
            with self.assertRaises(AssertionError):msi_safety.validate_operation_actions(tables,'synthetic')

    def test_data_identity_payload_and_digest_cannot_drift(self):
        tables=write_probe_fixture();next(r for r in tables['CustomAction'] if r[0]=='CcbSetSyntheticWriteProbeData')[3]='UNVERIFIED'
        with self.assertRaises(AssertionError):msi_safety.validate_operation_actions(tables,'synthetic')
        for key,value in [('CCB_SYNTHETIC_POSTWRITE_FILE','unknown'),('CCB_SYNTHETIC_POSTWRITE_SHA256','not-a-digest')]:
            tables=write_probe_fixture();next(r for r in tables['Property'] if r[0]==key)[1]=value
            with self.assertRaises(AssertionError):msi_safety.validate_operation_actions(tables,'synthetic')


class NativeOperationContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.lib = C.WinDLL(os.environ["CCB_GUARD_TEST_DLL"])
        cls.lib.CcbTestSealedTables.argtypes = [C.c_uint]
        cls.lib.CcbTestSealedTables.restype = C.c_int
        cls.lib.CcbTestLocator.argtypes = [C.c_wchar_p, C.c_wchar_p]
        cls.lib.CcbTestPhysical.argtypes = [C.c_wchar_p, C.c_int, C.c_wchar_p]
        cls.lib.CcbTestSharedLayout.argtypes = [C.c_uint, C.c_uint]
        cls.lib.CcbTestProductState.argtypes = [C.c_uint, C.c_wchar_p]
        cls.lib.CcbTestAuthoredComponents.argtypes = [C.c_uint]
        cls.lib.CcbTestResolvedTargets.argtypes = [C.c_uint,C.c_wchar_p]
        cls.lib.CcbTestFullLocalPlan.argtypes = [C.c_uint]

    def test_actual_metadata_contract_and_native_hash_agree(self):
        with msi_safety.Database(os.environ["CCB_OPERATION_MSI"]) as db:
            self.assertEqual(1, self.lib.CcbTestSealedTables(db.handle))

    def test_postwrite_condition_uses_installer_fresh_upgrade_only_semantics(self):
        # Use the real condition evaluator on a restricted session. No action,
        # file costing, registration change or deferred callback is executed.
        lib=C.WinDLL("msi");ole=C.WinDLL("ole32")
        lib.MsiOpenPackageExW.argtypes=[C.c_wchar_p,C.c_uint,C.POINTER(C.c_uint)]
        lib.MsiSetPropertyW.argtypes=[C.c_uint,C.c_wchar_p,C.c_wchar_p]
        lib.MsiEvaluateConditionW.argtypes=[C.c_uint,C.c_wchar_p]
        condition='CCB_SYNTHETIC_FAIL_AFTER_WRITES=1 AND NOT Installed AND NOT REMOVE~="ALL"'
        session=C.c_uint();initialized=ole.CoInitializeEx(None,2)
        self.assertIn(initialized,(0,1));prior=lib.MsiSetInternalUI(2,None)
        try:
            self.assertEqual(0,lib.MsiOpenPackageExW(os.environ["CCB_OPERATION_MSI"],1,C.byref(session)))
            for enabled,installed,remove,expected in (
                ('1','','',1),('1','','ALL',0),('1','','all',0),
                ('1','1','',0),('1','1','ALL',0),('','','',0),
                ('0','','',0),('1','0','',0)):
                with self.subTest(enabled=enabled,installed=installed,remove=remove):
                    for name,value in (('CCB_SYNTHETIC_FAIL_AFTER_WRITES',enabled),('Installed',installed),('REMOVE',remove)):
                        self.assertEqual(0,lib.MsiSetPropertyW(session,name,value))
                    self.assertEqual(expected,lib.MsiEvaluateConditionW(session,condition))
        finally:
            if session.value:lib.MsiCloseHandle(session)
            lib.MsiSetInternalUI(prior,None);ole.CoUninitialize()

    def test_restricted_installer_session_retains_authored_seals_after_costing(self):
        # Documented restricted handle: cannot change the machine or run DLL CAs.
        # This verifies session table adaptation, never installation/admission.
        lib=C.WinDLL("msi"); ole=C.WinDLL("ole32")
        lib.MsiOpenPackageExW.argtypes=[C.c_wchar_p,C.c_uint,C.POINTER(C.c_uint)]
        lib.MsiSetPropertyW.argtypes=[C.c_uint,C.c_wchar_p,C.c_wchar_p]
        lib.MsiDoActionW.argtypes=[C.c_uint,C.c_wchar_p]
        lib.MsiSetTargetPathW.argtypes=[C.c_uint,C.c_wchar_p,C.c_wchar_p]
        lib.MsiGetActiveDatabase.restype=C.c_uint
        path=Path(os.environ["CCB_OPERATION_MSI"]); before=msi_safety.sha(path)
        session=C.c_uint();db=0;initialized=ole.CoInitializeEx(None,2)
        self.assertIn(initialized,(0,1)); prior=lib.MsiSetInternalUI(2,None)
        try:
            self.assertEqual(0,lib.MsiOpenPackageExW(str(path),1,C.byref(session)))
            target=Path(__file__).resolve().parents[3]/"app/desktopApp/build/op1-costing-target-probe"
            self.assertFalse(target.exists())
            self.assertEqual(0,lib.MsiSetPropertyW(session,"INSTALLDIR",str(target)))
            db=lib.MsiGetActiveDatabase(session); self.assertTrue(db)
            self.assertEqual(1,self.lib.CcbTestSealedTables(db))
            self.assertEqual(1,self.lib.CcbTestAuthoredComponents(db))
            self.assertEqual(0,lib.MsiSetPropertyW(session,"CCB_SYNTHETIC_TRANSIENT","INVENTED"))
            self.assertEqual(1,self.lib.CcbTestSealedTables(db))
            for action in ("CostInitialize","FileCost","CostFinalize"):
                self.assertEqual(0,lib.MsiDoActionW(session,action))
                self.assertEqual(1,self.lib.CcbTestSealedTables(db))
                self.assertEqual(1,self.lib.CcbTestAuthoredComponents(db))
            self.assertEqual(1,self.lib.CcbTestResolvedTargets(session,str(target)))
            self.assertEqual(1,self.lib.CcbTestFullLocalPlan(session))
            # Actual resolved child target changes, while authored table hashes stay
            # valid. It must be caught by the separate final-target check.
            self.assertEqual(0,lib.MsiSetTargetPathW(session,"dir40f84b56068d341da01a768174eb5e59",str(target.parent/"op1-escaped-target-probe")))
            self.assertEqual(1,self.lib.CcbTestSealedTables(db))
            self.assertEqual(0,self.lib.CcbTestResolvedTargets(session,str(target)))
            self.assertFalse(target.exists())
        finally:
            if db:lib.MsiCloseHandle(db)
            if session.value:lib.MsiCloseHandle(session)
            lib.MsiSetInternalUI(prior,None);ole.CoUninitialize()
        self.assertEqual(before,msi_safety.sha(path))

    def test_changed_table_or_unknown_family_cannot_use_old_authored_contract(self):
        root = Path(os.environ["CCB_COMPONENT_TEST_DIRECTORY"]); root.mkdir(exist_ok=True)
        target = root / "operation-mutation.msi"
        shutil.copyfile(os.environ["CCB_OPERATION_MSI"], target)
        original = msi_safety.sha(target)
        for sql in ("UPDATE `Directory` SET `DefaultDir`='escape' WHERE `Directory`='INSTALLDIR'",
                    "UPDATE `InstallExecuteSequence` SET `Sequence`=1399 WHERE `Action`='CcbValidateOperation'",
                    "CREATE TABLE `UnexpectedResource` (`Name` CHAR(32) NOT NULL PRIMARY KEY `Name`)",
                    "CREATE TABLE `_Property` (`Name` CHAR(32) NOT NULL PRIMARY KEY `Name`)",
                    "CREATE TABLE `CostAdjuster` (`Name` CHAR(32) NOT NULL PRIMARY KEY `Name`)",
                    "INSERT INTO `Component` (`Component`,`ComponentId`,`Directory_`,`Attributes`,`Condition`,`KeyPath`) VALUES ('__UNKNOWN65','Temporary Id','WindowsFolder',260,'','')"):
            with self.subTest(sql=sql), msi_safety.Database(target, writable=True) as db:
                db.sql(sql)
                self.assertEqual(0, self.lib.CcbTestSealedTables(db.handle))
            # No commit: original bytes must remain unchanged after handle release.
            self.assertEqual(original, msi_safety.sha(target))

    def test_locator_defaults_custom_unknown_members_and_invalid_present_input(self):
        for text, valid, custom in (
            ('{"formatVersion":1,"mode":"DEFAULT"}', True, ""),
            ('{"formatVersion":1,"mode":"CUSTOM","customRoot":"C:\\\\Synthetic\\\\Data"}', True, "C:\\Synthetic\\Data"),
            ('{"formatVersion":1,"mode":"DEFAULT","future":{"array":[1,true,null,"text"]}}', True, ""),
            ('{"formatVersion":1,"mode":"DEFAULT","customRoot":null}', False, ""),
            ('{"formatVersion":1,"mode":"DEFAULT","customRoot":"C:\\\\Data"}', False, ""),
            ('{"formatVersion":1,"mode":"CUSTOM"}', False, ""),
            ('{"formatVersion":1,"mode":"CUSTOM","customRoot":123}', False, ""),
            ('{"formatVersion":2,"mode":"DEFAULT"}', False, ""),
            ('{"formatVersion":1,"mode":"DEFAULT","mode":"CUSTOM"}', False, ""),
            ('{"formatVersion":1,"mode":"DEFAULT"} trailing', False, ""),
            ('{"formatVersion":1,"mode":"DEFAULT",}', False, ""),
            ('{}', False, ""), ('not-json', False, "")):
            with self.subTest(text=text):
                out = C.create_unicode_buffer(1024)
                self.assertEqual(int(valid), self.lib.CcbTestLocator(text, out))
                if valid: self.assertEqual(custom, out.value)

    def test_readonly_physical_identity_existing_and_missing_paths(self):
        root = Path(os.environ["CCB_COMPONENT_TEST_DIRECTORY"]) / "physical-identity"
        root.mkdir(parents=True, exist_ok=False)
        file = root / "synthetic.txt"; file.write_bytes(b"INVENTED_OPERATION_CONTRACT")
        before = msi_safety.sha(file)
        for path, file_allowed in ((root, 0), (file, 1), (root / "missing" / "program", 0)):
            out = C.create_unicode_buffer(1024)
            self.assertEqual(1, self.lib.CcbTestPhysical(str(path), file_allowed, out))
            self.assertEqual(str(path).casefold(), out.value.casefold())
        for path in ("C:\\Synthetic\\..\\Data", "C:\\Synthetic:stream", "C:\\", "\\\\server\\share\\x"):
            self.assertEqual(0, self.lib.CcbTestPhysical(path, 0, C.create_unicode_buffer(1024)))
        self.assertEqual(before, msi_safety.sha(file))
        alias = root / "hardlink.txt"; os.link(file, alias)
        self.assertEqual(0, self.lib.CcbTestPhysical(str(alias), 1, C.create_unicode_buffer(1024)))
        self.assertEqual(before, msi_safety.sha(file))

    def test_production_exports_and_no_inventory_imports(self):
        import build_directory_guard
        path = Path(os.environ["CCB_GUARD_PRODUCTION_DLL"])
        self.assertEqual({"CcbResolveInstallDirectory", "CcbValidateOperation"}, build_directory_guard.pe_exports(path))
        data = path.read_bytes()
        for name in (b"MsiEnumClientsExW", b"MsiEnumComponentsExW", b"MsiConfigureProduct", b"MsiInstallProduct", b"RegSetValue", b"DeleteFile", b"RemoveDirectory"):
            self.assertNotIn(name, data)

    def test_required_exact_product_query_errors_remain_unknown(self):
        for status, value, expected in ((1605, "", 0), (0, "5", 1), (0, "1", -1), (0, "", -1),
                                        (2, "", -1), (87, "", -1), (5, "", -1), (1610, "", -1)):
            self.assertEqual(expected, self.lib.CcbTestProductState(status, value))

    def test_common_guid_compares_resource_addresses_not_versioned_row_ids(self):
        # Historical frozen full packages are read as metadata only, not installed.
        with msi_safety.Database(os.environ["CCB_LAYOUT_OLD_MSI"]) as old, msi_safety.Database(os.environ["CCB_LAYOUT_NEW_MSI"]) as new:
            self.assertEqual(1, self.lib.CcbTestSharedLayout(new.handle, old.handle))
        root = Path(os.environ["CCB_COMPONENT_TEST_DIRECTORY"])
        root.mkdir(exist_ok=True); changed = root / "layout-change.msi"
        shutil.copyfile(os.environ["CCB_LAYOUT_NEW_MSI"], changed)
        before = msi_safety.sha(changed)
        for mutation in ("UPDATE `File` SET `FileName`='unexpected.jar'",
                         "UPDATE `Directory` SET `DefaultDir`='unexpected' WHERE `Directory`='dir40f84b56068d341da01a768174eb5e59'",
                         "UPDATE `Registry` SET `Name`='Foreign' WHERE `Name`='Installed'"):
            with msi_safety.Database(os.environ["CCB_LAYOUT_OLD_MSI"]) as old, msi_safety.Database(changed, writable=True) as new:
                new.sql(mutation)
                self.assertEqual(0, self.lib.CcbTestSharedLayout(new.handle, old.handle))
            self.assertEqual(before, msi_safety.sha(changed))


if __name__ == "__main__": unittest.main()
