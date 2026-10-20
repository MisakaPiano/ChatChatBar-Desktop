import hashlib,json,shutil,subprocess
from pathlib import Path
from datetime import datetime,timezone
repo=Path('H:/ChatChatBar-Desktop');scope=repo/'app/desktopApp/build/post-p8-screenshot-r2-boundary-20261021'
source='c38bcf274fca2eafb81a27b10cb3fcb16ed94fbe'
def git(*args):return subprocess.check_output(['git',*args],cwd=repo)
assert git('rev-parse','HEAD').decode().strip()==source
assert not git('diff','HEAD','--name-only').strip()
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
def files(root):return [{'path':p.relative_to(root).as_posix(),'bytes':p.stat().st_size,'sha256':sha(p)} for p in sorted(root.rglob('*')) if p.is_file()]
assert json.loads((scope/'08-package/result.json').read_text(encoding='utf-8-sig'))['exitCode']==0
oldResults=[]
for name,expected in [('post-p8-screenshot-workspace-r2-20261020/windows-frozen-3ece1366','822797ac24fa6211bf4c764c82c29d746c9e8e22730888d768877e9708cc9af7'),('post-p8-manual-reliability-r1-20261020/windows-frozen-60cc44d0','f912b60179d7b31debafb363b221175210b597aac84a97beb3d79f11f824c10f'),('post-p8-audio-maintenance-r1-20261020/windows-frozen-694a8bdd','d98691a663275c99aeee2318514db1e528e28c74a71349c232e235cc7f076ed1')]:
 root=repo/'app/desktopApp/build'/name
 assert sha(root/'distribution-manifest.json')==expected
 old=json.loads((root/'distribution-manifest.json').read_text(encoding='utf-8-sig'))
 assert {f['path']:(f['bytes'],f['sha256'].lower()) for f in old['files']}=={f['path']:(f['bytes'],f['sha256']) for f in files(root/'runtime-image')}
 oldResults.append({'path':str(root),'verifiedFiles':len(old['files']),'manifestSha256':expected,'status':'UNCHANGED'})
built=scope/'package-c38bcf27-build/compose/binaries/main/app/ChatChatBarDesktop'
entries=files(built);assert len(entries)==212
frozen=scope/'windows-frozen-c38bcf27';assert not frozen.exists()
shutil.copytree(built,frozen/'runtime-image');assert entries==files(frozen/'runtime-image')
manifest={'sourceCommit':source,'base':'ba7b58c829fb583c0e22c100f5de8a3799406c20','formalUpstream':'5e76a9cb841736bbbf3499a2e35e5789af4c5ca8','fileCount':len(entries),'files':entries}
(frozen/'distribution-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
assert entries==files(frozen/'runtime-image')
record={'utc':datetime.now(timezone.utc).isoformat(),'sourceCommit':source,'runtimeImage':str(frozen/'runtime-image'),'manifestSha256':sha(frozen/'distribution-manifest.json'),
 'verifiedFiles':len(entries),'copyAndRehash':'PASS','oldPackages':oldResults,'guiStarted':False,'fakeStarted':False,'syntheticGuiRootSeeded':False}
(scope/'frozen-package.json').write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8')
(scope/'source.patch').write_bytes(git('show','--format=fuller','--binary',source))
(scope/'source-checkpoint.json').write_text(json.dumps({'sourceCommit':source,'parent':git('rev-parse','HEAD^').decode().strip(),
 'stat':git('show','--stat','--oneline','HEAD').decode(),'files':git('diff','HEAD^','HEAD','--name-only').decode().splitlines()},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(record,indent=2))
