"""Build-time correction of the verified JDK17 MSI layout, before signing/distribution. No installation."""
import argparse
import ctypes as C
from ctypes import wintypes as W
import hashlib
import json
import os
from pathlib import Path
import shutil
import re
import importlib.util
import uuid

if not __debug__:
    raise RuntimeError("MSI_SAFETY_REQUIRES_ENABLED_VALIDATION")

PRODUCTS = {
    "desktop": ("ChatChatBarDesktop", "8e4fddbd-141f-5c1d-bfb2-1b48a6e46b81"),
    "synthetic": ("ChatChatBarDesktopSynthetic", "58e95fd1-709d-5813-9b5b-f518a4612bae"),
}

# Finite JDK17 resource vocabulary. Unrecognized nonempty tables are not silently ignored.
OP_COLUMNS = {"Property": 2, "Directory": 3, "Component": 6, "File": 8, "Registry": 6,
    "Feature": 8, "FeatureComponents": 2, "RemoveFile": 5, "CreateFolder": 2,
    "Shortcut": 16, "Icon": 2, "MsiShortcutProperty": 4, "AppSearch": 2,
    "RegLocator": 5, "Signature": 9, "Upgrade": 7, "CustomAction": 5,
    "InstallExecuteSequence": 3, "InstallUISequence": 3, "AdminExecuteSequence": 3,
    "AdminUISequence": 3, "AdvtExecuteSequence": 3, "Media": 6, "MsiFileHash": 6,
    "Binary": 2, "WixRemoveFolderEx": 4}
OP_META = {"_Validation", "_Tables", "_Columns", "_Streams", "_Storages",
           "CcbOperationTable", "CcbOperationStream"}


