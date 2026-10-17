from pathlib import Path
import os,subprocess,json,time,shutil
G=Path(__file__).resolve().parent
java=Path(r"C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin/java.exe")
cp=str(G/"support-classes")+os.pathsep+str(G/"runtime-image/app/*")
profile=G/"environments/r5-profile";profile.mkdir(parents=True)
root=profile/"ChatChatBarDesktop"
env=dict(os.environ);env["LOCALAPPDATA"]=str(profile)
def command(mode,*args, extra=None):return [str(java),*(extra or []),"-cp",cp,"R5T1Support",mode,*map(str,args)]
def logged(name,args,environment,timeout=90):
 with (G/(name+".stdout.log")).open("wb") as out,(G/(name+".stderr.log")).open("wb") as err:
  r=subprocess.run(args,env=environment,stdout=out,stderr=err,timeout=timeout)
 (G/(name+".result.json")).write_text(json.dumps({"exitCode":r.returncode,"purpose":"isolated synthetic validation only"})+"\n")
 if r.returncode:raise RuntimeError(name+" failed")
logged("synthetic-seed",command("seed",root),env)
mocklog=(G/"mock.stdout.log").open("wb");mockerr=(G/"mock.stderr.log").open("wb")
mock=subprocess.Popen(command("mock",G/"mock-service"),env=env,stdin=subprocess.PIPE,stdout=mocklog,stderr=mockerr)
(G/"mock-process.json").write_text(json.dumps({"pid":mock.pid,"owned":True,"purpose":"loopback synthetic Fish only"})+"\n")
try:
 deadline=time.monotonic()+90
 while not (G/"mock-service/ready.json").exists():
  if mock.poll() is not None or time.monotonic()>deadline:raise RuntimeError("Mock fixture readiness failed")
  time.sleep(.25)
 port=json.loads((G/"mock-service/ready.json").read_text())["port"]
 (G/"socket-tmp").mkdir()
 env["JAVA_TOOL_OPTIONS"]='-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort='+str(port)+' -Djavax.net.ssl.trustStore="'+str(G/"mock-service/public-trust.jks")+'" -Djdk.net.unixdomain.tmpdir="'+str(G/"socket-tmp")+'"'
 logged("local-tls-fixture-validation",command("validate",root),env)
 (G/"native-context.json").write_text(json.dumps({"root":"environments/r5-profile/ChatChatBarDesktop","image":"runtime-image","mockOnly":True,"realFishRequests":0,"routing":"process-only HTTP CONNECT loopback and ephemeral public-only truststore; default hostname verification retained","systemConfigurationChanged":False})+"\n")
 # The watcher only observes owned process identity/lifetime; all UI actions use Computer Use.
 rc=subprocess.call([shutil.which("pwsh"),"-NoProfile","-File",str(G/"runtime-watch.ps1"),"-RunId","native01","-ImageId","runtime-image","-LocalId","r5-profile"],env=env)
 (G/"native-watch-result.json").write_text(json.dumps({"exitCode":rc})+"\n")
finally:
 if mock.poll() is None:
  mock.stdin.write(b"\n");mock.stdin.flush();mock.stdin.close()
  try:mock.wait(timeout=45)
  except subprocess.TimeoutExpired:(G/"mock-cleanup-blocked.json").write_text('{"state":"BLOCKED_NO_FORCE_KILL"}')
 mocklog.close();mockerr.close()
 (G/"mock-final.json").write_text(json.dumps({"exited":mock.poll() is not None,"exitCode":mock.poll(),"privateKeyRemains":(G/"mock-service/ephemeral-private.p12").exists(),"publicTrustRemains":(G/"mock-service/public-trust.jks").exists()})+"\n")
