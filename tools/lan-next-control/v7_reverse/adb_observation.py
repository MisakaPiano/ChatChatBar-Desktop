"""Closed, content-free observations of this synthetic API26 controller only."""
import hashlib
import re

PRIVATE_FILES={'READY':'ready.json','PROGRESS':'progress.json','ROUTE':'route.json','ORACLE':'oracle.json'}
OPERATIONS=frozenset({'GET_EMULATOR_FLAG','GET_API_LEVEL','GET_PACKAGE_DESCRIPTOR','GET_APK_PATH','GET_APK_HASH',
    'VERIFY_SHELL_FEATURES','VERIFY_RUN_AS_ROOT','PRIVATE_OFFER_WRITE','PRIVATE_ACK_WRITE',
    'FORWARD_CREATE','FORWARD_LIST','FORWARD_REMOVE','PRIVATE_PROTOCOL_CLEANUP'} |
    {'PRIVATE_'+key+'_'+action for key in PRIVATE_FILES for action in ('STAT','CAT')})


class AdbFailure(Exception):
    def __init__(self,evidence,code='ADB_COMMAND_FAILED'):
        super().__init__(code)
        self.evidence=evidence


def observation(operation,returncode,stdout,stderr,transport='RETURNED'):
    if operation not in OPERATIONS:raise ValueError('ADB_OPERATION_NOT_CLOSED')
    if transport not in {'RETURNED','TIMEOUT','SPAWN_ERROR'}:raise ValueError('ADB_TRANSPORT_NOT_CLOSED')
    if type(stdout) is not bytes or type(stderr) is not bytes:raise ValueError('ADB_OBSERVATION_BYTES')
    if returncode is not None and type(returncode) is not int:raise ValueError('ADB_EXIT_NOT_INTEGER')
    code=None if returncode is None else max(-255,min(returncode,255))
    value={'operation':operation,'transport':transport,'exitCode':code,
        'exitCodeCapped':returncode is not None and returncode!=code,
        'stdoutBytes':min(len(stdout),65537),'stdoutCapped':len(stdout)>65536,
        'stderrBytes':min(len(stderr),4097),'stderrCapped':len(stderr)>4096,
        'errorClass':'NONE','disposition':'REFUSED'}
    if transport!='RETURNED':value['errorClass']=transport;return value
    if len(stdout)>65536 or len(stderr)>4096:value['errorClass']='OUTPUT_LIMIT';return value
    if returncode==0 and not stderr.strip():value['disposition']='SUCCESS';return value
    # API26 toybox stat's exact single-path ENOENT contract. A package/run-as
    # failure containing the same English phrase is deliberately not a miss.
    for key,name in PRIVATE_FILES.items():
        expected=("stat: 'files/v7-reverse-lab/"+name+"': No such file or directory\n").encode('ascii')
        if operation=='PRIVATE_'+key+'_STAT' and returncode==1 and not stdout and stderr==expected:
            value.update(errorClass='EXPECTED_PRIVATE_FILE_MISSING',disposition='EXPECTED_MISSING');return value
    patterns=(('RUN_AS_NOT_DEBUGGABLE',rb'(?i)not debuggable'),
        ('RUN_AS_PACKAGE_UNAVAILABLE',rb'(?i)(unknown package|package .+ is unknown)'),
        ('RUN_AS_SETUP_FAILED',rb'(?i)(could not set capabilities|could not set uid|could not set gid)'),
        ('RUN_AS_DATA_UNAVAILABLE',rb'(?i)could not stat'),
        ('PERMISSION_DENIED',rb'(?i)permission denied'),
        ('DEVICE_UNAVAILABLE',rb'(?i)(device offline|no devices/emulators found|device .+ not found)'),
        ('BAD_ADB_OPTION',rb'(?i)(unknown option|unrecognized option|unknown command)'),
        ('TRANSPORT_CLOSED',rb'(?i)(error: closed|connection reset|protocol fault)'),
        ('OTHER_ENOENT',rb'No such file or directory'))
    value['errorClass']=next((label for label,pattern in patterns if re.search(pattern,stderr)),'OTHER')
    return value


def interpret(evidence,stdout,missing=False):
    if evidence['disposition']=='SUCCESS':return stdout
    if missing and evidence['disposition']=='EXPECTED_MISSING':return None
    raise AdbFailure(evidence)


def instrumentation_summary(stdout,stderr,process_exited,expected_class,expected_method,phases):
    if type(stdout) is not bytes or type(stderr) is not bytes or type(process_exited) is not bool:
        raise ValueError('NATIVE_OBSERVATION_TYPES')
    bounded=len(stdout)<=131072 and len(stderr)<=131072
    value={'availability':'READABLE' if bounded else 'SIZE_LIMIT','snapshotComplete':False,'passClaimed':False,
        'processExitedBeforeCleanup':process_exited,'stdoutBytes':min(len(stdout),131073),
        'stderrBytes':min(len(stderr),131073),'stdoutSha256':None,'stderrSha256':None,
        'expectedClassObserved':None,'expectedMethodObserved':None,'testStartCodeObserved':None,
        'lastStatusCode':None,'finalCode':None,'methodFailurePhase':None,'exceptionMarker':'UNKNOWN'}
    if not bounded:return value
    value.update(stdoutSha256=hashlib.sha256(stdout).hexdigest(),stderrSha256=hashlib.sha256(stderr).hexdigest(),
        expectedClassObserved=re.search(rb'(?m)^INSTRUMENTATION_STATUS: class='+re.escape(expected_class.encode())+rb'\r?$',stdout) is not None,
        expectedMethodObserved=re.search(rb'(?m)^INSTRUMENTATION_STATUS: test='+re.escape(expected_method.encode())+rb'\r?$',stdout) is not None,
        testStartCodeObserved=re.search(rb'(?m)^INSTRUMENTATION_STATUS_CODE: 1\r?$',stdout) is not None)
    for key,prefix in (('lastStatusCode',b'INSTRUMENTATION_STATUS_CODE'),('finalCode',b'INSTRUMENTATION_CODE')):
        matches=re.findall(rb'(?m)^'+prefix+rb': (-?[0-9]{1,3})\r?$',stdout)
        if matches:value[key]=max(-255,min(255,int(matches[-1])))
    failed=re.findall(rb'V7_REVERSE_ANDROID_PHASE_FAILED:([a-z-]+)',stdout)
    if failed and failed[0].decode('ascii') in phases:value['methodFailurePhase']=failed[0].decode('ascii')
    combined=stdout+b'\n'+stderr
    markers=(('VERIFY_ERROR',rb'\bjava\.lang\.VerifyError\b'),
        ('NO_CLASS_DEF',rb'\bjava\.lang\.NoClassDefFoundError\b'),
        ('CLASS_NOT_FOUND',rb'\bjava\.lang\.ClassNotFoundException\b'),
        ('ASSERTION_ERROR',rb'\bjava\.lang\.AssertionError\b'),
        ('SECURITY_EXCEPTION',rb'\bjava\.lang\.SecurityException\b'),
        ('PROCESS_CRASH_MARKER',rb'(?m)^INSTRUMENTATION_RESULT: shortMsg=Process crashed\.'),
        ('RUNNER_FAILURE_MARKER',rb'(?m)^INSTRUMENTATION_FAILED:'))
    value['exceptionMarker']=next((name for name,pattern in markers if re.search(pattern,combined)),'UNKNOWN')
    return value
