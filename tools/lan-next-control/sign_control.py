"""Accept public blocks from the existing signer; never open or create an identity."""
import argparse
from pathlib import Path
import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile
from contract import (BASE, CERTIFICATE, HERE, ROLES, SIGN_KIND, artifact_metadata, file_hash, sha,
                      load, require, validate_build, validate_public_block, validate_reused_inputs, validate_sign, write_new)
from build_control import inspect_apk

# Only the existing, version-independent public APK assembler is reused.
sys.path.insert(0, str(HERE.parent / 'lan-preview-signing'))
from assemble_public_apk import assemble


def validate_raw(directory, build, control_sha=None):
    validate_build(build)
    manifest = load(directory / 'raw-manifest.json')
    require(manifest['buildRequest'] == build and load(directory / 'source-request.json') == build, 'RAW_BUILD_REQUEST')
    require(manifest['sourceSha'] == build['sourceSha'], 'RAW_SOURCE')
    if control_sha is not None:
        require(manifest['controlSha'] == control_sha, 'RAW_CONTROL')
    rows = manifest['inputs']
    require(len(rows) == 4 and {row['role'] for row in rows} == set(ROLES), 'FOUR_RAW_INPUTS')
    by_role = {row['role']: row for row in rows}
    for role, (filename, package, version) in ROLES.items():
        row = by_role[role]; path = directory / filename
        require(row['filename'] == filename and row['package'] == package, 'RAW_ROLE_PACKAGE')
        if version is not None:
            require(row['versionCode'] == version, 'RAW_VERSION')
        expected_source = BASE['sourceSha'] if role in ('base82', 'seed82') else build['sourceSha']
        require(row['sourceSha'] == expected_source, 'FOUR_INPUT_SOURCE')
        require(row['testFixture'] == build['fixtures'].get(role), 'FOUR_INPUT_FIXTURE')
        require(row['deliveredBase'] == (BASE if role == 'base82' else None), 'FOUR_INPUT_BASE')
        require(path.is_file() and not path.is_symlink(), 'RAW_FILE')
        require(row['bytes'] == path.stat().st_size and row['sha256'] == file_hash(path), 'RAW_BYTES_HASH')
        if role == 'base82':
            require(row['sha256'] == BASE['rawSha256'], 'RAW_DELIVERED82_CHANGED')
    if build['format'] == 2:
        validate_reused_inputs(manifest, by_role)
    return manifest, by_role


def prepare_request(raw_dir, artifact, public_blocks, old_request, output):
    artifact_metadata(artifact)
    build = load(raw_dir / 'source-request.json')
    _, rows = validate_raw(raw_dir, build, artifact['controlSha'])
    require(set(public_blocks) == {'seed82', 'candidate83', 'verify83'}, 'THREE_NEW_PUBLIC_BLOCKS')
    require(old_request['sourceSha'] == BASE['sourceSha'] and old_request['certificateSha256'] == CERTIFICATE, 'FROZEN82_PUBLIC_REQUEST')
    base_block = old_request['entries']['upgrade']['publicSigning']
    blocks = dict(public_blocks, base82=base_block)
    entries = {}
    for role, (filename, _, _) in ROLES.items():
        validate_public_block(blocks[role], rows[role]['sha256'], role == 'base82')
        entries[role] = {'filename': filename, 'publicSigning': blocks[role]}
    result = {'kind': SIGN_KIND, 'format': 1, 'buildRequest': build,
              'originalArtifact': artifact, 'entries': entries}
    validate_sign(result)
    write_new(output, result)


