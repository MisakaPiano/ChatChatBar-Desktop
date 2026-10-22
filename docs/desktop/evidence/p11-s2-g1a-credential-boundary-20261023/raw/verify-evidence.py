"""Verify explicit synthetic outputs/source identities and optionally exact committed Git bytes."""
from pathlib import Path
import hashlib, io, json, subprocess, sys, tarfile, xml.etree.ElementTree as ET
RAW = Path(__file__).resolve().parent
REPO = RAW.parents[4]
sha = lambda data: hashlib.sha256(data).hexdigest()
load = lambda name: json.loads((RAW / name).read_text(encoding='utf-8'))
source = REPO / 'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/P11S2CredentialTransactionBoundaryProbeTest.kt'
assert source.read_bytes() == (RAW/'green/probe-source.kt.txt').read_bytes()
results = load('green/results.json')
assert len(results['suites']) == 12 and results['totals'] == dict(tests=112,failures=0,errors=0,skipped=0)
for name in ['probe-exit.txt','final-exit.txt','final-verified-exit.txt','jdk-version-exit.txt']:
    assert (RAW/name).read_text(encoding='utf-8-sig').strip() == '0'
records = RAW/'green/probe-records'
receipts = list(records.glob('*-cleanup.txt'))
assert len(receipts) == 32
assert all(p.read_text() == 'OWNED_TEMP_REMOVED=true ROOT_LOCK_RELEASED=true FAKE_PROTECTOR_ONLY=true' for p in receipts)
finals = list(records.glob('*-released-final.json'))
assert len(finals) == 32
for path in finals:
    value = json.loads(path.read_text())
    assert value['rootOwned'] is False and value['coordinator'] == 'CLOSED'
archives = list(records.glob('*.fixture.cbbackup'))
assert len(archives) == 17 and (records/'wrong-password.fixture.cbbackup').is_file()
fake_values = [f'FAKE_P11_G1A_{p}_NOT_A_REAL_KEY'.encode() for p in ['OLD_A','OLD_B','NEW_A','NEW_B']]
for path in records.glob('*.protected.fixture.bin'):
    assert all(value not in path.read_bytes() for value in fake_values)
build = REPO/'app/desktopApp/build/p11-s2-g1a-credential-probe'
originals = list(build.iterdir())
copies = list(records.iterdir())
assert {p.name for p in originals} == {p.name for p in copies}
for path in originals:
    assert path.read_bytes() == (records/path.name).read_bytes(), path.name
production = load('production-source-identities.json')
assert len(production) == 21 and all(v['workspaceDiffEmpty'] for v in production)
trees = load('production-tree-identities.json')
assert len(trees) == 3 and all(v['controlTree']==v['headTree'] and v['workspaceDiffEmpty'] for v in trees)
tests = load('test-source-identities.json')
assert len(tests) == 15
assert len(load('official-source-identities.json')) == 9
for entry in tests:
    data = (REPO/entry['path']).read_bytes()
    assert len(data) == entry['bytes'] and sha(data) == entry['sha256'], entry['path']
diagnostic = load('diagnostic-secrecy.json')
assert diagnostic['newSyntheticCredentialValuesAbsent'] and diagnostic['knownSyntheticLiteralsScanned'] >= 6
summary = dict(finalSuites=12,finalTests=112,failures=0,errors=0,skipped=0,
               fixtureLifetimesReleased=32,sourceArchives=17,buildArtifactsByteVerified=len(originals),
               productionSourcesUnchanged=21,productionTreesUnchanged=3,testSourceIdentities=15,
               officialSourceIdentities=9,
               diagnostics=diagnostic,realDpapi='NOT_TESTED',crossDomainAtomicity='NOT_PROVEN')
if len(sys.argv)>1:
    revision = sys.argv[1]
    prefix = RAW.relative_to(REPO).as_posix()+'/'
    data = subprocess.check_output(['git','archive','--format=tar',revision,prefix],cwd=REPO)
    entries = load('evidence-sha256.json')
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for entry in entries:
            value = archive.extractfile(prefix+entry['path']).read()
            assert len(value)==entry['bytes'] and sha(value)==entry['sha256'], entry['path']
        index = archive.extractfile(prefix+'evidence-sha256.json').read()
        assert index == (RAW/'evidence-sha256.json').read_bytes()
    summary.update(committedRevision=revision,committedRawFilesVerified=len(entries),indexSha256=sha(index))
print(json.dumps(summary,indent=2))
