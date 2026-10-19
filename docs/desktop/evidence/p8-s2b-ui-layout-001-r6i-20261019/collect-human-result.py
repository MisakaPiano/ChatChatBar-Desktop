from pathlib import Path
import datetime as dt
import hashlib
import json
import re
import shutil
import subprocess

R = Path.cwd()
G = Path(__file__).resolve().parent
E = R / 'docs/desktop/evidence/p8-s2b-ui-layout-001-r6i-20261019'
BASE = '8f9a96a9432a350f0d049db3d01613a8da88b6b5'

def sha(b):
    return hashlib.sha256(b).hexdigest()

def save(name, obj):
    (E / name).write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.PIPE).decode().strip()

assert git('rev-parse', 'HEAD') == BASE and not git('diff', '--cached', '--name-only')
frozen_manifest = json.loads((G / 'phase-A-frozen/manifest.json').read_text(encoding='utf-8'))
A = E / 'phase-A-preparation'
assert not A.exists()
A.mkdir()
for item in frozen_manifest['members']:
    earlier = G / 'phase-A-frozen' / item['path']
    source = earlier if earlier.exists() else E / item['path']
    b = source.read_bytes()
    assert len(b) == item['bytes'] and sha(b) == item['sha256'], item['path']
    target = A / item['path']
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(b)
shutil.copyfile(G / 'phase-A-frozen/manifest.json', A / 'manifest.json')
shutil.copytree(G / '02-human-physical', E / '02-human-physical', dirs_exist_ok=True)
shutil.copytree(G / 'manual-control', E / 'manual-control', dirs_exist_ok=True)
for name in ['BEFORE-B-preflight.json', 'AFTER-B-preflight.json', 'user-start-authorization.json', 'collect-human-result.py']:
    shutil.copyfile(G / name, E / name)
stdout = E / '02-human-physical/gradle.stdout.log'
raw = stdout.read_bytes()
try:
    raw.decode('utf-8', errors='strict')
    utf8_valid = True
except UnicodeDecodeError:
    utf8_valid = False
text = raw.decode('cp936', errors='strict')
(E / 'human-stdout-decoded-cp936.txt').write_text(text, encoding='utf-8')
records = [s for s in text.splitlines() if s.startswith('FISH_')]
(E / 'human-events-derived.jsonl').write_text(''.join(json.dumps({'rawRecord': s}, ensure_ascii=False) + '\n' for s in records), encoding='utf-8')
ready = [s for s in records if s.startswith('FISH_R6I_READY ')]
armed = [s for s in records if s.startswith('FISH_R6I_ARMED ')]
probe = [s for s in records if s.startswith('FISH_R6H_NATIVE_HITTEST ')]
errors = [s for s in records if s.startswith('FISH_R6I_RUNNER_ERROR ')]
awt = [s for s in records if s.startswith('FISH_R6G_MOUSE ')]
result = (E / 'manual-control/result.txt').read_text(encoding='utf-8')
assert len(ready) == 1 and len(errors) == 1 and not armed and not probe
assert 'classification=INCONCLUSIVE' in result and 'armed=false' in result and 'beforeRange=1545.0' in result
assert len(awt) == 2 and all('id=506 ' in s and 'targetOwned=true ' in s for s in awt)
stage = json.loads((E / '02-human-physical/stage.json').read_text(encoding='utf-8-sig'))
assert stage['exitCode'] == 1
graph = next(s for s in records if s.startswith('FISH_R6I_TASK_GRAPH ')).split(' ', 1)[1].split(',')
assert ':desktopApp:test' not in graph and graph.count(':desktopApp:runR6IHumanMouse') == 1
assert not list((E / '02-human-physical/junit').glob('TEST-*.xml'))
save('execution-ledger.json', dict(
    preparationCompile='PASS / exit0 / already performed in R6I-A',
    humanJavaExecLaunches=1, fixtureInstances=1, humanJavaExecExit=1,
    humanDurationSeconds=stage['durationSeconds'], junitInvocations=0, junitXmlCount=0,
    nativeRobotMethodsInvoked=0, boundedInvocations=0, retries=0,
    sourceChangesAfterPreparation=0, taskGraph=graph,
    gradleSkippedTasks='Resource/configuration tasks only; no JUnit SKIPPED tests',
    stdoutBytes=len(raw), stdoutSha256=sha(raw), stdoutStrictUtf8=utf8_valid,
    stdoutDerivedDecode='Strict CP936 succeeds; UTF-8 derivative saved separately; original bytes untouched',
    stderrSha256=sha((E / '02-human-physical/gradle.stderr.log').read_bytes()),
    runnerResult='INCONCLUSIVE / not a JUnit test failure',
))
save('user-signatures.json', dict(
    source='Direct human messages in this chat; exact text below; no screenshot',
    startAuthorization='可以开始 R6I-B。\n\n我现在可以进行一次真实鼠标操作。',
    questionItemId='["request_user_input_async","call_6b11c7be38be4ca2a1edb2d8badd8209",0]',
    question='R6I 合成窗口已就绪并开始记录。请只做一次：①点击标题含“R6I 手动物理鼠标”的窗口标题栏激活；②在右侧音色卡列表的最右边，找到靠上那段细竖滚动条滑块（不是窗口边框或底部页码），按住左键向下拖约半个列表高度，再释放。不要重试或点其他操作。列表是否随拖动向下滚动？若找不到窗口或滑块，请直接说明。',
    answer='已拖动一次，列表正常向下滚动',
    followup='但是我刚拖动完整个窗口就已经被关上了，这是我不小心点到了，还是窗口崩了',
    interpretation='USER_REPORTED_LIST_SCROLL_SUCCESS / actual human action confirmed; not formal PHYSICAL_INPUT_PASS',
    runnerEmbeddedSignoff='NOT_SIGNED is preserved original runner output; later chat signoff is separately recorded here',
    limitations='Chat does not provide event-level timestamp; no fabricated Press/Release or native HWND from user report',
))
times = []
for p in sorted((E / 'manual-control').iterdir()):
    stat = p.stat()
    times.append(dict(path=p.name, bytes=stat.st_size, sha256=sha(p.read_bytes()),
        modifiedEpochNanoseconds=stat.st_mtime_ns,
        modifiedUtc=dt.datetime.fromtimestamp(stat.st_mtime_ns / 1e9, dt.timezone.utc).isoformat()))