def tool(executable, arguments, output, expected_success=True):
    result = subprocess.run([str(executable), *arguments], text=True, encoding='utf8',
                            errors='replace', stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    output.write_text(result.stdout, encoding='utf8')
    require((result.returncode == 0) == expected_success, 'SDK_' + output.name)
    return result.stdout


def verify(raw_dir, request, output, tools, control_sha):
    sha(control_sha, 40)
    validate_sign(request)
    _, raw_rows = validate_raw(raw_dir, request['buildRequest'], request['originalArtifact']['controlSha'])
    output.mkdir(parents=True, exist_ok=False)
    apksigner = tools / ('apksigner.bat' if os.name == 'nt' else 'apksigner')
    aapt = tools / ('aapt.exe' if os.name == 'nt' else 'aapt')
    rows = []
    for role, (filename, package, version) in ROLES.items():
        block = request['entries'][role]['publicSigning']
        validate_public_block(block, raw_rows[role]['sha256'], role == 'base82')
        target = output / (role + '.apk')
        assemble(raw_dir / filename, block, target)
        signature = tool(apksigner, ['verify', '--verbose', '--print-certs', str(target)], output / (role + '-apksigner.txt'))
        require('Verified using v2 scheme (APK Signature Scheme v2): true' in signature, 'OFFICIAL_V2')
        require('Number of signers: 1' in signature, 'SINGLE_SIGNER')
        require(re.search(r'^Signer #1 certificate SHA-256 digest: ' + CERTIFICATE + r'\s*$', signature, re.M), 'OFFICIAL_SIGNER')
        actual_version = inspect_apk(target, package, version, tools, output / (role + '-badging.txt'))
        if role in ('seed82', 'verify83'):
            manifest = tool(aapt, ['dump', 'xmltree', str(target), 'AndroidManifest.xml'], output / (role + '-manifest.txt'))
            require(re.search(r'android:targetPackage[^\n]*="' + re.escape(ROLES['base82'][1]) + '"', manifest), 'TEST_TARGET_PACKAGE')
        digest = file_hash(target)
        if role == 'base82':
            require(digest == BASE['deliveredSha256'] and target.stat().st_size == BASE['deliveredBytes'], 'DELIVERED82_BYTES_CHANGED')
        rows.append(dict(raw_rows[role], signedSha256=digest, signedBytes=target.stat().st_size,
                         versionCode=actual_version, certificateSha256=CERTIFICATE, officialV2Verified=True))
    tampered = output / 'tampered83.apk'
    shutil.copyfile(output / 'candidate83.apk', tampered)
    with zipfile.ZipFile(tampered) as archive:
        dex = archive.getinfo('classes.dex')
        require(dex.compress_size > 0, 'TAMPER_DEX')
    with tampered.open('r+b') as stream:
        stream.seek(dex.header_offset)
        header = stream.read(30)
        require(len(header) == 30 and header[:4] == b'PK\x03\x04', 'TAMPER_HEADER')
        name_size, extra_size = struct.unpack_from('<HH', header, 26)
        stream.seek(dex.header_offset + 30 + name_size + extra_size)
        original = stream.read(1); require(len(original) == 1, 'TAMPER_BYTE')
        stream.seek(-1, 1); stream.write(bytes([original[0] ^ 1]))
    tool(apksigner, ['verify', '--min-sdk-version', '26', str(tampered)], output / 'tamper83-rejection.txt', False)
    tampered.unlink()
    write_new(output / 'verification.json', {'kind': 'CCB_LAN_82_TO_83_SIGNED_FOUR_INPUTS',
        'verificationControlSha': control_sha,
        'buildRequest': request['buildRequest'], 'originalArtifact': request['originalArtifact'],
        'entries': rows, 'protectedCandidate83ByteTamperRejected': True,
        'privateKeyReadByControl': False, 'privateKeyUploaded': False,
        'deviceUpgradeTested': False, 'humanAcceptance': False, 'userDelivered': False})


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    make = sub.add_parser('request')
    for flag in ('raw-dir', 'artifact-metadata', 'public-blocks', 'old-public-request', 'output'):
        make.add_argument('--' + flag, type=Path, required=True)
    check = sub.add_parser('verify')
    for flag in ('raw-dir', 'request', 'output', 'build-tools'):
        check.add_argument('--' + flag, type=Path, required=True)
    check.add_argument('--control-sha', required=True)
    args = parser.parse_args()
    if args.command == 'request':
        prepare_request(args.raw_dir, load(args.artifact_metadata), load(args.public_blocks), load(args.old_public_request), args.output)
    else:
        verify(args.raw_dir, load(args.request), args.output, args.build_tools, args.control_sha)
