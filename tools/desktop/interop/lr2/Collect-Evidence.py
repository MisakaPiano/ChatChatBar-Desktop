"""Public synthetic evidence only. Never opens authorized real source or private test roots."""
from pathlib import Path
import hashlib, json, subprocess, sys, xml.etree.ElementTree as ET

repo=Path(__file__).resolve().parents[4]
evidence=repo/'docs/desktop/evidence/p11-lr2-20261024'
label=sys.argv[1]
assert label.replace('-','').isalnum()
dest=evidence/'raw'/('checkpoint-'+label)
dest.mkdir(exist_ok=False)
def git(*args): return subprocess.check_output(['git',*args],cwd=repo).decode('utf-8').strip()
def identity(path):
    data=path.read_bytes()
    return dict(path=path.relative_to(repo).as_posix(),bytes=len(data),sha256=hashlib.sha256(data).hexdigest(),gitBlob=git('hash-object',str(path)))
def save(name,value): (dest/name).write_text(json.dumps(value,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
tools=sorted((repo/'tools/desktop/interop/lr2').rglob('*'))
sources=[identity(p) for p in tools if p.is_file() and '__pycache__' not in p.parts]
save('source-identities.json',sources)
save('git-control.json',dict(head=git('rev-parse','HEAD'),start='33789eba37eda8952a806671bcfd76eb95cead1c',
    branch=git('branch','--show-current'),productionDiff=git('diff','33789eba','--name-only','--','app'),
    upstreamBaseline=git('hash-object','docs/desktop/10_UPSTREAM_BASELINE.json')))
rounds=[]
for directory in sorted((evidence/'raw').iterdir()):
    xmls=sorted((directory/'junit').glob('*.xml'))
    if not xmls: continue
    counts=dict(tests=0,failures=0,errors=0,skipped=0)
    for file in xmls:
        suite=ET.parse(file).getroot()
        assert int(suite.attrib['tests'])==len(suite.findall('testcase'))
        for key in counts: counts[key]+=int(suite.attrib.get(key,0))
    exit_file=directory/'exit.txt'
    rounds.append(dict(round=directory.name,suites=len(xmls),**counts,exit=exit_file.read_text(encoding='utf-8-sig').strip() if exit_file.exists() else 'WRAPPER1_GRADLE_EXIT_NOT_CAPTURED'))
save('rounds.json',rounds)
for folder in ('synthetic','child-evidence'):
    original=repo/'app/desktopApp/build/p11-lr2'/folder
    target=dest/folder; target.mkdir()
    if original.exists():
        for file in original.iterdir():
            if file.is_file():
                assert file.stat().st_size<2*1024*1024
                (target/file.name).write_bytes(file.read_bytes())
raw=[identity(p) for p in sorted((evidence/'raw').rglob('*')) if p.is_file() and p!=dest/'index.json']
save('index.json',raw)
print(json.dumps(dict(label=label,files=len(raw),rounds=rounds)))
