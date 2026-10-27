"""Independent tests against an actual task-built unsigned synthetic MSI, without installing it."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET
from run_directory_guard_tests import Results
import msi_safety
from msi_safety import Database, prepare, sha, verify


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--guard-build", type=Path)
    parser.add_argument("--expected-guard-sha256")
    args = parser.parse_args()
    root = args.evidence.resolve(); root.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(__file__, root / "test_msi_safety.py")
    shutil.copyfile(msi_safety.__file__, root / "msi_safety.py")
    original = args.fixture.resolve(); original_hash = sha(original)
    counter = 0

    def fresh():
        nonlocal counter
        counter += 1
        case = root / f"case-{counter}"; case.mkdir()
        fixture = case / "input.msi"; shutil.copyfile(original, fixture)
        return case, fixture

    class Safety(unittest.TestCase):
        def test_optimized_python_cannot_disable_validation(self):
            result = subprocess.run([sys.executable, "-I", "-O", msi_safety.__file__, "--help"], capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn(b"MSI_SAFETY_REQUIRES_ENABLED_VALIDATION", result.stderr)

        def test_corrected_tables_and_every_cabinet_are_verified_and_input_is_retained(self):
            case, fixture = fresh()
            output = case / "verified.msi"
            receipt = prepare(fixture, case / "evidence", original_hash, "synthetic", output,
                              args.guard_build, args.expected_guard_sha256)
            self.assertTrue(output.is_file())
            self.assertEqual(original_hash, sha(fixture))
            self.assertNotEqual(original_hash, receipt["outputSha256"])
            with Database(output) as db:
                verify(db, "synthetic")
                self.assertTrue(db.cabinets())
            self.assertEqual(original_hash, sha(case / "evidence/original-unsigned.msi"))

        def test_wrong_input_hash_makes_no_workspace(self):
            case, fixture = fresh()
            with self.assertRaisesRegex(AssertionError, "MSI_INPUT_CHANGED"):
                prepare(fixture, case / "evidence", "0" * 64, "synthetic")
            self.assertFalse((case / "evidence").exists())
            self.assertEqual(original_hash, sha(fixture))

        def test_foreign_product_profile_is_refused_before_any_copy(self):
            case, fixture = fresh()
            with self.assertRaisesRegex(AssertionError, "FOREIGN_MSI_REFUSED"):
                prepare(fixture, case / "evidence", original_hash, "desktop")
            self.assertFalse((case / "evidence").exists())
            self.assertEqual(original_hash, sha(fixture))

        def test_unknown_future_sequence_is_not_relaxed(self):
            case, fixture = fresh()
            with Database(fixture, writable=True) as db:
                db.sql("UPDATE `InstallExecuteSequence` SET `Sequence`=777 WHERE `Action`='RemoveExistingProducts'")
                self.assertEqual(0, db.lib.MsiDatabaseCommit(db.handle))
            before = sha(fixture)
            with self.assertRaisesRegex(AssertionError, "UNRECOGNIZED_JPACKAGE_LAYOUT"):
                prepare(fixture, case / "evidence", before, "synthetic")
            self.assertEqual(before, sha(fixture)); self.assertFalse((case / "evidence").exists())

        def test_unknown_existing_output_is_never_overwritten(self):
            case, fixture = fresh()
            output = case / "keep.msi"; output.write_bytes(b"SYNTHETIC_UNMANAGED_OUTPUT")
            with self.assertRaisesRegex(AssertionError, "OUTPUT_ALREADY_EXISTS"):
                prepare(fixture, case / "evidence", original_hash, "synthetic", output)
            self.assertEqual(b"SYNTHETIC_UNMANAGED_OUTPUT", output.read_bytes())
            self.assertEqual(original_hash, sha(fixture))

        def test_static_wildcard_cleanup_is_also_refused(self):
            case, fixture = fresh()
            with Database(fixture, writable=True) as db:
                row = db.rows("RemoveFile", 5)[0]
                db.sql("INSERT INTO `RemoveFile` (`FileKey`,`Component_`,`FileName`,`DirProperty`,`InstallMode`) VALUES ('SyntheticSweep','" + row[1] + "','*','INSTALLDIR',2)")
                self.assertEqual(0, db.lib.MsiDatabaseCommit(db.handle))
            before = sha(fixture)
            with self.assertRaisesRegex(AssertionError, "STATIC_FILE_SWEEP_REFUSED"):
                prepare(fixture, case / "evidence", before, "synthetic")
            self.assertEqual(before, sha(fixture)); self.assertFalse((case / "evidence").exists())

    with (root / "unittest.log").open("w", encoding="utf-8") as log:
        class RetainedResults(Results):
            def __init__(self, *a, **kw):
                super().__init__(*a, **kw); self.entries = []
        result = unittest.TextTestRunner(stream=log, verbosity=2, resultclass=RetainedResults).run(unittest.defaultTestLoader.loadTestsFromTestCase(Safety))
    xml = ET.Element("testsuite", name="ActualMsiSafety", tests=str(result.testsRun), failures=str(len(result.failures)), errors=str(len(result.errors)), skipped=str(len(result.skipped)))
    for entry in result.entries:
        case = ET.SubElement(xml, "testcase", name=entry["id"], time=str(entry["seconds"]))
        for kind in ("failure", "error"):
            if kind in entry: ET.SubElement(case, kind).text = entry[kind]
    ET.ElementTree(xml).write(root / "TEST-ActualMsiSafety.xml", encoding="utf-8", xml_declaration=True)
    report = {"tests": result.testsRun, "failures": len(result.failures), "errors": len(result.errors), "skipped": len(result.skipped), "fixtureSha256": original_hash, "installsExecuted": 0}
    (root / "RESULT.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))
    raise SystemExit(0 if result.wasSuccessful() else 1)


if __name__ == "__main__":
    main()
