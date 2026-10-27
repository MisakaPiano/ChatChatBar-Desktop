from pathlib import Path
import json,hashlib,subprocess,shutil,xml.etree.ElementTree as ET,datetime
G=Path(__file__).resolve().parent
R=G.parents[3]
E=R/'docs/desktop/evidence/p8-s7-20261019'
SOURCE='008343bb26d2eb76c23cae9b98132af828a9f447'
BASE='6968a4975fcd3da0c0a6618a600b013cc94c923c'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def write(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def git(*a):return subprocess.check_output(['git',*a],cwd=R)
assert git('rev-parse','HEAD').decode().strip()==SOURCE
assert not E.exists()
E.mkdir(parents=True)
(E/'.gitattributes').write_text('* -text\n',encoding='utf-8')
stages=[]
for d in sorted(G.glob('s7-*')):
 assert (d/'stage.json').exists(),d
 out=E/d.name;out.mkdir()
 for p in d.rglob('*'):
  if not p.is_file():continue
  rel=p.relative_to(d)
  if rel.suffix=='.log':rel=rel.with_suffix('.txt')
  target=out/rel;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,target)
 groups=[]
 for xmlDir in sorted(p for p in d.iterdir() if p.is_dir() and ('junit' in p.name)):
  suites=[]
  for p in sorted(xmlDir.glob('*.xml')):
   x=ET.parse(p).getroot()
   suites.append(dict(name=x.get('name'),tests=int(x.get('tests',0)),failures=int(x.get('failures',0))+int(x.get('errors',0)),skipped=int(x.get('skipped',0)),path=p.relative_to(G).as_posix(),sha256=sha(p)))
  groups.append(dict(directory=xmlDir.name,suites=len(suites),tests=sum(s['tests'] for s in suites),failures=sum(s['failures'] for s in suites),skipped=sum(s['skipped'] for s in suites),members=suites))
 resources=[json.loads(s.lstrip('\ufeff')) for s in (d/'resources.jsonl').read_text(encoding='utf-8-sig').splitlines() if s.strip()]
 res=dict(samples=len(resources),minimumAvailableBytes=min((s['availableBytes'] for s in resources),default=None),minimumCommitHeadroomBytes=min((s['commitLimitBytes']-s['commitBytes'] for s in resources),default=None),maximumPagesPerSecond=max((s['pagesPerSecond'] for s in resources),default=None))
 stages.append(dict(stage=d.name,execution=json.loads((d/'stage.json').read_text(encoding='utf-8-sig')),groups=groups,resources=res))
write(E/'test-ledger.json',stages)
for n in ['run-stage.ps1','bounded.init.gradle','prepare-final.py'] :shutil.copyfile(G/n,E/n)
commits=[]
for line in git('log','--reverse','--format=%H %P %s',BASE+'..HEAD').decode('utf-8').splitlines():
 h,parent,subject=line.split(' ',2)
 commits.append(dict(sha=h,parent=parent,subject=subject,diffstat=git('show','--format=','--shortstat',h).decode('utf-8').strip()))
write(E/'commit-ledger.json',commits)
for h in ['549837c191230a4b55ee30dd0a68fb4f89d080f6','79ba7bb1509dcc0747239d6e2c23a0daf45c0aff',SOURCE]:
 (E/(h[:8]+'.patch')).write_bytes(git('show','--format=',h))
(E/'all-source.patch').write_bytes(git('diff',BASE,SOURCE,'--','app','.agents/skills'))
paths=git('diff','--name-only',BASE,SOURCE,'--','app','.agents/skills').decode('utf-8').splitlines()
write(E/'final-source-identities.json',[dict(path=p,bytes=(R/p).stat().st_size,sha256=sha(R/p),gitBlob=git('rev-parse',SOURCE+':'+p).decode().strip()) for p in paths if (R/p).is_file()])
for sourceDir,targetDir in [('windows-gate','superseded-package'),('windows-final-gate','windows-preparation')]:
 src=G/sourceDir;dst=E/targetDir;dst.mkdir()
 for p in src.iterdir():
  if p.is_file() and (p.suffix in ['.json','.java','.py','.ps1','.txt']):shutil.copyfile(p,dst/p.name)
 # No runtime JARs, DPAPI blobs, seeded entities or temporary TLS keys are committed.
write(E/'preflight-final.json',dict(base=BASE,source=SOURCE,branch=git('branch','--show-current').decode().strip(),tracking=git('rev-parse','@{u}').decode().strip(),originDesktop=git('rev-parse','origin/desktop').decode().strip(),localDesktop=git('rev-parse','desktop').decode().strip(),status=git('status','--porcelain=v1','--untracked-files=all').decode('utf-8'),utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),userFiles='KEEP / UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED; path metadata only; no content hashes',realFishGET=0,realFishTTS=0,manualFakeServersStarted=0,manualGuiLaunches=0))
protected=['app/sharedCore/src/main/kotlin/com/example/chatbar/data/local/entity','app/sharedCore/src/main/kotlin/com/example/chatbar/domain/voice/FishAudioRequestFactory.kt','docs/desktop/10_UPSTREAM_BASELINE.json','docs/desktop/13_FEATURE_PARITY.md','docs/desktop/21_CURRENT_STATE.md']
write(E/'unchanged-contract-check.json',dict(paths=protected,diff=git('diff','--name-status',BASE,SOURCE,'--',*protected).decode(),formalUpstream='5e76a9cb841736bbbf3499a2e35e5789af4c5ca8',observedMaster='979cb505958dffb1b6d64a8de657434f198ffa96',upstreamSync=False,promptGolden='FishAudioTagGatewayTest compares byte-preserved official fixture and serialized API messages; prior S4 authority block SHA fb4072e46a81cf0f6d8e161e3a80490f33faa95f8f5090e2163857206a65e7bd'))
print(json.dumps([dict(stage=s['stage'],exit=s['execution']['exitCode'],groups=[{k:v for k,v in g.items() if k!='members'} for g in s['groups']],resources=s['resources']) for s in stages],indent=2))
