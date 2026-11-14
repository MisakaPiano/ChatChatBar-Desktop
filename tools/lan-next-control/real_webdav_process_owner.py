"""Direct, bounded owner of the fixed synthetic rclone child; no import-time work."""
from __future__ import annotations

import argparse
import base64
from contextlib import contextmanager
import hashlib
import os
from pathlib import Path
import platform
import re
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

SOURCE = '6ab083be4ee3de70757b06ea75ba49a77c558385'
FIXTURE_SHA = '56075f65de42a46ea3dffe1ebf38f91691316473705e1da068e7f5dbe4af9a69'
TARGET = 'app/sharedCore/src/test/kotlin/com/example/chatbar/domain/syncStoreProof/RealWebDavInteropSmokeTest.kt'
XML = 'app/sharedCore/build/test-results/test/TEST-com.example.chatbar.domain.syncStoreProof.RealWebDavInteropSmokeTest.xml'
SELECTOR = ('com.example.chatbar.domain.syncStoreProof.RealWebDavInteropSmokeTest.'
            'realServerMethodsAndColdDifferentLocalRoutePreserveCompleteOpaquePayload')
URL = 'http://127.0.0.1:18765/'
USER = 'synthetic-ccb-ci'
STOP_SECONDS = 5


class OwnerFailure(RuntimeError):
    """Only fixed internal codes may enter a public diagnostic."""


def require(value, code):
    if not value:
        raise OwnerFailure(code)


def stop_owned_server(process):
    """Signal only this Popen's unreaped child; every wait has a five-second bound."""
    if process.poll() is not None:
        return
    try:
        process.terminate()
    except ProcessLookupError:
        pass  # It exited between poll and signal; still reap our own child below.
    except OSError:
        raise OwnerFailure('SERVER_TERMINATE_FAILED') from None
    try:
        process.wait(timeout=STOP_SECONDS)
        return
    except subprocess.TimeoutExpired:
        pass
    except OSError:
        raise OwnerFailure('SERVER_TERM_WAIT_FAILED') from None
    if process.poll() is not None:
        return
    try:
        process.kill()
    except ProcessLookupError:
        pass
    except OSError:
        raise OwnerFailure('SERVER_KILL_FAILED') from None
    try:
        process.wait(timeout=STOP_SECONDS)
    except subprocess.TimeoutExpired:
        # TimeoutExpired contains process.args, including the synthetic password.
        raise OwnerFailure('SERVER_KILL_WAIT_EXPIRED') from None
    except OSError:
        raise OwnerFailure('SERVER_KILL_WAIT_FAILED') from None


def run_owned_server(server_argv, server_log, ready, work, begin_cleanup=lambda: None):
    # No new session/PGID: the existing outer helper owns the whole group.
    process = subprocess.Popen(server_argv, stdout=subprocess.DEVNULL, stderr=server_log,
                               start_new_session=False)
    primary = None
    try:
        ready(process)
        return work()
    except BaseException as failure:
        primary = failure
        raise
    finally:
        begin_cleanup()
        try:
            stop_owned_server(process)
        except BaseException as cleanup:
            if primary is None:
                raise
            # Preserve a client/readiness/interruption failure without printing
            # either exception's repr/argv. The outer control keeps original XML.
            print('REAL_DAV_CLEANUP_FAILURE type=' + type(cleanup).__name__, file=sys.stderr)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


def readiness(process, password):
    token = base64.b64encode((USER + ':' + password).encode('ascii')).decode('ascii')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    for _ in range(60):
        require(process.poll() is None, 'SERVER_EXITED_BEFORE_READY')
        request = urllib.request.Request(URL, method='PROPFIND',
            headers={'Depth': '0', 'Authorization': 'Basic ' + token})
        try:
            with opener.open(request, timeout=2) as response:
                if response.status == 207:
                    require(process.poll() is None, 'SERVER_EXITED_AFTER_READY')
                    return
        except urllib.error.HTTPError as response:
            response.close()
        except (urllib.error.URLError, TimeoutError, OSError):
            pass
        time.sleep(0.25)
    raise OwnerFailure('SERVER_NOT_READY')


def cloud():
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_OS') == 'Linux' and
            os.environ.get('GITHUB_REPOSITORY') == 'MisakaPiano/ChatChatBar-Desktop' and
            platform.system() == 'Linux' and platform.machine() == 'x86_64', 'CLOUD_LINUX_X64_ONLY')


def canonical(value, exists=True):
    path = Path(os.path.abspath(value))
    require(all(not p.is_symlink() for p in (path, *path.parents)), 'PATH_SYMLINK')
    require(path.resolve(strict=exists) == path, 'PATH_ALIAS')
    return path


