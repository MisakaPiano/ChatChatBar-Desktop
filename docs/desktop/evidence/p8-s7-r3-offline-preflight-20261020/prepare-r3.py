from pathlib import Path
import hashlib,json,subprocess,shutil,os,re,datetime,difflib,ast
R=Path(__file__).resolve().parents[4]
B=Path(__file__).resolve().parent
O=B/'windows-final-gate-r2-20261020'
F=B/'windows-final-gate-r3-20261020'
E=R/'docs/desktop/evidence/p8-s7-r3-offline-preflight-20261020'
J=Path(r'C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin')
def git(*a):return subprocess.check_output(['git',*a],cwd=R,text=True,encoding='utf-8').strip()
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as s:
  for b in iter(lambda:s.read(1048576),b''):h.update(b)
 return h.hexdigest()
def write(p,v):p.write_bytes((json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode('utf-8'))
assert git('rev-parse','HEAD')==git('rev-parse','@{u}')=='39fdc97d1b34591fe2f61329788f239ddff2b0d3'
assert git('branch','--show-current')=='feature/phase8-fish-audio'
assert not git('diff','--name-only') and not git('diff','--cached','--name-only')
assert not git('diff','008343bb26d2eb76c23cae9b98132af828a9f447','HEAD','--','app','.agents/skills')
assert not F.exists() and not E.exists()
F.mkdir();E.mkdir();(E/'.gitattributes').write_bytes(b'* -text\n')
preserved=[]
for folder in [B/'windows-final-gate',O]:
 for n in ['manual-start-once.lock','manual-status.json','mock.stderr.log','mock-final.json','P8S7Support.java','manual-supervisor.py']:
  p=folder/n
  if p.exists():preserved.append(dict(path=str(p),bytes=p.stat().st_size,sha256=sha(p)))
write(E/'preflight.json',dict(head=git('rev-parse','HEAD'),source='008343bb26d2eb76c23cae9b98132af828a9f447',branch=git('branch','--show-current'),preservedR1R2=preserved,userFiles='KEEP / UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED',guiAuthorized=False))
manifest=json.loads((O/'distribution-manifest.json').read_text(encoding='utf-8'))
assert len(manifest['files'])==212 and manifest['sourceCommit']=='008343bb26d2eb76c23cae9b98132af828a9f447'
for x in manifest['files']:
 p=O/'runtime-image'/x['path'];assert p.stat().st_size==x['bytes'] and sha(p)==x['sha256']
shutil.copytree(O/'runtime-image',F/'runtime-image')
for n in ['runtime-watch.ps1','manual-supervisor.py','distribution-manifest.json','package-identity.json','live-official-sample.mp3','synthetic-tts-fixture.mp3','synthetic-tts-long.mp3']:shutil.copyfile(O/n,F/n)
for n in ['support-classes','support-temp','synthetic-home','preview-temp','uds','environments']:(F/n).mkdir()
# Deliberately DO NOT create mock-service. The fake owns its creation after future authorization.
old=(O/'P8S7Support.java').read_text(encoding='utf-8')
prior=re.search(r'static final String FAKE="([^"]+)";',old)
assert prior
text=old.replace(prior.group(0),'static String FAKE;\n    static final Set<String> MODEL_KEYS=new HashSet<>();')
text=text.replace(prior.group(1),'')
text=text.replace('case "seed" -> seed(Path.of(args[1]));','case "seed" -> { FAKE="synthetic-invalid-"+UUID.randomUUID(); seed(Path.of(args[1])); }')
text=text.replace('case "mock" -> mock(Path.of(args[1]));','''case "mock" -> {
                Path folder=Path.of(args[1]);
                var store=WindowsSecretStore.Companion.create(folder.getParent().resolve("environments/p8s7-profile/ChatChatBarDesktop"));
                FAKE=store.load(DesktopCredentialKey.FishAudioApiKey.INSTANCE);
                if(FAKE==null||!FAKE.startsWith("synthetic-invalid-"))throw new IOException("Synthetic Fish credential required");
                for(String id:List.of("p8s7-chat","p8s7-aux")) {
                    String key=store.load(new DesktopCredentialKey.ModelApiKey(id));
                    if(key==null||!key.startsWith("synthetic-invalid-")||key.equals(FAKE)||!MODEL_KEYS.add(key))throw new IOException("Synthetic model credential required");
                }
                mock(folder);
            }''')
for logical in ['p8s7-chat','p8s7-aux']:
 before='root.resolve("entities/model_configs/'+logical+'.json")'
 after='root.resolve("entities/model_configs/"+com.example.chatbar.data.repository.WindowsSafeModelStorageKeyPolicy.INSTANCE.storageKey("'+logical+'")+".json")'
 assert text.count(before)==1
 text=text.replace(before,after)
oldsave='WindowsSecretStore.Companion.create(root).save(DesktopCredentialKey.FishAudioApiKey.INSTANCE,FAKE);'
assert text.count(oldsave)==1
text=text.replace(oldsave,'''var syntheticStore=WindowsSecretStore.Companion.create(root);
            syntheticStore.save(DesktopCredentialKey.FishAudioApiKey.INSTANCE,FAKE);
            for(String id:List.of("p8s7-chat","p8s7-aux"))
                syntheticStore.save(new DesktopCredentialKey.ModelApiKey(id),"synthetic-invalid-"+UUID.randomUUID());''')
auth='boolean validApi=request.contains("Authorization: Bearer "+FAKE+"\\r\\n");'
assert text.count(auth)==1
text=text.replace(auth,'boolean validApi=auxiliary?MODEL_KEYS.stream().anyMatch(k->request.contains("Authorization: Bearer "+k+"\\r\\n")):request.contains("Authorization: Bearer "+FAKE+"\\r\\n");')
assert prior.group(1) not in text
(F/'P8S7Support.java').write_bytes(text.encode('utf-8'))
sanitizedOld=old.replace(prior.group(1),'[REDACTED PRIOR SYNTHETIC CREDENTIAL]')
(E/'fixture-helper-redacted.patch').write_bytes(''.join(difflib.unified_diff(sanitizedOld.splitlines(True),text.splitlines(True),fromfile='R2/P8S7Support.java (prior credential literal redacted)',tofile='R3/P8S7Support.java')).encode('utf-8'))
shutil.copyfile(B/'P8R3Preflight.java',F/'P8R3Preflight.java')
env=dict(os.environ);env['LOCALAPPDATA']=str(F/'environments/p8s7-profile')
for n in ['JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS']:env.pop(n,None)
def run(name,args,expected=0):
 write(E/(name+'.command.json'),list(map(str,args)))
 start=datetime.datetime.now(datetime.timezone.utc)
 with (E/(name+'.stdout.txt')).open('wb') as out,(E/(name+'.stderr.txt')).open('wb') as err:
  p=subprocess.Popen(list(map(str,args)),env=env,stdout=out,stderr=err)
  write(E/(name+'.process.json'),dict(pid=p.pid,startedUtc=start.isoformat(),owned=True))
  try:rc=p.wait(timeout=90)
  except subprocess.TimeoutExpired:
   write(E/(name+'.result.json'),dict(status='OWNED HELPER EXIT UNCONFIRMED; no kill or retry',pid=p.pid));raise
 write(E/(name+'.result.json'),dict(exitCode=rc,expected=expected,startedUtc=start.isoformat(),endedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),guiStarts=0,networkCalls=0))
 assert rc==expected,name
