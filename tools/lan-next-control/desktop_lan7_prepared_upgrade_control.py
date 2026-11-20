"""Cloud-only exact delivered LAN6 -> LAN7: original Main engine + separate DPAPI actions.

No packaged UI click is claimed. All downloads, Program swapping, ordinary launcher,
WM_CLOSE, process/root-lock checks and retained entity rules stay in pinned owners.
"""
from contextlib import contextmanager
import argparse
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
OLD = '5c906e6d4a0f31a114326e35a331c4af4dfa6379'
NEW = '8b4e88553243a910bc5682da48e72cd8e25220c3'
BUILD_CONTROL = '15394b991348b3a63f0babd1b33cb38fe6856587'
EXPECTED_CANDIDATE = {'series': 'lan7', 'version': '0.1.0-lan.7', 'sourceSha': '8b4e88553243a910bc5682da48e72cd8e25220c3', 'controlSha': '15394b991348b3a63f0babd1b33cb38fe6856587', 'runId': 44650796570, 'artifactId': 14590848815, 'artifactName': 'ccb-lan6-lan7-independent-desktop', 'outerBytes': 210772708, 'outerSha256': 'a985e6062dd3fb0312a08dd067f08550cf6aaafd9a2a846080c31e58c9761d53', 'filename': 'CCB-0.1.0-lan.7-independent-windows-x64.zip', 'innerBytes': 210657520, 'innerSha256': 'c9c9ed6104c2de0e481aaf87a9afe85677c9f4c3e631380567a8bb06335c642e', 'installerVersion': '0.1.40007', 'buildNumber': '40007'}
BRIDGE_SHA = '896abd01777e01084fe83d363c288eab3fa4d5135016ebec8b722452d9f293f1'
PROFILE_SHA = 'c1f9a5a0fbca1ed0dfb11571b2abe51cbc23fa60403699234840ec3be4020209'
FIXTURE = HERE / 'fixtures/DesktopGroupPreparedUpgradeControlTest.kt'
FIXTURE_SHA = '3f6d6281ba5cf1de45d2579255ebdb4844464dd64cb415c7f18366ab76f5eccd'
CLASS = 'com.example.chatbar.desktop.lanSync.DesktopGroupPreparedUpgradeControlTest'
METHODS = {
    'SEED': 'seedOrResumeTheSamePreparedDesktopGroupWithRealDpapi',
    'RESUME': 'seedOrResumeTheSamePreparedDesktopGroupWithRealDpapi',
    'COLD_INVITE': 'coldReadyUsesOriginalDpapiCodeAndIssuesInvitation',
    'ARCHIVE': 'archiveUnusedSetupThenVerifyColdArchive',
    'ARCHIVE_COLD': 'archiveUnusedSetupThenVerifyColdArchive',
}
ACTIONS = tuple(METHODS)
TARGET = 'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/lanSync/DesktopGroupPreparedUpgradeControlTest.kt'
REQUEST = '.github/lan-next-desktop-lan7-prepared-upgrade-request.json'
EVIDENCE = 'desktop-lan7-prepared-upgrade-evidence'
RESULT = 'native-lan6-lan7-prepared-upgrade-result.json'
OUTPUTS = ('source-request.json', 'original-provenance.json', 'original-input-bytes.json', RESULT,
           'prepared-upgrade-validation.json') + tuple('component-' + a.lower() + '.json' for a in ACTIONS)
MAIN_STAGES = ('lan6-fresh', 'lan6-populated', 'lan6-warm-control', 'lan7-upgraded', 'lan7-cold-reopen')
MAIN_PATH = 'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/Main.kt'
MAIN_SHA = 'cadabb582ffd45d3e393ffa1cc7cd5013ce12bb758cc29f8a90461efcda2e75a'
TIMESTAMP_HASHES = ('b30bb8fe2e2c06482cc3b2db6aba3f51a6f51b322473f5263c3c1de34ad0a0a0',
                    '52e82ebbfe6a4ad5647a05b210cde71856cc679bd5ea1113015c2f076bfc13d3',
                    'd015dc6f32b552bc85de11b3698ee61bd6917acf2d7e55f8691d1185adf55388')
