"""Retry MSI build in the existing safety tool's permitted repository build root."""
from pathlib import Path
import hashlib,json,os,shutil,subprocess,sys,time
if not __debug__:raise SystemExit('VALIDATION_REQUIRED')
repo=Path('H:/ChatChatBar-Desktop');case=repo/'app/desktopApp/build/preview-continuous-private-20261025/windows-msi-candidate-1010-owned-root';build=repo/'app/desktopApp/build/preview-win-msi-candidate-1010'
head='946c3791153634b0af65286313a497d141d5c598'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo).decode().strip()==head
assert not subprocess.check_output(['git','diff','--name-only'],cwd=repo)
assert not case.exists() and not build.exists();assert shutil.disk_usage(repo).free>2600000000
case.mkdir();build.mkdir()
def save(name,value):(case/name).write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
owner={'kind':'CCB_DESKTOP_INTERNAL_UNPUBLISHED_MSI_BUILD','sourceCommit':head,'profile':'desktop','installerExecuted':False};save('TASK_OWNER.json',owner);(build/'TASK_OWNER.json').write_text(json.dumps(owner,indent=2)+'\n',encoding='utf-8')
(case/'Build-InternalMsi-1010-OwnedRoot.py').write_bytes(Path(__file__).read_bytes())
init=case/'package.init.gradle';init.write_text("gradle.beforeProject { p -> if (p.path == ':desktopApp') p.layout.buildDirectory.set(new File('"+build.as_posix()+"')) }\n",encoding='utf-8')
args=json.loads((case.parent/'windows-package-candidate-1010/arguments.json').read_text(encoding='utf-8'));args[args.index('-I')+1]=str(init);assert args[-1]==':desktopApp:createDistributable';args[-1]=':desktopApp:packageMsi';save('arguments.json',args)
env=os.environ.copy();env['JAVA_HOME']='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
for name in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS']:env.pop(name,None)
start=time.time()
with (case/'build.log').open('wb') as log:result=subprocess.run(args,cwd=repo/'app',env=env,stdout=log,stderr=subprocess.STDOUT)
(case/'exit.txt').write_text(str(result.returncode),encoding='ascii');report={'exit':result.returncode,'elapsedSeconds':round(time.time()-start,2),'sourceCommit':head,'installerExecuted':False}
if result.returncode==0:
    assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo).decode().strip()==head
    msi=build/'compose/binaries/main/msi/ChatChatBarDesktop-0.1.1010.msi';assert msi.is_file()
    report.update(bytes=msi.stat().st_size,sha256=hashlib.sha256(msi.read_bytes()).hexdigest())
    receipts=list((build/'reports/windows-installer-safety').glob('packageMsi-*/verified/RECEIPT.json'));assert len(receipts)==1
    receipt=json.loads(receipts[0].read_text());assert receipt['profile']=='desktop' and receipt['outputSha256']==report['sha256'] and not receipt['syntheticFailureAction']
    (case/'SAFETY_RECEIPT.json').write_bytes(receipts[0].read_bytes())
save('BUILD_RESULT.json',report);print('OWNED_ROOT_CANDIDATE_MSI_BUILD_EXIT_'+str(result.returncode),flush=True)
if result.returncode:print((case/'build.log').read_text(encoding='utf-8',errors='replace')[-4500:])
sys.exit(result.returncode)