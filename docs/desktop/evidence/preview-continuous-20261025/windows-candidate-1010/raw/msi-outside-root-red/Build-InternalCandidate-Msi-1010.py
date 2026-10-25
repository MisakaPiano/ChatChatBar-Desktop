"""Build and inspect internal ordinary-profile MSI without running Windows Installer."""
from pathlib import Path
import hashlib,json,os,subprocess,sys,time
if not __debug__:raise SystemExit('VALIDATION_REQUIRED')
repo=Path('H:/ChatChatBar-Desktop');case=repo/'app/desktopApp/build/preview-continuous-private-20261025/windows-package-candidate-1010';build=Path('I:/CCB-Desktop-Preview-Private-Candidate-1010-20261025')
assert (case/'exit.txt').read_text()=='0' and (case/'audit-exit.txt').read_text()=='0'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo).decode().strip()=='946c3791153634b0af65286313a497d141d5c598'
assert not subprocess.check_output(['git','diff','--name-only'],cwd=repo)
msi=build/'compose/binaries/main/msi/ChatChatBarDesktop-0.1.1010.msi';assert not msi.exists()
for name in ['msi-build.log','msi-exit.txt','msi-arguments.json','MSI_BUILD_RESULT.json']:assert not (case/name).exists()
(case/'Build-InternalCandidate-Msi-1010.py').write_bytes(Path(__file__).read_bytes())
args=json.loads((case/'arguments.json').read_text(encoding='utf-8'));assert args[-1]==':desktopApp:createDistributable';args[-1]=':desktopApp:packageMsi'
(case/'msi-arguments.json').write_text(json.dumps(args,indent=2)+'\n',encoding='utf-8')
env=os.environ.copy();env['JAVA_HOME']='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
for name in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS']:env.pop(name,None)
start=time.time()
with (case/'msi-build.log').open('wb') as log:result=subprocess.run(args,cwd=repo/'app',env=env,stdout=log,stderr=subprocess.STDOUT)
(case/'msi-exit.txt').write_text(str(result.returncode),encoding='ascii')
report={'exit':result.returncode,'elapsedSeconds':round(time.time()-start,2),'installerExecuted':False,'ordinaryInstallationTouched':False}
if result.returncode==0:
    assert msi.is_file();report.update(bytes=msi.stat().st_size,sha256=hashlib.sha256(msi.read_bytes()).hexdigest())
(case/'MSI_BUILD_RESULT.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print('INTERNAL_CANDIDATE_MSI_BUILD_EXIT_'+str(result.returncode),flush=True)
if result.returncode:print((case/'msi-build.log').read_text(encoding='utf-8',errors='replace')[-4500:])
sys.exit(result.returncode)