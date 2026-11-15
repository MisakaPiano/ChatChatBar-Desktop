"""Isolated cloud-only V5/e606 -> LAN6/7ce Program swap + real group component.

All package/provenance/ZIP/data/native-window work remains in the pinned owners.
The companion is compiled in separate fixed source worktrees, never the originals.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid

HERE = Path(__file__).resolve().parent
OLD = 'e606bf2a2723fbc896323a8071cfd52dde99a4c6'
NEW = 'a6bb882d7cef9aceba3fda3b0d33329f9d687d8f'
BUILD_CONTROL = '1da064e91a7a94c4ff16da8934b8cec4bbfdb200'
ORDER = ('contract', 'desktop_control', 'prove_desktop_lan3_lan4', 'desktop_lan5_control',
         'desktop_lan6_control', 'instrumentation_report', 'group_ui_control', 'desktop_group_ui_control')
HELPERS = {
    'contract.py': '53f01f7ff3190ee0c677e9221240f77c9e4ab8e95675d7d8533782bc09e98eb2',
    'desktop_control.py': '6fcd6ace566b67e1693fd4b35781d8a1feda3fa8de618c758c165ad701be0cfb',
    'prove_desktop_lan3_lan4.py': 'cbb40b2fd689e986957f7956cb521956425a7885063ddc7724ee5af227d3d686',
    'desktop_lan5_control.py': '6694974cf280141d637f2c3f5359c5609db668f598d6ae00eb69d55191801199',
    'desktop_lan6_control.py': 'b2fb329933250c9312248a20d984dcc308bd0b4c61f5a259df3872c63d9a2f55',
    'instrumentation_report.py': 'af65a1a275fa5d4b80c7c46d1f09bb03bb9c19e5c822bad928974f6f67f22158',
    'group_ui_control.py': 'c2f4dc2ffb7f42933abb1286982e7e990d5f292fa25fa2066d66300958a0c77b',
    'desktop_group_ui_control.py': '1b0c56542824dad1f7462c77aee66614eda4f037958239c4f7d2d0e201c75b66',
}
LEGACY_SHA = 'a3b1db7cea226b14c872ba222607ccae5925f449de2e18521a521af61e5753ce'
FIXTURE = HERE / 'fixtures/DesktopGroupRetainedUpgradeControlTest.kt'
FIXTURE_SHA = '3bb73d144d8c174a5c02cf657de9be92f7f4c2096aa3cb6fd7f7be96e79be738'
CLASS = 'com.example.chatbar.desktop.lanSync.DesktopGroupRetainedUpgradeControlTest'
METHOD = 'seedOrVerifyRealGroupInRetainedIndependentUserData'
TARGET = 'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/lanSync/DesktopGroupRetainedUpgradeControlTest.kt'
REQUEST = '.github/lan-next-desktop-group-retained-upgrade-request.json'
EVIDENCE = 'desktop-group-retained-upgrade-evidence'
RESULT = 'native-lan5-lan6-group-retained-upgrade-result.json'
COMPONENTS = ('lan5-seed', 'lan5-populated', 'lan5-warm-control', 'lan6-upgraded', 'lan6-cold-reopen')
TOP = ('source-request.json', 'original-provenance.json', 'original-input-bytes.json', RESULT,
       'group-retained-validation.json')
OUTPUTS = TOP + tuple('group-component-' + stage + '.json' for stage in COMPONENTS)
PUBLIC_KEYS = {
    'kind', 'passed', 'action', 'stage', 'sourceSha', 'packagedMainLaunch', 'realAppContainer', 'realWindowsDpapi',
    'rootAndDailyDistinct', 'authorSequence', 'nativeSequence', 'nativeUnitCount', 'groupIdentityDigest',
    'protectedFileCount', 'protectedInventorySha256', 'routeDisabled', 'routeDpapiUnprotected',
    'protectedBytesRetained', 'observerProtectedBytesUnchangedOnClose', 'componentPaused', 'durablePauseClaimed',
    'closeNoticeCount', 'recoveryInputUsedByVerifier', 'networkDeliveryVerified', 'defaultCredentialReadBack',
}
_phase = 'PROFILE'


def require(condition, code):
    if not condition:
        raise ValueError(code)


def digest(path):
    require(path.is_file() and not path.is_symlink(), 'PIN_FILE_REQUIRED')
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pairs(items):
    result = {}
    for key, value in items:
        require(key not in result, 'DUPLICATE_JSON_KEY')
        result[key] = value
    return result


def small_json(path):
    require(path.is_file() and not path.is_symlink() and 0 < path.stat().st_size < 65536, 'SMALL_JSON_REQUIRED')
    return json.loads(path.read_bytes().decode('utf8'), object_pairs_hook=pairs)


def write_new(path, value):
    with path.open('x', encoding='utf8', newline='\n') as output:
        json.dump(value, output, indent=2, ensure_ascii=False)
        output.write('\n')


@contextmanager
def private_helpers(directory=HERE):
    """No shared defaults, import aliases or existing profile code survive mutation."""
    directory = Path(directory)
    previous = {name: sys.modules.get(name) for name in ORDER}
    modules = {}
    try:
        for name in ORDER:
            path = directory / (name + '.py')
            require(digest(path) == HELPERS[path.name], 'TRUSTED_HELPER_CHANGED')
            spec = importlib.util.spec_from_file_location(name, path)
            module = importlib.util.module_from_spec(spec)
            sys.modules[name] = module
            exec(compile(path.read_bytes(), str(path), 'exec'), module.__dict__)
            modules[name] = module
        yield modules
    finally:
        for name, prior in previous.items():
            if prior is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = prior


def validate_request(base, request):
    # This is the unchanged exact original upgrade contract, with two additional fixed identity checks.
    base.validate_upgrade(request)
    require(type(request['format']) is int, 'INTEGER_FORMAT_REQUIRED')
    require(request['packages'][0]['sourceSha'] == OLD and request['packages'][1]['sourceSha'] == NEW,
            'EXACT_E606_TO_A6_REQUIRED')
    require(request['packages'][1]['controlSha'] == BUILD_CONTROL, 'EXACT_A6_BUILD_CONTROL_REQUIRED')
    return request


def component_report(value, stage):
    require(type(value) is dict and set(value) == PUBLIC_KEYS, 'COMPONENT_REPORT_FIELDS')
    require(value['kind'] == 'CCB_REAL_WINDOWS_GROUP_RETAINED_COMPONENT_V1' and value['stage'] == stage,
            'COMPONENT_REPORT_IDENTITY')
    require(value['sourceSha'] == (OLD if stage.startswith('lan5-') else NEW) and
            value['action'] == ('SEED' if stage == 'lan5-seed' else 'VERIFY'), 'COMPONENT_SOURCE_ACTION')
    false_keys = ('packagedMainLaunch', 'durablePauseClaimed', 'recoveryInputUsedByVerifier', 'networkDeliveryVerified')
    integer_keys = ('authorSequence', 'nativeSequence', 'nativeUnitCount', 'protectedFileCount', 'closeNoticeCount')
    digest_keys = ('groupIdentityDigest', 'protectedInventorySha256')
    for key in PUBLIC_KEYS - {'kind', 'action', 'stage', 'sourceSha'} - set(integer_keys) - set(digest_keys):
        require(type(value[key]) is bool and value[key] == (key not in false_keys), 'COMPONENT_BOOLEAN')
    require(all(type(value[key]) is int for key in integer_keys) and
            value['authorSequence'] == value['nativeSequence'] == 1 and value['nativeUnitCount'] > 0 and
            value['protectedFileCount'] > 0 and value['closeNoticeCount'] in (0, 1), 'COMPONENT_COUNTERS')
    require(all(type(value[key]) is str and re.fullmatch('[a-f0-9]{64}', value[key]) for key in digest_keys),
            'COMPONENT_DIGESTS')
    return value


def effective_environment(environment, root, private, source, stage, report):
    result = dict(environment)
    for name, leaf in (('LOCALAPPDATA', 'Local'), ('APPDATA', 'Roaming'), ('TEMP', 'Temp'), ('TMP', 'Temp')):
        path = root / 'UserData' / leaf
        require(path.is_dir(), 'LAUNCHER_USERDATA_ENVIRONMENT_MISSING')
        result[name] = str(path.resolve(strict=True))
    result.update(CCB_GROUP_RETAINED_CONTROL='true', CCB_GROUP_RETAINED_ROOT=str(root.resolve(strict=True)),
                  CCB_GROUP_RETAINED_PRIVATE=str(private.resolve(strict=True)), CCB_GROUP_RETAINED_SOURCE=source,
                  CCB_GROUP_RETAINED_ACTION='SEED' if stage == 'lan5-seed' else 'VERIFY',
                  CCB_GROUP_RETAINED_STAGE=stage, CCB_GROUP_RETAINED_REPORT=str(report))
    return result


def remove_owned_previous_xml(xml, retained):
    require(xml.is_file() and not xml.is_symlink() and digest(xml) == digest(retained), 'PREVIOUS_XML_CHANGED')
    require(xml.name == 'TEST-' + CLASS + '.xml' and xml.parent.parts[-4:] == ('desktopApp', 'build', 'test-results', 'test'),
            'PREVIOUS_XML_SCOPE')
    xml.unlink()  # One authenticated, retained prior report in our new source worktree only.


class Companion:
    def __init__(self, modules, sources, private, output):
        self.ui = modules['desktop_group_ui_control']
        self.ui.CLASS, self.ui.METHOD, self.ui.TARGET, self.ui.FIXTURE = CLASS, METHOD, TARGET, FIXTURE
        self.sources, self.private, self.output = sources, private, output
        self.templates, self.requests, self.previous_xml, self.reports = {}, {}, {}, []
        self.original_descriptors = []

    def prepare(self):
        for sha, source in self.sources.items():
            request = self.private / ('component-request-' + sha + '.json')
            write_new(request, {'format': 1, 'sourceSha': sha, 'fixtureSha256': FIXTURE_SHA})
            evidence = self.private / ('prepare-' + sha)
            self.ui.prepare(request, source, evidence)
            self.templates[sha] = (evidence / 'prepared.json').read_bytes()
            self.requests[sha] = request

    def run(self, root, stage):
        global _phase
        _phase = 'COMPONENT_' + stage.replace('-', '_').upper()
        sha = OLD if stage.startswith('lan5-') else NEW
        source = self.sources[sha]
        evidence = self.private / stage
        evidence.mkdir()
        (evidence / 'prepared.json').write_bytes(self.templates[sha])
        xml = source / ('app/desktopApp/build/test-results/test/TEST-' + CLASS + '.xml')
        if sha in self.previous_xml:
            remove_owned_previous_xml(xml, self.previous_xml[sha])
        report = self.private / ('report-' + stage + '.json')
        environment = effective_environment(dict(os.environ), root, self.private, sha, stage, report)
        # The isolated private runner instance retains all native command/XML/overlay guards.
        # Only its per-invocation environment selection uses the launcher's actual retained roots.
        prior = self.ui.isolated_environment
        try:
            self.ui.isolated_environment = lambda ignored: dict(environment)
            self.ui.run(self.requests[sha], source, evidence)
        finally:
            self.ui.isolated_environment = prior
        self.previous_xml[sha] = evidence / 'original-test.xml'
        raw = self.previous_xml[sha].read_bytes()
        parsed = self.ui.junit_result(raw)
        require(report.is_file() and not report.is_symlink() and 0 < report.stat().st_size < 65536,
                'SMALL_ORIGINAL_COMPONENT_JSON_REQUIRED')
        original_report = report.read_bytes()
        require(0 < len(original_report) < 65536, 'SMALL_ORIGINAL_COMPONENT_JSON_REQUIRED')
        value = component_report(json.loads(original_report.decode('utf8'), object_pairs_hook=pairs), stage)
        if self.reports:
            require(all(value[key] == self.reports[0][key] for key in
                        ('groupIdentityDigest', 'protectedInventorySha256', 'protectedFileCount', 'nativeUnitCount')),
                    'RETAINED_GROUP_DIGEST_CHANGED')
        destination = self.output / ('group-component-' + stage + '.json')
        with destination.open('xb') as exported:
            exported.write(original_report)  # Exact original fixture JSON, after strict public-only validation.
        require(digest(destination) == hashlib.sha256(original_report).hexdigest(), 'ORIGINAL_COMPONENT_COPY_CHANGED')
        self.original_descriptors.append({'stage': stage, 'sourceSha': sha, 'fixtureSha256': FIXTURE_SHA,
            'controlSha': json.loads(self.templates[sha])['controlSha'],
            'originalComponentJson': {'sha256': hashlib.sha256(original_report).hexdigest(), 'bytes': len(original_report)},
            'originalJUnit': {'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw), **parsed},
            'originalJUnitUploaded': False, 'scope': 'real AppContainer/DPAPI component; separate from packaged Main'})
        self.reports.append(value)
        return value


@contextmanager
def attach_native(modules, companion, legacy_path):
    """Small hooks around the original engine: never replace launch/package/data guards."""
    native = modules['prove_desktop_lan3_lan4']
    require(digest(legacy_path) == LEGACY_SHA, 'TRUSTED_NATIVE_ENGINE_CHANGED')
    old = native.legacy()
    seed, writer, Native = old.seed, old.write_json, old.Native
    main_stages = []
    verify_read_only_stages = []
    seed_image = None

    def seeded(data):
        nonlocal seed_image
        image = seed(data)  # Original seven synthetic entities + PNG; no Python group/protected writes.
        seed_image = image
        companion.run(data.parents[2], 'lan5-seed')
        return image

    class GroupNative(Native):
        def launch(self, stage, environment):
            global _phase
            _phase = 'PACKAGED_MAIN_' + stage.replace('-', '_').upper()
            result = super().launch(stage, environment)  # Real packaged Main / window / WM_CLOSE / drain / lock.
            main_stages.append(stage)
            if stage != 'lan5-fresh':
                # Initialization/close can recover an authorized WAL. It must not repair Main's
                # entities/media and then let the original post-component retention checks pass.
                _phase = 'VERIFY_OBSERVER_PRE_' + stage.replace('-', '_').upper()
                data = self.root / 'UserData/Local/ChatChatBarDesktop'
                require(seed_image is not None, 'ORIGINAL_SEED_IMAGE_REQUIRED')
                entities_before = old.inventory(data / 'entities')
                image_before = old.sha(seed_image)
                companion.run(self.root, stage)
                _phase = 'VERIFY_OBSERVER_POST_' + stage.replace('-', '_').upper()
                require(old.inventory(data / 'entities') == entities_before and old.sha(seed_image) == image_before,
                        'VERIFY_COMPONENT_CHANGED_ENTITIES_OR_SEED_PNG')
                verify_read_only_stages.append(stage)
            return result

    def result_writer(path, value):
        refused = False
        if path.name == RESULT:
            value = dict(value)
            complete = [r['stage'] for r in companion.reports] == list(COMPONENTS)
            complete = complete and main_stages == ['lan5-fresh', *COMPONENTS[1:]]
            complete = complete and verify_read_only_stages == list(COMPONENTS[1:])
            if value.get('passed') and not complete:
                value.update(passed=False, failure={'phase': 'EXACT_GROUP_PHASES', 'type': 'ValueError'})
                refused = True
            if 'failure' in value:
                value['failure'] = {'phase': _phase, 'type': value['failure'].get('type', 'Exception')}
            value.update(peerOrIdentitySeeded=bool(companion.reports and companion.reports[0]['passed']),
                         groupComponentCount=len(companion.reports), groupComponentStages=[r['stage'] for r in companion.reports],
                         packagedMainStagesCompleted=main_stages,
                         verifyComponentReadOnlyStages=list(verify_read_only_stages),
                         verifyComponentEntitiesAndSeedPngUnchanged=verify_read_only_stages == list(COMPONENTS[1:]),
                         groupScope='real old AppContainer/DPAPI seed; four retained-root component verifiers after original Main closes',
                         componentIsPackagedMain=False, durablePauseVerified=False, remoteDeliveryVerified=False)
        writer(path, value)
        require(not refused, 'EXACT_GROUP_PHASES')

    prior = {key: getattr(native, key) for key in ('legacy', 'RESULT_KIND', 'RESULT_FILE', 'SUCCESS_MARKER')}
    try:
        old.seed, old.Native, old.write_json = seeded, GroupNative, result_writer
        native.legacy = lambda: old
        native.RESULT_KIND = 'EXACT_LAN5_LAN6_PROGRAM_ONLY_REAL_GROUP_RETAINED_UPGRADE'
        native.RESULT_FILE = RESULT
        native.SUCCESS_MARKER = 'LAN5_LAN6_REAL_GROUP_RETAINED_NATIVE_UPGRADE_PASS'
        yield
    finally:
        old.seed, old.Native, old.write_json = seed, Native, writer
        for key, value in prior.items():
            setattr(native, key, value)


def command(*argv):
    result = subprocess.run(list(argv), capture_output=True, timeout=180)
    require(result.returncode == 0, 'FIXED_SOURCE_WORKTREE_COMMAND_FAILED')
    return result.stdout


def make_worktrees(root, private):
    sources = {}
    for sha, leaf in ((OLD, 'source-old-e606'), (NEW, 'source-new-a6')):
        source = private / leaf
        require(not source.exists(), 'NEW_SOURCE_WORKTREE_REQUIRED')
        command('git', '-C', str(root), 'worktree', 'add', '--detach', str(source), sha)
        require(command('git', '-C', str(source), 'rev-parse', 'HEAD').decode().strip() == sha, 'SOURCE_HEAD')
        sources[sha] = source
    return sources


def check_output(output, complete=False):
    require(output.is_dir() and not output.is_symlink(), 'ISOLATED_OUTPUT_REQUIRED')
    paths = list(output.iterdir())
    require(all(p.is_file() and not p.is_symlink() and p.name in OUTPUTS and 0 < p.stat().st_size < 65536 for p in paths),
            'SMALL_OUTPUT_ALLOWLIST')
    require(all(type(small_json(path)) is dict for path in paths), 'OUTPUT_JSON_OBJECT_REQUIRED')
    if complete:
        require({p.name for p in paths} == set(OUTPUTS), 'EXACT_SMALL_OUTPUT_COUNT')


def run(request_path, output, modules):
    global _phase
    base, profile = modules['desktop_control'], modules['desktop_lan6_control']
    root = Path.cwd().resolve(strict=True)
    base.cloud()
    require(os.environ.get('RUNNER_OS') == 'Windows', 'WINDOWS_CLOUD_ONLY')
    require(Path(os.environ['GITHUB_WORKSPACE']).resolve(strict=True) == root, 'CONTROL_WORKSPACE_REQUIRED')
    require(request_path.resolve(strict=True) == root / REQUEST and output.absolute() == root / EVIDENCE,
            'FIXED_REQUEST_OUTPUT_PATHS')
    require(command('git', '-C', str(root), 'status', '--porcelain', '--untracked-files=all') == b'', 'CLEAN_CONTROL_REQUIRED')
    require(command('git', '-C', str(root), 'rev-parse', 'HEAD').decode().strip() == os.environ['GITHUB_SHA'], 'CONTROL_HEAD_REQUIRED')
    require(digest(FIXTURE) == FIXTURE_SHA, 'COMPANION_FIXTURE_CHANGED')
    temporary = Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
    require(not temporary.is_relative_to(root) and not root.is_relative_to(temporary), 'SEPARATE_RUNNER_TEMP_REQUIRED')
    private = temporary / ('ccb-real-group-retained-' + uuid.uuid4().hex)
    private.mkdir()
    request = small_json(request_path)
    with profile.profile():
        validate_request(base, request)
        _phase = 'EXACT_ORIGINAL_PACKAGE_AUTHENTICATION'
        # Existing API/outer/inner/CRC/full-bundle/validateOriginal path, with original V5 baseline unchanged.
        base.fetch_originals(request, output)
        _phase = 'FIXED_COMPONENT_SOURCE_WORKTREES'
        sources = make_worktrees(root, private)
        companion = Companion(modules, sources, private, output)
        companion.prepare()
        legacy_path = HERE.parent / 'lan-preview-signing/prove_desktop_profile_upgrade.py'
        _phase = 'ORIGINAL_FIVE_NATIVE_LAUNCHES_WITH_REAL_GROUP'
        with attach_native(modules, companion, legacy_path):
            profile.native.run(request, output)
        require(small_json(output / RESULT)['passed'] is True and len(companion.reports) == 5, 'EXACT_REAL_GROUP_PROOF_REQUIRED')
        write_new(output / TOP[-1], {'kind': 'CCB_REAL_WINDOWS_GROUP_RETAINED_UPGRADE_VERIFIED', 'passed': True,
            'sourceOld': OLD, 'sourceCandidate': NEW, 'candidateBuildControl': BUILD_CONTROL,
            'packagedMainLaunches': 5, 'separateAppContainerComponents': 5, 'originalBaselineRebuilt': False,
            'candidateApplicationRebuilt': False, 'testClasspathBuiltSeparately': True,
            'userDataRootMoved': False, 'programOnlySwap': True, 'outsideDefaultGuardUnchanged': True,
            'durablePauseClaimed': False, 'cloudOrNearbyDeliveryClaimed': False, 'nsisOrHumanAcceptanceClaimed': False,
            'privateCaseCleanup': 'RETAINED_EPHEMERAL_CLOUD_RUNNER', 'privateOriginalXmlUploaded': False,
            'privateCustodyOracleProtectedTreeBundleCodeOrLogsUploaded': False,
            'rawControllerRequest': {'bytes': request_path.stat().st_size, 'sha256': digest(request_path)},
            'originalAppContainerReports': companion.original_descriptors,
            'outputs': list(OUTPUTS)})
        check_output(output, complete=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--request', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--audit-output', action='store_true')
    args = parser.parse_args(argv)
    try:
        if args.audit_output:
            require(args.output.absolute() == Path.cwd().resolve(strict=True) / EVIDENCE, 'FIXED_OUTPUT_PATH_REQUIRED')
            check_output(args.output)
            return 0
        with private_helpers() as modules:
            run(args.request, args.output, modules)
        return 0
    except Exception as failure:
        # Never print arbitrary filesystem/API/Gradle/DPAPI exception messages or causes.
        print('GROUP_RETAINED_UPGRADE_REFUSED:' + _phase + ':' + type(failure).__name__, file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
