from pathlib import Path
import os,json,subprocess,datetime,sys
scope=Path(__file__).resolve().parent;repo=scope.parents[3]
image=repo/'app/desktopApp/build/p11-review-fixes-20261021/windows-frozen-70b5c30c/runtime-image'
cp=str(image/'app/*')+os.pathsep+(scope/'classpath.txt').read_text().strip()
env=dict(os.environ)
for key in ['JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS']:env.pop(key,None)
for key,sub in [('LOCALAPPDATA','profile/Local'),('APPDATA','profile/Roaming'),('TEMP','temp'),('TMP','temp')]:
 p=scope/sub;p.mkdir(parents=True,exist_ok=True);env[key]=str(p)
java=Path(r'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe')
assert sys.argv[1:] in ([], ['--enrich'])
for mode in (['manual-enrich'] if sys.argv[1:] else ['seed-and-integrate','schedule','recover','rollback']):
 stage=scope/('03-'+mode);stage.mkdir()
 args=[str(java),'-Xmx256m','-Djava.awt.headless=true','-Duser.home='+str(scope/'synthetic-home'),'-Xlog:class+load=info:file=class-load.log','-cp',cp,'com.example.chatbar.desktop.UnifiedAcceptanceFixture',mode]
 (stage/'command.json').write_text(json.dumps(args,indent=2),encoding='utf-8')
 started=datetime.datetime.now(datetime.timezone.utc).isoformat()
 with (stage/'stdout.log').open('xb') as out,(stage/'stderr.log').open('xb') as err:
  p=subprocess.Popen(args,cwd=stage,env=env,stdout=out,stderr=err)
  try:code=p.wait(timeout=180)
  except subprocess.TimeoutExpired:
   (stage/'result.json').write_text(json.dumps(dict(pid=p.pid,state='EXIT_UNCONFIRMED_NO_KILL',started=started)),encoding='utf-8');raise
 (stage/'result.json').write_text(json.dumps(dict(pid=p.pid,exitCode=code,started=started,finished=datetime.datetime.now(datetime.timezone.utc).isoformat(),guiStarted=False,installed=False)),encoding='utf-8')
 sources=[line for line in (stage/'class-load.log').read_text().splitlines() if any((' '+name+' source:') in line for name in ['com.example.chatbar.desktop.DesktopSaveSlotService','com.example.chatbar.desktop.DesktopFullBackupService','com.example.chatbar.desktop.DesktopStartupGate'])]
 assert sources and all('windows-frozen-70b5c30c' in line for line in sources),sources
 (stage/'production-class-origins.txt').write_text('\n'.join(sources)+'\n',encoding='utf-8')
 print(mode,code,flush=True)
 assert code==0,mode
