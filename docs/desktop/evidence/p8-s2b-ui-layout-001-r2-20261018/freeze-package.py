from pathlib import Path
import json,hashlib,shutil,zipfile,subprocess
R=Path.cwd();G=Path(__file__).resolve().parent
assert json.loads((G/'06-create-distributable/stage.json').read_text(encoding='utf-8-sig'))['exitCode']==0
commit=subprocess.check_output(['git','rev-parse','HEAD']).decode().strip()
source=json.loads((G/'final-source.json').read_text());ledger=[]
for row in source['files']:
    n=row['path'];b=(R/n).read_bytes();git=subprocess.check_output(['git','show',commit+':'+n])
    assert hashlib.sha256(b).hexdigest()==row['sha256'] and git==b.replace(b'\r\n',b'\n')
    ledger.append(dict(path=n,gitBlob=subprocess.check_output(['git','rev-parse',commit+':'+n]).decode().strip(),gitSha256=hashlib.sha256(git).hexdigest(),worktreeSha256=row['sha256']))
(G/'source-checkpoint.json').write_text(json.dumps(dict(commit=commit,parent=subprocess.check_output(['git','rev-parse','HEAD^']).decode().strip(),files=ledger),indent=2)+'\n')
original=R/'app/desktopApp/build/compose/binaries/main/app/ChatChatBarDesktop';target=G/'runtime-image'
assert not target.exists() and not (original/'portable.flag').exists() and not (original/'UserData').exists()
for p in original.rglob('*'):assert not p.is_symlink() and not (getattr(p.lstat(),'st_file_attributes',0)&0x400)
shutil.copytree(original,target)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
files=[dict(path=p.relative_to(target).as_posix(),bytes=p.stat().st_size,sha256=sha(p)) for p in sorted(target.rglob('*')) if p.is_file()]
jar,=list((target/'app').glob('desktopApp-*.jar'))
prefixes=['MainKt','DesktopStudioControlsKt','DesktopFishAudioPanelKt','DesktopFishPickerWindowKt','DesktopFishWindowOwnerLease','DesktopFishPickerPresentationKt']
verified=[]
with zipfile.ZipFile(jar) as z:
    for name in z.namelist():
        simple=name.rsplit('/',1)[-1]
        if name.endswith('.class') and any(simple.startswith(p+'.') or simple.startswith(p+'$') for p in prefixes):
            assert z.read(name)==(R/'app/desktopApp/build/classes/kotlin/main'/name).read_bytes();verified.append(name)
assert len(verified)>=len(prefixes)
cfg=(target/'app/ChatChatBarDesktop.cfg').read_text();assert 'chatbar.desktop.applicationHome=$ROOTDIR' in cfg
data=dict(sourceCommit=commit,count=len(files),files=files,launcherSha256=sha(target/'ChatChatBarDesktop.exe'),businessJar=jar.relative_to(target).as_posix(),businessJarSha256=sha(jar),compiledClassMatches=verified,applicationHomeConfiguration=cfg)
(G/'distribution-manifest.json').write_text(json.dumps(data,indent=2)+'\n')
print(json.dumps({k:v for k,v in data.items() if k not in ['files','compiledClassMatches','applicationHomeConfiguration']}))
