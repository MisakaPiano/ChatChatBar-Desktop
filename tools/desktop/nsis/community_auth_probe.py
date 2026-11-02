"""Cloud-only native companion over an exact restored ordinary candidate; no provider login."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

from native_smoke import OWNER, environment


def require(value, code):
    if not value:
        raise RuntimeError(code)


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def tree(path):
    result = {}
    total = 0
    for entry in path.rglob('*'):
        require(not entry.is_symlink() and not (getattr(entry.lstat(), 'st_file_attributes', 0) & 0x400), 'PROFILE_REPARSE_REFUSED')
        if entry.is_file():
            total += entry.stat().st_size
            require(len(result) < 10000 and total <= 256 * 1024 * 1024, 'SYNTHETIC_PROFILE_BOUND_REFUSED')
            result[str(entry.relative_to(path))] = sha(entry)
    return result


def execute(command, env, log, timeout):
    with log.open('xb') as output:
        process = subprocess.Popen(command, env=env, stdout=output, stderr=subprocess.STDOUT)
        try:
            return process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            # Do not force-close a real program and then call its exit successful.
            raise RuntimeError('PROBE_PROCESS_EXIT_UNCONFIRMED')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case', type=Path, required=True)
    parser.add_argument('--root', type=Path, required=True)
    args = parser.parse_args()
    require(os.name == 'nt' and os.environ.get('GITHUB_ACTIONS') == 'true' and
            os.environ.get('GITHUB_REPOSITORY') == 'MisakaPiano/ChatChatBar-Desktop', 'EXPLICIT_WINDOWS_CI_REQUIRED')
    source = Path(os.environ['CCB_CI_SOURCE']).resolve(strict=True)
    require(subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], encoding='utf-8').strip() ==
            os.environ['CCB_CANDIDATE_SOURCE'], 'EXACT_APPLICATION_SOURCE_REQUIRED')
    base = Path(os.environ['CCB_BUILD_BASE']).resolve(strict=True)
    require(base.parent == Path(base.anchor) and base.name.startswith('ccbpack-'), 'OWNED_CLOUD_BASE_REQUIRED')
    case, root = args.case.resolve(strict=True), args.root.resolve(strict=True)
    approved = source / 'app/desktopApp/build/preview-continuous-private-20261025'
    require(case.parent == approved and case.name == 'windows-runtime-nsis-cloud', 'OWNED_CASE_REQUIRED')
    require(root.parent == base and root.name == 'installed-auth', 'OWNED_AUTH_INSTALL_REQUIRED')
    require(json.loads((case / 'TASK_OWNER.json').read_text(encoding='utf-8'))['kind'] == OWNER, 'CASE_OWNER_REQUIRED')
    current = (root / 'current.txt').read_text(encoding='utf-8').strip()
    home = (root / 'versions' / current).resolve(strict=True)
    require(home.parent == root / 'versions', 'CURRENT_PROGRAM_SCOPE')
    manifest = json.loads((home / 'install-manifest.json').read_text(encoding='utf-8-sig'))
    require(manifest['commit'] == os.environ['CCB_CANDIDATE_SOURCE'] and manifest['version'] == os.environ['CCB_CANDIDATE_VERSION'], 'INSTALLED_IDENTITY_REQUIRED')
    data = case / 'local-appdata/ChatChatBarDesktop'
    require(data.is_dir(), 'INITIALIZED_SYNTHETIC_DATA_REQUIRED')
    before_data = tree(data)
    before_root = {name: sha(root / name) for name in ['ChatChatBar.exe', 'OpenChatBarFile.exe', 'Uninstall.exe', 'current.txt']}
    report_path = case / 'auth-helper-result.json'
    require(not report_path.exists(), 'NEW_NATIVE_PROBE_REPORT_REQUIRED')
    classes = case / 'temp/community-auth-probe-classes'
    classes.mkdir()
    java_source = Path(__file__).with_name('CommunityAuthHelperProbe.java')
    jdk = Path(os.environ['JAVA_HOME']) / 'bin'
    env = environment(case)
    require(not env.get('GITHUB_TOKEN'), 'NO_GITHUB_TOKEN_IN_NATIVE_CHILD_REQUIRED')
    code = execute([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '-d', str(classes), str(java_source)], env,
                   case / 'auth-helper-compile-stderr.log', 120)
    require(code == 0, 'PROBE_COMPANION_COMPILE_FAILED')
    cp = str(classes) + os.pathsep + str(home / 'app/*')
    code = execute([str(jdk / 'java.exe'), '-Djava.awt.headless=true', '-cp', cp, 'CommunityAuthHelperProbe',
                    str(home), str(case / 'local-appdata'), str(report_path)], env, case / 'auth-helper-probe-stderr.log', 120)
    require(report_path.is_file(), 'PROBE_RECEIPT_MISSING')
    report = json.loads(report_path.read_text(encoding='utf-8'))
    report.update(applicationSource=os.environ['CCB_CANDIDATE_SOURCE'], version=manifest['version'],
                  controlSource=subprocess.check_output(['git', '-C', os.environ['GITHUB_WORKSPACE'], 'rev-parse', 'HEAD'], encoding='utf-8').strip(),
                  companionSourceSha256=sha(java_source), driverSourceSha256=sha(Path(__file__)),
                  syntheticDataUnchanged=tree(data) == before_data,
                  fourRootResourcesUnchanged={name: sha(root / name) for name in before_root} == before_root,
                  freshDefaultProfile=True, fakeCredentialRetentionVerified=False, customDataRootBusyVerified=False,
                  humanAcceptance=False, installerRunOnUserMachine=False)
    report_path.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    require(code == 0 and report['status'] in ['PASS', 'SKIPPED_PREEXISTING_HANDLER'], 'NATIVE_AUTH_HELPER_FAILED')
    require(report['syntheticDataUnchanged'] and report['fourRootResourcesUnchanged'], 'NATIVE_AUTH_CHANGED_EXISTING_STATE')
    print(json.dumps(report))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        import re
        code = str(error) if isinstance(error, RuntimeError) and re.fullmatch('[A-Z0-9_]+', str(error)) else None
        failure = dict(kind='COMMUNITY_AUTH_NATIVE_PROBE_FAILED', errorType=type(error).__name__, code=code)
        # Archive an explicit failure even when compilation or launch produced no JVM receipt.
        if '--case' in sys.argv:
            case = Path(sys.argv[sys.argv.index('--case') + 1])
            expected = Path(os.environ.get('CCB_CI_SOURCE', '')) / 'app/desktopApp/build/preview-continuous-private-20261025/windows-runtime-nsis-cloud'
            if case.is_absolute() and case == expected and (case / 'TASK_OWNER.json').is_file():
                target = case / 'auth-helper-driver-failure-result.json'
                if not target.exists():
                    target.write_text(json.dumps(failure, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(failure))
        raise SystemExit(2)
