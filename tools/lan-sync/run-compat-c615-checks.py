"""Focused cloud-only checks for the a6bb/c615 integration branch."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("scope", choices=("shared", "desktop"))
    parser.add_argument("--report-only", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    manifest = json.loads((Path(__file__).with_name("compat-c615-test-scope.json")).read_text(encoding="utf-8"))
    classes = manifest["scopes"][args.scope]
    if not classes or len(classes) != len(set(classes)):
        raise ValueError("COMPAT_SCOPE_INVALID")
    if not args.report_only:
        gradle = root / "app" / ("gradlew.bat" if os.name == "nt" else "gradlew")
        if os.name != "nt":
            gradle.chmod(gradle.stat().st_mode | 0o100)
        command = [str(gradle), f":{'sharedCore' if args.scope == 'shared' else 'desktopApp'}:test"]
        for name in classes:
            command += ["--tests", name]
        if args.scope == "shared":
            command += [":app:compileLanPreviewKotlin", ":app:compileLanPreviewAndroidTestKotlin",
                        ":app:testLanPreviewUnitTest", "--tests",
                        "com.example.chatbar.lanSync.AndroidLanSyncCloudWorkTest", "-PlanSyncDeviceTests=true"]
        else:
            command += ["-PdesktopTestHeadless=true"]
        command += ["--rerun-tasks", "--no-build-cache", "--continue", "--no-daemon", "--stacktrace"]
        return subprocess.run(command, cwd=root / "app").returncode

    module = "sharedCore" if args.scope == "shared" else "desktopApp"
    reports = root / "app" / module / "build/test-results/test"
    checks = [(name, reports / f"TEST-{name}.xml") for name in classes]
    if args.scope == "shared":
        name = "com.example.chatbar.lanSync.AndroidLanSyncCloudWorkTest"
        checks.append((name, root / "app/app/build/test-results/testLanPreviewUnitTest" / f"TEST-{name}.xml"))
    result = {"scope": args.scope, "headSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
              "sourceSha": manifest["sourceSha"], "integrationTargetSha": manifest["integrationTargetSha"],
              "classes": [], "problems": [], "tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for name, path in checks:
        if not path.is_file():
            result["problems"].append(f"MISSING_CLASS:{name}")
            continue
        suite = ET.parse(path).getroot()
        cases = suite.findall("testcase")
        counts = {key: sum(len(case.findall(tag)) > 0 for case in cases)
                  for key, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped"))}
        counts["tests"] = len(cases)
        if not cases or any(case.get("classname") != name for case in cases):
            result["problems"].append(f"CLASS_NOT_EXECUTED:{name}")
        if any(int(suite.get(key, "0")) != count for key, count in counts.items()):
            result["problems"].append(f"COUNT_MISMATCH:{name}")
        result["classes"].append({"name": name, **counts})
        for key, count in counts.items():
            result[key] += count
    if result["failures"] or result["errors"] or result["skipped"]:
        result["problems"].append("CASES_NOT_ALL_EXECUTED_PASS")
    output = root / "app/build/reports/compat-c615" / f"{args.scope}.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, sort_keys=True))
    return 1 if result["problems"] else 0


if __name__ == "__main__":
    sys.exit(main())
