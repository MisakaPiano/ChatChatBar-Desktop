import pathlib,subprocess,json,shutil,sys,hashlib
from xml.etree import ElementTree as ET
root=pathlib.Path.cwd();out=root/'pr7-independent';out.mkdir(exist_ok=True)
BASE='46cc17fe9450a61592e85a2f734c4e5e59930b7d'
VM='app/app/src/main/java/com/example/chatbar/ui/chat/ChatViewModel.kt'
def gradle(*args):
    return subprocess.run(['./gradlew',*args,'--no-daemon','--stacktrace'],cwd=root/'app')
def report(label,native=False):
    src=root/('app/app/build/outputs/androidTest-results/connected' if native else 'app/app/build/test-results/testDebugUnitTest')
    dst=out/label
    shutil.copytree(src,dst)
    cases=[]
    for p in dst.rglob('TEST-*.xml'):
        for c in ET.parse(p).iter('testcase'):
            err=c.find('failure');err=err if err is not None else c.find('error')
            cases.append(dict(name=c.get('name'),classname=c.get('classname'),failure=None if err is None else dict(type=err.get('type'),message=err.get('message'),text=err.text)))
    (out/(label+'.json')).write_text(json.dumps(cases,ensure_ascii=False,indent=2))
    return cases
def overlay(files,label,command):
    saved={n:(root/n).read_bytes() for n in files}
    try:
        for n in files:(root/n).write_bytes(subprocess.check_output(['git','show',BASE+':'+n]))
        (out/(label+'-overlay.patch')).write_bytes(subprocess.check_output(['git','diff']))
        return command()
    finally:
        for n,b in saved.items():(root/n).write_bytes(b)
phase=sys.argv[1]
if phase=='prepare':
    source=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    (out/'source.json').write_text(json.dumps(dict(source=source,baseline=BASE,syntheticOnly=True,realAccountCalls=False)))
    p=root/'app/app/build.gradle.kts';s=p.read_text();assert 'applicationId = "com.example.chatbar"' in s;p.write_text(s.replace('applicationId = "com.example.chatbar"','applicationId = "com.example.chatbar.pr7independent.r1"'))
    (out/'isolated-identity.patch').write_bytes(subprocess.check_output(['git','diff']))
    p=root/'app/gradlew';p.chmod(p.stat().st_mode|0o111)
elif phase=='baseline-jvm':
    def run():
        r=gradle(':app:testDebugUnitTest','--tests','*BackupMergeEngineTest.copied*RepairIdentity*')
        cases=report('baseline-jvm')
        assert r.returncode!=0 and len(cases)==3 and all(c['failure'] and c['failure']['type'] in ['org.junit.ComparisonFailure','java.lang.AssertionError'] for c in cases),cases
    overlay(['app/app/src/main/java/com/example/chatbar/domain/backup/BackupMergeEngine.kt','app/app/src/main/java/com/example/chatbar/domain/chat/MessageFormatRepairPolicy.kt'],'baseline-jvm',run)
elif phase=='fixed-jvm':
    r=gradle(':app:testDebugUnitTest','--tests','*BackupMergeEngineTest','--tests','*BackupJsonStreamTest','--tests','*BackupExportSelectionTest','--tests','*MessageAlternativeVersionPolicyTest','--tests','*MessageFormatRepairPolicyTest','--tests','*MomentImageVersionsTest',':app:compileDebugAndroidTestKotlin',':app:assembleDebug')
    cases=report('fixed-jvm'); assert r.returncode==0 and cases and all(not c['failure'] for c in cases)
elif phase=='baseline-native':
    def run():
        classes=','.join('com.example.chatbar.ui.chat.ReplyDeletionRuntimeTest#'+n for n in ['staleSwitchCannotResurrectCandidateDeletedBeforeUiRefresh','staleSwitchCannotOverwriteUndoBeforeUiRefresh','staleSwitchCannotOverwriteRedoBeforeUiRefresh'])+',com.example.chatbar.ui.moments.MomentPostRowLayoutTest'
        r=gradle(':app:connectedDebugAndroidTest','-Pandroid.testInstrumentationRunnerArguments.class='+classes)
        cases=report('baseline-native',True)
        races=[c for c in cases if c['name'].startswith('staleSwitch')]
        assert r.returncode!=0 and len(races)==3 and all(c['failure'] and c['failure']['type']=='java.lang.AssertionError' for c in races),races
    overlay([VM],'baseline-native',run)
elif phase=='fixed-native':
    r=gradle(':app:connectedDebugAndroidTest','-Pandroid.testInstrumentationRunnerArguments.class=com.example.chatbar.ui.chat.ReplyDeletionRuntimeTest,com.example.chatbar.ui.chat.ReplyDeletionConfirmationTest,com.example.chatbar.ui.chat.ReplyMediaVersionControlsTest')
    cases=report('fixed-native',True);assert r.returncode==0 and len(cases)==11 and all(not c['failure'] for c in cases)
elif phase=='package':
    src=root/'app/app/build/outputs/apk/debug/app-debug.apk';shutil.copy2(src,out/'CCB-PR7-independent-internal.apk')
    (out/'apk.json').write_text(json.dumps(dict(bytes=src.stat().st_size,sha256=hashlib.sha256(src.read_bytes()).hexdigest(),applicationId='com.example.chatbar.pr7independent.r1',notHumanAcceptance=True)))
else:raise ValueError(phase)
