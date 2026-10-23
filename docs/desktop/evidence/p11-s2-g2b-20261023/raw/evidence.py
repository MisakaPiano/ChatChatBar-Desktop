"""G2B bounded evidence: only repo sources and self-generated synthetic build artifacts."""
import hashlib, io, json, re, shutil, subprocess, sys, tarfile, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[5]
RAW=Path(__file__).resolve().parent
CONTROL='0db537d37e309550833c1e3693e0b4d0284e897b'
PIN='1198a13cf3008de1358b8a69aa5e40b64797c8d9'
MAIN='app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/'
TEST='app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/backup/'
SOURCES=[MAIN+n for n in ['DesktopBackupReadOnlyInspector.kt','DesktopCredentialSourceCensus.kt','VerifiedCredentialSourceMetadata.kt','DesktopCredentialTargetObservation.kt']]+[TEST+n for n in ['CensusWireFixtures.kt','DesktopCredentialSourceCensusTest.kt']]
CLASSES=['DesktopCredentialSourceCensusTest','DesktopCredentialImportPlanTest','RecoverableIntentContractTest','G2AOfficialWireTest','P11S2CredentialTransactionBoundaryProbeTest','WindowsSecretStoreTest','DesktopSecureModelRepositoryTest','DesktopSecureEmbeddingRepositoryTest','DesktopFullBackupArchiveTest','DesktopFullBackupMappingTest','DesktopBackupReadOnlyInspectorTest','MomentArchiveReadOnlyValidatorTest','DesktopFullBackupCommitJournalTest','DesktopStartupGateTest','DesktopReadOnlyBackupRedTest']
def git(*args): return subprocess.check_output(['git',*args],cwd=ROOT)
def sha(b): return hashlib.sha256(b).hexdigest()
def dump(p,obj):
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(obj,indent=2,ensure_ascii=False)+'\n',encoding='utf8')
def ident(path,ref=None):
    b=git('show',ref+':'+path) if ref else (ROOT/path).read_bytes()
    canonical=b if ref else b.replace(b'\r\n',b'\n')
    return dict(path=path,commit=ref,bytes=len(b),sha256=sha(b),canonicalSha256=sha(canonical),canonicalBytes=len(canonical),gitBlob=hashlib.sha1(b'blob '+str(len(canonical)).encode()+b'\0'+canonical).hexdigest())
def capture():
    build=ROOT/'app/desktopApp/build';out=RAW/'green';out.mkdir(exist_ok=True)
    for path in SOURCES:
        target=out/'source'/Path(path).name;target.parent.mkdir(exist_ok=True);shutil.copyfile(ROOT/path,target)
    dump(RAW/'tested-source-identities.json',[ident(p) for p in SOURCES])
    summary=[]
    for name in CLASSES:
        files=list((build/'test-results/test').glob('TEST-*.'+name+'.xml'));assert len(files)==1,name
        target=out/'junit'/files[0].name;target.parent.mkdir(exist_ok=True);shutil.copyfile(files[0],target)
        node=ET.parse(target).getroot();summary.append(dict(name=node.attrib['name'],**{k:int(node.attrib[k]) for k in ['tests','failures','errors','skipped']},sha256=sha(target.read_bytes())))
    dump(RAW/'green/junit-summary.json',summary)
    shutil.copytree(build/'p11-s2-g2b/fixtures',out/'fixtures',dirs_exist_ok=True)
    # Original plaintext synthetic golden JSON, extracted without rewriting bytes.
    b=(out/'fixtures/positive-false.cbbackup').read_bytes();length=int.from_bytes(b[8:12],'big')
    with zipfile.ZipFile(io.BytesIO(b[12+length:])) as z:
        for i,name in enumerate(z.namelist()):
            target=out/'wire'/f'{i:02d}.bin';target.parent.mkdir(exist_ok=True);target.write_bytes(z.read(name))
        dump(out/'wire/names.json',dict(enumerate(z.namelist())))
    dump(RAW/'fixture-identities.json',[dict(path=str(p.relative_to(out)).replace('\\','/'),bytes=p.stat().st_size,sha256=sha(p.read_bytes())) for p in sorted((out/'fixtures').iterdir())])
    old=json.loads((ROOT/'docs/desktop/evidence/p11-s2-g2a-20261023/raw/existing-production-identities.json').read_text())
    boundaries=[]
    for item in old:
        if item['path']==MAIN+'DesktopBackupReadOnlyInspector.kt':continue
        p=item['path'];current=ident(p);assert current['sha256']==item['workspaceSha256'],p
        boundaries.append(dict(**ident(p,CONTROL),workspaceSha256=current['sha256'],unchanged=True))
    dump(RAW/'unchanged-executing-boundaries.json',boundaries)
    dump(RAW/'inspector-before-identity.json',ident(MAIN+'DesktopBackupReadOnlyInspector.kt',CONTROL))
    official=['.agents/skills/chatbar-app-backup/SKILL.md','app/app/build.gradle.kts']+['app/app/src/main/java/com/example/chatbar/'+p for p in ['data/local/entity/ModelConfig.kt','data/local/entity/AppSettings.kt','domain/backup/AppBackupModels.kt','domain/backup/BackupPreferenceTransfer.kt','domain/backup/BackupDataRegistry.kt','domain/backup/BackupEntityValidator.kt','domain/community/CommunityModels.kt','domain/appearance/ThemeColor.kt']]
    dump(RAW/'official-source-identities.json',[ident(p,PIN) for p in official])
    paths=[p.decode() for p in git('ls-files','app/desktopApp/src/test').splitlines() if any(('/'+name+'.kt').encode() in p for name in CLASSES)]
    paths += [TEST+'OfficialBackupWireFixtures.kt',TEST+'Official147FixtureCodec.kt']
    dump(RAW/'regression-test-identities.json',[ident(p) for p in sorted(set(paths))])
    needles=set(re.findall(r'(?:FAKE_G2B|PRIVATE_G2B|FAKE_P11_G1A)_[A-Z0-9_]+','\n'.join((ROOT/p).read_text() for p in SOURCES if '/test/' in p)+(ROOT/'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/P11S2CredentialTransactionBoundaryProbeTest.kt').read_text()))
    logs=list(RAW.glob('*.log'))+list(RAW.rglob('*.xml'))
    hits=[str(p.relative_to(RAW)) for p in logs if any(n in p.read_text(errors='replace') for n in needles)]
    assert not hits,hits
    dump(RAW/'redaction-audit.json',dict(files=len(logs),knownSyntheticNeedles=len(needles),hits=hits,note='Diagnostic logs/XML only. Explicit synthetic fixtures/source contain fake values; no real credentials used.'))
    print(json.dumps(dict(suites=len(summary),**{k:sum(x[k] for x in summary) for k in ['tests','failures','errors','skipped']})))
