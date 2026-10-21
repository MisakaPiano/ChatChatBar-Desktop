"""Compare only synthetic P11 artifacts; no user roots or credentials are read."""
import hashlib
import json
import sys
import zipfile
from pathlib import Path


def read_archive(path):
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        assert len(names) == len(set(names)), "duplicate ZIP entries"
        def records(name):
            return [json.loads(x) for x in z.read(name).decode().splitlines() if x]
        return {
            "manifest": json.loads(z.read("manifest.json")),
            "messages": records("messages.jsonl"),
            "voices": records("voices.jsonl"),
            "rag": records("rag.jsonl"),
            "media": {n: hashlib.sha256(z.read(n)).hexdigest() for n in names if n.startswith("media/")},
        }


def media_hash(archive, reference):
    key = reference.split(":", 1)[1]
    matches = [h for n, h in archive["media"].items() if Path(n).stem == key]
    assert len(matches) == 1, "unresolved portable media reference"
    return matches[0]


def canonical_message(archive, value):
    result = dict(value)
    result.pop("sessionId")
    result["images"] = [media_hash(archive, x) for x in result["images"]]
    result["generatedImageMetadata"] = [dict(x, imagePath=media_hash(archive, x["imagePath"]))
                                        for x in result["generatedImageMetadata"]]
    return result


def canonical_voice(archive, value):
    # Restore deliberately creates platform-owned record/anchor IDs and an updated timestamp.
    result = {k: v for k, v in value.items() if k not in ("id", "sessionId", "anchorId", "updatedAt")}
    result["audioPath"] = media_hash(archive, result["audioPath"])
    return result


def canonical_memory(snapshot):
    ids = {node["id"]: f"node-{i}" for i, node in enumerate(snapshot["nodes"])}
    assert len(ids) == len(snapshot["nodes"]), "duplicate Memory node IDs"
    state = snapshot["state"]
    for name in ("episodeNodeIds", "arcNodeIds", "eraNodeIds", "legacyReferenceNodeIds"):
        assert all(x in ids for x in state[name]), "Memory root reference"
    for node in snapshot["nodes"]:
        assert all(x in ids for x in node["childIds"]), "Memory child reference"
    def normalize(value):
        if isinstance(value, dict):
            return {ids.get(k, k): normalize(v) for k, v in value.items() if k != "sessionId"}
        if isinstance(value, list):
            return [normalize(x) for x in value]
        if isinstance(value, str):
            return ids.get(value, value)
        return value
    result = normalize(snapshot)
    # Checked separately against the post-continuation messages/session below. These are
    # reconciled by the official loadLocked path, not opaque archive payload loss.
    result["state"].pop("timeline")
    result["state"].pop("memoryWasEnabled")
    return result


def main():
    scope = Path(sys.argv[1]).resolve()
    source = read_archive(scope / "desktop-materials/desktop.cbsave")
    returned = read_archive(scope / "android-materials/android-return.cbsave")
    original_ids = [m["id"] for m in source["messages"]]
    assert [m["id"] for m in returned["messages"] if m["id"] in original_ids] == original_ids, "message order"
    returned_messages = {m["id"]: m for m in returned["messages"]}
    for message in source["messages"]:
        assert canonical_message(source, message) == canonical_message(returned, returned_messages[message["id"]]), "message/version/media identity"
    encode = lambda x: json.dumps(x, sort_keys=True, ensure_ascii=False)
    assert sorted(encode(canonical_voice(source, v)) for v in source["voices"]) == sorted(
        encode(canonical_voice(returned, v)) for v in returned["voices"]), "voice version/text/audio binding"
    for archive in (source, returned):
        ids = {m["id"] for m in archive["messages"]}
        assert all(v["messageId"] in ids for v in archive["voices"]), "voice message reference"
        assert all(r["messageId"] in ids for r in archive["rag"]), "RAG message reference"
    rag = lambda a: sorted(encode({k: v for k, v in r.items() if k not in ("id", "sourceId")}) for r in a["rag"])
    assert rag(source) == rag(returned), "RAG content/vector/reference"
    state = returned["manifest"]["memorySnapshot"]["state"]
    assert state["memoryWasEnabled"] == returned["manifest"]["longTermMemoryEnabled"], "Memory enable state reconciliation"
    turns = {m["sourceTurnId"]: m["sourceTurnOrder"] for m in returned["messages"] if m["sourceTurnId"]}
    assert {t["sourceTurnId"]: t["sourceOrder"] for t in state["timeline"]} == turns, "Memory timeline must match source turns"
    assert all(not t["tombstone"] for t in state["timeline"]), "unexpected synthetic tombstone"
    assert canonical_memory(source["manifest"]["memorySnapshot"]) == canonical_memory(returned["manifest"]["memorySnapshot"]), "current Memory snapshot"
    assert sorted(source["media"].values()) == sorted(returned["media"].values()), "all media bytes"
    report = {"result": "PASS", "originalMessages": len(original_ids), "returnedMessages": len(returned["messages"]),
              "voiceRecords": len(source["voices"]), "mediaFiles": len(source["media"]), "ragChunks": len(source["rag"]),
              "physicalAndroid": "NOT_TESTED", "draftPortable": False,
              "normalizations": ["destination session IDs", "owned voice/anchor and RAG IDs", "voice restore updatedAt", "media references resolved to SHA-256", "Memory node graph IDs", "Memory timeline and enable state independently checked against returned messages/session"]}
    (scope / "roundtrip-graph-proof.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