run('01-helper-compile',[J/'javac.exe','-J-Xmx128m','--release','17','-encoding','UTF-8','-cp',F/'runtime-image/app/*','-d',F/'support-classes',F/'P8S7Support.java',F/'P8R3Preflight.java'])
cp=str(F/'support-classes')+os.pathsep+str(F/'runtime-image/app/*')
base=[J/'java.exe','-Xmx128m','-Djava.awt.headless=true','-Duser.home='+str(F/'synthetic-home'),'-Djava.io.tmpdir='+str(F/'support-temp'),'-cp',cp]
run('02-auxiliary-payload-selfcheck',base+['P8S7Support','selfcheck'])
root=F/'environments/p8s7-profile/ChatChatBarDesktop'
run('03-protected-synthetic-seed',base+['P8S7Support','seed',root])
jsonChecks=[]
for p in sorted((root/'entities/model_configs').glob('*.json')):
 v=json.loads(p.read_text(encoding='utf-8'));assert v['apiKey']==''
 assert p.name=='ccb-model-v1-'+v['id'].encode('utf-8').hex()+'.json'
 jsonChecks.append(dict(id=v['id'],path=p.relative_to(root).as_posix(),apiKeyEmpty=True))
write(E/'model-json-checks.json',jsonChecks)
assert len(jsonChecks)==2
run('04-real-policy-repository-hydrate',base+['P8R3Preflight',root])
run('05-post-preflight-independent-lock',base+['P8S7Support','lock',root])
assert not (F/'mock-service').exists() and not (F/'manual-start-once.lock').exists() and not (F/'runs').exists()
def records(base,paths):return [dict(path=p.relative_to(base).as_posix(),bytes=p.stat().st_size,sha256=sha(p)) for p in sorted(paths)]
write(F/'fixture-manifest.json',dict(root=str(root),disposable=True,expectedBootstrap=dict(formatVersion=1,mode='DEFAULT'),files=records(root,root.rglob('*.json'))))
write(F/'support-manifest.json',dict(files=records(F,[F/'P8S7Support.java',F/'P8R3Preflight.java',F/'manual-supervisor.py',F/'runtime-watch.ps1',*list((F/'support-classes').glob('*.class'))])))
for x in manifest['files']:
 p=F/'runtime-image'/x['path'];assert p.stat().st_size==x['bytes'] and sha(p)==x['sha256']
for x in preserved:
 p=Path(x['path']);assert p.stat().st_size==x['bytes'] and sha(p)==x['sha256']
ast.parse((F/'manual-supervisor.py').read_text(encoding='utf-8'))
write(F/'manual-preparation-status.json',dict(status='R3 OFFLINE PREFLIGHT PASS / USER READY REQUIRED',source=manifest['sourceCommit'],packageFiles=212,guiStarts=0,fakeStarts=0,realFishGET=0,realFishTTS=0,mockServicePathAbsent=True,typedCredentialScopes=3,credentialsInEvidence=False,studioReadScope='actual resolver dependency path, not Compose GUI or repaired error handling',independentSingleUseLockAbsent=True))
for n in ['P8S7Support.java','P8R3Preflight.java','manual-supervisor.py','runtime-watch.ps1','fixture-manifest.json','support-manifest.json','distribution-manifest.json','package-identity.json','manual-preparation-status.json']:shutil.copyfile(F/n,E/n)
write(E/'identity-result.json',dict(packageFiles=212,packageManifestSHA256=sha(F/'distribution-manifest.json'),supportManifestSHA256=sha(F/'support-manifest.json'),fixtureManifestSHA256=sha(F/'fixture-manifest.json'),R1R2Preserved=True,productSourceUnchanged=True,mockServicePathAbsent=True,guiStarts=0,fakeStarts=0,realFishGET=0,realFishTTS=0))
print('R3 OFFLINE PREFLIGHT PASS / USER READY REQUIRED')
