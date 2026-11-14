"""Fixed, cloud-only real rclone WebDAV transport interop control.

No import-time native work. The sole fixture is an explicit test-only overlay;
requests cannot select a host, executable, class, method, tool version or source.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import platform
import re
import signal
import subprocess
import time
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
SOURCE = '6ab083be4ee3de70757b06ea75ba49a77c558385'
CLASS = 'com.example.chatbar.domain.syncStoreProof.RealWebDavInteropSmokeTest'
METHOD = 'realServerMethodsAndColdDifferentLocalRoutePreserveCompleteOpaquePayload'
TARGET = 'app/sharedCore/src/test/kotlin/com/example/chatbar/domain/syncStoreProof/RealWebDavInteropSmokeTest.kt'
XML = 'app/sharedCore/build/test-results/test/TEST-' + CLASS + '.xml'
FIXTURE = 'fixtures/RealWebDavInteropSmokeTest.kt'
SCRIPT = 'run-real-webdav-interop.sh'
PROCESS_OWNER = 'real_webdav_process_owner.py'
FORMAT = 2
REQUEST = '.github/lan-next-real-webdav-interop-request.json'
VERSION = 'v1.76.0'
ARCHIVE = 'rclone-v1.76.0-linux-amd64.zip'
ARCHIVE_SHA = 'b00f00b3266c3764413afe599d3ec316f6f43b3e0ea8a0bc1d186d2133e46df7'
URL = 'https://github.com/rclone/rclone/releases/download/' + VERSION + '/' + ARCHIVE
METHOD_FILE = 'real-webdav-method-counts.json'
ARCHIVE_FILE = 'rclone-archive-manifest.json'
SCOPE = ('Real official rclone loopback HTTP/Basic/XML/path interoperability of the unchanged CCB '
         'chunk writer and cold different-local-route reader with196731 opaque bytes/seven chunks; '
         'not AEAD, ledger/native admission, app recovery, TLS, commercial provider or large-data performance.')


def require(value, code):
    if not value:
        raise ValueError(code)


def unique(pairs):
    value = {}
    for key, item in pairs:
        require(key not in value, 'DUPLICATE_JSON_KEY')
        value[key] = item
    return value


def load(path):
    return json.loads(Path(path).read_bytes().decode('utf-8', errors='strict'), object_pairs_hook=unique)


def exact_xml(body):
    """Validate actual original Gradle JUnit bytes, never a marker/summary alone."""
    require(0 < len(body) <= 2 * 1024 * 1024 and b'\x00' not in body and b'<!DOCTYPE' not in body and
            b'<!ENTITY' not in body, 'JUNIT_BYTES')
    root = ET.fromstring(body.decode('utf-8', errors='strict'))
    require(root.tag == 'testsuite' and root.get('name') == CLASS, 'JUNIT_SUITE')
    require(all(root.get(key) == expected for key, expected in
                (('tests', '1'), ('failures', '0'), ('errors', '0'), ('skipped', '0'))), 'JUNIT_COUNTS')
    require(all(child.tag in ('testcase', 'properties', 'system-out', 'system-err') for child in root), 'JUNIT_CHILD')
    cases = root.findall('testcase')
    require(len(cases) == 1 and len(list(root.iter('testcase'))) == 1 and
            cases[0].get('classname') == CLASS and cases[0].get('name') == METHOD,
            'JUNIT_EXACT_CASE')
    require(not any(item.tag in ('failure', 'error', 'skipped') for item in root.iter()), 'JUNIT_FAILURE_OR_SKIP')
    require(all(child.tag in ('system-out', 'system-err') for child in cases[0]), 'JUNIT_CASE_CHILD')
    seconds = float(cases[0].get('time', 'nan'))
    require(math.isfinite(seconds) and seconds >= 0, 'JUNIT_TIME')
    return {'class': CLASS, 'method': METHOD, 'tests': 1, 'passed': 1, 'failures': 0,
            'errors': 0, 'skipped': 0, 'seconds': seconds, 'rawBytes': len(body),
            'rawSha256': hashlib.sha256(body).hexdigest()}


class RealWebDavControl:
    def __init__(self, control_root, own_directory=HERE):
        self.control = Path(control_root).resolve(strict=True)
        self.here = Path(own_directory).resolve(strict=True)
        # Reuse the old contract's byte/hash/exclusive JSON primitives without
        # rebinding old constants or importing a native top-level runner.
        path = self.control / 'tools/lan-next-control/contract.py'
        require(not path.is_symlink(), 'COMMON_CONTRACT_SYMLINK')
        spec = importlib.util.spec_from_file_location('_real_webdav_existing_contract', path)
        self.common = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.common)

    def git(self, root, *args):
        return subprocess.run(['git', '-C', str(root), *args], check=True, capture_output=True,
                              timeout=30).stdout.decode('utf-8', errors='strict').strip()

    def expected_request(self):
        fixture = self.here / FIXTURE
        require(fixture.is_file() and not fixture.is_symlink(), 'CONTROL_FIXTURE_FILE')
        return {'format': FORMAT, 'sourceSha': SOURCE, 'fixtureSha256': self.common.fixture_hash(fixture)}

    def validate_request(self, value):
        require(isinstance(value, dict) and set(value) == {'format', 'sourceSha', 'fixtureSha256'} and
                type(value.get('format')) is int and value['format'] == FORMAT, 'REQUEST_FIELDS')
        self.common.sha(value['sourceSha'], 40); self.common.sha(value['fixtureSha256'])
        require(value == self.expected_request(), 'FIXED_INTEROP_REQUEST')
        return value

    def cloud(self):
        require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_OS') == 'Linux' and
                os.environ.get('GITHUB_REPOSITORY') == 'MisakaPiano/ChatChatBar-Desktop' and
                platform.system() == 'Linux' and platform.machine() == 'x86_64', 'CLOUD_LINUX_X64_ONLY')

    @staticmethod
    def canonical(path, exists=True):
        path = Path(os.path.abspath(path))
        for part in (path, *path.parents):
            require(not part.is_symlink(), 'PATH_SYMLINK')
        require(path.resolve(strict=exists) == path, 'PATH_ALIAS')
        return path

    def control_binding(self):
        control_sha = self.common.sha(os.environ.get('GITHUB_SHA'), 40)
        require(self.git(self.control, 'rev-parse', 'HEAD') == control_sha, 'CONTROL_HEAD_CHANGED')
        require(self.git(self.control, 'status', '--porcelain', '--untracked-files=all') == '', 'CONTROL_NOT_CLEAN')
        require(self.canonical(Path(os.environ['GITHUB_WORKSPACE'])) == self.control, 'CONTROL_WORKSPACE')
        files = {}
        for name in ('real_webdav_interop_control.py', SCRIPT, FIXTURE, PROCESS_OWNER):
            path = self.here / name
            require(path.is_file() and not path.is_symlink(), 'CONTROL_INPUT_MISSING')
            files[name] = self.common.file_hash(path)
        return {'controlSha': control_sha, 'filesSha256': files}

    def checked_target(self, source):
        source = self.canonical(source)
        target = source
        for part in Path(TARGET).parts:
            target /= part
            require(not target.is_symlink(), 'SOURCE_TEST_SYMLINK')
        require(target.resolve(strict=False).is_relative_to(source), 'SOURCE_TEST_ESCAPE')
        return target

    def source_status(self, source, request, overlay=False):
        self.validate_request(request)
        require(self.git(source, 'rev-parse', 'HEAD') == request['sourceSha'], 'SOURCE_HEAD_CHANGED')
        require(self.git(source, 'cat-file', '-t', request['sourceSha']) == 'commit', 'SOURCE_NOT_COMMIT')
        expected = '?? ' + TARGET if overlay else ''
        require(self.git(source, 'status', '--porcelain', '--untracked-files=all') == expected, 'SOURCE_NOT_EXCLUSIVE')
        target = self.checked_target(source)
        require(self.git(source, 'ls-tree', '--full-name', request['sourceSha'], '--', TARGET) == '', 'TRACKED_TARGET_ALREADY_EXISTS')
        if overlay:
            require(target.is_file() and self.common.fixture_hash(target) == request['fixtureSha256'], 'SOURCE_FIXTURE_CHANGED')
        else:
            require(not target.exists(), 'SOURCE_TARGET_EXISTS')
        return target

    def roots(self, source, output):
        temporary = self.canonical(Path(os.environ['RUNNER_TEMP']))
        source = self.canonical(source)
        output = self.canonical(output, exists=False)
        require(source.is_relative_to(temporary) and output.is_relative_to(temporary) and
                not source.is_relative_to(output) and not output.is_relative_to(source), 'OWNED_DISTINCT_TEMP_ROOTS')
        require(not source.is_relative_to(self.control) and not output.is_relative_to(self.control), 'CONTROL_ROOT_OVERLAP')
        return source, output

    def prepare(self, request, source, output):
        self.validate_request(request); self.cloud()
        binding = self.control_binding()
        source, output = self.roots(source, output)
        target = self.source_status(source, request)
        require(not (source / XML).exists() and not (source / XML).is_symlink(), 'PREEXISTING_JUNIT_EVIDENCE')
        output.mkdir(parents=True, exist_ok=False)
        target.parent.mkdir(parents=True, exist_ok=True)
        require(self.checked_target(source) == target, 'SOURCE_PATH_CHANGED')
        with target.open('xb') as stream: stream.write(self.common.fixture_bytes(self.here / FIXTURE))
        self.source_status(source, request, True)
        self.common.write_new(output / 'prepared.json', {'kind': 'CCB_REAL_WEBDAV_TEST_ONLY_PREPARED',
            'request': request, 'sourceSha': request['sourceSha'], 'target': TARGET, 'productionFilesChanged': [],
            'control': binding, 'scope': SCOPE})

    def verify_reports(self, source, output, request):
        raw = self.canonical(output / Path(XML).name)
        require(raw.is_file(), 'JUNIT_MISSING')
        body = raw.read_bytes()
        execution = exact_xml(body)
        archive = load(self.canonical(output / ARCHIVE_FILE))
        require(set(archive) == {'kind', 'version', 'filename', 'downloadUrl', 'bytes', 'sha256'} and
                archive['kind'] == 'CCB_RCLONE_PINNED_ARCHIVE' and archive['version'] == VERSION and
                archive['filename'] == ARCHIVE and archive['downloadUrl'] == URL and
                type(archive['bytes']) is int and archive['bytes'] > 0 and archive['sha256'] == ARCHIVE_SHA,
                'RCLONE_ARCHIVE_BINDING')
        version_path = self.canonical(output / 'rclone-version.txt')
        version = version_path.read_text(encoding='utf-8', errors='strict')
        require(version.splitlines()[0] == 'rclone ' + VERSION, 'RCLONE_VERSION')
        counts = load(self.canonical(output / METHOD_FILE))
        require(set(counts) == {'kind', 'payloadBytes', 'physicalChunks', 'methods', 'paths'} and
                counts['kind'] == 'CCB_REAL_WEBDAV_METHOD_COUNTS' and
                type(counts['payloadBytes']) is int and counts['payloadBytes'] == 196731 and
                type(counts['physicalChunks']) is int and counts['physicalChunks'] == 7, 'METHOD_SCOPE')
        methods, paths = counts['methods'], counts['paths']
        require(isinstance(methods, dict) and {'PUT', 'GET', 'PROPFIND', 'MKCOL'} <= set(methods) and
                all(re.fullmatch('[A-Z]{1,16}', key) and type(count) is int and count > 0 for key, count in methods.items()), 'REAL_METHODS')
        require(isinstance(paths, dict) and paths and len(paths) <= 512 and
                all(isinstance(key, str) and len(key) <= 1024 and re.fullmatch('/[a-z0-9/._-]*', key) and
                    type(count) is int and count > 0 for key, count in paths.items()), 'HEADER_FREE_PATHS')
        # The entire report directory is an allowlist, not a broad upload glob
        # over payload/config/private client roots.
        allowed = {'prepared.json', 'runner-stdout.txt', 'runner-stderr.txt', ARCHIVE_FILE, METHOD_FILE,
                   'rclone-version.txt', Path(XML).name}
        require({p.name for p in output.iterdir()} <= allowed and all(p.is_file() and not p.is_symlink() for p in output.iterdir()),
                'REPORT_DIRECTORY_NOT_EXCLUSIVE')
        return {'execution': execution, 'archive': archive, 'methodCounts': counts,
                'rcloneVersionSha256': self.common.file_hash(version_path), 'sourceSha': request['sourceSha']}

    @staticmethod
    def retained_xml(source, output):
        path = source / XML
        if not path.exists() and not path.is_symlink():
            return False
        path = RealWebDavControl.canonical(path)
        require(path.is_file(), 'JUNIT_NOT_REGULAR')
        # Keep original failed XML too. Interpretation happens only afterwards;
        # raw evidence is not overwritten, deleted or reduced to a PASS marker.
        with path.open('rb') as incoming, (output / path.name).open('xb') as target:
            for body in iter(lambda: incoming.read(1024 * 1024), b''): target.write(body)
        return True

    @staticmethod
    def child(argv, stdout, stderr):
        process = subprocess.Popen(argv, stdout=stdout, stderr=stderr, start_new_session=True)
        try:
            return process.wait(timeout=18 * 60)
        except BaseException:
            # The process group is created solely for this new cloud child;
            # it contains its bash/Gradle/rclone children, never an existing host.
            try: os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError: pass
            deadline = time.monotonic() + 15
            while True:
                # Reap an exited leader, but do not mistake that for all of its
                # descendants having drained. Probe the fixed newly-owned PGID.
                process.poll()
                try: os.killpg(process.pid, 0)
                except ProcessLookupError: break
                if time.monotonic() >= deadline:
                    try: os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError: pass
                    break
                time.sleep(0.1)
            process.wait(timeout=15)
            raise

    def run(self, request, source, output):
        self.validate_request(request); self.cloud()
        source, output = self.roots(source, output)
        binding = self.control_binding()
        self.source_status(source, request, True)
        prepared = load(self.canonical(output / 'prepared.json'))
        require(prepared['request'] == request and prepared['control'] == binding and
                prepared['target'] == TARGET and prepared['productionFilesChanged'] == [], 'PREPARED_BINDING')
        require({p.name for p in output.iterdir()} == {'prepared.json'}, 'REPORTS_ALREADY_EXIST')
        require(not (source / XML).exists() and not (source / XML).is_symlink(), 'PREEXISTING_JUNIT_EVIDENCE')
        result = {'kind': 'CCB_REAL_WEBDAV_INTEROP_EXECUTION', 'passed': False, 'sourceSha': request['sourceSha'],
            'control': binding, 'fixtureSha256': request['fixtureSha256'], 'scope': SCOPE,
            'productionFilesChanged': [], 'realProviderOrAeadOrNativeProof': False, 'requestFormat': FORMAT}
        phase = 'cloud-runner'
        try:
            argv = ['bash', str(self.here / SCRIPT), str(source), str(output), request['sourceSha'], request['fixtureSha256']]
            with (output / 'runner-stdout.txt').open('xb') as stdout, (output / 'runner-stderr.txt').open('xb') as stderr:
                code = self.child(argv, stdout, stderr)
            result['runnerExitCode'] = code
            result['originalXmlRetained'] = self.retained_xml(source, output)
            require(code == 0, 'INTEROP_RUNNER_FAILED')
            phase = 'source-and-control-recheck'
            self.source_status(source, request, True)
            require(self.control_binding() == binding, 'CONTROL_CHANGED_DURING_RUN')
            phase = 'strict-original-junit-and-tool-records'
            result.update(self.verify_reports(source, output, request)); result['passed'] = True
        except Exception as error:
            result['failure'] = {'phase': phase, 'type': type(error).__name__}
            raise
        finally:
            if 'originalXmlRetained' not in result:
                try: result['originalXmlRetained'] = self.retained_xml(source, output)
                except Exception as error: result['originalXmlRetentionError'] = type(error).__name__
            self.common.write_new(output / 'execution.json', result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('validate', 'prepare', 'run'))
    parser.add_argument('--request', type=Path, required=True)
    parser.add_argument('--source-dir', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    control = RealWebDavControl(HERE.parents[1])
    request = control.validate_request(load(args.request))
    if args.mode == 'validate': print(request['sourceSha'])
    elif args.mode == 'prepare': control.prepare(request, args.source_dir, args.output)
    else: control.run(request, args.source_dir, args.output)


if __name__ == '__main__':
    main()
