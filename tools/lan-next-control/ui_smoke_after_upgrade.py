"""One cloud-only Activity/repository smoke after the actual82 ->83 upgrade proof."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess

from contract import PACKAGE, PROOF_ROOT, load, require, sha, validate_build, write_new
from prove_upgrade import execution, package_state, unchanged_install

UI_CLASS = 'com.example.chatbar.lanSync.LanSyncUiTest'
UI_METHOD = 'syncActivityReopensAndOfflineSampleUsesActualRepositories'
# Frozen source90 LanSyncPreviewSample.kt13-15 / LanSyncUiTest.kt26-27.
SAMPLE_PATHS = (
    'files/entities/chat_sessions/ccb-lan-preview-sample-v2-session.json',
    'files/entities/chat_sessions/ccb-lan-preview-sample-session.json',
)


def ui_execution(output):
    require(re.search(r'^OK \(1 test\)\s*$', output, re.M), 'UI_TEST_NOT_OK')
    require(not any(value in output for value in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'INSTRUMENTATION_ABORTED')), 'UI_TEST_FAILED')
    statuses = re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$', output, re.M)
    require(statuses == ['1', '0'], 'UI_TEST_MUST_EXECUTE_NOT_SKIP')
    classes = re.findall(r'^INSTRUMENTATION_STATUS: class=(.+?)\s*$', output, re.M)
    methods = re.findall(r'^INSTRUMENTATION_STATUS: test=(.+?)\s*$', output, re.M)
    require(classes and all(value == UI_CLASS for value in classes), 'UI_TEST_CLASS')
    require(methods and all(value == UI_METHOD for value in methods), 'UI_TEST_METHOD')
    require(re.findall(r'^INSTRUMENTATION_CODE: (-?\d+)\s*$', output, re.M) == ['-1'], 'UI_INSTRUMENTATION_RESULT')
    return {'class': UI_CLASS, 'method': UI_METHOD, 'rawStatuses': [1, 0], 'executed': 1, 'passed': 1, 'skipped': 0}


def run(directory):
    # This guard precedes filesystem input inspection and every adb invocation.
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and
            os.environ.get('CCB_LAN_SYNTHETIC_UPGRADE_CLOUD') == 'true', 'CLOUD_CONTROL_ONLY')
    sha(os.environ.get('GITHUB_SHA'), 40)
    directory = directory.resolve(strict=True)
    output = directory / 'ui-smoke-result.json'
    require(not output.exists(), 'UI_SMOKE_ALREADY_RECORDED')
    result = {'kind': 'CCB_LAN83_POST_UPGRADE_ACTIVITY_REPOSITORY_SMOKE', 'passed': False,
              'controlSha': os.environ['GITHUB_SHA'], 'class': UI_CLASS, 'method': UI_METHOD,
              'installUninstallOrClearPerformed': False, 'humanAcceptance': False,
              'scope': 'Actual LanSyncActivity launch/recreate, synthetic repository versions and BitmapFactory file decode only.',
              'holdConflictRecoveryFrozenImageUiTested': False, 'settingsNavigationTested': False}
    serial = None

    def adb(arguments, filename, expected=(0,)):
        command = ['adb'] + (['-s', serial] if serial else []) + arguments
        process = subprocess.run(command, text=True, encoding='utf8', errors='replace',
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
        (directory / filename).write_text(process.stdout, encoding='utf8')
        require(process.returncode in expected, 'UI_ADB_' + filename)
        return process

    try:
        proof = load(directory / 'upgrade-proof.json')
        require(proof['kind'] == 'CCB_LAN_EXACT_DELIVERED82_TO_CANDIDATE83_PROOF' and
                proof['verificationControlSha'] == proof['upgradeControlSha'] == os.environ['GITHUB_SHA'], 'UI_REQUIRES_SAME_ACTUAL_UPGRADE')
        validate_build(proof['buildRequest'])
        require(proof['api'] == 26 and proof['package'] == PACKAGE and proof['versions'] == [82, 83], 'UI_UPGRADE_IDENTITY')
        stages = [execution((directory / filename).read_text(encoding='utf8'), method) for filename, method in (
            ('seed82-instrumentation.txt', 'seedSyntheticRepositoryAndProtectedPeerIdentity'),
            ('verify83-instrumentation.txt', 'verifySyntheticRepositoryAndProtectedPeerIdentityAfterUpgrade'))]
        require(proof['stages'] == stages and proof['fixtureStagesPassed'] == proof['executedTests'] == 2 and
                proof['skippedTests'] == 0, 'UI_REQUIRES_TWO_UPGRADE_PASSES')
        require(all(proof[key] is True for key in ('sameUid', 'sameFirstInstallTime', 'sameDataDir', 'strictSyntheticFileHashesPreserved', 'syntheticPrivateRootOnly')) and
                proof['uninstallOrClearData'] is False and proof['verifyReseeded'] is False and proof['allowedStateMigrations'] == [], 'UI_UPGRADE_PRESERVATION_REQUIRED')
        unchanged_install(proof['installationBefore'], proof['installationAfter'])

        devices = adb(['devices'], 'ui-device-target.txt').stdout
        targets = re.findall(r'^(\S+)\s+(device|offline|unauthorized)\s*$', devices, re.M)
        require(len(targets) == 1 and targets[0][0].startswith('emulator-') and targets[0][1] == 'device', 'UI_SINGLE_CLOUD_EMULATOR_ONLY')
        serial = targets[0][0]
        require(adb(['shell', 'getprop', 'ro.kernel.qemu'], 'ui-device-emulator.txt').stdout.strip() == '1', 'UI_EMULATOR_REQUIRED')
        require(adb(['shell', 'getprop', 'ro.build.version.sdk'], 'ui-device-api.txt').stdout.strip() == '26', 'UI_API26_REQUIRED')
        before = package_state(adb(['shell', 'dumpsys', 'package', PACKAGE], 'ui-before-package.txt').stdout)
        require(before == proof['installationAfter'] and before['versionCode'] == 83, 'UI_INSTALLED83_CHANGED')
        proof_path = 'files/' + PROOF_ROOT + '/proof.json'
        before_proof = adb(['shell', 'run-as', PACKAGE, 'cat', proof_path], 'ui-before-upgrade-proof.json').stdout
        require(hashlib.sha256(before_proof.encode('utf8')).hexdigest() == proof['seedProofSha256'], 'UI_EXISTING_UPGRADE_PROOF_CHANGED')
        for index, path in enumerate(SAMPLE_PATHS):
            absence = adb(['shell', 'run-as', PACKAGE, 'ls', path], 'ui-sample-absence-' + str(index) + '.txt', expected=(0, 1))
            require(absence.returncode == 1 and path in absence.stdout and 'No such file or directory' in absence.stdout, 'UI_SAMPLE_ALREADY_PRESENT_OR_UNREADABLE')

        raw = adb(['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', UI_CLASS + '#' + UI_METHOD,
                   PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'], 'ui-smoke-instrumentation.txt').stdout
        actual = ui_execution(raw)
        after = package_state(adb(['shell', 'dumpsys', 'package', PACKAGE], 'ui-after-package.txt').stdout)
        require(after == before, 'UI_INSTALL_IDENTITY_CHANGED')
        final_proof = adb(['shell', 'run-as', PACKAGE, 'cat', proof_path], 'ui-after-upgrade-proof.json').stdout
        require(final_proof == before_proof, 'UI_UPGRADE_PROOF_REWRITTEN')
        result.update(passed=True, api=26, emulator=serial, package=PACKAGE, priorUpgradeTestsPassed=2,
                      priorUpgradeSkipped=0, samplePathsAbsentBefore=list(SAMPLE_PATHS), execution=actual,
                      installationBefore=before, installationAfter=after, upgradeSeedProofUnchanged=True,
                      rawInstrumentation='ui-smoke-instrumentation.txt')
    except Exception as failure:
        result['failure'] = {'type': type(failure).__name__, 'message': str(failure)}
        raise
    finally:
        write_new(output, result)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    run(parser.parse_args().directory)
