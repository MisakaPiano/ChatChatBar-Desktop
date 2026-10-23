"""Offline container census, NOT a replacement for authenticated S0/S1/restore.

No extraction, secrets, source names or digests in its public result. Windows
source handles deny write/delete; the caller explicitly supplies the scan root.
"""
import contextlib
import ctypes
import hashlib
import json
import os
from pathlib import Path
import stat
import struct
import zipfile


class Refused(Exception):
    pass


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Refused("DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def safe(path, directory=False):
    path = Path(path)
    if not path.is_absolute() or any(p in (".", "..") for p in path.parts):
        raise Refused("PATH")
    for ancestor in [*reversed(path.parents), path]:
        s = ancestor.lstat()
        if stat.S_ISLNK(s.st_mode) or getattr(s, "st_file_attributes", 0) & 0x400:
            raise Refused("REPARSE")
        if ancestor != path or directory:
            if not stat.S_ISDIR(s.st_mode):
                raise Refused("DIRECTORY")
        elif not stat.S_ISREG(s.st_mode):
            raise Refused("REGULAR_FILE")
    return path


@contextlib.contextmanager
def source_handle(path):
    safe(path)
    if os.name != "nt":
        raise Refused("WINDOWS_REQUIRED")
    import msvcrt
    from ctypes import wintypes
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                                   ctypes.c_void_p, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
    kernel.CreateFileW.restype = wintypes.HANDLE
    handle = kernel.CreateFileW(str(path), 0x80000000, 1, None, 3, 0x00200000, None)
    if handle == ctypes.c_void_p(-1).value:
        raise Refused("SOURCE_OPEN")
    try:
        fd = msvcrt.open_osfhandle(handle, os.O_RDONLY | os.O_BINARY)
    except BaseException:
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        kernel.CloseHandle(handle)
        raise
    with os.fdopen(fd, "rb") as stream:
        safe(path)
        yield stream


def digest(stream):
    stream.seek(0)
    h = hashlib.sha256()
    while block := stream.read(65536):
        h.update(block)
    return h.hexdigest()


def census(stream):
    """Header claims are deliberately untrusted; ZIP checks do not prove schema."""
    stream.seek(0)
    prefix = stream.read(12)
    if prefix[:8] == b"CBBACKUP":
        if len(prefix) != 12:
            raise Refused("HEADER")
        length = struct.unpack(">I", prefix[8:])[0]
        if not 1 <= length <= 32768:
            raise Refused("HEADER_LIMIT")
        header = json.loads(stream.read(length).decode("utf-8", "strict"), object_pairs_hook=strict_object)
        if not isinstance(header, dict) or set(header) - {"version", "encrypted", "salt", "wrappedKey"}:
            raise Refused("HEADER_FIELDS")
        version, encrypted = header.get("version", 1), header.get("encrypted", False)
        if type(version) is not int or type(encrypted) is not bool:
            raise Refused("HEADER_TYPES")
        return {"kind": "CBBACKUP", "header_claim_version": version if version in (1, 2) else "UNKNOWN",
                "encrypted_claim": encrypted, "authentication": "NOT_PERFORMED",
                "payload": "PASSWORD_REQUIRED" if encrypted else "USE_S0_INSPECTOR",
                "restore_authorized": False}
    if prefix[:4] != b"PK\x03\x04":
        return {"kind": "UNRECOGNIZED", "restore_authorized": False}
    # Bound central directory allocation before ZipFile creates entry objects.
    stream.seek(0, 2)
    length = stream.tell()
    stream.seek(max(0, length - 65557))
    tail = stream.read(65557)
    end = tail.rfind(b"PK\x05\x06")
    if end < 0 or len(tail) - end < 22:
        raise Refused("ZIP_DIRECTORY")
    disk, cd_disk, count_disk, count, cd_size, cd_offset, comment = struct.unpack_from("<HHHHIIH", tail, end + 4)
    if disk or cd_disk or count != count_disk or end + 22 + comment != len(tail):
        raise Refused("ZIP_DIRECTORY")
    if count >= 65535 or cd_size > 4 * 1024 * 1024 or cd_offset + cd_size > length - len(tail) + end:
        raise Refused("ZIP_DIRECTORY_LIMIT")
    categories = {"manifest": 0, "messages": 0, "rag": 0, "voices": 0, "images": 0, "audio": 0, "other": 0}
    total = 0
    schema = "UNKNOWN"
    with zipfile.ZipFile(stream) as archive:
        infos = archive.infolist()
        if len(infos) != count:
            raise Refused("ZIP_DIRECTORY")
        seen = set()
        for entry in infos:
            # ZipInfo.filename normalizes backslashes on Windows even while reading.
            # Validate original wire spelling before relying on normalized lookup names.
            name = entry.orig_filename
            if name != entry.filename or '\0' in name:
                raise Refused("ZIP_PATH")
            if name.casefold() in seen or len(name) > 512 or name.startswith("/") or "\\" in name or ":" in name or any(p in (".", "..") for p in name.split("/")):
                raise Refused("ZIP_PATH")
            seen.add(name.casefold())
            if stat.S_ISLNK(entry.external_attr >> 16) or entry.flag_bits & 1:
                raise Refused("ZIP_ENTRY_TYPE")
            total += entry.file_size
            if total > 2 * 1024**3 or entry.file_size > 256 * 1024**2 or entry.file_size > max(1, entry.compress_size) * 1000:
                raise Refused("ZIP_EXPANSION_LIMIT")
            kind = {"manifest.json": "manifest", "messages.jsonl": "messages", "rag.jsonl": "rag", "voices.jsonl": "voices"}.get(name)
            if kind is None:
                kind = "images" if name.startswith("media/images/") else "audio" if name.startswith("media/audio/") else "other"
            if not entry.is_dir():
                categories[kind] += 1
            # CRC is verified by ZipExtFile at EOF, with bounded memory; no private bytes emitted.
            payload = bytearray() if name == "manifest.json" else None
            if payload is not None and entry.file_size > 1024**2:
                raise Refused("MANIFEST_LIMIT")
            actual = 0
            with archive.open(entry) as body:
                while block := body.read(65536):
                    actual += len(block)
                    if actual > entry.file_size:
                        raise Refused("ZIP_LENGTH")
                    if payload is not None:
                        payload.extend(block)
            if actual != entry.file_size:
                raise Refused("ZIP_LENGTH")
            if payload is not None:
                try:
                    value = json.loads(payload.decode("utf-8", "strict"), object_pairs_hook=strict_object)
                    v = value.get("schemaVersion") if isinstance(value, dict) else None
                    schema = v if type(v) is int and v in range(1, 9) else "UNKNOWN"
                finally:
                    payload[:] = b"\0" * len(payload)
    return {"kind": "ZIP_CONTAINER", "crc": "ALL_ENTRIES_READ", "entry_count": count,
            "categories": categories, "schema_claim": schema, "semantic_validation": "NOT_PERFORMED",
            "restore_authorized": False}


def inspect(path):
    try:
        with source_handle(path) as stream:
            before = digest(stream)
            try:
                result = census(stream)
            except Refused as failure:
                result = {"status": "REFUSED", "reason": str(failure)}
            except Exception:
                result = {"status": "REFUSED", "reason": "CONTAINER_OR_IO"}
            after = digest(stream)
            result["source_bytes"] = "UNCHANGED" if before == after else "CHANGED"
            result["restore_authorized"] = False
            return result
    except Exception:
        return {"status": "REFUSED", "reason": "INPUT_OR_IO", "restore_authorized": False}


def scan(root):
    root = safe(root, True)
    pending, files, skipped = [root], [], 0
    while pending:
        directory = safe(pending.pop(), True)
        for entry in directory.iterdir():
            s = entry.lstat()
            if stat.S_ISLNK(s.st_mode) or getattr(s, "st_file_attributes", 0) & 0x400:
                skipped += 1
            elif stat.S_ISDIR(s.st_mode):
                pending.append(entry)
            elif stat.S_ISREG(s.st_mode):
                files.append(entry)
            else:
                skipped += 1
    return {"scope": "EXPLICIT_DIRECTORY_OFFLINE", "files": len(files), "reparse_or_special_skipped": skipped,
            "results": [inspect(p) for p in files]}


if __name__ == "__main__":
    import sys
    try:
        if len(sys.argv) != 2:
            raise Refused("ARGUMENTS")
        print(json.dumps(scan(Path(sys.argv[1])), sort_keys=True))
    except Exception:
        print('{"status":"REFUSED_SCAN","restore_authorized":false}')
        sys.exit(2)
