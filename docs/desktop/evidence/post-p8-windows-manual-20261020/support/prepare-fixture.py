from pathlib import Path
import datetime, hashlib, json, os, struct, subprocess, zlib

G = Path(__file__).resolve().parent
R = G.parents[3]
IMAGE = R / 'app/desktopApp/build/post-p8-audio-maintenance-r1-20261020/windows-frozen-694a8bdd/runtime-image'
JAVA = Path(r'C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin/java.exe')
PROFILE = G / 'environments/post-p8-profile'
ROOT = PROFILE / 'ChatChatBarDesktop'
CP = str(G/'support-classes') + os.pathsep + str(IMAGE/'app/*')

def write(path, obj):
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')

def run(name, arguments):
    env = dict(os.environ)
    env['LOCALAPPDATA'] = str(PROFILE)
    for key in ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS']:
        env.pop(key, None)
    command = [str(JAVA), '-Xmx128m', '-Duser.home='+str(G/'synthetic-home'), '-cp', CP, *arguments]
    write(G/'w1'/f'{name}.command.json', {'arguments':command, 'isolatedLocalAppData':str(PROFILE)})
    with (G/'w1'/f'{name}.stdout.log').open('xb') as out, (G/'w1'/f'{name}.stderr.log').open('xb') as err:
        proc = subprocess.Popen(command, env=env, stdout=out, stderr=err)
        try:
            rc = proc.wait(timeout=90)
        except subprocess.TimeoutExpired:
            write(G/'w1'/f'{name}.result.json', {'exitCode':None, 'pid':proc.pid, 'state':'EXIT_UNCONFIRMED_NO_KILL'})
            raise RuntimeError('Owned helper exit unconfirmed; no retry or GUI')
    write(G/'w1'/f'{name}.result.json', {'exitCode':rc})
    if rc:
        raise RuntimeError('Offline helper rejected; preserve logs and STOP')

def png(path, width, height, colors):
    def chunk(kind, data):
        return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
    rows = bytearray()
    for y in range(height):
        rows.append(0)
        for x in range(width):
            color = colors[((x//max(1,width//4))+(y//max(1,height//4)))%len(colors)]
            rows.extend(color)
    path.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(bytes(rows)))+chunk(b'IEND',b''))

if __name__ == '__main__':
    assert G.name == 'post-p8-windows-manual-20261020'
    assert not ROOT.exists() and not (G/'mock-service').exists()
    assert not (G/'manual-start-once.lock').exists()
    fd = os.open(G/'fixture-prepare-once.lock',os.O_CREAT|os.O_EXCL|os.O_WRONLY)
    os.write(fd,b'User-authorized W1 fresh fixture preparation only\n'); os.close(fd)
    assert IMAGE.is_dir()
    run('offline-helper-selfcheck',['P8S7Support','selfcheck'])
    run('fresh-seed',['P8S7Support','seed',str(ROOT)])
    # Only this newly seeded synthetic root is touched. No old R3 or user data is loaded.
    images=ROOT/'images'; images.mkdir()
    png(images/'post-p8-avatar-alice.png',96,96,[(38,135,158),(137,210,219)])
    png(images/'post-p8-avatar-bob.png',96,96,[(155,103,55),(238,197,135)])
    png(images/'post-p8-landscape.png',400,200,[(34,105,151),(76,160,114),(235,200,97),(227,238,246)])
    png(images/'post-p8-background.png',400,400,[(225,237,244),(233,242,233)])
    for cardfile in (ROOT/'entities/character_cards').glob('p8s7-*.json'):
        card=json.loads(cardfile.read_text(encoding='utf-8'))
        card['name']=card['name'].replace('P8 S7','Post-P8 UX')
        card['avatar']='images/post-p8-avatar-alice.png'
        card['chatBackground']='images/post-p8-background.png'
        card['momentsEnabled']=False
        for character in card['characters']:
            character['appearanceImage']='images/post-p8-avatar-'+('alice' if 'alice' in character['id'] else 'bob')+'.png'
        write(cardfile,card)
    for sessionfile in (ROOT/'entities/chat_sessions').glob('p8s7-*.json'):
        session=json.loads(sessionfile.read_text(encoding='utf-8'))
        session['title']=session['title'].replace('P8 S7','Post-P8 UX')
        write(sessionfile,session)
    first=ROOT/'entities/chat_messages/p8s7-session-a_p8s7-a-user.json'
    user=json.loads(first.read_text(encoding='utf-8'))
    user['images']=['images/post-p8-landscape.png']
    write(first,user)
    messages=[
        {'id':'postp8-a-user-2','sessionId':'p8s7-session-a','role':'USER','content':'这是两条助手回复之间的合成 User 消息。连播应跳过本条，不生成新音频。','createdAt':3000000,'updatedAt':3000000,'orderKey':3000000},
        {'id':'postp8-a-assistant-2','sessionId':'p8s7-session-a','role':'ASSISTANT','content':'第二条助手回复。\n<n="Alice 合成测试"/>[这是后续回复的测试句。](平静)\n<n="Bob 合成测试"/>[这是后续回复的另一句。](微笑)','createdAt':4000000,'updatedAt':4000000,'orderKey':4000000,'currentAlternativeVersionId':'postp8-a2-version-1'},
    ]
    for item in messages:
        write(ROOT/'entities/chat_messages'/('p8s7-session-a_'+item['id']+'.json'),item)
    write(ROOT/'desktop-settings.json',{'formatVersion':1,'automaticBackup':{'enabled':False,'minimumBackupInterval':'PT24H','maximumSnapshotCount':7,'checkInterval':'PT1H'},'uiLanguage':'ZH_CN','colorStyle':'NEUTRAL'})
    for name in ['synthetic-home','preview-temp','uds','exports/png','exports/mp3']:
        (G/name).mkdir(parents=True,exist_ok=False)
    run('actual-offline-hydrate',['P8R3Preflight',str(ROOT)])
    # This inventory contains only non-secret JSON/media under the new synthetic root.
    # OS-protected credential storage is a separate sibling and is never copied/hashed.
    records=[]
    for f in sorted(ROOT.rglob('*')):
        if f.is_file() and f.suffix=='.json':
            b=f.read_bytes(); records.append({'path':f.relative_to(ROOT).as_posix(),'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest()})
    write(G/'fixture-manifest.json',{'root':str(ROOT),'disposable':True,'files':records})
    media=[]
    for f in sorted(images.glob('*.png')):
        b=f.read_bytes();media.append({'path':f.relative_to(ROOT).as_posix(),'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest()})
    write(G/'w1/synthetic-media.json',{'files':media,'source':'Programmatically authored synthetic color grids; no user image'})
    files=[]
    for f in [*sorted((G/'support-src').glob('*.java')),*sorted((G/'support-classes').glob('*.class')),G/'runtime-watch.ps1',G/'manual-supervisor.py',G/'prepare-fixture.py',G/'live-official-sample.mp3',G/'synthetic-tts-long.mp3']:
        b=f.read_bytes();files.append({'path':f.relative_to(G).as_posix(),'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest()})
    write(G/'support-manifest.json',{'files':files})
    assert not (G/'mock-service').exists() and not (G/'manual-start-once.lock').exists()
    print('W1_FRESH_SYNTHETIC_FIXTURE_HYDRATE_PASS; no fake or GUI started')
