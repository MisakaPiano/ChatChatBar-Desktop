"""Cloud Windows JDK-only probe owner. No application/source/package is executed."""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import sys
import uuid

HERE = Path(__file__).resolve().parent
JAVA = HERE / 'fixtures/StableEmptyObjectProof.java'
JAVA_SHA = '49c24dfaf49bb08a375bd00726b91d455b1e9dd81333150f9b0887a635bf171e'
CLASS = 'com.example.chatbar.desktop.lanSync.StableEmptyObjectProof'
OUTPUT = 'desktop-group-retained-upgrade-evidence'
RAW = 'empty-object-proof-native-result.json'
VERIFIED = 'empty-object-proof-verification.json'
CHECKS = (
    'REAL_WINDOWS_LOCKED_EMPTY_CONFIRMED', 'NONEMPTY_LOCKED_FALLBACK_REMAINS_FATAL',
    'GROWTH_BETWEEN_BEFORE_AND_HIT_REJECTED',
    'AFTER_GROWTH_REJECTED_WITH_INJECTED_STALE_ZERO_ATTRIBUTES',
    'REAL_REPLACEMENT_AFTER_WITNESS_REJECTED', 'REAL_REPLACEMENT_INSIDE_GUARD_BLOCKED',
    'INJECTED_UNKNOWN_OR_WRONG_IDENTITY_REJECTED',
    'INJECTED_METADATA_OR_FILESYSTEM_ERROR_OR_UNKNOWN_REJECTED',
    'DIRECTORY_AND_MISSING_REJECTED', 'REAL_SYMBOLIC_LINK_REJECTED', 'PRIVATE_WITNESSES_CLEANED',
)
RAW_KEYS = frozenset({'kind', 'passed', 'commandClass', 'commandMethod', 'invocations',
    'logicalChecksCompleted', 'checks', 'publicFileKeyAvailable', 'fileKeyNullUsedAsIdentity',
    'ntfsAndSameStoreObserved', 'lockedEmptyStrictReadFailed', 'unknownIdentityStrictReadAttempted',
    'unknownIdentityStrictReadFailed', 'failureCode', 'failureType', 'realWindowsFileLockExercised',
    'appOrPackagedMainExecuted', 'privateFilesUploaded'})
VERIFY_KEYS = frozenset({'kind', 'passed', 'controlSha', 'sourceSha256', 'sourceBytes', 'commandClass',
    'commandMethod', 'standaloneMainInvocations', 'javaProcessLaunchAttempts', 'logicalChecksExpected', 'junitMethodsExecuted',
    'applicationMainInvocations', 'groupJUnitComponentsExecuted', 'appSourceCheckedOut', 'packageDownloaded',
    'privateLogsOrClassesOrCaseFilesUploaded', 'failurePhase', 'nativeOriginal', 'compilerLocations'})
# Finite literal tokens from the JAVA_SHA-bound source; no arbitrary exception message is accepted.
FAILURE_CODES = frozenset({'NONEMPTY_LOCKED_FALLBACK_REMAINS_FATAL', 'OUTPUT_SCOPE', 'REAL_SYMBOLIC_LINK_REJECTED', 'NTFS', 'WITNESSES_CLEANED', 'ISOLATED_ROOT', 'REAL_REPLACEMENT_AFTER_WITNESS_REJECTED', 'GITHUB_ACTIONS', 'MISSING_REJECTED', 'GROWTH_BETWEEN_BEFORE_AND_HIT_REJECTED', 'INJECTED_METADATA_OR_FILESYSTEM_ERROR_OR_UNKNOWN_REJECTED', 'NONEMPTY_REJECTED', 'SYMBOLIC_LINK_REJECTED', 'RUNNER_OS', 'RUNNER_TEMP', 'CONFIRMED_NTFS_SAME_STORE_REQUIRED', 'CLOUD_WINDOWS_JDK17', 'REPLACEMENT_REJECTED', 'GITHUB_WORKSPACE', 'EMPTY_REAL_LOCK_CONFIRMED', 'GROWTH_STRICT_FALLBACK_FATAL', 'UNSUPPORTED_FS_REJECTED', 'AFTER_GROWTH_REJECTED_WITH_INJECTED_STALE_ZERO_ATTRIBUTES', 'REAL_WINDOWS_LOCKED_EMPTY_CONFIRMED', 'NONEMPTY_STRICT_FALLBACK_FATAL', 'NOSHARE_DELETE_PREVENTS_REPLACEMENT', 'REAL_REPLACEMENT_INSIDE_GUARD_BLOCKED', 'UNEXPECTED_FAILURE', 'STABLE_EMPTY_OBJECT_NATIVE_PROBE_PASS', 'AFTER_GROWTH_REJECTED', 'OUTPUT_UNAVAILABLE', 'ARGUMENTS', 'UNKNOWN_FS_REJECTED', 'METADATA_EXCEPTION_REJECTED', 'STABLE_EMPTY_OBJECT_NATIVE_PROBE_FAIL', 'SYMBOLIC_LINK_STRICT_FALLBACK_FATAL', 'NATIVE_SYMBOLIC_LINK_CASE_UNAVAILABLE', 'PRIVATE_WITNESSES_CLEANED', 'SYNTHETIC_METADATA_FAULT', 'EMPTY_OBJECT_PROOF_CLEANUP_FAILED', 'WRONG_IDENTITY_REJECTED', 'NONE', 'AFTER_GROWTH_FALLBACK_FATAL', 'NULL_METADATA_REJECTED', 'GROWTH_REJECTED', 'DIRECTORY_REJECTED', 'ORIGINAL_OWNER_UNCHANGED', 'UNKNOWN_IDENTITY_REJECTED', 'INJECTED_UNKNOWN_OR_WRONG_IDENTITY_REJECTED', 'STABLE_EMPTY_OBJECT_NATIVE_PROBE_OUTPUT_FAILED', 'DIRECTORY_AND_MISSING_REJECTED'})
PHASE = 'GUARD'


