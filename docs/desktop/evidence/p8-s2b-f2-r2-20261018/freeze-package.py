from pathlib import Path
import hashlib,json,subprocess,shutil,zipfile
R=Path.cwd();G=R/'app/desktopApp/build/p8-s2b-f2-r2-20261018';sha=lambda b:hashlib.sha256(b).hexdigest()
assert json.loads((G/'05-create-distributable/stage.json').read_text(encoding='utf-8-sig'))['exitCode']==0
commit=subprocess.check_output(['git','rev-parse','HEAD']).decode().strip();assert commit=='0b0a2f6c88555cc1eecbf1ea6370cd88582418bc'
paths=subprocess.check_output(['git','diff-tree','--no-commit-id','--name-only','-r',commit]).decode().splitlines();assert len(paths)==5
source=[]
for p in paths:
    b=subprocess.check_output(['git','show',commit+':'+p]);wt=(R/p).read_bytes();assert b==wt.replace(b'\r\n',b'\n')
    source.append(dict(path=p,gitBlob=subprocess.check_output(['git','rev-parse',commit+':'+p]).decode().strip(),gitBytes=len(b),gitSha256=sha(b),worktreeBytes=len(wt),worktreeSha256=sha(wt)))
(G/'source-checkpoint.json').write_text(json.dumps(dict(commit=commit,parent='e3e0dcc1ee3324caf5e1f8f321b03449e716a061',files=source),indent=2)+'\n',encoding='utf-8')
(G/'source-commit.patch').write_bytes(subprocess.check_output(['git','show','--format=','--binary',commit]))
original=R/'app/desktopApp/build/compose/binaries/main/app/ChatChatBarDesktop';target=G/'runtime-image'
assert not target.exists()
for p in original.rglob('*'):
    assert not p.is_symlink() and not (getattr(p.lstat(),'st_file_attributes',0)&0x400),str(p)
assert not (original/'portable.flag').exists() and not (original/'UserData').exists()
shutil.copytree(original,target)
files=[]
for p in sorted(target.rglob('*')):
    if p.is_file():b=p.read_bytes();files.append(dict(path=p.relative_to(target).as_posix(),bytes=len(b),sha256=sha(b)))
business=list((target/'app').glob('desktopApp-*.jar'));assert len(business)==1
classes=['DesktopFishSampleTransport','DesktopFishSampleTransport$Attempt','DesktopFishSamplePolicy','DesktopFishPreviewRuntime','DesktopFishPreviewRuntime$Session','DesktopFishAudioPanelKt']
verified={}
with zipfile.ZipFile(business[0]) as z:
    for name in classes:
        entry='com/example/chatbar/desktop/'+name+'.class';assert z.read(entry)==(R/'app/desktopApp/build/classes/kotlin/main'/entry).read_bytes();verified[name]=True
cfg=(target/'app/ChatChatBarDesktop.cfg').read_text(encoding='utf-8');assert 'chatbar.desktop.applicationHome=$ROOTDIR' in cfg
data=dict(sourceCommit=commit,count=len(files),files=files,launcherSha256=sha((target/'ChatChatBarDesktop.exe').read_bytes()),businessJar=business[0].relative_to(target).as_posix(),businessJarSha256=sha(business[0].read_bytes()),classesMatchCompiledOutput=verified,applicationHomeConfiguration=cfg)
(G/'distribution-manifest.json').write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in data.items() if k not in ['files','applicationHomeConfiguration']}))
