from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

R = Path.cwd()
G = Path(__file__).resolve().parent
E = R / 'docs/desktop/evidence/p8-s2b-ui-layout-001-r6c-20261019'
BASE = '46d9f89691cafd1fbb69b5e7cd3481602bafa10c'

def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.PIPE)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def save(name, value):
    (G / name).write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')

assert git('rev-parse', 'HEAD').decode().strip() == BASE
assert not git('diff', '--cached', '--name-only').strip()
pre = json.loads((G / 'preflight.json').read_text(encoding='utf-8'))
ledger = []
for original in pre['sourceIdentities']:
    path = R / original['path']
    before = (G / 'baseline-sources' / path.name).read_bytes()
    after = path.read_bytes()
    assert len(before) == original['bytes'] and sha(before) == original['sha256']
    if not path.name.endswith('NativeUxTest.kt'):
        assert before == after
    else:
        assert after == (G / 'tested-source.kt').read_bytes()
    ledger.append(dict(path=original['path'], beforeBytes=len(before), beforeSha256=sha(before), currentBytes=len(after), currentSha256=sha(after), unchanged=before == after))
save('source-preservation.json', dict(files=ledger, onlyAssertPagerLayoutChanged=True, originalAssertionBlockUnchanged=True, allOutsideMethodUnchanged=True, sourceChangedAfterRed=False))

stage = G / '01-gate-a-single-method'
xml = stage / 'junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml'
root = ET.parse(xml).getroot()
assert root.attrib['tests'] == '1' and root.attrib['failures'] == '1' and root.attrib['skipped'] == '0'
test = root.find('testcase')
failure = test.find('failure')
assert '360dp pager must wrap without clipping' in failure.attrib['message']
stdout = root.findtext('system-out') or ''
lines = stdout.splitlines()
outerLines = [line for line in lines if line.startswith('FISH_R6C_PAGER_OUTER ')]
assert len(outerLines) == 5

def number(line, key):
    return int(re.search(r'(?:^| )' + re.escape(key) + r'=(-?\d+)', line)[1])

def rectangle(line, key):
    values = re.search(re.escape(key) + r'=java.awt.Rectangle\[x=(-?\d+),y=(-?\d+),width=(\d+),height=(\d+)\]', line).groups()
    return dict(zip(['x', 'y', 'width', 'height'], map(int, values)))

rows = []
for line in outerLines:
    row = {key: number(line, key) for key in ['requestedWidthDp', 'page', 'inputNode', 'buttonNode', 'inputItem', 'inputParent', 'buttonParent', 'pair', 'pager', 'gap', 'overlap']}
    row['inputScreen'] = rectangle(line, 'inputScreen')
    row['buttonScreen'] = rectangle(line, 'buttonScreen')
    assert row['inputParent'] == row['buttonParent'] == row['pair']
    row['sharedPairIdentityConfirmed'] = True
    row['coordinatesCoverWholeOuterItem'] = True
    row['adjacency12PxPassed'] = 0 <= row['gap'] <= 12 and row['overlap'] > 0
    assert row['adjacency12PxPassed']
    rows.append(row)
save('outer-geometry.json', dict(source='Parsed original XML system-out; IDs and rectangles are runtime observations, never fixed test expectations', measurements=rows))
(G / 'native-system-out.txt').write_text(stdout, encoding='utf-8')
raw = [line for line in lines if line.startswith(('FISH_R6', 'FISH_R5_', 'FISH_R4_'))]
(G / 'native-geometry-events.jsonl').write_text(''.join(json.dumps(dict(record=line), ensure_ascii=False) + '\n' for line in raw), encoding='utf-8')

completed = [line for line in lines if line.startswith('FISH_R3_SNAPSHOT phase=page-input-and-scrollbar')]
assert len(completed) == 2
assert sum('phase=R5-real-drag-completed' in line for line in lines) == 2
assert sum('phase=R5-native-wheel-completed' in line for line in lines) == 2
assert sum(line.startswith('FISH_R5_DRAG ') for line in lines) == 32
assert not any('unknown-total-int-boundary' in line for line in lines)
start360 = next(i for i, line in enumerate(lines) if line.startswith('FISH_R6C_PAGER_OUTER requestedWidthDp=360'))
matches360 = [line for line in lines[start360:] if 'FISH_R6A_PAGER_GEOMETRY MATCH ' in line]
controls360 = []
for line in matches360:
    label = re.search(r'MATCH label=(.*?) candidates=', line)[1]
    controls360.append(dict(label=label, selectedId=number(line, 'chosen'), screen=rectangle(line, 'measuredControlRect')))
