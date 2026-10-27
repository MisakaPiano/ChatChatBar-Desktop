"""R1 source/fixture/JUnit byte audit; no user files or credentials."""
import hashlib,io,json,re,shutil,subprocess,sys,tarfile
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[5]; RAW=Path(__file__).resolve().parent
BASE='40f0f3dec9b6f1448314ca25804bb9091e7751d2'
EVID='docs/desktop/evidence/p11-s2-g2b-r1-20261023/'
SOURCE='app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/DesktopCredentialSourceCensus.kt'
TEST='app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/backup/DesktopCredentialSourceCensusTest.kt'
def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT)
def sha(b):return hashlib.sha256(b).hexdigest()
def dump(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n',encoding='utf8')
def identity(path,ref=None):
    b=git('show',ref+':'+path) if ref else (ROOT/path).read_bytes();c=b if ref else b.replace(b'\r\n',b'\n')
    return dict(path=path,ref=ref,bytes=len(b),sha256=sha(b),canonicalSha256=sha(c),gitBlob=hashlib.sha1(b'blob '+str(len(c)).encode()+b'\0'+c).hexdigest())
def collect():
    build=ROOT/'app/desktopApp/build';green=RAW/'green';green.mkdir(exist_ok=True)
    classes=json.loads((ROOT/'docs/desktop/evidence/p11-s2-g2b-20261023/raw/green/junit-summary.json').read_text())
    results=[]
    for old in classes:
        p=build/'test-results/test'/('TEST-'+old['name']+'.xml');target=green/'junit'/p.name
        target.parent.mkdir(exist_ok=True);shutil.copyfile(p,target);node=ET.parse(target).getroot()
        results.append(dict(name=node.attrib['name'],**{k:int(node.attrib[k]) for k in ['tests','failures','errors','skipped']},sha256=sha(target.read_bytes())))
    dump(green/'junit-summary.json',results)
    for p in [SOURCE,TEST]:
        target=green/'source'/Path(p).name;target.parent.mkdir(exist_ok=True);shutil.copyfile(ROOT/p,target)
    dump(RAW/'tested-source-identities.json',[identity(p) for p in [SOURCE,TEST]])
    paths=[x.decode() for x in git('ls-files','app/desktopApp/src/test').splitlines() if any(('/'+c['name'].split('.')[-1]+'.kt').encode() in x for c in classes)]
    paths+=['app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/backup/'+n for n in ['CensusWireFixtures.kt','OfficialBackupWireFixtures.kt','Official147FixtureCodec.kt']]
    dump(RAW/'test-and-fixture-source-identities.json',[identity(p) for p in sorted(set(paths))])
    for p in sorted((build/'p11-s2-g2b/fixtures').glob('r1-*')):
        target=green/'fixtures'/p.name;target.parent.mkdir(exist_ok=True);shutil.copyfile(p,target)
    dump(RAW/'fixture-identities.json',[dict(path=str(p.relative_to(green)),bytes=p.stat().st_size,sha256=sha(p.read_bytes())) for p in sorted((green/'fixtures').iterdir())])
    pin='1198a13cf3008de1358b8a69aa5e40b64797c8d9';official='app/app/src/main/java/com/example/chatbar/domain/community/CommunityModels.kt'
    dump(RAW/'official-source-identity.json',identity(official,pin))
    source=git('show',pin+':'+official).decode();assert 'val expiresAtMillis: Long = 0L' in source
    literals=['FAKE_G2B_SOURCE_NOT_A_REAL_KEY','FAKE_G2B_PASSWORD_NOT_REAL','PRIVATE_G2B_STAMP','PRIVATE_G2B_TARGET_FAILURE']
    diagnostics=list(RAW.glob('*.log'))+list(RAW.rglob('*.xml'));hits=[]
    for p in diagnostics:
        if any(s in p.read_text(errors='replace') for s in literals):hits.append(str(p.relative_to(RAW)))
    assert not hits,hits
    dump(RAW/'redaction-audit.json',dict(diagnosticFiles=len(diagnostics),knownLiterals=len(literals),hits=hits,scope='Known synthetic literals in diagnostic logs/XML; explicit synthetic fixtures/source retain fake values.'))
    print(json.dumps(dict(suites=len(results),**{k:sum(x[k] for x in results) for k in ['tests','failures','errors','skipped']})))
def index():dump(RAW/'raw-index.json',[dict(path=str(p.relative_to(RAW)).replace('\\','/'),bytes=p.stat().st_size,sha256=sha(p.read_bytes())) for p in sorted(RAW.rglob('*')) if p.is_file() and p.name!='raw-index.json'])
def verify(ref):
    changed=git('diff','--name-only',BASE,ref).decode().splitlines();assert all(p in [SOURCE,TEST] or p.startswith(EVID) for p in changed),changed
    archive=tarfile.open(fileobj=io.BytesIO(git('archive',ref,EVID)),mode='r:')
    items=json.loads(archive.extractfile(EVID+'raw/raw-index.json').read())
    for item in items:
        b=archive.extractfile(EVID+'raw/'+item['path']).read();assert len(b)==item['bytes'] and sha(b)==item['sha256'],item['path']
    for item in json.loads(archive.extractfile(EVID+'raw/tested-source-identities.json').read()):assert sha(git('show',ref+':'+item['path']))==item['canonicalSha256']
    for prefix in ['app/desktopApp/src/main','app/sharedCore/src/main','app/app/src/main']:
        actual=git('diff','--name-only',BASE,ref,'--',prefix).decode().splitlines();assert actual==([SOURCE] if 'desktopApp' in prefix else [])
    print(json.dumps(dict(reference=ref,rawVerified=len(items),testedSources=2,onlyProductionChange=SOURCE),indent=2))
if __name__=='__main__':
    if sys.argv[1]=='collect':collect()
    elif sys.argv[1]=='index':index()
    elif sys.argv[1]=='verify':verify(sys.argv[2])
