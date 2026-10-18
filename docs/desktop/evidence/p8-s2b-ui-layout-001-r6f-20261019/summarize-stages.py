from pathlib import Path
import json,xml.etree.ElementTree as ET,hashlib
G=Path(__file__).resolve().parent
rows=[]
for folder in sorted(G.iterdir()):
    if not folder.is_dir() or not (folder/'stage.json').exists():continue
    stage=json.loads((folder/'stage.json').read_text(encoding='utf-8-sig'))
    suites=[]
    for file in sorted((folder/'junit').glob('TEST-*.xml')):
        root=ET.parse(file).getroot()
        suites.append(dict(name=root.attrib['name'],tests=int(root.attrib['tests']),failures=int(root.attrib['failures']),errors=int(root.attrib['errors']),skipped=int(root.attrib['skipped']),sha256=hashlib.sha256(file.read_bytes()).hexdigest()))
    samples=[json.loads(s) for s in (folder/'resources.jsonl').read_text(encoding='utf-8-sig').splitlines() if s.strip()]
    totals={k:sum(x[k] for x in suites) for k in ['tests','failures','errors','skipped']}
    totals['reportedPass']=totals['tests']-totals['failures']-totals['errors']-totals['skipped']
    rows.append(dict(stage=folder.name,exitCode=stage['exitCode'],durationSeconds=stage['durationSeconds'],suiteCount=len(suites),totals=totals,suites=suites,resources=dict(sampleCount=len(samples),minAvailableBytes=min(x['availableBytes'] for x in samples),minCommitHeadroomBytes=min(x['commitLimitBytes']-x['commitBytes'] for x in samples),maxPagesPerSecond=max(x['pagesPerSecond'] for x in samples))))
(G/'stage-summary.json').write_text(json.dumps(rows,indent=2)+'\n',encoding='utf-8')
for row in rows:print(row['stage'],row['exitCode'],row['suiteCount'],row['totals'])
