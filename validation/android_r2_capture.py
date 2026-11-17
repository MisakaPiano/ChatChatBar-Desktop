import pathlib,subprocess,json,shutil,os,hashlib,re
from xml.etree import ElementTree as ET
root=pathlib.Path.cwd();out=root/'android-r2-capture';out.mkdir(exist_ok=True)
source='9a796f321c748a943e0b0ed0eda1f024a74593da';app_id='com.example.chatbar.nextreview.r2'
assert subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()==source
(root/'app/gradlew').chmod(0o755)
p=root/'app/app/build.gradle.kts';s=p.read_text();assert s.count('applicationId = "com.example.chatbar"')==1
p.write_text(s.replace('applicationId = "com.example.chatbar"','applicationId = "'+app_id+'"'))
p=root/'app/app/src/main/res/values/strings.xml';s=p.read_text();p.write_text(re.sub(r'(<string name="app_name">)[^<]*(</string>)',r'\g<1>CCB 集中验收 r2\2',s,count=1))
(out/'package-projection.patch').write_bytes(subprocess.check_output(['git','diff']))
classes='com.example.chatbar.ui.chat.SaveSlotActionsTest#cancelLoadDoesNotReplaceConversation,com.example.chatbar.ui.chat.SaveSlotActionsTest#narrowLargeFontShowsAllThreeCaptionsWithIndependentTouchTargets,com.example.chatbar.ui.character.CharacterNovelAiPromptFieldTest#styleAndNegativeFieldsResolveTranslationsWithoutWritingChineseOrChangingWeights'
# The pinned AGP9 option retains the debug app and external files long enough to pull screenshots.
r=subprocess.run(['./gradlew',':app:connectedDebugAndroidTest','-Pandroid.testInstrumentationRunnerArguments.class='+classes,
    '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true','--no-daemon','--stacktrace'],cwd=root/'app')
reports=root/'app/app/build/outputs/androidTest-results/connected'
if reports.exists():shutil.copytree(reports,out/'native')
cases=[]
for p in (out/'native').rglob('TEST-*.xml'):
    for c in ET.parse(p).iter('testcase'):
        e=c.find('failure');e=e if e is not None else c.find('error')
        cases.append(dict(name=c.get('name'),classname=c.get('classname'),skipped=c.find('skipped') is not None,
            failure=None if e is None else dict(type=e.get('type'),text=e.text)))
(out/'cases.json').write_text(json.dumps(cases,ensure_ascii=False,indent=2))
assert r.returncode==0 and len(cases)==3 and all(not c['failure'] and not c['skipped'] for c in cases)
installed=subprocess.check_output(['adb','shell','pm','path',app_id],text=True)
assert 'package:' in installed,installed
subprocess.run(['adb','pull','/sdcard/Android/data/'+app_id+'/files/ui-previews',str(out/'screenshots')],check=True)
png=list((out/'screenshots').rglob('*.png'));projections=list((out/'screenshots').rglob('tag-input-projection.json'))
assert len(png)==2 and len(projections)==1
projection=json.loads(projections[0].read_text());assert any(', ' in x for x in projection['input'])
(out/'source.json').write_text(json.dumps(dict(source=source,applicationId=app_id,scope='Three existing tests repeated only for screenshot collection; not additional distinct coverage',
    syntheticOnly=True,realAccountCalls=False,apkForDelivery=False,retainedApksOnlyInEphemeralCloudEmulator=True,
    files={str(p.relative_to(out)):dict(bytes=p.stat().st_size,sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in png+projections}),ensure_ascii=False,indent=2))
