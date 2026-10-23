"""Collect only parent-created T0 fixtures; no user profile, backup or credential discovery."""
from pathlib import Path
import subprocess, json, hashlib, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[4]
OUT=Path(__file__).resolve().parent
RAW=OUT/'raw'
BASE='5bb326808a7138f0701cedca7793ef080b92a9c6'
def git(*args):return subprocess.check_output(['git','-C',str(ROOT),*args])
def sha(b):return hashlib.sha256(b).hexdigest()
def save(path,value):path.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')

summary={}
for stage,expected,failures in [('first',118,1),('receipt-red',1,1),('green',119,0)]:
    rows=[]
    for p in sorted((RAW/stage/'junit').glob('TEST-*.xml')):
        r=ET.parse(p).getroot();rows.append({'suite':r.attrib['name'],**{k:int(r.attrib[k]) for k in ['tests','failures','errors','skipped']}})
    assert sum(r['tests'] for r in rows)==expected and sum(r['failures'] for r in rows)==failures
    assert all(r[k]==0 for r in rows for k in ['errors','skipped'])
    summary[stage]={'rows':rows,'tests':expected,'failures':failures,'exit':int((RAW/stage/'exit.txt').read_text(encoding='utf-8-sig'))}
summary['red']={'tests':0,'exit':1,'classification':'CONFIGURATION_REJECTED; stale XML excluded'}
save(RAW/'execution-summary.json',summary)

# Content-addressed raw files avoid long Windows/Git paths; inventory binds exact original relative names.
owned=ROOT/'app/desktopApp/build/g1b-t0'
objects=RAW/'objects';objects.mkdir(exist_ok=True)
inventory=[];child_exits={}
for p in sorted(owned.rglob('*')):
    if not p.is_file():continue
    data=p.read_bytes();digest=sha(data);dest=objects/digest
    if not dest.exists():dest.write_bytes(data)
    else:assert dest.read_bytes()==data
    inventory.append({'fixturePath':p.relative_to(owned).as_posix(),'object':'raw/objects/'+digest,'bytes':len(data),'sha256':digest})
    if p.name.endswith('.exit'):
        code=data.decode().strip();child_exits[code]=child_exits.get(code,0)+1
    if p.name=='source.synthetic':assert data==b'SYNTHETIC_SOURCE_NOT_A_CBBackup'
save(RAW/'fixture-inventory.json',inventory)
save(RAW/'child-exits.json',{'realChildRunsAllRounds':sum(child_exits.values()),'byActualExit':child_exits,'notJUnitCount':True,'fixtures':'Includes RED and GREEN artifacts; not every tree is a GREEN result'})

production=['app/desktopApp/src/main','app/sharedCore/src/main','app/app/src/main']
assert not git('diff',BASE,'--',*production,'app/desktopApp/build.gradle.kts','.agents').strip()
save(RAW/'production-boundaries.json',[{'path':p,'gitTree':git('rev-parse',BASE+':'+p).decode().strip(),'worktreeDiff':'UNCHANGED'} for p in production])
paths=['security/WindowsSecretRootResolver.kt','security/DesktopSecretStore.kt','security/WindowsSecretStore.kt',
       'DesktopFullBackupCommitJournal.kt','DesktopStartupGate.kt','DesktopDataOperationCoordinator.kt',
       'DesktopDataRootOwnership.kt','DesktopDocumentIndexReadEvidence.kt','backup/RecoverableIntentContract.kt']
sources=[]
for short in paths:
    p='app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/'+short;b=git('show',BASE+':'+p)
    sources.append({'revision':BASE,'path':p,'blob':git('rev-parse',BASE+':'+p).decode().strip(),'sha256':sha(b),'bytes':len(b)})
official='1198a13cf3008de1358b8a69aa5e40b64797c8d9'
for p in ['.agents/skills/chatbar-app-backup/SKILL.md','app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupModels.kt']:
    b=git('show',official+':'+p);sources.append({'revision':official,'path':p,'blob':git('rev-parse',official+':'+p).decode().strip(),'sha256':sha(b),'bytes':len(b)})
save(RAW/'source-identities.json',sources)
new=ROOT/'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/g1bt0'
tests=[]
for p in sorted(new.glob('*.kt')):
    b=p.read_bytes();snap=RAW/'green/source'/p.name
    assert b==snap.read_bytes()
    relative=p.relative_to(ROOT).as_posix()
    tests.append({'path':relative,'blobWithFilters':git('hash-object','--path='+relative,relative).decode().strip(),'sha256':sha(b),'bytes':len(b)})
save(RAW/'test-identities.json',tests)

# Child diagnostics are checked before object copying. Fake-valued inputs/ciphertexts are intentionally raw.
diagnostics=list(RAW.rglob('TEST-*.xml'))+list(RAW.glob('*/gradle.log'))+list(owned.rglob('child-*.log'))
hits=[]
for p in diagnostics:
    b=p.read_bytes()
    if any(x in b for x in [b'FAKE_T0_OLD_',b'FAKE_T0_NEW_',b'FAKE_T0_FOREIGN_ACCOUNT',b'Authorization: Bearer ']):hits.append(p.name)
assert not hits
save(RAW/'diagnostic-audit.json',{'diagnosticsChecked':len(diagnostics),'knownDummyCredentialHits':hits,'limitations':'Sentinel/manual source review, not arbitrary-secret/heap/OS erasure proof; synthetic input bytes deliberately excluded'})
index=[]
for p in sorted(RAW.rglob('*')):
    if p.is_file() and p.name!='index.json':
        b=p.read_bytes();index.append({'path':p.relative_to(OUT).as_posix(),'sha256':sha(b),'bytes':len(b)})
save(RAW/'index.json',index)
print(json.dumps({'rawFiles':len(index),'fixtureFiles':len(inventory),'childExecutions':child_exits,'finalJUnit':119,'sourceMainChanged':False}))