def operation_table_digest(rows):
    # Same length-prefixed UTF-16LE wire used by the native read-only verifier.
    # NUL and surrogate values are not supported authored metadata.
    encoded = []
    for row in rows:
        assert all("\0" not in v for v in row), "RESOURCE_NUL_REFUSED"
        encoded.append("".join(str(len(v.encode("utf-16-le")) // 2) + ":" + v for v in row))
    encoded.sort(key=lambda v: tuple(int.from_bytes(v.encode("utf-16-le")[i:i+2], "little") for i in range(0, len(v.encode("utf-16-le")), 2)))
    return hashlib.sha256("".join(v + "\n" for v in encoded).encode("utf-16-le")).hexdigest()


def operation_resource_graph(tables, properties):
    for name, rows in tables.items():
        assert name in OP_COLUMNS or name in OP_META or not rows, "RESOURCE_TABLE_UNSUPPORTED:" + name
    def unique(name, index=0):
        rows = tables.get(name, [])
        result = {r[index]: r for r in rows}
        assert len(result) == len(rows), "RESOURCE_ID_DUPLICATE:" + name
        assert all(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.]{0,71}", key) for key in result), "RESOURCE_ID_UNSUPPORTED:" + name
        return result
    def segment(value):
        assert value.count(":") <= 1, "DIRECTORY_SOURCE_SYNTAX_UNSUPPORTED"
        target = value.split(":")[0]
        assert target.count("|") <= 1, "SHORT_LONG_NAME_UNSUPPORTED"
        name = target.split("|")[-1]
        assert name and name not in (".", "..") and not re.search(r'[\\/:*?"<>|\x00-\x1f%\[\]]', name) and name[-1] not in ". ", "RESOURCE_NAME_UNSUPPORTED"
        assert not re.fullmatch(r"(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?", name), "RESOURCE_DEVICE_NAME"
        return name
    directories = unique("Directory"); components = unique("Component"); shortcuts = unique("Shortcut")
    assert properties["ProductName"] != PRODUCTS["synthetic"][0] or not shortcuts, "SYNTHETIC_SHORTCUT_REFUSED"
    assert 1 < len(components) <= 1024 and "INSTALLDIR" in directories, "RESOURCE_COMPONENT_OR_ROOT_MISSING"
    assert directories["TARGETDIR"] == ["TARGETDIR", "", "SourceDir"]
    assert directories["LocalAppDataFolder"] == ["LocalAppDataFolder", "TARGETDIR", "."]
    assert directories["INSTALLDIR"][1] == "LocalAppDataFolder", "PROGRAM_ROOT_PARENT_UNSUPPORTED"
    segment(directories["INSTALLDIR"][2])
    paths = {"INSTALLDIR": ""}
    def path(key, stack=()):
        assert key not in stack and key in directories and len(stack) < 64, "DIRECTORY_GRAPH_INVALID"
        if key in paths: return paths[key]
        row = directories[key]
        parent = path(row[1], (*stack, key))
        paths[key] = parent + ("\\" if parent else "") + segment(row[2])
        return paths[key]
    external = {"DesktopFolder", "ProgramMenuFolder"}
    shortcut_directories = {}
    for key, row in directories.items():
        if key == "DesktopFolder":
            shortcut_directories[key] = {"knownFolder": "DesktopFolder", "relative": ""}
        elif row[1] == "ProgramMenuFolder":
            assert shortcuts and segment(row[2]) == properties["ProductName"], "MENU_LEAF_IDENTITY_UNSUPPORTED"
            shortcut_directories[key] = {"knownFolder": "ProgramMenuFolder", "relative": properties["ProductName"]}
    for key in directories:
        if key in ("TARGETDIR", "LocalAppDataFolder"): continue
        if key in external:
            assert directories[key][1:] == ["TARGETDIR", "."], "SHORTCUT_DIRECTORY_UNSUPPORTED"
        elif key in shortcut_directories: continue
        else: path(key)
    assert len({v.casefold() for v in paths.values()}) == len(paths), "DIRECTORY_ALIAS_COLLISION"
    choices = {"DesktopFolder": "JP_INSTALL_DESKTOP_SHORTCUT", "ProgramMenuFolder": "JP_INSTALL_STARTMENU_SHORTCUT"}
    for row in components.values():
        assert row[3] == "260", "COMPONENT_ATTRIBUTES_UNSUPPORTED"
        if row[2] in paths:
            assert not row[4], "PROGRAM_COMPONENT_CONDITION_UNSUPPORTED"
        else:
            assert row[2] in shortcut_directories, "COMPONENT_DIRECTORY_UNSUPPORTED"
            assert row[4] == choices[shortcut_directories[row[2]]["knownFolder"]], "SHORTCUT_CHOICE_UNSUPPORTED"
    assert all(not p or p.split("\\")[0].casefold() not in ("userdata", "portable.flag") for p in paths.values()), "PORTABLE_DATA_RESOURCE_REFUSED"
    files = unique("File"); targets = {}
    for key, row in files.items():
        assert row[1] in components, "FILE_OWNER_UNKNOWN"
        assert components[row[1]][2] in paths, "PROGRAM_FILE_EXTERNAL_OWNER"
        base = paths[components[row[1]][2]]
        target = base + ("\\" if base else "") + segment(row[2])
        assert target.casefold() not in {v.casefold() for v in targets.values()}, "FILE_TARGET_COLLISION"
        assert target.casefold() not in {v.casefold() for v in paths.values()}, "FILE_DIRECTORY_COLLISION"
        assert target.split("\\")[0].casefold() not in ("userdata", "portable.flag"), "PORTABLE_DATA_RESOURCE_REFUSED"
        assert row[3].isdigit() and int(row[3]) > 0 and row[7].isdigit(), "FILE_METADATA_UNSUPPORTED"
        targets[key] = target
    features = unique("Feature"); mapping = tables.get("FeatureComponents", [])
    assert features and all(not r[1] and r[5] == "1" and r[7] == "0" for r in features.values()), "FEATURE_PLAN_UNSUPPORTED"
    assert len({tuple(r) for r in mapping}) == len(mapping) and all(r[0] in features and r[1] in components for r in mapping), "FEATURE_MAP_INVALID"
    assert {r[1] for r in mapping} == set(components), "FEATURE_COMPONENT_CLOSURE_INCOMPLETE"
    for row in tables.get("RemoveFile", []):
        assert row[1] in components and not row[2] and row[4] == "2", "REMOVE_TARGET_UNSUPPORTED"
        assert components[row[1]][2] == row[3], "REMOVE_COMPONENT_DIRECTORY_MISMATCH"
        assert row[3] in paths or (row[3] in shortcut_directories and shortcut_directories[row[3]]["knownFolder"] == "ProgramMenuFolder"), "REMOVE_TARGET_UNSUPPORTED"
    for row in tables.get("CreateFolder", []):
        assert row[0] in paths and row[1] in components and components[row[1]][2] == row[0], "CREATE_FOLDER_UNSUPPORTED"
    shortcut_graph = {}
    for key, row in shortcuts.items():
        assert row[1] in shortcut_directories and row[3] in components and components[row[3]][2] == row[1], "SHORTCUT_COMPONENT_OWNER_UNSUPPORTED"
        match = re.fullmatch(r"\[#([A-Za-z_][A-Za-z0-9_.]{0,71})\]", row[4])
        assert match and targets.get(match[1]) == properties["ProductName"] + ".exe", "SHORTCUT_TARGET_UNSUPPORTED"
        assert not any(row[5:11]) and row[11] == "INSTALLDIR" and not any(row[12:]), "SHORTCUT_ARGUMENTS_OR_ADVERTISEMENT_UNSUPPORTED"
        assert segment(row[2]) == properties["ProductName"], "SHORTCUT_NAME_IDENTITY_UNSUPPORTED"
        shortcut_graph[key] = {"directory": row[1], "name": properties["ProductName"] + ".lnk", "targetFile": match[1],
                               "component": row[3], "choiceProperty": components[row[3]][4]}
    external_components = {r[0] for r in components.values() if r[2] not in paths}
    assert external_components == {r["component"] for r in shortcut_graph.values()}, "SHORTCUT_COMPONENT_CLOSURE_INCOMPLETE"
    if shortcuts:
        assert len(shortcuts) == 2 and len({r["directory"] for r in shortcut_graph.values()}) == 2, "FIXED_SHORTCUT_SET_REQUIRED"
        assert {shortcut_directories[r["directory"]]["knownFolder"] for r in shortcut_graph.values()} == external, "FIXED_SHORTCUT_SET_REQUIRED"
        assert set(shortcut_directories) == {r["directory"] for r in shortcut_graph.values()}, "UNUSED_SHORTCUT_DIRECTORY_REFUSED"
    assert not tables.get("MsiShortcutProperty"), "SHORTCUT_PROPERTY_UNSUPPORTED"
    searches = unique("AppSearch"); locators = unique("RegLocator")
    assert not tables.get("Signature") and {r[1] for r in searches.values()} == set(locators), "SEARCH_GRAPH_UNSUPPORTED"
    own_key = "Software\\ChatChatBar\\" + properties["ProductName"] + "\\" + properties["ProductVersion"]
    for row in locators.values():
        assert row[1:3] == ["1", own_key] and re.fullmatch(r"RM_RF[0-9A-Fa-f]{32}", row[3]) and row[4] == "18", "SEARCH_OWNER_UNSUPPORTED"
    upgrades = tables.get("Upgrade", [])
    assert len(upgrades) == 2, "UPGRADE_GRAPH_UNSUPPORTED"
    assert {tuple(r[1:]) for r in upgrades} == {( "", properties["ProductVersion"], "", "257", "", "JP_UPGRADABLE_FOUND"),
        (properties["ProductVersion"], "", "", "1", "", "JP_DOWNGRADABLE_FOUND")}, "UPGRADE_RANGE_UNSUPPORTED"
    assert all(r[0].upper() == properties["UpgradeCode"].upper() for r in upgrades), "UPGRADE_FAMILY_UNSUPPORTED"
    return {"directories": paths, "files": targets, "shortcutDirectories": shortcut_directories, "shortcuts": shortcut_graph}


def operation_tables(db):
    names = {r[0] for r in db.rows("_Tables", 1)}
    result = {}
    for name in names - OP_META:
        assert name in OP_COLUMNS, "RESOURCE_TABLE_UNSUPPORTED:" + name
        if name not in ("Binary", "Icon"): result[name] = db.rows(name, OP_COLUMNS[name])
    return result


def validate_operation_actions(tables, profile):
    properties = dict(tables.get("Property", []))
    write_probe = properties.get("CCB_SYNTHETIC_POSTWRITE_PROBE")
    assert write_probe in (None, "1"), "SYNTHETIC_WRITE_PROBE_MARKER_UNSUPPORTED"
    assert not write_probe or profile == "synthetic", "SYNTHETIC_WRITE_PROBE_ONLY"
    actions = {r[0]: r for r in tables["CustomAction"]}
    expected = {"CcbResolveInstallDirectory": ["1", "CcbDirectoryGuard", "CcbResolveInstallDirectory"],
                "CcbValidateOperation": ["1", "CcbDirectoryGuard", "CcbValidateOperation"],
                "CcbUnsupportedInstallMode": ["19", "", "Administrative and advertised installation is not supported."],
                "CcbDisallowDowngrade": ["19", "", "A newer CCB Desktop version is installed. Use a newer installer."]}
    if profile == "synthetic": expected["CcbSyntheticFailAfterRemoval"] = ["19", "", "Synthetic installer rollback probe."]
    if write_probe:
        graph = operation_resource_graph(tables, properties)
        file_id = properties.get("CCB_SYNTHETIC_POSTWRITE_FILE", "")
        digest = properties.get("CCB_SYNTHETIC_POSTWRITE_SHA256", "")
        assert file_id in graph["files"] and graph["files"][file_id].lower().endswith(".jar") and re.fullmatch(r"[a-f0-9]{64}", digest), "SYNTHETIC_WRITE_PAYLOAD_UNVERIFIED"
        assert re.fullmatch(r"[a-f0-9]{64}", properties.get("CCB_SYNTHETIC_POSTWRITE_BINARY_SHA256", "")), "SYNTHETIC_WRITE_BINARY_UNVERIFIED"
        data = "|".join(("CCB_SYNTHETIC_AFTER_WRITES_V1", properties["ProductCode"], properties["ProductVersion"], "[INSTALLDIR]", graph["files"][file_id], digest))
        expected["CcbSetSyntheticWriteProbeData"] = ["51", "CcbSyntheticFailAfterWrites", data]
        expected["CcbSyntheticFailAfterWrites"] = ["1025", "CcbSyntheticWriteProbe", "CcbSyntheticFailAfterWrites"]
    for name, value in (("JpSetARPINSTALLLOCATION", ["51", "ARPINSTALLLOCATION", "[INSTALLDIR]"]),
                        ("JpSetARPCOMMENTS", ["51", "ARPCOMMENTS", "ChatChatBar Desktop"]),
                        ("JpSetARPCONTACT", ["51", "ARPCONTACT", "ChatChatBar"])):
        expected[name] = value
    assert actions.get("JpSetARPSIZE", [])[1:3] == ["51", "ARPSIZE"] and actions["JpSetARPSIZE"][3].isdigit(), "ARP_SIZE_ACTION_UNSUPPORTED"
    expected["JpSetARPSIZE"] = actions["JpSetARPSIZE"][1:4]
    assert all(len(r) == 5 and not r[4] for r in actions.values()), "CUSTOM_ACTION_EXTENDED_TYPE_UNSUPPORTED"
    assert {k: r[1:4] for k, r in actions.items()} == expected, "CUSTOM_ACTION_GRAPH_UNSUPPORTED"
    standard = {"FindRelatedProducts", "AppSearch", "ValidateProductID", "CostInitialize", "FileCost", "CostFinalize", "MigrateFeatureStates",
        "InstallValidate", "InstallInitialize", "RemoveExistingProducts", "ProcessComponents", "UnpublishFeatures", "RemoveRegistryValues", "RemoveFiles",
        "InstallFiles", "WriteRegistryValues", "RegisterUser", "RegisterProduct", "PublishFeatures", "PublishProduct", "InstallFinalize", "ExecuteAction",
        "RemoveShortcuts", "CreateShortcuts", "CreateFolders", "RemoveFolders", "InstallAdminPackage", "InstallExecute", "InstallExecuteAgain"}
    for table in ("InstallExecuteSequence", "InstallUISequence", "AdminExecuteSequence", "AdminUISequence", "AdvtExecuteSequence"):
        rows = tables.get(table, [])
        assert len({r[0] for r in rows}) == len(rows) and len({r[2] for r in rows}) == len(rows), "ACTION_OR_SEQUENCE_DUPLICATE"
        assert all(r[0] in standard or r[0] in actions for r in rows), "ACTION_UNKNOWN"
        assert all(not r[1] for r in rows if r[0] in standard), "STANDARD_ACTION_CONDITION_UNSUPPORTED"
        if table in ("AdminExecuteSequence", "AdminUISequence", "AdvtExecuteSequence"):
            assert {r[0]: r[1:] for r in rows}.get("CcbUnsupportedInstallMode") == ["", "1"], "UNSUPPORTED_MODE_NOT_BLOCKED"
        if table in ("InstallExecuteSequence", "InstallUISequence"):
            seq = {r[0]: r[1:] for r in rows}
            for name, pos in (("FindRelatedProducts", "25"), ("AppSearch", "50"), ("CcbResolveInstallDirectory", "60"),
                              ("CostInitialize", "800"), ("FileCost", "900"), ("CostFinalize", "1000"), ("MigrateFeatureStates", "1200")):
                assert seq.get(name) == ["", pos], "OPERATION_SEQUENCE_UNSUPPORTED:" + name
            assert seq.get("CcbDisallowDowngrade") == ["JP_DOWNGRADABLE_FOUND", "26"]
            if table == "InstallExecuteSequence":
                for name, pos in (("InstallValidate", "1400"), ("InstallInitialize", "1500"), ("CcbValidateOperation", "1501"), ("RemoveExistingProducts", "1510")):
                    assert seq.get(name) == ["", pos], "OPERATION_SEQUENCE_UNSUPPORTED:" + name
                assert all(int(r[2]) < 1501 for r in rows if r[0].startswith("JpSet")), "LATE_TARGET_REWRITE_REFUSED"
                assert all(r[1] == "Not Installed" for r in rows if r[0].startswith("JpSet"))
                assert seq["ProcessComponents"] == ["", "1600"] and seq["InstallFinalize"] == ["", "6600"]
                if profile == "synthetic": assert seq["CcbSyntheticFailAfterRemoval"] == ["CCB_DESKTOP_SYNTHETIC_FAIL_AFTER_REMOVE=1", "1511"]
                if write_probe:
                    condition = 'CCB_SYNTHETIC_FAIL_AFTER_WRITES=1 AND NOT Installed AND NOT REMOVE~="ALL"'
                    assert seq.get("CcbSetSyntheticWriteProbeData") == [condition, "5001"], "SYNTHETIC_WRITE_DATA_SEQUENCE_UNSUPPORTED"
                    assert seq.get("CcbSyntheticFailAfterWrites") == [condition, "5002"], "SYNTHETIC_WRITE_DEFERRED_SEQUENCE_UNSUPPORTED"
                    assert seq.get("WriteRegistryValues") == ["", "5000"]


def seal_operation_contract(db, properties, profile):
    tables = operation_tables(db)
    operation_resource_graph(tables, properties)
    validate_operation_actions(tables, profile)
    db.sql("CREATE TABLE `CcbOperationTable` (`TableName` CHAR(72) NOT NULL, `Columns` SHORT NOT NULL, `RowCount` LONG NOT NULL, `Digest` CHAR(64) NOT NULL PRIMARY KEY `TableName`)")
    for name, rows in sorted(tables.items()):
        db.sql("INSERT INTO `CcbOperationTable` (`TableName`,`Columns`,`RowCount`,`Digest`) VALUES ('" + name + "'," + str(OP_COLUMNS[name]) + "," + str(len(rows)) + ",'" + operation_table_digest(rows) + "')")
    db.sql("CREATE TABLE `CcbOperationStream` (`TableName` CHAR(32) NOT NULL, `StreamName` CHAR(72) NOT NULL, `ByteLength` LONG NOT NULL, `Digest` CHAR(64) NOT NULL PRIMARY KEY `TableName`,`StreamName`)")
    for table in ("Binary", "Icon"):
        if table in {r[0] for r in db.rows("_Tables", 1)}:
            for name, proof in db.sql("SELECT `Name`,`Data` FROM `" + table + "`", 2, streams=True):
                assert re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.]{0,71}", name)
                db.sql("INSERT INTO `CcbOperationStream` (`TableName`,`StreamName`,`ByteLength`,`Digest`) VALUES ('" + table + "','" + name + "'," + str(proof["bytes"]) + ",'" + proof["sha256"] + "')")


def install_synthetic_write_probe(db, properties, profile, config):
    assert profile == "synthetic", "SYNTHETIC_WRITE_PROBE_ONLY"
    assert set(config) == {"build", "expectedSha256", "fileId", "payloadSha256"}, "SYNTHETIC_WRITE_PROBE_CONFIG_UNSUPPORTED"
    spec = importlib.util.spec_from_file_location("ccb_synthetic_write_probe_build", Path(__file__).with_name("build_synthetic_write_fault.py"))
    builder = importlib.util.module_from_spec(spec); spec.loader.exec_module(builder)
    dll = builder.reuse(config["build"], config["expectedSha256"])
    graph = operation_resource_graph(operation_tables(db), properties)
    file_id, digest = config["fileId"], config["payloadSha256"]
    assert file_id in graph["files"] and graph["files"][file_id].lower().endswith(".jar") and re.fullmatch(r"[a-f0-9]{64}", digest), "SYNTHETIC_WRITE_PAYLOAD_UNVERIFIED"
    assert not any(r[2] in ("5001", "5002") for r in db.rows("InstallExecuteSequence", 3)), "SYNTHETIC_WRITE_SEQUENCE_COLLISION"
    db.add_binary("CcbSyntheticWriteProbe", dll)
    data = "|".join(("CCB_SYNTHETIC_AFTER_WRITES_V1", properties["ProductCode"], properties["ProductVersion"], "[INSTALLDIR]", graph["files"][file_id], digest))
    db.sql("INSERT INTO `CustomAction` (`Action`,`Type`,`Source`,`Target`) VALUES ('CcbSetSyntheticWriteProbeData',51,'CcbSyntheticFailAfterWrites','" + data.replace("'", "''") + "')")
    db.sql("INSERT INTO `CustomAction` (`Action`,`Type`,`Source`,`Target`) VALUES ('CcbSyntheticFailAfterWrites',1025,'CcbSyntheticWriteProbe','CcbSyntheticFailAfterWrites')")
    condition = 'CCB_SYNTHETIC_FAIL_AFTER_WRITES=1 AND NOT Installed AND NOT REMOVE~="ALL"'
    for name, sequence in (("CcbSetSyntheticWriteProbeData", 5001), ("CcbSyntheticFailAfterWrites", 5002)):
        db.sql("INSERT INTO `InstallExecuteSequence` (`Action`,`Condition`,`Sequence`) VALUES ('" + name + "','" + condition + "'," + str(sequence) + ")")
    for name, value in (("CCB_SYNTHETIC_POSTWRITE_PROBE", "1"), ("CCB_SYNTHETIC_POSTWRITE_FILE", file_id),
                        ("CCB_SYNTHETIC_POSTWRITE_SHA256", digest), ("CCB_SYNTHETIC_POSTWRITE_BINARY_SHA256", config["expectedSha256"])):
        db.sql("INSERT INTO `Property` (`Property`,`Value`) VALUES ('" + name + "','" + value + "')")


def sha(path):
    result = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


class Database:
    def __init__(self, path, writable=False):
        self.lib = C.WinDLL("msi")
        self.lib.MsiOpenDatabaseW.argtypes = [W.LPCWSTR, W.LPCWSTR, C.POINTER(W.UINT)]
        self.lib.MsiDatabaseOpenViewW.argtypes = [W.UINT, W.LPCWSTR, C.POINTER(W.UINT)]
        self.lib.MsiRecordGetStringW.argtypes = [W.UINT, W.UINT, W.LPWSTR, C.POINTER(W.DWORD)]
        self.lib.MsiRecordReadStream.argtypes = [W.UINT, W.UINT, C.c_void_p, C.POINTER(W.DWORD)]
        self.handle = W.UINT()
        code = self.lib.MsiOpenDatabaseW(str(path), C.cast(C.c_void_p(1), W.LPCWSTR) if writable else None, C.byref(self.handle))
        if code:
            raise RuntimeError(f"MSI_DATABASE_OPEN_FAILED status={code}; protection/OS cause requires evidence")

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.lib.MsiCloseHandle(self.handle)

    def sql(self, statement, columns=0, streams=False):
        view = W.UINT()
        code = self.lib.MsiDatabaseOpenViewW(self.handle, statement, C.byref(view))
        if code:
            raise RuntimeError(f"MSI_QUERY_OPEN_FAILED status={code}; protection/OS cause requires evidence")
        rows = []
        try:
            code = self.lib.MsiViewExecute(view, 0)
            if code:
                raise RuntimeError(f"MSI_QUERY_EXECUTE_FAILED status={code}; protection/OS cause requires evidence")
            if not columns:
                return rows
            while True:
                record = W.UINT()
                result = self.lib.MsiViewFetch(view, C.byref(record))
                if result == 259:
                    return rows
                assert result == 0, "MSI_FETCH_FAILED"
                row = []
                try:
                    for index in range(1, columns + 1):
                        if streams and index == 2:
                            digest = hashlib.sha256(); size = 0
                            while True:
                                buffer = C.create_string_buffer(65536); length = W.DWORD(len(buffer))
                                assert self.lib.MsiRecordReadStream(record, index, buffer, C.byref(length)) == 0
                                if not length.value:
                                    break
                                digest.update(buffer.raw[:length.value]); size += length.value
                            row.append({"bytes": size, "sha256": digest.hexdigest()})
                        else:
                            buffer = C.create_unicode_buffer(32768); length = W.DWORD(len(buffer))
                            assert self.lib.MsiRecordGetStringW(record, index, buffer, C.byref(length)) == 0
                            row.append(buffer.value)
                finally:
                    self.lib.MsiCloseHandle(record)
                rows.append(row)
        finally:
            self.lib.MsiCloseHandle(view)

    def rows(self, table, columns):
        return self.sql("SELECT * FROM `" + table + "`", columns)

    def cabinets(self):
        return {name: proof for name, proof in self.sql("SELECT `Name`,`Data` FROM `_Streams`", 2, streams=True) if name.lower().endswith(".cab")}

    def package_code(self, replacement=None):
        """An edited MSI is a different package; never change its Component/Product GUIDs here."""
        summary = W.UINT()
        self.lib.MsiGetSummaryInformationW.argtypes = [W.UINT, W.LPCWSTR, W.UINT, C.POINTER(W.UINT)]
        self.lib.MsiSummaryInfoGetPropertyW.argtypes = [W.UINT, W.UINT, C.POINTER(W.UINT), C.POINTER(C.c_int), C.c_void_p, W.LPWSTR, C.POINTER(W.DWORD)]
        self.lib.MsiSummaryInfoSetPropertyW.argtypes = [W.UINT, W.UINT, W.UINT, C.c_int, C.c_void_p, W.LPCWSTR]
        assert self.lib.MsiGetSummaryInformationW(self.handle, None, int(replacement is not None), C.byref(summary)) == 0, "MSI_SUMMARY_UNAVAILABLE"
        try:
            value = C.create_unicode_buffer(256); length = W.DWORD(len(value)); kind = W.UINT(); number = C.c_int(); filetime = W.FILETIME()
            assert self.lib.MsiSummaryInfoGetPropertyW(summary, 9, C.byref(kind), C.byref(number), C.byref(filetime), value, C.byref(length)) == 0, "MSI_PACKAGE_CODE_UNAVAILABLE"
            assert kind.value == 30 and re.fullmatch(r"\{[0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}\}", value.value), "MSI_PACKAGE_CODE_UNSUPPORTED"
            if replacement is not None:
                assert replacement != value.value and re.fullmatch(r"\{[0-9A-F]{8}(?:-[0-9A-F]{4}){3}-[0-9A-F]{12}\}", replacement), "MSI_NEW_PACKAGE_CODE_REQUIRED"
                assert self.lib.MsiSummaryInfoSetPropertyW(summary, 9, 30, 0, None, replacement) == 0, "MSI_PACKAGE_CODE_UPDATE_FAILED"
                assert self.lib.MsiSummaryInfoPersist(summary) == 0, "MSI_PACKAGE_CODE_PERSIST_FAILED"
            return value.value
        finally:
            self.lib.MsiCloseHandle(summary)

    def add_binary(self, name, path):
        view = W.UINT()
        self.lib.MsiCreateRecord.argtypes = [W.UINT]
        self.lib.MsiCreateRecord.restype = W.UINT
        self.lib.MsiViewExecute.argtypes = [W.UINT, W.UINT]
        record = self.lib.MsiCreateRecord(2)
        assert record, "MSI_BINARY_RECORD_FAILED"
        self.lib.MsiRecordSetStringW.argtypes = [W.UINT, W.UINT, W.LPCWSTR]
        self.lib.MsiRecordSetStreamW.argtypes = [W.UINT, W.UINT, W.LPCWSTR]
        try:
            assert self.lib.MsiDatabaseOpenViewW(self.handle, "INSERT INTO `Binary` (`Name`,`Data`) VALUES (?,?)", C.byref(view)) == 0, "MSI_DIRECTORY_BINARY_VIEW_FAILED"
            assert self.lib.MsiRecordSetStringW(record, 1, name) == 0, "MSI_DIRECTORY_BINARY_NAME_FAILED"
            assert self.lib.MsiRecordSetStreamW(record, 2, str(path)) == 0, "MSI_DIRECTORY_BINARY_STREAM_FAILED"
            assert self.lib.MsiViewExecute(view, record) == 0, "MSI_DIRECTORY_BINARY_INSERT_FAILED"
        finally:
            if view.value:
                self.lib.MsiCloseHandle(view)
            self.lib.MsiCloseHandle(record)


def check_product(db, profile):
    properties = dict(db.rows("Property", 2))
    name, upgrade = PRODUCTS[profile]
    assert properties["ProductName"] == name and properties["UpgradeCode"].strip("{}").lower() == upgrade, "FOREIGN_MSI_REFUSED"
    assert "ALLUSERS" not in properties, "PER_USER_MSI_REQUIRED"
    tables = {row[0] for row in db.rows("_Tables", 1)}
    for table in ["MsiDigitalSignature", "MsiDigitalCertificate"]:
        if table in tables:
            assert not db.rows(table, 1), "SIGNED_MSI_MUST_NOT_BE_EDITED"
    assert all(not row[2] for row in db.rows("RemoveFile", 5)), "STATIC_FILE_SWEEP_REFUSED"
    return properties


def verify(db, profile):
    properties = check_product(db, profile)
    assert properties.get("CCB_DESKTOP_MSI_SAFETY") == "1"
    assert properties.get("CCB_DIRECTORY_CONTRACT") == "2", "DIRECTORY_CONTRACT_REQUIRED"
    assert properties.get("CCB_COMPONENT_CONTRACT") == "1", "COMPONENT_CONTRACT_REQUIRED"
    assert properties.get("CCB_OPERATION_CONTRACT") == "1", "OPERATION_CONTRACT_REQUIRED"
    validate_component_contract(db, properties)
    assert "INSTALLDIR" in properties.get("SecureCustomProperties", "").split(";"), "DIRECTORY_UI_TRANSFER_REQUIRED"
    assert not db.rows("WixRemoveFolderEx", 4)
    actions = {row[0]: row for row in db.rows("CustomAction", 4)}
    assert "WixRemoveFoldersEx" not in actions
    assert actions["CcbDisallowDowngrade"][1:] == ["19", "", "A newer CCB Desktop version is installed. Use a newer installer."]
    sequence = {row[0]: row for row in db.rows("InstallExecuteSequence", 3)}
    assert sequence["RemoveExistingProducts"][2] == "1510"
    assert sequence["InstallInitialize"][2] == "1500"
    for table in ["InstallExecuteSequence", "InstallUISequence"]:
        sequence = {row[0]: row for row in db.rows(table, 3)}
        assert "WixRemoveFoldersEx" not in sequence
        assert sequence["CcbDisallowDowngrade"][1:] == ["JP_DOWNGRADABLE_FOUND", "26"]
    assert ("CcbSyntheticFailAfterRemoval" in actions) == (profile == "synthetic")
    if profile == "synthetic":
        sequence = {row[0]: row for row in db.rows("InstallExecuteSequence", 3)}
        assert sequence["CcbSyntheticFailAfterRemoval"][1:] == ["CCB_DESKTOP_SYNTHETIC_FAIL_AFTER_REMOVE=1", "1511"]
    for action in ["CcbResolveInstallDirectory", "CcbValidateOperation"]:
        assert actions[action][1:] == ["1", "CcbDirectoryGuard", action], "DIRECTORY_GUARD_ACTION_CHANGED"
    for table in ["InstallExecuteSequence", "InstallUISequence"]:
        sequence = {row[0]: row for row in db.rows(table, 3)}
        assert sequence["CcbResolveInstallDirectory"][1:] == ["", "60"], "DIRECTORY_RESOLUTION_SEQUENCE_CHANGED"
        assert sequence["AppSearch"][2] == "50" and sequence["CostInitialize"][2] == "800"
    sequence = {row[0]: row for row in db.rows("InstallExecuteSequence", 3)}
    assert sequence["CcbValidateOperation"][1:] == ["", "1501"], "OPERATION_REVALIDATION_SEQUENCE_CHANGED"
    validate_directory_record(db, properties, require_v2=True)
    binary = dict(db.sql("SELECT `Name`,`Data` FROM `Binary`", 2, streams=True))
    assert binary.get("CcbDirectoryGuard", {}).get("bytes", 0) > 0, "DIRECTORY_GUARD_BINARY_MISSING"
    expected_binary = {"CcbDirectoryGuard"}
    if properties.get("CCB_SYNTHETIC_POSTWRITE_PROBE") == "1":
        assert profile == "synthetic", "SYNTHETIC_WRITE_PROBE_ONLY"
        expected_binary.add("CcbSyntheticWriteProbe")
        assert binary.get("CcbSyntheticWriteProbe", {}).get("sha256") == properties.get("CCB_SYNTHETIC_POSTWRITE_BINARY_SHA256"), "SYNTHETIC_WRITE_BINARY_UNVERIFIED"
    assert set(binary) == expected_binary, "UNRECOGNIZED_BINARY"
    tables = operation_tables(db)
    operation_resource_graph(tables, properties)
    validate_operation_actions(tables, profile)
    sealed = {r[0]: r[1:] for r in db.rows("CcbOperationTable", 4)}
    assert sealed == {name: [str(OP_COLUMNS[name]), str(len(rows)), operation_table_digest(rows)] for name, rows in tables.items()}, "OPERATION_TABLE_CHANGED"
    for table, name, length, digest in db.rows("CcbOperationStream", 4):
        actual = dict(db.sql("SELECT `Name`,`Data` FROM `" + table + "`", 2, streams=True))
        assert actual[name] == {"bytes": int(length), "sha256": digest}, "OPERATION_STREAM_CHANGED"
    return properties


def validate_directory_record(db, properties, require_v2=False):
    assert re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", properties["ProductVersion"]), "DIRECTORY_VERSION_INVALID"
    key = "Software\\ChatChatBar\\" + properties["ProductName"] + "\\" + properties["ProductVersion"]
    saved = [row for row in db.rows("Registry", 6) if re.fullmatch(r"RM_RF[A-Fa-f0-9]{32}", row[3]) and row[4] == "[INSTALLDIR]"]
    assert len(saved) == 1 and saved[0][1:3] == ["1", key], "DIRECTORY_REGISTRY_LAYOUT_UNKNOWN"
    components = {row[0]: row for row in db.rows("Component", 6)}
    assert re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.]{0,71}", saved[0][5]), "DIRECTORY_COMPONENT_UNKNOWN"
    assert int(components[saved[0][5]][3]) & 256, "DIRECTORY_REGISTRY_VIEW_UNKNOWN"
    if require_v2:
        entries = [row for row in db.rows("Registry", 6) if row[3] == "CcbInstallDirectoryV2"]
        assert len(entries) == 1 and entries[0][1:] == ["1", key, "CcbInstallDirectoryV2", "[INSTALLDIR]", saved[0][5]], "DIRECTORY_RECORD_CHANGED"
    return key, saved[0][5]


def component_key(properties, component_guid):
    return "Software\\ChatChatBar\\" + properties["ProductName"] + "\\Components\\x64\\" + component_guid[1:-1]


def component_records(db, properties):
    """Recognize the bounded JDK17 component shape before changing any table."""
    _, directory_owner = validate_directory_record(db, properties)
    components = db.rows("Component", 6)
    registry = db.rows("Registry", 6)
    assert 1 < len(components) <= 1024, "COMPONENT_COUNT_UNSUPPORTED"
    assert len({r[0] for r in components}) == len(components), "COMPONENT_ID_DUPLICATE"
    assert len({r[1].upper() for r in components}) == len(components), "COMPONENT_GUID_DUPLICATE"
    assert len({r[0] for r in registry}) == len(registry), "COMPONENT_REGISTRY_ID_DUPLICATE"
    by_registry = {r[0]: r for r in registry}
    records = []
    for row in components:
        assert re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.]{0,71}", row[0]), "COMPONENT_ID_UNSUPPORTED"
        assert re.fullmatch(r"\{[0-9A-F]{8}(?:-[0-9A-F]{4}){3}-[0-9A-F]{12}\}", row[1]), "COMPONENT_GUID_UNSUPPORTED"
        assert row[2] and row[3:5] == ["260", ""], "COMPONENT_ATTRIBUTES_OR_CONDITION_UNSUPPORTED"
        assert re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.]{0,71}", row[5]), "COMPONENT_KEYPATH_ID_UNSUPPORTED"
        assert row[5] in by_registry, "COMPONENT_KEYPATH_MISSING"
        entry = by_registry[row[5]]
        assert entry[1] == "1" and entry[5] == row[0], "COMPONENT_KEYPATH_OWNERSHIP_UNSUPPORTED"
        records.append((row, entry))
    # Do not accept an unrecognized resource family by silently normalizing its marker.
    names = {r[0] for r in db.rows("_Tables", 1)}
    for table, width in (("ServiceInstall", 13), ("Environment", 4), ("IniFile", 8),
                         ("DuplicateFile", 5), ("MoveFile", 7), ("Class", 13),
                         ("Extension", 5), ("ProgId", 6)):
        if table in names:
            assert not db.rows(table, width), "COMPONENT_RESOURCE_FAMILY_UNSUPPORTED"
    known = {r[0] for r in components}
    assert all(r[1] in known and r[1] != directory_owner for r in db.rows("File", 8)), "COMPONENT_FILE_OWNER_UNSUPPORTED"
    assert all(r[1] in known and not r[2] for r in db.rows("RemoveFile", 5)), "COMPONENT_REMOVAL_UNSUPPORTED"
    return records, registry, directory_owner


