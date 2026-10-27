from pathlib import Path
import json,hashlib,subprocess,os
b=Path(__file__).resolve().parent
repo=b.parents[3]
old=b.parent/'p11-unified-acceptance-prep-20261021'
image=b.parent/'p11-gui-r1-20261022/windows-frozen-d95501f8/runtime-image'
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for chunk in iter(lambda:f.read(1048576),b''):h.update(chunk)
 return h.hexdigest()
def save(n,v):(b/n).write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()=='80cdaa9e15191ecb5c4ff20db8682b687e9a6ec6'
manifest=image.parent/'package-manifest.json'
assert sha(manifest)=='4732d1dd9f4de95f6af654b120c210b19405a9e83b887ff937e6ff826dfe6b91'
entries=json.loads(manifest.read_text());assert len(entries)==217
assert {p.relative_to(image).as_posix() for p in image.rglob('*') if p.is_file()}=={e['path'] for e in entries}
for e in entries:
 p=image/e['path'];assert p.stat().st_size==e['size'] and sha(p)==e['sha256'],e['path']
source=old/'materials/manual-ready.cbbackup'
entry=next(e for e in json.loads((old/'fixture-manifest.json').read_text())['files'] if e['path']=='materials/manual-ready.cbbackup')
assert source.stat().st_size==entry['size'] and sha(source)==entry['sha256']
save('package-and-material-check.json',dict(packageFiles=217,manifestSha256=sha(manifest),material=entry,status='PASS'))
for d in ['profile/Local','profile/Roaming','synthetic-home/Desktop','temp','exports/png']:(b/d).mkdir(parents=True,exist_ok=True)
env=dict(os.environ)
for k in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS']:env.pop(k,None)
for k,v in [('LOCALAPPDATA','profile/Local'),('APPDATA','profile/Roaming'),('USERPROFILE','synthetic-home'),('TEMP','temp'),('TMP','temp')]:env[k]=str(b/v)
env['JAVA_TOOL_OPTIONS']='-Duser.home='+str(b/'synthetic-home')
java=Path(r'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe')
cp=str(image/'app/*')+os.pathsep+str(b)
for args,name in [([str(java.with_name('javac.exe')),'-cp',cp,str(b/'RestoreFixture.java')],'compile'),([str(java),'-Xmx256m','-Djava.awt.headless=true','-cp',cp,'RestoreFixture',str(b/'app-data'),str(source)],'restore')]:
 p=subprocess.run(args,env=env,capture_output=True)
 (b/(name+'-stdout.log')).write_bytes(p.stdout);(b/(name+'-stderr.log')).write_bytes(p.stderr)
 assert p.returncode==0,name
root=b/'app-data'
save('profile/Local/ChatChatBarDesktop.bootstrap.json',dict(formatVersion=1,mode='CUSTOM',customRoot=str(root)))
(root/'desktop-settings.json').write_text(json.dumps(dict(formatVersion=1,automaticBackup=dict(enabled=False,minimumBackupInterval='PT24H',maximumSnapshotCount=7,checkInterval='PT1H'),uiLanguage='ZH_CN',colorStyle='NEUTRAL')))
settings=root/'entities/app_settings.json'
app=json.loads(settings.read_text()) if settings.exists() else {}
app['allowCleartextModelApi']=True
settings.write_text(json.dumps(app),encoding='utf-8')
voices=[json.loads(p.read_text()) for p in (root/'entities/generated_voice_messages').glob('*.json')]
messages={m['id']:m for p in (root/'entities/chat_messages').glob('*.json') if (m:=json.loads(p.read_text()))}
assert len(voices)>=4 and all(v['messageId'] in messages and messages[v['messageId']]['content'].strip() for v in voices)
assert all(Path(v['audioPath']).is_relative_to(root) and Path(v['audioPath']).is_file() for v in voices)
models=[json.loads(p.read_text()) for p in (root/'entities/model_configs').glob('*.json')]
assert models and all(not m['apiKey'] and m['baseUrl']=='http://127.0.0.1:18793/v1' for m in models)
save('fixture-ready.json',dict(root=str(root),voiceCount=len(voices),messageCount=len(messages),durationsMs=[v['durationMs'] for v in voices],httpPermission=True,realCredentials=False,officialRestore=True,status='PASS'))
save('authorization.json',dict(maximumLaunches=1,authorized=True,consumed=False,source='User GUI R1 Windows execution order 2026-10-22',controlHead='80cdaa9e15191ecb5c4ff20db8682b687e9a6ec6'))
print('PACKAGE_217_AND_NEW_FIXTURE_PASS')
