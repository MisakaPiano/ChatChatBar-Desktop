import hashlib,json,shutil,subprocess
from pathlib import Path
repo=Path('H:/ChatChatBar-Desktop');scope=repo/'app/desktopApp/build/post-p8-screenshot-workspace-r2-20261020'
prefix='docs/desktop/evidence/post-p8-screenshot-workspace-r2-20261020/'
dest=repo/prefix
def git(*args): return subprocess.check_output(['git',*args],cwd=repo)
source='3ece1366b142210f2db7c61a9c479cd156534b69'
assert git('rev-parse','HEAD').decode().strip()==source
assert not git('diff','HEAD','--name-only').strip()
assert not git('diff','--cached','--name-only').strip()
identity=json.loads((scope/'tested-source-identity.json').read_text(encoding='utf-8-sig'))
for f in identity['files']:
 b=(repo/f['path']).read_bytes()
 assert len(b)==f['rawBytes'] and hashlib.sha256(b).hexdigest()==f['rawSha256']
 assert git('rev-parse',source+':'+f['path']).decode().strip()==f['normalizedGitBlob']
protection=json.loads((scope/'preflight-and-protection.json').read_text(encoding='utf-8-sig'))
for p in protection['protectedPathStatusOnly']:
 assert git('status','--porcelain','--untracked-files=all','--',p).decode().strip().startswith('?? ')
assert not git('diff','22a8612b9076880ff6d2520068947cd23ea7c029','HEAD','--name-only','--','docs/desktop/evidence').strip()
untracked=git('ls-files','--others','--exclude-standard').decode().splitlines()
assert all(p.startswith(prefix) or p in protection['protectedPathStatusOnly'] for p in untracked)
legacy=[]
for f in json.loads((scope/'legacy-test-export-identity.json').read_text(encoding='utf-8-sig')):
 assert hashlib.sha256(Path(f['Path']).read_bytes()).hexdigest()==f['Hash'].lower()
 legacy.append({'path':f['Path'],'sha256':f['Hash'].lower(),'status':'UNCHANGED'})
(scope/'preservation-final.json').write_text(json.dumps({'sourceCommit':source,'protectedContentReadOrHashed':False,
 'protectedStatusOnly':protection['protectedPathStatusOnly'],'oldTrackedEvidenceChanges':[],'legacyRendererExports':legacy},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
raw=dest/'raw';assert not raw.exists();raw.mkdir()
stages=['01-workspace-red','02-workspace-focused','03-workspace-focused','04-workspace-focused','05-final-focused','06-final-focused','07-final-focused','08-package']
for name in stages:shutil.copytree(scope/name,raw/name)
rootFiles=['initial-path-status.txt','compose-dialog-bytecode.txt','compose-dialog-owner-bytecode.txt','compose-public-swing-dialog-api.txt','compose-api-identity.json',
 'legacy-test-export-identity.json','resource-monitor.jsonl','resources-final.json','stages-summary.json','tested-source-identity.json','preflight-and-protection.json','preservation-final.json',
 'source.patch','source-checkpoint.json','frozen-package.json','package.init.gradle','run-focused.ps1','run-package.ps1','freeze-package.py','summarize.py','check-source.py','assemble-evidence.py']
for name in rootFiles:shutil.copyfile(scope/name,raw/name)
shutil.copytree(scope/'ui-previews',dest/'previews')
shutil.copytree(scope/'renderer-previews',dest/'renderer-previews')
(dest/'package').mkdir()
shutil.copyfile(scope/'windows-frozen-3ece1366/distribution-manifest.json',dest/'package/distribution-manifest.json')
files=[{'path':p.relative_to(dest).as_posix(),'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
 for p in sorted(dest.rglob('*')) if p.is_file() and p!=dest/'manifest.json']
(dest/'manifest.json').write_text(json.dumps({'sourceCommit':source,'fileCount':len(files),'files':files,
 'excludesOnly':'root manifest.json (self)','rawTextConversion':'disabled by evidence-local .gitattributes'},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'sourceIdentity':'10/10 PASS','evidenceFiles':len(files)+1,'totalBytes':sum(f['bytes'] for f in files),'legacyExports':'UNCHANGED','protectedFiles':'PATH STATUS ONLY'},indent=2))