def install_component_contract(db, properties):
    assert "CCB_COMPONENT_CONTRACT" not in properties, "COMPONENT_ALREADY_PROCESSED"
    records, registry, owner = component_records(db, properties)
    key, _ = validate_directory_record(db, properties)
    # Original JDK17 records are one registry KeyPath per component; refuse extras.
    assert len(registry) == len(records), "RAW_COMPONENT_REGISTRY_LAYOUT_UNSUPPORTED"
    assert len({r[5] for r, _ in records}) == len(records), "RAW_COMPONENT_KEYPATH_REUSED"
    for row, entry in records:
        if row[0] != owner:
            assert entry[2:5] == [key, "ProductCode", "[ProductCode]"], "RAW_COMPONENT_KEYPATH_UNSUPPORTED"
    for row, entry in records:
        if row[0] != owner:
            # GUID, files, conditions, bitness and directories stay untouched.
            db.sql("UPDATE `Registry` SET `Key`='" + component_key(properties, row[1]) +
                   "',`Name`='Installed',`Value`='1' WHERE `Registry`='" + entry[0] + "'")
    # Directory recovery needs its own versioned product owner; do not leave this
    # value owned simultaneously by every file component.
    db.sql("INSERT INTO `Registry` (`Registry`,`Root`,`Key`,`Name`,`Value`,`Component_`) VALUES "
           "('CcbComponentProductOwnerV1',1,'" + key + "','ProductCode','[ProductCode]','" + owner + "')")
    db.sql("INSERT INTO `Property` (`Property`,`Value`) VALUES ('CCB_COMPONENT_CONTRACT','1')")
    validate_component_contract(db, dict(db.rows("Property", 2)))


