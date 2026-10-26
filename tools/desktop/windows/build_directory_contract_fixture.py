"""Build a real SYNTHETIC MSI from frozen runtime classes; never starts the app/installer."""
import argparse
import ctypes as C
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import zipfile
import msi_safety
import copy
import importlib.util

if not __debug__:
    raise SystemExit("VALIDATION_REQUIRED")


def sha(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1048576), b""):
            h.update(chunk)
    return h.hexdigest()


def save(path, data):
    with path.open("x", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def available_memory():
    class Memory(C.Structure):
        _fields_ = [("length", C.c_uint32), ("load", C.c_uint32)] + [(name, C.c_uint64) for name in
                   ("totalPhys", "availPhys", "totalPage", "availPage", "totalVirtual", "availVirtual", "extended")]
    memory = Memory(); memory.length = C.sizeof(memory)
    assert C.windll.kernel32.GlobalMemoryStatusEx(C.byref(memory)), "MEMORY_OBSERVATION_FAILED"
    return memory.availPhys


def check_resource_budget(budget, accept_memory_pressure=False):
    budget["standardMemoryMinimum"] = 1073741824
    budget["standardMemoryGatePassed"] = budget["availableMemory"] >= 1073741824
    budget["memoryPressureExplicitlyAccepted"] = bool(accept_memory_pressure)
    assert budget["standardMemoryGatePassed"] or accept_memory_pressure, "INSUFFICIENT_MEMORY_FOR_BOUNDED_PACKAGE"
    # An explicit performance tradeoff must not waive disk or integrity admission.
    assert budget["hFree"] >= 1610612736 and budget["iFree"] >= 1073741824, "INSUFFICIENT_PACKAGE_DISK"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("source-image", "manifest", "jdk", "wix", "output", "scratch", "guard-build"):
        parser.add_argument("--" + key, type=Path, required=True)
    parser.add_argument("--sequence", type=int, required=True)
    parser.add_argument("--expected-guard-sha256", required=True)
    parser.add_argument("--preflight", action="store_true", help="Read-only source/tool/resource checks; no output/image/MSI creation")
    parser.add_argument("--accept-memory-pressure", action="store_true", help="Explicitly accept possible paging/slowness; retain disk, source and integrity checks")
    args = parser.parse_args()
    assert 1100 <= args.sequence <= 1199
    repo = Path(__file__).resolve().parents[3]
    output = args.output.resolve(); scratch = args.scratch.resolve()
    assert output.is_relative_to(repo / "app/desktopApp/build") and not output.exists()
    assert scratch.drive.upper() == "I:" and not scratch.exists()
    package_temp = repo / "app/desktopApp/build/jpkg" / ("dir2-" + str(args.sequence) + "-r01")
    assert not package_temp.exists(), "FRESH_JPACKAGE_SCRATCH_REQUIRED"
    for path in [output.parent, scratch.parent, args.source_image, args.guard_build]:
        for parent in [path, *path.parents]:
            if parent.exists():
                assert not parent.is_symlink() and not parent.stat().st_file_attributes & 0x400
    budget = {"availableMemory": available_memory(), "hFree": shutil.disk_usage(output.parent).free,
              "iFree": shutil.disk_usage(scratch.parent).free, "javaHeapLimit": 268435456,
              "expectedCombinedWorkingSetBudget": 805306368, "reservedMemory": 268435456}
    try:
        check_resource_budget(budget, args.accept_memory_pressure)
    finally:
        print(json.dumps({"resourceObservation": budget}), flush=True)
    assert not subprocess.check_output(["git", "diff", "946c3791153634b0af65286313a497d141d5c598", "--",
        "app/desktopApp/src/main", "app/sharedCore/src/main", "app/sharedCore/build.gradle.kts",
        "app/settings.gradle.kts", "app/gradle/libs.versions.toml"], cwd=repo), "FROZEN_RUNTIME_SOURCE_CHANGED"
    manifest = json.loads(args.manifest.read_text())
    assert len(manifest) == 217
    expected = {row["path"]: row for row in manifest}
    actual = {p.relative_to(args.source_image).as_posix(): p for p in args.source_image.rglob("*") if p.is_file()}
    assert set(expected) == set(actual), "FROZEN_IMAGE_SET_CHANGED"
    for name, path in actual.items():
        assert path.stat().st_size == expected[name]["bytes"] and sha(path) == expected[name]["sha256"], "FROZEN_IMAGE_CHANGED"
    assert (args.jdk / "bin/java.exe").is_file() and (args.wix / "candle.exe").is_file() and (args.wix / "light.exe").is_file(), "EXISTING_PACKAGE_TOOLS_REQUIRED"
    spec = importlib.util.spec_from_file_location("guard_reuse_preflight", Path(__file__).with_name("build_directory_guard.py"))
    builder = importlib.util.module_from_spec(spec); spec.loader.exec_module(builder)
    builder.reuse_production(args.guard_build, args.expected_guard_sha256)
    if args.preflight:
        print(json.dumps({"status": "READY_WAIT_SLOT_ACK", "budget": budget, "frozenFiles": len(actual),
                          "sourceVerified": True, "guardReused": True, "newImageOrMsiCreated": False}))
        return
    output.mkdir(); scratch.mkdir()
    save(output / "BUDGET.json", budget)
    save(output / "SOURCE.json", {"packagingBase": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip(),
        "runtimeClassesCompiledAt": "946c3791153634b0af65286313a497d141d5c598", "sameRuntimeSourcesVerified": True,
        "frozenManifestSha256": sha(args.manifest), "syntheticOnly": True, "sequence": args.sequence})
    for name in ("build_directory_contract_fixture.py", "msi_safety.py", "build_directory_guard.py", "directory_guard.c", "operation_guard.h"):
        (output / name).write_bytes(Path(__file__).with_name(name).read_bytes())
    env = os.environ.copy()
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        env.pop(name, None)
    env["PATH"] = str(args.wix) + os.pathsep + env["PATH"]
    java = args.jdk / "bin/java.exe"
    java_tool = [str(java), "-Xmx256m", "-XX:ActiveProcessorCount=1", "-m", "jdk.jpackage/jdk.jpackage.main.Main"]
    input_dir = scratch / "input"; input_dir.mkdir()
    main_jar = None
    identity = ("version=0.1.0-preview.{0}\ncommit={1}\ninstallerVersion=0.1.{0}\ninstallerVersionAssigned=true\npackagingProfile=synthetic\n".format(
        args.sequence, subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip())).encode()
    for path in (args.source_image / "app").rglob("*"):
        if not path.is_file() or path.name in (".jpackage.xml", "ChatChatBarDesktop.cfg"):
            continue
        target = input_dir / path.relative_to(args.source_image / "app"); target.parent.mkdir(parents=True, exist_ok=True)
        if path.suffix != ".jar":
            shutil.copyfile(path, target); continue
        with zipfile.ZipFile(path) as original:
            if "desktop-build.properties" not in original.namelist():
                shutil.copyfile(path, target); continue
            assert main_jar is None
            assert len(original.namelist()) == len(set(original.namelist()))
            assert not any(n.upper().endswith((".SF", ".RSA", ".DSA", ".EC")) for n in original.namelist())
            main_jar = path.name
            with zipfile.ZipFile(target, "x") as changed:
                for item in original.infolist():
                    if item.filename == "desktop-build.properties":
                        changed.writestr(copy.copy(item), identity)
                    else:
                        with original.open(item.filename) as src, changed.open(copy.copy(item), "w") as dst:
                            shutil.copyfileobj(src, dst, 1048576)
            with zipfile.ZipFile(target) as changed:
                assert original.namelist() == changed.namelist()
                def entry_hash(archive, name):
                    digest = hashlib.sha256()
                    with archive.open(name) as stream:
                        for chunk in iter(lambda: stream.read(1048576), b""):
                            digest.update(chunk)
                    return digest.hexdigest()
                assert all(entry_hash(original, name) == entry_hash(changed, name) for name in original.namelist() if name != "desktop-build.properties")
    assert main_jar
    save(output / "IDENTITY_OVERLAY.json", {"mainJar": main_jar, "onlyChangedEntry": "desktop-build.properties",
        "newIdentity": identity.decode(), "allOtherEntriesUnchanged": True, "notFreshClassCompilation": True})

    def run(label, command):
        save(output / (label + ".command.json"), command)
        start = time.monotonic()
        with (output / (label + ".log")).open("xb") as log:
            result = subprocess.run(command, env=env, cwd=scratch, stdout=log, stderr=subprocess.STDOUT, timeout=600)
        (output / (label + ".exit")).write_text(str(result.returncode))
        save(output / (label + ".timing.json"), {"seconds": time.monotonic() - start, "exit": result.returncode})
        assert result.returncode == 0, label + "_FAILED"

    product = "ChatChatBarDesktopSynthetic"
    run("app-image", java_tool + ["--type", "app-image", "--name", product, "--app-version", "0.1." + str(args.sequence),
        "--input", str(input_dir), "--main-jar", main_jar, "--main-class", "com.example.chatbar.desktop.MainKt",
        "--runtime-image", str(args.source_image / "runtime"), "--dest", str(scratch / "image"),
        "--icon", str(repo / "app/desktopApp/src/main/resources/brand/ccb.ico")])
    image = scratch / "image" / product
    # Preserve the known classpath and runtime options, changing only the package-version option.
    cfg = (args.source_image / "app/ChatChatBarDesktop.cfg").read_text()
    assert "java-options=-Djpackage.app-version=0.1.1010" in cfg
    cfg = cfg.replace("java-options=-Djpackage.app-version=0.1.1010", "java-options=-Djpackage.app-version=0.1." + str(args.sequence))
    (image / "app" / (product + ".cfg")).write_text(cfg)
    assert len(list((image / "app").glob("*.jar"))) == 63
    raw_dir = output / "raw-package"; raw_dir.mkdir()
    run("raw-msi", java_tool + ["--type", "msi", "--name", product, "--app-version", "0.1." + str(args.sequence),
        "--app-image", str(image), "--dest", str(raw_dir), "--temp", str(package_temp), "--verbose",
        "--win-per-user-install", "--win-upgrade-uuid", "58e95fd1-709d-5813-9b5b-f518a4612bae",
        "--install-dir", "ChatChatBarDesktopSyntheticApp", "--vendor", "ChatChatBar", "--description", "ChatChatBar Desktop"])
    raw, = raw_dir.glob("*.msi")
    final = output / raw.name
    receipt = msi_safety.prepare(raw, output / "safety", sha(raw), "synthetic", final, args.guard_build, args.expected_guard_sha256)
    with msi_safety.Database(final) as db:
        properties = msi_safety.verify(db, "synthetic")
        binaries = dict(db.sql("SELECT `Name`,`Data` FROM `Binary`", 2, streams=True))
        assert binaries["CcbDirectoryGuard"]["sha256"] == args.expected_guard_sha256
        result = {"file": final.name, "bytes": final.stat().st_size, "sha256": sha(final),
                  "ProductCode": properties["ProductCode"], "ProductVersion": properties["ProductVersion"],
                  "UpgradeCode": properties["UpgradeCode"], "directoryContract": properties["CCB_DIRECTORY_CONTRACT"],
                  "componentContract": properties["CCB_COMPONENT_CONTRACT"], "packageCode": db.package_code(),
                  "operationContract": properties["CCB_OPERATION_CONTRACT"],
                  "embeddedGuard": binaries["CcbDirectoryGuard"], "applicationCabinetsUnchanged": receipt["applicationCabinets"],
                  "installed": False, "signature": "UNSIGNED_TEST_ONLY"}
    save(output / "RESULT.json", result)
    print(json.dumps(result))


if __name__ == "__main__":
    main()
