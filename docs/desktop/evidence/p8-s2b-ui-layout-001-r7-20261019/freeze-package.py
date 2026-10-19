from pathlib import Path
import json,hashlib,shutil,subprocess,zipfile,re,struct
R=Path.cwd();G=Path(__file__).resolve().parent
def sha(b):return hashlib.sha256(b).hexdigest()
def git(*a):return subprocess.check_output(['git',*a],stderr=subprocess.PIPE).decode().strip()
source=json.loads((G/'source-preflight.json').read_text(encoding='utf-8'))
assert git('rev-parse','HEAD')==source['head'] and not git('diff','--name-only') and not git('diff','--cached','--name-only')
stage=json.loads((G/'01-create-distributable/stage.json').read_text(encoding='utf-8-sig'));assert stage['exitCode']==0
raw=(G/'01-create-distributable/gradle.stdout.log').read_bytes();log=raw.decode('utf-8',errors='strict')
tasks=[]
for line in log.splitlines():
 if line.startswith('> Task '):
  p=line[len('> Task '):].split(' ',1);tasks.append(dict(task=p[0],status=p[1] if len(p)>1 else 'EXECUTED'))
for task in [':sharedCore:compileKotlin',':desktopApp:compileKotlin',':desktopApp:createDistributable']:
 matches=[s for s in tasks if s['task']==task];assert len(matches)==1 and matches[0]['status']=='EXECUTED',matches
assert not any(s['task']==':desktopApp:test' for s in tasks)
original=R/'app/desktopApp/build/compose/binaries/main/app/ChatChatBarDesktop';target=G/'runtime-image'
assert not target.exists() and not (original/'portable.flag').exists() and not (original/'UserData').exists()
for p in original.rglob('*'):assert not p.is_symlink() and not (getattr(p.lstat(),'st_file_attributes',0)&0x400)
shutil.copytree(original,target)
files=[dict(path=p.relative_to(target).as_posix(),bytes=p.stat().st_size,sha256=sha(p.read_bytes())) for p in sorted(target.rglob('*')) if p.is_file()]
assert not any(any(s in f['path'].lower() for s in ['.p12','.jks','r7uisupport','nativeuxtest','userdata/','entities/','support-classes']) for f in files)
jar,=list((target/'app').glob('desktopApp-*.jar'))
def source_file(b):
 assert b[:4]==b'\xca\xfe\xba\xbe';pos=8
 def u2():
  nonlocal pos
  n=int.from_bytes(b[pos:pos+2],'big');pos+=2;return n
 def u4():
  nonlocal pos
  n=int.from_bytes(b[pos:pos+4],'big');pos+=4;return n
 count=u2();pool={};i=1
 while i<count:
  tag=b[pos];pos+=1
  if tag==1:
   n=u2();pool[i]=b[pos:pos+n].decode('utf-8',errors='replace');pos+=n
  elif tag in [3,4,9,10,11,12,17,18]:pos+=4
  elif tag in [5,6]:pos+=8;i+=1
  elif tag in [7,8,16,19,20]:pos+=2
  elif tag==15:pos+=3
  else:raise AssertionError(('Unknown class tag',tag))
  i+=1
 pos+=6
 interfaces=u2();pos+=2*interfaces
 def attrs(capture=False):
  nonlocal pos
  result=None
  for _ in range(u2()):
   name=u2();n=u4()
   if capture and pool.get(name)=='SourceFile':assert n==2;result=pool.get(int.from_bytes(b[pos:pos+2],'big'))
   pos+=n
  return result
 for _kind in range(2):
  for _ in range(u2()):pos+=6;attrs()
 return attrs(True)
verified=[];forbidden=[]
with zipfile.ZipFile(jar) as z:
 for n in z.namelist():
  if not n.endswith('.class'):continue
  if any(x in n for x in ['DesktopFishPickerNativeUxTest','R7UiSupport','R6J','HumanMouseControl']):forbidden.append(n)
  b=z.read(n);sf=source_file(b)
  if sf in ['DesktopFishPickerPresentation.kt','DesktopFishPickerWindow.kt']:
   compiled=R/'app/desktopApp/build/classes/kotlin/main'/n
   assert compiled.read_bytes()==b
   verified.append(dict(className=n,sourceFile=sf,bytes=len(b),sha256=sha(b)))
assert not forbidden and all(any(c['sourceFile']==s for c in verified) for s in ['DesktopFishPickerPresentation.kt','DesktopFishPickerWindow.kt'])
for s in source['source']:assert sha((R/s['path']).read_bytes())==s['sha256']
cfg=(target/'app/ChatChatBarDesktop.cfg').read_text();assert 'chatbar.desktop.applicationHome=$ROOTDIR' in cfg
data=dict(buildHead=source['head'],sourceCommit=source['sourceCommit'],packagePath=str(target),fileCount=len(files),files=files,
 launcher=dict(path='ChatChatBarDesktop.exe',bytes=(target/'ChatChatBarDesktop.exe').stat().st_size,sha256=sha((target/'ChatChatBarDesktop.exe').read_bytes())),
 businessJar=dict(path=jar.relative_to(target).as_posix(),bytes=jar.stat().st_size,sha256=sha(jar.read_bytes())),
 classesVerified=verified,testRunnerAndPrivateMaterialAbsent=True,applicationHomeConfiguration=cfg)
(G/'distribution-manifest.json').write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
(G/'task-execution-ledger.json').write_text(json.dumps(dict(command=stage['command'],tasks=tasks,sourceCompileExecuted=True,testsExecuted=False),indent=2)+'\n',encoding='utf-8')
actual={p.relative_to(target).as_posix() for p in target.rglob('*') if p.is_file()};assert actual=={f['path'] for f in files}
for f in files:
 b=(target/f['path']).read_bytes();assert len(b)==f['bytes'] and sha(b)==f['sha256']
print(json.dumps(dict(files=len(files),verifiedFishClasses=len(verified),launcher=data['launcher'],businessJar=data['businessJar'],manifestSha256=sha((G/'distribution-manifest.json').read_bytes()))))