INIT = "allprojects { tasks.withType(org.gradle.api.tasks.testing.Test).configureEach { minHeapSize = '64m'; maxHeapSize = '256m' } }\n"
SAFE_PHASES = frozenset({'GUARD','READ_BASE_ORACLE','OWNERSHIP','VERIFY_RETAINED_BEFORE_CONSTRUCTION','INITIALIZE',
 'SEED_SYNTHETIC_ENTITIES','CREATE_ON_BASE','SAVE_CODE_WITH_DPAPI_BEFORE_CONFIRMATION','ACTUAL_BASE_METADATA_REFUSAL',
 'OPEN_PREPARED_ON_CANDIDATE','UNLOCK_SAME_DPAPI_CODE','COMPLETE_SAME_SETUP','VERIFY_REAL_LOCAL_SOURCE_AND_PUBLISH_OFFLINE',
 'CLOSE_REAL_APP','POST_CLOSE_EVIDENCE','COLD_OPEN_READY','RESUME_READY_FOR_INVITATION','UNLOCK_READY_WITH_ORIGINAL_DPAPI_CODE',
 'ISSUE_REAL_COORDINATOR_INVITATION','CREATE_UNUSED_SYNTHETIC_SETUP','EXPLICIT_ARCHIVE_UNUSED_SETUP','READ_ARCHIVE_DPAPI_ORACLE',
 'COLD_OPEN_COMPLETED_ARCHIVE','CAPTURE_AUTHENTICATED_COLD_BASELINE','CLOSE_REAL_APP_CALL','VERIFY_CLOSE_NOTICE'})
SAFE_JVM_TYPES = frozenset({'AssertionError','IllegalStateException','IllegalArgumentException','IOException','SecurityException','CancellationException','TimeoutCancellationException','OutOfMemoryError','DesktopLanSyncFinalFlushIncompleteException','DesktopLanSyncFinalFlushTimeoutException','UNKNOWN'})
_phase = 'GUARD'

def require(value, code):
    if not value: raise ValueError(code)

def digest(path):
    require(path.is_file() and not path.is_symlink(), 'REGULAR_PIN_REQUIRED')
    return hashlib.sha256(path.read_bytes()).hexdigest()

SAFE_TYPES = frozenset({'ControlError','AssertionError','ValueError','TypeError','KeyError','RuntimeError','FileNotFoundError','FileExistsError','PermissionError','OSError','TimeoutError','TimeoutExpired','CalledProcessError','ParseError','JSONDecodeError','UnicodeDecodeError'})
def safe_type(failure):
    value=type(failure).__name__
    return value if value in SAFE_TYPES else 'UNKNOWN_EXCEPTION_TYPE'

def pairs(items):
    out = {}
    for key, value in items:
        require(key not in out, 'DUPLICATE_JSON_KEY'); out[key] = value
    return out

MAX_NATIVE_RESULT_BYTES = 256 * 1024

def small(path):
    maximum = MAX_NATIVE_RESULT_BYTES if path.name == RESULT else 65536
    require(path.is_file() and not path.is_symlink() and 0 < path.stat().st_size < maximum, 'SMALL_JSON_REQUIRED')
    def reject_constant(ignored): raise ValueError('NONFINITE_JSON')
    return json.loads(path.read_bytes(), object_pairs_hook=pairs, parse_constant=reject_constant)