def require(value, code):
    if not value:
        raise ValueError(code)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pairs(items):
    result = {}
    for key, value in items:
        require(key not in result, 'DUPLICATE_JSON_KEY')
        result[key] = value
    return result


def small(path):
    require(path.is_file() and not path.is_symlink() and 0 < path.stat().st_size < 65536, 'SMALL_REGULAR_JSON')
    return json.loads(path.read_bytes(), object_pairs_hook=pairs)


def validate_raw(value):
    require(type(value) is dict and set(value) == RAW_KEYS, 'RAW_FIELDS')
    require(value['kind'] == 'CCB_WINDOWS_STABLE_EMPTY_OBJECT_NATIVE_PROBE_V1' and
            value['commandClass'] == CLASS and value['commandMethod'] == 'main', 'RAW_IDENTITY')
    require(type(value['passed']) is bool and type(value['invocations']) is int and value['invocations'] == 1, 'RAW_COUNTS')
    require(type(value['logicalChecksCompleted']) is int and type(value['checks']) is list and
            value['checks'] == list(CHECKS[:value['logicalChecksCompleted']]) and
            0 <= value['logicalChecksCompleted'] <= len(CHECKS), 'RAW_LOGICAL_CHECKS')
    for key in ('publicFileKeyAvailable', 'ntfsAndSameStoreObserved', 'lockedEmptyStrictReadFailed', 'unknownIdentityStrictReadFailed'):
        require(value[key] is None or type(value[key]) is bool, 'RAW_OBSERVATION_TYPES')
    for key in ('fileKeyNullUsedAsIdentity', 'unknownIdentityStrictReadAttempted', 'realWindowsFileLockExercised',
                'appOrPackagedMainExecuted', 'privateFilesUploaded'):
        require(type(value[key]) is bool, 'RAW_BOOL_TYPES')
    require(value['fileKeyNullUsedAsIdentity'] is False and value['appOrPackagedMainExecuted'] is False and
            value['privateFilesUploaded'] is False, 'RAW_SCOPE')
    require(type(value['failureCode']) is str and value['failureCode'] in FAILURE_CODES and
            type(value['failureType']) is str and re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,63}', value['failureType']), 'RAW_SAFE_FAILURE')
    if value['passed']:
        require(value['checks'] == list(CHECKS) and value['logicalChecksCompleted'] == 11 and
                value['ntfsAndSameStoreObserved'] is True and value['realWindowsFileLockExercised'] is True and
                value['unknownIdentityStrictReadAttempted'] is True and
                all(type(value[key]) is bool for key in ('publicFileKeyAvailable', 'lockedEmptyStrictReadFailed', 'unknownIdentityStrictReadFailed')) and
                value['failureCode'] == value['failureType'] == 'NONE', 'RAW_SUCCESS_CONTRACT')
    else:
        require(value['failureCode'] != 'NONE' and value['failureType'] != 'NONE', 'RAW_FAILURE_REQUIRED')
    return value


