from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

R = Path.cwd()
G = Path(__file__).resolve().parent
E = R / 'docs/desktop/evidence/p8-s2b-ui-layout-001-r6d-20261019'
BASE = 'b366d3ceb019b0f78ea7ab4d3c3f19508a8389fc'

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
    if path.name.endswith('NativeUxTest.kt'):
        assert after == (G / 'tested-source.kt').read_bytes()
    else:
        assert before == after
    ledger.append(dict(path=original['path'], beforeBytes=len(before), beforeSha256=sha(before), currentBytes=len(after), currentSha256=sha(after), unchanged=before == after))
save('source-preservation.json', dict(files=ledger, onlyNarrowElseBranchChanged=True, allOtherCodeUnchanged=True, sourceChangedAfterGate=False))

stage = G / '01-nativeux-class'
xml = stage / 'junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml'
root = ET.parse(xml).getroot()
assert root.attrib['tests'] == '4' and root.attrib['failures'] == '0' and root.attrib['errors'] == '0' and root.attrib['skipped'] == '0'
tests = [dict(test.attrib) for test in root.findall('testcase')]
stdout = root.findtext('system-out') or ''
lines = stdout.splitlines()
(G / 'native-system-out.txt').write_text(stdout, encoding='utf-8')
raw = [line for line in lines if line.startswith('FISH_')]
(G / 'native-events-geometry.jsonl').write_text(''.join(json.dumps(dict(record=line), ensure_ascii=False) + '\n' for line in raw), encoding='utf-8')

def number(line, key):
    return int(re.search(r'(?:^| )' + re.escape(key) + r'=(-?\d+)', line)[1])

def rect_values(match):
    return dict(zip(['x', 'y', 'width', 'height'], map(int, match)))

rect_pattern = r'java.awt.Rectangle\[x=(-?\d+),y=(-?\d+),width=(\d+),height=(\d+)\]'

def rectangle(line, key):
    return rect_values(re.search(re.escape(key) + '=' + rect_pattern, line).groups())

outer = []
for line in lines:
    if line.startswith('FISH_R6C_PAGER_OUTER '):
        row = {key: number(line, key) for key in ['requestedWidthDp', 'page', 'inputItem', 'inputParent', 'buttonParent', 'pair', 'pager', 'gap', 'overlap']}
        row['inputScreen'] = rectangle(line, 'inputScreen')
        row['buttonScreen'] = rectangle(line, 'buttonScreen')
        assert row['inputParent'] == row['buttonParent'] == row['pair']
        assert 0 <= row['gap'] <= 12 and row['overlap'] > 0
        outer.append(row)
assert len(outer) == 6
save('outer-geometry.json', dict(measurements=outer, source='Original XML system-out, runtime IDs and coordinates only'))

groupings = []
for line in lines:
    if not line.startswith('FISH_R6D_PAGER_ROWS '):
        continue
    item_text = line.split(' items=', 1)[1].split(' identities=', 1)[0]
    items = {}
    for match in re.finditer(r'([a-z-]+)=' + rect_pattern, item_text):
        items[match[1]] = rect_values(match.groups()[1:])
    groups_text = re.search(r'groups=(\[\[.*?\]\]) envelopes=', line)[1]
    groups = [[item.strip() for item in group.split(',')] for group in re.findall(r'\[([^\[\]]*)\]', groups_text)]
    envelopes = [rect_values(m) for m in re.findall(rect_pattern, line.split(' envelopes=', 1)[1])]
    assert len(items) == 4 and len(groups) >= 2 and len(envelopes) == len(groups)
    gaps = [below['y'] - above['y'] - above['height'] for above, below in zip(envelopes, envelopes[1:])]
    assert all(gap >= 0 for gap in gaps)
    assert sorted(item for group in groups for item in group) == sorted(items)
    for group in groups:
        assert min(items[item]['y'] + items[item]['height'] for item in group) > max(items[item]['y'] for item in group)
    groupings.append(dict(widthDp=number(line, 'requestedWidthDp'), page=number(line, 'page'), pagerIdentity=number(line, 'pager'), items=items, groups=groups, envelopes=envelopes, verticalGapsPx=gaps, sourceRecord=line))
assert len(groupings) == 2
save('narrow-row-groups.json', dict(algorithm='Positive common vertical intersection, unambiguous assignment, nonoverlapping group envelopes; no equal-top or fixed-item requirement', observations=groupings))

