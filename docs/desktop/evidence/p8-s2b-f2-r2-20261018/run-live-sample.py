from pathlib import Path
import subprocess,os,json,datetime
G=Path('app/desktopApp/build/p8-s2b-f2-r2-20261018').resolve();basis=json.loads((G/'live-source-basis.json').read_text(encoding='utf-8'));assert basis['chosen'] is not None
assert json.loads((G/'helper-compile.json').read_text(encoding='utf-8-sig'))['exitCode']==0
assert not (G/'live-sample-result.json').exists()
env=dict(os.environ)
for k in ['JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS']:env.pop(k,None)
(G/'live-temp').mkdir(exist_ok=True)
java='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin/java.exe'
args=[java,'-Xmx128m','-Djava.io.tmpdir='+str(G/'live-temp'),'-cp',str(G/'support-classes')+os.pathsep+str(G/'runtime-image/app/*'),'F2SampleLiveSupport',str(G),basis['chosen']['url']]
now=lambda:datetime.datetime.now(datetime.timezone.utc).isoformat()
with (G/'live-sample.stdout.log').open('wb') as out,(G/'live-sample.stderr.log').open('wb') as err:
 p=subprocess.Popen(args,env=env,stdout=out,stderr=err)
 (G/'live-sample-process.json').write_text(json.dumps({'pid':p.pid,'observedStartUtc':now(),'purpose':'one anonymous official sample GET; no credential access','TTS':0},indent=2)+'\n',encoding='utf-8')
 try:rc=p.wait(timeout=180)
 except subprocess.TimeoutExpired:raise RuntimeError('Owned live helper exit unconfirmed; no force kill or retry')
 (G/'live-sample-exit.json').write_text(json.dumps({'exitCode':rc,'exited':True,'utc':now()},indent=2)+'\n',encoding='utf-8')
 print((G/'live-sample-result.json').read_text(encoding='utf-8'))
 if rc:raise RuntimeError('Live gate stopped; no retry')
