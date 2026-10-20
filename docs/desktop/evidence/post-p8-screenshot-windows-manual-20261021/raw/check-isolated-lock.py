from pathlib import Path
import os,subprocess,time,json,datetime
S=Path(__file__).resolve().parent
JAVA=Path(r'C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin/java.exe')
IMAGE=Path('H:/ChatChatBar-Desktop/app/desktopApp/build/post-p8-screenshot-r2-boundary-20261021/windows-frozen-c38bcf27/runtime-image')
ROOT=S/'environments/post-p8-profile/ChatChatBarDesktop'
CP=str(S/'support-classes')+os.pathsep+str(IMAGE/'app/*')
env=dict(os.environ);env['LOCALAPPDATA']=str(ROOT.parent)
for k in ['JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS']:env.pop(k,None)
def command(cls,*args):return [str(JAVA),'-Xmx96m','-Duser.home='+str(S/'synthetic-home'),'-cp',CP,cls,*map(str,args)]
def probe(label,expected):
 cmd=command('P8S7Support','lock',ROOT)
 with (S/'preparation'/f'{label}.stdout.log').open('xb') as out,(S/'preparation'/f'{label}.stderr.log').open('xb') as err:
  p=subprocess.Popen(cmd,env=env,stdout=out,stderr=err)
  try:rc=p.wait(timeout=30)
  except subprocess.TimeoutExpired:
   (S/'preparation'/f'{label}.result.json').write_text(json.dumps({'pid':p.pid,'exit':'UNCONFIRMED_NO_KILL'}));raise
 (S/'preparation'/f'{label}.result.json').write_text(json.dumps({'command':cmd,'exitCode':rc,'expected':expected}))
 assert rc==expected
probe('lock-before-holder',0)
with (S/'preparation/lock-holder.stdout.log').open('xb') as out,(S/'preparation/lock-holder.stderr.log').open('xb') as err:
 holder=subprocess.Popen(command('RootLockHold',S),env=env,stdin=subprocess.PIPE,stdout=out,stderr=err)
 try:
  deadline=time.monotonic()+30
  while not (S/'preparation/lock-holder-ready.json').exists():
   if holder.poll() is not None or time.monotonic()>deadline:raise RuntimeError('holder readiness unconfirmed')
   time.sleep(.05)
  probe('lock-held-independent-probe',75)
 finally:
  if holder.poll() is None:holder.stdin.write(b'\n');holder.stdin.flush();holder.stdin.close()
  try:rc=holder.wait(timeout=30)
  except subprocess.TimeoutExpired:
   (S/'preparation/lock-holder.result.json').write_text(json.dumps({'pid':holder.pid,'exit':'UNCONFIRMED_NO_KILL'}));raise
  (S/'preparation/lock-holder.result.json').write_text(json.dumps({'pid':holder.pid,'exitCode':rc,'releasedBy':'cooperative stdin signal; no kill'}))
 assert rc==0
probe('lock-after-holder',0)
print('INDEPENDENT_ROOT_LOCK_PRECHECK_PASS free=0 held=75 released=0; no GUI/fake')
