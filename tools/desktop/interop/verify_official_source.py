"""Prove extracted main/build sources match the pinned Git object; do not inspect preset assets."""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

PIN = "1198a13cf3008de1358b8a69aa5e40b64797c8d9"
repo, scope = map(lambda x: Path(x).resolve(), sys.argv[1:3])
tree = subprocess.check_output(["git", "-C", str(repo), "ls-tree", "-r", PIN], text=True)
checked = []
eol_normalized = []
for line in tree.splitlines():
    identity, path = line.split("\t", 1)
    selected = path.startswith("app/app/src/main/java/") and path.endswith(".kt")
    selected |= path in ("app/app/build.gradle.kts", "app/build.gradle.kts", "app/gradle/libs.versions.toml", "app/app/src/main/AndroidManifest.xml")
    if not selected:
        continue
    data = (scope / "official" / path).read_bytes()
    actual = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
    if actual != identity.split()[2]:
        normalized = data.replace(b"\r\n", b"\n")
        canonical = hashlib.sha1(b"blob " + str(len(normalized)).encode() + b"\0" + normalized).hexdigest()
        assert canonical == identity.split()[2], f"Official main/build source changed beyond CRLF: {path}"
        eol_normalized.append(path)
    checked.append(path)
assert checked
report = {"officialCommit": PIN, "verifiedMainAndBuildFiles": len(checked), "result": "PASS",
          "crlfNormalizedFiles": len(eol_normalized), "comparison": "Git blob SHA-1; Windows CRLF normalized only when required",
          "officialReleaseSignature": False, "instrumentationSeparateFromMain": True,
          "presetAssetContentsTested": False}
(scope / "official-source-proof.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report))
