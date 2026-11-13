"""Cloud-emulator-only actual delivered82 -> candidate83 reinstall proof."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from contract import (BASE, CERTIFICATE, FIXTURE_CLASS, PACKAGE, PROOF_ROOT,
                      file_hash, load, require, sha, validate_build, write_new)


def package_state(text):
    patterns = {'uid': r'^\s*userId=(\d+)\s*$',
                'versionCode': r'^\s*versionCode=(\d+)\b',
                'firstInstallTime': r'^\s*firstInstallTime=(.+?)\s*$',
                'dataDir': r'^\s*dataDir=(\S+)\s*$'}
    state = {}
    for field, pattern in patterns.items():
        matches = re.findall(pattern, text, re.M)
        require(len(matches) == 1, 'PACKAGE_FIELD_' + field)
        state[field] = int(matches[0]) if field in ('uid', 'versionCode') else matches[0]
    return state


def execution(output, method):
    require(re.search(r'OK \(1 test\)', output), 'TEST_NOT_OK_' + method)
    require(not any(word in output for word in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'INSTRUMENTATION_ABORTED')), 'TEST_FAILED_' + method)
    statuses = re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$', output, re.M)
    require(statuses == ['1', '0'], 'TEST_MUST_EXECUTE_NOT_SKIP_' + method)
    require(re.search(r'^INSTRUMENTATION_STATUS: test=' + re.escape(method) + r'\s*$', output, re.M), 'TEST_METHOD_' + method)
    require(re.search(r'^INSTRUMENTATION_STATUS: class=' + re.escape(FIXTURE_CLASS) + r'\s*$', output, re.M), 'TEST_CLASS')
    return {'method': method, 'instrumentationStatuses': [1, 0], 'executed': 1, 'passed': 1, 'skipped': 0}


def unchanged_install(before, after):
    require(before['versionCode'] == 82 and after['versionCode'] == 83, 'VERSION_PAIR')
    for field in ('uid', 'firstInstallTime', 'dataDir'):
        require(before[field] == after[field], 'INSTALL_IDENTITY_CHANGED_' + field)


def run(directory):
    # Do not let this tool accidentally target a user's adb device or an existing app.
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and
            os.environ.get('CCB_LAN_SYNTHETIC_UPGRADE_CLOUD') == 'true', 'CLOUD_CONTROL_ONLY')
    verification = load(directory / 'verification.json')
    sha(os.environ.get('GITHUB_SHA'), 40)
    require(verification['verificationControlSha'] == os.environ['GITHUB_SHA'], 'SAME_VERIFICATION_AND_UPGRADE_CONTROL')
    validate_build(verification['buildRequest'])
    require(verification['kind'] == 'CCB_LAN_82_TO_83_SIGNED_FOUR_INPUTS', 'VERIFICATION_KIND')
    rows = {row['role']: row for row in verification['entries']}
    require(len(verification['entries']) == 4 and set(rows) == {'base82', 'seed82', 'candidate83', 'verify83'}, 'FOUR_VERIFIED_APKS')
    for role, row in rows.items():
        path = directory / (role + '.apk')
        require(path.is_file() and not path.is_symlink(), 'VERIFIED_APK_FILE')
        require(file_hash(path) == row['signedSha256'] and path.stat().st_size == row['signedBytes'], 'VERIFIED_APK_CHANGED')
        require(row['officialV2Verified'] is True and row['certificateSha256'] == CERTIFICATE, 'SDK_VERIFICATION_REQUIRED')
    require(rows['base82']['signedSha256'] == BASE['deliveredSha256'] and rows['base82']['signedBytes'] == BASE['deliveredBytes'], 'EXACT_DELIVERED82_REQUIRED')

    def adb(arguments, filename):
        result = subprocess.run(['adb', *arguments], text=True, encoding='utf8', errors='replace',
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
        (directory / filename).write_text(result.stdout, encoding='utf8')
        require(result.returncode == 0, 'ADB_' + filename)
        return result.stdout

    devices = adb(['devices'], 'device-target.txt')
    targets = re.findall(r'^(\S+)\s+device\s*$', devices, re.M)
    require(len(targets) == 1 and targets[0].startswith('emulator-'), 'SINGLE_CLOUD_EMULATOR_ONLY')
    require(adb(['shell', 'getprop', 'ro.kernel.qemu'], 'device-emulator.txt').strip() == '1', 'EMULATOR_REQUIRED')
    require(adb(['shell', 'getprop', 'ro.build.version.sdk'], 'device-api.txt').strip() == '26', 'API26_REQUIRED')
    installed = adb(['shell', 'pm', 'list', 'packages', PACKAGE], 'preexisting-package-check.txt')
    require(not re.search(r'^package:' + re.escape(PACKAGE) + r'(?:\.test)?\s*$', installed, re.M), 'FRESH_DEVICE_NO_UNINSTALL')
    stages = []
    require(re.search(r'^Success\s*$', adb(['install', str(directory / 'base82.apk')], 'install-exact82.txt'), re.M), 'INSTALL_BASE82')
    require(re.search(r'^Success\s*$', adb(['install', str(directory / 'seed82.apk')], 'install-seed82-tests.txt'), re.M), 'INSTALL_SEED_TESTS')

    def stage(method, name):
        output = adb(['shell', 'am', 'instrument', '-w', '-r', '-e', 'lanDelivered82UpgradeFixture', 'true',
                      '-e', 'class', FIXTURE_CLASS + '#' + method,
                      PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'], name + '-instrumentation.txt')
        stages.append(execution(output, method))

    stage('seedSyntheticRepositoryAndProtectedPeerIdentity', 'seed82')
    before = package_state(adb(['shell', 'dumpsys', 'package', PACKAGE], 'seed82-package.txt'))
    require(before['versionCode'] == 82, 'SEEDED_VERSION82')
    proof_path = 'files/' + PROOF_ROOT + '/proof.json'
    proof_text = adb(['shell', 'run-as', PACKAGE, 'cat', proof_path], 'seed82-proof.json')
    proof = json.loads(proof_text)
    require(proof['package'] == PACKAGE and proof['seedVersionCode'] == 82, 'ACTUAL82_SEED_PROOF')
    require(proof['uid'] == before['uid'] and proof['files'], 'SEED_UID_AND_FILES')
    proof_hash = hashlib.sha256(proof_text.encode('utf8')).hexdigest()
    adb(['shell', 'am', 'force-stop', PACKAGE], 'stop82-before-upgrade.txt')
    require(re.search(r'^Success\s*$', adb(['install', '-r', str(directory / 'candidate83.apk')], 'install83-preserving-data.txt'), re.M), 'INSTALL_UPGRADE83')
    after = package_state(adb(['shell', 'dumpsys', 'package', PACKAGE], 'installed83-package.txt'))
    unchanged_install(before, after)
    # Read before verifier installation/execution: neither may reconstruct missing evidence.
    prior_verify = adb(['shell', 'run-as', PACKAGE, 'cat', proof_path], 'before-verify83-proof.json')
    require(prior_verify == proof_text, 'SEED_PROOF_CHANGED_BEFORE_VERIFY')
    require(re.search(r'^Success\s*$', adb(['install', '-r', str(directory / 'verify83.apk')], 'install-verify83-tests.txt'), re.M), 'REPLACE_TESTS_ONLY')
    stage('verifySyntheticRepositoryAndProtectedPeerIdentityAfterUpgrade', 'verify83')
    final = package_state(adb(['shell', 'dumpsys', 'package', PACKAGE], 'verified83-package.txt'))
    unchanged_install(before, final)
    final_proof = adb(['shell', 'run-as', PACKAGE, 'cat', proof_path], 'verified83-proof.json')
    require(final_proof == proof_text, 'SEED_PROOF_REWRITTEN')
    write_new(directory / 'upgrade-proof.json', {'kind': 'CCB_LAN_EXACT_DELIVERED82_TO_CANDIDATE83_PROOF',
        'verificationControlSha': verification['verificationControlSha'],
        'upgradeControlSha': os.environ['GITHUB_SHA'],
        'buildRequest': verification['buildRequest'], 'originalArtifact': verification['originalArtifact'],
        'api': 26, 'package': PACKAGE, 'versions': [82, 83], 'entries': verification['entries'],
        'stages': stages, 'fixtureStagesPassed': 2, 'executedTests': 2, 'skippedTests': 0,
        'installationBefore': before, 'installationAfter': final, 'sameUid': True,
        'sameFirstInstallTime': True, 'sameDataDir': True, 'seedProofSha256': proof_hash,
        'strictSyntheticFileHashesPreserved': True, 'allowedStateMigrations': [],
        'uninstallOrClearData': False, 'verifyReseeded': False,
        'syntheticPrivateRootOnly': True, 'normalUserProfileTested': False,
        'physicalPhoneTested': False, 'humanAcceptance': False, 'userDelivered': False})


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    run(parser.parse_args().directory)