def validate_component_contract(db, properties):
    assert properties.get("CCB_COMPONENT_CONTRACT") == "1", "COMPONENT_CONTRACT_REQUIRED"
    records, registry, owner = component_records(db, properties)
    key, _ = validate_directory_record(db, properties)
    addresses = set()
    expected_ids = {r[5] for r, _ in records}
    for row, entry in records:
        address = tuple(v.lower() for v in entry[1:4])
        assert address not in addresses, "COMPONENT_KEYPATH_ADDRESS_DUPLICATE"
        addresses.add(address)
        if row[0] != owner:
            assert entry[2:5] == [component_key(properties, row[1]), "Installed", "1"], "COMPONENT_KEYPATH_LAYOUT_UNSUPPORTED"
    owners = [r for r in registry if r[0] == "CcbComponentProductOwnerV1"]
    assert owners == [["CcbComponentProductOwnerV1", "1", key, "ProductCode", "[ProductCode]", owner]], "COMPONENT_PRODUCT_OWNER_UNSUPPORTED"
    expected_ids.add("CcbComponentProductOwnerV1")
    if properties.get("CCB_DIRECTORY_CONTRACT") == "2":
        validate_directory_record(db, properties, require_v2=True)
        expected_ids.add("CcbDirectoryRecordV2")
    assert {r[0] for r in registry} == expected_ids, "COMPONENT_EXTRA_REGISTRY_UNSUPPORTED"