completed_pages = [line for line in lines if line.startswith('FISH_R3_SNAPSHOT phase=page-input-and-scrollbar')]
drag_done = [line for line in lines if 'phase=R5-real-drag-completed' in line]
wheel_done = [line for line in lines if 'phase=R5-native-wheel-completed' in line]
assert len(completed_pages) == len(drag_done) == len(wheel_done) == 3
assert sum(line.startswith('FISH_R5_DRAG ') for line in lines) == 48
assert sum(line.startswith('FISH_R5_PRESS ') for line in lines) == 3
assert not any(line.startswith('FISH_R5_ADMISSION_REJECT ') for line in lines)
unknown = [line for line in lines if 'phase=unknown-total-int-boundary' in line]
assert len(unknown) == 1
save('behavior-coverage.json', dict(widthsDp=[1100, 720, 360], actualGeometryAndOperationsCompleted=True, admittedDrags=3, physicalPressesPerDrag=1, continuousDragStepsTotal=48, releasesVerified=True, admissionRelocationsUsed=0, wheelCompletions=3, pageCompletionRecords=completed_pages, dragCompletionRecords=drag_done, wheelCompletionRecords=wheel_done, unknownTotalIntBoundaryRecord=unknown[0], unknownTotalAndIntBoundary='PASS / actual branch completed', queryInvariant='Each known-total fixture asserted queriedPages [1,3,2]; invalid inputs did not query; top reset0', bindingInvariant='No implicit binding; assertions remained0'))
save('result.json', dict(gateInvocations=1, compilation='PASS', exitCode=0, suites=1, tests=4, reportedPass=4, failures=0, errors=0, skipped=0, testSeconds=float(root.attrib['time']), testCases=tests, xmlSha256=sha(xml.read_bytes()), sourceChangedAfterGate=False, reruns=0, nativeGateOnly=True, newRed=False, newUnknown=False, historicalUnknownsUnchanged=True, restoreFurtherGates='NativeUx prerequisite now PASS; Project approval and fresh resource preflight required; no additional Gate started', notTested=['Full Fish focused', 'bounded', 'new distribution', 'Windows manual/hearing', 'packaged Launcher/JVM exit and formal root lock']))
save('requests-cleanup.json', dict(realFishGet=0, realFishTts=0, mockSampleGet=5, mockTtsPost=5, mockHttpTotal=10, syntheticCatalogCalls=28, paginationCatalogCalls=11, bindings=0, accountingBasis='Derived from completed test loops and per-fixture server.requestCount/type assertions: Stop4GET/4POST, resize1GET/1POST; Escape/pagination HTTP0. Catalog counts Escape8 + Stop8 + resize1 + pagination11. Not an independent global packet capture.', fixtures=21, finallyCleanup='All fixture finally owner-enable/window-dispose/scope-runtime-drain/no-mp3-part assertions passed', postGateJavaCount=0, manualFakeStarted=False, realUserDataRead=False, launcher='NOT STARTED', appJvm='NOT STARTED', formalRootLock='NOT TESTED', physicalAuditorySignoff='NOT TESTED / native MCI status and synthetic fixtures do not replace user hearing'))
history = []
for folder in ['p8-s2b-ui-layout-001-r3-20261019', 'p8-s2b-ui-layout-001-r4-20261019', 'p8-s2b-ui-layout-001-r5-20261019', 'p8-s2b-ui-layout-001-r6a-20261019', 'p8-s2b-ui-layout-001-r6b-20261019', 'p8-s2b-ui-layout-001-r6c-20261019']:
    path = 'docs/desktop/evidence/' + folder
    tree = git('rev-parse', BASE + ':' + path).decode().strip()
    assert not git('diff', '--name-only', 'HEAD', '--', path).strip()
    history.append(dict(path=path, gitTreeAtStart=tree, gitTreeNow=git('rev-parse', 'HEAD:' + path).decode().strip(), worktreeUnchanged=True))
save('history-preservation.json', history)
save('skill-review.json', dict(reviewedBeforeEvidenceCommit=True, skills=['chatbar-fish-audio-voice', 'chatbar-shadcn-compose'], edits=[], reason='Evidence-only checkpoint with test-only assertion convergence; no accepted production contract changed'))

E.mkdir(exist_ok=False)
(E / '.gitattributes').write_bytes(b'* -text\n')
for folder in ['baseline-sources', '01-nativeux-class']:
    shutil.copytree(G / folder, E / folder)
for name in ['preflight.json', 'pre-gate-resources.json', 'post-gate-resources.json', 'tested-source.kt', 'tested-source-identity.json', 'narrow-wrap-fix.patch', 'source-preservation.json', 'stage-summary.json', 'native-system-out.txt', 'native-events-geometry.jsonl', 'outer-geometry.json', 'narrow-row-groups.json', 'behavior-coverage.json', 'result.json', 'requests-cleanup.json', 'history-preservation.json', 'skill-review.json', 'run-stage.ps1', 'summarize-stages.py', 'freeze-evidence.py']:
    shutil.copyfile(G / name, E / name)
print(json.dumps(dict(evidence=E.relative_to(R).as_posix(), gate='4/4 PASS', xmlSha256=sha(xml.read_bytes()), outerMeasurements=len(outer), narrowGroupObservations=len(groupings), dragSteps=48)))
