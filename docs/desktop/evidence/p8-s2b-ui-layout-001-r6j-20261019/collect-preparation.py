from pathlib import Path
import hashlib
import json
import shutil
import subprocess

R = Path.cwd()
G = Path(__file__).resolve().parent
E = R / 'docs/desktop/evidence/p8-s2b-ui-layout-001-r6j-20261019'
BASE = 'e0bd3c1e48a2cf2849f6eb4e5600b84bbb39e45c'

def sha(data):
    return hashlib.sha256(data).hexdigest()

def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.PIPE).decode().strip()

def save(name, obj):
    (E / name).write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

assert git('rev-parse', 'HEAD') == BASE and not git('diff', '--cached', '--name-only')
post = json.loads((G / 'POST-preflight.json').read_text(encoding='utf-8-sig'))
for name in ['baseline-sources', '01-compile-only', '02-headless-handshake', '03-operator-guard-checks', 'deterministic-checks']:
    shutil.copytree(G / name, E / name, dirs_exist_ok=True)
for name in ['initial-identities.json', 'INITIAL-preflight.json', 'PRECOMPILE-preflight.json', 'BEFORE-CHECKS-preflight.json',
             'POST-preflight.json', 'precompile-static-audit.json', 'handshake-only.patch', 'human-runner-body.txt',
             'manual-runner.init.gradle', 'verify-human-handshake.py', 'operator-handshake-checks.json',
             'compiled-javap.txt', 'compiled-javap.stderr.txt', 'compiled-discovery-audit.json',
             'preflight.ps1', 'run-stage.ps1', 'collect-preparation.py']:
    shutil.copyfile(G / name, E / name)
(E / 'prepared-sources').mkdir(exist_ok=True)
for item in post['source']:
    data = (R / item['path']).read_bytes()
    assert sha(data) == item['expected']
    (E / 'prepared-sources' / Path(item['path']).name).write_bytes(data)
save('prepared-source-identities.json', post['source'])
history = []
for phase in ['r3', 'r4', 'r5', 'r6a', 'r6b', 'r6c', 'r6d', 'r6e', 'r6f', 'r6g', 'r6h', 'r6i']:
    path = f'docs/desktop/evidence/p8-s2b-ui-layout-001-{phase}-20261019'
    tree = git('rev-parse', BASE + ':' + path)
    assert git('rev-parse', 'HEAD:' + path) == tree and not git('diff', 'HEAD', '--', path)
    history.append(dict(path=path, treeAtStart=tree, unchanged=True))
save('history-preservation.json', history)
patch = (G / 'handshake-only.patch').read_bytes()
lines = patch.decode('utf-8').splitlines()
save('delta-and-scope.json', dict(patch='handshake-only.patch', bytes=len(patch), sha256=sha(patch),
    insertions=sum(s.startswith('+') and not s.startswith('+++') for s in lines),
    deletions=sum(s.startswith('-') and not s.startswith('---') for s in lines),
    onlyEngineeringFile=post['source'][2]['path'], productionDraftsUnchanged=True,
    originalJUnitMethodsAndNativeDriversRawIdentical=True,
    skillReviewed='.agents/skills/chatbar-fish-audio-voice/SKILL.md', skillUpdateNeeded=False,
    reason='Manual test recorder protocol only; no Fish production feature/contract change', sourceCommit=None))
ledger = []
for name in ['01-compile-only', '02-headless-handshake']:
    stage = json.loads((G / name / 'stage.json').read_text(encoding='utf-8-sig'))
    assert stage['exitCode'] == 0
    raw = (G / name / 'gradle.stdout.log').read_bytes()
    text = raw.decode('utf-8', errors='strict')
    graph = next(s for s in text.splitlines() if s.startswith('FISH_R6J_TASK_GRAPH ')).split(' ', 1)[1].split(',')
    assert ':desktopApp:test' not in graph and ':desktopApp:runR6JHumanMouse' not in graph
    assert not list((G / name / 'junit').glob('TEST-*.xml'))
    resources = [json.loads(s) for s in (G / name / 'resources.jsonl').read_text(encoding='utf-8-sig').splitlines() if s]
    ledger.append(dict(stage=name, exit=stage['exitCode'], seconds=stage['durationSeconds'], taskGraph=graph,
        stdoutBytes=len(raw), stdoutSha256=sha(raw), stderrSha256=sha((G / name / 'gradle.stderr.log').read_bytes()),
        samples=len(resources), minAvailableBytes=min(s['availableBytes'] for s in resources),
        minCommitHeadroomBytes=min(s['commitLimitBytes'] - s['commitBytes'] for s in resources),
        maxPagesPerSecond=max(s['pagesPerSecond'] for s in resources)))
