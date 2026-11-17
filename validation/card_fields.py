import pathlib,subprocess,sys,json,shutil,hashlib,os,re,zipfile,tarfile
from xml.etree import ElementTree as ET
root=pathlib.Path.cwd();out=root/'card-fields-originals';out.mkdir(exist_ok=True)
PR='1799c3f77d026f60f2e25881323425ce2c11c8ec'
COMBINED='f0203776d89c031b6b4b8655fec798f2cc601123'
APP_ID='com.example.chatbar.nextreview.r3'
CLASSES=','.join(['com.example.chatbar.ui.character.CharacterCardFieldControlsTest',
 'com.example.chatbar.ui.character.CharacterCardFieldsPersistenceTest',
 'com.example.chatbar.ui.chat.SaveSlotActionsTest',
 'com.example.chatbar.ui.character.CharacterNovelAiPromptFieldTest',
 'com.example.chatbar.ui.kit.CbInputStateTest'])
ISOLATED_FLAGS=[] if sys.argv[1]=='pr' else ['-PCHATBAR_ISOLATED_ANDROID_TEST=true','-PCHATBAR_ISOLATED_ANDROID_TEST_APP_ID='+APP_ID]
def gradle(*args):return subprocess.run(['./gradlew',*args,*ISOLATED_FLAGS,'--no-daemon','--stacktrace'],cwd=root/'app')
def check_source(expected):
 assert subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()==expected
 subprocess.run(['git','config','core.filemode','false'],check=True)
 (root/'app/gradlew').chmod(0o755)
def report(label,native=False):
 src=root/('app/app/build/outputs/androidTest-results/connected' if native else 'app/app/build/test-results/testDebugUnitTest')
 dst=out/label
 if src.exists():shutil.copytree(src,dst)
 cases=[]
 for p in dst.rglob('TEST-*.xml'):
  for c in ET.parse(p).iter('testcase'):
   f=c.find('failure');f=f if f is not None else c.find('error')
   cases.append(dict(classname=c.get('classname'),name=c.get('name'),skipped=c.find('skipped') is not None,
    failure=None if f is None else dict(type=f.get('type'),text=f.text)))
 (out/(label+'.json')).write_text(json.dumps(cases,ensure_ascii=False,indent=2))
 return cases
def metadata(label,source,app_id):
 apk=root/'app/app/build/outputs/apk/debug/app-debug.apk'
 signer=sorted(pathlib.Path(os.environ['ANDROID_HOME']).glob('build-tools/*/apksigner'))[-1]
 (out/(label+'-signature.txt')).write_text(subprocess.check_output([str(signer),'verify','--verbose','--print-certs',str(apk)],text=True))
 badging=subprocess.check_output([str(signer.with_name('aapt')),'dump','badging',str(apk)],text=True)
 assert "package: name='"+app_id+"'" in badging
 (out/(label+'-badging.txt')).write_text(badging)
 with zipfile.ZipFile(apk) as z:assert all(b'SafeBatchOfflineActivity' not in z.read(n) for n in z.namelist() if n.endswith('.dex'))
 d=dict(source=source,applicationId=app_id,bytes=apk.stat().st_size,sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),signatureVerified=True)
 (out/(label+'.json')).write_text(json.dumps(d,indent=2));return d
def collect(directory,label):
 archive=out/(label+'-'+directory+'.tar')
 with archive.open('wb') as output:
  subprocess.run(['adb','exec-out','run-as',APP_ID,'tar','-cf','-','-C','files',directory],stdout=output,check=True)
 with tarfile.open(archive) as packed:
  assert all(pathlib.PurePosixPath(m.name).parts[0]==directory for m in packed.getmembers())
  packed.extractall(out/(label+'-screenshots'),filter='data')
phase=sys.argv[1]
if phase=='pr':
 check_source(PR)
 (out/'source.json').write_text(json.dumps(dict(prSource=PR,combinedSource=COMBINED,baseline='41f6e0ccad59d5ae00018a11043f9b03efa60249',
  combinedBaseline='fcc475d17e3b229e260021a9093ac7aed9c80646',syntheticOnly=True,realAccountCalls=False,humanAcceptance=False)))
 result=gradle(':app:testDebugUnitTest',':app:compileDebugAndroidTestKotlin',':app:assembleDebug')
 cases=report('pr-jvm')
 allowed={('com.example.chatbar.domain.backup.BackupLargeStreamTest','encryptedGiBRoundTripPreservesRawBytesWithConstantBuffers')}
 assert result.returncode==0 and len(cases)>1000 and not any(c['failure'] for c in cases)
 assert {(c['classname'],c['name']) for c in cases if c['skipped']}<=allowed
 metadata('pr-standard-apk',PR,'com.example.chatbar')
elif phase=='combined':
 subprocess.run(['git','fetch','origin',COMBINED,'--depth=1'],check=True)
 subprocess.run(['git','checkout','--detach',COMBINED],check=True);check_source(COMBINED)
 p=root/'app/app/src/main/res/values/strings.xml';s=p.read_text();p.write_text(re.sub(r'(<string name="app_name">)[^<]*(</string>)',r'\g<1>CCB 集中验收 r3\2',s,count=1))
 (out/'review-package-projection.patch').write_bytes(subprocess.check_output(['git','diff']))
 assert set(subprocess.check_output(['git','diff','--name-only'],text=True).splitlines())=={'app/app/src/main/res/values/strings.xml'}
 result=gradle(':app:testDebugUnitTest','--tests','com.example.chatbar.domain.card.CharacterGreetingPolicyTest',
  '--tests','com.example.chatbar.domain.chat.CharacterSessionServiceTest','--tests','com.example.chatbar.domain.chat.PromptAssemblerCharacterModeTest',
  ':app:compileDebugAndroidTestKotlin',':app:assembleDebug')
 cases=report('combined-jvm')
 assert result.returncode==0 and cases and all(not c['failure'] and not c['skipped'] for c in cases)
 metadata('review-apk-before-native',COMBINED,APP_ID)
elif phase.startswith('native-'):
 result=gradle(':app:connectedDebugAndroidTest','-Pandroid.testInstrumentationRunnerArguments.class='+CLASSES,
  '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true')
 cases=report(phase,True)
 assert result.returncode==0 and len(cases)==21 and len({c['classname'] for c in cases})==5
 assert all(not c['failure'] and not c['skipped'] for c in cases)
 collect('ui-previews',phase)
 if phase=='native-api30':collect('card-fields-review',phase)
elif phase=='package':
 d=metadata('review-apk',COMBINED,APP_ID)
 assert d==json.loads((out/'review-apk-before-native.json').read_text())
 target=root/'review-apk/CCB-consolidated-review-r3.apk';target.parent.mkdir(exist_ok=True)
 shutil.copy2(root/'app/app/build/outputs/apk/debug/app-debug.apk',target)
 (out/'delivery.json').write_text(json.dumps(dict(apk=target.name,metadata=d,humanAcceptance=False,publishedRelease=False)))
else:raise ValueError(phase)
