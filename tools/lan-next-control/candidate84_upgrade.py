"""Cloud-only exact83 reinstall84, strict original reports and one live Activity test."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess

from candidate84_profile import *
from candidate84_build import cloud
from instrumentation_report import execution
from group_ui_control import emulator_serial

# The public assembler intentionally prepends lan-preview-signing to sys.path.
# That older directory also has a prove_upgrade.py with top-level device work.
# Load ONLY the inert next-control module by its exact file, never by that name.
_install_spec = importlib.util.spec_from_file_location('_candidate84_existing_install_state', HERE / 'prove_upgrade.py')
_install_module = importlib.util.module_from_spec(_install_spec)
_install_spec.loader.exec_module(_install_module)
package_state = _install_module.package_state
SAMPLE_PATHS = ('files/entities/chat_sessions/ccb-lan-preview-sample-v2-session.json',
                'files/entities/chat_sessions/ccb-lan-preview-sample-session.json')


def unchanged_install(before, after):
    require(before['versionCode'] == 83 and after['versionCode'] == 84, 'VERSION_PAIR_83_84')
    for field in ('uid', 'firstInstallTime', 'dataDir'):
        require(before[field] == after[field], 'INSTALL_IDENTITY_CHANGED_' + field)


def require_sample_absent(stdout, stderr, code, path):
    # adb shell_v2 keeps toybox ls errors on stderr. Older adb merged them.
    # Both originals are already retained; only exact ENOENT for this fixed path counts.
    text = stdout + stderr.decode('utf-8', errors='strict')
    require(path in SAMPLE_PATHS and code == 1 and path in text and
            'No such file or directory' in text, 'UI_SAMPLE_NOT_ABSENT')


def verified_inputs(directory, control):
    verification = load(directory / 'verification.json')
    require(verification['kind'] == VERIFY_KIND and verification['verificationControlSha'] == control and
            verification['protectedCandidate84ByteTamperRejected'] is True, 'SAME_SDK_VERIFICATION_REQUIRED')
    validate_build(verification['buildRequest']); artifact_metadata(verification['originalArtifact'])
    rows = verification['entries']
    require(len(rows) == 4 and {r['role'] for r in rows} == set(ROLES), 'FOUR_VERIFIED_APKS')
    by_role = {r['role']: r for r in rows}
    for role, (_, package, version) in ROLES.items():
        row = by_role[role]; path = directory / (role + '.apk')
        require(row['package'] == package and (version is None or row['versionCode'] == version) and
                row['sourceSha'] == (BASE['sourceSha'] if role in ('base83', 'seed83') else verification['buildRequest']['sourceSha']) and
                row['testFixture'] == verification['buildRequest']['fixtures'].get(role), 'VERIFIED_APK_LINEAGE')
        require(path.is_file() and not path.is_symlink() and path.stat().st_size == row['signedBytes'] and
                file_hash(path) == row['signedSha256'], 'VERIFIED_APK_CHANGED')
        require(row['officialV2Verified'] is True and row['zipalign4Passed'] is True and
                row['certificateSha256'] == CERTIFICATE, 'OFFICIAL_SDK_PROOF_REQUIRED')
    base = by_role['base83']
    require(base['signedBytes'] == BASE['deliveredBytes'] and base['signedSha256'] == BASE['deliveredSha256'], 'EXACT_DELIVERED83_REQUIRED')
    return verification


class Device:
    def __init__(self, directory):
        self.directory = directory
        self.serial = None

    def command(self, arguments, name, allowed=(0,)):
        argv = ['adb'] + (['-s', self.serial] if self.serial else []) + arguments
        try:
            result = subprocess.run(argv, capture_output=True, timeout=180)
            stdout, stderr = result.stdout, result.stderr
        except subprocess.TimeoutExpired as error:
            for suffix, data in [('.txt', error.stdout or b''), ('-stderr.txt', error.stderr or b'')]:
                with (self.directory / (name + suffix)).open('xb') as stream: stream.write(data)
            raise ValueError('ADB_TIMEOUT_' + name) from None
        for suffix, data in [('.txt', stdout), ('-stderr.txt', stderr)]:
            with (self.directory / (name + suffix)).open('xb') as stream: stream.write(data)
        require(result.returncode in allowed, 'ADB_FAILED_' + name)
        return stdout.decode('utf-8', errors='strict'), stderr, result.returncode

    def select(self, prefix):
        self.serial = emulator_serial(self.command(['devices'], prefix + '-device-target')[0])
        require(self.command(['shell', 'getprop', 'ro.kernel.qemu'], prefix + '-qemu')[0].strip() == '1', 'QEMU_REQUIRED')
        require(self.command(['shell', 'getprop', 'ro.build.version.sdk'], prefix + '-api')[0].strip() == '26', 'API26_REQUIRED')

    def install(self, filename, name, replacing=False):
        argv = ['install'] + (['-r'] if replacing else []) + [str(self.directory / filename)]
        require(re.search(r'^Success\s*$', self.command(argv, name)[0], re.M), 'INSTALL_FAILED_' + name)

    def state(self, name):
        return package_state(self.command(['shell', 'dumpsys', 'package', PACKAGE], name)[0])

    def proof(self, name):
        return self.command(['shell', 'run-as', PACKAGE, 'cat', 'files/' + PROOF_ROOT + '/proof.json'], name)[0]

    def instrument(self, class_name, method, name, argument=None):
        argv = ['shell', 'am', 'instrument', '-w', '-r']
        if argument is not None: argv += ['-e', argument, 'true']
        argv += ['-e', 'class', class_name + '#' + method, PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner']
        text, stderr, _ = self.command(argv, name)
        require(not stderr.strip(), 'INSTRUMENTATION_STDERR_' + name)
        # Whitelisted fields, both exact status blocks, [1,0], one normal final
        # -1 and final-stream OK/Time; no println-marker or summary-only success.
        return execution(text, class_name, method)


def run(directory):
    cloud()
    require(os.environ.get('CCB_LAN_SYNTHETIC_UPGRADE_CLOUD') == 'true', 'EXPLICIT_UPGRADE_CONTROL_ONLY')
    control = sha(os.environ.get('GITHUB_SHA'), 40)
    directory = directory.resolve(strict=True)
    verification = verified_inputs(directory, control)
    result = {'kind': UPGRADE_KIND, 'passed': False, 'sourceSha': verification['buildRequest']['sourceSha'],
        'verificationControlSha': control, 'upgradeControlSha': control, 'buildRequest': verification['buildRequest'],
        'originalArtifact': verification['originalArtifact'], 'api': 26, 'package': PACKAGE, 'versions': [83, 84],
        'stages': [], 'uninstallOrClearData': False, 'verifyReseeded': False, 'allowedStateMigrations': [],
        'syntheticPrivateRootOnly': True, 'normalUserProfileTested': False, 'physicalPhoneTested': False,
        'humanAcceptance': False, 'userDelivered': False}
    require(not (directory / 'upgrade-proof.json').exists() and not (directory / 'ui-smoke-result.json').exists(), 'PROOF_ALREADY_RECORDED')
    device = Device(directory); phase = 'fresh-emulator'
    try:
        device.select('upgrade')
        require(not device.command(['shell', 'pm', 'list', 'packages', 'com.example.chatbar'], 'fresh-ccb-packages')[0].strip(), 'FRESH_DEVICE_NO_UNINSTALL')
        phase = 'install-exact83-and-seed-companion'
        device.install('base83.apk', 'install-exact83')
        device.install('seed83.apk', 'install-seed83-tests')
        phase = 'seed83'
        result['stages'].append(device.instrument(FIXTURE_CLASS, SEED_METHOD, 'seed83-instrumentation', ARGUMENT))
        before = device.state('seed83-package')
        require(before['versionCode'] == 83, 'SEEDED_VERSION83')
        proof_text = device.proof('seed83-proof')
        proof = json.loads(proof_text, object_pairs_hook=unique_object)
        require(proof['package'] == PACKAGE and proof['seedVersionCode'] == 83 and
                proof['uid'] == before['uid'] and proof['files'], 'ACTUAL83_SEED_PROOF')
        phase = 'preserving-install84'
        device.command(['shell', 'am', 'force-stop', PACKAGE], 'stop83-before-upgrade')
        device.install('candidate84.apk', 'install84-preserving-data', True)
        after = device.state('installed84-package'); unchanged_install(before, after)
        require(device.proof('before-verify84-proof') == proof_text, 'PROOF_CHANGED_BEFORE_VERIFIER')
        device.install('verify84.apk', 'install-verify84-tests', True)
        phase = 'verify84'
        result['stages'].append(device.instrument(FIXTURE_CLASS, VERIFY_METHOD, 'verify84-instrumentation', ARGUMENT))
        final = device.state('verified84-package'); unchanged_install(before, final)
        require(device.proof('verified84-proof') == proof_text, 'PROOF_REWRITTEN')
        result.update(passed=True, fixtureStagesPassed=2, executedTests=2, skippedTests=0,
            installationBefore=before, installationAfter=final, sameUid=True, sameFirstInstallTime=True,
            sameDataDir=True, strictSyntheticFileHashesPreserved=True,
            seedProofSha256=hashlib.sha256(proof_text.encode('utf-8')).hexdigest(), entries=verification['entries'])
    except Exception as error:
        result['failure'] = {'phase': phase, 'type': type(error).__name__}
        raise
    finally:
        write_new(directory / 'upgrade-proof.json', result)
    run_ui(directory)


def run_ui(directory):
    cloud()
    require(os.environ.get('CCB_LAN_SYNTHETIC_UPGRADE_CLOUD') == 'true', 'EXPLICIT_UPGRADE_CONTROL_ONLY')
    control = sha(os.environ.get('GITHUB_SHA'), 40)
    proof = load(directory / 'upgrade-proof.json')
    require(proof['kind'] == UPGRADE_KIND and proof['passed'] is True and proof['api'] == 26 and
            proof['versions'] == [83, 84] and proof['verificationControlSha'] == proof['upgradeControlSha'] == control,
            'UI_REQUIRES_ACTUAL_SAME_UPGRADE')
    validate_build(proof['buildRequest'])
    stages = [execution((directory / (name + '-instrumentation.txt')).read_text(encoding='utf-8'), FIXTURE_CLASS, method)
              for name, method in [('seed83', SEED_METHOD), ('verify84', VERIFY_METHOD)]]
    require(proof['stages'] == stages and proof['fixtureStagesPassed'] == proof['executedTests'] == 2 and
            proof['skippedTests'] == 0, 'UI_REQUIRES_TWO_ACTUAL_PASSES')
    unchanged_install(proof['installationBefore'], proof['installationAfter'])
    result = {'kind': 'CCB_LAN84_POST_UPGRADE_ACTIVITY_REPOSITORY_SMOKE', 'passed': False,
        'sourceSha': proof['buildRequest']['sourceSha'], 'controlSha': control, 'class': UI_CLASS, 'method': UI_METHOD,
        'scope': 'Actual legacy LanSyncActivity launch/recreate and synthetic repository/image decode after upgrade; not group UI, FGS, remote/cloud, OS death or human acceptance.',
        'installUninstallOrClearPerformed': False, 'humanAcceptance': False}
    phase = 'ui-before'
    try:
        device = Device(directory); device.select('ui')
        before = device.state('ui-before-package')
        require(before == proof['installationAfter'] and before['versionCode'] == 84, 'UI_INSTALL_CHANGED')
        seed_proof = device.proof('ui-before-proof')
        require(hashlib.sha256(seed_proof.encode('utf-8')).hexdigest() == proof['seedProofSha256'], 'UI_PROOF_CHANGED')
        for index, path in enumerate(SAMPLE_PATHS):
            text, stderr, code = device.command(['shell', 'run-as', PACKAGE, 'ls', path], 'ui-sample-absence-' + str(index), (0, 1))
            require_sample_absent(text, stderr, code, path)
        phase = 'actual-live-activity'
        actual = device.instrument(UI_CLASS, UI_METHOD, 'ui-smoke-instrumentation')
        after = device.state('ui-after-package')
        require(before == after and device.proof('ui-after-proof') == seed_proof, 'UI_INSTALL_OR_PROOF_CHANGED')
        result.update(passed=True, execution=actual, priorUpgradeTestsPassed=2, priorUpgradeSkipped=0,
            installationBefore=before, installationAfter=after, upgradeSeedProofUnchanged=True,
            samplePathsAbsentBefore=list(SAMPLE_PATHS))
    except Exception as error:
        result['failure'] = {'phase': phase, 'type': type(error).__name__}
        raise
    finally:
        write_new(directory / 'ui-smoke-result.json', result)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    run(parser.parse_args().directory)
