from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

R = Path.cwd()
G = Path(__file__).resolve().parent
E = R / 'docs/desktop/evidence/p8-s2b-ui-layout-001-r6h-20261019'
BASE = '06945846e970fd1ea608a3c2daf5ded71393298b'

def sha(b):
    return hashlib.sha256(b).hexdigest()

def save(name, obj):
    (E / name).write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.PIPE)

assert git('rev-parse', 'HEAD').decode().strip() == BASE
assert not git('diff', '--cached', '--name-only').strip()
shutil.copytree(G / 'baseline-sources', E / 'baseline-sources', dirs_exist_ok=True)
shutil.copyfile(__file__, E / Path(__file__).name)
xml = E / '01-native-method/junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml'
b = xml.read_bytes()
root = ET.fromstring(b)
out = root.findtext('system-out') or ''
(E / 'native-system-out.txt').write_text(out, encoding='utf-8')
lines = out.splitlines()
hit = [s for s in lines if s.startswith('FISH_R6H_NATIVE_HITTEST ')]
assert len(hit) == 1 and 'phase=before-press ' in hit[0]
(E / 'win32-hit-test-raw.jsonl').write_text(json.dumps({'source': xml.relative_to(E).as_posix(), 'record': hit[0]}, ensure_ascii=False) + '\n', encoding='utf-8')
line = hit[0]

def point(key):
    m = re.search(r'\b' + key + r'=java\.awt\.Point\[x=(-?\d+),y=(-?\d+)\]', line)
    assert m, key
    return {'x': int(m[1]), 'y': int(m[2])}

def value(key):
    m = re.search(r'\b' + key + r'=([^ ]+)', line)
    assert m, key
    return m[1]