check_lines = (G / 'deterministic-checks/checks.txt').read_text(encoding='utf-8').splitlines()
assert check_lines[:2] == ['mode=DETERMINISTIC_HEADLESS_NO_WINDOW', 'count=16']
assert len(check_lines[2:]) == 16 and all(s.endswith('=PASS') for s in check_lines[2:])
operator = json.loads((G / 'operator-handshake-checks.json').read_text(encoding='utf-8'))
assert len(operator['cases']) == 11 and all(operator['cases'].values())
assert not (G / 'manual-control').exists()
save('stage-ledger.json', dict(stages=ledger, compiledProtocolChecks=dict(cases=16, passed=16, headless=True),
    operatorGuardChecks=dict(cases=11, passed=11, exit=0),
    junitInvocations=0, junitXmlCount=0, nativeUxInvocations=0, boundedInvocations=0,
    actualHumanRunnerLaunches=0, actualArmedRecord='NONE; synthetic check artifacts are not runtime HWND/ARMED evidence',
    compilerWarning='NativeUxTest.kt:1057 unnecessary non-null assertion; retained, no compiler error or suppression'))
save('phase-status.json', dict(status='R6J-A HANDSHAKE READY / USER ACTION REQUIRED',
    phaseA='Compile PASS / headless16 PASS / operator11 PASS', phaseB='NOT STARTED / awaits explicit user authorization',
    actualManualRunId=None, actualWindow='NOT CREATED', actualHwnd='NOT SAMPLED', actualUserInput='NOT_EXECUTED',
    userSignoff='NOT SIGNED', physicalGate='NOT TESTED', actualWin32AwtCollection='NOT EXECUTED',
    actualFinishAndDrain='NOT TESTED; logical state/close-decision checks only',
    realFishGet=0, realFishTts=0, mockHttp=0, syntheticCatalogCalls=0,
    robotInput=0, simulatedInput=0, sourceCommit=None, evidenceCommit=None, head=BASE,
    sourceDrafts='UNSTAGED / UNCOMMITTED',
    protectedUserFiles='KEEP / UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED; paths only',
    historicalRobotPress0='UNKNOWN / unchanged', winAuto001='OPEN', winBrand001='OPEN / DEFERRED'))
save('protocol-limits.json', dict(
    arming='No instructions until actual fresh run armed.txt and FISH_R6J_ARMED match and verify-human-handshake.py permits; WINDOW_VISIBLE/command start alone cannot authorize a drag',
    baseline='Real range0/first/thumb/counts/owned HWND/logical owner/listener checked before ARMED; nonzero becomes PREARM_BASELINE_INVALID without resetting scroll',
    publication='FileChannel.force(true) then ATOMIC_MOVE; files are owned per-run and not overwritten',
    capture='One Press, Drag left mask, Release, sequential Win32 checks, stable after state; capture persists before confirmation',
    uncertainty='Press/Release event coordinate vs subsequent native pointer mismatch yields INCONCLUSIVE, never inferred atomic timing',
    completion='Capture/recorder error is HELD; only same-run finish/cancel or explicit window close/120s confirmation timeout can proceed to disposal',
    limitsSeconds=dict(windowReady=10, gesture=120, stabilization=2, confirmation=120, physicalRelease=10),
    noForceRelease=True, noProductionChanges=True,
    lifecycleLimit='Pure state checks validate decisions; actual Window, resource drain and physical input remain R6J-B work',
    next='STOP; no manual start, packaging, bounded, merge or release'))
save('post-resource-summary.json', dict(javaCounts=[len(s['java']) for s in post['samples']],
    relevantProcesses=post['relevantProcesses'], samples=post['samples'],
    observedOom=False, noProcessesKilled=True, noPagefileChanges=True))
(E / '.gitattributes').write_bytes(b'* -text\n')
print(json.dumps(dict(compile=0, headless='16/16 PASS', operator='11/11 PASS', manual='NOT STARTED',
    membersBeforeReadme=len([p for p in E.rglob('*') if p.is_file()]))))
