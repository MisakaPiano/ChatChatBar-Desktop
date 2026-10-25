"""Small headless process probe against an explicit frozen app image. Never install/scan data."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import traceback
import unittest
import xml.etree.ElementTree as ET

if not __debug__:
    raise SystemExit("VALIDATION_REQUIRED")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_bytes((json.dumps(value, indent=2) + "\n").encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("image", "compiler", "java", "round"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--compiler-support", type=Path, action="append", default=[])
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[4]
    base = args.round.resolve()
    allowed = repo / "app/desktopApp/build/preview-continuous-private-20261025"
    assert base.is_relative_to(allowed.resolve()) and base != allowed.resolve()
    assert not base.exists(), "Fresh round required; old evidence must survive"
    base.mkdir()
    source = Path(__file__).with_name("SyntheticSnapshotRecoveryProbe.kt")
    sources = [source, Path(__file__)]
    for item in sources:
        (base / item.name).write_bytes(item.read_bytes())
    jars = sorted((args.image / "app").glob("*.jar"))
    assert len(jars) == 63
    identity = [{"name": p.name, "bytes": p.stat().st_size, "sha256": sha(p)} for p in jars]
    save(base / "INPUT_JARS.json", identity)
    save(base / "SOURCE_IDENTITY.json", {
        "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip(),
        "sources": [{"file": str(p.relative_to(repo)), "sha256": sha(p),
                     "gitBlob": subprocess.check_output(["git", "hash-object", str(p)], cwd=repo, text=True).strip()}
                    for p in sources],
        "compiler": {"bytes": args.compiler.stat().st_size, "sha256": sha(args.compiler)},
        "compilerSupport": [{"name": p.name, "bytes": p.stat().st_size, "sha256": sha(p)}
                            for p in args.compiler_support],
        "productionUnchangedSince": "946c3791153634b0af65286313a497d141d5c598",
    })
    assert not subprocess.check_output([
        "git", "diff", "946c3791153634b0af65286313a497d141d5c598", "--",
        "app/desktopApp/src/main", "app/sharedCore/src/main"], cwd=repo)
    env = os.environ.copy()
    for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"):
        env.pop(key, None)
    for key in ("LOCALAPPDATA", "APPDATA", "USERPROFILE", "TEMP", "TMP"):
        directory = base / key.lower()
        directory.mkdir()
        env[key] = str(directory)
    cp = os.pathsep.join(map(str, jars))
    main_jar, = [p for p in jars if p.name.startswith("desktopApp-")]

    def execute(label, command):
        save(base / (label + ".command.json"), command)
        with (base / (label + ".log")).open("xb") as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, env=env,
                                    cwd=base, timeout=120)
        (base / (label + ".exit")).write_bytes(str(result.returncode).encode())
        return result.returncode

    compile_command = [str(args.java), "-Xmx256m", "-XX:ActiveProcessorCount=1", "-cp",
                       os.pathsep.join(map(str, [args.compiler, *args.compiler_support])) + os.pathsep + cp,
                       "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                       "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-Xfriend-paths=" + str(main_jar),
                       "-classpath", cp, "-d", str(base / "probe.jar"), str(base / source.name)]
    compile_exit = execute("compile", compile_command)
    if compile_exit:
        save(base / "RESULT.json", {"compileExit": compile_exit, "testsExecuted": 0})
        return compile_exit

    def child(label, mode, case):
        command = [str(args.java), "-Xmx128m", "-XX:ActiveProcessorCount=1", "-Djava.awt.headless=true",
                   "-Duser.home=" + str(base / "userprofile"), "-Djava.io.tmpdir=" + str(base / "temp"),
                   "-cp", str(base / "probe.jar") + os.pathsep + cp,
                   "com.example.chatbar.desktop.SyntheticSnapshotRecoveryProbe", mode, str(case)]
        assert execute(label, command) == 0, label

    def case(name):
        directory = base / name
        directory.mkdir()
        (directory / "SYNTHETIC_ONLY").write_bytes(b"snapshot-recovery-v1")
        (directory / "root").mkdir()
        return directory

    class ProcessRecoveryTest(unittest.TestCase):
        def test_restore_then_two_independent_repository_reopens(self):
            directory = case("roundtrip")
            child("roundtrip-seed", "seed", directory)
            child("roundtrip-restore", "restore", directory)
            child("roundtrip-reopen-1", "reopen", directory)
            child("roundtrip-reopen-2", "reopen", directory)

        def test_corrupt_selected_snapshot_rejects_before_live_changes(self):
            directory = case("corruption")
            child("corruption-seed", "seed", directory)
            selected = (directory / "SELECTED").read_text()
            target = directory / "root/backups" / selected / "payload/images/synthetic.png"
            assert target.is_file() and not target.is_symlink()
            target.write_bytes(b"synthetic-corrupted-media")
            child("corruption-reject", "reject-corrupt", directory)

    start = time.monotonic()
    with (base / "unittest.log").open("x", encoding="utf-8") as stream:
        result = unittest.TextTestRunner(stream=stream, verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(ProcessRecoveryTest))
    failures = {test.id(): detail for test, detail in result.failures}
    errors = {test.id(): detail for test, detail in result.errors}
    suite = ET.Element("testsuite", name="SyntheticSnapshotRecoveryProcessTest", tests=str(result.testsRun),
                       failures=str(len(failures)), errors=str(len(errors)), skipped=str(len(result.skipped)),
                       time=str(time.monotonic() - start))
    for name in unittest.defaultTestLoader.getTestCaseNames(ProcessRecoveryTest):
        test = ET.SubElement(suite, "testcase", classname="SyntheticSnapshotRecoveryProcessTest", name=name)
        for values, kind in ((failures, "failure"), (errors, "error")):
            detail = next((v for k, v in values.items() if k.endswith("." + name)), None)
            if detail:
                ET.SubElement(test, kind).text = detail
    ET.ElementTree(suite).write(base / "TEST-SyntheticSnapshotRecoveryProcessTest.xml", encoding="utf-8", xml_declaration=True)
    unchanged = identity == [{"name": p.name, "bytes": p.stat().st_size, "sha256": sha(p)} for p in jars]
    save(base / "RESULT.json", {"compileExit": 0, "tests": result.testsRun, "failures": len(failures),
                              "errors": len(errors), "skipped": len(result.skipped),
                              "inputJarsUnchanged": unchanged, "exit": 0 if result.wasSuccessful() and unchanged else 1})
    print((base / "RESULT.json").read_text())
    return 0 if result.wasSuccessful() and unchanged else 1


if __name__ == "__main__":
    sys.exit(main())
