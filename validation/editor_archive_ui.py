import pathlib,subprocess,json,shutil,sys,hashlib,os
from xml.etree import ElementTree as ET
root=pathlib.Path.cwd();out=root/'editor-archive-ui';out.mkdir(exist_ok=True)
SOURCE='2d4a899c02dec280c637cbfab5f4c9c8835cf053'
REVIEW_ID='com.example.chatbar.uireview.n1'

def gradle(*args):
    subprocess.run(['./gradlew',*args,'--no-daemon','--stacktrace'],cwd=root/'app',check=True)

def report(label,native=False):
    src=root/('app/app/build/outputs/androidTest-results/connected' if native else 'app/app/build/test-results/testDebugUnitTest')
    dst=out/label;shutil.copytree(src,dst);cases=[]
    for p in dst.rglob('TEST-*.xml'):
        for c in ET.parse(p).iter('testcase'):
            err=c.find('failure');err=err if err is not None else c.find('error')
            cases.append(dict(name=c.get('name'),classname=c.get('classname'),skipped=c.find('skipped') is not None,
                failure=None if err is None else dict(type=err.get('type'),message=err.get('message'),text=err.text)))
    (out/(label+'.json')).write_text(json.dumps(cases,ensure_ascii=False,indent=2))
    assert cases and all(not c['failure'] and not c['skipped'] for c in cases)
    return cases

def metadata(label,app_id):
    src=root/'app/app/build/outputs/apk/debug/app-debug.apk'
    signer=sorted(pathlib.Path(os.environ['ANDROID_HOME']).glob('build-tools/*/apksigner'))[-1]
    (out/(label+'-signature.txt')).write_text(subprocess.check_output([str(signer),'verify','--verbose','--print-certs',str(src)],text=True))
    badging=subprocess.check_output([str(signer.with_name('aapt')),'dump','badging',str(src)],text=True)
    assert "package: name='"+app_id+"'" in badging
    (out/(label+'-badging.txt')).write_text(badging)
    (out/(label+'.json')).write_text(json.dumps(dict(bytes=src.stat().st_size,sha256=hashlib.sha256(src.read_bytes()).hexdigest(),
        applicationId=app_id,signatureVerified=True,source=SOURCE,humanAcceptance=False)))

phase=sys.argv[1]
if phase=='prepare':
    assert subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()==SOURCE
    assert 'applicationId = "com.example.chatbar"' in (root/'app/app/build.gradle.kts').read_text()
    (root/'app/gradlew').chmod(0o755)
    (out/'source.json').write_text(json.dumps(dict(source=SOURCE,standardPackage='com.example.chatbar',syntheticOnly=True,realAccountCalls=False)))
elif phase=='jvm':
    args=[':app:testDebugUnitTest']
    for name in ['NovelAiPromptTranslationTest','NovelAiTagCompletionTest','NovelAiTagSuggestionServiceTest','SaveSlotSerializationTest']:
        args.extend(['--tests','*'+name])
    gradle(*args,':app:compileDebugAndroidTestKotlin',':app:assembleDebug')
    report('jvm');metadata('standard-apk','com.example.chatbar')
elif phase.startswith('native-'):
    classes='com.example.chatbar.ui.chat.SaveSlotActionsTest,com.example.chatbar.ui.character.CharacterNovelAiPromptFieldTest,com.example.chatbar.ui.kit.CbInputStateTest'
    gradle(':app:connectedDebugAndroidTest','-Pandroid.testInstrumentationRunnerArguments.class='+classes)
    cases=report(phase,True);assert len(cases)==14
    subprocess.run(['adb','pull','/sdcard/Android/data/com.example.chatbar/files/ui-previews',str(out/(phase+'-screenshots'))],check=True)
elif phase=='prepare-review-apk':
    p=root/'app/app/build.gradle.kts';s=p.read_text();assert s.count('applicationId = "com.example.chatbar"')==1
    p.write_text(s.replace('applicationId = "com.example.chatbar"','applicationId = "'+REVIEW_ID+'"'))
    p=root/'app/app/src/main/res/values/strings.xml';s=p.read_text();assert '<string name="app_name">' in s
    import re
    p.write_text(re.sub(r'(<string name="app_name">)[^<]*(</string>)',r'\g<1>CCB 界面测试 n1\2',s,count=1))
    (out/'review-apk-projection.patch').write_bytes(subprocess.check_output(['git','diff']))
    gradle(':app:assembleDebug',':app:compileDebugAndroidTestKotlin')
    metadata('review-apk',REVIEW_ID)
elif phase=='review-smoke':
    classes='com.example.chatbar.ui.chat.SaveSlotActionsTest#exportNeverLoadsAndOnlyExplicitConfirmationLoadsOnce,com.example.chatbar.ui.character.CharacterNovelAiPromptFieldTest#styleAndNegativeFieldsResolveTranslationsWithoutWritingChineseOrChangingWeights'
    gradle(':app:connectedDebugAndroidTest','-Pandroid.testInstrumentationRunnerArguments.class='+classes)
    assert len(report(phase,True))==2
    target=root/'review-apk/CCB-ui-review-n1.apk';target.parent.mkdir(exist_ok=True)
    shutil.copy2(root/'app/app/build/outputs/apk/debug/app-debug.apk',target)
else:raise ValueError(phase)
