from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import sys

R = Path.cwd()
G = Path(__file__).resolve().parent
prefix = 'docs/desktop/evidence/p8-s2b-ui-layout-001-r6i-20261019'
E = R / prefix
BASE = '8f9a96a9432a350f0d049db3d01613a8da88b6b5'

def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.PIPE)

def sha(b):
    return hashlib.sha256(b).hexdigest()

def save(name, obj):
    (E / name).write_text(json.dumps(obj, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')

assert git('rev-parse', 'HEAD').decode().strip() == BASE
assert git('branch', '--show-current').decode().strip() == 'feature/phase8-fish-audio'
for item in json.loads((E / 'prepared-source-identities.json').read_text(encoding='utf-8')):
    assert sha((R / item['path']).read_bytes()) == item['expected']
    assert (R / item['path']).read_bytes() == (E / 'prepared-sources' / Path(item['path']).name).read_bytes()
initial = json.loads((E / 'initial-identities.json').read_text(encoding='utf-8'))
others = [p for p in git('ls-files', '--others', '--exclude-standard').decode().splitlines() if not p.startswith(prefix + '/')]
assert sorted(others) == sorted(initial['untrackedPathsOnly'])
assert sorted(git('diff', '--name-only').decode().splitlines()) == sorted(i['path'] for i in initial['sources'][:2])
for h in json.loads((E / 'history-preservation.json').read_text(encoding='utf-8')):
    assert git('rev-parse', 'HEAD:' + h['path']).decode().strip() == h['treeAtStart']
    assert not git('diff', 'HEAD', '--', h['path']).strip()
for item in json.loads((E / 'phase-A-preparation/manifest.json').read_text(encoding='utf-8'))['members']:
    b = (E / 'phase-A-preparation' / item['path']).read_bytes()
    assert len(b) == item['bytes'] and sha(b) == item['sha256']
staged = [p for p in git('diff', '--cached', '--name-only', '-z').decode().split('\0') if p]
assert staged and all(p.startswith(prefix + '/') for p in staged)
if '--prepare' in sys.argv:
    shutil.copyfile(__file__, E / Path(__file__).name)
    paths = [p.relative_to(R).as_posix() for p in E.rglob('*') if p.is_file() and
             p.suffix in ['.md', '.json', '.py', '.ps1', '.gradle', '.kt'] and p.name != 'arming-source-excerpt.kt']
    meta = subprocess.run(['git', '-c', 'core.whitespace=cr-at-eol', 'diff', '--cached', '--check', '--', *paths], capture_output=True)
    assert meta.returncode == 0, (meta.stdout + meta.stderr).decode(errors='replace')
    raw = subprocess.run(['git', '-c', 'core.whitespace=cr-at-eol', 'diff', '--cached', '--check', '--', prefix], capture_output=True)
    (E / 'diff-check-closeout-raw.log').write_bytes(raw.stdout + raw.stderr)
    links = re.findall(r'\]\(([^)]+)\)', (E / 'README.md').read_text(encoding='utf-8'))
    assert all((E / link).is_file() for link in links)
    save('static-validation.json', dict(metadataAndSourceCopiesDiffCheckExit=meta.returncode,
        rawFullDiffCheckExit=raw.returncode, rawWarningsPreserved=True,
        excerptIsVerbatimSourceNotStandaloneNewKotlin=True,
        markdownTargetsChecked=len(links), allTargetsExist=True,
        phaseAManifestReverified=True, sourceDraftsFrozenUnstaged=True, historicalEvidenceUnchanged=True,
        protectedUserFilesPathsOnly=True, manualLaunches=1, retries=0, junitInvocations=0,
        onlyNewEvidenceStaged=True))
    members = []
    for p in sorted(E.rglob('*')):
        if p.is_file() and p != E / 'manifest.json':
            b = p.read_bytes()
            members.append(dict(path=p.relative_to(E).as_posix(), bytes=len(b), sha256=sha(b)))
    save('manifest.json', dict(algorithm='SHA-256', rootManifestSelfExcluded=True,
        phaseAManifestIncluded=True, result='R6I-B INCONCLUSIVE', members=members))
    print(json.dumps(dict(members=len(members), manifestSha256=sha((E / 'manifest.json').read_bytes()),
        metadataDiffCheck=meta.returncode, rawDiffCheck=raw.returncode)))
else:
    manifest = json.loads((E / 'manifest.json').read_text(encoding='utf-8'))
    assert len(staged) == len(manifest['members']) + 1
    for m in manifest['members']:
        b = (E / m['path']).read_bytes()
        assert len(b) == m['bytes'] and sha(b) == m['sha256']
    entries = [e for e in git('ls-files', '--stage', '-z', '--', prefix).decode().split('\0') if e]
    blobs = []
    for e in entries:
        meta, path = e.split('\t')
        mode, oid, stage = meta.split()
        assert stage == '0'
        blobs.append((oid, path))
    data = subprocess.run(['git', 'cat-file', '--batch'], input=('\n'.join(oid for oid, p in blobs) + '\n').encode(), capture_output=True, check=True).stdout
    offset = 0
    for oid, path in blobs:
        end = data.index(b'\n', offset)
        header = data[offset:end].decode().split()
        size = int(header[2])
        b = data[end + 1:end + 1 + size]
        assert header[0] == oid and b == (R / path).read_bytes(), path
        offset = end + size + 2
    assert offset == len(data)
    print(json.dumps(dict(stagedEvidenceFiles=len(staged), verifiedRawBlobs=len(blobs),
        manifestSha256=sha((E / 'manifest.json').read_bytes()),
        sourceDraftsUnstaged=True, historyAndUserPathsProtected=True, result='INCONCLUSIVE')))
