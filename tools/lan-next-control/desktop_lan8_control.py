"""Additive LAN8 profile. Use the existing original-artifact and native launcher engines."""
import argparse
from contextlib import contextmanager
import hashlib
import os
from pathlib import Path
import re
import uuid

import desktop_control as control

BASE_SOURCE = 'f5fc060546eb9479b2566375761903d383c538b1'
BASE = {
    'series': 'lan7', 'version': '0.1.0-lan.7', 'sourceSha': BASE_SOURCE,
    'controlSha': '3bd17c5c78c7e1dd3a67e2b082675f292a57cf1d',
    'runId': 44662039110, 'artifactId': 14596173695,
    'artifactName': 'ccb-lan6-lan7-independent-desktop',
    'outerBytes': 210775355,
    'outerSha256': '6c1bec0a8ab4db25f81f47d1514f8b823096290b6c9a72aedb42d46a4fd82140',
    'filename': 'CCB-0.1.0-lan.7-independent-windows-x64.zip',
    'innerBytes': 210660167,
    'innerSha256': '390b2a0630316140ca3f9703ce92eb5f17091226989701472c33f0ecd3d6c457',
    'installerVersion': '0.1.40007', 'buildNumber': '40007',
}
PROFILE = {
    'BASE': BASE, 'BUILD_KIND': 'CCB_LAN7_LAN8_DESKTOP_BUILD_V1',
    'UPGRADE_KIND': 'CCB_EXACT_LAN7_LAN8_DESKTOP_PROFILE_UPGRADE_V1',
    'MANIFEST_KIND': 'CCB_LAN8_INDEPENDENT_DESKTOP_ORIGINAL_V1',
    'SERIES': 'lan8', 'VERSION': '0.1.0-lan.8',
    'INSTALLER': '0.1.40008', 'BUILD': '40008',
    'FILENAME': 'CCB-0.1.0-lan.8-independent-windows-x64.zip',
    'ARTIFACT_NAME': 'ccb-lan7-lan8-independent-desktop',
}
NATIVE_RESULT = 'native-lan7-lan8-upgrade-result.json'
NATIVE = {
    'BASE_SERIES': 'lan7', 'CANDIDATE_SERIES': 'lan8',
    'RESULT_KIND': 'EXACT_LAN7_LAN8_PROGRAM_ONLY_GROUP_RETAINED_UPGRADE',
    'RESULT_FILE': NATIVE_RESULT, 'SUCCESS_MARKER': 'LAN7_LAN8_NATIVE_UPGRADE_PASS',
}
MAIN_STAGES = ('lan7-fresh', 'lan7-populated', 'lan7-warm-control',
               'lan8-upgraded', 'lan8-cold-reopen')
