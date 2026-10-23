"""Indexes only this run's public synthetic evidence and explicit source files."""
from pathlib import Path
import hashlib, json, subprocess, xml.etree.ElementTree as ET

repo = Path(__file__).resolve().parents[4]
evidence = repo / 'docs/desktop/evidence/prepreview-autonomous-20261023'
raw = evidence / 'raw'
def git(*args):
    return subprocess.check_output(['git', *args], cwd=repo).decode().strip()
def save(path, obj):
    path.write_text(json.dumps(obj, indent=2, ensure_ascii=True) + '\n', encoding='utf-8')
def identity(path):
    b = path.read_bytes()
    return {'bytes': len(b), 'sha256': hashlib.sha256(b).hexdigest()}

sources = [
 'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/DesktopFullBackupMapping.kt',
 'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/FullBackupHiddenMediaSafetyTest.kt',
 'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/P11S2OwnedMediaSafetyProbeTest.kt',
 'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/P11S2RootParticipationProbeTest.kt']
sources += [p.relative_to(repo).as_posix() for p in (repo/'tools/desktop/interop/prepreview').rglob('*') if p.suffix in ('.kt', '.py', '.ps1')]
snapshot = raw / 'final-source'
snapshot.mkdir(exist_ok=True)
items = {}
for source in sources:
    path = repo / source
    (snapshot/path.name).write_bytes(path.read_bytes())
    items[source] = {**identity(path), 'gitBlob': git('hash-object', '--path='+source, source)}
save(raw/'source-identities.json', items)
rounds = {}
for folder in raw.iterdir():
    if not folder.is_dir() or not (folder/'exit.txt').exists():
        continue
    row = {'exit': int((folder/'exit.txt').read_text(encoding='utf-8-sig').strip()), 'suites': 0,
           'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    for path in (folder/'junit').glob('*.xml'):
        root = ET.parse(path).getroot(); row['suites'] += 1
        for key in ('tests', 'failures', 'errors', 'skipped'): row[key] += int(root.attrib.get(key, 0))
    rounds[folder.name] = row
save(raw/'junit-rounds.json', rounds)
changed_main = [p for p in git('diff','--name-only','cac1a811').splitlines() if '/src/main/' in p]
save(raw/'boundaries.json', {'base': 'cac1a811a21e6f99181c716e438b6b8df8dbf4f7',
 'existingMainChanged': changed_main, 'expectedOnlyMapping': changed_main == [sources[0]],
 'formalBaselineBlobUnchanged': not git('diff','cac1a811','--','docs/desktop/10_UPSTREAM_BASELINE.json'),
 'historicalEvidenceUnchanged': not git('diff','--name-only','cac1a811','--','docs/desktop/evidence',
                                     ':!docs/desktop/evidence/prepreview-autonomous-20261023')})
index = {p.relative_to(raw).as_posix(): identity(p) for p in sorted(raw.rglob('*')) if p.is_file() and p != raw/'index.json'}
save(raw/'index.json', index)
print(json.dumps({'indexed': len(index), 'rounds': rounds, 'mainDelta': changed_main}))
