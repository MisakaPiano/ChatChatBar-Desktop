from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess

R = Path.cwd()
G = Path(__file__).resolve().parent
E = R / 'docs/desktop/evidence/p8-s2b-ui-layout-001-r6i-20261019'
BASE = '8f9a96a9432a350f0d049db3d01613a8da88b6b5'

def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.PIPE).decode().strip()

def sha(b):
    return hashlib.sha256(b).hexdigest()

def save(name, obj):
    (E / name).write_text(json.dumps(obj, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')

assert git('rev-parse', 'HEAD') == git('rev-parse', '@{u}') == BASE
assert git('branch', '--show-current') == 'feature/phase8-fish-audio'
assert not git('diff', '--cached', '--name-only')
assert git('rev-parse', 'desktop') == '4c2af2576e2319e8041ffd2a6c5a205450f18246'
assert git('rev-parse', 'origin/desktop') == '5ef95428421f7c693b010ac88f51d470804ab07a'
assert git('ls-remote', 'origin', 'refs/heads/feature/phase8-fish-audio').split()[0] == BASE
assert git('ls-remote', 'origin', 'refs/heads/desktop').split()[0] == '5ef95428421f7c693b010ac88f51d470804ab07a'
initial = json.loads((G / 'initial-identities.json').read_text(encoding='utf-8'))
prefix = E.relative_to(R).as_posix() + '/'
untracked = [p for p in git('ls-files', '--others', '--exclude-standard').splitlines() if not p.startswith(prefix)]
assert sorted(untracked) == sorted(initial['untrackedPathsOnly'])
modified = git('diff', '--name-only').splitlines()
assert sorted(modified) == sorted(i['path'] for i in initial['sources'][:2])
for item in json.loads((E / 'prepared-source-identities.json').read_text(encoding='utf-8')):
    data = (R / item['path']).read_bytes()
    assert sha(data) == item['sha256'] == item['expected']
    assert data == (E / 'prepared-sources' / Path(item['path']).name).read_bytes()
assert not (G / 'manual-control').exists()
for h in json.loads((E / 'history-preservation.json').read_text(encoding='utf-8')):
    assert git('rev-parse', 'HEAD:' + h['path']) == h['treeAtStart']
    assert not git('diff', 'HEAD', '--', h['path'])
shutil.copyfile(__file__, E / Path(__file__).name)
save('static-validation.json', {'status': 'pending static check'})
links = re.findall(r'\]\(([^)]+)\)', (E / 'README.md').read_text(encoding='utf-8'))
assert all((E / link).is_file() for link in links)
warning_log = []
metadata_warnings = []
for p in sorted(E.rglob('*')):
    if not p.is_file() or p.name in ['manifest.json', 'diff-check-raw.log']:
        continue
    check = subprocess.run(['git', '-c', 'core.whitespace=cr-at-eol', 'diff', '--no-index', '--check', '--', '/dev/null', str(p)], capture_output=True)
    assert check.returncode in [0, 1, 2], (str(p), check.stderr)
    if check.stdout or check.stderr:
        warning_log.append(check.stdout + check.stderr)
        if p.suffix in ['.md', '.json', '.py', '.ps1', '.gradle', '.kt']:
            metadata_warnings.append(str(p))
assert not metadata_warnings, metadata_warnings
(E / 'diff-check-raw.log').write_bytes(b''.join(warning_log))
save('static-validation.json', dict(headUnchanged=True, trackingAndLiveEqual=True, indexEmpty=True,
    sourceDraftsUnstaged=True, sourceCopiesRawIdentical=True, productionDraftsFrozen=True,
    protectedFilesPathsOnlyUntrackedUnchanged=True, historicalEvidenceUnchanged=True,
    metadataAndSourceCopyWhitespaceWarnings=[], rawWarningsPreserved=bool(warning_log),
    markdownTargetsVerified=len(links), rawXmlCount=0, manualDirectoryAbsent=True,
    junitAndManualTasksNotExecuted=True, evidenceCommit=None, sourceCommit=None))
members = []
for p in sorted(E.rglob('*')):
    if p.is_file() and p.name != 'manifest.json':
        b = p.read_bytes()
        members.append(dict(path=p.relative_to(E).as_posix(), bytes=len(b), sha256=sha(b)))
save('manifest.json', dict(algorithm='SHA-256', selfExcluded=True, stage='R6I-A PREPARATION ONLY', members=members))
for m in members:
    b = (E / m['path']).read_bytes()
    assert len(b) == m['bytes'] and sha(b) == m['sha256']
print(json.dumps(dict(status='R6I MANUAL FIXTURE READY / USER ACTION REQUIRED',
    files=len(members) + 1, manifestSha256=sha((E / 'manifest.json').read_bytes()),
    indexEmpty=True, head=BASE, manual='NOT_EXECUTED', noCommit=True)))
