from pathlib import Path
import os,subprocess,json,time,shutil,datetime,hashlib,sys
G=Path(__file__).resolve().parent
JAVA=Path(r"C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin/java.exe")
IMAGE=Path('H:/ChatChatBar-Desktop/app/desktopApp/build/post-p8-screenshot-r2-boundary-20261021/windows-frozen-c38bcf27/runtime-image')
CP=str(G/"support-classes")+os.pathsep+str(IMAGE/"app/*")
PROFILE=G/"environments/post-p8-profile";ROOT=PROFILE/"ChatChatBarDesktop"
# Explicit authorization and single-use launch admission; no GUI is started by importing this file.
if __name__ != "__main__" or sys.argv[1:] != ["--start-screenshot-r2-user-authorized"]:
 raise SystemExit("P8 S7 MANUAL START REQUIRES EXPLICIT USER READY; no process started")
admission=json.loads((G/"launch-admission.json").read_text())
if admission.get("guiAuthorized") is not True or admission.get("maximumLaunches") != 1:
 raise SystemExit("Screenshot R2 user authorization not recorded; no process started")
recheck=json.loads((G/"launch-recheck.json").read_text())
if recheck.get("ready") is not True or (datetime.datetime.now(datetime.timezone.utc)-datetime.datetime.fromisoformat(recheck["utc"])).total_seconds()>120:
 raise SystemExit("Fresh launch recheck required; no process started")
repo=G.parents[3]
if subprocess.check_output(["git","rev-parse","HEAD"],cwd=repo,text=True).strip()!="7cc135b971e0dd509f2f6f387b0ff7cfe2828f9f":
 raise SystemExit("Unexpected Git HEAD; STOP")
fd=os.open(G/"manual-start-once.lock",os.O_CREAT|os.O_EXCL|os.O_WRONLY)
os.write(fd,json.dumps({"fixtureId":admission["fixtureId"],"sourceCommit":"c38bcf274fca2eafb81a27b10cb3fcb16ed94fbe","authorizedOnce":True}).encode());os.close(fd)
ENV=dict(os.environ);ENV["LOCALAPPDATA"]=str(PROFILE)
for key in ["JAVA_TOOL_OPTIONS","_JAVA_OPTIONS","JDK_JAVA_OPTIONS"]:ENV.pop(key,None)
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def write(path,obj):
 tmp=path.with_suffix(path.suffix+".tmp");tmp.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+"\n",encoding="utf-8");os.replace(tmp,path)
def status(state,**kw):write(G/"manual-status.json",dict(state=state,utc=now(),supervisorPid=os.getpid(),phase="FAKE_ONLY",realRequestsThisProcess=0,**kw))
def java(mode,*args):return [str(JAVA),"-Xmx128m","-Duser.home="+str(G/"synthetic-home"),"-cp",CP,"P8S7Support",mode,*map(str,args)]
def logged(name,args,expected=0):
 with (G/(name+".stdout.log")).open("wb") as out,(G/(name+".stderr.log")).open("wb") as err:
  p=subprocess.Popen(args,env=ENV,stdout=out,stderr=err)
  try:rc=p.wait(timeout=90)
  except subprocess.TimeoutExpired:raise RuntimeError("Owned helper exit unconfirmed; no kill")
 write(G/(name+".result.json"),dict(exitCode=rc,expected=expected,utc=now()))
 if rc!=expected:raise RuntimeError("Synthetic helper rejected")
def cards():
 return {p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in ROOT.glob('entities/character_cards/p8s7-*.json')}