def index():
    dump(RAW/'raw-index.json',[dict(path=str(p.relative_to(RAW)).replace('\\','/'),bytes=p.stat().st_size,sha256=sha(p.read_bytes())) for p in sorted(RAW.rglob('*')) if p.is_file() and p.name!='raw-index.json'])
def verify(ref):
    paths=[x['path'] for x in json.loads((RAW/'tested-source-identities.json').read_text())]
    changed=git('diff','--name-only',CONTROL,ref).decode().splitlines()
    prefix='docs/desktop/evidence/p11-s2-g2b-20261023/'
    allowed=set(paths)|{'.agents/skills/chatbar-desktop-full-backup/SKILL.md','docs/desktop/171_P11_S2_G2B_BOUNDED_CENSUS_USER_AUTHORIZATION.md'}
    assert all(p in allowed or p.startswith(prefix) for p in changed),changed
    archive=tarfile.open(fileobj=io.BytesIO(git('archive',ref,prefix)),mode='r:')
    records=json.loads(archive.extractfile(prefix+'raw/raw-index.json').read())
    for item in records:
        data=archive.extractfile(prefix+'raw/'+item['path']).read();assert len(data)==item['bytes'] and sha(data)==item['sha256'],item['path']
    for item in json.loads((RAW/'tested-source-identities.json').read_text()):
        assert sha(git('show',ref+':'+item['path']))==item['canonicalSha256'],item['path']
    projections=[]
    for tree in ['app/desktopApp/src/main','app/sharedCore/src/main','app/app/src/main']:
        old={line.split('\t')[1]:line.split()[2] for line in git('ls-tree','-r',CONTROL,'--',tree).decode().splitlines()}
        now={line.split('\t')[1]:line.split()[2] for line in git('ls-tree','-r',ref,'--',tree).decode().splitlines()}
        assert all(now.get(p)==blob for p,blob in old.items() if p!=MAIN+'DesktopBackupReadOnlyInspector.kt')
        assert set(now)-set(old)==(set(SOURCES[1:4]) if 'desktopApp' in tree else set())
        projections.append(dict(tree=tree,originalFiles=len(old),onlyExistingChange=MAIN+'DesktopBackupReadOnlyInspector.kt' if 'desktopApp' in tree else None))
    print(json.dumps(dict(reference=ref,rawFilesVerified=len(records),testedSourcesVerified=len(paths),projections=projections),indent=2))
if __name__=='__main__':
    if sys.argv[1]=='capture':capture()
    elif sys.argv[1]=='index':index()
    elif sys.argv[1]=='verify':verify(sys.argv[2])
