"""Only this owned synthetic evidence scope and explicitly identified Git objects."""
from pathlib import Path
import hashlib, io, json, subprocess, tarfile, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[4]
OUT=Path(__file__).resolve().parent
RAW=OUT/'raw'
BASE='243168e97609f45d8265a2e6ba77db09f6bfa125'
SOURCE='a0b0a0707142a8b9a2c7bfd4d33acb106138ae0f'
def git(*args):return subprocess.check_output(['git','-C',str(ROOT),*args])
def sha(b):return hashlib.sha256(b).hexdigest()

for name in ['DesktopUpdateChecker.kt','DesktopUpdateCheckerTest.kt']:
    sub='main' if name=='DesktopUpdateChecker.kt' else 'test'
    p=f'app/desktopApp/src/{sub}/kotlin/com/example/chatbar/desktop/{name}'
    assert git('show',SOURCE+':'+p).replace(b'\r\n',b'\n')==(RAW/'green'/name).read_bytes().replace(b'\r\n',b'\n')
sourceFiles=['app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/'+n for n in
             ['DesktopUpdateChecker.kt','DesktopUpdatePanel.kt','DesktopPrimaryShell.kt','Main.kt','DesktopDataDirectory.kt']]
sourceFiles+=['app/desktopApp/build.gradle.kts']
identities=[{'path':p,'blob':git('rev-parse',SOURCE+':'+p).decode().strip(),'sha256':sha(git('show',SOURCE+':'+p))} for p in sourceFiles]
(RAW/'source-identities.json').write_text(json.dumps(identities,indent=2))
assert not git('diff',BASE,'HEAD','--','app/sharedCore','app/app','app/desktopApp/build.gradle.kts').strip()
for stage,fail in [('red',2),('green',0)]:
    xml=[ET.parse(p).getroot() for p in (RAW/stage/'junit').glob('TEST-*.xml')]
    assert len(xml)==3 and sum(int(x.attrib['tests']) for x in xml)==45
    assert sum(int(x.attrib['failures']) for x in xml)==fail
    assert all(int(x.attrib[k])==0 for x in xml for k in ['errors','skipped'])

before=json.loads((RAW/'synthetic-inputs.json').read_text())
fixture=RAW/'synthetic-fixture';fixture.mkdir(exist_ok=True)
for path,identity in before['data'].items():
    b=(ROOT/'app/desktopApp/build/preview-b1/isolated/data'/path).read_bytes()
    assert sha(b)==identity['sha256']
    dest=fixture/path;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(b)
assert json.loads((RAW/'processes-after.json').read_text(encoding='utf-8-sig'))==[]
live=json.loads((RAW/'windows-902/processes-live.json').read_text(encoding='utf-8-sig'))
assert len(live)==2 and live[1]['ParentProcessId']==live[0]['ProcessId']
(RAW/'exit-code-limits.json').write_text(json.dumps({'launcher901':0,'launcher902':0,'jvmChildExitCodes':'UNKNOWN','allScopedProcessesAbsentAfterClose':True,'note':'Separate same-executable runtime child observed for902; no child process handle retained for its actual exit code. Module query on launcher returned no evidence; no in-process JVM claim.'},indent=2))
diagnostics=list(RAW.rglob('*.log'))+list(RAW.rglob('TEST-*.xml'))
hits=[]
for p in diagnostics:
    text=p.read_bytes().decode('utf-8',errors='replace')
    for token in ['Authorization: Bearer ', 'R0_SYNTHETIC_KEY_NEVER_REAL','FAKE_G2B_SOURCE_NOT_A_REAL_KEY']:
        if token in text:hits.append(p.relative_to(OUT).as_posix())
assert not hits
(RAW/'diagnostic-audit.json').write_text(json.dumps({'files':len(diagnostics),'knownSecretSentinelHits':hits,'realData':'NONE_USED','limit':'Synthetic-only source/call-site review and sentinel checks, not a universal secret detector'},indent=2))
index=[]
for p in sorted(RAW.rglob('*')):
    if p.is_file() and p.name!='index.json':
        b=p.read_bytes();index.append({'path':p.relative_to(OUT).as_posix(),'bytes':len(b),'sha256':sha(b)})
(RAW/'index.json').write_text(json.dumps(index,indent=2)+'\n')
print(json.dumps({'rawFiles':len(index),'greenTests':45,'greenFailures':0,'originalSyntheticFilesVerified':8,'diagnosticHits':0}))
