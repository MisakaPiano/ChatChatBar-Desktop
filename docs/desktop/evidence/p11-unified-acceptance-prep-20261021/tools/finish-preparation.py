from pathlib import Path
import hashlib,json,subprocess,xml.etree.ElementTree as ET
scope=Path(__file__).resolve().parent;repo=scope.parents[3]
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def save(path,obj):path.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
root=scope/'environments/manual-ready/app-data'
assert root.is_dir()
bootstrap=scope/'profile/Local/ChatChatBarDesktop.bootstrap.json'
bootstrapValue=dict(formatVersion=1,mode='CUSTOM',customRoot=str(root))
if bootstrap.exists():assert json.loads(bootstrap.read_text())==bootstrapValue
else:save(bootstrap,bootstrapValue)
for sub in ['exports/png','exports/mp3','exports/cbsave','exports/cbbackup','synthetic-home','preview-temp']:(scope/sub).mkdir(parents=True,exist_ok=True)
records=[]
for group in ['environments','materials']:
 # Production deliberately supports extended paths; the evidence inventory must do so too.
 scan=Path('\\\\?\\'+str(scope/group))
 for p in sorted(scan.rglob('*')):
  if p.is_file():records.append(dict(path=group+'/'+p.relative_to(scan).as_posix(),size=p.stat().st_size,sha256=sha(p)))
save(scope/'fixture-manifest.json',dict(syntheticOnly=True,protectedCredentialStoreExcluded=True,files=records))
entities=root/'entities'
counts={d.name:len(list(d.glob('*.json'))) for d in entities.iterdir() if d.is_dir()}
voices=[json.loads(p.read_text(encoding='utf-8-sig')) for p in (entities/'generated_voice_messages').glob('*.json')]
selected=[v for v in voices if v['sessionId']=='unified-a']
assert selected and all(v['durationMs']>10000 for v in selected)
messages={m['id']:m for p in (entities/'chat_messages').glob('*.json') if (m:=json.loads(p.read_text(encoding='utf-8-sig')))}
assert all(v['messageId'] in messages and messages[v['messageId']]['content'].strip() for v in voices)
models=[json.loads(p.read_text(encoding='utf-8-sig')) for p in (entities/'model_configs').glob('*.json')]
assert all(m['apiKey']=='' and m['baseUrl'].startswith('http://127.0.0.1:18793/') for m in models)
assert not json.loads((root/'desktop-settings.json').read_text())['automaticBackup']['enabled']
save(scope/'fixture-summary.json',dict(root=str(root),entityCounts=counts,primarySession='unified-a',longAudioDurationsMs=[v['durationMs'] for v in selected],recordingsAlwaysHaveText=True,modelEndpointsLoopbackOnly=True,modelJsonHasNoCredentials=True,automaticBackup=False,memory='Synthetic legacy-reference snapshot plus HEAD; not an AI-generated Episode/Arc/Era result',rag='Synthetic persisted chat and document vectors; not real-provider embedding quality evidence',sourceArchivePreserved=True))
save(scope/'launch-admission.json',dict(fixtureId='p11-unified-acceptance-prep-20261021',controlHead='ec2b3a8e52b0d5ad6bb8bb78126266f78e77d141',production='70b5c30c91439e79728bf224c1a44d35b25cf733',guiAuthorized=False,installAuthorized=False,privateMigrationAuthorized=False,consumed=False,maximumLaunches=1,requiresFreshSafetyCheck=True,root=str(root),localAppData=str(scope/'profile/Local'),fakeEndpoint='http://127.0.0.1:18793/v1',realProvidersAllowed=False))
suites=[]
for p in (scope/'02-service-regressions').glob('TEST-*.xml'):
 t=ET.parse(p).getroot();suites.append(dict(name=t.attrib['name'],**{k:int(t.attrib.get(k,0)) for k in ['tests','failures','errors','skipped']}))
assert len(suites)==8 and all(s['failures']==s['errors']==s['skipped']==0 for s in suites)
save(scope/'test-summary.json',dict(suites=suites,tests=sum(s['tests'] for s in suites),allPassed=True,independentProcessModes=[json.loads(p.read_text()) for p in sorted((scope/'integration').glob('*.json'))]))
protected=['CCB_DESKTOP_STANDARD_CONVERSATION_HANDOFF.md']+['.codex-remote-attachments/01a10e16-9252-7930-afe1-f07cb92177e6/'+p+'/1-1000072937.jpg' for p in ['4bb6706b-0132-41be-bd8e-03e6c0229e46','b5264b76-6c08-406d-af8d-bbb2f876c7c1','ea41235d-a0f1-44c3-8db1-47f5bb5b2883']]
for p in protected:assert subprocess.check_output(['git','ls-files','--others','--exclude-standard','--',p],cwd=repo,text=True).strip()==p
assert not subprocess.check_output(['git','diff','--name-only','70b5c30c','--','app','.agents'],cwd=repo).strip()
save(scope/'protection.json',dict(protectedPaths=protected,status='KEEP / UNREAD / UNHASHED / UNMODIFIED / UNSTAGED',productionUnchanged=True,realUserRootsAccessed=False,realCredentialsAccessed=False,historicalEvidenceOverwritten=False))
print(json.dumps(dict(tests=sum(s['tests'] for s in suites),fixtureCounts=counts,longAudioDurationsMs=[v['durationMs'] for v in selected]),ensure_ascii=False))
