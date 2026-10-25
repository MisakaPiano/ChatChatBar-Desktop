"""Test corrected packaged startup against the exact retained failing synthetic profile."""
from pathlib import Path
import hashlib, json, os, re, subprocess, sys, zipfile

repo=Path('H:/ChatChatBar-Desktop')
home=repo/'app/desktopApp/build/preview-continuous-private-20261025'
package=home/'windows-package-startup-fix-1009'
assert (package/'exit.txt').read_text()=='0'
head=(package/'head.txt').read_text().strip()
assert head=='7b3e1534422dd6120e1e23304b679e03da884c55'
profile=home/'windows-runtime-merge-ui-1008'
owner=json.loads((profile/'TASK_OWNER.json').read_text())
assert owner['kind']=='CCB_DESKTOP_SYNTHETIC_WINDOWS_RUNTIME'
assert owner['sourceCommit']=='2f75a4d3d4e5557d7bd513f2e9368a4ed5e8d1f7'
image=repo/'app/desktopApp/build/preview-win-startup-fix-1009/compose/binaries/main/app/ChatChatBarDesktopSynthetic'
rows=json.loads((package/'PACKAGE_FILES.json').read_text(encoding='utf-8-sig'))
assert len(rows)==217
def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for part in iter(lambda:stream.read(1024*1024),b''):h.update(part)
    return h.hexdigest()
for row in rows:
    path=image/row['path'];assert path.is_relative_to(image)
    assert path.stat().st_size==row['bytes'] and sha(path)==row['sha256']
jars=sorted((image/'app').glob('*.jar'));assert len(jars)==63
classes=0
for jar in jars:
    with zipfile.ZipFile(jar) as archive:
        assert archive.testzip() is None
        for name in archive.namelist():
            classes+=name.endswith('.class')
            assert not any(word in name for word in ['DesktopWindowsPackageFixture','TraceSyntheticStartup','RealRootRelocationLocal','RealPublishedRootContinuation','CensusWireFixtures','Official147FixtureCodec'])
            assert not name.lower().endswith(('.cbbackup','.cbback','.cbsave','.protected','.ccbsecret'))
            if name.startswith('com/example/chatbar/') or name=='desktop-build.properties':
                assert not re.search(rb'dl[0-9]{7}',archive.read(name))
audit=dict(source=head,files=len(rows),jarClassesInspected=classes,privateFixturesAndWitnessesAbsent=True,
           archiveAndProtectedBlobSuffixesAbsent=True,passwordPatternAbsentFromOwnEntries=True)
(package/'PACKAGE_AUDIT.json').write_text(json.dumps(audit,indent=2)+'\n')
base=home/'windows-runtime-startup-fix-1009';base.mkdir(exist_ok=False)
(base/'TASK_OWNER.json').write_text(json.dumps(dict(kind='CCB_DESKTOP_SYNTHETIC_WINDOWS_REUSE_PROFILE',
    sourceCommit=head,applicationImage=str(image),retainedSyntheticProfile=str(profile),
    profileSourceCommit=owner['sourceCommit'],installerExecuted=False),indent=2)+'\n')
(base/'frozen-production.json').write_text(json.dumps([dict(file=p.name,bytes=p.stat().st_size,sha256=sha(p)) for p in jars],indent=2)+'\n')
fixture=profile/'native-fixture.jar'
fixtureSource='app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/DesktopWindowsPackageFixture.kt'
assert not subprocess.check_output(['git','diff',owner['sourceCommit'],head,'--',fixtureSource],cwd=repo)
classpath=os.pathsep.join(map(str,[fixture,*jars]))
(base/'frozen-classpath.private.txt').write_text(classpath)
probe=(profile/'native-app-image-probe.py').read_text()
old='program = safe(Path(owner["applicationImage"]))'
assert probe.count(old)==1
probe=probe.replace(old,'assert owner["sourceCommit"] == '+repr(owner['sourceCommit'])+'\n    program = safe(Path('+repr(str(image))+'))')
old='assert before["commit"] == owner["sourceCommit"] and before["version"] == "0.1.0-preview.1008"'
assert probe.count(old)==1
probe=probe.replace(old,'assert before["commit"] == '+repr(head)+' and before["version"] == "0.1.0-preview.1009"')
assert probe.count('deadline = time.monotonic() + 45')==1
probe=probe.replace('deadline = time.monotonic() + 45','deadline = time.monotonic() + 180')
probePath=base/'native-1009-retained-profile-probe.py';probePath.write_text(probe)
env=os.environ.copy();env.update({'LOCALAPPDATA':str(profile/'local-appdata'),'APPDATA':str(profile/'roaming'),'TEMP':str(profile/'temp'),'TMP':str(profile/'temp')})
for name in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS']:env.pop(name,None)
java=r'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe'
verify=[java,'-Duser.home='+str(profile/'home'),'-Djava.io.tmpdir='+str(profile/'temp'),'-Dfile.encoding=UTF-8','-cp',classpath,
        'com.example.chatbar.desktop.DesktopWindowsPackageFixture','verify',str(profile)]
steps=[('verify-before',verify),('cold-1009-first',[sys.executable,'-I',str(probePath),'launch-close','--base',str(profile),'--label','cold-1009-first']),
       ('verify-after-first',verify),('cold-1009-second',[sys.executable,'-I',str(probePath),'launch-close','--base',str(profile),'--label','cold-1009-second']),('verify-after-second',verify)]
for label,args in steps:
    (base/(label+'-arguments.json')).write_text(json.dumps(args,indent=2)+'\n')
    with (base/(label+'-driver.log')).open('wb') as log:
        result=subprocess.run(args,cwd=repo,env=env,stdout=log,stderr=subprocess.STDOUT)
    (base/(label+'-exit.txt')).write_text(str(result.returncode))
    print(label+'_EXIT_'+str(result.returncode),flush=True)
    if result.returncode:sys.exit(result.returncode)
for row in rows:assert sha(image/row['path'])==row['sha256']
(base/'RESULT.json').write_text(json.dumps(dict(source=head,reusedExactFailingSyntheticProfile=True,
    profileRecreated=False,profileOwnerUnchanged=json.loads((profile/'TASK_OWNER.json').read_text())==owner,
    packageFilesUnchanged=True,nativeColdStarts=2,independentVerifications=3,installerExecuted=False),indent=2)+'\n')
print('NATIVE1009_RETAINED_PROFILE_ALL_PASS')
