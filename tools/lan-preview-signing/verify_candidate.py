"""Cloud-only exact-input next-preview assembly and official SDK verification."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile
from assemble_public_apk import assemble, file_hash

CERTIFICATE = '801f54622926f9ca6710086ac2732854a31b546f8ec2fd38cd0c6c11837a1aa7'
PACKAGE = 'com.example.chatbar.lansyncnext'
ROLES = {'base': (PACKAGE, 81), 'upgrade': (PACKAGE, 82), 'tests': (PACKAGE + '.test', None)}


def run_tool(tool, arguments, path, success=True):
    result = subprocess.run([str(tool), *arguments], text=True, encoding='utf8',
                            errors='replace', stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    path.write_text(result.stdout, encoding='utf8')
    assert (result.returncode == 0) == success, path.name
    return result.stdout


def main(request_path, source_dir, output_dir, build_tools):
    request = json.loads(request_path.read_text(encoding='utf8'))
    assert request['kind'] == 'CCB_LAN_NEXT_PREVIEW_PUBLIC_SIGNING'
    assert request['certificateSha256'] == CERTIFICATE
    assert re.fullmatch('[a-f0-9]{40}', request['sourceSha'])
    assert set(request['entries']) == set(ROLES)
    output_dir.mkdir(parents=True, exist_ok=False)
    rows = []
    for role, (package, version) in ROLES.items():
        entry = request['entries'][role]
        filename = entry['filename']
        assert re.fullmatch('[a-zA-Z0-9._-]+[.]apk', filename)
        source = source_dir / filename
        assert source.is_file() and not source.is_symlink()
        receipt = entry['publicSigning']
        assert receipt['purpose'] == 'ISOLATED_LAN_NEXT_PREVIEW'
        assert receipt['certificateSha256'] == CERTIFICATE
        output = output_dir / (role + '.apk')
        assemble(source, receipt, output)
        signature = run_tool(build_tools/'apksigner', ['verify', '--verbose', '--print-certs', str(output)],
                             output_dir/(role+'-apksigner.txt'))
        assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
        assert 'Number of signers: 1' in signature
        digest = re.search(r'^Signer #1 certificate SHA-256 digest: (\S+)', signature, re.M)
        assert digest and digest.group(1) == CERTIFICATE
        badging = run_tool(build_tools/'aapt', ['dump', 'badging', str(output)], output_dir/(role+'-badging.txt'))
        match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)'", badging, re.M)
        assert match and match.group(1) == package
        if version is not None:
            assert int(match.group(2)) == version
        assert "sdkVersion:'26'" in badging
        run_tool(build_tools/'zipalign', ['-c', '4', str(output)], output_dir/(role+'-zipalign.txt'))
        rows.append({'role': role, 'package': package, 'versionCode': int(match.group(2)),
                     'sha256': file_hash(output), 'bytes': output.stat().st_size,
                     'certificateSha256': CERTIFICATE, 'officialV2Verified': True,
                     'entryBytesAndOffsetsPreserved': True, 'zipalign4Passed': True})
    # An intact signing block cannot hide changed executable bytes.
    tampered = output_dir/'tampered.apk'
    shutil.copyfile(output_dir/'base.apk', tampered)
    with zipfile.ZipFile(tampered) as archive:
        entry = archive.getinfo('classes.dex')
    with tampered.open('r+b') as stream:
        stream.seek(entry.header_offset+26)
        names, extra = struct.unpack('<HH', stream.read(4))
        offset = entry.header_offset+30+names+extra+32
        assert offset < entry.header_offset+30+names+extra+entry.compress_size
        stream.seek(offset); before = stream.read(1); assert before
        stream.seek(offset); stream.write(bytes([before[0] ^ 1]))
    rejection = run_tool(build_tools/'apksigner', ['verify', '--min-sdk-version', '26', str(tampered)],
                         output_dir/'tamper-rejection.txt', False)
    assert 'DOES NOT VERIFY' in rejection
    tampered.unlink()
    result = {'kind': request['kind'], 'sourceSha': request['sourceSha'], 'entries': rows,
              'protectedByteTamperRejected': True, 'privateKeyUploaded': False,
              'localApkWritten': False, 'firstPreviewUpgradeCompatible': False,
              'deviceUpgradeTested': False, 'humanAcceptance': False, 'userDelivered': False}
    (output_dir/'verification.json').write_text(json.dumps(result, indent=2), encoding='utf8')
    print(json.dumps(result))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for name in ['request', 'source-dir', 'output-dir', 'build-tools']:
        parser.add_argument('--'+name, type=Path, required=True)
    args = parser.parse_args()
    main(args.request, args.source_dir, args.output_dir, args.build_tools)
