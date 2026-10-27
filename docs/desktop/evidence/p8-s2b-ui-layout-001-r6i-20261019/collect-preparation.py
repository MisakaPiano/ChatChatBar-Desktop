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

def sha(data):
    return hashlib.sha256(data).hexdigest()

def save(name, data):
    (E / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

assert git('rev-parse', 'HEAD') == BASE
assert not git('diff', '--cached', '--name-only')
post = json.loads((G / 'POST-preflight.json').read_text(encoding='utf-8-sig'))
audit = json.loads((G / 'compile-and-discovery-audit.json').read_text(encoding='utf-8'))
initial = json.loads((G / 'initial-identities.json').read_text(encoding='utf-8'))
shutil.copytree(G / 'baseline-sources', E / 'baseline-sources', dirs_exist_ok=True)
shutil.copytree(G / '01-compile-only', E / '01-compile-only', dirs_exist_ok=True)
for name in ['initial-identities.json', 'INITIAL-preflight.json', 'PRECOMPILE-preflight.json', 'POST-preflight.json',
             'manual-runner-only.patch', 'precompile-static-audit.json', 'compile-and-discovery-audit.json',
             'manual-runner.init.gradle', 'compiled-test-javap.txt', 'compiled-test-javap.stderr.txt',
             'preflight.ps1', 'run-stage.ps1', 'collect-preparation.py']:
    shutil.copyfile(G / name, E / name)
(E / 'prepared-sources').mkdir(exist_ok=True)
for item in post['source']:
    data = (R / item['path']).read_bytes()
    assert sha(data) == item['expected']
    (E / 'prepared-sources' / Path(item['path']).name).write_bytes(data)
save('prepared-source-identities.json', post['source'])
history = []
for phase in ['r3', 'r4', 'r5', 'r6a', 'r6b', 'r6c', 'r6d', 'r6e', 'r6f', 'r6g', 'r6h']:
    path = f'docs/desktop/evidence/p8-s2b-ui-layout-001-{phase}-20261019'
    tree = git('rev-parse', BASE + ':' + path)
    assert git('rev-parse', 'HEAD:' + path) == tree and not git('diff', 'HEAD', '--', path)
    history.append(dict(path=path, treeAtStart=tree, unchanged=True))
save('history-preservation.json', history)
patch = (G / 'manual-runner-only.patch').read_bytes()
patch_lines = patch.decode('utf-8').splitlines()
insertions = sum(s.startswith('+') and not s.startswith('+++') for s in patch_lines)
deletions = sum(s.startswith('-') and not s.startswith('---') for s in patch_lines)
save('delta-and-scope.json', dict(patch='manual-runner-only.patch', bytes=len(patch), sha256=sha(patch),
    insertions=insertions, deletions=deletions, onlyEngineeringFile=post['source'][2]['path'],
    productionDraftsUnchanged=True, originalJUnitBodyAndDriverIdentityChecked=True,
    skillReviewed='.agents/skills/chatbar-fish-audio-voice/SKILL.md', skillUpdateNeeded=False,
    reason='Test-only optional manual runner; no production feature or repository workflow semantics changed',
    sourceCandidate=None, engineeringFilesStaged=False))
samples = [json.loads(s) for s in (G / '01-compile-only/resources.jsonl').read_text(encoding='utf-8-sig').splitlines() if s]
save('resource-summary.json', dict(compileSamples=len(samples),
    minAvailableBytes=min(s['availableBytes'] for s in samples),
    minCommitHeadroomBytes=min(s['commitLimitBytes'] - s['commitBytes'] for s in samples),
    maxPagesPerSecond=max(s['pagesPerSecond'] for s in samples),
    postJavaCounts=[len(s['java']) for s in post['samples']], postRelevantProcesses=post['relevantProcesses'],
    resourceChanges=False, killedUserProcesses=False, oomObserved=False,
    note='Sampling only; no causal claim about historical input failures'))
assert not (G / 'manual-control').exists()
save('phase-status.json', dict(status='R6I MANUAL FIXTURE READY / USER ACTION REQUIRED',
    currentPhase='R6I-A COMPLETE; R6I-B NOT STARTED', compile='PASS', compileExit=0,
    nativeJUnit='NOT RUN', fullBounded='NOT RUN', junitXmlCount=0,
    humanPhysicalInput='NOT_EXECUTED', userSignoff='NOT SIGNED',
    runtimeWin32AndAwtRecords='NOT EXECUTED; no new HWND or event evidence',
    fixtureCleanup='NOT TESTED: no fixture/loopback service/window created',
    manualControlDirectory='NOT CREATED', physicalOrRobotInputCalls=0,
    realFishGet=0, realFishTts=0, mockHttpRequests=0, syntheticCatalogCalls=0,
    manualRuntimeGuards='Compiled/static reviewed only; no claim of runtime PASS',
    windowOrOwnerLease='NOT EXECUTED', applicationPackaging='NOT RUN', sourceCommit=None, evidenceCommit=None,
    head=BASE, parent=git('rev-parse', 'HEAD^'),
    protectedUserFiles='KEEP / UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED; paths only',
    historicalPress0RootCause='UNKNOWN / unchanged', winAuto001='OPEN', winBrand001='OPEN / DEFERRED'))
command = '.\\gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 --priority=low --console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process --init-script desktopApp/build/p8-s2b-ui-layout-001-r6i-20261019/manual-runner.init.gradle -Pr6iHumanStart=true :desktopApp:runR6IHumanMouse\n'
(E / 'B-command-NOT-EXECUTED.txt').write_text(command, encoding='utf-8')
(E / '.gitattributes').write_bytes(b'* -text\n')
save('stage-ledger.json', dict(compile=audit, manualStartCommand='B-command-NOT-EXECUTED.txt',
    actualExecutionCount=1, manualExecutionCount=0, noJUnitResultFabricated=True,
    gradleResourceAccessorSkipped='Normal Gradle code-generation task status; not a JUnit SKIPPED test'))
assert audit['compiledJUnitAnnotations'] == 4 and not audit['manualTaskExecuted']
print(json.dumps(dict(filesBeforeReadmeAndManifest=len([p for p in E.rglob('*') if p.is_file()]),
    delta=f'+{insertions}/-{deletions}', compileExit=0, manual='NOT_EXECUTED')))
