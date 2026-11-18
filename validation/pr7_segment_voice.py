import pathlib,subprocess,json,shutil,sys,hashlib,os
from xml.etree import ElementTree as ET
root=pathlib.Path.cwd();out=root/'pr7-segment-voice';out.mkdir(exist_ok=True)
SOURCE='efa6ffbb20cfc52096f0f5df03b41c419fc14e4a'
BASE='3132f4658eb33cea3eee02ca432f1fbc526086ae'
REPO='app/app/src/main/java/com/example/chatbar/data/repository/MomentRepository.kt'
VM='app/app/src/main/java/com/example/chatbar/ui/chat/ChatViewModel.kt'
def gradle(*args):return subprocess.run(['./gradlew',*args,'--no-daemon','--stacktrace'],cwd=root/'app')
def report(label,native=False):
    src=root/('app/app/build/outputs/androidTest-results/connected' if native else 'app/app/build/test-results/testDebugUnitTest')
    dst=out/label;shutil.copytree(src,dst);cases=[]
    for p in dst.rglob('TEST-*.xml'):
        for c in ET.parse(p).iter('testcase'):
            err=c.find('failure');err=err if err is not None else c.find('error')
            cases.append(dict(name=c.get('name'),classname=c.get('classname'),skipped=c.find('skipped') is not None,failure=None if err is None else dict(type=err.get('type') or (err.text or '').strip().split(':',1)[0],message=err.get('message'),text=err.text)))
    (out/(label+'.json')).write_text(json.dumps(cases,ensure_ascii=False,indent=2));return cases
def overlay(files,label,command):
    saved={n:(root/n).read_bytes() for n in files}
    try:
        for n in files:(root/n).write_bytes(subprocess.check_output(['git','show',BASE+':'+n]))
        (out/(label+'-overlay.patch')).write_bytes(subprocess.check_output(['git','diff']))
        return command()
    finally:
        for n,b in saved.items():
            (root/n).write_bytes(b);assert hashlib.sha256((root/n).read_bytes()).digest()==hashlib.sha256(b).digest()
phase=sys.argv[1]
if phase=='prepare':
    source=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip();assert source==SOURCE
    assert 'applicationId = "com.example.chatbar"' in (root/'app/app/build.gradle.kts').read_text()
    (out/'source.json').write_text(json.dumps(dict(source=source,baseline=BASE,standardPackage=True,syntheticOnly=True,realAccountCalls=False)))
    p=root/'app/gradlew';p.chmod(p.stat().st_mode|0o111)
elif phase=='fixed-jvm':
    classes=['BackupMergeEngineTest','BackupMessageVersionIdentityTest','BackupJsonStreamTest','BackupExportSelectionTest','MessageAlternativeVersionPolicyTest','MessageFormatRepairPolicyTest','MomentRepositoryTest','MomentImagePathPolicyTest','ReplyMediaVersionRetentionTest','RoleplayContentSegmentsTest']
    args=[':app:testDebugUnitTest']
    for name in classes:args.extend(['--tests','*'+name])
    args+=[':app:compileDebugAndroidTestKotlin',':app:assembleDebug']
    r=gradle(*args);cases=report('fixed-jvm');assert r.returncode==0 and cases and all(not c['failure'] and not c['skipped'] for c in cases)
elif phase=='baseline-native':
    def run():
        names='com.example.chatbar.ui.chat.ReplyAlternativeVoiceCleanupRuntimeTest#clearingLastTextSegmentCleansRemovedVersionVoiceAndAnchorFromBackup'
        r=gradle(':app:connectedDebugAndroidTest','-Pandroid.testInstrumentationRunnerArguments.class='+names)
        cases=report('baseline-native',True);failed=[c for c in cases if c['failure']]
        assert r.returncode!=0 and len(cases)==1 and len(failed)==1
        assert all(c['failure']['type']=='java.lang.AssertionError' for c in failed),failed
        assert any('Deleted alternative must not retain its dedicated voice' in c['failure']['text'] for c in failed),failed
    overlay([VM],'baseline-native',run)
elif phase.startswith('fixed-native'):
    names='com.example.chatbar.ui.chat.ReplyAlternativeVoiceCleanupRuntimeTest,com.example.chatbar.data.repository.MomentImageCommitRuntimeTest,com.example.chatbar.ui.chat.FormatRepairRetryRuntimeTest,com.example.chatbar.ui.chat.ReplyMediaVersionControlsTest,com.example.chatbar.ui.moments.MomentPostRowLayoutTest'
    r=gradle(':app:connectedDebugAndroidTest','-Pandroid.testInstrumentationRunnerArguments.class='+names)
    cases=report(phase,True);assert r.returncode==0 and len(cases)==32 and all(not c['failure'] and not c['skipped'] for c in cases)
elif phase=='package-metadata':
    src=root/'app/app/build/outputs/apk/debug/app-debug.apk'
    tools=sorted(pathlib.Path(os.environ['ANDROID_HOME']).glob('build-tools/*/apksigner'))[-1]
    (out/'apk-signature.txt').write_text(subprocess.check_output([str(tools),'verify','--verbose','--print-certs',str(src)],text=True))
    badging=subprocess.check_output([str(tools.with_name('aapt')),'dump','badging',str(src)],text=True)
    assert "package: name='com.example.chatbar'" in badging
    (out/'apk-badging.txt').write_text(badging)
    (out/'apk.json').write_text(json.dumps(dict(bytes=src.stat().st_size,sha256=hashlib.sha256(src.read_bytes()).hexdigest(),applicationId='com.example.chatbar',signatureVerified=True,notUploaded=True,notHumanAcceptance=True)))
else:raise ValueError(phase)
