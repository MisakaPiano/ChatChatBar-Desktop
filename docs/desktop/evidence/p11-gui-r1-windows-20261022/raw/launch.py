from pathlib import Path
import os,json,subprocess,datetime,importlib.util,threading,urllib.request,time,ctypes
b=Path(__file__).resolve().parent
old=b.parent/'p11-unified-acceptance-prep-20261021'
image=b.parent/'p11-gui-r1-20261022/windows-frozen-d95501f8/runtime-image'
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def save(n,v):(b/n).write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
assert json.loads((b/'fixture-ready.json').read_text())['status']=='PASS'
a=json.loads((b/'authorization.json').read_text());assert a['authorized'] and not a['consumed']
env=dict(os.environ)
for k in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS']:env.pop(k,None)
for k,v in [('LOCALAPPDATA','profile/Local'),('APPDATA','profile/Roaming'),('USERPROFILE','synthetic-home'),('TEMP','temp'),('TMP','temp')]:env[k]=str(b/v)
env['JAVA_TOOL_OPTIONS']='-Duser.home='+str(b/'synthetic-home')
java=Path(r'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe')
probe=subprocess.run([str(java),'-cp',str(b),'RootLockProbe',str(b/'app-data')],capture_output=True,env=env)
save('lock-before.json',dict(exitCode=probe.returncode,result=probe.stdout.decode().strip()));assert probe.returncode==0
spec=importlib.util.spec_from_file_location('fake',old/'offline-api.py');fake=importlib.util.module_from_spec(spec);spec.loader.exec_module(fake)
class Logged(fake.Handler):
 def do_GET(self):
  try:super().do_GET()
  finally:save('fake-events.json',fake.events)
 def do_POST(self):
  try:super().do_POST()
  finally:save('fake-events.json',fake.events)
server=fake.ThreadingHTTPServer(('127.0.0.1',18793),Logged)
threading.Thread(target=server.serve_forever,daemon=True).start()
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
ready=json.load(opener.open('http://127.0.0.1:18793/health',timeout=5));assert ready['forwarding'] is False
save('fake-ready.json',dict(time=now(),pid=os.getpid(),address=server.server_address,health=ready))
save('authorization-before-launch.json',a)
with (b/'launch-once.guard').open('x') as f:f.write(now())
a.update(consumed=True,consumedAt=now());save('authorization.json',a)
with (b/'launcher-stdout.log').open('xb') as out,(b/'launcher-stderr.log').open('xb') as err:
 app=subprocess.Popen([str(image/'ChatChatBarDesktop.exe')],cwd=image,env=env,stdout=out,stderr=err)
 save('launch.json',dict(time=now(),launcherPid=app.pid,supervisorPid=os.getpid(),executable=str(image/'ChatChatBarDesktop.exe'),root=str(b/'app-data'),isolatedHome=env['USERPROFILE'],localAppData=env['LOCALAPPDATA']))
 # Retain native child handles to obtain real JVM exit codes after normal shutdown.
 k=ctypes.WinDLL('kernel32',use_last_error=True);k.OpenProcess.restype=ctypes.c_void_p
 k.GetExitCodeProcess.argtypes=[ctypes.c_void_p,ctypes.POINTER(ctypes.c_ulong)]
 k.CloseHandle.argtypes=[ctypes.c_void_p]
 children=[]
 for attempt in range(20):
  result=subprocess.run(['powershell','-NoProfile','-Command',f"Get-CimInstance Win32_Process -Filter 'ParentProcessId={app.pid}' | Select-Object ProcessId,Name | ConvertTo-Json -Compress"],capture_output=True,creationflags=0x08000000)
  try:data=json.loads(result.stdout.decode('utf-8-sig'));data=data if isinstance(data,list) else [data]
  except Exception:data=[]
  for item in data:
   if item['Name'].lower() in ['java.exe','javaw.exe']:
    handle=k.OpenProcess(0x1000,False,item['ProcessId']);children.append((item,handle))
  if children or app.poll() is not None:break
  time.sleep(1)
 save('jvm-observed.json',[dict(**item,handleAcquired=bool(handle)) for item,handle in children])
 code=app.wait();save('launcher-exit.json',dict(time=now(),pid=app.pid,exitCode=code))
 for item,handle in children:
  value=ctypes.c_ulong(259)
  if handle:
   for attempt in range(60):
    if not k.GetExitCodeProcess(handle,ctypes.byref(value)):break
    if value.value!=259:break
    time.sleep(1)
   k.CloseHandle(handle)
  item['exitCode']=value.value if handle and value.value!=259 else 'UNKNOWN'
 save('jvm-exit.json',[item for item,_ in children] or [dict(exitCode='UNKNOWN',reason='No child handle observed')])
server.shutdown();server.server_close();save('fake-stopped.json',dict(time=now(),events=fake.events,forwardedRequests=0))