def source_guard(source):
    def git(*args):
        return subprocess.run(['git', '-C', str(source), *args], check=True, capture_output=True,
                              timeout=30).stdout.decode('utf-8', errors='strict').strip()
    require(git('rev-parse', '--show-toplevel') == str(source) and git('rev-parse', 'HEAD') == SOURCE,
            'FIXED_SOURCE_REQUIRED')
    require(git('status', '--porcelain', '--untracked-files=all') == '?? ' + TARGET,
            'ONE_TEST_OVERLAY_REQUIRED')
    fixture = canonical(source / TARGET)
    require(fixture.is_file() and hashlib.sha256(fixture.read_bytes()).hexdigest() == FIXTURE_SHA,
            'FIXED_FIXTURE_REQUIRED')
    require(not (source / XML).exists() and not (source / XML).is_symlink(), 'PREEXISTING_JUNIT')


def layout(source_value, case_value):
    temporary = canonical(os.environ['RUNNER_TEMP'])
    source = canonical(source_value)
    case = canonical(case_value)
    require(source.is_relative_to(temporary) and case.parent == temporary and
            case.name.startswith('ccb-rclone-interop-') and
            not source.is_relative_to(case) and not case.is_relative_to(source), 'OWNED_TEMP_ROOTS')
    binary = canonical(case / 'binary/rclone-v1.76.0-linux-amd64/rclone')
    require(binary.is_file() and os.access(binary, os.X_OK), 'FIXED_SERVER_BINARY')
    for name in ('server', 'cache'):
        require(canonical(case / name).is_dir(), 'PRIVATE_SERVER_DIRECTORY')
    config = canonical(case / 'empty.conf')
    require(config.is_file() and config.stat().st_size == 0, 'EMPTY_PRIVATE_CONFIG')
    log = canonical(case / 'server.log', exists=False)
    require(not log.exists() and not log.is_symlink(), 'NEW_PRIVATE_LOG')
    require(os.environ.get('CCB_REAL_DAV_PROOF') == 'true' and
            os.environ.get('CCB_REAL_DAV_URL') == URL and os.environ.get('CCB_REAL_DAV_USER') == USER,
            'FIXED_LOOPBACK_REQUIRED')
    password = os.environ.get('CCB_REAL_DAV_PASS', '')
    require(re.fullmatch('[0-9a-f]{48}', password) is not None, 'SYNTHETIC_PASSWORD_REQUIRED')
    evidence = canonical(os.environ['CCB_REAL_DAV_METHOD_EVIDENCE'], exists=False)
    require(evidence.is_relative_to(temporary) and not evidence.is_relative_to(source) and
            not evidence.is_relative_to(case) and evidence.name == 'real-webdav-method-counts.json' and
            not evidence.exists() and not evidence.is_symlink(), 'FIXED_EVIDENCE_PATH')
    source_guard(source)
    return source, case, binary, log, password


@contextmanager
def interruption_handlers():
    stopping = False
    def stop_signal(signum, frame):
        nonlocal stopping
        if stopping:
            return  # Only ignore repeated signals during the finite cleanup.
        stopping = True
        raise OwnerFailure('PROCESS_OWNER_INTERRUPTED')
    def begin_cleanup():
        nonlocal stopping
        stopping = True
    previous = {sig: signal.getsignal(sig) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        for sig in previous:
            signal.signal(sig, stop_signal)
        yield begin_cleanup
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


def main(argv=None):
    phase = 'validate'
    try:
        cloud()  # Before parsing paths, inspecting files or creating processes.
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument('--source-dir', required=True)
        parser.add_argument('--case-root', required=True)
        args = parser.parse_args(argv)
        source, case, binary, log, password = layout(args.source_dir, args.case_root)
        server_argv = [str(binary), 'serve', 'webdav', str(case / 'server'), '--addr', '127.0.0.1:18765',
            '--user', USER, '--pass', password, '--config', str(case / 'empty.conf'),
            '--cache-dir', str(case / 'cache'), '--vfs-cache-mode', 'off', '--log-level', 'INFO']
        def work():
            source_guard(source)
            return subprocess.run(['bash', './gradlew', ':sharedCore:test', '--tests', SELECTOR,
                '--no-build-cache', '--rerun-tasks', '--no-daemon', '--stacktrace'],
                cwd=source / 'app', start_new_session=False).returncode
        phase = 'server-client-and-bounded-cleanup'
        with interruption_handlers() as begin_cleanup, log.open('xb') as server_log:
            code = run_owned_server(server_argv, server_log,
                lambda process: readiness(process, password), work, begin_cleanup)
        return code if 0 <= code <= 255 else 70
    except BaseException as failure:
        # Never expose TimeoutExpired/CalledProcessError args or a credential.
        print('REAL_DAV_PROCESS_FAILURE phase=' + phase + ' type=' + type(failure).__name__, file=sys.stderr)
        return 70


if __name__ == '__main__':
    raise SystemExit(main())
