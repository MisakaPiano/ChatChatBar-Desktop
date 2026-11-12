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
    assert request['purpose'] == 'ENGINEERING_SIGNING_PROOF_ONLY_NOT_AN_UPDATE'
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

def verify(source, request_path, directory, build_tools):
    request = json.loads(request_path.read_text())
    directory.mkdir(parents=True, exist_ok=False)
    output = directory / 'LAB-ONLY-resigned-first-preview.apk'
    assemble(source, request, output)
    def inspect(tool, args, name, expected_success=True):
        result = subprocess.run([str(build_tools / tool), *args], text=True, encoding='utf8', errors='replace', stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (directory / name).write_text(result.stdout, encoding='utf8')
        assert (result.returncode == 0) == expected_success, name
        return result.stdout
    signature = inspect('apksigner', ['verify', '--verbose', '--print-certs', str(output)], 'official-apksigner.txt')
    assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
    certificate = re.search(r'^Signer #1 certificate SHA-256 digest: (\S+)', signature, re.M).group(1)
    assert certificate == request['certificateSha256']
    assert 'Number of signers: 1' in signature
    badging = inspect('aapt', ['dump','badging',str(output)], 'official-badging.txt')
    assert "name='com.example.chatbar.lansync'" in badging
    assert "sdkVersion:'26'" in badging
    inspect('zipalign', ['-c','4',str(output)], 'official-zipalign.txt')
    # Change a protected compressed entry byte without touching ZIP structure or signing data.
    tampered = directory / 'LAB-ONLY-tampered.apk'
    shutil.copyfile(output, tampered)
    with zipfile.ZipFile(output) as archive:
        entry = next(e for e in archive.infolist() if e.filename == 'classes.dex')
    with tampered.open('r+b') as stream:
        stream.seek(entry.header_offset+26)
        name_size, extra_size = struct.unpack('<HH', stream.read(4))
        offset = entry.header_offset + 30 + name_size + extra_size + 32
        stream.seek(offset); previous = stream.read(1); assert previous
        stream.seek(offset); stream.write(bytes([previous[0]^1]))
    rejected = inspect('apksigner', ['verify','--min-sdk-version','26',str(tampered)], 'official-tamper-rejection.txt', False)
    assert 'DOES NOT VERIFY' in rejected
    tampered.unlink()
    receipt = {'purpose': request['purpose'], 'sourceSha256': request['sourceSha256'],
        'assembledSha256': file_hash(output), 'bytes': output.stat().st_size,
        'certificateSha256': certificate, 'officialV2Verified': True, 'protectedByteTamperRejected': True,
        'zipalign4Passed': True, 'allEntryDataAndOffsetsPreserved': True,
        'sameIdentityAcrossLocalProcessReopen': request['sameIdentityAcrossLocalProcessReopen'],
        'originalArtifact': request['originalArtifact'],
        'privateKeyPresentInCloud': False, 'localApkWritten': False,
        'firstDeliveredApkUnchanged': True, 'firstDeliveredUpgradeCompatible': False,
        'newUserCandidate': False, 'deviceInstallOrUpgradeTested': False}
    (directory/'proof.json').write_text(json.dumps(receipt,indent=2),encoding='utf8')
    print(json.dumps(receipt))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source',type=Path,required=True)
    parser.add_argument('--public-request',type=Path,required=True)
    parser.add_argument('--directory',type=Path,required=True)
    parser.add_argument('--build-tools',type=Path,required=True)
    args = parser.parse_args()
    verify(args.source,args.public_request,args.directory,args.build_tools)
