from pathlib import Path
import datetime, hashlib, json, re, shutil, subprocess
import xml.etree.ElementTree as ET

R = Path.cwd()
G = Path(__file__).resolve().parent
E = R / 'docs/desktop/evidence/p8-s2b-ui-layout-001-r4-20261019'
BASE = '3d46c6448b6748644c561ea27d3ed2b016e1b045'
def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.PIPE)
def sha(data):
    return hashlib.sha256(data).hexdigest()
def save(name, obj):
    (G / name).write_text(json.dumps(obj, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')

assert git('rev-parse', 'HEAD').decode().strip() == BASE
assert not git('diff', '--cached', '--name-only').strip()
frozen = json.loads((G / 'baseline-source.json').read_text(encoding='utf-8-sig'))
paths = [f['path'] for f in frozen['files']]
assert set(git('diff', '--name-only').decode().splitlines()) == set(paths[:2])
assert sha((R / 'docs/desktop/evidence/p8-s2b-ui-layout-001-r3-20261019/source.patch').read_bytes()) == frozen['patchSha256']
for f in frozen['files']:
    b = (G / 'baseline-sources' / Path(f['path']).name).read_bytes()
    assert len(b) == f['bytes'] and sha(b) == f['sha256']
    assert sha(b.replace(b'\r\n', b'\n')) == f['normalizedSha256']
for f in frozen['files'][:2]:
    assert sha((R / f['path']).read_bytes()) == f['sha256']

patch = git('diff', '--binary', 'HEAD', '--', *paths[:2])
ledger = []
for f in frozen['files']:
    p = R / f['path']; raw = p.read_bytes(); normalized = raw.replace(b'\r\n', b'\n')
    if f['baseGitBlob'] is None:
        lines = normalized.splitlines(keepends=True)
        patch += (f"diff --git a/{f['path']} b/{f['path']}\nnew file mode 100644\n--- /dev/null\n+++ b/{f['path']}\n@@ -0,0 +1,{len(lines)} @@\n").encode()
        patch += b''.join(b'+' + line for line in lines)
    ledger.append(dict(path=f['path'], bytes=len(raw), sha256=sha(raw), normalizedSha256=sha(normalized),
                       originalR3Sha256=f['sha256'], unchangedFromFrozenR3=sha(raw) == f['sha256']))
(G / 'source.patch').write_bytes(patch)
delta = subprocess.run(['git', 'diff', '--no-index', '--',
    str(G / 'baseline-sources' / Path(paths[2]).name), str(R / paths[2])], capture_output=True)
assert delta.returncode == 1
(G / 'diagnostic-only-delta.patch').write_bytes(delta.stdout)
save('source.json', dict(base=BASE, sourceCheckpointCreated=False, files=ledger,
    patchSha256=sha(patch), diagnosticDeltaSha256=sha(delta.stdout),
    actualR4SourceTreeChange='NativeUxTest only; two R3 production drafts raw-byte unchanged',
    changedAfterDiagnosticRed=False))
assert (G / 'diagnostic-sources' / Path(paths[2]).name).read_bytes() == (R / paths[2]).read_bytes()

stage = G / '01-native-scrollbar-diagnostic'
xml = stage / 'junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml'
root = ET.parse(xml).getroot()
assert root.attrib['tests'] == '1' and root.attrib['failures'] == '1' and root.attrib['skipped'] == '0'
test = root.find('testcase'); failure = test.find('failure')
stdout = root.findtext('system-out') or ''
events = [line for line in stdout.splitlines() if line.startswith('FISH_R4_MOUSE ')]
snapshots = [line for line in stdout.splitlines() if line.startswith('FISH_R4_SCROLL ')]
commands = [line for line in stdout.splitlines() if line.startswith('FISH_R4_ROBOT ')]
assert len(snapshots) == 4 and len(commands) == 1
press = next(line for line in events if 'id=501 ' in line)
assert 'screen=1118,162 ' in press
assert all('range=0.0/100.0' in line for line in snapshots)
assert all('queries=1 requests=0 bindings=0' in line for line in snapshots)
for name, data in [('native-event-trace.jsonl', events), ('native-geometry-state.jsonl', snapshots), ('robot-commands.jsonl', commands)]:
    (G / name).write_text(''.join(json.dumps(dict(record=line), ensure_ascii=False) + '\n' for line in data), encoding='utf-8')
save('failure-summary.json', dict(test=test.attrib['name'], suite=root.attrib, testDurationSeconds=float(test.attrib['time']),
    exception=failure.attrib, stack=failure.text, xmlSha256=sha(xml.read_bytes()),
    originalAssertionRetained='range().value() > before', originalWaitMillis=10000, currentSourceLine=375,
    diagnosticExecutions=1, rerunsAfterRed=0, compilation='PASS / production compile UP-TO-DATE, compileTestKotlin executed',
    frame=dict(x=48, y=48, width=1100, height=720), client=dict(x=56, y=79, width=1084, height=681), dpiScale=[1, 1],
    track=dict(x=1120, y=155, width=8, height=487), thumb=dict(x=1120, y=155, width=8, height=89,
        basis='Delegating public ScrollbarStyle Shape observes unchanged drawn thumb size; public layout algorithm places it at track top at zero scroll'),
    requestedPress=[1124, 163], receivedPress=[1118, 162], receivedPressInsideTrack=False, receivedPressInsideThumb=False,
    awtMouseEventCount=len(events), awtTarget='owned Compose child / SkiaLayer',
    initialRange=0, finalRange=0, rangeMax=100,
    visibleSyntheticItemIndicesBefore=[1, 2, 3, 4], visibleSyntheticItemIndicesAfter=[1, 2, 3, 4],
    firstVisibleSyntheticItemIndexOneBased=1, firstVisibleOffsetPx=0,
    knownObservation='Robot requested coordinates differ from received physical press; actual press misses the thumb; additional native motion appears between explicit commands',
    movementOrigin='UNKNOWN; no attribution to user, OS, Robot or capture infrastructure established',
    composeThumbGestureDelivery='NOT PROVEN; AWT child delivery is not proof that the thumb pointer handler captured the press',
    historicR3RootCause='UNKNOWN / do not retroactively infer from this R4 run', productScrollbarDefect='NOT PROVEN',
    nextBranches='720dp, wheel, page jump, unknown-total and extreme Int branches NOT REACHED in this run'))

history = []
for folder in ['p8-s2b-ui-layout-001-20261018', 'p8-s2b-ui-layout-001-r1-20261018',
               'p8-s2b-ui-layout-001-r2-20261018', 'p8-s2b-ui-layout-001-r3-20261019',
               'p8-s2b-f2-20261018', 'p8-s2b-f2-r1-20261018', 'p8-s2b-f2-r2-20261018']:
    p = 'docs/desktop/evidence/' + folder
    tree = git('rev-parse', BASE + ':' + p).decode().strip()
    assert git('rev-parse', 'HEAD:' + p).decode().strip() == tree
    assert not git('diff', '--name-only', 'HEAD', '--', p).strip()
    history.append(dict(path=p, gitTreeAtStart=tree, gitTreeNow=tree, trackedWorktreeChanges=False, unchanged=True))
save('history-preservation.json', history)
save('request-accounting.json', dict(realFishGet=0, realFishTts=0, manualFakeStarted=False, manualFakeRequests=0,
    nativeFixture=dict(catalogSyntheticFunctionInvocations=1, mockWebServerPhysicalRequests=0, bindings=0),
    source='All four raw native snapshots; no other test method executed; no live gate'))
save('closing-state.json', dict(status='P8-S2B-UI-LAYOUT-001-R4 HOLD / RAW RED PRESERVED',
    sourceDraftsUncommitted=True, productionDraftsUnchanged=True, diagnosticTestDraftRetained=True,
    newWindowsDistribution='NOT BUILT', manualGate='NOT STARTED', launcherExit='NOT TESTED / NOT STARTED',
    appJvmExit='NOT TESTED / NOT STARTED', activeRootExclusion='NOT TESTED', rootReacquire='NOT TESTED',
    fakeService='NOT STARTED', temporaryTls='NOT CREATED',
    nativeTestCleanup='Fixture finally completed owner re-enable, window dispose, scope/runtime drain and no owned mp3/part assertion without cleanup failure',
    testCommandExit=1, postRunJavaProcessCount=0,
    windowsMaximize='NOT TESTED in R4', windowsAudio='NOT TESTED in R4',
    winAuto001='OPEN / not attributed as cause of this scrollbar RED', winBrand001='OPEN / DEFERRED',
    protectedUserFiles=dict(untracked=4, contentsRead=False, modified=False, staged=False),
    residualLimits=['MCI/device compatibility', 'platform TOCTOU', 'physical I/O absolute deadlines', 'prior symlink NOT TESTED and two existing JUnit SKIPPED unchanged']))
save('deferred-pagination-layout.json', dict(source='User steering during R4 evidence closeout',
    requirement='When width permits, place page number input and Jump after current-page / total-page caption on the same row; wrap only when needed',
    status='RECORDED / IMPLEMENTATION PENDING', codeChanged=False,
    reason='New diagnostic RED triggered R4 STOP; no additional production layout edit made'))
save('skill-review.json', dict(reviewedBeforeCommit=True, skills=['chatbar-fish-audio-voice', 'chatbar-shadcn-compose'],
    changes=[], reason='Evidence-only HOLD checkpoint does not promote the uncommitted candidate as accepted product capability; no current skill fact changed by diagnostic evidence'))

E.mkdir(exist_ok=False)
(E / '.gitattributes').write_bytes(b'* -text\n')
for folder in ['01-native-scrollbar-diagnostic', 'baseline-sources', 'diagnostic-sources']:
    shutil.copytree(G / folder, E / folder)
for name in ['preflight.json', 'baseline-source.json', 'source.json', 'source.patch', 'diagnostic-only-delta.patch',
    'diagnostic-source-hashes.json', 'pre-diagnostic-resources.json', 'post-diagnostic-resources.json',
    'stage-summary.json', 'failure-summary.json', 'native-event-trace.jsonl', 'native-geometry-state.jsonl',
    'robot-commands.jsonl', 'history-preservation.json', 'request-accounting.json', 'closing-state.json',
    'deferred-pagination-layout.json', 'skill-review.json', 'compose-1.10.3-scrollbar-bytecode.txt',
    'compose-library-identity.json', 'run-stage.ps1', 'summarize-stages.py', 'freeze-red-evidence.py']:
    shutil.copyfile(G / name, E / name)
print(json.dumps(dict(evidence=E.relative_to(R).as_posix(), filesBeforeReadmeAndManifest=len(list(E.rglob('*'))),
    sourcePatchSha256=sha(patch), currentTestRawSha256=ledger[2]['sha256'], awtMouseEvents=len(events))))
