"""Sequential acceptance for two explicitly selected, corrected synthetic MSI packages only."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import native_package_probe as probe
from msi_safety import Database


def fixture(base, label):
    env = os.environ.copy()
    env.update(LOCALAPPDATA=str(base / "local-appdata"), APPDATA=str(base / "roaming"),
               TEMP=str(base / "temp"), TMP=str(base / "temp"))
    for name in ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"]:
        env.pop(name, None)
    command = [r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe",
               "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-cp",
               (probe.REPO / "app/desktopApp/build/readonly-r0/runtime-classpath.txt").read_text(),
               "com.example.chatbar.desktop.DesktopWindowsPackageFixture", "verify", str(base)]
    result = subprocess.run(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
    (base / f"{label}-fixture-private.log").write_bytes(result.stdout)
    probe.save(base / f"{label}-fixture-result.json", {"exit": result.returncode,
               "dataMediaBootstrapAndThreeProtectedKeys": result.returncode == 0})
    assert result.returncode == 0 and b"WINDOWS_SYNTHETIC_DATA_MEDIA_BOOTSTRAP_THREE_PROTECTED_KEYS_PASS" in result.stdout


def run(base, action, installer=None, label=None):
    command = [sys.executable, "-I", str(Path(probe.__file__)), action, "--base", str(base), "--label", label]
    if installer is not None:
        command += ["--installer", str(installer)]
    probe.save(base / f"{label}-driver-command.json", command)
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=240)
    (base / f"{label}-driver-private.log").write_bytes(result.stdout)
    probe.save(base / f"{label}-driver-result.json", {"exit": result.returncode})
    assert result.returncode == 0, "OWNED_NATIVE_ACTION_FAILED_SEE_RETAINED_LOG"


def metadata(base, msi, label):
    info = probe.msi_info(msi)
    assert info["safetyVersion"] == "1"
    with Database(msi) as db:
        directories = db.rows("Directory", 3)
        assert any(row[0] == "INSTALLDIR" and row[1] == "LocalAppDataFolder" and
                   row[2].split("|")[-1] == probe.PRODUCT + "App" for row in directories), "SEPARATE_DEFAULT_PROGRAM_FOLDER_NOT_PROVEN"
        shortcuts = db.rows("Shortcut", 12) if any(row[0] == "Shortcut" for row in db.rows("_Tables", 1)) else []
        assert not shortcuts, "SYNTHETIC_PACKAGE_MUST_NOT_CREATE_SHELL_SHORTCUTS"
        probe.save(base / f"{label}-package-tables.json", {"info": info, "directories": directories, "shortcutCount": len(shortcuts)})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=["first", "upgrade"])
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--first", type=Path, required=True)
    parser.add_argument("--second", type=Path)
    args = parser.parse_args()
    assert os.name == "nt" and __debug__
    base = probe.task(args.base)
    driver_hash = probe.sha(Path(__file__))[:12]
    for name in ["native_acceptance.py", "native_package_probe.py", "msi_safety.py"]:
        with (base / f"{args.stage}-{driver_hash}-{name}").open("xb") as snapshot:
            snapshot.write(Path(__file__).with_name(name).read_bytes())
    if args.stage == "first":
        metadata(base, args.first, "corrected-a")
        run(base, "install", args.first, "corrected-install-a")
        for relative in ["UNMANAGED_SYNTHETIC_KEEP.txt", "app/UNMANAGED_SYNTHETIC_APP.txt", "runtime/UNMANAGED_SYNTHETIC_RUNTIME.txt"]:
            with (base / "program" / relative).open("xb") as file:
                file.write(b"SYNTHETIC_INSTALLER_UNMANAGED_FILE_MUST_SURVIVE")
        run(base, "launch-close", label="corrected-run-a")
        fixture(base, "corrected-a")
    else:
        assert args.second is not None
        metadata(base, args.second, "corrected-b")
        run(base, "fail-upgrade", args.second, "corrected-failure-b")
        run(base, "launch-close", label="corrected-rollback-a")
        fixture(base, "corrected-rollback-a")
        run(base, "install", args.second, "corrected-upgrade-b")
        run(base, "launch-close", label="corrected-run-b")
        fixture(base, "corrected-b")
        run(base, "reject-older", args.first, "corrected-reject-a")
        fixture(base, "corrected-after-reject")
        run(base, "uninstall", label="corrected-uninstall-b")
        fixture(base, "corrected-after-uninstall")
    probe.save(base / f"{args.stage}-acceptance-result.json", {"stage": args.stage, "passed": True,
               "onlySyntheticProduct": True, "providerRequests": False, "realArchiveRead": False,
               "powerLossAndCrossAccount": "NOT_TESTED", "occupiedMsiUpgrade": "NOT_TESTED"})
    print("WINDOWS_CORRECTED_" + args.stage.upper() + "_ACCEPTANCE_PASS")


if __name__ == "__main__":
    main()
