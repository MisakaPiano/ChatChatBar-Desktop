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

if not __debug__:
    raise RuntimeError("MSI_SAFETY_REQUIRES_ENABLED_VALIDATION")

PRODUCTS = {
    "desktop": ("ChatChatBarDesktop", "8e4fddbd-141f-5c1d-bfb2-1b48a6e46b81"),
    "synthetic": ("ChatChatBarDesktopSynthetic", "58e95fd1-709d-5813-9b5b-f518a4612bae"),
}


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
    assert "INSTALLDIR" in properties.get("SecureCustomProperties", "").split(";"), "DIRECTORY_UI_TRANSFER_REQUIRED"
    assert not db.rows("WixRemoveFolderEx", 4)
    actions = {row[0]: row for row in db.rows("CustomAction", 4)}
    assert "WixRemoveFoldersEx" not in actions
    assert actions["CcbDisallowDowngrade"][1:] == ["19", "", "A newer CCB Desktop version is installed. Use a newer installer."]
    sequence = {row[0]: row for row in db.rows("InstallExecuteSequence", 3)}
    assert sequence["RemoveExistingProducts"][2] == "1501"
    assert sequence["InstallInitialize"][2] == "1500"
    for table in ["InstallExecuteSequence", "InstallUISequence"]:
        sequence = {row[0]: row for row in db.rows(table, 3)}
        assert "WixRemoveFoldersEx" not in sequence
        assert sequence["CcbDisallowDowngrade"][1:] == ["JP_DOWNGRADABLE_FOUND", "26"]
    assert ("CcbSyntheticFailAfterRemoval" in actions) == (profile == "synthetic")
    if profile == "synthetic":
        sequence = {row[0]: row for row in db.rows("InstallExecuteSequence", 3)}
        assert sequence["CcbSyntheticFailAfterRemoval"][1:] == ["CCB_DESKTOP_SYNTHETIC_FAIL_AFTER_REMOVE=1", "1502"]
    for action in ["CcbResolveInstallDirectory", "CcbRevalidateInstallDirectory"]:
        assert actions[action][1:] == ["1", "CcbDirectoryGuard", action], "DIRECTORY_GUARD_ACTION_CHANGED"
    for table in ["InstallExecuteSequence", "InstallUISequence"]:
        sequence = {row[0]: row for row in db.rows(table, 3)}
        assert sequence["CcbResolveInstallDirectory"][1:] == ["", "60"], "DIRECTORY_RESOLUTION_SEQUENCE_CHANGED"
        assert sequence["AppSearch"][2] == "50" and sequence["CostInitialize"][2] == "800"
    sequence = {row[0]: row for row in db.rows("InstallExecuteSequence", 3)}
    assert sequence["CcbRevalidateInstallDirectory"][1:] == ["", "1399"], "DIRECTORY_REVALIDATION_SEQUENCE_CHANGED"
    validate_directory_record(db, properties, require_v2=True)
    binary = dict(db.sql("SELECT `Name`,`Data` FROM `Binary`", 2, streams=True))
    assert binary.get("CcbDirectoryGuard", {}).get("bytes", 0) > 0, "DIRECTORY_GUARD_BINARY_MISSING"
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