# Raw reports keep their frozen fixture's internal stage names. The summary binds each to its real stage/source.
COMPONENT_ALIASES = {
    'lan7-seed': 'lan5-seed', 'lan7-populated': 'lan5-populated',
    'lan7-warm-control': 'lan5-warm-control', 'lan8-upgraded': 'lan6-upgraded',
    'lan8-cold-reopen': 'lan6-cold-reopen',
}
BRIDGE_SHA = '896abd01777e01084fe83d363c288eab3fa4d5135016ebec8b722452d9f293f1'
BRIDGE_PATH = Path(control.__file__).resolve().with_name('desktop_group_retained_upgrade_control.py')
RUNTIME_PATHS = (
    'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/Main.kt',
    'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopAppContainer.kt',
    'tools/desktop/nsis/build_independent_zip.py',
    'tools/desktop/nsis/independent_smoke.py',
)
SHARED = 'app/sharedCore/src/main/kotlin/com/example/chatbar/'
DESKTOP = 'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/'
SCROLL_PATH = 'chat_scroll_positions/profile-upgrade-session.json'
SCROLL_RULE = 'EXACT_LAN7_LAN8_SCROLL_CLOSURE_OBSERVED_OLD_CAPTURED_AT_ONLY'
CAPTURED_AT = re.compile(rb'("capturedAt"[ \t\r\n]*:[ \t\r\n]*)-?[0-9]+(?=[ \t\r\n]*[,}])')
# The complete files below own the scroll model, writer, viewport and data admission.
# For files with unrelated edits, retain the exact ranges that implement or bind these operations.
SCROLL_SOURCES = {
    SHARED + 'data/local/entity/ChatScrollPosition.kt': (),
    SHARED + 'data/local/StorageCommitRevision.kt': (),
    SHARED + 'data/operation/AppDataOperationGate.kt': (),
    DESKTOP + 'DesktopChatReadingPositionWriter.kt': (),
    DESKTOP + 'DesktopChatViewportState.kt': (),
    DESKTOP + 'DesktopDataOperationCoordinator.kt': (),
    SHARED + 'data/local/JsonFileStorage.kt': (
        ('package ', '    suspend fun <T : Any> saveEntityUncached('),
        ('    /** 批量保存大型实体，但不把完整对象放入通用缓存。 */', None),
    ),
    DESKTOP + 'DesktopPrimaryChatController.kt': (
        ('package ', '    suspend fun editMessage('),
        ('    suspend fun deleteMessageImage(', None),
    ),
    DESKTOP + 'DesktopPrimaryChatPanel.kt': (
        ('private fun PrimaryTimeline(', '        if (!background.isNullOrBlank()) DesktopOwnedImage('),
        ('        LazyColumn(\n            Modifier.width(placement.contentWidthDp.dp).fillMaxHeight().alpha',
         'internal fun PrimaryMessageBubble('),
    ),
    DESKTOP + 'DesktopAppContainer.kt': (
        ('    val appDataRoot: Path =', '    private val lanSyncOwner ='),
    ),
    SHARED + 'data/repository/ChatRepository.kt': (
        ('        private const val SESSION_TYPE', '        private const val MESSAGE_TYPE'),
        ('        private const val SCROLL_POSITION_TYPE', '        private const val INITIAL_WINDOW_TURNS'),
        ('    private val _sessions = ', '    suspend fun reloadAfterSessionCommit('),
        ('    suspend fun getAllSessions()', '    suspend fun createSession('),
        ('    private suspend fun <T> Mutex.withDataAccess', '    suspend fun updateSession(session: ChatSession) {'),
        ('    suspend fun getScrollPosition(', '    suspend fun deleteSession('),
    ),
}
EVIDENCE = frozenset(('source-request.json', 'original-provenance.json',
    'original-input-bytes.json', 'native-source-binding.json', NATIVE_RESULT,
    *('group-component-' + stage + '.json' for stage in COMPONENT_ALIASES.values())))


@contextmanager
def scoped(module, values):
    previous = {key: getattr(module, key) for key in values}
    try:
        for key, value in values.items():
            setattr(module, key, value)
        yield
    finally:
        for key, value in previous.items():
            setattr(module, key, value)


def verify_bridge():
    control.require(control.file_hash(BRIDGE_PATH) == BRIDGE_SHA, 'EXACT_RETAINED_GROUP_BRIDGE')
    import desktop_group_retained_upgrade_control as bridge
    control.require(Path(bridge.__file__).resolve(strict=True) == BRIDGE_PATH, 'EXACT_BRIDGE_IMPORT')
    control.require(bridge.digest(bridge.FIXTURE) == bridge.FIXTURE_SHA, 'EXACT_BASE_GROUP_FIXTURE')
    control.require(bridge.digest(bridge.EMPTY_PROOF_JAVA) == bridge.EMPTY_PROOF_SHA, 'EXACT_EMPTY_OBJECT_FIXTURE')
    return bridge


def rebound_fixture(bridge, candidate):
    control.sha(candidate, 40)
    control.require(candidate != BASE_SOURCE, 'NEW_SOURCE_REQUIRED')
    raw = bridge.FIXTURE.read_bytes()
    changes = ((bridge.OLD.encode('ascii'), BASE_SOURCE.encode('ascii')),
               (bridge.NEW.encode('ascii'), candidate.encode('ascii')))
    control.require(all(raw.count(before) == 1 for before, _ in changes), 'EXACT_GROUP_FIXTURE_SOURCE_PINS')
    for before, after in changes:
        raw = raw.replace(before, after)
    return raw


def source_slice(body, start, end):
    start = start.encode('utf-8')
    stop = end.encode('utf-8') if end is not None else None
    control.require(body.count(start) == 1 and (stop is None or body.count(stop) == 1),
                    'EXACT_SCROLL_SOURCE_ANCHORS')
    first = body.index(start)
    last = body.index(stop) if stop is not None else len(body)
    control.require(first < last, 'EXACT_SCROLL_SOURCE_RANGE')
    return body[first:last]


