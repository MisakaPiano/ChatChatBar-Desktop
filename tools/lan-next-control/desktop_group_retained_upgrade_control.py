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
import xml.etree.ElementTree as ET

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


# Bounded diagnostic-only projection. Never return private text, paths, causes or stacks.
SAFE_COMPONENT_PHASES = frozenset({
    'GUARD', 'PROTECTED_PRECONSTRUCT', 'OWNERSHIP', 'INITIALIZE', 'CREATE_REAL_GROUP',
    'SAVE_RECOVERY_DPAPI_BEFORE_ACK', 'WAIT_ORDINARY_INITIAL_PUBLICATION',
    'ADD_DISABLED_REAL_PROTECTED_ROUTE', 'OPEN_EXISTING_WHILE_PAUSED',
    'READ_REAL_DAILY_OWNER_NATIVE_AND_ROUTE',
    'CLOSE_REAL_APP_AND_VERIFY_OBSERVER_DID_NOT_REWRITE', 'SAFE_REPORT',
})
SAFE_CONTROL_CODES = frozenset({
    'CLOUD_WINDOWS_ONLY', 'DESKTOP_FIXTURE_SYMLINK', 'DUPLICATE_REQUEST_KEY',
    'REQUEST_FIELDS', 'REQUEST_FORMAT', 'SOURCE_SHA_INVALID', 'FIXTURE_SHA_INVALID',
    'FIXTURE_SHA_MISMATCH', 'PREPARATION_CHANGED', 'SOURCE_HEAD_CHANGED',
    'DESKTOP_OVERLAY_NOT_EXCLUSIVE', 'DESKTOP_OVERLAY_CHANGED', 'DESKTOP_OVERLAY_SYMLINK',
    'DESKTOP_OVERLAY_ESCAPES_SOURCE', 'PREEXISTING_NATIVE_XML_REFUSED',
    'WINDOWS_GROUP_SCENE_TIMEOUT', 'WINDOWS_GROUP_SCENE_XML_MISSING',
    'WINDOWS_GROUP_SCENE_GRADLE_FAILED', 'JUNIT_FORMAT', 'JUNIT_CLASS',
    'JUNIT_NOT_ONE_EXECUTED_PASS', 'JUNIT_METHOD', 'JUNIT_CASE_FAILED',
    'JUNIT_UNEXPECTED_ELEMENT', 'JUNIT_TIME',
})
SAFE_PYTHON_FAILURE_TYPES = frozenset({
    'ControlError', 'AssertionError', 'ValueError', 'TypeError', 'KeyError', 'RuntimeError',
    'FileNotFoundError', 'FileExistsError', 'PermissionError', 'OSError',
    'TimeoutError', 'TimeoutExpired', 'CalledProcessError', 'ParseError',
    'JSONDecodeError', 'UnicodeDecodeError',
})
SAFE_MARKER = re.compile(r'GROUP_RETAINED_COMPONENT:([A-Z_]{1,80}):([A-Za-z][A-Za-z0-9_]{0,63})')
FIXTURE_BASENAME = 'DesktopGroupRetainedUpgradeControlTest.kt'


def safe_failure_type(failure):
    name = type(failure).__name__
    return name if name in SAFE_PYTHON_FAILURE_TYPES else 'Exception'


def safe_control_code(failure, control_error_type):
    args = failure.args
    if type(failure) is control_error_type and len(args) == 1 and type(args[0]) is str and args[0] in SAFE_CONTROL_CODES:
        return args[0]
    return 'UNCLASSIFIED'


def safe_fixture_marker(text):
    if type(text) is not str:
        return None
    match = SAFE_MARKER.fullmatch(text)
    if match is None or match[1] not in SAFE_COMPONENT_PHASES:
        return None
    return {'phase': match[1], 'exceptionType': match[2]}


def diagnostic_private_bytes(path, evidence, limit):
    # Only an existing regular direct child of this invocation's private evidence directory.
    if path.parent != evidence or evidence.is_symlink() or path.is_symlink() or not path.is_file():
        return None
    if not 0 < path.stat().st_size <= limit:
        return None
    with path.open('rb') as stream:
        raw = stream.read(limit + 1)
    return raw if 0 < len(raw) <= limit else None


def junit_failure_diagnostic(evidence):
    value = {'present': False, 'parsed': False, 'exactSingleExpectedCase': False,
             'countScope': 'DECLARED_ROOT_COUNTS_ONLY_NOT_VALIDATED_PASS',
             'tests': None, 'passed': None, 'failed': None, 'errors': None, 'skipped': None,
             'testCaseCount': None, 'wholeTreeCaseCount': None,
             'fixtureFailureMarker': None, 'fixtureFailureMarkerPresent': False,
             'fixtureMarkerInSystemOut': False, 'status': 'MISSING_OR_UNSAFE'}
    path = evidence / 'original-test.xml'
    value['present'] = path.exists()
    raw = diagnostic_private_bytes(path, evidence, 1024 * 1024)
    if raw is None:
        return value
    if b'<!DOCTYPE' in raw or b'<!ENTITY' in raw:
        value['status'] = 'XML_DECLARATION_REFUSED'
        return value
    try:
        root = ET.fromstring(raw)
    except ET.ParseError:
        value['status'] = 'XML_PARSE_FAILED'
        return value
    if root.tag != 'testsuite':
        value['status'] = 'XML_WRONG_ROOT'
        return value
    value['parsed'] = True
    def count(name):
        text = root.get(name)
        return int(text) if type(text) is str and re.fullmatch(r'[0-9]{1,4}', text) else None
    value.update(tests=count('tests'), failed=count('failures'), errors=count('errors'), skipped=count('skipped'))
    counts = [value[key] for key in ('tests', 'failed', 'errors', 'skipped')]
    if all(item is not None for item in counts) and sum(counts[1:]) <= counts[0]:
        value['passed'] = counts[0] - sum(counts[1:])
    direct = root.findall('testcase')
    whole = list(root.iter('testcase'))
    value['testCaseCount'], value['wholeTreeCaseCount'] = len(direct), len(whole)
    exact = (root.get('name') == CLASS and len(direct) == len(whole) == 1 and
             direct[0].get('classname') == CLASS and direct[0].get('name') == METHOD)
    value['exactSingleExpectedCase'] = exact
    value['status'] = 'COUNTS_ONLY_WRONG_CASE' if not exact else 'EXPECTED_CASE_DIAGNOSTIC_ONLY'
    if exact:
        failures = [item for item in whole[0] if item.tag in ('failure', 'error')]
        markers = [safe_fixture_marker(item.get('message')) for item in failures]
        markers = [item for item in markers if item is not None]
        if len(failures) == len(markers) == 1:
            value['fixtureFailureMarker'] = markers[0]
            value['fixtureFailureMarkerPresent'] = True
        for item in root.iter('system-out'):
            if any(safe_fixture_marker(line) is not None for line in (item.text or '').splitlines()):
                value['fixtureMarkerInSystemOut'] = True
    return value


