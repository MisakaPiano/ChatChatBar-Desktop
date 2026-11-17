import pathlib,subprocess,sys,json,shutil,hashlib,os,re,zipfile,tarfile
from xml.etree import ElementTree as ET
root=pathlib.Path.cwd();out=root/'card-fields-pr-native';out.mkdir(exist_ok=True)
PR='bd7fc1225d8a86cc9feeba35babc38dc827ed785'
COMBINED='ba6f2dc9131dd0e13b3b8c630f7943d0ddec99a0'
APP_ID='com.example.chatbar.cardfields.review.n1'
CLASSES=','.join(['com.example.chatbar.ui.character.CharacterCardFieldControlsTest',
 'com.example.chatbar.ui.character.CharacterCardFieldsPersistenceTest',
 'com.example.chatbar.ui.chat.SaveSlotActionsTest',
 'com.example.chatbar.ui.character.CharacterNovelAiPromptFieldTest',
 'com.example.chatbar.ui.kit.CbInputStateTest'])
def gradle(*args):return subprocess.run(['./gradlew',*args,'--no-daemon','--stacktrace'],cwd=root/'app')
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

check_source(PR)
p=root/'app/app/build.gradle.kts';s=p.read_text();assert s.count('applicationId = "com.example.chatbar"')==1
p.write_text(s.replace('applicationId = "com.example.chatbar"','applicationId = "'+APP_ID+'"'))
p=root/'app/app/src/main/res/values/strings.xml';s=p.read_text();p.write_text(re.sub(r'(<string name="app_name">)[^<]*(</string>)',r'\g<1>CCB card fields internal\2',s,count=1))
(out/'package-projection.patch').write_bytes(subprocess.check_output(['git','diff']))
classes='com.example.chatbar.ui.character.CharacterCardFieldControlsTest,com.example.chatbar.ui.character.CharacterCardFieldsPersistenceTest'
result=gradle(':app:connectedDebugAndroidTest','-Pandroid.testInstrumentationRunnerArguments.class='+classes,
 '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true')
cases=report('pr-native-api30',True)
assert result.returncode==0 and len(cases)==7 and all(not c['failure'] and not c['skipped'] for c in cases)
collect('card-fields-review','pr-native-api30')
metadata('pr-native-apk',PR,APP_ID)
(out/'source.json').write_text(json.dumps(dict(source=PR,syntheticOnly=True,realAccountCalls=False,apkForDelivery=False,
 scope='Seven new methods on exact upstream PR head in isolated internal app; no consolidation code')))
