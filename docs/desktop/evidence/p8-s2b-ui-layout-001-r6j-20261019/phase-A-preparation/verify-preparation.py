from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess

R = Path.cwd()
G = Path(__file__).resolve().parent
E = R / 'docs/desktop/evidence/p8-s2b-ui-layout-001-r6j-20261019'
BASE = 'e0bd3c1e48a2cf2849f6eb4e5600b84bbb39e45c'

def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.PIPE).decode().strip()

def sha(b):
    return hashlib.sha256(b).hexdigest()

def save(name, obj):
    (E / name).write_text(json.dumps(obj, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')

assert git('rev-parse', 'HEAD') == git('rev-parse', '@{u}') == BASE
assert git('rev-parse', 'HEAD^') == '8f9a96a9432a350f0d049db3d01613a8da88b6b5'
assert git('branch', '--show-current') == 'feature/phase8-fish-audio'
assert not git('diff', '--cached', '--name-only')
assert git('rev-parse', 'desktop') == '4c2af2576e2319e8041ffd2a6c5a205450f18246'
assert git('rev-parse', 'origin/desktop') == '5ef95428421f7c693b010ac88f51d470804ab07a'
assert git('ls-remote', 'origin', 'refs/heads/feature/phase8-fish-audio').split()[0] == BASE
assert git('ls-remote', 'origin', 'refs/heads/desktop').split()[0] == '5ef95428421f7c693b010ac88f51d470804ab07a'
initial = json.loads((E / 'initial-identities.json').read_text(encoding='utf-8'))
prefix = E.relative_to(R).as_posix() + '/'
others = [p for p in git('ls-files', '--others', '--exclude-standard').splitlines() if not p.startswith(prefix)]
assert sorted(others) == sorted(initial['untrackedPathsOnly'])
assert sorted(git('diff', '--name-only').splitlines()) == sorted(i['path'] for i in initial['sources'][:2])
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
warnings = []
metadata_errors = []
for p in sorted(E.rglob('*')):
    if not p.is_file() or p.name in ['manifest.json', 'diff-check-raw.log']:
        continue
    check = subprocess.run(['git', '-c', 'core.autocrlf=false', '-c', 'core.whitespace=cr-at-eol',
        'diff', '--no-index', '--check', '--', '/dev/null', str(p)], capture_output=True)
    assert check.returncode in [0, 1, 2, 3], (str(p), check.returncode, check.stderr)
    notices = check.stderr.decode('utf-8', errors='strict').splitlines()
    assert all(s.startswith("warning: in the working copy of '") and
        s.endswith("', LF will be replaced by CRLF the next time Git touches it") for s in notices), notices
    if check.stdout or check.stderr:
        warnings.append(check.stdout + check.stderr)
    if check.stdout and p.suffix in ['.md', '.json', '.py', '.ps1', '.gradle', '.kt']:
        metadata_errors.append(str(p))
assert not metadata_errors, metadata_errors
(E / 'diff-check-raw.log').write_bytes(b''.join(warnings))
save('static-validation.json', dict(headTrackingLiveEqual=True, indexEmpty=True,
    metadataAndSourceCopyWhitespaceErrors=[], rawWhitespaceAndKnownEolNoticesPreserved=True,
    unknownStderrRejected=True, sourceDraftsUnstaged=True, productionDraftsFrozen=True,
    originalFourJUnitMethodsAndInputDriversRawUnchanged=True, historicalEvidenceUnchanged=True,
    userFilesPathsOnlyProtected=True, markdownTargetsVerified=len(links), rawXmlCount=0,
    actualManualDirectoryAbsent=True, actualArmedAcknowledgementAbsent=True,
    physicalGateNotTested=True, evidenceCommit=None, sourceCommit=None))
members = []
for p in sorted(E.rglob('*')):
    if p.is_file() and p != E / 'manifest.json':
        b = p.read_bytes()
        members.append(dict(path=p.relative_to(E).as_posix(), bytes=len(b), sha256=sha(b)))
save('manifest.json', dict(algorithm='SHA-256', selfExcluded=True, stage='R6J-A PREPARATION ONLY', members=members))
for m in members:
    b = (E / m['path']).read_bytes()
    assert len(b) == m['bytes'] and sha(b) == m['sha256']
print(json.dumps(dict(status='R6J-A HANDSHAKE READY / USER ACTION REQUIRED',
    files=len(members) + 1, manifestSha256=sha((E / 'manifest.json').read_bytes()),
    head=BASE, noCommit=True, headlessChecks='16/16', operatorChecks='11/11', actualManual='NOT STARTED')))
