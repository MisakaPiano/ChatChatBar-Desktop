"""Verify preserved synthetic evidence; never inspect any ambient application root."""
from pathlib import Path
import hashlib, io, json, subprocess, tarfile, sys

raw = Path(__file__).resolve().parent
repo = raw.parents[4]
sha = lambda b: hashlib.sha256(b).hexdigest()
load = lambda name: json.loads((raw / name).read_text(encoding='utf-8'))
source = repo / 'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/P11S2OwnedMediaSafetyProbeTest.kt'
assert source.read_bytes() == (raw / 'final/probe-source.kt.txt').read_bytes()
results = load('final/results.json')
assert len(results['suites']) == 10
assert results['totals'] == dict(tests=88, failures=0, errors=0, skipped=0)
assert (raw / 'final-exit.txt').read_text(encoding='utf-8-sig').strip() == '0'
assert (raw / 'probe-exit.txt').read_text(encoding='utf-8-sig').strip() == '0'
receipts = sorted((raw / 'final/probe-records').glob('*-cleanup.txt'))
assert len(receipts) == 12
for path in receipts:
    assert path.read_text() == 'OWNED_TEMP_REMOVED=true ROOT_LOCK_RELEASED=true COORDINATOR=CLOSED'
for path in (raw / 'final/probe-records').glob('*-released-final.json'):
    value = json.loads(path.read_text())
    assert value['rootOwned'] is False and value['coordinator'] == 'CLOSED'
assert len(list((raw / 'final/probe-records').glob('*-released-final.json'))) == 12
for path in (raw / 'final/probe-records').glob('*-media.fixture.bin'):
    assert path.read_bytes() == bytes([0,1,2,3,255,10])
trees = load('production-tree-identities.json')
assert len(trees) == 3 and all(v['equal'] and v['workspaceDiffEmpty'] for v in trees)
production = load('production-source-identities.json')
assert len(production) == 18 and all(v['gitDiffEmpty'] for v in production)
tests = load('test-source-identities.json')
assert len(tests) == 13
for entry in tests:
    current = (repo / entry['path']).read_bytes()
    assert len(current) == entry['bytes'] and sha(current) == entry['sha256']
summary = dict(finalSuites=10, finalTests=88, failures=0, errors=0, junitSkipped=0,
               fixtureLifetimesReleased=12, productionSourcesUnchanged=18, productionTreesUnchanged=3,
               testSourceIdentities=13, pendingQueueRuntime='NOT_TESTED / NEEDS_ADAPTER',
               linksAndDowngrade='NOT_TESTED', secretAccess=False, guiLaunched=False, androidRun=False)
if len(sys.argv) > 1:
    revision = sys.argv[1]
    prefix = raw.relative_to(repo).as_posix() + '/'
    archive = subprocess.check_output(['git','archive','--format=tar',revision,prefix], cwd=repo)
    entries = load('evidence-sha256.json')
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for entry in entries:
            member = tar.getmember(prefix + entry['path'])
            data = tar.extractfile(member).read()
            assert len(data) == entry['bytes'] and sha(data) == entry['sha256'], entry['path']
        index = tar.extractfile(prefix + 'evidence-sha256.json').read()
        assert index == (raw / 'evidence-sha256.json').read_bytes()
    summary.update(committedRevision=revision, committedRawFilesVerified=len(entries),
                   indexSha256=sha(index))
print(json.dumps(summary, indent=2))
