from pathlib import Path
import os,subprocess,json,time,shutil,datetime,hashlib
G=Path(__file__).resolve().parent
JAVA=Path(r"C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin/java.exe")
CP=str(G/"support-classes")+os.pathsep+str(G/"runtime-image/app/*")
PROFILE=G/"environments/f2-profile";ROOT=PROFILE/"ChatChatBarDesktop"
ENV=dict(os.environ);ENV["LOCALAPPDATA"]=str(PROFILE)
for key in ["JAVA_TOOL_OPTIONS","_JAVA_OPTIONS","JDK_JAVA_OPTIONS"]:ENV.pop(key,None)
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def write(path,obj):
 tmp=path.with_suffix(path.suffix+".tmp");tmp.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+"\n",encoding="utf-8");os.replace(tmp,path)
def status(state,**kw):write(G/"manual-status.json",dict(state=state,utc=now(),supervisorPid=os.getpid(),realFishRequestsIncrement=0,**kw))
def java(mode,*args):return [str(JAVA),"-Xmx128m","-cp",CP,"F2UiSupport",mode,*map(str,args)]
def logged(name,args,expected=0):
 with (G/(name+".stdout.log")).open("wb") as out,(G/(name+".stderr.log")).open("wb") as err:
  p=subprocess.Popen(args,env=ENV,stdout=out,stderr=err)
  try:rc=p.wait(timeout=90)
  except subprocess.TimeoutExpired:raise RuntimeError("Owned helper exit unconfirmed; no kill")
 write(G/(name+".result.json"),dict(exitCode=rc,expected=expected,utc=now()))
 if rc!=expected:raise RuntimeError("Synthetic helper rejected")
def cards():
 return {p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in ROOT.glob('entities/character_cards/f2-*.json')}
mock=None;watch=None;safe=True
try:
 status("PREPARING");PROFILE.mkdir(parents=True,exist_ok=True);(G/"uds").mkdir(exist_ok=True);(G/"preview-temp").mkdir(exist_ok=True)
 logged("synthetic-seed",java("seed",ROOT))
 write(G/"before-synthetic-cards.json",dict(files=cards()))
 mo=(G/"mock.stdout.log").open("wb");me=(G/"mock.stderr.log").open("wb")
 mock=subprocess.Popen(java("mock",G/"mock-service"),env=ENV,stdin=subprocess.PIPE,stdout=mo,stderr=me)
 write(G/"mock-process.json",dict(pid=mock.pid,createdUtc=now(),realRequests=0))
 deadline=time.monotonic()+90
 while not (G/"mock-service/ready.json").exists():
  if mock.poll() is not None or time.monotonic()>deadline:raise RuntimeError("Fake readiness failed")
  time.sleep(.25)
 port=json.loads((G/"mock-service/ready.json").read_text())["port"]
 ENV["JAVA_TOOL_OPTIONS"]='-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort='+str(port)+' -Djavax.net.ssl.trustStore="'+str(G/"mock-service/public-trust.jks")+'" -Djava.io.tmpdir="'+str(G/"preview-temp")+'" -Djdk.net.unixdomain.tmpdir="'+str(G/"uds")+'"'
 wo=(G/"manual01.watch.stdout.log").open("wb");we=(G/"manual01.watch.stderr.log").open("wb")
 watch=subprocess.Popen([shutil.which("pwsh"),"-NoProfile","-File",str(G/"runtime-watch.ps1"),"-RunId","manual01","-ImageId","runtime-image","-LocalId","f2-profile"],env=ENV,stdout=wo,stderr=we)
 safe=False;status("RUNNING_AWAIT_USER",watchPid=watch.pid)
 while watch.poll() is None:
  if mock.poll() is not None:raise RuntimeError("Fake exited while app may be live")
  time.sleep(.5)
 wo.close();we.close()
 result=json.loads((G/"runs/manual01/process-result.json").read_text(encoding="utf-8-sig"))
 if not result.get("allOwnedProcessesExited"):raise RuntimeError("Owned app exit unconfirmed")
 safe=True
 logged("post-exit-lock",java("lock",ROOT))
 write(G/"after-synthetic-cards.json",dict(files=cards()))
 preview=[dict(path=x.relative_to(G/"preview-temp").as_posix(),bytes=x.stat().st_size) for x in (G/"preview-temp").rglob('*') if x.is_file() and x.suffix in ['.mp3','.part']]
 write(G/"post-exit-preview-files.json",dict(files=preview))
 status("APP_EXITED_LOCK_REACQUIRED",previewFiles=len(preview))
except Exception as e:status("PREPARATION_OR_LIFETIME_BLOCKED",errorType=type(e).__name__)
finally:
 if safe:
  if mock is not None and mock.poll() is None:
   mock.stdin.write(b"\n");mock.stdin.flush();mock.stdin.close()
   try:mock.wait(timeout=45)
   except subprocess.TimeoutExpired:status("MOCK_DRAIN_BLOCKED_NO_FORCE_KILL")
  write(G/"mock-final.json",dict(utc=now(),exitCode=None if mock is None else mock.poll(),exited=mock is None or mock.poll() is not None,privateKeyRemains=(G/"mock-service/ephemeral-private.p12").exists(),trustRemains=(G/"mock-service/public-trust.jks").exists()))
  if mock is not None and mock.poll() is not None:status("CLOSED_NO_APP_OR_SERVICE")
 else:
  status("BLOCKED_RETAINING_FAKE_FOR_OWNED_APP")
  while mock is not None and mock.poll() is None:time.sleep(5)
