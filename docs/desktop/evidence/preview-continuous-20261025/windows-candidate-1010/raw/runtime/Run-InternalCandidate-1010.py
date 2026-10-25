"""Candidate app-image only. Synthetic profile, explicit image, no MSI/registry actions."""
from pathlib import Path
import ast,hashlib,json,os,subprocess,sys,zipfile
if not __debug__:raise SystemExit('VALIDATION_REQUIRED')
repo=Path('H:/ChatChatBar-Desktop');home=repo/'app/desktopApp/build/preview-continuous-private-20261025'
package=home/'windows-package-candidate-1010';base=home/'windows-runtime-candidate-1010'
image=Path('I:/CCB-Desktop-Preview-Private-Candidate-1010-20261025/compose/binaries/main/app/ChatChatBarDesktop')
head='946c3791153634b0af65286313a497d141d5c598'
assert (package/'exit.txt').read_text()=='0' and (package/'audit-exit.txt').read_text()=='0'
assert not subprocess.check_output(['git','diff',head,'--','app/desktopApp/src/main','app/sharedCore/src/main'],cwd=repo)
assert not base.exists();base.mkdir()
for name in ['local-appdata','roaming','temp','home']:(base/name).mkdir()
def save(path,value):path.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1048576),b''):h.update(chunk)
    return h.hexdigest()