def validate_verification(value):
    require(type(value) is dict and set(value) == VERIFY_KEYS, 'VERIFICATION_FIELDS')
    require(value['kind'] == 'CCB_WINDOWS_STABLE_EMPTY_OBJECT_JDK_PROBE_VERIFICATION_V1' and
            value['commandClass'] == CLASS and value['commandMethod'] == 'main', 'VERIFICATION_IDENTITY')
    require(type(value['passed']) is bool and type(value['controlSha']) is str and
            re.fullmatch(r'[a-f0-9]{40}', value['controlSha']) and value['sourceSha256'] == JAVA_SHA and
            type(value['sourceBytes']) is int and 0 < value['sourceBytes'] < 65536, 'VERIFICATION_SOURCE')
    require(type(value['standaloneMainInvocations']) is int and value['standaloneMainInvocations'] in (0, 1) and
            type(value['javaProcessLaunchAttempts']) is int and value['javaProcessLaunchAttempts'] in (0, 1) and
            value['logicalChecksExpected'] == 11, 'VERIFICATION_COUNTS')
    for key in ('junitMethodsExecuted', 'applicationMainInvocations', 'groupJUnitComponentsExecuted'):
        require(type(value[key]) is int and value[key] == 0, 'VERIFICATION_NO_APP')
    for key in ('appSourceCheckedOut', 'packageDownloaded', 'privateLogsOrClassesOrCaseFilesUploaded'):
        require(value[key] is False, 'VERIFICATION_SCOPE')
    require(value['failurePhase'] in (None, 'COMPILE_JDK_HELPER', 'REAL_WINDOWS_JDK_PROBE'), 'VERIFICATION_PHASE')
    native = value['nativeOriginal']
    if native is not None:
        require(type(native) is dict and set(native) == {'bytes', 'sha256', 'passed', 'logicalChecksCompleted', 'failureCode', 'failureType'}, 'VERIFICATION_NATIVE_FIELDS')
        require(type(native['bytes']) is int and 0 < native['bytes'] < 65536 and type(native['sha256']) is str and
                re.fullmatch(r'[a-f0-9]{64}', native['sha256']) and type(native['passed']) is bool and
                type(native['logicalChecksCompleted']) is int and 0 <= native['logicalChecksCompleted'] <= 11 and
                native['failureCode'] in FAILURE_CODES and type(native['failureType']) is str and
                re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,63}', native['failureType']), 'VERIFICATION_NATIVE_TYPES')
    locations = value['compilerLocations']
    require(type(locations) is list and len(locations) <= 8, 'VERIFICATION_COMPILER_COUNT')
    for item in locations:
        require(type(item) is dict and set(item) == {'file', 'line', 'classification'} and
                item['file'] == JAVA.name and type(item['line']) is int and 0 < item['line'] <= 99999 and
                item['classification'] in {'CANNOT_FIND_SYMBOL', 'OTHER_COMPILER_ERROR'}, 'VERIFICATION_COMPILER_FIELDS')
    if value['passed']:
        require(value['standaloneMainInvocations'] == value['javaProcessLaunchAttempts'] == 1 and value['failurePhase'] is None and
                native is not None and native['passed'] is True and native['logicalChecksCompleted'] == 11 and not locations,
                'VERIFICATION_SUCCESS_CONTRACT')
    return value


def write_new(path, value):
    with path.open('x', encoding='utf-8', newline='\n') as output:
        json.dump(value, output, indent=2)
        output.write('\n')


def audit(directory):
    require(directory.is_dir() and not directory.is_symlink(), 'OUTPUT_DIRECTORY')
    for path in directory.iterdir():
        require(path.name in {RAW, VERIFIED}, 'OUTPUT_ALLOWLIST')
        small(path)
    require((directory / VERIFIED).is_file(), 'VERIFICATION_REQUIRED')
    verified = validate_verification(small(directory / VERIFIED))
    if (directory / RAW).exists():
        raw = validate_raw(small(directory / RAW))
        require(verified['nativeOriginal'] is not None and
                verified['nativeOriginal']['sha256'] == digest(directory / RAW) and
                verified['nativeOriginal']['bytes'] == (directory / RAW).stat().st_size and
                verified['nativeOriginal']['passed'] is raw['passed'], 'ORIGINAL_NATIVE_BINDING')
        for key in ('logicalChecksCompleted', 'failureCode', 'failureType'):
            require(verified['nativeOriginal'][key] == raw[key], 'ORIGINAL_NATIVE_SUMMARY_BINDING')
    else:
        require(verified['passed'] is False and verified['nativeOriginal'] is None, 'NATIVE_MISSING_IS_NOT_PASS')


