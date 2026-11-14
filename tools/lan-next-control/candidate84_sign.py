"""Fixed83/84 public-block orchestration; reuses existing assembler and SDK tools."""
import argparse
from pathlib import Path
import re
import shutil
import struct
import zipfile

from candidate84_profile import *
from candidate84_build import cloud, validate_raw
from sign_control import assemble, tool
from build_control import inspect_apk
from provenance import check as check_provenance
from desktop_control import original_stream, verify_outer_and_extract


def require_single_v2(signature):
    require(re.search(r'^Verified using v2 scheme \(APK Signature Scheme v2\): true[ \t]*\r?$', signature, re.M)
            and re.findall(r'^Number of signers: ([0-9]+)[ \t]*\r?$', signature, re.M) == ['1'],
            'OFFICIAL_SINGLE_V2')
    digests = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([a-f0-9]{64})[ \t]*\r?$', signature, re.M)
    require(digests == [CERTIFICATE] and
            re.search(r'^Signer #1 certificate SHA-256 digest: ' + CERTIFICATE + r'[ \t]*\r?$', signature, re.M),
            'OFFICIAL_SIGNER')


def manifest_descriptor(path):
    require(path.is_file() and not path.is_symlink(), 'RAW_MANIFEST_FILE')
    return {'bytes': path.stat().st_size, 'sha256': file_hash(path)}


def prepare_request(raw, artifact, public, output):
    build = load(raw / 'source-request.json')
    _, rows = validate_raw(raw, build, artifact['controlSha'])
    artifact_metadata(artifact)
    require(set(public) == {'seed83', 'candidate84', 'verify84'}, 'THREE_NEW_PUBLIC_BLOCKS')
    blocks = dict(public, base83=base_public_block())
    entries = {}
    for role, (filename, _, _) in ROLES.items():
        block(blocks[role], rows[role]['sha256'], role == 'base83')
        entries[role] = {'filename': filename, 'publicSigning': blocks[role]}
    value = {'kind': SIGN_KIND, 'format': 1, 'buildRequest': build,
             'originalArtifact': artifact, 'rawManifest': manifest_descriptor(raw / 'raw-manifest.json'),
             'entries': entries}
    write_new(output, validate_sign(value))


def fetch(request, archive, destination, evidence):
    cloud(); validate_sign(request)
    expected = request['originalArtifact']
    evidence.mkdir(parents=True, exist_ok=False)
    write_new(evidence / 'original-provenance.json', check_provenance(expected))
    count = 0
    with original_stream(expected['id']) as source, archive.open('xb') as target:
        while True:
            data = source.read(1024 * 1024)
            if not data: break
            count += len(data)
            require(count <= expected['bytes'], 'RAW_ARCHIVE_TOO_LONG')
            target.write(data)
    require(count == expected['bytes'], 'RAW_ARCHIVE_LENGTH')
    # Reuse the authenticated outer ZIP/CRC/path/member engine. The only inner
    # selector is this fixed manifest, pinned from the real raw inputs at request creation.
    receipt = verify_outer_and_extract(archive, destination, {
        'outerBytes': expected['bytes'], 'outerSha256': expected['outerSha256'],
        'filename': 'raw-manifest.json', 'innerBytes': request['rawManifest']['bytes'],
        'innerSha256': request['rawManifest']['sha256'], 'series': 'delivered83-candidate84'})
    require(receipt['innerRelativePath'] == 'raw-manifest.json', 'RAW_MANIFEST_NOT_AT_ROOT')
    validate_raw(destination, request['buildRequest'], expected['controlSha'])
    write_new(evidence / 'original-input-bytes.json', receipt)


def verify(raw, request, output, tools, control):
    cloud(); validate_sign(request); sha(control, 40)
    _, rows = validate_raw(raw, request['buildRequest'], request['originalArtifact']['controlSha'])
    require(manifest_descriptor(raw / 'raw-manifest.json') == request['rawManifest'], 'RAW_MANIFEST_CHANGED')
    output.mkdir(parents=True, exist_ok=False)
    apksigner, aapt, zipalign = (tools / name for name in ('apksigner', 'aapt', 'zipalign'))
    signed = []
    for role, (filename, package, version) in ROLES.items():
        public = request['entries'][role]['publicSigning']
        block(public, rows[role]['sha256'], role == 'base83')
        target = output / (role + '.apk')
        assemble(raw / filename, public, target)
        signature = tool(apksigner, ['verify', '--verbose', '--print-certs', str(target)], output / (role + '-apksigner.txt'))
        require_single_v2(signature)
        actual = inspect_apk(target, package, version, tools, output / (role + '-badging.txt'))
        tool(zipalign, ['-c', '4', str(target)], output / (role + '-zipalign.txt'))
        if role in ('seed83', 'verify84'):
            manifest = tool(aapt, ['dump', 'xmltree', str(target), 'AndroidManifest.xml'], output / (role + '-manifest.txt'))
            require(re.search(r'android:targetPackage[^\n]*="' + re.escape(PACKAGE) + '"', manifest), 'COMPANION_TARGET')
        digest = file_hash(target)
        if role == 'base83':
            require(digest == BASE['deliveredSha256'] and target.stat().st_size == BASE['deliveredBytes'], 'EXACT_DELIVERED83')
        signed.append(dict(rows[role], signedSha256=digest, signedBytes=target.stat().st_size,
                           versionCode=actual, certificateSha256=CERTIFICATE, officialV2Verified=True,
                           zipalign4Passed=True))
    # Same existing executable-byte rejection oracle, applied to the new84 input.
    tampered = output / 'tampered84.apk'
    shutil.copyfile(output / 'candidate84.apk', tampered)
    with zipfile.ZipFile(tampered) as bundle: dex = bundle.getinfo('classes.dex')
    require(dex.compress_size > 0, 'TAMPER_DEX')
    with tampered.open('r+b') as stream:
        stream.seek(dex.header_offset); header = stream.read(30)
        require(len(header) == 30 and header[:4] == b'PK\x03\x04', 'TAMPER_HEADER')
        names, extra = struct.unpack_from('<HH', header, 26)
        stream.seek(dex.header_offset + 30 + names + extra)
        value = stream.read(1); require(len(value) == 1, 'TAMPER_BYTE')
        stream.seek(-1, 1); stream.write(bytes([value[0] ^ 1]))
    tool(apksigner, ['verify', '--min-sdk-version', '26', str(tampered)], output / 'tamper84-rejection.txt', False)
    tampered.unlink()
    write_new(output / 'verification.json', {'kind': VERIFY_KIND, 'verificationControlSha': control,
        'buildRequest': request['buildRequest'], 'originalArtifact': request['originalArtifact'],
        'rawManifest': request['rawManifest'], 'entries': signed, 'protectedCandidate84ByteTamperRejected': True,
        'deviceUpgradeTested': False, 'humanAcceptance': False, 'userDelivered': False})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['request', 'fetch', 'verify'])
    for flag in ('raw-dir', 'artifact-metadata', 'public-blocks', 'output', 'request', 'archive', 'destination', 'evidence', 'build-tools'):
        parser.add_argument('--' + flag, type=Path)
    parser.add_argument('--control-sha')
    a = parser.parse_args()
    if a.mode == 'request': prepare_request(a.raw_dir, load(a.artifact_metadata), load(a.public_blocks), a.output)
    elif a.mode == 'fetch': fetch(load(a.request), a.archive, a.destination, a.evidence)
    else: verify(a.raw_dir, load(a.request), a.output, a.build_tools, a.control_sha)


if __name__ == '__main__': main()