def write_new(path, value):
    with path.open('x', encoding='utf8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2); stream.write('\n')

def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module

@contextmanager
def borrowed():
    bridge_path = HERE / 'desktop_group_retained_upgrade_control.py'
    require(digest(bridge_path) == BRIDGE_SHA, 'PINNED_BRIDGE_CHANGED')
    require(digest(HERE / 'desktop_lan7_control.py') == PROFILE_SHA, 'LAN7_PROFILE_CHANGED')
    bridge = load_module('private_lan7_bridge', bridge_path)
    with bridge.private_helpers(HERE) as modules:
        profile = load_module('private_lan7_profile', HERE / 'desktop_lan7_control.py')
        # These are private module globals; shared defaults and files are never changed.
        changes = {'NEW': NEW, 'NOTICE_CLOSE_SOURCE': NEW, 'NOTICE_CLOSE_STAGES': frozenset(MAIN_STAGES[-2:]),
                   'NOTICE_CLOSE_POLICY': 'ONE_EXACT_8B4E_INFORMATIONAL_NOTICE_WINDOW_CLOSE_AFTER_ORIGINAL_MAIN_WM_CLOSE',
                   'CLASS': CLASS, 'TARGET': TARGET, 'FIXTURE_BASENAME': FIXTURE.name}
        previous = {k: getattr(bridge, k) for k in changes}
        try:
            for k, v in changes.items(): setattr(bridge, k, v)
            yield bridge, modules, profile
        finally:
            for k, v in previous.items(): setattr(bridge, k, v)

def validate_request(control, request):
    control.validate_upgrade(request)
    require(type(request['format']) is int and request['packages'][0]['sourceSha'] == OLD and
            request['packages'][1]['sourceSha'] == NEW and request['packages'][1] == EXPECTED_CANDIDATE,
            'EXACT_LAN6_8B4E_BUILD_REQUIRED')
    expected = {'baseSourceSha': OLD, 'candidateSourceSha': NEW, 'files': [
        {'path': path, 'baseSha256': value, 'candidateSha256': value}
        for path, value in zip(control.TIMESTAMP_PATHS, TIMESTAMP_HASHES)]}
    require(request['timestampSourceBindings'] == expected and control.timestamp_exception_enabled(request),
            'THREE_FULL_TIMESTAMP_BLOBS_REQUIRED')
    return request

def environment_for(root, private, source, action, report):
    require(action in ACTIONS, 'FIXED_COMPONENT_ACTION')
    value = os.environ.copy()
    for key, leaf in (('LOCALAPPDATA','Local'),('APPDATA','Roaming'),('TEMP','Temp'),('TMP','Temp')):
        path = root / 'UserData' / leaf
        require(path.is_dir() and not path.is_symlink(), 'RETAINED_USERDATA_REQUIRED')
        value[key] = str(path.resolve(strict=True))
    value.update(CCB_V6_PREPARED_COMPONENT='true', CCB_V6_PREPARED_SOURCE=source,
        CCB_V6_PREPARED_ACTION=action, CCB_V6_PREPARED_ROOT=str(root.resolve(strict=True)),
        CCB_V6_PREPARED_PRIVATE=str(private.resolve(strict=True)), CCB_V6_PREPARED_REPORT=str(report))
    return value

def validate_close_fields(value, action):
    require(value['closeReturned'] is True and value['closeNoticeKnown'] is True, 'ACTUAL_SAFE_CLOSE_RETURN_REQUIRED')
    count=value['closeNoticeCount']
    require(type(count) is int and count in ((0,1) if action == 'COLD_INVITE' else (0,)), 'EXPECTED_CLOSE_NOTICE_COUNT')
    expected='RETURNED_WITHOUT_NOTICE' if count==0 else 'RETURNED_WITH_EXPECTED_PENDING_SYNC_NOTICE'
    require(value['closeOutcome']==expected, 'EXPECTED_CLOSE_OUTCOME')

def report(value, action):
    common = {'kind','sourceSha','action','passed','realDpapi','realAppContainer','packagedMainExercised','networkDeliveryExercised','closeReturned','closeNoticeKnown','closeNoticeCount','closeOutcome'}
    require(type(value) is dict and value.get('action') == action and value.get('sourceSha') == (OLD if action == 'SEED' else NEW), 'COMPONENT_IDENTITY')
    validate_close_fields(value,action)
    if action in ('SEED','RESUME'):
        keys = common | {'baseMetadataWorkingBudgetObserved','samePreparedSetupResumed','heapBytes','recordWindow',
            'rawFixtureBytes','aggregateQuotedCostLowerBound','rawFixtureSha256','retainedProfileSha256'}
        if action == 'RESUME': keys.add('localReceiptSha256')
        require(set(value) == keys and value['kind'] == 'CCB_V6_PREPARED_DPAPI_COMPONENT_V1', 'COMPONENT_FIELDS')
        for key in ('heapBytes','recordWindow','rawFixtureBytes','aggregateQuotedCostLowerBound'):
            require(type(value[key]) is int, 'COMPONENT_INTEGER')
        require(240*1024**2 <= value['heapBytes'] <= 256*1024**2 and value['recordWindow'] == value['heapBytes']//4 and
                value['rawFixtureBytes'] >= 24*1024**2 and value['aggregateQuotedCostLowerBound'] == 96*16384*6*8 and
                value['aggregateQuotedCostLowerBound'] > value['recordWindow'], 'ACTUAL_OLD_WINDOW_SCALE_REQUIRED')
        for key in ('baseMetadataWorkingBudgetObserved','samePreparedSetupResumed'):
            require(type(value[key]) is bool and value[key] == (action == ('SEED' if key.startswith('base') else 'RESUME')), 'COMPONENT_MODE')
        hashes = ('rawFixtureSha256','retainedProfileSha256') + (('localReceiptSha256',) if action == 'RESUME' else ())
    else:
        require(set(value) == common | {'visibleUiExercised','details'} and value['kind'] == 'CCB_LAN6_LAN7_ADDITIONAL_DPAPI_COMPONENT_V1', 'COMPONENT_FIELDS')
        require(value['visibleUiExercised'] is False, 'NO_PACKAGED_UI_CLAIM')
        details = value['details']; require(type(details) is dict, 'COMPONENT_DETAILS')
        if action == 'COLD_INVITE':
            keys = {'coldReady','sameGroupAndDailyIdentity','originalDpapiRecoveryCodeUsed','coordinatorInvitationIssued',
                    'managementLockedAfterward','immutableProtectedOriginsRetained','originalSyntheticEntitiesUnchanged','originalAuthenticatedDeliveryObjectsRetained','profileSha256'}
            require(set(details) == keys and all(type(details[k]) is bool and details[k] for k in keys-{'profileSha256'}), 'COLD_INVITE_DETAILS')
            require(re.fullmatch('[a-f0-9]{64}', details['profileSha256']) is not None, 'PROFILE_DIGEST')
        else:
            require(set(details) == {'unusedSetupOnly','explicitArchiveAction','completedArchiveRetained','liveEntitiesUnchanged','coldEmptyStateVerified','archiveSha256'}, 'ARCHIVE_DETAILS')
            for key in ('unusedSetupOnly','completedArchiveRetained','liveEntitiesUnchanged'):
                require(type(details[key]) is bool and details[key], 'ARCHIVE_RETENTION_REQUIRED')
            require(type(details['explicitArchiveAction']) is bool and details['explicitArchiveAction'] == (action == 'ARCHIVE') and
                    type(details['coldEmptyStateVerified']) is bool and details['coldEmptyStateVerified'] == (action == 'ARCHIVE_COLD'), 'ARCHIVE_MODE')
            require(re.fullmatch('[a-f0-9]{64}', details['archiveSha256']) is not None, 'ARCHIVE_DIGEST')
        hashes = ()
    for key in hashes: require(type(value[key]) is str and re.fullmatch('[a-f0-9]{64}',value[key]) is not None, 'COMPONENT_DIGEST')
    for key in ('passed','realDpapi','realAppContainer'): require(type(value[key]) is bool and value[key], 'COMPONENT_BOOLEAN')
    require(value['packagedMainExercised'] is False and value['networkDeliveryExercised'] is False, 'NO_EXTRA_SCOPE_CLAIM')
    return value

def _failure_projection(evidence, ui, bridge, source):
    value = {'status':'NOT_OBSERVED','counts':None,'safePhase':None,'safePrimaryPhase':None}
    path = evidence / 'original-test.xml'
    if path.is_file() and not path.is_symlink() and path.stat().st_size <= 1024**2:
        raw = path.read_bytes()
        if b'<!DOCTYPE' not in raw and b'<!ENTITY' not in raw:
            root = ET.fromstring(raw)
            cases = list(root.iter('testcase'))
            if root.tag == 'testsuite' and root.get('name') == CLASS and len(cases) == 1:
                attrs = {k:root.get(k) for k in ('tests','failures','errors','skipped')}
                if not all(type(v) is str and re.fullmatch('[01]',v) for v in attrs.values()): return value
                value.update(status='ONE_ORIGINAL_CASE_OBSERVED', counts=attrs)
                bad = list(root.iter('failure')) + list(root.iter('error'))
                if len(bad) == 1 and bad[0].get('type') in ('java.lang.AssertionError','AssertionError'):
                    message = bad[0].get('message','')
                    if message.startswith('java.lang.AssertionError: '): message = message[len('java.lang.AssertionError: '):]
                    match = re.fullmatch(r'CCB_V6_PREPARED_COMPONENT:([A-Z_]+):([A-Z_]+)(?::([A-Za-z]+))?',message)
                    if match and match[1] in SAFE_PHASES and match[2] in SAFE_PHASES and (match[3] is None or match[3] in SAFE_JVM_TYPES):
                        value.update(safePhase=match[1], safePrimaryPhase=match[2], safeCaughtType=match[3])
    try: value['compiler'] = bridge.compiler_diagnostic(evidence, source)
    except Exception: value['compiler'] = {'status':'UNAVAILABLE','fixtureLocations':[]}
    return value

def failure_projection(evidence, ui, bridge, source):
    try:
        return _failure_projection(evidence, ui, bridge, source)
    except Exception:
        return {'status':'UNAVAILABLE','counts':None,'safePhase':None,'safePrimaryPhase':None}

class Companion:
    def __init__(self, bridge, modules, sources, private, output):
        self.bridge, self.ui, self.sources, self.private, self.output = bridge, modules['desktop_group_ui_control'], sources, private, output
        self.ui.CLASS, self.ui.METHOD, self.ui.TARGET, self.ui.FIXTURE = CLASS, METHODS['SEED'], TARGET, FIXTURE
        self.requests, self.templates, self.previous_xml, self.reports, self.originals = {}, {}, {}, [], []
        self.failure = None
        self.init = private / 'test-heap.init.gradle'; self.init.write_text(INIT, encoding='utf8', newline='\n')
    def prepare(self):
        for sha, source in self.sources.items():
            request = self.private / ('component-request-'+sha+'.json')
            write_new(request, {'format':1,'sourceSha':sha,'fixtureSha256':FIXTURE_SHA})
            evidence = self.private / ('prepare-'+sha)
            self.ui.prepare(request,source,evidence)
            self.requests[sha], self.templates[sha] = request, (evidence/'prepared.json').read_bytes()
    def run(self, root, action):
        global _phase
        if action == 'SEED': self.retained_root = root.resolve(strict=True)
        _phase = 'COMPONENT_'+action
        require(action in ACTIONS and action not in [v['action'] for v in self.reports], 'EXACT_ONE_COMPONENT_INVOCATION')
        sha = OLD if action == 'SEED' else NEW; source = self.sources[sha]
        evidence = self.private / ('invoke-'+action.lower()); evidence.mkdir()
        (evidence/'prepared.json').write_bytes(self.templates[sha])
        xml = source / ('app/desktopApp/build/test-results/test/TEST-'+CLASS+'.xml')
        if sha in self.previous_xml: self.bridge.remove_owned_previous_xml(xml,self.previous_xml[sha])
        target = self.private / ('report-'+action.lower()+'.json')
        env = environment_for(root,self.private,sha,action,target)
        prior_env, prior_cmd, prior_method = self.ui.isolated_environment, self.ui.native_command, self.ui.METHOD
        def command(checked_source):
            argv = self.bridge.component_test_rerun_command(prior_cmd(checked_source))
            require(argv.count('--tests') == 1 and argv[argv.index('--tests')+1] == CLASS, 'EXACT_TEST_FILTER')
            argv[argv.index('--tests')+1] = CLASS+'.'+METHODS[action]
            return argv + ['--init-script',str(self.init)]
        try:
            require(self.init.read_text('utf8') == INIT, 'TEST_HEAP_INIT_CHANGED')
            self.ui.METHOD = METHODS[action]
            self.ui.isolated_environment = lambda ignored: dict(env)
            self.ui.native_command = command
            self.ui.run(self.requests[sha],source,evidence)
        except Exception:
            self.failure = failure_projection(evidence,self.ui,self.bridge,source)
            raise
        finally:
            self.ui.isolated_environment, self.ui.native_command, self.ui.METHOD = prior_env, prior_cmd, prior_method
        raw = (evidence/'original-test.xml').read_bytes()
        self.ui.METHOD = METHODS[action]
        try: parsed = self.ui.junit_result(raw)
        finally: self.ui.METHOD = prior_method
        original_report = target.read_bytes(); require(len(original_report) < 65536, 'SMALL_COMPONENT_JSON')
        value = report(small(target),action)
        if action == 'RESUME':
            require(value['retainedProfileSha256'] == self.reports[0]['retainedProfileSha256'] and value['rawFixtureSha256'] == self.reports[0]['rawFixtureSha256'], 'ORIGINAL_PREPARED_IDENTITY_OR_RAW_CHANGED')
        if action == 'COLD_INVITE': require(value['details']['profileSha256'] == self.reports[0]['retainedProfileSha256'], 'COLD_IDENTITY_CHANGED')
        if action == 'ARCHIVE_COLD': require(value['details']['archiveSha256'] == self.reports[-1]['details']['archiveSha256'], 'COLD_ARCHIVE_CHANGED')
        destination = self.output/('component-'+action.lower()+'.json')
        with destination.open('xb') as out: out.write(original_report)
        self.previous_xml[sha] = evidence/'original-test.xml'
        self.originals.append({'action':action,'sourceSha':sha,'fixtureSha256':FIXTURE_SHA,
            'originalReport':{'bytes':len(original_report),'sha256':hashlib.sha256(original_report).hexdigest()},
            'originalJUnit':{'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest(),**parsed},'originalJUnitUploaded':False})
        self.reports.append(value)
        return value

@contextmanager
def attach(modules, bridge, companion):
    native = modules['prove_desktop_lan3_lan4']; legacy_path = HERE.parent/'lan-preview-signing/prove_desktop_profile_upgrade.py'
    require(digest(legacy_path) == bridge.LEGACY_SHA, 'TRUSTED_NATIVE_ENGINE_CHANGED')
    old = native.legacy(); seed, Native, writer = old.seed, old.Native, old.write_json
    prior_legacy, prior_result = native.legacy, native.RESULT_FILE
    main_stages, observations = [], []
    def seeded(data):
        image = seed(data)
        companion.run(data.parents[2],'SEED')
        return image
    class PreparedNative(Native):
        def owned(self):
            value = super().owned()
            observation = getattr(self,'_observation',None)
            if observation is not None and type(value) is list and all(type(pid) is int for pid in value):
                bridge.c30_notice_close_after_original_owned(self,old,tuple(value),observation)
            return value
        def launch(self, stage, environment):
            global _phase
            if stage == 'lan7-upgraded': companion.run(self.root,'RESUME')
            _phase = 'PACKAGED_MAIN_'+stage.replace('-','_').upper()
            observation = {'stage':stage,'originalWmCloseQueued':None,'normalNoticeClose':bridge.notice_close_state(stage)}
            prior_user = self.user
            try:
                self._observation = observation
                self.user = bridge.OriginalCloseUser32(prior_user,observation)
                value = super().launch(stage,environment)
                observation['normalNoticeClose']['originalExitAndLockGuardPassed'] = True
                main_stages.append(stage)
                return value
            except BaseException:
                observation['normalNoticeClose']['originalExitAndLockGuardPassed'] = False
                raise
            finally:
                self.user = prior_user; self._observation = None
                observations.append(observation)
    def result_writer(path, value):
        if path.name == RESULT:
            value = dict(value)
            if 'failure' in value:
                value['failure'] = {'phase':_phase,'type':value['failure'].get('type') if value['failure'].get('type') in SAFE_TYPES else 'UNKNOWN_EXCEPTION_TYPE'}
            value.update(mainMatrixPassed=value.get('passed') is True, passed=False, proofComplete=False, packagedMainStagesCompleted=list(main_stages), separateComponentActionsCompleted=[v['action'] for v in companion.reports],
                componentIsPackagedUi=False, normalCloseObservations=list(observations), original60SecondCloseGuardUnchanged=True,
                informationalNoticeNotFullSyncDelivery=True)
            if companion.failure is not None: value['componentFailureDiagnostic'] = companion.failure
        writer(path,value)
    try:
        old.seed,old.Native,old.write_json = seeded,PreparedNative,result_writer
        native.legacy, native.RESULT_FILE = lambda:old, RESULT
        yield main_stages
    finally:
        old.seed,old.Native,old.write_json = seed,Native,writer
        native.legacy,native.RESULT_FILE = prior_legacy,prior_result

def isolated_archive_pair(root, private, temporary):
    require(root.parent == temporary and private.parent == temporary and root != private and
            not root.is_relative_to(private) and not private.is_relative_to(root), 'ARCHIVE_ROOT_PRIVATE_MUST_BE_SIBLINGS')

def make_archive_root(temporary, private):
    temporary=temporary.resolve(strict=True);private=private.resolve(strict=True)
    root=temporary/('ccb-lan7-archive-'+uuid.uuid4().hex)
    isolated_archive_pair(root,private,temporary)
    root.mkdir(exist_ok=False)
    for leaf in ('Local/ChatChatBarDesktop','Roaming','Temp'): (root/'UserData'/leaf).mkdir(parents=True,exist_ok=True)
    return root

def audit_output(output, complete=False):
    require(output.is_dir() and not output.is_symlink(), 'ISOLATED_OUTPUT')
    paths = list(output.iterdir())
    require(all(p.name in OUTPUTS for p in paths), 'OUTPUT_ALLOWLIST')
    for path in paths: require(type(small(path)) is dict, 'OUTPUT_OBJECT')
    if complete: require({p.name for p in paths} == set(OUTPUTS), 'EXACT_OUTPUT_COUNT')

def run(request_path, output):
    global _phase
    with borrowed() as (bridge,modules,profile):
        c = modules['desktop_control']; c.cloud()
        root = Path.cwd().resolve(strict=True)
        require(os.environ.get('RUNNER_OS') == 'Windows' and Path(os.environ['GITHUB_WORKSPACE']).resolve(strict=True) == root, 'EXACT_CLOUD_WORKSPACE')
        require(request_path.resolve(strict=True) == root/REQUEST and output.absolute() == root/EVIDENCE, 'FIXED_INPUT_OUTPUT')
        require(bridge.command('git','-C',str(root),'status','--porcelain','--untracked-files=all') == b'', 'CLEAN_CONTROL')
        require(bridge.command('git','-C',str(root),'rev-parse','HEAD').decode().strip() == os.environ['GITHUB_SHA'], 'EXACT_CONTROL_HEAD')
        require(digest(FIXTURE) == FIXTURE_SHA, 'EXACT_FIXTURE')
        for sha in (OLD,NEW): require(hashlib.sha256(c.git_bytes(root,'show',sha+':'+MAIN_PATH)).hexdigest() == MAIN_SHA, 'SOURCE_BOUND_NORMAL_NOTICE_CONTRACT')
        temporary = Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
        require(not temporary.is_relative_to(root) and not root.is_relative_to(temporary), 'SEPARATE_RUNNER_TEMP')
        private = temporary/('ccb-lan7-prepared-'+uuid.uuid4().hex);private.mkdir()
        with profile.profile():
            request = validate_request(c,small(request_path))
            _phase = 'EXACT_ORIGINAL_PACKAGE_AUTHENTICATION';c.fetch_originals(request,output)
            sources = {}
            for sha,leaf in ((OLD,'source-delivered-lan6'),(NEW,'source-successor-lan7')):
                source=private/leaf
                bridge.command('git','-C',str(root),'worktree','add','--detach',str(source),sha)
                sources[sha]=source
            companion=Companion(bridge,modules,sources,private,output);companion.prepare()
            with attach(modules,bridge,companion) as main_stages: profile.native.run(request,output)
            require(main_stages == list(MAIN_STAGES) and small(output/RESULT)['mainMatrixPassed'] is True, 'FIVE_ORIGINAL_MAIN_LAUNCHES_REQUIRED')
            # Use only the exact same synthetic root captured by the successful old SEED invocation.
            try:
                companion.run(companion.retained_root,'COLD_INVITE')
            except Exception as failure:
                failed=small(output/RESULT); failed.update(passed=False,proofComplete=False,failure={'phase':_phase,'type':safe_type(failure)},componentFailureDiagnostic=companion.failure)
                (output/RESULT).write_text(json.dumps(failed,indent=2)+'\n',encoding='utf8',newline='\n')
                raise
            archive_root=make_archive_root(temporary,private)
            try:
                companion.run(archive_root,'ARCHIVE');companion.run(archive_root,'ARCHIVE_COLD')
            except Exception as failure:
                failed=small(output/RESULT); failed.update(passed=False,proofComplete=False,failure={'phase':_phase,'type':safe_type(failure)},componentFailureDiagnostic=companion.failure)
                (output/RESULT).write_text(json.dumps(failed,indent=2)+'\n',encoding='utf8',newline='\n')
                raise
            require([v['action'] for v in companion.reports] == list(ACTIONS), 'FIVE_EXACT_COMPONENT_INVOCATIONS')
            complete = small(output/RESULT); complete.update(passed=True, proofComplete=True, separateComponentActionsCompleted=list(ACTIONS))
            (output/RESULT).write_text(json.dumps(complete,ensure_ascii=False,indent=2)+'\n',encoding='utf8',newline='\n')
            write_new(output/'prepared-upgrade-validation.json',{'kind':'CCB_EXACT_LAN6_LAN7_PREPARED_UPGRADE_VERIFIED','passed':True,
                'sourceOld':OLD,'sourceCandidate':NEW,'candidateBuildControl':BUILD_CONTROL,'packagedMainLaunches':5,
                'separateComponentInvocations':5,'distinctKotlinMethods':3,'nativeRetentionComparisons':2,
                'originalMainAndRootLockGuardsUnchanged':True,'sharedTimestampWholeFilesIdentical':True,
                'oldPackageRebuilt':False,'candidateAppRebuilt':False,'programOnlyUpgrade':True,'sameWindowsAccountAndRetainedUserData':True,
                'packagedVisibleCreationOrRecoveryUiExercised':False,'cloudOrPeerDeliveryVerified':False,'humanAcceptance':False,
                'separateDpapiScopes':['old metadata refusal','same PREPARED completion','cold READY and real Coordinator invitation', 'unused setup archive','cold completed archive'],
                'originalComponents':companion.originals,'privateXmlCodeOracleLogsOrTreesUploaded':False,'outputs':list(OUTPUTS)})
            audit_output(output,complete=True)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--request',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--audit-output',action='store_true');args=parser.parse_args()
    try:
        if args.audit_output:
            require(args.output.absolute() == Path.cwd().resolve(strict=True)/EVIDENCE,'FIXED_OUTPUT');audit_output(args.output)
        else: run(args.request,args.output)
        return 0
    except Exception as failure:
        # No exception text, cause, path, stack, code, invitation or private report is public.
        print('LAN7_PREPARED_UPGRADE_REFUSED:'+_phase+':'+safe_type(failure),file=sys.stderr)
        return 2
if __name__=='__main__':raise SystemExit(main())
