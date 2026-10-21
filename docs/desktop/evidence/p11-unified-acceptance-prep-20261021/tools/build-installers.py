from pathlib import Path
import os,json,hashlib,shutil,subprocess,datetime
repo=Path(r'H:\ChatChatBar-Desktop')
scope=Path(__file__).resolve().parent
stage=scope/'01-installers';stage.mkdir()
frozen=repo/'app/desktopApp/build/p11-review-fixes-20261021/windows-frozen-70b5c30c'
image=frozen/'runtime-image'
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
assert sha(frozen/'package-manifest.json')=='841924831a065c080e502860bdd4631a9d919975438414b8b474b16ed4add71c'
rows=json.loads((frozen/'package-manifest.json').read_text())
def verify(folder):
 assert {p.relative_to(folder).as_posix() for p in folder.rglob('*') if p.is_file()}=={r['path'] for r in rows}
 for r in rows:
  p=folder/r['path'];assert p.stat().st_size==r['size'] and sha(p)==r['sha256']
verify(image)
staging=stage/'input-image';shutil.copytree(image,staging);verify(staging)
env=dict(os.environ);env['PATH']=str(repo/'app/build/wix311')+os.pathsep+env['PATH']
jpackage=Path(r'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\jpackage.exe')
outputs=[]
for kind in ('msi','exe'):
 out=stage/kind;out.mkdir()
 args=[str(jpackage),'--type',kind,'--app-image',str(staging),'--dest',str(out),'--name','ChatChatBarDesktop','--description','ChatChatBar Desktop','--app-version','0.1.0','--vendor','ChatChatBar','--win-dir-chooser','--temp',str(stage/(kind+'-work'))]
 (stage/(kind+'-command.json')).write_text(json.dumps(args,indent=2),encoding='utf-8')
 with (stage/(kind+'-stdout.log')).open('xb') as stdout,(stage/(kind+'-stderr.log')).open('xb') as stderr:
  result=subprocess.run(args,env=env,stdout=stdout,stderr=stderr)
 (stage/(kind+'-result.json')).write_text(json.dumps(dict(exitCode=result.returncode,installed=False,guiStarted=False)),encoding='utf-8')
 assert result.returncode==0,kind
 p=next(out.glob('*.'+kind));outputs.append(dict(path=str(p),size=p.stat().st_size,sha256=sha(p)))
verify(image);verify(staging)
(stage/'result.json').write_text(json.dumps(dict(sourceCommit='70b5c30c91439e79728bf224c1a44d35b25cf733',controlHead='ec2b3a8e52b0d5ad6bb8bb78126266f78e77d141',portableFilesVerified=217,outputs=outputs,guiStarted=False,installed=False),indent=2),encoding='utf-8')
print(json.dumps(outputs,indent=2))
