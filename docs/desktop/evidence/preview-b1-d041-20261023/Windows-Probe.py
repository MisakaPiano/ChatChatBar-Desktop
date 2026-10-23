"""Local app-image probe only. Never invokes an installer or reads a non-synthetic root."""
import base64, hashlib, json, os, subprocess, sys, time, wave, zipfile
from pathlib import Path

REPO=Path(__file__).resolve().parents[4]
BASE=REPO/'app/desktopApp/build/preview-b1'
EVIDENCE=Path(__file__).resolve().parent/'raw'
PROFILE=BASE/'isolated'
DATA=PROFILE/'data'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def inventory(root):return {p.relative_to(root).as_posix():{'sha256':sha(p),'bytes':p.stat().st_size} for p in sorted(root.rglob('*')) if p.is_file()}
def save(path,obj):path.write_text(json.dumps(obj,indent=2)+'\n',encoding='utf-8')

def seed():
    PROFILE.mkdir(exist_ok=False)
    for name in ['LocalAppData','Roaming','home','temp','data']: (PROFILE/name).mkdir()
    save(PROFILE/'LocalAppData/ChatChatBarDesktop.bootstrap.json',{'formatVersion':1,'mode':'CUSTOM','customRoot':str(DATA)})
    values={
      'entities/character_cards/b1c.json':{'id':'b1c','name':'B1 synthetic card','createdAt':1,'updatedAt':1},
      'entities/chat_sessions/b1s.json':{'id':'b1s','characterCardId':'b1c','title':'B1 synthetic session','createdAt':1,'updatedAt':1},
      'entities/chat_messages/b1m.json':{'id':'b1m','sessionId':'b1s','role':'USER','content':'B1 synthetic preservation text','images':[str(DATA/'images/b1.png')],'createdAt':1,'updatedAt':1},
      'entities/chat_drafts/b1s.json':{'sessionId':'b1s','content':'B1 synthetic draft','updatedAt':1},
      'entities/save_slots/b1a.json':{'id':'b1a','sessionId':'b1s','name':'B1 synthetic archive','createdAt':1},
      'entities/player_setting.json':{'id':'default','playerName':'B1 synthetic player','globalPersona':'B1 synthetic persona','updatedAt':1}}
    for name,value in values.items():p=DATA/name;p.parent.mkdir(parents=True,exist_ok=True);save(p,value)
    (DATA/'images').mkdir();(DATA/'images/b1.png').write_bytes(base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aJ1sAAAAASUVORK5CYII='))
    (DATA/'audio').mkdir()
    with wave.open(str(DATA/'audio/b1.wav'),'wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(8000);w.writeframes(b'\0'*16000)
    secret=PROFILE/'LocalAppData/ChatChatBarDesktopCredentials';secret.mkdir()
    (secret/'SYNTHETIC-OPAQUE-SENTINEL.bin').write_bytes(b'B1-NOT-A-CREDENTIAL-NOT-DPAPI')
    save(EVIDENCE/'synthetic-inputs.json',{'data':inventory(DATA),'bootstrap':sha(PROFILE/'LocalAppData/ChatChatBarDesktop.bootstrap.json'),'opaqueNonCredentialSentinel':sha(secret/'SYNTHETIC-OPAQUE-SENTINEL.bin')})

def run(number):
    package=BASE/f'build-{number}/compose/binaries/main/app/ChatChatBarDesktop'
    out=EVIDENCE/f'windows-{number}';out.mkdir(exist_ok=False)
    save(out/'package-manifest.json',inventory(package))
    identities=[]
    for jar in (package/'app').glob('*.jar'):
        with zipfile.ZipFile(jar) as z:
            if 'desktop-build.properties' in z.namelist():identities.append({'jar':jar.name,'properties':z.read('desktop-build.properties').decode()})
    assert len(identities)==1 and f'version=0.1.0-preview.{number}' in identities[0]['properties'] and 'development' not in identities[0]['properties']
    save(out/'identity.json',identities)
    save(out/'before.json',inventory(DATA))
    env=os.environ.copy()
    env.update({'LOCALAPPDATA':str(PROFILE/'LocalAppData'),'APPDATA':str(PROFILE/'Roaming'),'USERPROFILE':str(PROFILE/'home'),
                'HOME':str(PROFILE/'home'),'TEMP':str(PROFILE/'temp'),'TMP':str(PROFILE/'temp'),
                'JAVA_TOOL_OPTIONS':'-Duser.home='+str(PROFILE/'home'),'CCB_SHUTDOWN_TRACE':'1'})
    for n in ['JDK_JAVA_OPTIONS','_JAVA_OPTIONS']:env.pop(n,None)
    with (out/'stdout.log').open('wb') as stdout,(out/'stderr.log').open('wb') as stderr:
        p=subprocess.Popen([str(package/'ChatChatBarDesktop.exe')],cwd=package,env=env,stdout=stdout,stderr=stderr)
        save(out/'launch.json',{'pid':p.pid,'executable':str(package/'ChatChatBarDesktop.exe'),'selectedSyntheticRoot':str(DATA),'credentials':'EMPTY_EXCEPT_OPAQUE_NONCREDENTIAL_SENTINEL','version':number})
        print('LAUNCHED',number,p.pid,flush=True)
        code=p.wait() # UI normal-close only; no killing an unrecognized process.
    save(out/'exit.json',{'launcherExitCode':code,'dataAfter':inventory(DATA)})
    print('EXIT',number,code,flush=True)

if __name__=='__main__':
    if sys.argv[1]=='seed':seed()
    else:run(sys.argv[1])
