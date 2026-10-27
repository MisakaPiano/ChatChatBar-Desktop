"""Retained unittest/XML runner. Never invokes an installer or calls native live adapters."""
import argparse
import json
import os
from pathlib import Path
import sys
import time
import unittest
import xml.etree.ElementTree as ET

if not __debug__:
    raise SystemExit("VALIDATION_REQUIRED")
sys.path.insert(0, str(Path(__file__).resolve().parent))


class Results(unittest.TextTestResult):
    def startTest(self, test):
        super().startTest(test)
        self.started = time.monotonic()
        self.current = {"id": test.id()}

    def stopTest(self, test):
        self.current["seconds"] = time.monotonic() - self.started
        self.entries.append(self.current)
        super().stopTest(test)

    def addFailure(self, test, error):
        self.current["failure"] = self._exc_info_to_string(error, test)
        super().addFailure(test, error)

    def addError(self, test, error):
        self.current["error"] = self._exc_info_to_string(error, test)
        super().addError(test, error)

    def addSubTest(self, test, subtest, error):
        if error:
            self.current["failure"] = self._exc_info_to_string(error, test)
        super().addSubTest(test, subtest, error)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--module", action="append", choices=("test_msi_directory_guard", "test_native_probe_validation", "test_msi_component_contract", "test_package_resource_admission", "test_msi_operation_contract", "test_synthetic_write_fault", "test_msi_shortcut_resource_graph", "test_msi_header_diagnostics", "test_msi_summary_output_contract", "test_header_probe_only", "test_header_probe_delivery"))
    args = parser.parse_args()
    evidence = args.evidence.resolve()
    assert evidence.is_dir() and not (evidence / "RESULT.json").exists()
    os.environ["CCB_COMPONENT_TEST_DIRECTORY"] = str(evidence / "native-tables")
    suite = unittest.TestSuite()
    for module in args.module or ("test_msi_directory_guard", "test_native_probe_validation", "test_msi_component_contract"):
        suite.addTests(unittest.defaultTestLoader.loadTestsFromName(module))
    with (evidence / "unittest.log").open("x", encoding="utf-8") as stream:
        runner = unittest.TextTestRunner(stream=stream, verbosity=2, resultclass=Results)
        original_make = runner._makeResult
        def make():
            result = original_make()
            result.entries = []
            return result
        runner._makeResult = make
        result = runner.run(suite)
    classes = sorted({r["id"].rsplit(".", 1)[0] for r in result.entries})
    for classname in classes:
        rows = [r for r in result.entries if r["id"].rsplit(".", 1)[0] == classname]
        xml = ET.Element("testsuite", name=classname, tests=str(len(rows)), failures=str(sum("failure" in r for r in rows)),
                         errors=str(sum("error" in r for r in rows)), skipped="0")
        for r in rows:
            case = ET.SubElement(xml, "testcase", classname=classname, name=r["id"].rsplit(".", 1)[1], time=str(r["seconds"]))
            for kind in ("failure", "error"):
                if kind in r:
                    ET.SubElement(case, kind).text = r[kind]
        ET.ElementTree(xml).write(evidence / ("TEST-" + classname + ".xml"), encoding="utf-8", xml_declaration=True)
    report = {"tests": result.testsRun, "failures": len(result.failures), "errors": len(result.errors),
              "skipped": len(result.skipped), "suites": len(classes), "installerExecuted": False,
              "liveRegistryOrInstallerSessionCalled": "test_msi_operation_contract" in (args.module or ()),
              "restrictedInstallerSessionOnly": "test_msi_operation_contract" in (args.module or ()),
              "installerOrProductMutationExecuted": False,
              "nativeReadOnlyFileAndMetadataChecks": any(m in (args.module or ()) for m in ("test_msi_operation_contract", "test_synthetic_write_fault", "test_msi_header_diagnostics", "test_msi_summary_output_contract", "test_header_probe_only")),
              "testOnlyHeaderProbeFakeContext": "test_header_probe_only" in (args.module or ()),
              "testOnlyHeaderApiFaultShims": any(m in (args.module or ()) for m in ("test_msi_header_diagnostics", "test_msi_summary_output_contract")),
              "actualInstallerCustomActionServerExecuted": False,
              "exit": 0 if result.wasSuccessful() else 1}
    (evidence / "RESULT.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))
    return report["exit"]


if __name__ == "__main__":
    sys.exit(main())
