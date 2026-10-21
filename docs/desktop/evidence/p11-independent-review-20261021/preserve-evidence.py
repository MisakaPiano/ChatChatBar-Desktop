from pathlib import Path
import hashlib, json, shutil, subprocess, xml.etree.ElementTree as ET

repo = Path(r'H:\ChatChatBar-Desktop')
scope = repo / 'app/desktopApp/build/p11-review-fixes-20261021'
dest = repo / 'docs/desktop/evidence/p11-independent-review-20261021'
dest.mkdir(exist_ok=True)
def digest(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()
def save(name, value):
    (dest / name).write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
rows, stages, latest = [], [], {}
for stage in sorted(p for p in scope.iterdir() if p.is_dir() and p.name[:2].isdigit()):
    result = json.loads((stage / 'result.json').read_text(encoding='utf-8-sig'))
    suites = []
    for file in sorted(stage.rglob('*')):
        if not file.is_file(): continue
        if file.suffix not in {'.xml','.json','.log','.gradle'}: continue
        relative = file.relative_to(scope)
        target = dest / 'raw' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists(): assert digest(target)==digest(file), target
        else: shutil.copyfile(file, target)
        rows.append(dict(path=target.relative_to(dest).as_posix(), size=target.stat().st_size, sha256=digest(target)))
        if file.name.startswith('TEST-') and file.suffix=='.xml':
            root=ET.parse(file).getroot()
            suite=dict(name=root.attrib['name'], tests=int(root.attrib['tests']), failures=int(root.attrib['failures']), errors=int(root.attrib['errors']), skipped=int(root.attrib.get('skipped',0)), evidence=target.relative_to(dest).as_posix(), cached='shared' in file.relative_to(stage).parts)
            suites.append(suite)
            if result['exitCode']==0: latest[suite['name']]=suite
    stages.append(dict(stage=stage.name, result=result, suites=suites))
desktop=[s for s in latest.values() if not s['cached']]
cached=[s for s in latest.values() if s['cached']]
def counts(suites):
    return dict(suites=len(suites), **{k:sum(s[k] for s in suites) for k in ('tests','failures','errors','skipped')})
save('TEST_RESULTS.json',dict(stages=stages,latestDesktop=counts(desktop),sharedCacheReuse=counts(cached),latestSuites=list(latest.values()),historical546Unmodified=True))
save('RAW_FILE_SHA256.json',rows)
for name in ('run-focused.ps1','run-package-final.ps1','freeze-package.py','preserve-evidence.py'):
    source=scope/name
    if source.exists(): shutil.copyfile(source,dest/name)
(dest/'.gitattributes').write_text('*.log -text -whitespace\n*.xml -text -whitespace\n*.json -text -whitespace\n*.diff -text -whitespace\n*.gradle -text -whitespace\n',encoding='utf-8')
print(json.dumps(dict(desktop=counts(desktop),cached=counts(cached),rawFiles=len(rows)),indent=2))