planned, awt, native = point('planned'), point('awt'), point('native')
stable = [s for s in lines if s.startswith('FISH_R5_ADMISSION ')]
assert len(stable) == 3 and all('matches=true' in s for s in stable)
thumb_match = re.search(r'thumb=java\.awt\.Rectangle\[x=(\d+),y=(\d+),width=(\d+),height=(\d+)\]', stable[0])
assert thumb_match
thumb = dict(zip(['x', 'y', 'width', 'height'], map(int, thumb_match.groups())))
press_records = sum(s.startswith('FISH_R5_PRESS ') for s in lines)
drag_records = sum(s.startswith('FISH_R5_DRAG ') for s in lines)
assert press_records == 0 and drag_records == 0
assert value('destinationVerified') == 'false'
assert value('hitRootOwned') == 'false' and value('foregroundOwned') == 'false'
save('input-target-observations.json', {
    'basis': 'Raw JUnit system-out; sequential read-only API observations, not an atomic OS snapshot',
    'sourceRecord': line,
    'phase': 'before-press',
    'probeAvailable': value('available') == 'true',
    'ownedHwnd': value('owned'),
    'ownedRoot': value('ownedRoot'),
    'currentJvmPid': int(value('jvmPid')),
    'planned': planned, 'awtPointer': awt, 'win32Pointer': native,
    'driftAfterStableSamples': {'x': native['x'] - planned['x'], 'y': native['y'] - planned['y']},
    'stableSamples': stable,
    'actualThumb': thumb,
    'pointerCoordinatesAgree': awt == native,
    'pointerAtPlan': value('pointerAtPlan') == 'true',
    'thumbHit': value('thumbHit') == 'true',
    'hit': value('hit'), 'hitRoot': value('hitRoot'),
    'hitRootOwned': False, 'hitInJvm': value('hitInJvm') == 'true',
    'foreground': value('foreground'), 'foregroundRoot': value('foregroundRoot'),
    'foregroundOwned': False,
    'ownedRootValid': value('ownedRootValid') == 'true',
    'awtShowing': value('showing') == 'true',
    'awtActive': value('active') == 'true', 'awtFocused': value('focused') == 'true',
    'listenerRegistered': value('listenerRegistered') == 'true',
    'observeScrollbar': value('observeScrollbar') == 'true',
    'prePressLeftKeyState': int(value('leftKeyState')),
    'prePressLeftDown': value('leftDown') == 'true',
    'afterPressButtonState': 'NOT SAMPLED: Press blocked before invocation',
    'afterReleaseButtonState': 'NOT SAMPLED: no matching Press; no Release invoked',
    'robotPressInvocations': 0, 'robotReleaseInvocations': 0,
    'ownedAwtEvents': int(value('ownedEvents')), 'ownedAwtPresses': int(value('ownedPresses')),
    'windowSelfEvents': int(value('windowSelfEvents')), 'descendantEvents': int(value('descendantEvents')),
    'regionOtherEvents': int(value('regionOtherEvents')),
    'observedClassifications': ['HIT_TEST_TARGET_MISMATCH', 'FOREGROUND_MISMATCH'],
    'notApplicableClassifications': ['NATIVE_BUTTON_STATE_NOT_OBSERVED', 'NATIVE_DOWN_WITHOUT_AWT_EVENT', 'OWNED_INPUT_DELIVERED'],
    'foreignWindowIdentifiers': 'Only OTHER_PROCESS classifications recorded; no raw HWND/PID/title/command line',
    'foreignHitEqualsForeignForeground': 'UNKNOWN; not recorded and not inferred',
    'plannedPointHit': 'NOT SAMPLED: WindowFromPoint queried actual cursor, not the earlier plan',
    'historicalR6FR6GPress0RootCause': 'UNKNOWN: no historical native hit snapshot',
    'pointerMovementAndForegroundChangeSource': 'UNKNOWN',
})
case = root.find('testcase')
failure = case.find('failure')
assert failure is not None and 'INPUT_DESTINATION_UNVERIFIED' in failure.attrib['message']
save('junit-ledger.json', {
    'path': xml.relative_to(E).as_posix(), 'bytes': len(b), 'sha256': sha(b),
    'suite': root.attrib, 'testCase': case.attrib, 'failure': failure.attrib,
    'executionCount': 1, 'compileTestKotlin': 'PASS', 'exitCode': 1,
    'testResult': '1 suite / 1 test / 0 PASS / 1 FAIL / 0 errors / 0 SKIPPED',
    'firstFailureLocation': 'DesktopFishPickerNativeUxTest.kt:608',
    'firstFailureMeaning': 'Safety precondition failed before sending Press; original Press-count assertion NOT REACHED',
})
save('reachability-and-cleanup.json', {
    'newObservedPass': ['compileTestKotlin', '1100dp initial pager layout/8px adjacency', 'R6G deterministic window-self/descendant ownership checks', 'three stable pointer samples'],
    'safeRed': '1100dp before-press native hit/foreground admission',
    'notReached': ['physical Press', '16-step Drag', 'physical Release', 'wheel', 'page jump/validation', '720dp', '360dp', 'unknown total', 'Int boundary'],
    'notTested': ['NativeUx other three methods', 'Fish full focused', 'bounded', 'distribution', 'Windows manual', 'packaged Launcher/JVM/root lock'],
    'rawPressPlanRecords': press_records, 'rawDragStepRecords': drag_records,
    'repositionRecords': sum(s.startswith('FISH_R5_ADMISSION_REJECT ') for s in lines),
    'lastOriginalSnapshot': next(s for s in reversed(lines) if s.startswith('FISH_R4_SCROLL ')),
    'syntheticCatalogCalls': 1, 'mockHttpRequests': 0, 'bindings': 0,
    'realFishGet': 0, 'realFishTts': 0,
    'fixtureCleanup': {
        'basis': 'Unchanged method finally executes Fixture.dispose and server.close; no cleanup exception in sole failure; postflight Java/relevant processes empty',
        'disposeAssertions': ['owner no longer disabled', 'scope Job joined after runtime.closeAndDrain', 'no .mp3 or .part under owned synthetic root'],
        'windows': 'fixture child and owner disposed; observer removed by existing finally',
        'release': 'pressed flag remained false; no Release required or invoked',
        'emptyTemporaryDirectories': 'May remain; no broad deletion or user-data cleanup performed',
        'packagedApplicationCleanup': 'NOT TESTED; native fixture is not distribution Launcher/root-lock evidence',
    },
})
patch = E / 'native-hit-test-only.patch'
save('source-diff-identity.json', {
    'patch': patch.name, 'bytes': patch.stat().st_size, 'sha256': sha(patch.read_bytes()),
    'change': 'Only test-private readonly Win32 diagnostics and immediate input safety preconditions',
    'delta': {'insertions': 118, 'deletions': 2},
    'productionDrafts': 'byte-identical to start; no production edits',
    'sourceCheckpoint': 'NONE; three Kotlin drafts remain unstaged/uncommitted',
    'baselineAndTested': 'source-baseline-and-tested.json',
})
save('scope-and-skill-review.json', {
    'skillReviewed': '.agents/skills/chatbar-fish-audio-voice/SKILL.md',
    'skillUpdateNeeded': False,
    'reason': 'Test-private input diagnostics and evidence do not change Fish feature wiring or contracts; only authorized new evidence staged',
    'productionChanges': False, 'gradleDependencyChanges': False,
    'robotMechanism': 'Original mouseMove, 3 stable samples, maximum one relocation, single Press, 16 Drag steps, Release, wheel unchanged; safety guard can prevent unsafe input',
    'preserved': ['R6G ownership observer correction', 'pager outer measurement/12px/row assertions', 'all original query/cache/binding/cleanup assertions'],
    'prohibitedApisUsed': [],
    'noEdits': ['Main/window lifecycle', 'Fish HTTP/Runtime/Store/player', 'SecretStore', 'Prompt/Package/Entity/schema', 'CURRENT/parity/baseline', 'R3-R6G historical evidence', 'four user-owned files'],
    'screenshotsOrRecording': 'NONE', 'realUserDataOrCredentials': 'NOT ACCESSED',
})
save('alternative-validation-proposal.json', {
    'status': 'PROPOSAL ONLY / PROJECT AUTHORIZATION REQUIRED / NOT EXECUTED',
    'proposal': 'One synthetic owned-window manual-input control in a stable interactive Windows session, retaining readonly Win32/AWT recorder and original scroll/item/query/binding assertions; compare user physical mouse with Robot',
    'purpose': 'Separate Robot delivery from target/control behavior without force-foreground, extra synthetic Press, UIA/SendInput substitution or changing acceptance thresholds',
    'evidenceSeparation': 'User text signoff and actual listener/scroll observations remain separate from automated test PASS; no replacement acceptance claimed',
    'notProposed': ['closing unrelated apps', 'UAC or permission changes', 'unbounded retries', 'direct LazyList state or semantic scroll'],
})
closing = json.loads((E / 'closing-state.json').read_text(encoding='utf-8'))
closing.update(status='R6H HOLD / INPUT DESTINATION UNVERIFIED / PROJECT REVIEW PENDING',
               observations=['HIT_TEST_TARGET_MISMATCH', 'FOREGROUND_MISMATCH'],
               historicalPress0RootCause='UNKNOWN', robotPressSent=False,
               nextGate='STOP; no R6I/native class/bounded/package/manual started')
save('closing-state.json', closing)
save('static-validation.json', {'status': 'pending static verification'})
print(json.dumps({'xmlBytes': len(b), 'xmlSha256': sha(b), 'records': len(hit), 'planned': planned, 'actual': native, 'noPressSent': True}))