mock=None;watch=None;safe=True;failed=False
try:
 manifest=json.loads((G/'distribution-manifest.json').read_text())
 for record in manifest['files']:
  artifact=IMAGE/record['path']
  assert artifact.stat().st_size==record['bytes'] and hashlib.sha256(artifact.read_bytes()).hexdigest()==record['sha256']
 assert ROOT.is_dir() and ROOT.resolve()==ROOT.absolute() and ROOT.is_relative_to(G)
 assert json.loads((PROFILE/"ChatChatBarDesktop.bootstrap.json").read_text()) == {"formatVersion":1,"mode":"DEFAULT"}
 fixture=json.loads((G/"fixture-manifest.json").read_text())
 assert Path(fixture["root"])==ROOT and fixture["disposable"] is True
 assert set(p.relative_to(ROOT).as_posix() for p in ROOT.rglob('*.json'))==set(r['path'] for r in fixture['files'])
 for record in fixture['files']:
  item=ROOT/record['path'];assert item.stat().st_size==record['bytes'] and hashlib.sha256(item.read_bytes()).hexdigest()==record['sha256']
 # Preseeded audio must match this scope's immutable media manifest.
 for record in json.loads((G/"fixture-media-manifest.json").read_text())["files"]:
  item=ROOT/record["path"];assert item.stat().st_size==record["bytes"] and hashlib.sha256(item.read_bytes()).hexdigest()==record["sha256"]
 for record in json.loads((G/'support-manifest.json').read_text())['files']:
  item=G/record['path'];assert item.stat().st_size==record['bytes'] and hashlib.sha256(item.read_bytes()).hexdigest()==record['sha256']
 assert set(x.relative_to(IMAGE).as_posix() for x in (IMAGE).rglob('*') if x.is_file()) == set(x['path'] for x in manifest['files'])
 assert not (G/"mock-service").exists(), "Mock must create its own directory"
 logged("prelaunch-lock",java("lock",ROOT))
 status("PREPARING_EXISTING_ISOLATED_ROOT")
 write(G/"before-synthetic-cards.json",dict(files=cards()))
 mo=(G/"mock.stdout.log").open("wb");me=(G/"mock.stderr.log").open("wb")
 mock=subprocess.Popen(java("mock",G/"mock-service"),env=ENV,stdin=subprocess.PIPE,stdout=mo,stderr=me)
 write(G/"mock-process.json",dict(pid=mock.pid,createdUtc=now(),realRequests=0))
 deadline=time.monotonic()+90
 while not (G/"mock-service/ready.json").exists():
  if mock.poll() is not None or time.monotonic()>deadline:raise RuntimeError("Fake readiness failed")
  time.sleep(.25)
 ready=json.loads((G/"mock-service/ready.json").read_text())
 assert ready["loopbackOnly"] is True and ready["realProviderRequests"] == 0
 port=ready["port"]
 ENV["JAVA_TOOL_OPTIONS"]='-Duser.home="'+str(G/"synthetic-home")+'" -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort='+str(port)+' -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort='+str(port)+' -Djavax.net.ssl.trustStore="'+str(G/"mock-service/public-trust.jks")+'" -Djava.io.tmpdir="'+str(G/"preview-temp")+'" -Djdk.net.unixdomain.tmpdir="'+str(G/"uds")+'"'
 wo=(G/"manual01.watch.stdout.log").open("wb");we=(G/"manual01.watch.stderr.log").open("wb")
 watch=subprocess.Popen([shutil.which("pwsh"),"-NoProfile","-File",str(G/"runtime-watch.ps1"),"-RunId","manual01","-ImageId",str(IMAGE),"-LocalId","post-p8-profile"],env=ENV,stdout=wo,stderr=we)
 safe=False;status("STARTING_WAITING_FOR_WINDOW",watchPid=watch.pid,manualLifetime="User-controlled; no automatic GUI closure")
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
 expectedMainExit=(G/"runs/manual01/close-request.json").exists()
 write(G/"app-exit-classification.json",dict(expectedMainWindowClose=expectedMainExit,classification="USER_MAIN_CLOSE_TECHNICAL_CHECKS" if expectedMainExit else "UNEXPECTED_EXIT_DURING_SCREENSHOT_GATE",exitCodeIsNotUserPass=True))
 if not expectedMainExit:
  failed=True;status("UNEXPECTED_APP_EXIT_DURING_SCREENSHOT_GATE",previewFiles=len(preview))
 else:status("APP_EXITED_LOCK_REACQUIRED",previewFiles=len(preview))
except Exception as e:
 failed=True
 status("PREPARATION_OR_LIFETIME_BLOCKED",errorType=type(e).__name__)
finally:
 if safe:
  if mock is not None and mock.poll() is None:
   mock.stdin.write(b"\n");mock.stdin.flush();mock.stdin.close()
   try:mock.wait(timeout=45)
   except subprocess.TimeoutExpired:status("MOCK_DRAIN_BLOCKED_NO_FORCE_KILL")
  write(G/"mock-final.json",dict(utc=now(),exitCode=None if mock is None else mock.poll(),exited=mock is None or mock.poll() is not None,privateKeyRemains=(G/"mock-service/ephemeral-private.p12").exists(),trustRemains=(G/"mock-service/public-trust.jks").exists()))
  if mock is not None and mock.poll() is not None and not failed:status("CLOSED_NO_APP_OR_SERVICE")
 else:
  status("BLOCKED_RETAINING_FAKE_FOR_OWNED_APP")
  while mock is not None and mock.poll() is None:time.sleep(5)
