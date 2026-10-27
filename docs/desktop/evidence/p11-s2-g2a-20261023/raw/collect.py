"""Bounded G2A evidence collection; repo sources and self-created build artifacts only."""
import hashlib
import json
import re
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[5]
RAW = Path(__file__).resolve().parent
CONTROL = "648014ffda7c9e50029de8474faf36e011783bad"
OFFICIAL = "1198a13cf3008de1358b8a69aa5e40b64797c8d9"
MAIN = "app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/"
TEST = "app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/backup/"
NEW = [MAIN + name for name in ["DesktopCredentialImportPlan.kt", "RecoverableIntentContract.kt"]] + [TEST + name for name in ["DesktopCredentialImportPlanTest.kt", "RecoverableIntentContractTest.kt", "G2AOfficialWireTest.kt"]]

def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf8")

def identity(path, commit=None):
    data = git("show", f"{commit}:{path}") if commit else (ROOT / path).read_bytes()
    return {"path": path, "commit": commit, "bytes": len(data), "sha256": sha(data),
            "gitBlob": hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()}

def sources():
    records = []
    for path in NEW:
        record = identity(path)
        destination = RAW / "green" / "source" / Path(path).name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / path, destination)
        records.append(record)
    dump(RAW / "new-source-identities.json", records)
    previous = ROOT / "docs/desktop/evidence/p11-s2-g1a-credential-boundary-20261023/raw/production-source-identities.json"
    boundaries = []
    for old in json.loads(previous.read_text(encoding="utf-8-sig")):
        canonical = identity(old["path"], CONTROL)
        workspace = identity(old["path"])
        unchanged = canonical["gitBlob"] == old["blob"] and workspace["sha256"] == old["workspaceSha256"]
        assert unchanged, old["path"]
        boundaries.append({**canonical, "workspaceBytes": workspace["bytes"], "workspaceSha256": workspace["sha256"],
                           "sameExactWorkspaceBytesAsG1A": True, "priorEvidenceBlob": old["blob"]})
    dump(RAW / "existing-production-identities.json", boundaries)
    old_tests = ROOT / "docs/desktop/evidence/p11-s2-g1a-credential-boundary-20261023/raw/test-source-identities.json"
    paths = [item["path"] for item in json.loads(old_tests.read_text(encoding="utf-8-sig"))]
    paths += [TEST + "MomentArchiveReadOnlyValidatorTest.kt", TEST + "DesktopReadOnlyBackupRedTest.kt"]
    dump(RAW / "regression-test-identities.json", [identity(path, CONTROL) for path in paths])
    old_official = ROOT / "docs/desktop/evidence/p11-s2-g1a-credential-boundary-20261023/raw/official-source-identities.json"
    paths = [item["path"] for item in json.loads(old_official.read_text(encoding="utf-8-sig"))]
    paths += ["app/app/src/main/java/com/example/chatbar/data/local/entity/AppSettings.kt", "app/build.gradle.kts"]
    dump(RAW / "official-source-identities.json", [identity(path, OFFICIAL) for path in paths])
    baseline = "5e76a9cb841736bbbf3499a2e35e5789af4c5ca8"
    dump(RAW / "baseline-source-identities.json", [identity("app/app/src/main/java/com/example/chatbar/data/local/entity/ModelConfig.kt", baseline)])
    checks = []
    for prefix in ["app/desktopApp/src/main", "app/sharedCore/src/main", "app/app/src/main"]:
        delta = git("diff", "--name-only", CONTROL, "--", prefix).decode()
        assert not delta
        checks.append({"path": prefix, "controlTree": git("rev-parse", f"{CONTROL}:{prefix}").decode().strip(),
                       "trackedDiffEmpty": True, "note": "New untracked G2A files are separately indexed; projection is rechecked after commit."})
    dump(RAW / "production-projection.json", checks)
    patterns = ["DesktopCredentialImportPlan", "ImportCredential", "CredentialMappingDecision", "CandidateRecoveryIntent", "CandidateParticipant", "RecoverableIntentContract", "CandidateIntent"]
    found = subprocess.run(["rg", "-l", "|".join(patterns), "app/desktopApp/src/main", "app/sharedCore/src/main", "app/app/src/main", "--glob", "*.kt"], cwd=ROOT, capture_output=True, check=False)
    paths = sorted(line.replace("\\", "/") for line in found.stdout.decode().splitlines())
    assert paths == sorted(NEW[:2]), paths
    (RAW / "nonwiring-callsite-audit.txt").write_text("Command: rg -l '" + "|".join(patterns) + "' <three src/main trees> --glob '*.kt'\n" + "\n".join(paths) + "\nOnly the two new files reference these contracts.\n", encoding="utf8")