def install_directory_contract(db, properties, dll):
    key, component = validate_directory_record(db, properties)
    assert not any(row[0].startswith("CcbDirectory") or row[0] in ("CcbResolveInstallDirectory", "CcbRevalidateInstallDirectory") for row in db.rows("CustomAction", 4)), "DIRECTORY_ACTION_COLLISION"
    assert not any(row[0] == "CcbDirectoryGuard" for row in db.sql("SELECT `Name` FROM `Binary`", 1)), "DIRECTORY_BINARY_COLLISION"
    assert not any(row[2] in ("60", "1501", "1510", "1511") for row in db.rows("InstallExecuteSequence", 3)), "DIRECTORY_SEQUENCE_COLLISION"
    assert not any(row[2] == "60" for row in db.rows("InstallUISequence", 3)), "DIRECTORY_SEQUENCE_COLLISION"
    db.add_binary("CcbDirectoryGuard", dll)
    for action in ("CcbResolveInstallDirectory", "CcbValidateOperation"):
        db.sql("INSERT INTO `CustomAction` (`Action`,`Type`,`Source`,`Target`) VALUES ('" + action + "',1,'CcbDirectoryGuard','" + action + "')")
    for table in ("InstallExecuteSequence", "InstallUISequence"):
        db.sql("INSERT INTO `" + table + "` (`Action`,`Sequence`) VALUES ('CcbResolveInstallDirectory',60)")
    db.sql("INSERT INTO `InstallExecuteSequence` (`Action`,`Sequence`) VALUES ('CcbValidateOperation',1501)")
    db.sql("INSERT INTO `Registry` (`Registry`,`Root`,`Key`,`Name`,`Value`,`Component_`) VALUES ('CcbDirectoryRecordV2',1,'" + key + "','CcbInstallDirectoryV2','[INSTALLDIR]','" + component + "')")
    db.sql("INSERT INTO `Property` (`Property`,`Value`) VALUES ('CCB_DIRECTORY_CONTRACT','2')")
    secure = properties.get("SecureCustomProperties", "").split(";")
    assert all(not name or re.fullmatch(r"[A-Z_][A-Z0-9_]*", name) for name in secure), "SECURE_PROPERTY_LIST_UNKNOWN"
    if "INSTALLDIR" not in secure:
        secure = [name for name in secure if name] + ["INSTALLDIR"]
    if "SecureCustomProperties" in properties:
        db.sql("UPDATE `Property` SET `Value`='" + ";".join(secure) + "' WHERE `Property`='SecureCustomProperties'")
    else:
        db.sql("INSERT INTO `Property` (`Property`,`Value`) VALUES ('SecureCustomProperties','INSTALLDIR')")


