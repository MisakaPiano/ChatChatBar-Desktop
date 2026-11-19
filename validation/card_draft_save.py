import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from xml.etree import ElementTree as ET

root = Path.cwd()
out = root / 'card-draft-save-originals'
out.mkdir(exist_ok=True)
SOURCE = '1ecc21f9220d1d6903143be3ffdac837bb785a81'
BASE = 'b370d46d3d766ad11fb5a0d6b1c37cab97a64637'
VM = 'app/app/src/main/java/com/example/chatbar/ui/character/CharacterEditViewModel.kt'
PREFIX = 'com.example.chatbar.'
NEW = PREFIX + 'ui.character.CharacterDraftSaveLifecycleTest'

def gradle(*args, isolated=False):
    flags = ['-PCHATBAR_ISOLATED_ANDROID_TEST=true'] if isolated else []
    return subprocess.run(['./gradlew', *args, *flags, '--no-daemon', '--stacktrace', '--info', '--console=plain'], cwd=root/'app')

def report(label, native=False):
    src = root / ('app/app/build/outputs/androidTest-results/connected' if native else 'app/app/build/test-results/testDebugUnitTest')
    dst = out / label
    shutil.copytree(src, dst)
    cases = []
    for path in dst.rglob('TEST-*.xml'):
        for case in ET.parse(path).iter('testcase'):
            fail = case.find('failure')
            if fail is None:
                fail = case.find('error')
            cases.append(dict(name=case.get('name'), classname=case.get('classname'),
                skipped=case.find('skipped') is not None,
                failure=None if fail is None else dict(type=fail.get('type') or (fail.text or '').split(':', 1)[0], text=fail.text)))
    (out / f'{label}.json').write_text(json.dumps(cases, ensure_ascii=False, indent=2))
    return cases

def metadata(isolated):
    apk = root/'app/app/build/outputs/apk/debug/app-debug.apk'
    signer = sorted(Path(os.environ['ANDROID_HOME']).glob('build-tools/*/apksigner'))[-1]
    signature = subprocess.check_output([str(signer), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    badging = subprocess.check_output([str(signer.with_name('aapt')), 'dump', 'badging', str(apk)], text=True)
    expected = 'com.example.chatbar.instrumented' if isolated else 'com.example.chatbar'
    assert f"package: name='{expected}'" in badging
    (out/'apk-signature.txt').write_text(signature)
    (out/'apk-badging.txt').write_text(badging)
    (out/'apk.json').write_text(json.dumps(dict(source=SOURCE, applicationId=expected,
        isolatedFlag=isolated, bytes=apk.stat().st_size, sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
        signatureVerified=True, notUploaded=True, humanAcceptance=False)))

phase = sys.argv[1]
if phase == 'prepare':
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip() == SOURCE
    assert 'CHATBAR_ISOLATED_ANDROID_TEST' in (root/'app/app/build.gradle.kts').read_text()
    (out/'source.json').write_text(json.dumps(dict(source=SOURCE, baseline=BASE,
        phase=os.environ['DRAFT_CHECK_PHASE'], syntheticOnly=True, realAccountCalls=False)))
    wrapper = root/'app/gradlew'
    wrapper.chmod(wrapper.stat().st_mode | 0o111)
elif phase == 'jvm':
    result = gradle(':app:testDebugUnitTest', ':app:compileDebugAndroidTestKotlin', ':app:assembleDebug')
    cases = report('full-jvm')
    assert result.returncode == 0 and cases and not any(c['failure'] for c in cases)
    # Existing opt-in assumptions remain explicit in the untouched raw reports.
    metadata(False)
elif phase == 'negative-api30':
    path = root/VM
    fixed = path.read_bytes()
    old = subprocess.check_output(['git', 'show', BASE+':'+VM])
    try:
        path.write_bytes(old)
        (out/'old-vm-overlay.patch').write_bytes(subprocess.check_output(['git', 'diff']))
        (out/'old-vm-sha256.txt').write_text(hashlib.sha256(old).hexdigest())
        names = NEW+'#croppedAvatarInUnconfirmedCharacterDialogSurvivesDraftRestore'
        result = gradle(':app:connectedDebugAndroidTest', '-Pandroid.testInstrumentationRunnerArguments.class='+names, isolated=True)
        cases = report(phase, True)
        assert result.returncode != 0 and len(cases) == 1
        failure = cases[0]['failure']
        assert failure and 'TimeoutCancellationException' in failure['type'], cases
        assert '8000 ms' in failure['text'], cases
    finally:
        path.write_bytes(fixed)
        assert path.read_bytes() == fixed
elif phase in ('fixed-api26', 'fixed-api30'):
    names = ','.join([NEW, PREFIX+'ui.character.CharacterDraftDiscardTest',
        PREFIX+'ui.manage.ManagementDraftDiscardTest', PREFIX+'ui.character.CharacterCardFieldsPersistenceTest'])
    result = gradle(':app:connectedDebugAndroidTest', '-Pandroid.testInstrumentationRunnerArguments.class='+names, isolated=True)
    cases = report(phase, True)
    assert result.returncode == 0 and len(cases) == 21
    assert all(not c['failure'] and not c['skipped'] for c in cases), cases
    assert len([c for c in cases if c['classname']==NEW]) == 7
    metadata(True)
else:
    raise ValueError(phase)
