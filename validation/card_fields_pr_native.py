import pathlib,subprocess,sys,json,shutil,hashlib,os,re,zipfile,tarfile
from xml.etree import ElementTree as ET
root=pathlib.Path.cwd();out=root/'card-fields-pr-native';out.mkdir(exist_ok=True)
PR='1799c3f77d026f60f2e25881323425ce2c11c8ec'
APP_ID='com.example.chatbar.instrumented'
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
# Default upstream build must report explicit assumptions, with no repository fixture writes.
result=gradle(':app:connectedDebugAndroidTest',
 '-Pandroid.testInstrumentationRunnerArguments.class=com.example.chatbar.ui.character.CharacterCardFieldsPersistenceTest',
 '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true')
default_cases=report('pr-native-default',True)
assert result.returncode==0 and len(default_cases)==3 and all(c['skipped'] and not c['failure'] for c in default_cases)
metadata('pr-default-apk',PR,'com.example.chatbar')
rejections={}
for name,flags in [('formal-id',['-PCHATBAR_ISOLATED_ANDROID_TEST=true','-PCHATBAR_ISOLATED_ANDROID_TEST_APP_ID=com.example.chatbar']),
 ('orphan-id',['-PCHATBAR_ISOLATED_ANDROID_TEST_APP_ID=com.example.chatbar.instrumented'])]:
 r=subprocess.run(['./gradlew',':app:help',*flags,'--no-daemon'],cwd=root/'app',capture_output=True,text=True)
 (out/(name+'-rejection.txt')).write_text(r.stdout+'\n'+r.stderr)
 assert r.returncode!=0 and ('non-production' in r.stdout+r.stderr or 'requires CHATBAR_ISOLATED' in r.stdout+r.stderr)
 rejections[name]=dict(returncode=r.returncode,rejected=True)
(out/'configuration-checks.json').write_text(json.dumps(rejections))
# The supported Gradle property, not a source patch, enables the real behavioral fixtures.
assert subprocess.check_output(['git','diff','--name-only'],text=True).strip()==''
(out/'package-projection.patch').write_text('')
classes='com.example.chatbar.ui.character.CharacterCardFieldControlsTest,com.example.chatbar.ui.character.CharacterCardFieldsPersistenceTest'
result=gradle(':app:connectedDebugAndroidTest','-PCHATBAR_ISOLATED_ANDROID_TEST=true','-Pandroid.testInstrumentationRunnerArguments.class='+classes,
 '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true')
cases=report('pr-native-api30',True)
assert result.returncode==0 and len(cases)==7 and all(not c['failure'] and not c['skipped'] for c in cases)
collect('card-fields-review','pr-native-api30')
metadata('pr-native-apk',PR,APP_ID)
(out/'source.json').write_text(json.dumps(dict(source=PR,syntheticOnly=True,realAccountCalls=False,apkForDelivery=False,
 scope='Default build: 3 intentional assumptions, no behavioral pass. Supported isolated Gradle switch: seven real methods, no source patch or consolidation code')))
