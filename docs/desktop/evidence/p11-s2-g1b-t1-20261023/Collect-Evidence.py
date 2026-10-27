"""Collect only explicitly test-owned T1 outputs. Never discover user roots or backups."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

repo = Path(__file__).resolve().parents[4]
evidence = Path(__file__).resolve().parent
raw = evidence / 'raw'
base = 'e90fc229e6ea057bbbc05d12b943608811885cbe'
main = 'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/'
test = 'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/backup/'
new_files = [main+'DesktopTargetIdentityEvidence.kt', main+'ExplicitSyntheticDirectoryWitnessReader.kt', test+'DesktopTargetIdentityEvidenceTest.kt']

def git(*args):
    return subprocess.check_output(['git', *args], cwd=repo).decode('utf-8').strip()

def digest(data):
    return hashlib.sha256(data).hexdigest()

def write(name, value):
    (raw/name).write_text(json.dumps(value, indent=2)+'\n', encoding='utf-8')

summary = {}
for round_name in ('first', 'red', 'green', 'final'):
    folder = raw/round_name
    rows = []
    for xml in sorted((folder/'junit').glob('TEST-*.xml')):
        root = ET.parse(xml).getroot()
        row = {'suite':root.attrib['name'], **{k:int(root.attrib[k]) for k in ('tests','failures','errors','skipped')}}
        assert len(root.findall('testcase')) == row['tests']
        rows.append(row)
    summary[round_name] = {'suites':len(rows), 'exit':int((folder/'exit.txt').read_text()),
        **{k:sum(r[k] for r in rows) for k in ('tests','failures','errors','skipped')}, 'rows':rows}
assert (summary['first']['tests'],summary['first']['failures'],summary['first']['exit']) == (1,1,1)
assert (summary['red']['tests'],summary['red']['failures'],summary['red']['exit']) == (21,3,1)
for round_name in ('green','final'):
    assert (summary[round_name]['suites'],summary[round_name]['tests'],summary[round_name]['failures'],summary[round_name]['errors'],summary[round_name]['skipped'],summary[round_name]['exit']) == (12,175,0,0,0,0)
write('execution-summary.json', summary)

identities = []
for name in new_files:
    data = (repo/name).read_bytes()
    snapshot = raw/'final/source'/Path(name).name
    assert data == snapshot.read_bytes()
    identities.append({'path':name, 'bytes':len(data), 'sha256':digest(data), 'gitBlob':git('hash-object',name)})
write('new-source-identities.json', identities)

old_sources = [main+n+'.kt' for n in ('DesktopCredentialImportPlan','DesktopCredentialSourceCensus','VerifiedCredentialSourceMetadata','DesktopCredentialTargetObservation','DesktopBackupReadOnlyInspector')]
old_sources += ['app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/'+n+'.kt' for n in ('security/WindowsSecretRootResolver','security/DesktopSecretStore','security/WindowsSecretStore','DesktopDataRootOwnership','DesktopDocumentIndexReadEvidence')]
old_sources += [test+'CensusWireFixtures.kt', 'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/g1bt0/T0RecoveryLab.kt', 'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/g1bt0/T0RecoveryLabTest.kt']
source_ids=[]
for name in old_sources:
    original=git('rev-parse', f'{base}:{name}')
    assert git('hash-object', name) == original
    source_ids.append({'path':name,'commit':base,'gitBlob':original})
official='1198a13cf3008de1358b8a69aa5e40b64797c8d9'
for name in ('.agents/skills/chatbar-app-backup/SKILL.md','app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupModels.kt'):
    source_ids.append({'path':name,'commit':official,'gitBlob':git('rev-parse',f'{official}:{name}')})
write('source-authority.json',source_ids)

# Known existing source/Gradle paths only. Untracked user attachments are not read or hashed.
projection=git('diff','--name-only',base,'--','app/desktopApp/src/main','app/sharedCore/src/main','app/app/src/main','app/desktopApp/src/test','app/desktopApp/build.gradle.kts').splitlines()
assert not [name for name in projection if name not in new_files]
needle=re.compile(r'\b(DesktopTargetIdentityEvidence|ExplicitSyntheticDirectoryWitnessReader|DeclaredTargetConsumer|TargetIdentityEvidence|TargetDirectoryWitnessReader|TargetConsumerEvidence)\b')
calls=[]
for name in git('ls-tree','-r','--name-only',base,'app/desktopApp/src/main').splitlines():
    if name.endswith('.kt') and needle.search((repo/name).read_text(encoding='utf-8')):
        calls.append(name)
assert not calls
new_text='\n'.join((repo/n).read_text(encoding='utf-8') for n in new_files[:2])
for pattern in (r'System\.getenv\(',r'Files\.(walk|list|readAllBytes|write|createDirectory)',r'\.load\(',r'\.save\(',r'\.delete\(',r'scheduleRestore\(',r'WindowsSecretStore\.create\('):
    assert not re.search(pattern,new_text), pattern
write('nonwiring-boundaries.json', {'base':base,'existingTrackedSourceOrGradleChanges':[], 'externalMainCallSites':calls,
    'newMainFiles':new_files[:2], 'runtimeSecretOrRestoreCalls':False,
    'note':'Static call-site/projection review, not a proof against malicious reflection or future wiring.'})

# Only T1-owned files with known fixture names; do not sweep any historic test/user directories.
fixture_root=repo/'app/desktopApp/build/g1b-t1'
objects=raw/'objects'; objects.mkdir(exist_ok=True)
allowed={'root.id','native-observation.txt','data','before-after-inventory.txt','synthetic.cbbackup','ordinary-file'}
inventory=[]
for path in sorted(fixture_root.rglob('*')):
    assert not path.is_symlink(), 'Unexpected link in owned fixture tree'
    if not path.is_file():
        continue
    assert path.name in allowed, 'Unexpected file in owned fixture tree'
    data=path.read_bytes(); sha=digest(data); target=objects/sha
    if target.exists(): assert target.read_bytes()==data
    else: target.write_bytes(data)
    inventory.append({'path':path.relative_to(fixture_root).as_posix(),'bytes':len(data),'sha256':sha,'object':'objects/'+sha})
write('fixture-inventory.json',inventory)

markers=('PRIVATE_T1_IDENTITY_PATH','PRIVATE_T1_STORE_TOKEN','PRIVATE_T1_CANCEL','SYNTHETIC_PRIVATE_T1_BODY','FAKE_G2B_SOURCE_NOT_A_REAL_KEY')
checked=[]
for folder in ('first','red','green','final'):
    for path in (raw/folder).rglob('*'):
        if path.suffix in ('.log','.xml'):
            value=path.read_text(encoding='utf-8',errors='strict')
            assert not any(marker in value for marker in markers), 'Diagnostic marker leak'
            checked.append(path.relative_to(raw).as_posix())
write('diagnostic-audit.json',{'filesChecked':checked,'syntheticSentinelLeaks':0,
    'scope':'Known synthetic sentinels in logs/XML; fixture/source bytes intentionally contain fictitious markers. Not a general secret-detection proof.'})

patch=b''
for name in new_files:
    result=subprocess.run(['git','diff','--no-index','--','/dev/null',name],cwd=repo,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    assert result.returncode==1
    patch+=result.stdout
patch+=subprocess.check_output(['git','diff',base,'--','.agents/skills/chatbar-desktop-full-backup/SKILL.md'],cwd=repo)
(evidence/'CODE_DIFF.patch').write_bytes(patch)

index=[]
for path in sorted(raw.rglob('*')):
    if path.is_file() and path.name!='index.json':
        data=path.read_bytes(); index.append({'path':path.relative_to(raw).as_posix(),'bytes':len(data),'sha256':digest(data)})
write('index.json',index)
print(json.dumps({'rawFiles':len(index),'fixtureFiles':len(inventory),'finalSuites':12,'finalTests':175,'newTests':23,'rerunTests':152,'diagnosticLeaks':0}))
