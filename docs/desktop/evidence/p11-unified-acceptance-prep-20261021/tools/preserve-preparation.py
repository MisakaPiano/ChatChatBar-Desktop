from pathlib import Path
import hashlib,json,shutil,subprocess
scope=Path(__file__).resolve().parent;repo=scope.parents[3]
dest=repo/'docs/desktop/evidence/p11-unified-acceptance-prep-20261021'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def copy(source,relative):
 target=dest/relative;target.parent.mkdir(parents=True,exist_ok=True)
 if target.exists():assert sha(source)==sha(target),relative
 else:shutil.copyfile(source,target)
 return dict(path=relative,size=target.stat().st_size,sha256=sha(target))
rows=[]
for stage in sorted(p for p in scope.iterdir() if p.is_dir() and p.name[:2].isdigit()):
 for source in sorted(stage.iterdir()):
  if source.is_file() and source.suffix in {'.log','.xml','.json','.txt','.wxs'}:
   rows.append(copy(source,'raw/'+stage.name+'/'+source.name))
for source in sorted((scope/'integration').glob('*.json')):rows.append(copy(source,'integration/'+source.name))
for name in ['fixture-manifest.json','fixture-summary.json','test-summary.json','launch-admission.json','protection.json','preparation-preflight.json','offline-api-selftest.json','synthetic-credential.log','inventory-first-failure.json','final-lock-probe.json','final-lock-probe.log']:
 rows.append(copy(scope/name,name))
rows.append(copy(scope/'01-installers/result.json','installer-build-result.json'))
rows.append(copy(scope/'01-installers/payload-verification.json','payload-verification.json'))
for source in sorted(scope.iterdir()):
 if source.is_file() and source.suffix in {'.py','.ps1','.gradle'}:copy(source,'tools/'+source.name)
for source in sorted((scope/'harness').iterdir()):
 if source.is_file():copy(source,'tools/harness/'+source.name)
(dest/'RAW_SHA256.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(dest/'.gitattributes').write_text('raw/** -text -whitespace\nintegration/** -text -whitespace\n*.json -text -whitespace\n*.log -text -whitespace\ntools/** -text -whitespace\n',encoding='utf-8')
assert not subprocess.check_output(['git','diff','--name-only','70b5c30c','--','app','.agents'],cwd=repo).strip()
print(json.dumps(dict(rawFiles=len(rows),rawBytes=sum(r['size'] for r in rows),sourceUnchanged=True)))
