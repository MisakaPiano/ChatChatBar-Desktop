"""Verifier-only r02 control. SDK/Gradle execution belongs to explicit cloud jobs."""
import argparse
from copy import deepcopy
from pathlib import Path, PurePosixPath
import os
import stat
import zipfile
from contract import (BASE, HERE, ORIGINAL_INPUT_BYTES, REUSED_ORIGINAL_ARTIFACT, REUSED_ROLES,
                      ROLES, FIXTURE_TARGET, VERIFIER_ONLY_SOURCE, build_request, file_hash,
                      fixture_bytes, fixture_hash, load, require, sha, validate_build,
                      original_input_rows, expected_reuse_ledger,
                      validate_sign, verifier_only_request, write_new)
from build_control import copy_new, git, inspect_apk, one
from sign_control import validate_raw


def require_r02(request):
    validate_build(request)
    require(request['format'] == 2, 'VERIFIER_ONLY_FORMAT_REQUIRED')
    return request


def original_rows():
    return original_input_rows()


def reuse_ledger():
    return expected_reuse_ledger()


def validate_original(directory):
    manifest, rows = validate_raw(directory, build_request(VERIFIER_ONLY_SOURCE),
                                  REUSED_ORIGINAL_ARTIFACT['controlSha'])
    require(manifest['kind'] == 'CCB_LAN_82_TO_83_FOUR_RAW_INPUTS', 'ORIGINAL_MANIFEST_KIND')
    require(rows == original_rows(), 'ORIGINAL_FOUR_INPUTS_CHANGED')
    return manifest, rows


def extract_original(archive, directory):
    """Authenticate downloaded original ZIP bytes before exposing any members."""
    require(archive.is_file() and not archive.is_symlink(), 'ORIGINAL_ARCHIVE_FILE')
    require(archive.stat().st_size == REUSED_ORIGINAL_ARTIFACT['bytes'], 'ORIGINAL_ARCHIVE_BYTES')
    require(file_hash(archive) == REUSED_ORIGINAL_ARTIFACT['outerSha256'], 'ORIGINAL_ARCHIVE_DIGEST')
    require(not directory.exists() and not directory.is_symlink(), 'ORIGINAL_DIRECTORY_EXISTS')
    with zipfile.ZipFile(archive) as bundle:
        names = set()
        for entry in bundle.infolist():
            # ZipInfo on Windows normalizes backslashes in filename but keeps
            # the original wire spelling separately. Reject aliases there too.
            require(entry.orig_filename == entry.filename, 'ORIGINAL_MEMBER_SPELLING')
            path = PurePosixPath(entry.filename)
            require(entry.filename not in names, 'ORIGINAL_DUPLICATE_MEMBER')
            names.add(entry.filename)
            require(entry.filename and '\\' not in entry.filename and ':' not in entry.filename
                    and not path.is_absolute() and '..' not in path.parts
                    and path.as_posix() == entry.filename.rstrip('/'), 'ORIGINAL_MEMBER_PATH')
            mode = entry.external_attr >> 16
            require(not stat.S_ISLNK(mode) and not (entry.flag_bits & 1), 'ORIGINAL_MEMBER_TYPE')
        require(bundle.testzip() is None, 'ORIGINAL_ZIP_CRC')
        directory.mkdir(parents=True)
        bundle.extractall(directory)
    validate_original(directory)


def fetch_original(archive, directory):
    require(os.environ.get('GITHUB_ACTIONS') == 'true', 'CLOUD_ORIGINAL_DOWNLOAD_ONLY')
    require(not directory.exists() and not directory.is_symlink(), 'ORIGINAL_DIRECTORY_EXISTS')
    # This existing stdlib adapter strips the bearer before a signed-URL redirect.
    # Importing it does not run its Desktop/native CLI.
    from desktop_control import original_stream
    count = 0
    with original_stream(REUSED_ORIGINAL_ARTIFACT['id']) as source, archive.open('xb') as target:
        while True:
            block = source.read(1024 * 1024)
            if not block:
                break
            count += len(block)
            require(count <= REUSED_ORIGINAL_ARTIFACT['bytes'], 'ORIGINAL_ARCHIVE_TOO_LONG')
            target.write(block)
    require(count == REUSED_ORIGINAL_ARTIFACT['bytes'], 'ORIGINAL_ARCHIVE_BYTES')
    extract_original(archive, directory)


def prepare(request, candidate_dir, output):
    require_r02(request)
    require(git(candidate_dir, 'rev-parse', 'HEAD') == VERIFIER_ONLY_SOURCE, 'WORKTREE_SOURCE_verify83')
    require(not git(candidate_dir, 'status', '--porcelain', '--untracked-files=all'), 'WORKTREE_DIRTY_verify83')
    fixture = HERE / request['fixtures']['verify83']['path']
    require(fixture_hash(fixture) == request['fixtures']['verify83']['sha256'], 'FIXTURE_HASH')
    target = candidate_dir / FIXTURE_TARGET
    require(not target.exists() and not target.is_symlink(), 'FIXTURE_TARGET_EXISTS')
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open('xb') as stream:
        stream.write(fixture_bytes(fixture))
    require(git(candidate_dir, 'status', '--porcelain', '--untracked-files=all') == '?? ' + FIXTURE_TARGET,
            'ONLY_TEST_OVERLAY_ALLOWED')
    output.mkdir(parents=True, exist_ok=True)
    write_new(output / 'source-request.json', request)
    write_new(output / 'fixture-overlays.json', {'candidateSourceSha': VERIFIER_ONLY_SOURCE,
        'target': FIXTURE_TARGET, 'fixture': request['fixtures']['verify83'],
        'productionFilesChanged': [], 'rebuiltRoles': ['verify83'], 'reusedRoles': list(REUSED_ROLES)})


