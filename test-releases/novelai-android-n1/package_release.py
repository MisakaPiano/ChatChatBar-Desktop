"""Repackage the already verified cloud APK; never rebuild or access user data."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import zipfile

APK_SHA = "76ad2008e38b205355955b69e3bf89d00e1aee8713caac4a1e3ee91b9268b966"
ORIGINALS_SHA = "4161fe32661506155daf0660e91f5d8dcecd6bb74601babb0ff9db0ef9a03026"
SOURCE = "c4523ff64595ffe5272ae12aa387c0104e138e62"
APK_MEMBER = "app/app/build/outputs/apk/debug/app-debug.apk"
NAME = "CCB-NovelAI-Android-test-n1.zip"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--originals", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    original = args.originals.read_bytes()
    assert digest(original) == ORIGINALS_SHA, "Original CI artifact mismatch"
    with zipfile.ZipFile(args.originals) as z:
        assert len(z.namelist()) == len(set(z.namelist())), "Duplicate artifact members"
        assert z.testzip() is None, "Artifact CRC failure"
        apk = z.read(APK_MEMBER)
        identity = json.loads(z.read("safe-batch-evidence/apk-identity.json"))
        source = json.loads(z.read("safe-batch-evidence/source.json"))
    assert len(apk) == 105058533 and digest(apk) == APK_SHA
    assert identity["sha256"] == APK_SHA and identity["signatureVerified"] is True
    assert source["source"] == SOURCE and source["realAccountCalls"] is False
    payload = Path(__file__).parent / "payload"
    members = {p.relative_to(payload).as_posix(): p.read_bytes()
               for p in payload.rglob("*") if p.is_file()}
    members["CCB-NovelAI-批量生图测试-n1.apk"] = apk
    for line in members["SHA256SUMS.txt"].decode("utf-8-sig").splitlines():
        expected, name = line.split("  ", 1)
        assert digest(members[name]) == expected, "Payload mismatch: " + name
    assert len(members) == 8, "Unexpected public payload"
    args.out.mkdir(parents=True, exist_ok=True)
    archive = args.out / NAME
    with zipfile.ZipFile(archive, "w") as z:
        for name, data in sorted(members.items()):
            info = zipfile.ZipInfo(name, (2026, 11, 14, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            z.writestr(info, data, compresslevel=6)
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None and len(z.namelist()) == len(members)
        for name, data in members.items():
            assert z.read(name) == data
    checksum = digest(archive.read_bytes())
    (args.out / (NAME + ".sha256")).write_text(checksum + "  " + NAME + "\n", encoding="utf-8")
    shutil.copyfile(payload / "验收步骤.txt", args.out / "验收步骤.txt")
    receipt = dict(source=SOURCE, apkSha256=APK_SHA, zipSha256=checksum,
                   zipBytes=archive.stat().st_size, uniqueMembers=len(members),
                   originalRun=43543169213, originalArtifact=14092602828,
                   rebuild=False, realAccountCalls=False, humanAcceptance=False)
    (args.out / "RELEASE_MANIFEST.json").write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt))


if __name__ == "__main__":
    main()
