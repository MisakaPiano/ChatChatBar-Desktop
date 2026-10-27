"""Build-time correction of the verified JDK17 MSI layout, before signing/distribution. No installation."""
import argparse
import ctypes as C
from ctypes import wintypes as W
import hashlib
import json
import os
from pathlib import Path
import shutil

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
    return properties


def check_layout(db, profile):
    properties = check_product(db, profile)
    assert "CCB_DESKTOP_MSI_SAFETY" not in properties, "ALREADY_PROCESSED_MSI"
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
    shutil.copyfile(path, workspace / "original-unsigned.msi")
    prepared = workspace / "prepared.msi"; shutil.copyfile(path, prepared)
    assert sha(prepared) == expected_hash and sha(workspace / "original-unsigned.msi") == expected_hash
    with Database(prepared, writable=True) as db:
        check_layout(db, profile)
        cabinets = db.cabinets(); assert cabinets
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
