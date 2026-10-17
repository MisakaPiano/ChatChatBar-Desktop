from pathlib import Path
import json,xml.etree.ElementTree as E
G=Path(__file__).resolve().parent
rows=[]
for stage in sorted(G.glob('[0-9][0-9]-*')):
 if not stage.is_dir() or not (stage/'stage.json').exists():continue
 record=json.loads((stage/'stage.json').read_text(encoding='utf-8-sig'))
 record['stage']=stage.name
 record['junitCurrentExecution']=not (stage/'NOT_A_TEST_EXECUTION.json').exists()
 record['suites']=[]
 if record['junitCurrentExecution']:
  for f in sorted((stage/'junit').glob('*.xml')):
   e=E.parse(f).getroot()
   row=dict(file=f.relative_to(G).as_posix(),suite=e.get('name'),**{k:int(e.get(k,'0')) for k in ['tests','failures','errors','skipped']})
   row['reportedPass']=row['tests']-row['failures']-row['errors']-row['skipped']
   record['suites'].append(row)
 record['totals']={k:sum(x[k] for x in record['suites']) for k in ['tests','reportedPass','failures','errors','skipped']}
 samples=[json.loads(x) for x in (stage/'resources.jsonl').read_text(encoding='utf-8-sig').splitlines() if x.strip()]
 record['resourceSummary']=dict(samples=len(samples),minimumAvailableBytes=min(x['availableBytes'] for x in samples),minimumCommitHeadroomBytes=min(x['commitLimitBytes']-x['commitBytes'] for x in samples),maximumPagesPerSecond=max(x['pagesPerSecond'] for x in samples),sampledExtremaOnly=True)
 rows.append(record)
(G/'stage-results.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
for x in rows:print(x['stage'],x['exitCode'],x['durationSeconds'],len(x['suites']),x['totals'])
