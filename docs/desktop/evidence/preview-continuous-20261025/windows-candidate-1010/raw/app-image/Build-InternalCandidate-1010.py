"""Task-owned unpublished ordinary-profile app-image build; no installation/runtime data."""
from pathlib import Path
import hashlib,json,os,subprocess,sys,time
if not __debug__: raise SystemExit('ENABLED_VALIDATION_REQUIRED')
repo=Path('H:/ChatChatBar-Desktop')
case=repo/'app/desktopApp/build/preview-continuous-private-20261025/windows-package-candidate-1010'
build=Path('I:/CCB-Desktop-Preview-Private-Candidate-1010-20261025')
def git(*args): return subprocess.check_output(['git',*args],cwd=repo).decode('utf-8').strip()
def require(value,code):
    if not value: raise RuntimeError(code)
require(not git('diff','--name-only'),'TRACKED_SOURCE_DIRTY')
require(not git('diff','--cached','--name-only'),'INDEX_DIRTY')
require(git('branch','--show-current')=='feature/phase8-fish-audio','BRANCH_MISMATCH')
head=git('rev-parse','HEAD')
require(head=='946c3791153634b0af65286313a497d141d5c598','SOURCE_CHANGED')
require(not case.exists() and not build.exists(),'NEW_DIRECTORIES_REQUIRED')
require(not build.parent.is_symlink() and not build.parent.is_junction() if hasattr(build.parent,'is_junction') else not build.parent.is_symlink(),'REPARSE_PARENT')
case.mkdir();build.mkdir()
def save(name,value): (case/name).write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
owner={'kind':'CCB_DESKTOP_INTERNAL_UNPUBLISHED_CANDIDATE_BUILD','sourceCommit':head,'version':'0.1.0-preview.1010','installerVersion':'0.1.1010','profile':'desktop','installerExecuted':False}
(build/'TASK_OWNER.json').write_text(json.dumps(owner,indent=2)+'\n',encoding='utf-8');save('TASK_OWNER.json',owner)
(case/'Build-InternalCandidate-1010.py').write_bytes(Path(__file__).read_bytes())
paths=subprocess.check_output(['git','-c','core.quotepath=false','ls-files','-z','app/desktopApp/src/main','app/sharedCore/src/main','app/app/src/main/java','app/app/src/main/assets','app/desktopApp/build.gradle.kts','app/sharedCore/build.gradle.kts','app/settings.gradle.kts','app/gradle/libs.versions.toml'],cwd=repo).decode('utf-8').split('\0')
rows=[]
for relative in filter(None,paths):
    p=repo/relative;st=p.lstat();require(not p.is_symlink() and not st.st_file_attributes & 0x400,'SOURCE_REPARSE')
    rows.append({'path':relative,'bytes':st.st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
save('BUILD_INPUTS.json',rows)
(case/'head.txt').write_text(head,encoding='ascii')
init=case/'package.init.gradle'
init.write_text("gradle.beforeProject { p -> if (p.path == ':desktopApp') p.layout.buildDirectory.set(new File('"+build.as_posix()+"')) }\n",encoding='utf-8')
args=['cmd.exe','/d','/c','gradlew.bat','--offline','--no-daemon','--no-build-cache','--no-configuration-cache','--max-workers=1','--no-parallel','--console=plain','-Dorg.gradle.jvmargs=-Xmx1024m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8','-Pkotlin.compiler.execution.strategy=in-process','-PdesktopVersion=0.1.0-preview.1010','-PdesktopInstallerVersion=0.1.1010','-PdesktopPackagingProfile=desktop','-I',str(init),':desktopApp:verifyDesktopInstallerIdentity',':desktopApp:createDistributable']
save('arguments.json',args)
env=os.environ.copy();env['JAVA_HOME']='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
for name in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS']: env.pop(name,None)
start=time.time()
with (case/'build.log').open('wb') as log: result=subprocess.run(args,cwd=repo/'app',env=env,stdout=log,stderr=subprocess.STDOUT)
(case/'exit.txt').write_text(str(result.returncode),encoding='ascii');save('BUILD_RESULT.json',{'exit':result.returncode,'elapsedSeconds':round(time.time()-start,2),'sourceCommit':head,'inputFiles':len(rows)})
print('INTERNAL_CANDIDATE_BUILD_EXIT_'+str(result.returncode),flush=True)
if result.returncode:
    print((case/'build.log').read_text(encoding='utf-8',errors='replace')[-5000:]);sys.exit(result.returncode)
require(git('rev-parse','HEAD')==head and not git('diff','--name-only'),'SOURCE_CHANGED_DURING_BUILD')
image=build/'compose/binaries/main/app/ChatChatBarDesktop';require(image.is_dir(),'IMAGE_MISSING')
command=[sys.executable,'-I',str(repo/'tools/desktop/windows/audit_app_image.py'),'--image',str(image),'--commit',head,'--version','0.1.0-preview.1010','--installer-version','0.1.1010','--profile','desktop','--report',str(case/'PACKAGE_AUDIT.json')]
save('audit-arguments.json',command)
with (case/'audit.log').open('wb') as log: audit=subprocess.run(command,cwd=repo,stdout=log,stderr=subprocess.STDOUT)
(case/'audit-exit.txt').write_text(str(audit.returncode),encoding='ascii');print('INTERNAL_CANDIDATE_AUDIT_EXIT_'+str(audit.returncode),flush=True)
sys.exit(audit.returncode)