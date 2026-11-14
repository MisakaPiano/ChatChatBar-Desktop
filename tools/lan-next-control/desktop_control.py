"""Additive lan.3 -> lan.4 Desktop requests and exact original-artifact controls.

Request/validation commands are lightweight. Only explicit cloud commands download
packages; this module never starts Java, the application, or a signing identity.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.request
import zipfile

from contract import file_hash, load, require, sha, write_new

REPOSITORY = 'MisakaPiano/ChatChatBar-Desktop'
BUILD_KIND = 'CCB_LAN3_LAN4_DESKTOP_BUILD_V1'
UPGRADE_KIND = 'CCB_EXACT_LAN3_LAN4_DESKTOP_PROFILE_UPGRADE_V1'
ARTIFACT_NAME = 'ccb-lan3-lan4-independent-desktop'
MANIFEST_KIND = 'CCB_LAN4_INDEPENDENT_DESKTOP_ORIGINAL_V1'
SERIES = 'lan4'
VERSION = '0.1.0-lan.4'
INSTALLER = '0.1.40004'
BUILD = '40004'
FILENAME = 'CCB-' + VERSION + '-independent-windows-x64.zip'
BASE = {
    'series': 'lan3', 'version': '0.1.0-lan.3',
    'sourceSha': '10b14a8c89d2fc4b2ed2188546af4339b0e42b57',
    'controlSha': '8946caa73a1c6346a1a4f81bb0135795241c7738',
    'runId': 43349245943, 'artifactId': 14002799032,
    'outerBytes': 205733879,
    'outerSha256': 'f0dfffbf754785f909e078ec418fabc48054d60056598a691e0b43247b719d2d',
    'filename': 'CCB-0.1.0-lan.3-independent-windows-x64.zip',
    'innerBytes': 205622880,
    'innerSha256': '6314a42a25c44d7604f5d211e912460f71bd14a5bd3d4c93011abd90dbba7611',
    'artifactName': 'ccb-lan-next-independent-desktop',
    'installerVersion': '0.1.40003', 'buildNumber': '40003',
}
TIMESTAMP_PATHS = [
    'app/sharedCore/src/main/kotlin/com/example/chatbar/data/local/entity/ChatScrollPosition.kt',
    'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopChatReadingPositionWriter.kt',
    'app/sharedCore/src/main/kotlin/com/example/chatbar/data/repository/ChatRepository.kt',
]
TIMESTAMP_RULE = 'OBSERVED_OLD_CONTROL_CAPTURED_AT_ONLY_WITH_IDENTICAL_SOURCE'


def build_request(source):
    sha(source, 40)
    require(source != BASE['sourceSha'], 'CANDIDATE_MUST_BE_NEW_SOURCE')
    return {'kind': BUILD_KIND, 'format': 1, 'sourceSha': source,
            'version': VERSION, 'installerVersion': INSTALLER, 'buildNumber': BUILD,
            'deliveredBase': dict(BASE)}


def validate_build(request):
    require(isinstance(request, dict) and request == build_request(request.get('sourceSha')), 'DESKTOP_BUILD_CONTRACT')
    return request


def git_bytes(repository, *args):
    return subprocess.check_output(['git', '-C', str(repository), *args])


def source_bindings(repository, source):
    sha(source, 40)
    return {'baseSourceSha': BASE['sourceSha'], 'candidateSourceSha': source,
            'files': [{'path': path,
                       'baseSha256': hashlib.sha256(git_bytes(repository, 'show', BASE['sourceSha'] + ':' + path)).hexdigest(),
                       'candidateSha256': hashlib.sha256(git_bytes(repository, 'show', source + ':' + path)).hexdigest()}
                      for path in TIMESTAMP_PATHS]}


def validate_bindings(value, source):
    require(isinstance(value, dict) and set(value) == {'baseSourceSha', 'candidateSourceSha', 'files'}, 'TIMESTAMP_SOURCE_FIELDS')
    require(value['baseSourceSha'] == BASE['sourceSha'] and value['candidateSourceSha'] == source, 'TIMESTAMP_SOURCE')
    require(isinstance(value['files'], list) and [r.get('path') for r in value['files']] == TIMESTAMP_PATHS, 'TIMESTAMP_SOURCE_PATHS')
    for row in value['files']:
        require(set(row) == {'path', 'baseSha256', 'candidateSha256'}, 'TIMESTAMP_SOURCE_ROW')
        sha(row['baseSha256']); sha(row['candidateSha256'])
    return value


def timestamp_exception_enabled(request):
    return all(row['baseSha256'] == row['candidateSha256'] for row in request['timestampSourceBindings']['files'])


def identity(request):
    return {'version': request['version'], 'commit': request['sourceSha'],
            'installerVersion': request['installerVersion'], 'installerVersionAssigned': 'true', 'packagingProfile': 'desktop'}


def descriptor(path):
    require(path.is_file() and not path.is_symlink(), 'REGULAR_INPUT_REQUIRED')
    return {'filename': path.name, 'bytes': path.stat().st_size, 'sha256': file_hash(path)}


def collect(request, source, output, control_sha):
    validate_build(request); sha(control_sha, 40)
    require(git_bytes(source, 'rev-parse', 'HEAD').decode().strip() == request['sourceSha'], 'DESKTOP_BUILT_SOURCE')
    require(not git_bytes(source, 'status', '--porcelain', '--untracked-files=all').strip(), 'DESKTOP_SOURCE_DIRTY')
    require(load(output / 'source-request.json') == request, 'DESKTOP_SOURCE_REQUEST')
    manifest = {'kind': MANIFEST_KIND, 'format': 1,
                'sourceSha': request['sourceSha'], 'controlSha': control_sha, 'buildRequest': request,
                'timestampSourceBindings': source_bindings(source, request['sourceSha']),
                'archive': descriptor(output / FILENAME),
                'files': [descriptor(output / name) for name in (
                    'source-request.json', 'app-image-audit.json', FILENAME[:-4] + '.json', 'native-result.json')],
                'applicationImageBuiltFromFrozenSource': True, 'oldDeliveredPackageRebuilt': False,
                'profileUpgradeTested': False, 'humanAcceptance': False, 'userDelivered': False}
    write_new(output / 'desktop-package-manifest.json', manifest)
    validate_bundle(output)


def validate_bundle(directory):
    manifest = load(directory / 'desktop-package-manifest.json')
    require(manifest.get('kind') == MANIFEST_KIND and manifest.get('format') == 1, 'DESKTOP_MANIFEST_KIND')
    request = validate_build(manifest['buildRequest'])
    require(manifest['sourceSha'] == request['sourceSha'], 'DESKTOP_MANIFEST_SOURCE'); sha(manifest['controlSha'], 40)
    validate_bindings(manifest['timestampSourceBindings'], request['sourceSha'])
    require(manifest['archive'] == descriptor(directory / FILENAME), 'DESKTOP_ARCHIVE_DESCRIPTOR')
    expected_names = ['source-request.json', 'app-image-audit.json', FILENAME[:-4] + '.json', 'native-result.json']
    require([r.get('filename') for r in manifest['files']] == expected_names, 'DESKTOP_BUNDLE_FILES')
    require(manifest['files'] == [descriptor(directory / name) for name in expected_names], 'DESKTOP_BUNDLE_DIGEST')
    require(load(directory / 'source-request.json') == request, 'DESKTOP_BUNDLE_REQUEST')
    require(set(p.name for p in directory.iterdir()) == set(expected_names + [FILENAME, 'desktop-package-manifest.json']), 'DESKTOP_BUNDLE_UNEXPECTED_FILES')
    audit = load(directory / 'app-image-audit.json'); receipt = load(directory / (FILENAME[:-4] + '.json'))
    native = load(directory / 'native-result.json')
    require(audit['identity'] == identity(request) and audit['fileCount'] == len(audit['files']), 'DESKTOP_IMAGE_IDENTITY')
    require(receipt['version'] == VERSION and receipt['commit'] == request['sourceSha'] and
            receipt['bytes'] == manifest['archive']['bytes'] and receipt['sha256'] == manifest['archive']['sha256'] and
            receipt['programFiles'] == audit['fileCount'], 'DESKTOP_RECEIPT_IDENTITY')
    require(native['kind'] == 'CI_INDEPENDENT_ZIP_ACTUAL_LAUNCHER' and native['version'] == VERSION and
            native['source'] == request['sourceSha'] and native['programFilesVerified'] == audit['fileCount'], 'DESKTOP_NATIVE_IDENTITY')
    require(all(native.get(k) is True for k in ('passed', 'ownDataAndLockCreated', 'normalCloseAndAllProgramProcessesExited', 'outsideBootstrapUnchanged')), 'DESKTOP_NATIVE_NOT_PASSED')
    require(manifest['applicationImageBuiltFromFrozenSource'] is True and manifest['oldDeliveredPackageRebuilt'] is False and
            manifest['profileUpgradeTested'] is False and manifest['humanAcceptance'] is False and manifest['userDelivered'] is False, 'DESKTOP_MANIFEST_CLAIMS')
    return manifest


def metadata(row, artifact, run):
    require(run.get('id') == row['runId'] and run.get('conclusion') == 'success', 'ORIGINAL_RUN_NOT_SUCCESS')
    require(artifact.get('id') == row['artifactId'] and artifact['workflow_run']['id'] == row['runId'], 'ORIGINAL_ARTIFACT_RUN')
    require(run['head_sha'] == artifact['workflow_run']['head_sha'] == row['controlSha'], 'ORIGINAL_CONTROL_SOURCE')
    require(artifact.get('name') == row['artifactName'] and artifact.get('expired') is False, 'ORIGINAL_ARTIFACT_NAME')
    require(artifact.get('size_in_bytes') == row['outerBytes'] and artifact.get('digest') == 'sha256:' + row['outerSha256'], 'ORIGINAL_ARTIFACT_DIGEST')
    return {'package': row, 'run': {'id': run['id'], 'head_sha': run['head_sha'], 'conclusion': run['conclusion']}, 'artifact': artifact}


def upgrade_request(directory, artifact, run):
    manifest = validate_bundle(directory)
    row = {'series': SERIES, 'version': VERSION, 'sourceSha': manifest['sourceSha'], 'controlSha': manifest['controlSha'],
           'runId': run['id'], 'artifactId': artifact['id'], 'artifactName': ARTIFACT_NAME,
           'outerBytes': artifact['size_in_bytes'], 'outerSha256': artifact['digest'].removeprefix('sha256:'),
           'filename': FILENAME, 'innerBytes': manifest['archive']['bytes'], 'innerSha256': manifest['archive']['sha256'],
           'installerVersion': INSTALLER, 'buildNumber': BUILD}
    metadata(row, artifact, run)
    result = {'kind': UPGRADE_KIND, 'format': 1, 'packages': [dict(BASE), row],
              'timestampRule': TIMESTAMP_RULE, 'timestampSourceBindings': manifest['timestampSourceBindings'], 'allowedStateMigrations': []}
    return validate_upgrade(result)


def validate_upgrade(request):
    require(isinstance(request, dict) and set(request) == {'kind', 'format', 'packages', 'timestampRule', 'timestampSourceBindings', 'allowedStateMigrations'}, 'DESKTOP_UPGRADE_FIELDS')
    require(request['kind'] == UPGRADE_KIND and request['format'] == 1, 'DESKTOP_UPGRADE_KIND')
    require(isinstance(request['packages'], list) and len(request['packages']) == 2 and request['packages'][0] == BASE, 'EXACT_DELIVERED_LAN3_REQUIRED')
    row = request['packages'][1]
    require(set(row) == set(BASE), 'DESKTOP_CANDIDATE_FIELDS')
    require(row['series'] == SERIES and row['version'] == VERSION and row['installerVersion'] == INSTALLER and row['buildNumber'] == BUILD and
            row['artifactName'] == ARTIFACT_NAME and row['filename'] == FILENAME, 'LAN4_IDENTITY')
    sha(row['sourceSha'], 40); sha(row['controlSha'], 40); sha(row['outerSha256']); sha(row['innerSha256'])
    require(row['sourceSha'] != BASE['sourceSha'] and row['artifactId'] != BASE['artifactId'] and row['runId'] != BASE['runId'], 'NEW_ORIGINAL_REQUIRED')
    for key in ('runId', 'artifactId', 'outerBytes', 'innerBytes'):
        require(type(row[key]) is int and row[key] > 0, 'DESKTOP_' + key)
    require(request['timestampRule'] == TIMESTAMP_RULE and request['allowedStateMigrations'] == [], 'NO_UNREVIEWED_MIGRATION_WAIVER')
    validate_bindings(request['timestampSourceBindings'], row['sourceSha'])
    return request


def cloud():
    require(os.name == 'nt' and os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('GITHUB_REPOSITORY') == REPOSITORY, 'WINDOWS_CLOUD_ONLY')


def api(path):
    request = urllib.request.Request('https://api.github.com/repos/' + REPOSITORY + '/actions/' + path,
        headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json'})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def provenance(request):
    validate_upgrade(request)
    return [metadata(row, api('artifacts/' + str(row['artifactId'])), api('runs/' + str(row['runId']))) for row in request['packages']]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def original_stream(artifact_id):
    # Do not forward a GitHub bearer token to the signed cross-origin download URL.
    request = urllib.request.Request('https://api.github.com/repos/' + REPOSITORY + '/actions/artifacts/' + str(artifact_id) + '/zip',
        headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json'})
    try:
        return urllib.request.build_opener(NoRedirect).open(request, timeout=60)
    except urllib.error.HTTPError as redirect:
        require(redirect.code in (301, 302, 303, 307, 308), 'ARTIFACT_DOWNLOAD_STATUS')
        location = redirect.headers.get('Location', '')
        require(location.startswith('https://'), 'ARTIFACT_DOWNLOAD_HTTPS')
        return urllib.request.urlopen(location, timeout=60)


def verify_outer_and_extract(archive, destination, row):
    require(descriptor(archive)['bytes'] == row['outerBytes'] and file_hash(archive) == row['outerSha256'], 'EXACT_ORIGINAL_OUTER_BYTES')
    require(not destination.exists(), 'NEW_ORIGINAL_INPUT_DIRECTORY_REQUIRED')
    with zipfile.ZipFile(archive) as z:
        names = z.namelist()
        require(len(names) == len(set(n.casefold() for n in names)) and z.testzip() is None, 'ORIGINAL_OUTER_NAMES_OR_CRC')
        for item in z.infolist():
            require((destination / item.filename).resolve().is_relative_to(destination.resolve()) and
                    (item.external_attr >> 16) & 0o170000 != 0o120000 and '\\' not in item.filename, 'ORIGINAL_OUTER_ESCAPE_OR_LINK')
        destination.mkdir()
        z.extractall(destination)
    matches = list(destination.rglob(row['filename']))
    require(len(matches) == 1 and matches[0].is_file() and not matches[0].is_symlink(), 'SINGLE_ORIGINAL_INNER_REQUIRED')
    inner = matches[0]
    require(descriptor(inner)['bytes'] == row['innerBytes'] and file_hash(inner) == row['innerSha256'], 'EXACT_ORIGINAL_INNER_BYTES')
    return {'series': row['series'], 'outer': descriptor(archive), 'outerCrcPassed': True, 'outerMembers': names,
            'inner': descriptor(inner), 'innerRelativePath': inner.relative_to(destination).as_posix()}


def fetch_originals(request, output):
    cloud(); values = provenance(request)
    require(source_bindings(Path.cwd(), request['packages'][1]['sourceSha']) == request['timestampSourceBindings'], 'FROZEN_TIMESTAMP_SOURCE_CHANGED')
    output.mkdir(exist_ok=False)
    write_new(output / 'source-request.json', request)
    write_new(output / 'original-provenance.json', {'controlSha': os.environ['GITHUB_SHA'], 'packages': values})
    originals = []
    temporary = Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
    for row in request['packages']:
        archive = temporary / ('ccb-original-' + row['series'] + '-' + str(row['artifactId']) + '.zip')
        total = 0
        with original_stream(row['artifactId']) as response, archive.open('xb') as stream:
            while chunk := response.read(1024 * 1024):
                total += len(chunk); require(total <= row['outerBytes'], 'ORIGINAL_DOWNLOAD_SIZE'); stream.write(chunk)
        originals.append(verify_outer_and_extract(archive, Path('input-' + row['series']), row))
    require(upgrade_request(Path('input-' + SERIES), values[1]['artifact'], values[1]['run']) == request, 'CANDIDATE_BUNDLE_NOT_REQUESTED')
    write_new(output / 'original-input-bytes.json', {'packages': originals, 'oldPackageRebuilt': False, 'candidatePackageRebuilt': False})


def main():
    parser = argparse.ArgumentParser(description=__doc__); commands = parser.add_subparsers(dest='command', required=True)
    make = commands.add_parser('build-request'); make.add_argument('--source-sha', required=True); make.add_argument('--output', type=Path, required=True)
    check = commands.add_parser('validate-build'); check.add_argument('--request', type=Path, required=True)
    item = commands.add_parser('collect')
    for name in ('request', 'source-dir', 'output'): item.add_argument('--' + name, type=Path, required=True)
    item.add_argument('--control-sha', required=True)
    item = commands.add_parser('upgrade-request')
    for name in ('candidate-dir', 'artifact-metadata', 'run-metadata', 'output'): item.add_argument('--' + name, type=Path, required=True)
    item = commands.add_parser('fetch-originals'); item.add_argument('--request', type=Path, required=True); item.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'build-request': write_new(args.output, build_request(args.source_sha))
    elif args.command == 'validate-build': print(validate_build(load(args.request))['sourceSha'])
    elif args.command == 'collect': collect(load(args.request), args.source_dir, args.output, args.control_sha)
    elif args.command == 'upgrade-request': write_new(args.output, upgrade_request(args.candidate_dir, load(args.artifact_metadata), load(args.run_metadata)))
    else: fetch_originals(load(args.request), args.output)


if __name__ == '__main__':
    main()
