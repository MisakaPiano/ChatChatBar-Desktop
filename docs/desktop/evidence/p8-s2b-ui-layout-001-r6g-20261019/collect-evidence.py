from pathlib import Path
import hashlib, json, shutil, subprocess, xml.etree.ElementTree as ET
R=Path.cwd(); G=Path(__file__).resolve().parent
E=R/'docs/desktop/evidence/p8-s2b-ui-layout-001-r6g-20261019'
BASE='15d35ab8a46decdf710d09d12c9b0d3b87656444'
def git(*args): return subprocess.check_output(['git',*args],stderr=subprocess.PIPE)
def sha(b): return hashlib.sha256(b).hexdigest()
def save(name,obj): (E/name).write_text(json.dumps(obj,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
pre=json.loads((G/'A-preflight.json').read_text(encoding='utf-8-sig'))
identities=[]
for item in pre['source']:
 p=R/item['path']; b=p.read_bytes(); assert sha(b)==item['expected']
 (E/'tested-sources').mkdir(exist_ok=True)
 (E/'tested-sources'/p.name).write_bytes(b)
 identities.append(dict(path=item['path'],bytes=len(b),sha256=sha(b),expected=item['expected'],frozen=True))
save('source-identities.json',identities)
for p in G.iterdir():
 if p.is_file() and p.name not in [Path(__file__).name,'source-checkpoint.py']: shutil.copyfile(p,E/p.name)
 if p.is_dir() and (p/'stage.json').exists(): shutil.copytree(p,E/p.name,dirs_exist_ok=True)
shutil.copyfile(__file__,E/Path(__file__).name)
(E/'.gitattributes').write_bytes(b'* -text\n')
stages=json.loads((G/'stage-summary.json').read_text())
skips=[]; failures=[]
for row in stages:
 for p in (E/row['stage']/'junit').glob('TEST-*.xml'):
  root=ET.parse(p).getroot()
  for test in root.findall('testcase'):
   if test.find('skipped') is not None: skips.append(dict(stage=row['stage'],suite=root.attrib['name'],test=test.attrib['name']))
   for kind in ['failure','error']:
    for fail in test.findall(kind): failures.append(dict(stage=row['stage'],suite=root.attrib['name'],test=test.attrib['name'],kind=kind,attributes=fail.attrib,stack=fail.text))
save('failures.json',failures); save('skipped-tests.json',skips)
native=E/'02-bounded-desktop/junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml'
if native.exists():
 root=ET.parse(native).getroot(); out=root.findtext('system-out') or ''
 (E/'native-system-out.txt').write_text(out,encoding='utf-8')
 save('native-in-bounded.json',dict(suite=root.attrib,testCases=[t.attrib for t in root.findall('testcase')],xmlSha256=sha(native.read_bytes()),dragSteps=sum(s.startswith('FISH_R5_DRAG ') for s in out.splitlines()),physicalPresses=sum(s.startswith('FISH_R5_PRESS ') for s in out.splitlines()),dragCompletions=sum('phase=R5-real-drag-completed' in s for s in out.splitlines()),wheelCompletions=sum('phase=R5-native-wheel-completed' in s for s in out.splitlines()),narrowRowRecords=[s for s in out.splitlines() if s.startswith('FISH_R6D_PAGER_ROWS ')],unknownTotalRecords=[s for s in out.splitlines() if 'phase=unknown-total-int-boundary' in s]))
history=[]
for name in ['r3','r4','r5','r6a','r6b','r6c','r6d','r6e','r6f']:
 p='docs/desktop/evidence/p8-s2b-ui-layout-001-'+name+'-20261019'
 before=git('rev-parse',BASE+':'+p).decode().strip(); now=git('rev-parse','HEAD:'+p).decode().strip()
 assert before==now and not git('diff','HEAD','--',p).strip()
 history.append(dict(path=p,gitTreeAtStart=before,gitTreeNow=now,unchanged=True))
save('history-preservation.json',history)
head=git('rev-parse','HEAD').decode().strip()
if head!=BASE:
 assert git('rev-parse','HEAD^').decode().strip()==BASE
 names=git('diff-tree','--no-commit-id','--name-only','-r',head).decode().splitlines()
 assert sorted(names)==sorted(i['path'] for i in identities)
 patch=git('show','--format=','--binary',head)
 (E/'source-candidate.patch').write_bytes(patch)
 save('source-candidate.json',dict(sha=head,parent=BASE,paths=names,diffstat=git('show','--format=','--stat',head).decode(),patchBytes=len(patch),patchSha256=sha(patch),gitBlobs=[dict(path=i['path'],blob=git('rev-parse',head+':'+i['path']).decode().strip(),blobSha256=sha(git('show',head+':'+i['path'])),rawSha256=i['sha256']) for i in identities],acceptance='SOURCE CANDIDATE / PROJECT REVIEW PENDING'))
save('closing-state.json',dict(base=BASE,sourceCandidate=head if head!=BASE else None,stages=[dict(stage=s['stage'],suites=s['suiteCount'],**s['totals'],exitCode=s['exitCode']) for s in stages],realFishGet=0,realFishTts=0,newDistribution='NOT BUILT',windowsManual='NOT TESTED / NOT STARTED',packagedLauncherJvmRootLock='NOT TESTED / NOT STARTED',winAuto001='OPEN',winBrand001='OPEN / DEFERRED',unknownHistoricalPointerMovement='UNKNOWN / unchanged',protectedFiles='KEEP / UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED; paths only inventoried',sourceFrozen=True))
print(json.dumps(dict(stages=len(stages),failures=len(failures),skips=len(skips),sourceCandidate=head if head!=BASE else None)))