save(base/'TASK_OWNER.json',{'kind':'CCB_DESKTOP_SYNTHETIC_WINDOWS_RUNTIME','sourceCommit':head,'applicationImage':str(image),'installerExecuted':False,'ordinaryInstalledProductTouched':False})
(base/'Run-InternalCandidate-1010.py').write_bytes(Path(__file__).read_bytes())
fixtureSource='app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/DesktopWindowsPackageFixture.kt'
assert not subprocess.check_output(['git','diff','2f75a4d3d4e5557d7bd513f2e9368a4ed5e8d1f7',head,'--',fixtureSource],cwd=repo)
fixture=home/'windows-runtime-merge-ui-1008/native-fixture.jar'
(base/'native-fixture.jar').write_bytes(fixture.read_bytes());(base/'native-fixture-source.kt').write_bytes((repo/fixtureSource).read_bytes())
jars=sorted((image/'app').glob('*.jar'));assert len(jars)==63
classpath=os.pathsep.join(map(str,[base/'native-fixture.jar',*jars]));(base/'frozen-classpath.private.txt').write_text(classpath,encoding='utf-8')
save(base/'frozen-production.json',[{'file':p.name,'bytes':p.stat().st_size,'sha256':sha(p)} for p in jars])
probeSource=(repo/'tools/desktop/windows/native_package_probe.py').read_text(encoding='utf-8')
# Copy only lifecycle observation primitives. No install/repair/uninstall or MSI functions.
allowed={'save','safe','task','identity','Startup','ProcessInfo','launch_close'}
nodes=[n for n in ast.parse(probeSource).body if isinstance(n,(ast.FunctionDef,ast.ClassDef)) and n.name in allowed]
assert {n.name for n in nodes}==allowed
probe='if not __debug__: raise SystemExit("VALIDATION_REQUIRED")\nimport ctypes as C\nfrom ctypes import wintypes as W\nimport json,msvcrt,os,subprocess,time,zipfile\nfrom pathlib import Path\n'
probe+='REPO=Path('+repr(str(repo))+')\nTASKS=REPO/"app/desktopApp/build/preview-continuous-private-20261025"\nPRODUCT="ChatChatBarDesktop"\n'
probe+='\n\n'.join(ast.get_source_segment(probeSource,n) for n in nodes)+'\n'
assert probe.count('values[0]["packagingProfile"] == "synthetic"')==1
probe=probe.replace('values[0]["packagingProfile"] == "synthetic"','values[0]["packagingProfile"] == "desktop"')
assert probe.count('program = safe(base / "program")')==1
probe=probe.replace('program = safe(base / "program")','assert base == Path('+repr(str(base))+')\n    program = safe(Path('+repr(str(image))+'))')
probe=probe.replace('before = identity(program)','before = identity(program)\n    assert before["commit"] == '+repr(head)+' and before["version"] == "0.1.0-preview.1010" and before["installerVersion"] == "0.1.1010"')
assert probe.count('deadline = time.monotonic() + 40')==1
probe=probe.replace('deadline = time.monotonic() + 40','deadline = time.monotonic() + 180')
old='(REPO / "app/desktopApp/build/readonly-r0/runtime-classpath.txt").read_text()';assert probe.count(old)==1
probe=probe.replace(old,'(base / "frozen-classpath.private.txt").read_text(encoding="utf-8")')
probe+='\nif __name__ == "__main__":\n    import sys\n    assert len(sys.argv)==2 and sys.argv[1] in ("first", "second")\n    launch_close(task(Path('+repr(str(base))+')), "candidate-1010-"+sys.argv[1])\n    print("CANDIDATE_1010_NATIVE_LIFECYCLE_PASS")\n'
probePath=base/'candidate-lifecycle-only-probe.py';probePath.write_text(probe,encoding='utf-8')
assert 'def install(' not in probe and 'def repair_registration(' not in probe and 'msiexec' not in probe
save(base/'PROBE_SCOPE.json',{'functions':sorted(allowed),'ordinaryProfileImageOnly':True,'noInstallerFunctions':True,'sourceProbeSha256':sha(repo/'tools/desktop/windows/native_package_probe.py'),'generatedProbeSha256':sha(probePath),'externalSyntheticFixtureSha256':sha(base/'native-fixture.jar')})
env=os.environ.copy();env.update({'LOCALAPPDATA':str(base/'local-appdata'),'APPDATA':str(base/'roaming'),'TEMP':str(base/'temp'),'TMP':str(base/'temp')})
for name in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS']:env.pop(name,None)
java='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin/java.exe'
def verify(mode):return [java,'-Djava.awt.headless=true','-Duser.home='+str(base/'home'),'-Djava.io.tmpdir='+str(base/'temp'),'-Dfile.encoding=UTF-8','-cp',classpath,'com.example.chatbar.desktop.DesktopWindowsPackageFixture',mode,str(base)]
steps=[('seed',verify('seed')),('first',[sys.executable,'-I',str(probePath),'first']),('verify-first',verify('verify')),('second',[sys.executable,'-I',str(probePath),'second']),('verify-second',verify('verify'))]
for label,args in steps:
    save(base/(label+'-arguments.json'),args)
    with (base/(label+'-driver.log')).open('wb') as log:result=subprocess.run(args,cwd=repo,env=env,stdout=log,stderr=subprocess.STDOUT)
    (base/(label+'-exit.txt')).write_text(str(result.returncode),encoding='ascii');print('CANDIDATE_1010_'+label.upper()+'_EXIT_'+str(result.returncode),flush=True)
    if result.returncode:raise SystemExit(result.returncode)
# Complete immutable image inventory comparison after native processes close.
audit=json.loads((package/'PACKAGE_AUDIT.json').read_text(encoding='utf-8'))
fileRows=audit.get('files',[])
assert isinstance(fileRows,list) and fileRows
for row in fileRows:
    p=image/row['path'];assert p.stat().st_size==row['bytes'] and sha(p)==row['sha256']
save(base/'RESULT.json',{'sourceCommit':head,'version':'0.1.0-preview.1010','packagingProfile':'desktop','installerExecuted':False,'nativeColdStarts':2,'normalLauncherExit':0,'ownedProcessesRemaining':0,'independentSeedOrVerifications':3,'syntheticProtectedKeys':3,'packageFilesUnchanged':True,'humanVisualResult':'NOT_TESTED'})
print('CANDIDATE_1010_NATIVE_AND_SYNTHETIC_RETENTION_PASS',flush=True)