def results():
    build = ROOT / "app/desktopApp/build"
    destination = RAW / "green"
    junit = destination / "junit"
    junit.mkdir(parents=True, exist_ok=True)
    classes = ["DesktopCredentialImportPlanTest", "RecoverableIntentContractTest", "G2AOfficialWireTest", "P11S2CredentialTransactionBoundaryProbeTest", "WindowsSecretStoreTest", "DesktopSecureModelRepositoryTest", "DesktopSecureEmbeddingRepositoryTest", "DesktopFullBackupArchiveTest", "DesktopBackupReadOnlyInspectorTest", "MomentArchiveReadOnlyValidatorTest", "DesktopFullBackupCommitJournalTest", "DesktopStartupGateTest", "DesktopReadOnlyBackupRedTest"]
    summary = []
    for name in classes:
        files = list((build / "test-results/test").glob(f"TEST-*.{name}.xml"))
        assert len(files) == 1, name
        target = junit / files[0].name
        shutil.copyfile(files[0], target)
        root = ET.parse(target).getroot()
        summary.append({"class": root.attrib["name"], **{key: int(root.attrib[key]) for key in ["tests", "failures", "errors", "skipped"]}, "xmlSha256": sha(target.read_bytes())})
    dump(RAW / "green" / "junit-summary.json", summary)
    # Retain current synthetic fixtures only. G1A's old directory is not copied wholesale.
    shutil.copytree(build / "p11-s2-g2a/fixtures", destination / "fixtures", dirs_exist_ok=True)
    dump(RAW / "fixture-identities.json", [{"path": str(path.relative_to(destination)).replace("\\", "/"), "bytes": path.stat().st_size, "sha256": sha(path.read_bytes())} for path in sorted((destination / "fixtures").rglob("*")) if path.is_file()])
    totals = {key: sum(item[key] for item in summary) for key in ["tests", "failures", "errors", "skipped"]}
    print(json.dumps({"suites": len(summary), **totals}))

def index():
    files = [path for path in sorted(RAW.rglob("*")) if path.is_file() and path.name != "raw-index.json" and "__pycache__" not in path.parts]
    dump(RAW / "raw-index.json", [{"path": str(path.relative_to(RAW)).replace("\\", "/"), "bytes": path.stat().st_size, "sha256": sha(path.read_bytes())} for path in files])
    print(f"Raw indexed: {len(files)}; index sha256={sha((RAW / 'raw-index.json').read_bytes())}")

def diagnostics():
    # Never scan ambient credentials, user directories or protected attachments.
    sources = [(ROOT / path).read_text(encoding="utf8") for path in NEW]
    sources += [(ROOT / "app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/P11S2CredentialTransactionBoundaryProbeTest.kt").read_text(encoding="utf8")]
    needles = sorted(set(re.findall(r"FAKE_(?:G2A|P11_G1A)_[A-Z0-9_]+", "\n".join(sources))))
    diagnostic_files = list(RAW.glob("*.log")) + list((RAW / "green/junit").glob("*.xml")) + list((RAW / "red/junit").glob("*.xml"))
    hits = []
    for path in diagnostic_files:
        value = path.read_text(encoding="utf-8-sig", errors="replace")
        if any(needle in value for needle in needles): hits.append(str(path.relative_to(RAW)))
    assert not hits, hits
    dump(RAW / "diagnostic-redaction-audit.json", {"filesChecked": len(diagnostic_files), "syntheticNeedleCount": len(needles), "hits": hits,
         "scope": "Known synthetic test values in logs/XML, plus dedicated model-summary assertions; not a proof about arbitrary future adapter inputs.",
         "fixtureException": "Explicitly synthetic JSON/archive fixtures intentionally retain synthetic credentials. No real values exist in this task."})

def boundary_samples():
    build = ROOT / "app/desktopApp/build/p11-s2-g1a-credential-probe"
    started = (RAW / "bounded.log").stat().st_ctime
    records = []
    for path in sorted(build.iterdir()):
        if path.is_file() and path.stat().st_mtime >= started:
            data = path.read_bytes()
            records.append({"name": path.name, "bytes": len(data), "sha256": sha(data)})
            if path.suffix in (".json", ".txt"):
                out = RAW / "green/g1a-state" / path.name
                out.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, out)
    assert records
    dump(RAW / "g1a-fresh-artifact-identities.json", records)
    print(f"Current G1A artifact identities: {len(records)}; JSON/state and cleanup retained (not all fake ciphertext copies).")

if __name__ == "__main__":
    if sys.argv[1] == "sources": sources()
    elif sys.argv[1] == "results": results()
    elif sys.argv[1] == "index": index()
    elif sys.argv[1] == "diagnostics": diagnostics()
    elif sys.argv[1] == "boundary-samples": boundary_samples()
    else: raise SystemExit("unknown evidence action")
