from pathlib import Path
import os,subprocess,json,time,shutil,datetime,hashlib
G=Path(__file__).resolve().parent
JAVA=Path(r"C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin/java.exe")
CP=str(G/"support-classes")+os.pathsep+str(G/"runtime-image/app/*")
PROFILE=G/"environments/t2-profile";ROOT=PROFILE/"ChatChatBarDesktop"
ENV=dict(os.environ);ENV["LOCALAPPDATA"]=str(PROFILE)
for key in ["JAVA_TOOL_OPTIONS","_JAVA_OPTIONS","JDK_JAVA_OPTIONS"]:ENV.pop(key,None)
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def write(path,obj):
 tmp=path.with_suffix(path.suffix+".tmp");tmp.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+"\n",encoding="utf-8");os.replace(tmp,path)
def status(state,**kwargs):write(G/"manual-status.json",dict(state=state,utc=now(),supervisorPid=os.getpid(),realFishRequests=0,root="environments/t2-profile/ChatChatBarDesktop",**kwargs))
def java(mode,*args):return [str(JAVA),"-cp",CP,"R5T2Support",mode,*map(str,args)]
def logged(name,args,env):
 with (G/(name+".stdout.log")).open("wb") as out,(G/(name+".stderr.log")).open("wb") as err:
  p=subprocess.Popen(args,env=env,stdout=out,stderr=err)
  try:rc=p.wait(timeout=90)
  except subprocess.TimeoutExpired:status("SETUP_PROCESS_TIMEOUT_NO_FORCE_KILL",ownedPid=p.pid);raise RuntimeError("Owned setup process not confirmed exited")
 write(G/(name+".result.json"),dict(exitCode=rc,utc=now()))
 if rc:raise RuntimeError("Synthetic setup rejected")
def inventory(name):
 rows=[]
 for p in sorted(ROOT.rglob("*")):
  if p.is_file():rows.append(dict(path=p.relative_to(ROOT).as_posix(),bytes=p.stat().st_size,sha256=hashlib.sha256(p.read_bytes()).hexdigest()))
 write(G/name,dict(scope="isolated synthetic data root only; credential store excluded",files=rows,utc=now()))
mock=None;watch=None;safe=True
try:
 status("PREPARING");PROFILE.mkdir(parents=True)
 logged("synthetic-seed",java("seed",ROOT),ENV);inventory("before-first-launch-inventory.json")
 mo=(G/"mock.stdout.log").open("wb");me=(G/"mock.stderr.log").open("wb")
 mock=subprocess.Popen(java("mock",G/"mock-service"),env=ENV,stdin=subprocess.PIPE,stdout=mo,stderr=me)
 write(G/"mock-process.json",dict(pid=mock.pid,startedUtc=now(),purpose="loopback synthetic service",realRequests=0))
 deadline=time.monotonic()+90
 while not (G/"mock-service/ready.json").exists():
  if mock.poll() is not None or time.monotonic()>deadline:raise RuntimeError("Synthetic service readiness failed")
  time.sleep(.25)
 port=json.loads((G/"mock-service/ready.json").read_text(encoding="utf-8"))["port"]
 ENV["JAVA_TOOL_OPTIONS"]='-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort='+str(port)+' -Djavax.net.ssl.trustStore="'+str(G/"mock-service/public-trust.jks")+'" -Djdk.net.unixdomain.tmpdir="'+str(G/"uds")+'"'
 logged("local-fixture-readiness",java("validate",ROOT),ENV)
 runid=0;launch=True;finish=False
 while not finish:
  if launch:
   runid+=1;rid="manual%02d"%runid
   wo=(G/(rid+".watch.stdout.log")).open("wb");we=(G/(rid+".watch.stderr.log")).open("wb")
   watch=subprocess.Popen([shutil.which("pwsh"),"-NoProfile","-File",str(G/"runtime-watch.ps1"),"-RunId",rid,"-ImageId","runtime-image","-LocalId","t2-profile"],env=ENV,stdout=wo,stderr=we)
   status("RUNNING_AWAIT_USER_VISUAL_CONFIRMATION",run=rid,watchPid=watch.pid);launch=False;safe=False
  if mock.poll() is not None:status("FAKE_SERVICE_EXITED_DO_NOT_CONTINUE",run=rid);break
  if watch is not None and watch.poll() is not None:
   wo.close();we.close();result=G/"runs"/rid/"process-result.json"
   if not result.exists() or not json.loads(result.read_text(encoding="utf-8-sig")).get("allOwnedProcessesExited"):
    status("PROCESS_LIFETIME_BLOCKED",run=rid);break
   logged(rid+"-lock-reacquire",java("lock",ROOT),ENV);inventory(rid+"-post-exit-inventory.json")
   watch=None;safe=True;status("READY_FOR_REOPEN_OR_FINISH",run=rid,allOwnedProcessesExited=True,rootLockReacquired=True)
  for action in ["launch","finish"]:
   marker=G/"control"/(action+".json")
   if marker.exists():
    marker.unlink()
    with (G/"control-events.jsonl").open("a",encoding="utf-8") as f:f.write(json.dumps(dict(action=action,utc=now(),accepted=safe))+"\n")
    if safe:
     if action=="finish":finish=True
     else:launch=True
  time.sleep(.5)
except Exception as error:status("PREPARATION_OR_LIFETIME_BLOCKED",errorType=type(error).__name__)
finally:
 if safe:
  if mock is not None and mock.poll() is None:
   mock.stdin.write(b"\n");mock.stdin.flush();mock.stdin.close()
   try:mock.wait(timeout=45)
   except subprocess.TimeoutExpired:status("MOCK_CLEANUP_BLOCKED_NO_FORCE_KILL")
  write(G/"mock-final.json",dict(utc=now(),exited=mock is None or mock.poll() is not None,exitCode=None if mock is None else mock.poll(),privateKeyRemains=(G/"mock-service/ephemeral-private.p12").exists(),publicTrustRemains=(G/"mock-service/public-trust.jks").exists()))
  if mock is not None and mock.poll() is not None:status("CLOSED_NO_APP_OR_SERVICE")
 else:
  status("BLOCKED_RETAINING_SERVICE_FOR_OWNED_APP")
  while mock is not None and mock.poll() is None:time.sleep(5)
