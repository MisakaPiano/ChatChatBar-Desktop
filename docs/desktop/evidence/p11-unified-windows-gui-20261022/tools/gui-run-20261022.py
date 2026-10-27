from pathlib import Path
import os,sys,json,hashlib,subprocess,datetime,importlib.util,threading,urllib.request,time
base=Path(__file__).resolve().parent
repo=base.parents[3]
run=base/'gui-run-20261022'
image=repo/'app/desktopApp/build/p11-review-fixes-20261021/windows-frozen-70b5c30c/runtime-image'
root=base/'environments/manual-ready/app-data'
java=Path(r'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe')
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def save(name,value):(run/name).write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
env=dict(os.environ)
for k in ['JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS']:env.pop(k,None)
for k,v in [('LOCALAPPDATA','profile/Local'),('APPDATA','profile/Roaming'),('TEMP','temp'),('TMP','temp'),('USERPROFILE','synthetic-home')]:env[k]=str(base/v)
env['JAVA_TOOL_OPTIONS']='-Duser.home='+str(base/'synthetic-home')
cp=str(image/'app/*')+os.pathsep+str(run)
if sys.argv[1:] == ['--check']:
 run.mkdir(exist_ok=False)
 assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()=='37c67c724fe21107d7eaff0306439481c6f2bf07'
 assert not subprocess.check_output(['git','diff','--name-only','70b5c30c','--','app','.agents'],cwd=repo).strip()
 manifest=image.parent/'package-manifest.json'
 assert sha(manifest)=='841924831a065c080e502860bdd4631a9d919975438414b8b474b16ed4add71c'
 entries=json.loads(manifest.read_text());assert len(entries)==217
 assert {p.relative_to(image).as_posix() for p in image.rglob('*') if p.is_file()}=={e['path'] for e in entries}
 for e in entries:
  p=image/e['path'];assert p.stat().st_size==e['size'] and sha(p)==e['sha256'],e['path']
 checked=0
 for e in json.loads((base/'fixture-manifest.json').read_text())['files']:
  if not e['path'].startswith('environments/manual-ready/'):continue
  p=Path('\\\\?\\'+str(base/e['path']));assert p.stat().st_size==e['size'] and sha(p)==e['sha256'],e['path'];checked+=1
 assert json.loads((base/'profile/Local/ChatChatBarDesktop.bootstrap.json').read_text())['customRoot']==str(root)
 for p in (root/'entities/model_configs').glob('*.json'):
  m=json.loads(p.read_text(encoding='utf-8-sig'));assert not m['apiKey'] and m['baseUrl'].startswith('http://127.0.0.1:18793/')
 assert not json.loads((root/'desktop-settings.json').read_text())['automaticBackup']['enabled']
 a=json.loads((base/'launch-admission.json').read_text());assert not a['consumed'] and not a['guiAuthorized']
 save('admission-before.json',a)
 source='''import java.nio.file.*;import com.example.chatbar.desktop.security.*;public class CredentialCheck {public static void main(String[] a)throws Exception {Path b=Path.of(a[0]);if(!Path.of(System.getenv("LOCALAPPDATA")).equals(b.resolve("profile/Local")))throw new Exception("ISOLATION");String v=WindowsSecretStore.Companion.create(b.resolve("environments/manual-ready/app-data")).load(new DesktopCredentialKey.ModelApiKey("unified-offline"));if(v==null||!v.startsWith("synthetic-offline-only-"))throw new Exception("NOT_SYNTHETIC");System.out.println("PROTECTED_SYNTHETIC_CREDENTIAL_OK");}}'''
 (run/'CredentialCheck.java').write_text(source)
 subprocess.run([str(java.with_name('javac.exe')),'-cp',str(image/'app/*'),str(run/'CredentialCheck.java')],check=True,capture_output=True)
 c=subprocess.run([str(java),'-cp',cp,'CredentialCheck',str(base)],env=env,capture_output=True,text=True);assert c.returncode==0,'CREDENTIAL_CHECK_FAILED';save('credential-check.json',dict(exitCode=c.returncode,status=c.stdout.strip(),credentialValueRecorded=False))
 p=subprocess.run([str(java),'-cp',str(base/'support-classes'),'RootLockProbe'],env=env,capture_output=True,text=True);save('lock-before.json',dict(exitCode=p.returncode,stdout=p.stdout,stderr=p.stderr));assert p.returncode==0
 a.update(guiAuthorized=True,authorization='User execution order 2026-10-22; one frozen GUI and loopback fake only',controlHead='37c67c724fe21107d7eaff0306439481c6f2bf07',authorizedAt=now())
 (base/'launch-admission.json').write_text(json.dumps(a,indent=2))
 save('preflight.json',dict(time=now(),packageFiles=217,syntheticFilesChecked=checked,root=str(root),profile=env['LOCALAPPDATA'],status='PASS'))
 print('PREFLIGHT_PASS',checked,flush=True)
elif sys.argv[1:] == ['--launch']:
 assert json.loads((run/'preflight.json').read_text())['status']=='PASS'
 a=json.loads((base/'launch-admission.json').read_text());assert a['guiAuthorized'] and not a['consumed']
 spec=importlib.util.spec_from_file_location('fake',base/'offline-api.py');fake=importlib.util.module_from_spec(spec);spec.loader.exec_module(fake)
 class Logged(fake.Handler):
  def do_GET(self):
   try:super().do_GET()
   finally:save('fake-events.json',fake.events)
  def do_POST(self):
   try:super().do_POST()
   finally:save('fake-events.json',fake.events)
 server=fake.ThreadingHTTPServer(('127.0.0.1',18793),Logged)
 threading.Thread(target=server.serve_forever,daemon=True).start()
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}));ready=json.load(opener.open('http://127.0.0.1:18793/health',timeout=5));assert ready['forwarding'] is False
 save('fake-ready.json',dict(time=now(),pid=os.getpid(),address=server.server_address,readiness=ready))
 with (run/'launch-once.guard').open('x') as f:f.write(now())
 a.update(consumed=True,consumedAt=now());(base/'launch-admission.json').write_text(json.dumps(a,indent=2))
 with (run/'launcher-stdout.log').open('xb') as out,(run/'launcher-stderr.log').open('xb') as err:
  app=subprocess.Popen([str(image/'ChatChatBarDesktop.exe')],cwd=image,env=env,stdout=out,stderr=err)
  save('launch.json',dict(time=now(),pid=app.pid,supervisorPid=os.getpid(),executable=str(image/'ChatChatBarDesktop.exe'),root=str(root),localAppData=env['LOCALAPPDATA']))
  print('LAUNCHED',app.pid,flush=True)
  code=app.wait();save('launcher-exit.json',dict(time=now(),pid=app.pid,exitCode=code))
 server.shutdown();server.server_close();save('fake-stopped.json',dict(time=now(),events=fake.events,forwardedRequests=0))
 print('EXIT',code,flush=True)
else:raise SystemExit('Use --check or --launch')