def reading_position_binding(repository, candidate, build_control):
    control.sha(build_control, 40)
    control.require(control.git_bytes(repository, 'rev-parse', '--verify', candidate + '^{commit}')
                    .decode('ascii').strip() == candidate, 'EXACT_SCROLL_CANDIDATE_COMMIT')
    files = []
    for path, ranges in SCROLL_SOURCES.items():
        base, newer = (control.git_bytes(repository, 'show', sha + ':' + path) for sha in (BASE_SOURCE, candidate))
        parts = ([(source_slice(base, start, end), source_slice(newer, start, end)) for start, end in ranges]
                 if ranges else [(base, newer)])
        control.require(all(left == right for left, right in parts), 'SCROLL_SOURCE_CLOSURE_CHANGED:' + path)
        files.append({'path': path, 'baseFileSha256': hashlib.sha256(base).hexdigest(),
                      'candidateFileSha256': hashlib.sha256(newer).hexdigest(),
                      'equalSlicesSha256': [hashlib.sha256(left).hexdigest() for left, _ in parts]})
    return {'rule': SCROLL_RULE, 'baseSourceSha': BASE_SOURCE, 'candidateSourceSha': candidate,
            'candidateBuildControlSha': build_control, 'files': files}


def native_binding(repository, candidate, bridge, fixture, scroll_build_control=None):
    result = {
        'kind': 'CCB_LAN7_LAN8_NATIVE_BINDING_V1',
        'baseSourceSha': BASE_SOURCE,
        'candidateSourceSha': candidate,
        'runtimeFiles': [
            {'path': path, 'baseSha256': hashlib.sha256(control.git_bytes(
                repository, 'show', BASE_SOURCE + ':' + path)).hexdigest(),
             'candidateSha256': hashlib.sha256(control.git_bytes(
                repository, 'show', candidate + ':' + path)).hexdigest()}
            for path in RUNTIME_PATHS
        ],
        'timestampSourceBindings': control.source_bindings(repository, candidate),
        'retainedGroupBridgeSha256': BRIDGE_SHA,
        'originalFixtureSha256': bridge.FIXTURE_SHA,
        'reboundFixtureSha256': hashlib.sha256(fixture).hexdigest(),
        'emptyObjectProofSha256': bridge.EMPTY_PROOF_SHA,
    }
    if scroll_build_control is not None:
        result['readingPositionEquivalence'] = reading_position_binding(repository, candidate, scroll_build_control)
    return result


def require_native_binding(repository, request, supplied, bridge, fixture):
    candidate = request['packages'][1]
    build_control = candidate['controlSha'] if 'readingPositionEquivalence' in supplied else None
    binding = native_binding(repository, candidate['sourceSha'], bridge, fixture, build_control)
    control.require(supplied == binding and request['timestampSourceBindings'] == binding['timestampSourceBindings'],
                    'EXACT_FROZEN_NATIVE_BINDING')
    return binding


class ScrollRetention:
    def __init__(self, bridge, json_bodies):
        self.bridge = bridge
        self.original_json_bodies = json_bodies
        self.snapshots = {}
        self.completed = 0
        self.previous_timestamp = None

    def snapshot(self, directory, digest):
        path = directory / SCROLL_PATH
        control.require(path.is_file() and not path.is_symlink() and path.resolve() == path,
                        'SCROLL_SNAPSHOT_PATH')
        raw = path.read_bytes()
        control.require(hashlib.sha256(raw).hexdigest() == digest, 'SCROLL_SNAPSHOT_CHANGED')
        value = self.bridge.strict_scroll_json(raw)
        masked, count = CAPTURED_AT.subn(rb'\g<1>0', raw)
        control.require(count == 1, 'SCROLL_CAPTURED_AT_OCCURRENCE')
        return value, masked

    def json_bodies(self, directory, inventory):
        bodies = self.original_json_bodies(directory, inventory)
        if SCROLL_PATH in inventory:
            digest = inventory[SCROLL_PATH]
            value, masked = self.snapshot(directory, digest)
            control.require(bodies[SCROLL_PATH] == value, 'SCROLL_SNAPSHOT_CHANGED')
            self.snapshots[digest] = (value['capturedAt'], masked)
        return bodies

    def retain(self, owner, _request, before, after, bodies, directory, old_control):
        control.require(before.keys() == after.keys() and SCROLL_PATH in before, 'SCROLL_RETENTION_PATHS_CHANGED')
        original, current = before[SCROLL_PATH], after[SCROLL_PATH]
        matches = [row for row in old_control if row['path'] == SCROLL_PATH and
                   row.get('afterSha256') == original and row.get('changedTopLevelFields') == ['capturedAt']]
        control.require(len(matches) == 1 and len(old_control) == 1, 'SCROLL_CONTROL_NOT_OBSERVED')
        old_digest = matches[0].get('beforeSha256')
        control.require(old_digest in self.snapshots and original in self.snapshots, 'SCROLL_CONTROL_BYTES_CHANGED')
        old_time, old_masked = self.snapshots[old_digest]
        warm_time, warm_masked = self.snapshots[original]
        control.require(old_masked == warm_masked and 0 <= old_time < warm_time, 'SCROLL_CONTROL_BYTES_CHANGED')
        control.require(all(after[path] == digest for path, digest in before.items() if path != SCROLL_PATH),
                        'PREEXISTING_ENTITY_BYTES_CHANGED')
        value, masked = self.snapshot(directory, current)
        control.require(masked == warm_masked, 'SCROLL_NON_TIMESTAMP_BYTES_CHANGED')
        if self.previous_timestamp is not None:
            control.require(value['capturedAt'] >= self.previous_timestamp, 'SCROLL_CAPTURE_TIME_REGRESSED')
        result = owner.verify_retained_entities(before, after, bodies, directory, old_control)
        self.previous_timestamp = value['capturedAt']
        self.completed += 1
        return result