def check_layout(db, profile):
    properties = check_product(db, profile)
    assert "CCB_DESKTOP_MSI_SAFETY" not in properties, "ALREADY_PROCESSED_MSI"
    assert "CCB_DIRECTORY_CONTRACT" not in properties, "DIRECTORY_ALREADY_PROCESSED"
    assert "CCB_COMPONENT_CONTRACT" not in properties, "COMPONENT_ALREADY_PROCESSED"
    validate_directory_record(db, properties)
    sequence = {row[0]: row for row in db.rows("InstallExecuteSequence", 3)}
    assert sequence["RemoveExistingProducts"][2] == "798" and sequence["InstallInitialize"][2] == "1500", "UNRECOGNIZED_JPACKAGE_LAYOUT"
    assert sequence["FindRelatedProducts"][2] == "25" and sequence["WixRemoveFoldersEx"][2] == "799"
    assert len(db.rows("WixRemoveFolderEx", 4)) == 1, "UNRECOGNIZED_RECURSIVE_CLEANUP"
    for table in ["InstallExecuteSequence", "InstallUISequence"]:
        assert not any(row[2] == "26" for row in db.rows(table, 3))
    assert not any(row[2] in ["1501", "1510", "1511"] for row in db.rows("InstallExecuteSequence", 3))