save('narrow-failure-geometry.json', dict(requestedWidthDp=360, controls=controls360, originalPredicate='field.y > previous.y', inputOuterTop=rows[-1]['inputScreen']['y'], previousTop=controls360[0]['screen']['y'], predicatePassed=False, nextButtonTop=controls360[-1]['screen']['y'], flowLayoutObservedSize=[320, 94], observation='Input pair remains on first flow line; Next is on a second line. All controls passed client containment and 12px/overlap checks before unchanged narrow-wrap predicate failed.', classification='NEW_NARROW_WRAP_ASSERTION_RED / PROJECT DISPOSITION REQUIRED', productFixImplemented=False, assertionChangedAfterFailure=False))
save('result.json', dict(gateA=dict(invocations=1, compilation='PASS', exitCode=1, suites=1, tests=1, reportedPass=0, failures=1, errors=0, skipped=0, testSeconds=float(test.attrib['time']), xmlSha256=sha(xml.read_bytes())), gateB='NOT STARTED / Gate A RED', failure=failure.attrib, stack=failure.text, assertionLine=353, callerLine=570, retriesAfterRed=0, sourceChangedAfterRed=False, branches={'1100dp': 'Outer geometry, 16-step drag, wheel, known-total valid/invalid pages, Enter/click and top reset completed', '720dp': 'Same completed', '360dp': 'Outer geometry/client containment/12px/overlap passed; unchanged narrow-wrap predicate FAIL before native drag', '360dp drag/wheel/page': 'NOT REACHED', 'unknown total/Int boundary': 'NOT REACHED'}, nextFullFocused='NOT STARTED', bounded='NOT STARTED', packaging='NOT STARTED', manualGate='NOT STARTED', restoreFurtherGatesReady=False))
save('requests-cleanup.json', dict(realFishGet=0, realFishTts=0, mockHttp=0, catalogSyntheticCalls=7, bindings=0, basis='1100 and 720 complete queriedPages [1,3,2], plus 360 initial query1; raw snapshots requests0/bindings0', admittedDrags=2, continuousDragSteps=32, wheelCompletionSnapshots=2, nativeFinally='No owner restore/window disposal/scope-runtime drain/no-mp3-part assertion failure', postGateJavaCount=0, audioGenerated=False, manualFakeStarted=False, launcher='NOT STARTED', appJvm='NOT STARTED', formalRootLock='NOT TESTED'))
history = []
for folder in ['p8-s2b-ui-layout-001-r3-20261019', 'p8-s2b-ui-layout-001-r4-20261019', 'p8-s2b-ui-layout-001-r5-20261019', 'p8-s2b-ui-layout-001-r6a-20261019', 'p8-s2b-ui-layout-001-r6b-20261019']:
    path = 'docs/desktop/evidence/' + folder
    tree = git('rev-parse', BASE + ':' + path).decode().strip()
    assert not git('diff', '--name-only', 'HEAD', '--', path).strip()
    history.append(dict(path=path, gitTreeAtStart=tree, gitTreeNow=git('rev-parse', 'HEAD:' + path).decode().strip(), worktreeUnchanged=True))
save('history-preservation.json', history)
save('skill-review.json', dict(reviewedBeforeEvidenceCommit=True, skills=['chatbar-fish-audio-voice', 'chatbar-shadcn-compose'], edits=[], reason='Evidence-only HOLD checkpoint does not change accepted product or skill contracts'))

E.mkdir(exist_ok=False)
(E / '.gitattributes').write_bytes(b'* -text\n')
for folder in ['baseline-sources', '01-gate-a-single-method']:
    shutil.copytree(G / folder, E / folder)
for name in ['preflight.json', 'pre-gate-a-resources.json', 'post-gate-a-resources.json', 'tested-source.kt', 'tested-source-identity.json', 'measurement-fix.patch', 'source-preservation.json', 'stage-summary.json', 'outer-geometry.json', 'native-system-out.txt', 'native-geometry-events.jsonl', 'narrow-failure-geometry.json', 'result.json', 'requests-cleanup.json', 'history-preservation.json', 'skill-review.json', 'run-stage.ps1', 'summarize-stages.py', 'freeze-evidence.py']:
    shutil.copyfile(G / name, E / name)
print(json.dumps(dict(evidence=E.relative_to(R).as_posix(), xmlSha256=sha(xml.read_bytes()), outerMeasurements=len(rows), allObservedGaps=[row['gap'] for row in rows], gateA='RED', gateB='NOT STARTED')))
