import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from xml.etree import ElementTree as ET

root = Path.cwd()
out = root/'android-review-r6-originals'
out.mkdir(exist_ok=True)
SOURCE = 'a77dba550123ae16a197ed97cb99563eca579a93'
APP_ID = 'com.example.chatbar.nextreview.r6'
LABEL = 'CCB 集中验收 r6'
FLAGS = ['-PCHATBAR_ISOLATED_ANDROID_TEST=true', '-PCHATBAR_ISOLATED_ANDROID_TEST_APP_ID='+APP_ID]

def gradle(*args):
    return subprocess.run(['./gradlew', *args, *FLAGS, '--no-daemon', '--stacktrace', '--info', '--console=plain'], cwd=root/'app')

def report(label, native=False):
    src = root/('app/app/build/outputs/androidTest-results/connected' if native else 'app/app/build/test-results/testDebugUnitTest')
    dest = out/label
    shutil.copytree(src, dest)
    cases = []
    for path in dest.rglob('TEST-*.xml'):
        for c in ET.parse(path).iter('testcase'):
            err = c.find('failure')
            if err is None:
                err = c.find('error')
            cases.append(dict(classname=c.get('classname'), name=c.get('name'), skipped=c.find('skipped') is not None,
                failure=None if err is None else dict(type=err.get('type') or (err.text or '').split(':',1)[0], text=err.text)))
    (out/(label+'.json')).write_text(json.dumps(cases,ensure_ascii=False,indent=2))
    return cases

def package(label):
    path = root/'app/app/build/outputs/apk/debug/app-debug.apk'
    signer = sorted(Path(os.environ['ANDROID_HOME']).glob('build-tools/*/apksigner'))[-1]
    signature = subprocess.check_output([str(signer),'verify','--verbose','--print-certs',str(path)], text=True)
    badging = subprocess.check_output([str(signer.with_name('aapt')),'dump','badging',str(path)], text=True)
    assert f"package: name='{APP_ID}'" in badging
    assert f"application-label:'{LABEL}'" in badging
    (out/(label+'-signature.txt')).write_text(signature)
    (out/(label+'-badging.txt')).write_text(badging)
    result = dict(source=SOURCE,applicationId=APP_ID,label=LABEL,flags=FLAGS,
        bytes=path.stat().st_size,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),signatureVerified=True)
    (out/(label+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2))
    return result

phase = sys.argv[1]
if phase == 'prepare':
    assert subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip() == SOURCE
    assert not subprocess.check_output(['git','diff','--','app/app/src/main'],text=True).strip()
    strings = root/'app/app/src/main/res/values/strings.xml'
    original = strings.read_text()
    projected,count = re.subn(r'(<string name="app_name">)[^<]*(</string>)',lambda m:m[1]+LABEL+m[2],original)
    assert count == 1
    strings.write_text(projected)
    (out/'display-name-only-projection.patch').write_bytes(subprocess.check_output(['git','diff']))
    (out/'source.json').write_text(json.dumps(dict(source=SOURCE,
        previousCombined='eeb4f1253fda98444e51e2ae73ebaced0c762b95',focus='059cc8efb74810174529fbbb5faa415c5a754d67',action='c135ca3af42c06a505f24f292893e8818ab18912',
        cardDraftFix='b370d46d3d766ad11fb5a0d6b1c37cab97a64637',pr7='efa6ffbb20cfc52096f0f5df03b41c419fc14e4a',
        flags=FLAGS,applicationId=APP_ID,label=LABEL,syntheticOnly=True,realAccountCalls=False,humanAcceptance=False)))
    path = root/'app/gradlew'
    path.chmod(path.stat().st_mode | 0o111)
elif phase == 'full-jvm-and-package':
    result = gradle(':app:testDebugUnitTest',':app:compileDebugAndroidTestKotlin',':app:assembleDebug')
    cases = report('full-jvm')
    assert result.returncode == 0 and cases and not any(c['failure'] for c in cases)
    package('apk-before-native')
elif phase in ('native-api30','native-api26'):
    names = ','.join('com.example.chatbar.'+name for name in [
        'ui.character.CharacterDraftSaveLifecycleTest','ui.character.CharacterDraftDiscardTest',
        'ui.manage.ManagementDraftDiscardTest','ui.character.CharacterCardFieldsPersistenceTest',
        'ui.chat.ReplyAlternativeVoiceCleanupRuntimeTest','data.repository.MomentImageCommitRuntimeTest',
        'ui.chat.FormatRepairRetryRuntimeTest','ui.chat.ReplyMediaVersionControlsTest','ui.moments.MomentPostRowLayoutTest','ui.chat.ChatHistoryActionWireTest'])
    result = gradle(':app:connectedDebugAndroidTest','-Pandroid.testInstrumentationRunnerArguments.class='+names)
    cases = report(phase,True)
    assert result.returncode == 0 and len(cases) == 65
    assert all(not c['failure'] and not c['skipped'] for c in cases)
    assert len([c for c in cases if c['classname'].endswith('.CharacterDraftSaveLifecycleTest')]) == 6
    assert any(c['name'] == 'focusAndAdvanceKeepHistoryWrappingInTheRequestButNeverPersistTheEcho' for c in cases)
    assert any(c['name'] == 'declaredAndLegacyFencesAreRemovedFromTheRequestButNestedExamplesStay' for c in cases)
    after = package('apk-after-native')
    before = out/'apk-before-native.json'
    if before.exists():
        assert json.loads(before.read_text()) == after
        shutil.copy2(root/'app/app/build/outputs/apk/debug/app-debug.apk',root/'CCB-consolidated-review-r6.apk')
        (out/'delivery.json').write_text(json.dumps(dict(apk='CCB-consolidated-review-r6.apk',metadata=after,
            testedSameBytesOnApi30=True,humanAcceptance=False,publishedRelease=False),ensure_ascii=False,indent=2))
else:
    raise ValueError(phase)
