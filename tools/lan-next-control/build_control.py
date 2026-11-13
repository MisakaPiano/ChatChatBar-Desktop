"""Create explicit requests and inspect four cloud inputs; no local Gradle/SDK invocation."""
import argparse
from pathlib import Path
import os
import re
import shutil
import subprocess
from contract import (BASE, HERE, ROLES, FIXTURE_TARGET, build_request, file_hash, fixture_bytes, fixture_hash, load,
                      require, sha, validate_build, write_new)


def git(directory, *arguments):
    return subprocess.check_output(['git', '-C', str(directory), *arguments], text=True).strip()


def prepare_sources(request, seed_dir, candidate_dir, output):
    validate_build(request)
    for role, directory, source in [('seed82', seed_dir, BASE['sourceSha']),
                                    ('verify83', candidate_dir, request['sourceSha'])]:
        require(git(directory, 'rev-parse', 'HEAD') == source, 'WORKTREE_SOURCE_' + role)
        require(not git(directory, 'status', '--porcelain', '--untracked-files=all'), 'WORKTREE_DIRTY_' + role)
        fixture = HERE / request['fixtures'][role]['path']
        require(fixture_hash(fixture) == request['fixtures'][role]['sha256'], 'FIXTURE_HASH')
        target = directory / FIXTURE_TARGET
        require(not target.exists() and not target.is_symlink(), 'FIXTURE_TARGET_EXISTS')
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open('xb') as stream:
            stream.write(fixture_bytes(fixture))
        expected = '?? ' + FIXTURE_TARGET
        require(git(directory, 'status', '--porcelain', '--untracked-files=all') == expected, 'ONLY_TEST_OVERLAY_ALLOWED')
    output.mkdir(parents=True, exist_ok=True)
    write_new(output / 'source-request.json', request)
    write_new(output / 'fixture-overlays.json', {'seedSourceSha': BASE['sourceSha'],
        'candidateSourceSha': request['sourceSha'], 'target': FIXTURE_TARGET,
        'fixtures': request['fixtures'], 'productionFilesChanged': []})


def one(directory, pattern):
    files = list(directory.glob(pattern))
    require(len(files) == 1 and files[0].is_file() and not files[0].is_symlink(), 'SINGLE_APK_' + pattern)
    return files[0]


def inspect_apk(path, expected_package, expected_version, tools, report):
    executable = tools / ('aapt.exe' if os.name == 'nt' else 'aapt')
    badging = subprocess.check_output([str(executable), 'dump', 'badging', str(path)], text=True, encoding='utf8')
    report.write_text(badging, encoding='utf8')
    match = re.search(r"^package: name='([^']+)' versionCode='([0-9]*)'", badging, re.M)
    require(match is not None and match.group(1) == expected_package, 'APK_PACKAGE')
    version = int(match.group(2)) if match.group(2) else None
    if expected_version is not None:
        require(version == expected_version, 'APK_VERSION')
    require("sdkVersion:'26'" in badging, 'APK_MIN_SDK')
    return version


def copy_new(source, target):
    require(source.is_file() and not source.is_symlink(), 'SOURCE_FILE')
    with source.open('rb') as incoming, target.open('xb') as outgoing:
        shutil.copyfileobj(incoming, outgoing, 1024 * 1024)


def collect(request, seed_dir, candidate_dir, original_dir, output, control_sha, tools):
    validate_build(request); sha(control_sha, 40)
    require(load(output / 'source-request.json') == request, 'FROZEN_BUILD_REQUEST')
    for role, directory, source in [('seed82', seed_dir, BASE['sourceSha']),
                                    ('verify83', candidate_dir, request['sourceSha'])]:
        require(git(directory, 'rev-parse', 'HEAD') == source, 'BUILT_SOURCE_CHANGED')
        require(git(directory, 'status', '--porcelain', '--untracked-files=all') == '?? ' + FIXTURE_TARGET,
                'BUILD_CHANGED_PRODUCTION_FILES')
        require(fixture_hash(directory / FIXTURE_TARGET) == request['fixtures'][role]['sha256'], 'BUILT_FIXTURE_CHANGED')
    original = load(original_dir / 'raw-manifest.json')
    require(original['sourceSha'] == BASE['sourceSha'] and original['controlSha'] == BASE['controlSha'], 'BASE82_RAW_PROVENANCE')
    base_rows = [row for row in original['inputs'] if row['filename'] == BASE['member']]
    require(len(base_rows) == 1 and base_rows[0]['sha256'] == BASE['rawSha256'], 'BASE82_RAW_MANIFEST')
    require(base_rows[0]['package'] == ROLES['base82'][1] and base_rows[0]['versionCode'] == 82, 'BASE82_MANIFEST_IDENTITY')
    base_path = original_dir / BASE['member']
    require(file_hash(base_path) == BASE['rawSha256'] and base_path.stat().st_size == base_rows[0]['bytes'], 'EXACT_DELIVERED82_RAW')
    inputs = {'base82': base_path,
              'seed82': one(seed_dir / 'app/app/build/outputs/apk', 'androidTest/lanPreview/*.apk'),
              'candidate83': one(candidate_dir / 'app/app/build/outputs/apk', 'lanPreview/*.apk'),
              'verify83': one(candidate_dir / 'app/app/build/outputs/apk', 'androidTest/lanPreview/*.apk')}
    rows = []
    for role, (filename, package, version) in ROLES.items():
        source = inputs[role]; target = output / filename
        actual = inspect_apk(source, package, version, tools, output / (role + '-badging.txt'))
        copy_new(source, target)
        rows.append({'role': role, 'filename': filename, 'package': package, 'versionCode': actual,
                     'sourceSha': BASE['sourceSha'] if role in ('base82', 'seed82') else request['sourceSha'],
                     'testFixture': request['fixtures'].get(role), 'bytes': target.stat().st_size,
                     'sha256': file_hash(target), 'deliveredBase': BASE if role == 'base82' else None})
    copy_new(original_dir / 'raw-manifest.json', output / 'original82-raw-manifest.json')
    copy_new(original_dir / 'source-request.json', output / 'original82-source-request.json')
    write_new(output / 'raw-manifest.json', {'kind': 'CCB_LAN_82_TO_83_FOUR_RAW_INPUTS',
        'sourceSha': request['sourceSha'], 'controlSha': control_sha, 'buildRequest': request,
        'inputs': rows, 'persistentSigningApplied': False, 'deviceUpgradeTested': False,
        'humanAcceptance': False, 'userDelivered': False})


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    make = commands.add_parser('request')
    make.add_argument('--source-sha', required=True); make.add_argument('--output', type=Path, required=True)
    for command in ('prepare', 'collect'):
        item = commands.add_parser(command)
        for flag in ('request', 'seed-dir', 'candidate-dir', 'output'):
            item.add_argument('--' + flag, type=Path, required=True)
        if command == 'collect':
            item.add_argument('--original-dir', type=Path, required=True)
            item.add_argument('--control-sha', required=True)
            item.add_argument('--build-tools', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'request':
        write_new(args.output, build_request(args.source_sha))
    elif args.command == 'prepare':
        prepare_sources(load(args.request), args.seed_dir, args.candidate_dir, args.output)
    else:
        collect(load(args.request), args.seed_dir, args.candidate_dir, args.original_dir,
                args.output, args.control_sha, args.build_tools)
