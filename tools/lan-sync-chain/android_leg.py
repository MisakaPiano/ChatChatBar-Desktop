"""Cloud-only binary artifact transfer around one actual connected Android method."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
sys.path.insert(0,str(Path(__file__).resolve().parent))
from verify_xml import verify

source=Path(os.environ['CCB_CHAIN_SOURCE'])
sha=os.environ['CCB_CHAIN_SOURCE_SHA']
output=Path('chain-android-evidence'); output.mkdir()
origins=list(Path('origin-input').rglob('windows-origin.zip')); assert len(origins)==1
origin=origins[0].read_bytes()
apks=list((source/'app/app/build/outputs/apk/lanPreview').glob('*.apk')); assert len(apks)==1
package='com.example.chatbar.lansync'

def adb(args,data=None):
    p=subprocess.run(['adb',*args],input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=120)
    assert p.returncode==0, (args,p.stderr.decode('utf8',errors='replace'))
    return p.stdout

assert b'Success' in adb(['install',str(apks[0])])
adb(['shell','run-as',package,'mkdir','-p','files/lan-cross-platform-exchange'])
command="run-as com.example.chatbar.lansync sh -c 'cat > files/lan-cross-platform-exchange/input.zip'"
adb(['shell','-T',command],origin)
assert adb(['exec-out','run-as',package,'cat','files/lan-cross-platform-exchange/input.zip'])==origin
arguments=[str(source/'app/gradlew'),':app:connectedLanPreviewAndroidTest','-PlanSyncDeviceTests=true',
 '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true',
 '-Pandroid.testInstrumentationRunnerArguments.class=com.example.chatbar.lanSync.LanSyncCrossPlatformFixtureTest#importEditAndReturnWindowsSyntheticData',
 '-Pandroid.testInstrumentationRunnerArguments.lanSyncCrossPlatformProof=true',
 '-Pandroid.testInstrumentationRunnerArguments.lanSyncChainSourceSha='+sha,'--no-daemon','--no-build-cache','--stacktrace']
assert not (source/'app/app/build/outputs/androidTest-results').exists(), 'STALE_ANDROID_RESULTS'
with (output/'connected-original.log').open('wb') as log:
    result=subprocess.run(arguments,cwd=source/'app',stdout=log,stderr=subprocess.STDOUT)
results=source/'app/app/build/outputs/androidTest-results'
if results.exists(): shutil.copytree(results,output/'original-test-results')
assert result.returncode==0, 'CONNECTED_TEST_FAILED'
verify(results,'com.example.chatbar.lanSync.LanSyncCrossPlatformFixtureTest','importEditAndReturnWindowsSyntheticData')
for name in ('return.zip','android-report.json'):
    data=adb(['exec-out','run-as',package,'cat','files/lan-cross-platform-exchange/'+name])
    assert data
    (output/name).write_bytes(data)
shutil.copyfile('.github/lan-cross-platform-chain-request.json',output/'source-request.json')
print('Original Android return binary SHA256='+hashlib.sha256((output/'return.zip').read_bytes()).hexdigest())