def run():
    global PHASE
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_OS') == 'Windows', 'CLOUD_WINDOWS_ONLY')
    root = Path.cwd().resolve(strict=True)
    require(root == Path(os.environ['GITHUB_WORKSPACE']).resolve(strict=True), 'WORKSPACE_REQUIRED')
    sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    require(sha == os.environ['GITHUB_SHA'] and re.fullmatch(r'[a-f0-9]{40}', sha), 'FIXED_CONTROL_REQUIRED')
    require(subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=all'], text=True) == '', 'CLEAN_CONTROL_REQUIRED')
    require(not JAVA.is_symlink() and digest(JAVA) == JAVA_SHA, 'EXACT_JAVA_SOURCE')
    java_home = Path(os.environ['JAVA_HOME']).resolve(strict=True)
    javac, java = java_home / 'bin/javac.exe', java_home / 'bin/java.exe'
    require(javac.is_file() and java.is_file(), 'OWN_JDK_EXECUTABLES')
    runner = Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
    private = runner / ('ccb-stable-empty-' + uuid.uuid4().hex)
    private.mkdir()
    sources = private / 'sources'; sources.mkdir()
    source = sources / JAVA.name; source.write_bytes(JAVA.read_bytes())
    classes = private / 'classes'; classes.mkdir()
    output = root / OUTPUT
    require(not output.exists(), 'FRESH_OUTPUT_REQUIRED')
    output.mkdir()
    verification = {'kind': 'CCB_WINDOWS_STABLE_EMPTY_OBJECT_JDK_PROBE_VERIFICATION_V1',
        'passed': False, 'controlSha': sha, 'sourceSha256': JAVA_SHA, 'sourceBytes': JAVA.stat().st_size,
        'commandClass': CLASS, 'commandMethod': 'main', 'standaloneMainInvocations': 0, 'javaProcessLaunchAttempts': 0,
        'logicalChecksExpected': 11, 'junitMethodsExecuted': 0, 'applicationMainInvocations': 0,
        'groupJUnitComponentsExecuted': 0, 'appSourceCheckedOut': False, 'packageDownloaded': False,
        'privateLogsOrClassesOrCaseFilesUploaded': False, 'failurePhase': None, 'nativeOriginal': None,
        'compilerLocations': []}
    try:
        PHASE = 'COMPILE_JDK_HELPER'
        log = private / 'javac-private.log'
        with log.open('xb') as stream:
            compiled = subprocess.run([str(javac), '-encoding', 'UTF-8', '-d', str(classes), str(source)],
                stdout=stream, stderr=subprocess.STDOUT, timeout=120)
        if compiled.returncode:
            # Only this exact basename/line and a closed category may leave the private javac log.
            for line in log.read_text(encoding='utf-8', errors='replace').splitlines():
                match = re.fullmatch(re.escape(str(source)) + r':([0-9]{1,5}): error: (.*)', line)
                if match and 0 < int(match[1]) <= 99999:
                    category = 'CANNOT_FIND_SYMBOL' if match[2].startswith('cannot find symbol') else 'OTHER_COMPILER_ERROR'
                    verification['compilerLocations'].append({'file': JAVA.name, 'line': int(match[1]), 'classification': category})
                    if len(verification['compilerLocations']) == 8:
                        break
            require(False, 'JDK_HELPER_COMPILE_FAILED')
        require(digest(source) == JAVA_SHA and digest(JAVA) == JAVA_SHA, 'SOURCE_CHANGED_AFTER_COMPILE')
        PHASE = 'REAL_WINDOWS_JDK_PROBE'
        with (private / 'java-private.log').open('xb') as stream:
            verification['javaProcessLaunchAttempts'] = 1
            native = subprocess.run([str(java), '-cp', str(classes), CLASS, '--root', str(private / 'native-case'),
                '--output', str(output / RAW)], stdout=stream, stderr=subprocess.STDOUT, timeout=120)
        value = validate_raw(small(output / RAW))
        verification['standaloneMainInvocations'] = 1
        verification['nativeOriginal'] = {'bytes': (output / RAW).stat().st_size, 'sha256': digest(output / RAW),
            'passed': value['passed'], 'logicalChecksCompleted': value['logicalChecksCompleted'],
            'failureCode': value['failureCode'], 'failureType': value['failureType']}
        require(digest(source) == JAVA_SHA and digest(JAVA) == JAVA_SHA, 'SOURCE_CHANGED_AFTER_PROBE')
        require(native.returncode == 0 and value['passed'] is True, 'NATIVE_PROBE_FAILED')
        verification['passed'] = True
    except Exception:
        verification['failurePhase'] = PHASE
        raise
    finally:
        write_new(output / VERIFIED, verification)
        audit(output)


def main():
    try:
        if sys.argv[1:] == ['--audit-output']:
            audit(Path.cwd().resolve(strict=True) / OUTPUT)
        else:
            require(not sys.argv[1:], 'NO_USER_PARAMETERS')
            run()
        return 0
    except Exception as failure:
        print('STABLE_EMPTY_OBJECT_PROBE_REFUSED:' + PHASE + ':' + type(failure).__name__, file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