def run_native(request_path, binding_path, build_path, output):
    control.cloud()
    checkout = Path.cwd().resolve(strict=True)
    control.require(checkout == Path(os.environ['GITHUB_WORKSPACE']).resolve(strict=True)
                    and not control.git_bytes(checkout, 'status', '--porcelain', '--untracked-files=all'),
                    'EXACT_CLEAN_CONTROL_WORKSPACE')
    control_sha = control.git_bytes(checkout, 'rev-parse', 'HEAD').decode().strip()
    control.require(control_sha == os.environ['GITHUB_SHA'], 'EXACT_CONTROL_HEAD')
    bridge = verify_bridge()
    request = control.validate_upgrade(control.load(request_path))
    build = control.validate_build(control.load(build_path))
    candidate = request['packages'][1]['sourceSha']
    control.require(build['sourceSha'] == candidate, 'FROZEN_BUILD_AND_UPGRADE_SOURCE_MATCH')
    fixture = rebound_fixture(bridge, candidate)
    binding = require_native_binding(checkout, request, control.load(binding_path), bridge, fixture)
    runner = Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
    control.require(not runner.is_relative_to(checkout) and not checkout.is_relative_to(runner)
                    and output.parent.resolve(strict=True) == checkout
                    and all(not (checkout / ('input-' + row['series'])).exists()
                            for row in request['packages']), 'ISOLATED_RUNNER_OUTPUT')
    with bridge.private_helpers() as modules:
        original_control = modules['desktop_control']
        native = modules['prove_desktop_lan3_lan4']
        control.require(bridge.digest(native.LEGACY) == bridge.LEGACY_SHA, 'EXACT_NATIVE_LAUNCHER_OWNER')
        with scoped(original_control, PROFILE), scoped(native, NATIVE):
            original_control.fetch_originals(request, output)
            control.write_new(output / 'native-source-binding.json', binding)
            private = runner / ('ccb-lan7-lan8-companions-' + uuid.uuid4().hex)
            private.mkdir()
            sources = {}
            for revision, leaf in ((BASE_SOURCE, 'lan7-source'), (candidate, 'lan8-source')):
                source = private / leaf
                bridge.command('git', '-C', str(checkout), 'worktree', 'add', '--detach', str(source), revision)
                control.require(bridge.command('git', '-C', str(source), 'rev-parse', 'HEAD').decode().strip()
                                == revision, 'EXACT_COMPANION_SOURCE')
                sources[revision] = source
            fixture_path = private / bridge.FIXTURE.name
            fixture_path.write_bytes(fixture)
            with scoped(bridge, {'OLD': BASE_SOURCE, 'NEW': candidate, 'FIXTURE': fixture_path,
                                 'FIXTURE_SHA': binding['reboundFixtureSha256']}):
                companion = bridge.Companion(modules, sources, private, output)
                companion.prepare()
                scroll_bridge = bridge if 'readingPositionEquivalence' in binding else None
                run_matrix(native, companion, request, output, control_sha, scroll_bridge)


