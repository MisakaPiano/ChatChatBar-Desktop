"""Run the two ordered, synthetic-only upgrade fixture stages in the cloud."""
import json
from pathlib import Path
import re
import subprocess

PACKAGE = 'com.example.chatbar.lansyncnext'
CLASS = 'com.example.chatbar.lanSync.LanSyncPreviewUpgradeTest'
ROOT = Path('candidate')


def adb(arguments, filename):
    result = subprocess.run(['adb', *arguments], text=True, encoding='utf8', errors='replace',
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    (ROOT/filename).write_text(result.stdout, encoding='utf8')
    assert result.returncode == 0, filename
    return result.stdout


def stage(method, expected_version):
    output = adb(['shell', 'am', 'instrument', '-w', '-r', '-e', 'lanSyncUpgradeFixture', 'true',
                  '-e', 'class', CLASS+'#'+method,
                  PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner'], method+'.txt')
    assert re.search(r'OK \(1 test\)', output), method
    assert not any(word in output for word in ['FAILURES!!!', 'INSTRUMENTATION_FAILED', 'INSTRUMENTATION_ABORTED'])
    # JUnit assumptions may still yield OK. Require an actually executed successful test,
    # not AndroidJUnitRunner's -3 ignored/-4 assumption-failure terminal status.
    statuses = re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$', output, re.M)
    assert statuses == ['1', '0'], (method, statuses)
    package = adb(['shell', 'dumpsys', 'package', PACKAGE], method+'-package.txt')
    assert re.search(r'\bversionCode='+str(expected_version)+r'\b', package)


verification = json.loads((ROOT/'verification.json').read_text(encoding='utf8'))
seed_fixture = verification.get('previousSeedFixture')
assert 'Success' in adb(['install', str(ROOT/'base.apk')], 'install-base.txt')
assert 'Success' in adb(['install', str(ROOT/('seed-tests.apk' if seed_fixture else 'tests.apk'))], 'install-tests.txt')
stage('seedSyntheticRepositoryAndProtectedPeerIdentity', 81)
adb(['shell', 'am', 'force-stop', PACKAGE], 'stop-before-upgrade.txt')
assert 'Success' in adb(['install', '-r', str(ROOT/'upgrade.apk')], 'install-upgrade.txt')
if seed_fixture:
    assert 'Success' in adb(['install', '-r', str(ROOT/'tests.apk')], 'install-upgrade-tests.txt')
stage('verifySyntheticRepositoryAndProtectedPeerIdentityAfterUpgrade', 82)
receipt = {'kind': 'CCB_LAN_NEXT_PREVIEW_UPGRADE_PROOF', 'api': 26,
           'package': PACKAGE, 'versions': [81, 82], 'sameSignerUpgradeAccepted': True,
           'fixtureStagesPassed': 2, 'uninstallOrClearData': False,
           'physicalPhoneTested': False, 'firstPreviewMigrated': False,
           'humanAcceptance': False, 'userDelivered': False}
receipt['sourceSha'] = verification['sourceSha']
receipt['previousDeliveredBase'] = verification.get('previousDeliveredBase')
receipt['previousSeedFixture'] = seed_fixture
receipt['baseSha256'] = next(row['sha256'] for row in verification['entries'] if row['role'] == 'base')
receipt['upgradeSha256'] = next(row['sha256'] for row in verification['entries'] if row['role'] == 'upgrade')
(ROOT/'upgrade-proof.json').write_text(json.dumps(receipt, indent=2), encoding='utf8')
print(json.dumps(receipt))