def validate_r02_raw(directory, request, control_sha=None):
    require_r02(request)
    manifest, rows = validate_raw(directory, request, control_sha)
    require(manifest['kind'] == 'CCB_LAN_82_TO_83_FOUR_RAW_INPUTS', 'R02_MANIFEST_KIND')
    for flag in ('persistentSigningApplied', 'deviceUpgradeTested', 'humanAcceptance', 'userDelivered'):
        require(manifest.get(flag) is False, 'R02_UNPROVEN_' + flag)
    return manifest, rows


def collect(request, candidate_dir, original_dir, output, control_sha, tools):
    require_r02(request); sha(control_sha, 40)
    require(load(output / 'source-request.json') == request, 'FROZEN_BUILD_REQUEST')
    require(git(candidate_dir, 'rev-parse', 'HEAD') == VERIFIER_ONLY_SOURCE, 'BUILT_SOURCE_CHANGED')
    require(git(candidate_dir, 'status', '--porcelain', '--untracked-files=all') == '?? ' + FIXTURE_TARGET,
            'BUILD_CHANGED_PRODUCTION_FILES')
    require(fixture_hash(candidate_dir / FIXTURE_TARGET) == request['fixtures']['verify83']['sha256'],
            'BUILT_FIXTURE_CHANGED')
    _, originals = validate_original(original_dir)
    verifier = one(candidate_dir / 'app/app/build/outputs/apk', 'androidTest/lanPreview/*.apk')
    rows = []
    for role, (filename, package, version) in ROLES.items():
        source = verifier if role == 'verify83' else original_dir / filename
        actual = inspect_apk(source, package, version, tools, output / (role + '-badging.txt'))
        target = output / filename
        copy_new(source, target)
        if role in REUSED_ROLES:
            row = deepcopy(originals[role])
            require(actual == row['versionCode'], 'REUSED_APK_VERSION_' + role)
        else:
            row = {'role': role, 'filename': filename, 'package': package, 'versionCode': actual,
                'sourceSha': VERIFIER_ONLY_SOURCE, 'testFixture': request['fixtures'][role],
                'bytes': target.stat().st_size, 'sha256': file_hash(target), 'deliveredBase': None}
        require(target.stat().st_size == row['bytes'] and file_hash(target) == row['sha256'], 'COLLECT_BYTES_' + role)
        rows.append(row)
    copy_new(original_dir / 'raw-manifest.json', output / 'original-four-raw-manifest.json')
    copy_new(original_dir / 'source-request.json', output / 'original-four-source-request.json')
    write_new(output / 'raw-manifest.json', {'kind': 'CCB_LAN_82_TO_83_FOUR_RAW_INPUTS',
        'sourceSha': VERIFIER_ONLY_SOURCE, 'controlSha': control_sha, 'buildRequest': request,
        'inputs': rows, 'reuse': reuse_ledger(), 'persistentSigningApplied': False,
        'deviceUpgradeTested': False, 'humanAcceptance': False, 'userDelivered': False})
    validate_r02_raw(output, request, control_sha)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    make = commands.add_parser('request'); make.add_argument('--output', type=Path, required=True)
    extract = commands.add_parser('extract-original')
    extract.add_argument('--archive', type=Path, required=True); extract.add_argument('--output', type=Path, required=True)
    fetch = commands.add_parser('fetch-original')
    fetch.add_argument('--archive', type=Path, required=True); fetch.add_argument('--output', type=Path, required=True)
    provenance = commands.add_parser('original-provenance'); provenance.add_argument('--output', type=Path, required=True)
    for command in ('prepare', 'collect'):
        item = commands.add_parser(command)
        for flag in ('request', 'candidate-dir', 'output'):
            item.add_argument('--' + flag, type=Path, required=True)
        if command == 'collect':
            item.add_argument('--original-dir', type=Path, required=True)
            item.add_argument('--control-sha', required=True)
            item.add_argument('--build-tools', type=Path, required=True)
    validate = commands.add_parser('validate')
    validate.add_argument('--sign-request', type=Path, required=True)
    validate.add_argument('--raw-dir', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'request':
        write_new(args.output, verifier_only_request())
    elif args.command == 'original-provenance':
        from provenance import check
        write_new(args.output, check(REUSED_ORIGINAL_ARTIFACT))
    elif args.command == 'extract-original':
        extract_original(args.archive, args.output)
    elif args.command == 'fetch-original':
        fetch_original(args.archive, args.output)
    elif args.command == 'prepare':
        prepare(load(args.request), args.candidate_dir, args.output)
    elif args.command == 'collect':
        collect(load(args.request), args.candidate_dir, args.original_dir, args.output, args.control_sha, args.build_tools)
    else:
        request = validate_sign(load(args.sign_request))
        validate_r02_raw(args.raw_dir, request['buildRequest'], request['originalArtifact']['controlSha'])
