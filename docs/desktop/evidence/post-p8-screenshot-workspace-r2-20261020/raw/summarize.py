import json,re,hashlib,subprocess
from pathlib import Path
from datetime import datetime,timezone
import xml.etree.ElementTree as ET
repo=Path('H:/ChatChatBar-Desktop')
scope=repo/'app/desktopApp/build/post-p8-screenshot-workspace-r2-20261020'
stages=[]
for stage in sorted(scope.iterdir()):
    if not stage.is_dir() or not (stage/'result.json').exists(): continue
    result=json.loads((stage/'result.json').read_text(encoding='utf-8-sig'))
    start=datetime.fromisoformat(re.sub(r'(\.\d{6})\d+',r'\1',result['started']).replace('Z','+00:00')).timestamp()
    log=(stage/'stdout.log').read_text(encoding='utf-8-sig')
    executed=bool(re.search(r'^> Task :desktopApp:test(?: FAILED)?\s*$',log,re.M))
    suites=[];inherited=[]
    for path in sorted(stage.glob('TEST-*.xml')):
        if not executed or path.stat().st_mtime < start:
            inherited.append(path.name);continue
        tree=ET.parse(path).getroot()
        suites.append({'suite':tree.attrib['name'],**{k:int(tree.attrib.get(k,0)) for k in ('tests','failures','errors','skipped')},
          'file':path.name,'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    summary={'stage':stage.name,'gradleExit':result['exitCode'],'junitTaskExecuted':executed,'suites':suites,
      'totalTests':sum(s['tests'] for s in suites),'failures':sum(s['failures'] for s in suites),
      'errors':sum(s['errors'] for s in suites),'skipped':sum(s['skipped'] for s in suites),
      'inheritedXmlExcluded':inherited}
    (stage/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    stages.append({k:v for k,v in summary.items() if k!='suites'})
(scope/'stages-summary.json').write_text(json.dumps(stages,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(stages,ensure_ascii=False,indent=2))