def prepare(path, workspace, expected_hash, profile, output=None, guard_build=None, expected_guard_hash=None, synthetic_postwrite=None):
    path = Path(os.path.abspath(path)); workspace = Path(os.path.abspath(workspace))
    root = Path(__file__).resolve().parents[3] / "app/desktopApp/build"
    assert path.is_relative_to(root) and workspace.is_relative_to(root) and path.suffix.lower() == ".msi"
    output = Path(os.path.abspath(output)) if output else path
    assert output.is_relative_to(root) and output.suffix.lower() == ".msi"
    if output != path:
        assert not output.exists(), "OUTPUT_ALREADY_EXISTS"
    for candidate in [path, workspace, output]:
        for ancestor in [candidate, *candidate.parents]:
            if ancestor.exists():
                assert not (ancestor.stat(follow_symlinks=False).st_file_attributes & 0x400), "REPARSE_REFUSED"
    assert sha(path) == expected_hash, "MSI_INPUT_CHANGED"
    assert synthetic_postwrite is None or profile == "synthetic", "SYNTHETIC_WRITE_PROBE_ONLY"
    with Database(path) as original:
        check_layout(original, profile)
    workspace.mkdir(parents=True, exist_ok=False)
    spec = importlib.util.spec_from_file_location("ccb_directory_guard_build", Path(__file__).with_name("build_directory_guard.py"))
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    if guard_build is not None:
        assert expected_guard_hash, "EXPLICIT_GUARD_HASH_REQUIRED"
        guard = builder.reuse_production(guard_build, expected_guard_hash)
    else:
        assert expected_guard_hash is None, "GUARD_BUILD_REQUIRED"
        guard = builder.build(workspace / "directory-guard", test=False)
    shutil.copyfile(path, workspace / "original-unsigned.msi")
    prepared = workspace / "prepared.msi"; shutil.copyfile(path, prepared)
    assert sha(prepared) == expected_hash and sha(workspace / "original-unsigned.msi") == expected_hash
    with Database(prepared, writable=True) as db:
        check_layout(db, profile)
        cabinets = db.cabinets(); assert cabinets
        install_component_contract(db, check_product(db, profile))
        install_directory_contract(db, check_product(db, profile), guard)
        db.sql("DELETE FROM `WixRemoveFolderEx`")
        for table in ["InstallExecuteSequence", "InstallUISequence", "CustomAction"]:
            db.sql("DELETE FROM `" + table + "` WHERE `Action`='WixRemoveFoldersEx'")
        db.sql("DELETE FROM `Binary` WHERE `Name`='WixCA'")
        db.sql("UPDATE `InstallExecuteSequence` SET `Sequence`=1510 WHERE `Action`='RemoveExistingProducts'")
        db.sql("INSERT INTO `CustomAction` (`Action`,`Type`,`Target`) VALUES ('CcbDisallowDowngrade',19,'A newer CCB Desktop version is installed. Use a newer installer.')")
        db.sql("INSERT INTO `CustomAction` (`Action`,`Type`,`Target`) VALUES ('CcbUnsupportedInstallMode',19,'Administrative and advertised installation is not supported.')")
        for table in ("AdminExecuteSequence", "AdminUISequence", "AdvtExecuteSequence"):
            assert not any(r[2] == "1" for r in db.rows(table, 3)), "UNSUPPORTED_MODE_SEQUENCE_COLLISION"
            db.sql("INSERT INTO `" + table + "` (`Action`,`Sequence`) VALUES ('CcbUnsupportedInstallMode',1)")
        for table in ["InstallExecuteSequence", "InstallUISequence"]:
            db.sql("INSERT INTO `" + table + "` (`Action`,`Condition`,`Sequence`) VALUES ('CcbDisallowDowngrade','JP_DOWNGRADABLE_FOUND',26)")
        if profile == "synthetic":
            db.sql("INSERT INTO `CustomAction` (`Action`,`Type`,`Target`) VALUES ('CcbSyntheticFailAfterRemoval',19,'Synthetic installer rollback probe.')")
            db.sql("INSERT INTO `InstallExecuteSequence` (`Action`,`Condition`,`Sequence`) VALUES ('CcbSyntheticFailAfterRemoval','CCB_DESKTOP_SYNTHETIC_FAIL_AFTER_REMOVE=1',1511)")
        if synthetic_postwrite is not None:
            install_synthetic_write_probe(db, dict(db.rows("Property", 2)), profile, synthetic_postwrite)
        db.sql("INSERT INTO `Property` (`Property`,`Value`) VALUES ('CCB_DESKTOP_MSI_SAFETY','1')")
        db.sql("INSERT INTO `Property` (`Property`,`Value`) VALUES ('CCB_OPERATION_CONTRACT','1')")
        seal_operation_contract(db, dict(db.rows("Property", 2)), profile)
        new_package_code = "{" + str(uuid.uuid4()).upper() + "}"
        original_package_code = db.package_code(new_package_code)
        code = db.lib.MsiDatabaseCommit(db.handle)
        if code:
            raise RuntimeError(f"MSI_COMMIT_FAILED status={code}; protection/OS cause requires evidence")
    with Database(prepared) as db:
        result = verify(db, profile)
        assert db.package_code() == new_package_code, "MSI_PACKAGE_IDENTITY_NOT_PERSISTED"
        assert db.cabinets() == cabinets, "APPLICATION_PAYLOAD_CHANGED"
    assert sha(path) == expected_hash, "MSI_INPUT_CHANGED_BEFORE_PUBLICATION"
    final_hash = sha(prepared)
    if output == path:
        os.replace(prepared, path)
    else:
        prepared.rename(output)  # On Windows a competing destination is never overwritten.
    receipt = {"version": 1, "profile": profile, "inputSha256": expected_hash, "outputSha256": final_hash,
               "productVersion": result["ProductVersion"], "applicationCabinets": cabinets,
               "recursiveCleanupRemoved": True, "downgradeBlocked": True, "oldRemovalInsideTransaction": True,
               "syntheticFailureAction": profile == "synthetic", "signingPerformed": False}
    receipt["directoryContract"] = 2
    receipt["componentContract"] = 1
    receipt["operationContract"] = 1
    receipt["syntheticPostwriteProbe"] = synthetic_postwrite is not None
    receipt["packageCode"] = {"input": original_package_code, "output": new_package_code}
    receipt["directoryGuard"] = {"bytes": guard.stat().st_size, "sha256": sha(guard)}
    (workspace / "RECEIPT.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--msi", type=Path, required=True)
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--profile", choices=PRODUCTS, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--guard-build", type=Path)
    parser.add_argument("--expected-guard-sha256")
    args = parser.parse_args()
    assert os.name == "nt"
    receipt = prepare(args.msi, args.workspace, args.expected_sha256, args.profile, args.output,
                      args.guard_build, args.expected_guard_sha256)
    print("DESKTOP_MSI_SAFETY_VERIFIED " + receipt["outputSha256"])


if __name__ == "__main__":
    main()
