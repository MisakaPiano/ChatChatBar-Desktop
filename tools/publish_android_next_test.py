"""Publish only a reviewed immutable synthetic Android test candidate, never a stable update."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile

inputs = Path("test-release-input")
plan = json.loads((inputs / "release-plan.json").read_text(encoding="utf-8"))
repo = os.environ["GITHUB_REPOSITORY"]
assert repo == plan["repository"] == "MisakaPiano/ChatChatBar-Desktop"
assert len(plan["source"]) == 40

def command(args):
    return subprocess.check_output(args, text=True).strip()

def api(path):
    return json.loads(command(["gh", "api", path]))

run = api(f"repos/{repo}/actions/runs/{plan['run']}")
assert run["head_sha"] == plan["source"] and run["conclusion"] == "success"
assert run["head_branch"] == "feature/android-next-review-20261115"
command(["gh", "run", "download", str(plan["run"]), "--repo", repo,
         "--name", plan["artifactName"], "--dir", "verified-candidate"])
root = Path("verified-candidate")
source = json.loads(next(root.rglob("source.json")).read_text())
assert source["source"] == plan["source"] and source["syntheticOnly"] and not source["realAccountCalls"]
identity = json.loads(next(root.rglob("apk-identity.json")).read_text())
apk = next(root.rglob("app-debug.apk"))
assert identity["sha256"] == plan["expectedApkSha256"]
assert identity["applicationId"] == plan["applicationId"] and identity["signatureVerified"]
assert hashlib.sha256(apk.read_bytes()).hexdigest() == identity["sha256"]
assert apk.stat().st_size == identity["bytes"]
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    assert all(b"SafeBatchOfflineActivity" not in archive.read(n) for n in archive.namelist() if n.endswith(".dex"))

counts = {"jvm": {k: 0 for k in ("tests", "failures", "errors", "skipped")},
          "native": {k: 0 for k in ("tests", "failures", "errors", "skipped")}}
for file in root.rglob("TEST-*.xml"):
    suite = ET.parse(file).getroot()
    kind = "native" if "androidTest-results" in file.as_posix() else "jvm"
    for key in counts[kind]:
        counts[kind][key] += int(suite.get(key, 0))
assert counts == plan["expectedCounts"]
assert counts["jvm"]["tests"] > 1400 and counts["native"]["tests"] >= 45
assert all(value["failures"] == value["errors"] == 0 for value in counts.values())
assert counts["native"]["skipped"] == 0

out = Path("public-test-assets")
out.mkdir()
(out / "CCB-Android-next-review-r1.apk").write_bytes(apk.read_bytes())
for name in ("CCB-Android-next-review-r1-test-data.zip",):
    (out / name).write_bytes((inputs / name).read_bytes())
(out / "Acceptance-checklist.txt").write_bytes((inputs / "验收清单.txt").read_bytes())
with zipfile.ZipFile(out / "CCB-Android-next-review-r1-test-data.zip") as archive:
    assert archive.testzip() is None
    assert set(archive.namelist()) == {"集中验收完全合成卡.chatbar-character", "完全合成-备选与修复验收.cbbackup", "测试清单.txt"}
    assert hashlib.sha256(archive.read("完全合成-备选与修复验收.cbbackup")).hexdigest() == "070fecb0c4ef2ec6023d591f644c391e07ddd98b71315ebe7afddbc2e508503b"

manifest = {"source": plan["source"], "validationRun": plan["run"], "counts": counts,
            "applicationId": plan["applicationId"], "signatureVerified": True,
            "realAccountOrPaidTests": False, "humanAcceptance": "PENDING", "prerelease": True,
            "files": {p.name: {"bytes": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in out.iterdir()}}
(out / "RELEASE_MANIFEST.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
(out / "SHA256SUMS.txt").write_text("".join(hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.name + "\n" for p in sorted(out.iterdir())), encoding="utf-8")
notes = inputs / "release-notes.txt"
notes.write_text(plan["body"] + f"\n\n验证源码：{plan['source']}\n验证运行：https://github.com/{repo}/actions/runs/{plan['run']}\n", encoding="utf-8")

def find_release():
    matches = [r for r in api(f"repos/{repo}/releases?per_page=100") if r["tag_name"] == plan["tag"]]
    assert len(matches) <= 1
    return matches[0] if matches else None

def assert_tag_if_present(required=False):
    result = subprocess.run(["gh", "api", f"repos/{repo}/git/ref/tags/{plan['tag']}"], capture_output=True, text=True)
    if result.returncode:
        assert not required and "404" in result.stderr, result.stderr
        return
    obj = json.loads(result.stdout)["object"]
    while obj["type"] == "tag":
        obj = api(f"repos/{repo}/git/tags/{obj['sha']}")["object"]
    assert obj["type"] == "commit" and obj["sha"] == plan["source"], "Refusing a tag with different source"

release = find_release()
assert_tag_if_present()
if release is None:
    command(["gh", "release", "create", plan["tag"], "--repo", repo, "--target", plan["source"],
             "--title", plan["title"], "--notes-file", str(notes), "--draft", "--prerelease", "--latest=false"])
    # A just-created draft may not be visible in the immediately repeated list response.
    for page_size in (99, 98, 97):
        matches = [r for r in api(f"repos/{repo}/releases?per_page={page_size}") if r["tag_name"] == plan["tag"]]
        assert len(matches) <= 1
        if matches:
            release = matches[0]
            break
        time.sleep(2)
assert release is not None and release["target_commitish"] == plan["source"]
release_id = release["id"]
# GitHub normalized the first draft's Chinese filename to default.txt.
# Repair only these exact generated draft assets, never the APK/data or a public release.
legacy = {
    "default.txt": "9bc58ebcddd9085ab4d44aa427827f3cd81ab67daf0da507331f76f12c426a1f",
    "RELEASE_MANIFEST.json": "ca5383d2de190c1eed5b3ee7483f6f68cad8bc5e67a40baf05cc3cb5eeeda888",
    "SHA256SUMS.txt": "d8cdd73cbb8da71eed2bf51ce39c291f2e54306a8de3dadfe34c9e79fbedd9d9",
}
for item in release["assets"]:
    if release["draft"] and item["name"] in legacy and item.get("digest") == "sha256:" + legacy[item["name"]]:
        command(["gh", "api", "--method", "DELETE", f"repos/{repo}/releases/assets/{item['id']}"])
assets = {a["name"]: a for a in release["assets"] if not (release["draft"] and a["name"] in legacy and a.get("digest") == "sha256:" + legacy[a["name"]])}
for file in out.iterdir():
    if file.name in assets:
        item = assets[file.name]
        assert item["size"] == file.stat().st_size and item.get("digest") == "sha256:" + hashlib.sha256(file.read_bytes()).hexdigest(), "Refusing to replace a different release asset"
    else:
        command(["gh", "release", "upload", plan["tag"], str(file), "--repo", repo])
# Re-read after mutations using a distinct valid query, then verify immutable asset digests.
for page_size in (100, 99, 98):
    release = api(f"repos/{repo}/releases/{release_id}?per_page={page_size}")
    if {a["name"] for a in release["assets"]} == {p.name for p in out.iterdir()}:
        break
    time.sleep(2)
assert {a["name"] for a in release["assets"]} == {p.name for p in out.iterdir()}
for item in release["assets"]:
    file = out / item["name"]
    assert item["state"] == "uploaded" and item["size"] == file.stat().st_size
    assert item.get("digest") == "sha256:" + hashlib.sha256(file.read_bytes()).hexdigest()
command(["gh", "release", "edit", plan["tag"], "--repo", repo, "--draft=false", "--prerelease", "--latest=false", "--notes-file", str(notes)])
for page_size in (97, 96, 95):
    release = api(f"repos/{repo}/releases/{release_id}?per_page={page_size}")
    if not release["draft"]:
        break
    time.sleep(2)
assert not release["draft"] and release["prerelease"]
assert_tag_if_present(required=True)
print(release["html_url"])
