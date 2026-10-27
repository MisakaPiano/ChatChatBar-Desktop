"""Append a new public evidence snapshot. Never rewrite earlier raw checkpoints."""
import hashlib, json, subprocess, sys, xml.etree.ElementTree as ET
from pathlib import Path

repo = Path(__file__).resolve().parents[4]
evidence = repo/'docs/desktop/evidence/prepreview-autonomous-20261023'
raw = evidence/'raw'
label = sys.argv[1]
if not label.isalnum(): raise SystemExit('ALPHANUMERIC_LABEL_REQUIRED')
out = raw/('snapshot-'+label)
out.mkdir(exist_ok=False)
def git(*args): return subprocess.check_output(['git',*args],cwd=repo).decode().strip()
def save(name, value): (out/name).write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
def identity(path):
    b=path.read_bytes()
    return {'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest()}
tracked = git('diff','--name-only','cac1a811').splitlines()
pending = git('ls-files','--others','--exclude-standard').splitlines()
allowed = ('app/desktopApp/src/','tools/desktop/interop/prepreview/')
sources = sorted({p for p in tracked+pending if p.startswith(allowed) and Path(p).suffix in ('.kt','.py','.ps1')})
items={}
for name in sources:
    p=repo/name
    destination=out/'source'/name
    destination.parent.mkdir(parents=True,exist_ok=True)
    destination.write_bytes(p.read_bytes())
    items[name]={**identity(p),'gitBlob':git('hash-object','--path='+name,name)}
save('source-identities.json',items)
rounds={}
for folder in raw.iterdir():
    if not folder.is_dir() or not (folder/'exit.txt').exists(): continue
    row={'exit':int((folder/'exit.txt').read_text(encoding='utf-8-sig').strip()),'suites':0,
         'tests':0,'failures':0,'errors':0,'skipped':0}
    for p in (folder/'junit').glob('*.xml'):
        t=ET.parse(p).getroot();row['suites']+=1
        for k in ('tests','failures','errors','skipped'):row[k]+=int(t.attrib.get(k,0))
    if (folder/'JUNIT_DISQUALIFICATION.json').exists():
        row['excludedStaleXmlTests']=row['tests']
        for k in ('suites','tests','failures','errors','skipped'):row[k]=0
    rounds[folder.name]=row
save('rounds.json',rounds)
main=[p for p in tracked if '/src/main/' in p]
save('boundaries.json',{'base':'cac1a811a21e6f99181c716e438b6b8df8dbf4f7','headBeforeCommit':git('rev-parse','HEAD'),
 'mainDelta':main,'baselineUnchanged':not git('diff','cac1a811','--','docs/desktop/10_UPSTREAM_BASELINE.json'),
 'historicalEvidenceUnchanged':not git('diff','--name-only','cac1a811','--','docs/desktop/evidence',':!docs/desktop/evidence/prepreview-autonomous-20261023')})
index={p.relative_to(raw).as_posix():identity(p) for p in sorted(raw.rglob('*')) if p.is_file()}
save('index.json',index)
print(json.dumps({'filesIndexed':len(index),'rounds':rounds,'mainDelta':main}))
