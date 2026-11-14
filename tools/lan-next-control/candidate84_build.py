"""83/84 orchestration over existing raw-input/file/SDK primitives; cloud builds only."""
import argparse
import os
from pathlib import Path

from candidate84_profile import *
from build_control import git, one, inspect_apk, copy_new
from fixture_only_control import fetch_original, validate_original
from provenance import check as check_provenance


def cloud():
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_OS') == 'Linux',
            'CLOUD_LINUX_ONLY')


def overlay_path(directory):
    directory = directory.resolve(strict=True)
    target = directory
    for part in Path(FIXTURE_TARGET).parts:
        target = target / part
        require(not target.is_symlink(), 'OVERLAY_SYMLINK')
    require(target.resolve(strict=False).is_relative_to(directory), 'OVERLAY_ESCAPE')
    return target


def check_source(directory, source, fixture=None):
    require(git(directory, 'rev-parse', 'HEAD') == source, 'SOURCE_HEAD_CHANGED')
    expected = '' if fixture is None else '?? ' + FIXTURE_TARGET
    require(git(directory, 'status', '--porcelain', '--untracked-files=all') == expected, 'SOURCE_NOT_EXCLUSIVE')
    if fixture is not None:
        require(fixture_hash(overlay_path(directory)) == fixture['sha256'], 'BUILT_FIXTURE_CHANGED')


def prepare(build, seed, candidate, output):
    cloud(); validate_build(build)
    output.mkdir(parents=True, exist_ok=False)
    for role, directory, source in [('seed83', seed, BASE['sourceSha']), ('verify84', candidate, build['sourceSha'])]:
        check_source(directory, source)
        fixture = build['fixtures'][role]
        path = HERE / fixture['path']
        require(not path.is_symlink() and fixture_hash(path) == fixture['sha256'], 'FIXTURE_CHANGED')
        target = overlay_path(directory)
        require(not target.exists(), 'OVERLAY_EXISTS')
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open('xb') as stream:
            stream.write(fixture_bytes(path))
        check_source(directory, source, fixture)
    write_new(output / 'source-request.json', build)
    write_new(output / 'fixture-overlays.json', {'seedSourceSha': BASE['sourceSha'],
        'candidateSourceSha': build['sourceSha'], 'fixtures': build['fixtures'],
        'target': FIXTURE_TARGET, 'productionFilesChanged': []})


def validate_base(directory):
    # This is exactly the already-authenticated V4 raw artifact, NOT the verifier-
    # only format2 production substitution path. Its complete old manifest stays strict.
    validate_original(directory)
    path = directory / BASE['member']
    require(path.is_file() and not path.is_symlink() and path.stat().st_size == BASE['rawBytes'] and
            file_hash(path) == BASE['rawSha256'], 'EXACT83_RAW_BYTES')
    return path


def collect(build, seed, candidate, original, output, control, tools):
    cloud(); validate_build(build); sha(control, 40)
    require(load(output / 'source-request.json') == build, 'FROZEN_BUILD_REQUEST')
    for role, directory, source in [('seed83', seed, BASE['sourceSha']), ('verify84', candidate, build['sourceSha'])]:
        check_source(directory, source, build['fixtures'][role])
    paths = {'base83': validate_base(original),
             'seed83': one(seed / 'app/app/build/outputs/apk', 'androidTest/lanPreview/*.apk'),
             'candidate84': one(candidate / 'app/app/build/outputs/apk', 'lanPreview/*.apk'),
             'verify84': one(candidate / 'app/app/build/outputs/apk', 'androidTest/lanPreview/*.apk')}
    rows = []
    for role, (filename, package, version) in ROLES.items():
        target = output / filename
        actual = inspect_apk(paths[role], package, version, tools, output / (role + '-badging.txt'))
        copy_new(paths[role], target)
        rows.append({'role': role, 'filename': filename, 'package': package, 'versionCode': actual,
            'sourceSha': BASE['sourceSha'] if role in ('base83', 'seed83') else build['sourceSha'],
            'testFixture': build['fixtures'].get(role), 'bytes': target.stat().st_size,
            'sha256': file_hash(target), 'deliveredBase': dict(BASE) if role == 'base83' else None})
    write_new(output / 'raw-manifest.json', {'kind': RAW_KIND, 'sourceSha': build['sourceSha'],
        'controlSha': control, 'buildRequest': build, 'inputs': rows,
        'baseArtifact': base_artifact(), 'persistentSigningApplied': False,
        'deviceUpgradeTested': False, 'humanAcceptance': False, 'userDelivered': False})
    validate_raw(output, build, control)


def validate_raw(directory, build, control=None):
    validate_build(build)
    manifest = load(directory / 'raw-manifest.json')
    require(manifest['kind'] == RAW_KIND and manifest['sourceSha'] == build['sourceSha'] and
            manifest['buildRequest'] == build and load(directory / 'source-request.json') == build and
            manifest['baseArtifact'] == base_artifact(), 'RAW_BINDING')
    sha(manifest['controlSha'], 40)
    if control is not None:
        require(manifest['controlSha'] == control, 'RAW_CONTROL')
    rows = manifest['inputs']
    require(len(rows) == 4 and {r['role'] for r in rows} == set(ROLES), 'FOUR_RAW_INPUTS')
    result = {r['role']: r for r in rows}
    for role, (filename, package, version) in ROLES.items():
        row = result[role]; path = directory / filename
        require(row['filename'] == filename and row['package'] == package and
                (version is None or row['versionCode'] == version), 'RAW_ROLE_IDENTITY')
        require(row['sourceSha'] == (BASE['sourceSha'] if role in ('base83', 'seed83') else build['sourceSha']) and
                row['testFixture'] == build['fixtures'].get(role) and
                row['deliveredBase'] == (BASE if role == 'base83' else None), 'RAW_LINEAGE')
        require(path.is_file() and not path.is_symlink() and type(row['bytes']) is int and row['bytes'] > 0 and
                path.stat().st_size == row['bytes'] and file_hash(path) == row['sha256'], 'RAW_BYTES')
        if role == 'base83':
            require(row['bytes'] == BASE['rawBytes'] and row['sha256'] == BASE['rawSha256'], 'EXACT83_BASE')
    return manifest, result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['request', 'validate-request', 'prepare', 'collect', 'base-provenance', 'fetch-base', 'validate-raw'])
    for flag in ('request', 'seed-dir', 'candidate-dir', 'original-dir', 'output', 'archive', 'build-tools'):
        parser.add_argument('--' + flag, type=Path)
    parser.add_argument('--source-sha'); parser.add_argument('--control-sha')
    a = parser.parse_args()
    if a.mode == 'request': write_new(a.output, build_request(a.source_sha))
    elif a.mode == 'validate-request': print(validate_build(load(a.request))['sourceSha'])
    elif a.mode == 'prepare': prepare(load(a.request), a.seed_dir, a.candidate_dir, a.output)
    elif a.mode == 'collect': collect(load(a.request), a.seed_dir, a.candidate_dir, a.original_dir, a.output, a.control_sha, a.build_tools)
    elif a.mode == 'base-provenance': cloud(); write_new(a.output, check_provenance(base_artifact()))
    elif a.mode == 'fetch-base': cloud(); fetch_original(a.archive, a.output)
    else: validate_raw(a.original_dir, load(a.request), a.control_sha)


if __name__ == '__main__': main()
