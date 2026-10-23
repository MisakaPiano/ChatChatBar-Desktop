"""Only explicitly listed Git sources and this invocation's synthetic evidence. No user-file discovery."""
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
EVIDENCE = Path(__file__).resolve().parent
BASE = '5c7238e55ebbfb65ea5a78cad32d5437a598de43'
OFFICIAL = '1198a13cf3008de1358b8a69aa5e40b64797c8d9'

def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args])

def identity(data):
    return {'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)}

def save(name, value):
    (EVIDENCE / 'raw' / name).write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')

sources = [
    'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/' + n + '.kt'
    for n in ['DesktopBackupReadOnlyInspector', 'MomentArchiveReadOnlyValidator', 'DesktopCredentialSourceCensus',
              'VerifiedCredentialSourceMetadata', 'AppBackupCodec', 'DesktopBackupPolicy']
] + ['app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopArchiveFiles.kt']
tests = ['app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/backup/' + n + '.kt'
         for n in ['OfficialBackupWireFixtures', 'Official147FixtureCodec', 'CensusWireFixtures',
                   'DesktopBackupReadOnlyInspectorTest', 'DesktopReadOnlyBackupRedTest',
                   'MomentArchiveReadOnlyValidatorTest', 'DesktopCredentialSourceCensusTest', 'DesktopCredentialImportPlanTest']]
tests += ['app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/DesktopFullBackupArchiveTest.kt']
official = ['.agents/skills/chatbar-app-backup/SKILL.md', 'app/app/build.gradle.kts'] + [
    'app/app/src/main/java/com/example/chatbar/domain/backup/' + n + '.kt'
    for n in ['AppBackupModels', 'AppBackupCodec', 'BackupDataRegistry']]
records = []
for revision, paths in [(BASE, sources + tests), (OFFICIAL, official)]:
    for path in paths:
        data = git('show', revision + ':' + path)
        records.append({'revision': revision, 'path': path,
                        'gitBlob': git('rev-parse', revision + ':' + path).decode().strip(), **identity(data)})
save('source-identities.json', records)

main_paths = ['app/desktopApp/src/main', 'app/sharedCore/src/main', 'app/app/src/main']
trees = []
for path in main_paths:
    assert not git('diff', BASE, '--', path).strip()
    trees.append({'path': path, 'baseTree': git('rev-parse', BASE + ':' + path).decode().strip(),
                  'headTree': git('rev-parse', 'HEAD:' + path).decode().strip(), 'trackedDiff': 'UNCHANGED'})
save('production-boundaries.json', trees)

tool = ROOT / 'tools/desktop/interop/readonly-r0'
tooling = []
for p in sorted(tool.rglob('*')):
    if p.is_file():
        path = p.relative_to(ROOT).as_posix()
        tooling.append({'path': path, 'gitBlobWithFilters': git('hash-object', '--path=' + path, path).decode().strip(), **identity(p.read_bytes())})
save('tooling-identities.json', tooling)

code = (tool / 'src/RealArchiveReadOnlyTool.kt').read_text(encoding='utf-8')
forbidden = ['scheduleRestore(', 'DesktopFullBackupArchive', 'WindowsSecretStore', 'observeAndPlan(',
             'System.getenv(', 'Files.write', 'Files.walk', 'readLine(', 'System.`in`', 'afterAuthentication(']
# readLine/stdin is mentioned only in the explanatory comment; inspect executable lines separately.
executable = '\n'.join(line for line in code.splitlines() if not line.strip().startswith('//'))
violations = [x for x in forbidden if x in executable]
assert not violations
save('static-audit.json', {'forbiddenExecutableTokens': forbidden, 'matches': violations,
                          'sourceSet': 'OPT_IN_TEST_ONLY', 'formalGradleChanged': False,
                          'interpretation': 'Token search plus manual call-site review, not universal security proof'})

summary = {}
for run in ['first', 'green']:
    rows = []
    for p in sorted((EVIDENCE / 'raw' / run / 'junit').glob('TEST-*.xml')):
        r = ET.parse(p).getroot()
        rows.append({'suite': r.attrib['name'], **{k: int(r.attrib.get(k, 0)) for k in ['tests','failures','errors','skipped']}})
    totals = {k: sum(row[k] for row in rows) for k in ['tests','failures','errors','skipped']}
    summary[run] = {'suites': len(rows), **totals, 'exit': int((EVIDENCE / 'raw' / run / 'exit.txt').read_text()), 'rows': rows}
    assert len(rows) == 7 and totals['failures'] == totals['errors'] == totals['skipped'] == 0
assert summary['first']['tests'] == 114 and summary['green']['tests'] == 117
save('junit-summary.json', summary)

# Scan only reports/diagnostics, not intentionally fake-valued fixture/source bytes.
markers = [b'R0_SYNTHETIC_KEY_NEVER_REAL', b'FAKE_G2B_SOURCE_NOT_A_REAL_KEY', b'FAKE_G2B_PASSWORD_NOT_REAL',
           b'synthetic-password', b'synthetic-private-source', b'synthetic-private-name']
paths = []
for run in ['first', 'green']:
    paths += list((EVIDENCE / 'raw' / run / 'junit').glob('TEST-*.xml'))
    paths += [EVIDENCE / 'raw' / run / 'gradle.log']
paths += list((EVIDENCE / 'raw' / 'reports').glob('*.txt'))
paths += list((EVIDENCE / 'raw' / 'launcher-green').glob('*.log'))
hits = []
for p in paths:
    data = p.read_bytes()
    # PowerShell may store diagnostic output as UTF16. ASCII marker byte patterns are checked in both forms.
    for marker in markers:
        if marker in data or marker.decode().encode('utf-16le') in data:
            hits.append({'path': p.relative_to(EVIDENCE).as_posix(), 'markerCategory': 'SYNTHETIC_SENTINEL'})
assert not hits
save('redaction-audit.json', {'diagnosticFiles': len(paths), 'knownSyntheticMarkers': len(markers), 'hits': hits,
     'limits': 'Sentinel check plus fixed report schema review; no claim of arbitrary-input heap/OS erasure. Synthetic fixture/source files intentionally excluded.'})

index = []
for p in sorted((EVIDENCE / 'raw').rglob('*')):
    if p.is_file() and p.name != 'raw-index.json':
        index.append({'path': p.relative_to(EVIDENCE).as_posix(), **identity(p.read_bytes())})
save('raw-index.json', index)
print(json.dumps({'indexedRawFiles': len(index), 'junit': {r: {k:v for k,v in s.items() if k != 'rows'} for r,s in summary.items()},
                  'redactionFiles': len(paths), 'redactionHits': len(hits), 'mainTreesUnchanged': len(trees)}))