def run_matrix(native, companion, request, output, control_sha, scroll_bridge=None):
    old = native.legacy()
    original_seed, original_writer = old.seed, old.write_json
    scroll = ScrollRetention(scroll_bridge, old.json_bodies) if scroll_bridge is not None else None
    completed = []
    phase = 'BEFORE_FIRST_MAIN'
    image = None
    launcher_digest = None

    def seeded(data):
        nonlocal image, phase
        phase = 'LAN7_GROUP_SEED'
        image = original_seed(data)
        companion.run(data.parents[2], COMPONENT_ALIASES['lan7-seed'])
        return image

    class RetainedNative(old.Native):
        def launch(self, stage, environment):
            nonlocal phase, launcher_digest
            phase = 'PACKAGED_MAIN_' + stage
            launcher = self.root / '启动独立资料版.cmd'
            if launcher_digest is None:
                launcher_digest = old.sha(launcher)
            control.require(old.sha(launcher) == launcher_digest, 'ORIGINAL_ROOT_LAUNCHER_CHANGED')
            value = super().launch(stage, environment)
            completed.append(stage)
            if stage in COMPONENT_ALIASES:
                phase = 'SEPARATE_GROUP_COMPONENT_' + stage
                data = self.root / 'UserData/Local/ChatChatBarDesktop'
                before = (old.inventory(data / 'entities'), old.sha(image))
                companion.run(self.root, COMPONENT_ALIASES[stage])
                control.require(before == (old.inventory(data / 'entities'), old.sha(image)),
                                'COMPONENT_CHANGED_SYNTHETIC_ENTITIES_OR_IMAGE')
            return value

    def result_writer(path, value):
        if path.name != NATIVE_RESULT:
            return original_writer(path, value)
        value = dict(value)
        reports = companion.original_descriptors
        complete = (tuple(completed) == MAIN_STAGES
                    and [row['stage'] for row in reports] == list(COMPONENT_ALIASES.values())
                    and (scroll is None or scroll.completed == 2))
        if scroll is not None:
            value.update(readingPositionSourceEquivalenceRule=SCROLL_RULE,
                         readingPositionSourceEquivalenceCompleted=scroll.completed)
        if 'failure' in value:
            value['failure'] = {'phase': phase, 'type': value['failure']['type']}
        value.update(
            controlSha=control_sha, passed=bool(value['passed'] and complete),
            packagedMainStagesCompleted=completed,
            separateDpapiComponentStageAliases=COMPONENT_ALIASES,
            separateDpapiComponentOriginals=reports, peerOrIdentitySeeded=bool(reports),
            sameRunnerAccountAndRetainedRoot=complete, originalRootLauncherSha256=launcher_digest,
            packagedMainGroupUiExercised=False,
            rollbackTested=False, humanAcceptance=False,
        )
        original_writer(path, value)
        if 'failure' not in value:
            control.require(value['passed'], 'EXACT_NATIVE_AND_COMPONENT_MATRIX')

    old_hooks = {'seed': seeded, 'Native': RetainedNative, 'write_json': result_writer}
    native_hooks = {'legacy': lambda: old}
    if scroll is not None:
        old_hooks['json_bodies'] = scroll.json_bodies
        native_hooks['retained_entities'] = scroll.retain
    with scoped(old, old_hooks), scoped(native, native_hooks):
        native.run(request, output)


def audit_output(output):
    control.require(output.is_dir() and not output.is_symlink(), 'EXACT_SMALL_OUTPUT')
    files = list(output.iterdir())
    control.require(all(p.name in EVIDENCE and p.is_file() and not p.is_symlink()
                        and 0 < p.stat().st_size < 262144 for p in files), 'EXACT_SMALL_OUTPUT')
    result = output / NATIVE_RESULT
    if result.is_file() and control.load(result).get('passed') is True:
        control.require({p.name for p in files} == EVIDENCE, 'INCOMPLETE_SUCCESS_EVIDENCE')


def main():
    import sys
    commands = ('native-binding', 'retained', 'audit-output')
    with scoped(control, PROFILE):
        if len(sys.argv) < 2 or sys.argv[1] not in commands:
            return control.main()
        parser = argparse.ArgumentParser()
        sub = parser.add_subparsers(dest='action', required=True)
        bind = sub.add_parser('native-binding')
        bind.add_argument('--source-sha', required=True)
        bind.add_argument('--scroll-equivalence-build-control-sha')
        bind.add_argument('--repository', type=Path, required=True)
        bind.add_argument('--output', type=Path, required=True)
        retained = sub.add_parser('retained')
        for name in ('request', 'binding', 'build', 'output'):
            retained.add_argument('--' + name, type=Path, required=True)
        audit = sub.add_parser('audit-output')
        audit.add_argument('--output', type=Path, required=True)
        args = parser.parse_args()
        if args.action == 'native-binding':
            bridge = verify_bridge()
            fixture = rebound_fixture(bridge, args.source_sha)
            binding = native_binding(args.repository, args.source_sha, bridge, fixture,
                                     args.scroll_equivalence_build_control_sha)
            control.write_new(args.output, binding)
        elif args.action == 'audit-output':
            audit_output(args.output)
        else:
            try:
                run_native(args.request, args.binding, args.build, args.output)
            except Exception as failure:
                print('LAN7_LAN8_NATIVE_REFUSED:' + type(failure).__name__, file=sys.stderr)
                return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
