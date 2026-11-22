"""Additive LAN8 profile. Use the existing original-artifact and native launcher engines."""
import argparse
from contextlib import contextmanager
import hashlib
import os
from pathlib import Path
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


def native_binding(repository, candidate, bridge, fixture):
    return {
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
    binding = native_binding(checkout, candidate, bridge, fixture)
    control.require(control.load(binding_path) == binding
                    and request['timestampSourceBindings'] == binding['timestampSourceBindings'],
                    'EXACT_FROZEN_NATIVE_BINDING')
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
                run_matrix(native, companion, request, output, control_sha)


def run_matrix(native, companion, request, output, control_sha):
    old = native.legacy()
    original_seed, original_writer = old.seed, old.write_json
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
                    and [row['stage'] for row in reports] == list(COMPONENT_ALIASES.values()))
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

    with scoped(old, {'seed': seeded, 'Native': RetainedNative, 'write_json': result_writer}), \
         scoped(native, {'legacy': lambda: old}):
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
            control.write_new(args.output, native_binding(args.repository, args.source_sha, bridge, fixture))
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