def install_directory_contract(db, properties, dll):
    key, component = validate_directory_record(db, properties)
    assert not any(row[0].startswith("CcbDirectory") or row[0] in ("CcbResolveInstallDirectory", "CcbRevalidateInstallDirectory") for row in db.rows("CustomAction", 4)), "DIRECTORY_ACTION_COLLISION"
    assert not any(row[0] == "CcbDirectoryGuard" for row in db.sql("SELECT `Name` FROM `Binary`", 1)), "DIRECTORY_BINARY_COLLISION"
    assert not any(row[2] in ("60", "1399") for row in db.rows("InstallExecuteSequence", 3)), "DIRECTORY_SEQUENCE_COLLISION"
    assert not any(row[2] == "60" for row in db.rows("InstallUISequence", 3)), "DIRECTORY_SEQUENCE_COLLISION"
    db.add_binary("CcbDirectoryGuard", dll)
    for action in ("CcbResolveInstallDirectory", "CcbRevalidateInstallDirectory"):
        db.sql("INSERT INTO `CustomAction` (`Action`,`Type`,`Source`,`Target`) VALUES ('" + action + "',1,'CcbDirectoryGuard','" + action + "')")
    for table in ("InstallExecuteSequence", "InstallUISequence"):
        db.sql("INSERT INTO `" + table + "` (`Action`,`Sequence`) VALUES ('CcbResolveInstallDirectory',60)")
    db.sql("INSERT INTO `InstallExecuteSequence` (`Action`,`Sequence`) VALUES ('CcbRevalidateInstallDirectory',1399)")
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
    validate_directory_record(db, properties)
    sequence = {row[0]: row for row in db.rows("InstallExecuteSequence", 3)}
    assert sequence["RemoveExistingProducts"][2] == "798" and sequence["InstallInitialize"][2] == "1500", "UNRECOGNIZED_JPACKAGE_LAYOUT"
    assert sequence["FindRelatedProducts"][2] == "25" and sequence["WixRemoveFoldersEx"][2] == "799"
    assert len(db.rows("WixRemoveFolderEx", 4)) == 1, "UNRECOGNIZED_RECURSIVE_CLEANUP"
    for table in ["InstallExecuteSequence", "InstallUISequence"]:
        assert not any(row[2] == "26" for row in db.rows(table, 3))
    assert not any(row[2] in ["1501", "1502"] for row in db.rows("InstallExecuteSequence", 3))


def prepare(path, workspace, expected_hash, profile, output=None):
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
    with Database(path) as original:
        check_layout(original, profile)
    workspace.mkdir(parents=True, exist_ok=False)
    spec = importlib.util.spec_from_file_location("ccb_directory_guard_build", Path(__file__).with_name("build_directory_guard.py"))
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    guard = builder.build(workspace / "directory-guard", test=False)
    shutil.copyfile(path, workspace / "original-unsigned.msi")
    prepared = workspace / "prepared.msi"; shutil.copyfile(path, prepared)
    assert sha(prepared) == expected_hash and sha(workspace / "original-unsigned.msi") == expected_hash
    with Database(prepared, writable=True) as db:
        check_layout(db, profile)
        cabinets = db.cabinets(); assert cabinets
        install_directory_contract(db, check_product(db, profile), guard)
        db.sql("DELETE FROM `WixRemoveFolderEx`")
        for table in ["InstallExecuteSequence", "InstallUISequence", "CustomAction"]:
            db.sql("DELETE FROM `" + table + "` WHERE `Action`='WixRemoveFoldersEx'")
        db.sql("UPDATE `InstallExecuteSequence` SET `Sequence`=1501 WHERE `Action`='RemoveExistingProducts'")
        db.sql("INSERT INTO `CustomAction` (`Action`,`Type`,`Target`) VALUES ('CcbDisallowDowngrade',19,'A newer CCB Desktop version is installed. Use a newer installer.')")
        for table in ["InstallExecuteSequence", "InstallUISequence"]:
            db.sql("INSERT INTO `" + table + "` (`Action`,`Condition`,`Sequence`) VALUES ('CcbDisallowDowngrade','JP_DOWNGRADABLE_FOUND',26)")
        if profile == "synthetic":
            db.sql("INSERT INTO `CustomAction` (`Action`,`Type`,`Target`) VALUES ('CcbSyntheticFailAfterRemoval',19,'Synthetic installer rollback probe.')")
            db.sql("INSERT INTO `InstallExecuteSequence` (`Action`,`Condition`,`Sequence`) VALUES ('CcbSyntheticFailAfterRemoval','CCB_DESKTOP_SYNTHETIC_FAIL_AFTER_REMOVE=1',1502)")
        db.sql("INSERT INTO `Property` (`Property`,`Value`) VALUES ('CCB_DESKTOP_MSI_SAFETY','1')")
        code = db.lib.MsiDatabaseCommit(db.handle)
        if code:
            raise RuntimeError(f"MSI_COMMIT_FAILED status={code}; protection/OS cause requires evidence")
    with Database(prepared) as db:
        result = verify(db, profile)
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
    args = parser.parse_args()
    assert os.name == "nt"
    receipt = prepare(args.msi, args.workspace, args.expected_sha256, args.profile, args.output)
    print("DESKTOP_MSI_SAFETY_VERIFIED " + receipt["outputSha256"])


if __name__ == "__main__":
    main()
