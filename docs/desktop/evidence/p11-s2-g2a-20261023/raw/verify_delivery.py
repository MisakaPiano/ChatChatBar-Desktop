"""Verify exact scoped index/commit bytes; never inspect unrelated untracked files."""
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[5]
EVIDENCE = "docs/desktop/evidence/p11-s2-g2a-20261023"
RAW = ROOT / EVIDENCE / "raw"
CONTROL = "648014ffda7c9e50029de8474faf36e011783bad"

def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)

def digest(value):
    return hashlib.sha256(value).hexdigest()

def main():
    reference = sys.argv[1]
    new = json.loads((RAW / "new-source-identities.json").read_text(encoding="utf8"))
    prefixes = ["app/desktopApp/src/main", "app/sharedCore/src/main", "app/app/src/main"]
    allowed = {item["path"] for item in new} | {".agents/skills/chatbar-desktop-full-backup/SKILL.md"}
    if reference == "--staged":
        names = git("diff", "--cached", "--name-only", CONTROL).decode().splitlines()
        changes = git("diff", "--cached", "--name-status", CONTROL).decode().splitlines()
        read = lambda path: git("show", ":" + path)
    else:
        names = git("diff", "--name-only", CONTROL, reference).decode().splitlines()
        changes = git("diff", "--name-status", CONTROL, reference).decode().splitlines()
        archive = tarfile.open(fileobj=io.BytesIO(git("archive", reference, EVIDENCE)), mode="r:")
        read = lambda path: archive.extractfile(path).read() if path.startswith(EVIDENCE + "/") else git("show", f"{reference}:{path}")
    assert all(path in allowed or path.startswith(EVIDENCE + "/") for path in names)
    for change in changes:
        status, path = change.split("\t", 1)
        assert status == ("M" if path.startswith(".agents/") else "A"), change
    projections = []
    for prefix in prefixes:
        old = {line.split("\t")[1]: line.split()[2] for line in git("ls-tree", "-r", CONTROL, "--", prefix).decode().splitlines()}
        if reference == "--staged":
            current = {line.split("\t")[1]: line.split()[1] for line in git("ls-files", "--stage", "--", prefix).decode().splitlines()}
        else:
            current = {line.split("\t")[1]: line.split()[2] for line in git("ls-tree", "-r", reference, "--", prefix).decode().splitlines()}
        additions = set(current) - set(old)
        expected = {item["path"] for item in new[:2]} if prefix == prefixes[0] else set()
        assert additions == expected
        assert all(current.get(path) == blob for path, blob in old.items())
        projections.append({"prefix": prefix, "existingFilesEqual": len(old), "onlyAdditions": sorted(additions)})
    for item in new:
        actual = read(item["path"])
        assert len(actual) == item["bytes"] and digest(actual) == item["sha256"], item["path"]
        assert (ROOT / item["path"]).read_bytes() == actual
    index = json.loads(read(EVIDENCE + "/raw/raw-index.json"))
    for item in index:
        actual = read(EVIDENCE + "/raw/" + item["path"])
        assert len(actual) == item["bytes"] and digest(actual) == item["sha256"], item["path"]
    result = {"reference": reference, "control": CONTROL, "scopedChangedFiles": len(names), "newTestedSourcesEqual": len(new),
              "rawArtifactsEqual": len(index), "rawIndexSha256": digest(read(EVIDENCE + "/raw/raw-index.json")), "projections": projections}
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == "__main__": main()
