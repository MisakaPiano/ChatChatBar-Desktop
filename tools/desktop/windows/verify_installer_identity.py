"""Offline integration checks of the actual Desktop Gradle installer identity task. No installer runs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import unittest
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[3]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True, type=Path)
    options = parser.parse_args()
    evidence = options.evidence.resolve()
    assert evidence.is_relative_to(REPO / "app/desktopApp/build"), "TASK_BUILD_DIRECTORY_REQUIRED"
    evidence.mkdir(parents=True, exist_ok=False)
    script = REPO / "app/desktopApp/build.gradle.kts"
    (evidence / "build.gradle.kts").write_bytes(script.read_bytes())
    (evidence / "SOURCE_SHA256.txt").write_text(hashlib.sha256(script.read_bytes()).hexdigest())
    receipt = REPO / "app/desktopApp/build/reports/desktop-installer/identity.properties"
    records = []

    def invoke(label, *properties):
        command = [str(REPO / "app/gradlew.bat"), "--offline", "--no-daemon", "--no-configuration-cache",
                   "--no-build-cache", "--max-workers=1", "--console=plain",
                   "-Dorg.gradle.jvmargs=-Xmx1024m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8",
                   ":desktopApp:verifyDesktopInstallerIdentity", *properties]
        env = os.environ.copy()
        env["JAVA_HOME"] = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
        started = time.time()
        result = subprocess.run(command, cwd=REPO / "app", env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
        (evidence / f"{label}.log").write_bytes(result.stdout)
        record = {"case": label, "command": command, "exit": result.returncode, "seconds": round(time.time()-started, 3)}
        if result.returncode == 0:
            data = receipt.read_bytes()
            (evidence / f"{label}.properties").write_bytes(data)
            record["receipt"] = dict(line.split("=", 1) for line in data.decode().splitlines())
        records.append(record)
        (evidence / "RUNS.json").write_text(json.dumps(records, indent=2) + "\n")
        return result, record.get("receipt")

    class Identity(unittest.TestCase):
        def test_missing_explicit_version_rejects(self):
            result, _ = invoke("missing")
            self.assertNotEqual(0, result.returncode)
            self.assertIn(b"DESKTOP_INSTALLER_VERSION_EXPLICIT_REQUIRED", result.stdout)

        def test_preview_a_and_b_have_distinct_increasing_native_versions(self):
            result, a = invoke("preview-a", "-PdesktopVersion=0.1.0-preview.1000", "-PdesktopInstallerVersion=0.1.1000", "-PdesktopPackagingProfile=synthetic")
            self.assertEqual(0, result.returncode)
            result, b = invoke("preview-b", "-PdesktopVersion=0.1.0-preview.1001", "-PdesktopInstallerVersion=0.1.1001", "-PdesktopPreviousInstallerVersion=0.1.1000", "-PdesktopPackagingProfile=synthetic")
            self.assertEqual(0, result.returncode)
            self.assertNotEqual(a["installerVersion"], b["installerVersion"])
            self.assertEqual(a["upgradeUuid"], b["upgradeUuid"])
            self.assertEqual("ChatChatBarDesktopSynthetic", a["packageName"])

        def test_synthetic_and_desktop_product_identities_are_distinct(self):
            result, desktop = invoke("desktop", "-PdesktopInstallerVersion=0.1.1000")
            self.assertEqual(0, result.returncode)
            result, synthetic = invoke("synthetic", "-PdesktopInstallerVersion=0.1.1000", "-PdesktopPackagingProfile=synthetic")
            self.assertEqual(0, result.returncode)
            self.assertNotEqual(desktop["upgradeUuid"], synthetic["upgradeUuid"])
            self.assertNotEqual(desktop["packageName"], synthetic["packageName"])

        def test_same_and_older_versions_reject(self):
            for current in ["0.1.1000", "0.1.999"]:
                result, _ = invoke("not-increasing-" + current, "-PdesktopInstallerVersion=" + current, "-PdesktopPreviousInstallerVersion=0.1.1000")
                self.assertNotEqual(0, result.returncode)
                self.assertIn(b"DESKTOP_INSTALLER_VERSION_NOT_INCREASING", result.stdout)

        def test_bad_version_and_range_reject(self):
            for value in ["0.1.1000.1", "0.1.65536", "256.1.1", "00.1.1"]:
                result, _ = invoke("invalid-" + value, "-PdesktopInstallerVersion=" + value)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(b"DESKTOP_INSTALLER_VERSION_", result.stdout)

        def test_invalid_profile_rejects(self):
            result, _ = invoke("bad-profile", "-PdesktopInstallerVersion=0.1.1000", "-PdesktopPackagingProfile=studio")
            self.assertNotEqual(0, result.returncode)
            self.assertIn(b"DESKTOP_PACKAGING_PROFILE_INVALID", result.stdout)

    suite = unittest.defaultTestLoader.loadTestsFromTestCase(Identity)
    outcome = unittest.TextTestRunner(verbosity=2).run(suite)
    report = {"tests": outcome.testsRun, "failures": len(outcome.failures), "errors": len(outcome.errors), "skipped": len(outcome.skipped), "gradleInvocations": len(records)}
    (evidence / "RESULT.json").write_text(json.dumps(report, indent=2) + "\n")
    # The raw run logs plus unittest failure details remain the authority; this is not JVM JUnit.
    (evidence / "FAILURES.txt").write_text("\n".join(text for _, text in outcome.failures + outcome.errors))
    raise SystemExit(0 if outcome.wasSuccessful() else 1)


if __name__ == "__main__":
    main()
