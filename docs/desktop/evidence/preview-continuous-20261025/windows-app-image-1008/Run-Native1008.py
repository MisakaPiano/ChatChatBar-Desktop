from pathlib import Path
import os,json,hashlib,zipfile,subprocess,re,shutil,sys
repo=Path('H:/ChatChatBar-Desktop');home=repo/'app/desktopApp/build/preview-continuous-private-20261025'
package=home/'windows-package-merge-candidate-1008'
assert (package/'exit.txt').read_text()=='0'
head=(package/'head.txt').read_text().strip()
assert head=='2f75a4d3d4e5557d7bd513f2e9368a4ed5e8d1f7'
image=repo/'app/desktopApp/build/preview-win-merge-candidate-1008/compose/binaries/main/app/ChatChatBarDesktopSynthetic'
rows=json.loads((package/'PACKAGE_FILES.json').read_text(encoding='utf-8-sig'))
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
assert len(rows)==217
for r in rows:
 p=image/r['path'];assert p.is_relative_to(image) and p.is_file()
 assert p.stat().st_size==r['bytes'] and sha(p)==r['sha256']
jars=sorted((image/'app').glob('*.jar'));assert len(jars)==63
classes=0
for jar in jars:
 with zipfile.ZipFile(jar) as z:
  assert z.testzip() is None
  for name in z.namelist():
   classes+=name.endswith('.class')
   assert not any(x in name for x in ['DesktopWindowsPackageFixture','RealRootRelocationLocal','RealPublishedRootContinuation','CensusWireFixtures','Official147FixtureCodec'])
   assert not name.lower().endswith(('.cbbackup','.cbsave','.cbback','.protected','.ccbsecret'))
   if name.startswith('com/example/chatbar/') or name=='desktop-build.properties':assert not re.search(rb'dl[0-9]{7}',z.read(name))
audit={'source':head,'files':len(rows),'jarClassesInspected':classes,'privateFixturesAndWitnessesAbsent':True,'archiveAndProtectedBlobSuffixesAbsent':True,'passwordPatternAbsentFromOwnEntries':True,'realUserPayloadReadForAudit':False}
(package/'PACKAGE_AUDIT.json').write_text(json.dumps(audit,indent=2)+'\n')
base=home/'windows-runtime-merge-ui-1008';base.mkdir(exist_ok=False)
for n in ['local-appdata','roaming','temp','home']:(base/n).mkdir()
(base/'TASK_OWNER.json').write_text(json.dumps({'kind':'CCB_DESKTOP_SYNTHETIC_WINDOWS_RUNTIME','applicationImage':str(image),'sourceCommit':head,'installerExecuted':False},indent=2)+'\n')
fixture='app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/DesktopWindowsPackageFixture.kt'
assert not subprocess.check_output(['git','diff','85d16a67130a954d30bab81a87c41d35eabdc18f',head,'--',fixture],cwd=repo)
shutil.copyfile(home/'windows-runtime-merge-ui-1007/native-fixture.jar',base/'native-fixture.jar')
shutil.copyfile(repo/fixture,base/'native-fixture-source.kt')
classpath=os.pathsep.join(map(str,[base/'native-fixture.jar',*jars]))
(base/'frozen-classpath.private.txt').write_text(classpath)
(base/'frozen-production.private.json').write_text(json.dumps([{'file':p.name,'bytes':p.stat().st_size,'sha256':sha(p)} for p in jars],indent=2)+'\n')
probe=(home/'windows-runtime-merge-ui-1007/native-app-image-probe.py').read_text()
assert probe.count('0.1.0-preview.1007')==1
(base/'native-app-image-probe.py').write_text(probe.replace('0.1.0-preview.1007','0.1.0-preview.1008'))
env=os.environ.copy();env.update({'LOCALAPPDATA':str(base/'local-appdata'),'APPDATA':str(base/'roaming'),'TEMP':str(base/'temp'),'TMP':str(base/'temp')})
for n in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS']:env.pop(n,None)
java='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin/java.exe'
def fixture_args(mode):return [java,'-Duser.home='+str(base/'home'),'-Djava.io.tmpdir='+str(base/'temp'),'-Dfile.encoding=UTF-8','-cp',classpath,'com.example.chatbar.desktop.DesktopWindowsPackageFixture',mode,str(base)]
steps=[('seed',fixture_args('seed')),('initial-1008',[sys.executable,str(base/'native-app-image-probe.py'),'launch-close','--base',str(base),'--label','initial-1008']),('verify',fixture_args('verify'))]
for label,args in steps:
 (base/(label+'-arguments.private.json')).write_text(json.dumps(args,indent=2)+'\n')
 with (base/(label+'-driver.private.log')).open('wb') as log:r=subprocess.run(args,cwd=repo,env=env,stdout=log,stderr=subprocess.STDOUT)
 (base/(label+'-exit.txt')).write_text(str(r.returncode))
 print('NATIVE1008_'+label.upper()+'_EXIT_'+str(r.returncode),flush=True)
 if r.returncode:sys.exit(r.returncode)
for r in rows:assert sha(image/r['path'])==r['sha256']
print('NATIVE1008_PACKAGE_BYTES_UNCHANGED_PASS',flush=True)
