"""Cloud-only ordinary JAR/installer upgrade chain; never publishes or touches a host profile.

The separate fault NSIS deliberately pairs a 1.0.2 manifest with the unmodified
ordinary 1.0.1 image to exercise the existing READY identity rejection. It is not
an audited candidate, is not uploaded, and adds no production fault switch.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request

PUBLIC_SHA = 'ee0914020b1f11c7ab27a8cf23a7dfbe869ea56879a177dc788f92dc15ba86e8'
PUBLIC_SOURCE = '2ba39c165cb42ca1b043e5c6b4a504406188c13e'
PUBLIC_URL = ('https://github.com/MisakaPiano/ChatChatBar-Desktop/releases/download/desktop-v0.1.0-preview.1233/'
              'ChatChatBar-Desktop-0.1.0-preview.1233-windows-x64.exe')


def require(value, code):
    if not value:
        raise RuntimeError(code)


def sha(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def run(command, log, cwd=None):
    print('CLOUD_CHAIN_CHILD_START ' + log.name, flush=True)
    with log.open('xb') as output:
        result = subprocess.run(list(map(str, command)), stdout=output, stderr=subprocess.STDOUT, cwd=cwd)
    print('CLOUD_CHAIN_CHILD_EXIT ' + log.name + ' ' + str(result.returncode), flush=True)
    require(result.returncode == 0, 'CLOUD_CHAIN_CHILD_FAILED:' + log.name)


def create_fault(next_package, output, compiler, source, log):
    """Copy only native bootstrap inputs; the ordinary image and package remain unchanged."""
    require(not output.exists(), 'FAULT_OUTPUT_EXISTS')
    output.mkdir()
    for name in ['ChatChatBar.exe', 'OpenChatBarFile.exe', 'install-manager.exe']:
        shutil.copyfile(next_package / name, output / name)
    original = json.loads((next_package / 'install-manifest.json').read_text(encoding='utf-8'))
    require(original['version'] == '1.0.1' and original['buildNumber'] == 1236, 'FAULT_SOURCE_IDENTITY')
    fault = dict(original, version='1.0.2', buildNumber=1237, storageVersion='1.0.2-preview.1237')
    save(output / 'install-manifest.json', fault)
    text = (next_package / 'installer.nsi').read_text(encoding='utf-8-sig')
    old_output = str(next_package).replace('/', '\\')
    new_output = str(output).replace('/', '\\')
    require(old_output in text, 'FAULT_NATIVE_INPUTS_NOT_FOUND')
    text = text.replace(old_output, new_output)
    old_id = '1.0.1-preview.1236-' + source[:12]
    require(old_id in text, 'FAULT_STORAGE_INPUT_NOT_FOUND')
    text = text.replace(old_id, '1.0.2-preview.1237-' + source[:12])
    script = output / 'installer.nsi'
    script.write_text(text, encoding='utf-8-sig')
    run([compiler, script], log)
    return dict(kind='CLOUD_ONLY_UNAUDITED_READY_IDENTITY_FAULT', claimedVersion='1.0.2',
                ordinaryJarVersion='1.0.1', productionFaultSwitch=False,
                manifestSha256=sha(output / 'install-manifest.json'), installerSha256=sha(output / 'ChatChatBar-Setup.exe'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--compiler', type=Path)
    parser.add_argument('--baseline-only', action='store_true', help='Only pinned public1233 fresh-profile lifecycle; no new JAR build or upgrade')
    args = parser.parse_args()
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and
            os.environ.get('GITHUB_REPOSITORY') == 'MisakaPiano/ChatChatBar-Desktop' and os.name == 'nt', 'CLOUD_WINDOWS_ONLY')
    source = args.source.resolve(strict=True)
    expected = os.environ['CCB_CANDIDATE_SOURCE']
    require(source == Path(os.environ['CCB_CI_SOURCE']).resolve(strict=True) and
            subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() == expected,
            'FROZEN_APPLICATION_SOURCE_REQUIRED')
    output = args.output.resolve(strict=True)
    require(output.is_relative_to(Path(os.environ['CCB_BUILD_BASE']).resolve(strict=True)), 'OWNED_OUTPUT_REQUIRED')
    reports = output / 'stable-chain-reports'
    reports.mkdir()
    control = Path(__file__).resolve().parents[3]
    driver = control / 'tools/desktop/nsis/native_smoke.py'
    builder = control / 'tools/desktop/nsis/build_installer.py'
    image = source / 'app/desktopApp/build/compose/binaries/main/app/ChatChatBarDesktop'
    baseline = output / 'public1233.exe'
    with urllib.request.urlopen(PUBLIC_URL, timeout=60) as response, baseline.open('xb') as destination:
        shutil.copyfileobj(response, destination)
    require(sha(baseline) == PUBLIC_SHA, 'PINNED_PUBLIC1233_HASH')
    approved = source / 'app/desktopApp/build/preview-continuous-private-20261025'
    approved.mkdir(parents=True, exist_ok=True)
    case = approved / 'windows-runtime-nsis-stable-chain'
    program = output / 'installed'
    # Record the actual Framework state-log path before starting the frozen installer.
    # This is numeric/path evidence in the cloud-owned profile, not a new installer admission rule.
    local_appdata = case / 'local-appdata'
    state_log = local_appdata / 'ChatChatBarDesktopNSISInstallState' / (
        hashlib.sha256(str(program.absolute()).upper().encode('utf-8')).hexdigest().upper() + '.log')
    save(reports / 'baseline-paths.json', dict(kind='CLOUD_OWNED_BASELINE_PATHS',
         programRoot=str(program), localAppData=str(local_appdata), managerLog=str(state_log),
         programRootUtf16Units=len(str(program).encode('utf-16-le')) // 2,
         localAppDataUtf16Units=len(str(local_appdata).encode('utf-16-le')) // 2,
         managerLogUtf16Units=len(str(state_log).encode('utf-16-le')) // 2,
         installerSha256=sha(baseline), nativeApplicationSource=PUBLIC_SOURCE,
         installerOrPathGuardsChanged=False, baselineOnly=args.baseline_only))
    common = [sys.executable, '-B', driver, '--case', case, '--image', image, '--root', program,
              '--fresh-default-profile', '--ci-source', source, '--allow-owned-update-helper-cwd']
    steps = []

    def drive(label, installer=None, extra=()):
        run(common + ['--label', label] + (['--installer', installer] if installer else []) + list(extra),
            reports / (label + '-driver.log'))
        result = json.loads((case / (label + '-result.json')).read_text(encoding='utf-8'))
        require(not result['remaining'], 'CHILD_JOB_NOT_DRAINED')
        steps.append(dict(label=label, result=result))
        shutil.copyfile(case / (label + '-result.json'), reports / (label + '-result.json'))

    def identity(version, number, commit):
        current = (program / 'current.txt').read_text(encoding='utf-8').strip()
        manifest = json.loads((program / 'versions' / current / 'install-manifest.json').read_text(encoding='utf-8'))
        require(manifest['version'] == version and manifest['commit'] == commit, 'ACTUAL_INSTALLED_IDENTITY')
        if number != 1233:
            require(manifest['buildNumber'] == number and manifest['storageVersion'] + '-' + commit[:12] == current,
                    'ACTUAL_INSTALLED_STORAGE_IDENTITY')
        return current

    drive('public1233', baseline)
    identity('0.1.0-preview.1233', 1233, PUBLIC_SOURCE)
    root_hashes = {name: sha(program / name) for name in ['ChatChatBar.exe', 'OpenChatBarFile.exe', 'Uninstall.exe']}
    if args.baseline_only:
        save(reports / 'BASELINE_RESULT.json', dict(kind='PINNED_PUBLIC1233_FRESH_PROFILE_ONLY',
             pinnedInstallerSha256=PUBLIC_SHA, nativeApplicationSource=PUBLIC_SOURCE,
             namespaceSource=expected, rootThreeResourceHashes=root_hashes, steps=steps, passed=True,
             newJarBuild=False, upgradeStarted=False, userMachineInstall=False))
        return
    require(args.compiler and args.compiler.is_file(), 'PINNED_COMPILER_REQUIRED')
    canary = case / 'local-appdata/ChatChatBarDesktop/stable-chain-canary.txt'
    canary.write_bytes(b'SYNTHETIC_ORDINARY_STABLE_CHAIN_CANARY')
    canary_hash = sha(canary)

    def retained():
        require({name: sha(program / name) for name in root_hashes} == root_hashes, 'OLD_ROOT_THREE_RESOURCES_CHANGED')
        require(sha(canary) == canary_hash, 'FRESH_PROFILE_CANARY_CHANGED')

    packages = {}
    for version, number, installer in [('0.1.0-preview.1234', 1234, '0.1.1234'), ('1.0.0', 1235, '1.0.0'), ('1.0.1', 1236, '1.0.1')]:
        tag = str(number)
        run([source / 'app/gradlew.bat', ':desktopApp:verifyDesktopInstallerIdentity', ':desktopApp:attachDesktopUpdater',
             '-PdesktopVersion=' + version, '-PdesktopInstallerVersion=' + installer, '-PdesktopBuildNumber=' + tag,
             '--no-daemon', '--no-configuration-cache', '--max-workers=2', '--console=plain'], reports / (tag + '-build.log'), source / 'app')
        package = output / ('nsis-' + tag)
        run([sys.executable, '-B', builder, '--image', image, '--output', package, '--version', version,
             '--commit', expected, '--installer-version', installer, '--build-number', tag, '--profile', 'desktop',
             '--makensis', args.compiler], reports / (tag + '-package.log'))
        packages[number] = package
        for name in ['package.json', 'install-manifest.json', 'app-image-audit.json']:
            shutil.copyfile(package / name, reports / (tag + '-' + name))

    for label, version, number in [('bridge1234', '0.1.0-preview.1234', 1234), ('stable', '1.0.0', 1235)]:
        drive(label, packages[number] / 'ChatChatBar-Setup.exe')
        identity(version, number, expected); retained()
        drive(label + '-reopen', extra=['--probe-external-files'])
        identity(version, number, expected); retained()
    old_pointer = (program / 'current.txt').read_bytes()
    fault = output / 'fault-ready-identity'
    fault_receipt = create_fault(packages[1236], fault, args.compiler, expected, reports / 'fault-package.log')
    save(reports / 'fault-scope.json', fault_receipt)
    # Ordinary JAR reports its real1.0.1 identity; the intentionally mismatched descriptor is refused.
    drive('failed-next', fault / 'ChatChatBar-Setup.exe',
          ['--expect-install-failure', '--ci-fresh-readiness-failure', 'READY_IDENTITY_REFUSED'])
    require((program / 'current.txt').read_bytes() == old_pointer, 'FAILED_NEXT_REPLACED_CURRENT')
    retained()
    drive('stable-after-failure')
    identity('1.0.0', 1235, expected); retained()
    drive('next-stable', packages[1236] / 'ChatChatBar-Setup.exe')
    identity('1.0.1', 1236, expected); retained()
    drive('next-stable-cold', extra=['--probe-external-files', '--cold-external-file', '--cold-external-matrix'])
    identity('1.0.1', 1236, expected); retained()
    save(reports / 'RESULT.json', dict(kind='ORDINARY_FROZEN_JAR_STABLE_CHAIN', source=expected,
         controlSource=subprocess.check_output(['git', '-C', str(control), 'rev-parse', 'HEAD'], text=True).strip(),
         pinnedPublic1233Sha256=PUBLIC_SHA, rootThreeResourceHashes=root_hashes, syntheticCanarySha256=canary_hash,
         steps=steps, passed=True, fakeCredentialRetentionVerified=False, customDataRootBusyVerified=False,
         actualInAppDownloadHandoff=False, signedClientBridgeProof='Separate frozen-client TUF test',
         humanAcceptance=False, publication=False, userMachineInstall=False))


if __name__ == '__main__':
    main()
