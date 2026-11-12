"""Cloud-only assembly and independent Android SDK verification of public proof data."""
import argparse
import base64
import hashlib
import json
import mmap
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile

MAGIC = b'APK Sig Block 42'

def file_hash(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def assemble(source, request, output):
    assert request['format'] == 1
    assert request['purpose'] == 'ISOLATED_LAN_NEXT_PREVIEW'
    assert file_hash(source) == request['sourceSha256'], 'SOURCE_HASH'
    block = base64.b64decode(request['blockBase64'], validate=True)
    assert 32 <= len(block) <= 32768 and block[-16:] == MAGIC, 'NEW_BLOCK'
    assert struct.unpack('<Q', block[:8])[0] == len(block) - 8
    assert block[:8] == block[-24:-16]
    with source.open('rb') as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
        start = max(0, len(data) - 65557)
        relative = bytes(data[start:]).rfind(b'PK\x05\x06')
        assert relative >= 0, 'EOCD_MISSING'
        eocd_offset = start + relative
        eocd = bytearray(data[eocd_offset:])
        _, disk, cd_disk, disk_count, count, cd_size, cd_offset, comment_size = struct.unpack('<4s4H2IH', eocd[:22])
        assert disk == cd_disk == 0 and disk_count == count and count != 65535
        assert eocd_offset == cd_offset + cd_size and len(eocd) == 22 + comment_size
        assert data[cd_offset-16:cd_offset] == MAGIC, 'SOURCE_SIGNING_BLOCK'
        old_size = struct.unpack('<Q', data[cd_offset-24:cd_offset-16])[0]
        old_start = cd_offset - old_size - 8
        assert old_start >= 0 and struct.unpack('<Q', data[old_start:old_start+8])[0] == old_size
        struct.pack_into('<I', eocd, 16, old_start + len(block))
        with output.open('xb') as target:
            for at in range(0, old_start, 1048576): target.write(data[at:min(at+1048576, old_start)])
            target.write(block)
            for at in range(cd_offset, eocd_offset, 1048576): target.write(data[at:min(at+1048576, eocd_offset)])
            target.write(eocd)
    assert output.stat().st_size == request['bytes']
    assert file_hash(output) == request['outputSha256'], 'ASSEMBLED_OUTPUT_HASH'
    with zipfile.ZipFile(source) as old, zipfile.ZipFile(output) as new:
        assert old.namelist() == new.namelist() and len(set(new.namelist())) == len(new.namelist())
        assert [(x.filename,x.CRC,x.file_size,x.compress_size,x.header_offset) for x in old.infolist()] == [(x.filename,x.CRC,x.file_size,x.compress_size,x.header_offset) for x in new.infolist()]
        assert new.testzip() is None
        assert len(new.namelist()) == request['entries']