def compiler_diagnostic(evidence, source):
    raw = diagnostic_private_bytes(evidence / 'gradle-original.log', evidence, 16 * 1024 * 1024)
    value = {'status': 'MISSING_OR_UNSAFE_OR_TOO_LARGE', 'fixtureLocations': []}
    if raw is None:
        return value
    value['status'] = 'NO_RECOGNIZED_FIXTURE_COMPILER_LOCATION'
    fixture = str(source / TARGET)
    slash = fixture.replace('\\', '/')
    locations = sorted({fixture, slash, 'file:///' + slash.lstrip('/')})
    location_pattern = re.compile(
        r'^e: (?:' + '|'.join(re.escape(item) for item in locations) +
        r'):(?:(?P<line>[0-9]{1,6}):(?P<column>[0-9]{1,6})(?::|\s)|'
        r'\s*\((?P<old_line>[0-9]{1,6}),\s*(?P<old_column>[0-9]{1,6})\):)\s*(?P<message>[^\r\n]*)$')
    seen = set()
    for text in raw.decode('utf-8', errors='replace').splitlines():
        if len(text) > 8192:
            continue
        match = location_pattern.fullmatch(text)
        if match is None:
            continue
        line = int(match['line'] or match['old_line'])
        column = int(match['column'] or match['old_column'])
        if not 0 < line <= 999999 or not 0 < column <= 999999:
            continue
        # The message is inspected locally only. Only this finite classification leaves the runner.
        message = match['message']
        if message.startswith('Unresolved reference'):
            category = 'UNRESOLVED_REFERENCE'
        elif message.startswith(('Cannot access', 'Cannot access class')):
            category = 'INACCESSIBLE_DECLARATION'
        elif message.startswith(('Type mismatch', 'Argument type mismatch', 'Return type mismatch')):
            category = 'TYPE_MISMATCH'
        elif message.startswith(('Overload resolution ambiguity', 'None of the following candidates')):
            category = 'OVERLOAD_RESOLUTION'
        elif message.startswith(('No value passed for parameter', 'Too many arguments')):
            category = 'CALL_ARGUMENTS'
        elif message.startswith(('Syntax error', 'Expecting ', 'Unexpected tokens')):
            category = 'SYNTAX'
        else:
            category = 'OTHER_COMPILER_ERROR'
        key = (line, column, category)
        if key not in seen:
            value['fixtureLocations'].append({'file': FIXTURE_BASENAME, 'line': line,
                                               'column': column, 'classification': category})
            seen.add(key)
        if len(seen) == 16:
            break
    if seen:
        value['status'] = 'RECOGNIZED_FIXTURE_LOCATIONS_ONLY'
    return value


def component_failure_diagnostic(failure, evidence, source, stage, control_error_type):
    # Calling this observer can never complete a component or change the original exception.
    phase = 'COMPONENT_' + stage.replace('-', '_').upper() if stage in COMPONENTS else 'COMPONENT_UNKNOWN'
    value = {'kind': 'CCB_REAL_GROUP_COMPONENT_SAFE_FAILURE_V1', 'phase': phase,
             'type': safe_failure_type(failure), 'controlCode': safe_control_code(failure, control_error_type),
             'diagnosticOnly': True, 'nativeContinuationAllowed': False, 'collectorStatus': 'OK'}
    try:
        value['junit'] = junit_failure_diagnostic(evidence)
        value['compiler'] = compiler_diagnostic(evidence, source)
    except Exception:
        value['collectorStatus'] = 'UNAVAILABLE'
        value.pop('junit', None)
        value.pop('compiler', None)
    return value


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
        self.failure_diagnostic = None

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
        self.failure_diagnostic = None
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
        except Exception as failure:
            self.failure_diagnostic = component_failure_diagnostic(
                failure, evidence, source, stage, self.ui.ControlError)
            try:
                print("GROUP_RETAINED_COMPONENT_DIAGNOSTIC:" + json.dumps(
                    self.failure_diagnostic, sort_keys=True, separators=(",", ":")), file=sys.stderr)
            except OSError:
                pass
            raise  # Original failure remains fatal; no component completion or fallback.
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
                if getattr(companion, 'failure_diagnostic', None) is not None:
                    value['componentFailureDiagnostic'] = companion.failure_diagnostic
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
