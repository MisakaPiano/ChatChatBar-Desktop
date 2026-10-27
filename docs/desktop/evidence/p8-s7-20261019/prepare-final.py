from pathlib import Path
import hashlib,json,os,shutil,subprocess,datetime,zipfile,ast

G=Path(__file__).resolve().parent
F=G/'windows-final-gate'
O=G/'windows-gate'
REPO=G.parents[3]
SOURCE='008343bb26d2eb76c23cae9b98132af828a9f447'
JAVA=Path(r'C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin')
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as s:
  for b in iter(lambda:s.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
def write(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def records(root,paths):return [dict(path=p.relative_to(root).as_posix(),bytes=p.stat().st_size,sha256=sha(p)) for p in sorted(paths)]
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip()==SOURCE
assert not F.exists()
F.mkdir()
for name in ['P8S7Support.java','runtime-watch.ps1','live-official-sample.mp3','synthetic-tts-fixture.mp3','synthetic-tts-long.mp3']:
 shutil.copyfile(O/name,F/name)
supervisor=(O/'manual-supervisor.py').read_text(encoding='utf-8')
assert supervisor.startswith('raise SystemExit("Superseded')
supervisor=supervisor.split('\n',1)[1]
ast.parse(supervisor)
(F/'manual-supervisor.py').write_text(supervisor,encoding='utf-8')
shutil.copytree(REPO/'app/desktopApp/build/compose/binaries/main/app/ChatChatBarDesktop',F/'runtime-image')
files=records(F/'runtime-image',(p for p in (F/'runtime-image').rglob('*') if p.is_file()))
assert len(files)==212
write(F/'distribution-manifest.json',dict(sourceCommit=SOURCE,files=files))
business={}
expected={'desktopApp-':['DesktopChatVoiceRuntime','DesktopFishTtsAdmission','DesktopVoiceArchiveHandoff','DesktopVoicePlayback'],
          'sharedCore-':['VoiceMessageRepository','FishAudioPromptAuthority','FishAudioTagService']}
for prefix,classes in expected.items():
 jars=list((F/'runtime-image/app').glob(prefix+'*.jar'));assert len(jars)==1
 jar=jars[0]
 with zipfile.ZipFile(jar) as z:
  found=[n for n in z.namelist() if n.rsplit('/',1)[-1].removesuffix('.class') in classes and n.endswith('.class')]
  assert len(found)==len(classes)
 business[jar.name]=dict(bytes=jar.stat().st_size,sha256=sha(jar),classes=found)
write(F/'package-identity.json',dict(source=SOURCE,fileCount=len(files),manifestSHA256=sha(F/'distribution-manifest.json'),launcher=dict(path='ChatChatBarDesktop.exe',sha256=sha(F/'runtime-image/ChatChatBarDesktop.exe')),businessJars=business,launcherExecution='NOT TESTED',jvmExecution='NOT TESTED',status='EXACT FINAL PACKAGE BUILT; USER ACTION REQUIRED'))
for name in ['support-classes','support-temp','synthetic-home','mock-service','preview-temp','uds','environments']:(F/name).mkdir()
env=dict(os.environ);env['LOCALAPPDATA']=str(F/'environments/p8s7-profile')
for k in ['JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS']:env.pop(k,None)
def run(name,args):
 write(F/(name+'.command.json'),list(map(str,args)))
 with (F/(name+'.stdout.txt')).open('wb') as out,(F/(name+'.stderr.txt')).open('wb') as err:
  p=subprocess.run(list(map(str,args)),env=env,stdout=out,stderr=err,timeout=90)
 write(F/(name+'.result.json'),dict(exitCode=p.returncode,utc=datetime.datetime.now(datetime.timezone.utc).isoformat()))
 assert p.returncode==0,name
run('support-compile',[JAVA/'javac.exe','-J-Xmx128m','-encoding','UTF-8','--release','17','-cp',str(F/'runtime-image/app/*'),'-d',F/'support-classes',F/'P8S7Support.java'])
base=[JAVA/'java.exe','-Xmx128m','-Duser.home='+str(F/'synthetic-home'),'-Djava.io.tmpdir='+str(F/'support-temp'),'-cp',str(F/'support-classes')+os.pathsep+str(F/'runtime-image/app/*'),'P8S7Support']
run('support-selfcheck',base+['selfcheck'])
root=F/'environments/p8s7-profile/ChatChatBarDesktop'
run('synthetic-seed',base+['seed',root])
run('prelaunch-root-lock',base+['lock',root])
fixture=records(root,root.rglob('*.json'))
write(F/'fixture-manifest.json',dict(root=str(root),disposable=True,expectedBootstrap=dict(formatVersion=1,mode='DEFAULT'),files=fixture))
write(F/'support-manifest.json',dict(files=records(F,[F/'P8S7Support.java',F/'runtime-watch.ps1',F/'manual-supervisor.py',*list((F/'support-classes').rglob('*.class'))])))
assert not (F/'mock-service/ready.json').exists() and not (F/'manual-start-once.lock').exists()
write(F/'manual-preparation-status.json',dict(status='P8 S7 WINDOWS PACKAGE READY / USER ACTION REQUIRED',source=SOURCE,launches=0,fakeServers=0,realFishGET=0,realFishTTS=0,scope='Initial synthetic session; controlled restart needs user confirmation',launcherExecution='NOT TESTED',jvmExecution='NOT TESTED',listening='NOT TESTED',rootLockHelper='PASS acquisition/release only; running-app exclusion NOT TESTED'))
write(F/'preparation.json',dict(status='OFFLINE PREPARATION ONLY; no GUI/fake started',realFishGET=0,realFishTTS=0,assets=records(F,[F/'live-official-sample.mp3',F/'synthetic-tts-fixture.mp3',F/'synthetic-tts-long.mp3'])))
oldstatus=json.loads((O/'manual-preparation-status.json').read_text(encoding='utf-8-sig'))
write(O/'superseded-by-final.json',dict(previous=oldstatus,supersededBy=str(F),source=SOURCE,reason='Final queue/intent/reference-proof source checkpoint; previous package retained intact'))
print(json.dumps(json.loads((F/'package-identity.json').read_text()),indent=2))