save('control-file-timeline.json', dict(
    files=times, basis='Copied original control files retain timestamps; raw arm timestamp is authoritative content',
    readyToArmSeconds=((G / 'manual-control/arm.request').stat().st_mtime_ns - (G / 'manual-control/ready.txt').stat().st_mtime_ns) / 1e9,
    armToResultSeconds=((G / 'manual-control/result.txt').stat().st_mtime_ns - (G / 'manual-control/arm.request').stat().st_mtime_ns) / 1e9,
    pressTime='NOT RECORDED', releaseTime='NOT RECORDED',
    humanInstructionVsActualPressOrdering='UNKNOWN at event-level precision; operator did not wait for ARMED acknowledgement',
))
source = (R / 'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/DesktopFishPickerNativeUxTest.kt').read_text(encoding='utf-8')
excerpt = source[source.index('        fun armHumanMouse()'):source.index('        private fun observeHumanMouse(')]
(E / 'arming-source-excerpt.kt').write_text(excerpt, encoding='utf-8')
save('observation-and-cause.json', dict(
    formalClassification='INCONCLUSIVE', userExperience='USER_REPORTED_LIST_SCROLL_SUCCESS',
    actualNativeWin32RecordCount=0, actualLegacyOwnedAwtRecords=awt,
    partialAwtFinding='Two true MOUSE_DRAGGED id506 / BUTTON1_DOWN_MASK1024, OWNED_DESCENDANT SkiaLayer$1',
    physicalPress='NOT RECORDED', physicalRelease='NOT RECORDED', nativeHwnd='NOT SAMPLED',
    foregroundAndWindowFromPoint='NOT SAMPLED', nativeLeftButtonState='NOT SAMPLED',
    lateBaselineRange=1545.0, lateBaselineFirst='[预览目标 · Page 1 synthetic voice 4]',
    actualPreGestureRange='UNKNOWN: this late baseline is not a before/after measurement',
    afterRange='NOT RECORDED', afterFirst='NOT RECORDED',
    rawRunnerHumanCounters=dict(armed=False, presses=0, drags=0, releases=0),
    counterMeaning='Human recorder was never armed; zero counters are not proof of no physical human action; legacy listener separately captured 2 drags',
    provenControlFailure='armHumanMouse line439 zero-range precondition is false at observed range1545; throws before line441 armed=true and before Win32 probes',
    caughtException='IllegalStateException; internal catch logs type only, so internal stack not available; do not fabricate it',
    wrapperStack='Original stderr records companion main line60 check after unsuccessful control and completed cleanup',
    closureMechanism='Catch marks INCONCLUSIVE; original finally calls fixture.dispose; then wrapper check yields process exit1',
    userMisclickClose='NOT PROVEN / no evidence requiring it to explain closure',
    productOrNativeJvmCrash='NOT OBSERVED; controlled runner exception/cleanup is not evidence of native crash',
    procedureGap='Operator announced recording after writing arm.request but before FISH_R6I_ARMED acknowledgement; window ready explicitly means not armed',
    exactHumanGestureTiming='UNKNOWN: Press/Release timestamps absent; do not blame user or assert an exact race schedule',
    historicalR6FR6GPress0='UNKNOWN / unchanged',
    noRetryOrFix=True,
))
samples = [json.loads(s) for s in (E / '02-human-physical/resources.jsonl').read_text(encoding='utf-8-sig').splitlines() if s]
post = json.loads((E / 'AFTER-B-preflight.json').read_text(encoding='utf-8-sig'))
save('human-resource-and-cleanup.json', dict(
    samples=len(samples), minAvailableBytes=min(s['availableBytes'] for s in samples),
    minCommitHeadroomBytes=min(s['commitLimitBytes'] - s['commitBytes'] for s in samples),
    maxPagesPerSecond=max(s['pagesPerSecond'] for s in samples),
    postJavaCounts=[len(s['java']) for s in post['samples']], postRelevantProcesses=post['relevantProcesses'],
    rawRunnerCleanup=True, cleanupErrorRecords=[s for s in records if 'CLEANUP_ERROR' in s or 'SERVER_CLOSE_ERROR' in s],
    cleanupBasis='Raw cleanup=true with no cleanup/server-close error; unchanged dispose includes runtime.closeAndDrain, scope join, owner restore assertion, window disposal, observer removal and no .mp3/.part assertion; original server.close returned before result',
    mockService='One in-process loopback MockWebServer closed; no separate fake process',
    catalogCalls=1, mockHttpRequests=0, bindingCalls=0, realFishGet=0, realFishTts=0,
    audioOperations=0, independentFileHandlePlaybackTest='NOT TESTED: no audio generated/downloaded/played',
    packagedLauncherRootLock='NOT TESTED; native fixture is not distribution',
    resourceAnomaly='No OOM or abnormal resource termination observed; no causal attribution',
    userProcessesKilled=False, pagefileOrSystemConfigChanged=False,
))
save('project-next-step-proposal.json', dict(
    status='PROPOSAL ONLY / NOT IMPLEMENTED / NO RESTART',
    minimumScope='Test-only preparation/arming handshake; production UI unchanged',
    proposal='Require explicit successful ARMED acknowledgement before instructing human to drag; separate activation and recording readiness visibly. Preserve actual pre-action baseline and strict target/event/range/item invariants; do not reset LazyList or synthesize input.',
    reason='Current control rejected a nonzero late baseline before recording; user was told recording had begun without acknowledgement',
    decisionsForProject='Whether and how to authorize one further manual control; keep unattended Robot and attended human acceptance distinct; no bounded exclusions added',
))
save('phase-status.json', dict(
    status='R6I-B INCONCLUSIVE / MANUAL CONTROL ARMING FAILED / PROJECT REVIEW PENDING',
    phaseA='Compile PASS / exit0', phaseB='One manual JavaExec / exit1 / INCONCLUSIVE',
    humanAction='User confirmed one physical drag and normal list scrolling',
    userSigned=True, signatureEvidence='user-signatures.json',
    fullPhysicalInputGate='NOT PROVEN; missing Press/Release and Win32 data',
    catalogCalls=1, mockHttpRequests=0, bindingCalls=0, realFishGet=0, realFishTts=0,
    junitInvocations=0, boundedInvocations=0, retries=0, packaging='NOT RUN',
    afterPreparationSourceChanges=0, sourceCommit=None,
    sourceDrafts='UNSTAGED / UNCOMMITTED', protectedUserFiles='KEEP / UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED',
    historicalPress0RootCause='UNKNOWN', winAuto001='OPEN', winBrand001='OPEN / DEFERRED',
    nextAction='STOP / Project independent review; no new window or source fix',
))
shutil.copyfile(G / 'phase-A-frozen/stage-ledger.json', E / 'stage-ledger-phase-A-original.json')
save('stage-ledger.json', dict(phaseA='phase-A-preparation/stage-ledger.json',
    phaseB='execution-ledger.json', actualCompilations=1, humanJavaExecLaunches=1,
    junitInvocations=0, boundedInvocations=0, currentResult='INCONCLUSIVE', sourceCheckpoint=None))
print(json.dumps(dict(manualLaunches=1, result='INCONCLUSIVE', userReport='SCROLLED',
    nativeWin32Records=len(probe), ownedAwtDragRecords=len(awt), realGet=0, realTts=0)))
