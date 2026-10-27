"""Metadata-only self-check of this task's public synthetic evidence. Only exact task-owned synthetic round roots may be snapshotted; no general build/user-data scan."""
from pathlib import Path
import hashlib
import json
import subprocess
import zipfile
import xml.etree.ElementTree as ET
from collections import Counter

repo = Path(__file__).resolve().parents[4]
evidence = repo / "docs/desktop/evidence/p11-lr5-20261024"
raw = evidence / "raw"
packet = evidence / "P11_LR5_PROJECT_REVIEW_PACKET"
rounds = []
children = []
for directory in sorted(raw.iterdir()):
    if not directory.is_dir() or not (directory / "gradle.log").exists():
        continue
    assert (directory / "exit.txt").exists(), "ROUND_STILL_RUNNING"
    sandbox = repo / "app/desktopApp/build/p11-lr5" / directory.name
    archive = directory / "synthetic-final-state.zip"
    if sandbox.exists() and not archive.exists():
        nodes = sorted(sandbox.rglob("*"))
        assert not any(node.is_symlink() for node in nodes), "UNSAFE_SYNTHETIC_STATE"
        files = [node for node in nodes if node.is_file()]
        assert len(files) <= 5000 and sum(node.stat().st_size for node in files) <= 32 * 1024 * 1024
        temp = archive.with_suffix(".part")
        with zipfile.ZipFile(temp, "x", compression=zipfile.ZIP_DEFLATED) as output:
            for node in files:
                assert node.resolve().is_relative_to(sandbox.resolve()), "STATE_ESCAPE"
                output.write(node, node.relative_to(sandbox).as_posix())
        temp.rename(archive)
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    suites = []
    for xml in sorted((directory / "junit").glob("*.xml")):
        root = ET.parse(xml).getroot()
        counts = {key: int(root.attrib[key]) for key in totals}
        assert len(root.findall("testcase")) == counts["tests"]
        assert root.attrib["name"].startswith("com.example.chatbar.desktop.lr5.")
        for key in totals:
            totals[key] += counts[key]
        suites.append(dict(path=xml.relative_to(repo).as_posix(), **counts))
    records = []
    for stdout in sorted((directory / "child").rglob("*.stdout")):
        lines = stdout.read_text(encoding="utf-8").splitlines()
        exitfile = stdout.with_name(stdout.name + ".exit")
        exitcode = exitfile.read_text().strip() if exitfile.exists() else "NOT_RECORDED"
        fields = dict(line.split("=", 1) for line in lines if "=" in line and not line.startswith("STAGE="))
        stages = [line.removeprefix("STAGE=") for line in lines if line.startswith("STAGE=")]
        if exitcode == "71":
            assert stages and "STATE" not in fields
        if "BUSINESS_AUTHORIZED" in fields:
            assert fields["BUSINESS_AUTHORIZED"] == "false"
        row = dict(round=directory.name, case=stdout.parent.name, run=stdout.stem,
                   exit=exitcode, state=fields.get("STATE", "HARD_EXIT" if exitcode == "71" else "NOT_RECORDED"),
                   reason=fields.get("REASON", "NONE"), lastStage=stages[-1] if stages else "NONE")
        records.append(row)
    rounds.append(dict(round=directory.name, suites=len(suites), **totals,
                       exit=int((directory / "exit.txt").read_text().strip()), junit=suites,
                       childInvocations=len(records), childExitCounts=dict(Counter(row["exit"] for row in records))))
    children.extend(records)
(packet / "TEST_ROUNDS.json").write_text(json.dumps(rounds, indent=2) + "\n", encoding="utf-8")
columns = ["round", "case", "run", "exit", "state", "reason", "lastStage"]
(packet / "CHILD_FAILURE_MATRIX.tsv").write_text("\t".join(columns) + "\n" + "".join("\t".join(str(row[k]) for k in columns) + "\n" for row in children), encoding="utf-8")

def identity(path):
    b = path.read_bytes()
    return dict(path=path.relative_to(repo).as_posix(), bytes=len(b), sha256=hashlib.sha256(b).hexdigest(),
                gitBlob=hashlib.sha1(b"blob " + str(len(b)).encode() + b"\0" + b).hexdigest())

items = [identity(p) for p in sorted(raw.rglob("*")) if p.is_file()]
sources = [identity(p) for p in sorted(Path(__file__).resolve().parent.rglob("*")) if p.is_file()]
final = raw / "protocol-green/source"
snapshots = []
for current in sorted((Path(__file__).resolve().parent / "src").glob("*.kt")):
    frozen = final / "src" / current.name
    assert current.read_bytes() == frozen.read_bytes(), "FINAL_SOURCE_CHANGED"
    snapshots.append(dict(current=identity(current), frozen=identity(frozen)))
head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
result = dict(attribution="Codex self-check; not independent Project review", headAtCollection=head,
              rawFileCount=len(items), raw=items, currentToolFiles=sources, finalKotlinSnapshots=snapshots)
(packet / "RAW_INDEX.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
print(json.dumps(dict(rounds=[{k:v for k,v in row.items() if k != "junit"} for row in rounds], rawFiles=len(items), finalKotlin=len(snapshots))))
