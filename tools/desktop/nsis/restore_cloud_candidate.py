"""Restore exact public-repository Actions build artifacts for native revalidation, without rebuilding."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'windows'))
from audit_app_image import audit

REPOSITORY = 'MisakaPiano/ChatChatBar-Desktop'


def require(value, code):
    if not value:
        raise RuntimeError(code)


def sha(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(1024 * 1024):
            result.update(block)
    return result.hexdigest()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        return None


def main():
    require(os.name == 'nt' and os.environ.get('GITHUB_ACTIONS') == 'true', 'WINDOWS_CI_REQUIRED')
    require(os.environ.get('GITHUB_REPOSITORY') == REPOSITORY, 'REPOSITORY_REQUIRED')
    base = Path(os.environ['CCB_BUILD_BASE']).resolve(strict=True)
    require(base.parent == Path(base.anchor) and base.name.startswith('ccbpack-'), 'OWNED_BASE_REQUIRED')
    control = Path(os.environ['GITHUB_WORKSPACE']).resolve(strict=True)
    request = json.loads((control / '.github/desktop-candidate-request.json').read_text(encoding='utf-8'))
    require(request['reuseBuildRun'] == int(os.environ['CCB_REUSE_BUILD_RUN']), 'REUSE_RUN_IDENTITY')
    version, commit = os.environ['CCB_CANDIDATE_VERSION'], os.environ['CCB_CANDIDATE_SOURCE']
    require(request['sourceSha'] == commit and request['desktopVersion'] == version, 'APPLICATION_IDENTITY')
    output = Path(os.environ['CCB_BUILD_OUTPUT']).resolve(strict=True)
    require(output.parent == base and not list(output.iterdir()), 'NEW_OUTPUT_REQUIRED')
    api = urllib.request.build_opener(NoRedirect())
    headers = {'Authorization': 'Bearer ' + os.environ['GITHUB_TOKEN'], 'Accept': 'application/vnd.github+json',
               'User-Agent': 'CCB-Desktop-Artifact-Revalidation', 'X-GitHub-Api-Version': '2022-11-28'}
    prefix = 'https://api.github.com/repos/' + REPOSITORY + '/actions/artifacts/'

    def extract(archive_path, destination):
        with zipfile.ZipFile(archive_path) as archive:
            require(len(archive.infolist()) <= 5000, 'ARCHIVE_MEMBER_LIMIT')
            require(sum(row.file_size for row in archive.infolist()) <= 1024**3, 'ARCHIVE_SIZE_LIMIT')
            require(len(set(archive.namelist())) == len(archive.infolist()), 'ARCHIVE_DUPLICATE_MEMBER')
            for row in archive.infolist():
                require((destination / row.filename).resolve().is_relative_to(destination.resolve()), 'ARCHIVE_ESCAPE')
                require((row.external_attr >> 16) & 0o170000 != 0o120000, 'ARCHIVE_LINK')
                require(not (destination / row.filename).exists() or row.is_dir(), 'ARCHIVE_EXISTING_FILE')
            require(archive.testzip() is None, 'ARCHIVE_CRC')
            archive.extractall(destination)

    artifacts = request['reuseArtifacts']
    require({row['name'] for row in artifacts} == {'desktop-candidate-installer', 'desktop-candidate-independent'} and len(artifacts) == 2,
            'ARTIFACT_SET_REQUIRED')
    for row in artifacts:
        url = prefix + str(int(row['id']))
        with api.open(urllib.request.Request(url, headers=headers), timeout=60) as response:
            metadata = json.load(response)
        require(metadata['workflow_run']['id'] == request['reuseBuildRun'] and
                metadata['workflow_run']['head_sha'] == request['reuseRequestCommit'] and
                metadata['name'] == row['name'] and metadata['digest'] == 'sha256:' + row['sha256'] and
                metadata['size_in_bytes'] == row['bytes'] and not metadata['expired'], 'ARTIFACT_IDENTITY')
        try:
            api.open(urllib.request.Request(url + '/zip', headers=headers), timeout=60)
            raise RuntimeError('DOWNLOAD_REDIRECT_REQUIRED')
        except urllib.error.HTTPError as redirect:
            require(redirect.code == 302, 'DOWNLOAD_REDIRECT_REFUSED')
            location = redirect.headers['Location']
        parsed = urllib.parse.urlsplit(location)
        require(parsed.scheme == 'https' and parsed.hostname and
                parsed.hostname.endswith(('.blob.core.windows.net', '.actions.githubusercontent.com', '.githubusercontent.com')),
                'DOWNLOAD_HOST_REFUSED')
        archive_path = base / (row['name'] + '.artifact.zip')
        # A fresh unauthenticated request prevents forwarding the GitHub token across hosts.
        with urllib.request.urlopen(location, timeout=120) as response, archive_path.open('xb') as target:
            shutil.copyfileobj(response, target, 1024 * 1024)
        require(archive_path.stat().st_size == row['bytes'] and sha(archive_path) == row['sha256'], 'ARTIFACT_BYTES')
        extract(archive_path, output)
    nsis = output / 'nsis'
    installer = nsis / 'ChatChatBar-Setup.exe'
    package = json.loads((nsis / 'package.json').read_text(encoding='utf-8-sig'))
    manifest = json.loads((nsis / 'install-manifest.json').read_text(encoding='utf-8-sig'))
    require(package['version'] == manifest['version'] == version and package['commit'] == manifest['commit'] == commit,
            'INSTALLER_APPLICATION_IDENTITY')
    require(sha(installer) == package['installerSha256'], 'INSTALLER_BYTES')
    require(package['compilerSha256'] == '4ec390d0f96f9728507fbc2f4e91311dd90bfc6c8ddfe2159357b3651ae151c0', 'COMPILER_IDENTITY')
    archive = output / f'ChatChatBar-Desktop-{version}-independent-windows-x64.zip'
    receipt = json.loads(archive.with_suffix('.json').read_text(encoding='utf-8'))
    require(receipt['version'] == version and receipt['commit'] == commit and sha(archive) == receipt['sha256'], 'ZIP_APPLICATION_IDENTITY')
    image_root = base / 'restored-image'
    image_root.mkdir()
    extract(archive, image_root)
    image = image_root / 'Program'
    expected = dict(version=version, commit=commit, installerVersion=os.environ['CCB_CANDIDATE_INSTALLER'],
                    installerVersionAssigned='true', packagingProfile='desktop')
    require(audit(image, expected) == json.loads((nsis / 'app-image-audit.json').read_text(encoding='utf-8')), 'RESTORED_IMAGE_IDENTITY')
    report = dict(kind='EXACT_EXISTING_CANDIDATE_BYTES_RESTORED', applicationSource=commit, version=version,
                  originalBuildRun=request['reuseBuildRun'], artifacts=artifacts, installerSha256=sha(installer),
                  independentSha256=receipt['sha256'], noRebuild=True,
                  validationControl=subprocess.check_output(['git', '-C', str(control), 'rev-parse', 'HEAD'], text=True).strip())
    (output / 'restored-candidate.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    with Path(os.environ['GITHUB_ENV']).open('a', encoding='utf-8') as environment:
        environment.write(f'CCB_CANDIDATE_IMAGE={image}\nCCB_CANDIDATE_ARCHIVE={archive}\nCCB_CANDIDATE_NSIS={nsis}\n')
    print(json.dumps(report))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        import re
        code = str(error) if isinstance(error, RuntimeError) and re.fullmatch('[A-Z0-9_]+', str(error)) else None
        print(json.dumps(dict(kind='RESTORE_CANDIDATE_FAILED', errorType=type(error).__name__, code=code)))
        raise SystemExit(2)
