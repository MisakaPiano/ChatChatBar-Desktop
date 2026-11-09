"""Cloud-only packaged-class Main tray proof; ordinary executable/runtime remains a separate matrix."""
import argparse
import ctypes as C
from ctypes import wintypes as W
import hashlib
import json
import msvcrt
import os
from pathlib import Path
import stat
import subprocess
import time
import native_smoke as native


def regular(path):
    for item in (path, *path.parents):
        if item.exists():
            info = item.lstat()
            native.require(not stat.S_ISLNK(info.st_mode) and not getattr(info, 'st_file_attributes', 0) & 0x400, 'PROBE_REPARSE_REFUSED')
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case', type=Path, required=True)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--source', type=Path, required=True)
    args = parser.parse_args()
    native.require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('GITHUB_REPOSITORY') == 'MisakaPiano/ChatChatBar-Desktop', 'CI_ONLY')
    source = args.source.resolve(strict=True); case = regular(args.case.resolve(strict=True)); root = regular(args.root.resolve(strict=True))
    native.require(source == Path(os.environ['CCB_CI_SOURCE']).resolve(strict=True), 'EXACT_CI_SOURCE_REQUIRED')
    commit = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    native.require(commit == os.environ['CCB_CANDIDATE_SOURCE'], 'SOURCE_IDENTITY')
    approved = source / 'app/desktopApp/build/preview-continuous-private-20261025'
    native.require(case.is_relative_to(approved) and case.name.startswith('windows-runtime-nsis-') and
                   json.loads((case / 'TASK_OWNER.json').read_text())['kind'] == native.OWNER, 'OWNED_SYNTHETIC_CASE_REQUIRED')
    current = (root / 'current.txt').read_text().strip(); package = root / 'versions' / current
    manifest = json.loads((package / 'install-manifest.json').read_text())
    native.require(manifest['commit'] == commit and manifest['version'] == os.environ['CCB_CANDIDATE_VERSION'], 'PACKAGED_IDENTITY')
    previous = json.loads((case / 'candidate-cold-external-result.json').read_text())
    native.require(previous.get('remaining') == [] and previous.get('exitCode') == 0 and previous.get('current') == current,
                   'PREVIOUS_OWNED_NATIVE_JOB_MUST_HAVE_DRAINED')
    classes = case / 'packaged-main-tray-classes'; classes.mkdir()  # Exact new companion, outside all program payloads.
    companion = Path(__file__).with_name('PackagedMainTrayProbe.java')
    subprocess.run([str(native.JAVA.with_name('javac.exe')), '-encoding', 'UTF-8', '--release', '17', '-d', str(classes),
                    str(companion)], check=True, timeout=60)
    settings = regular(case / 'local-appdata/ChatChatBarDesktop/desktop-settings.json')
    native.require(settings.parent.is_dir() and (not settings.exists() or
                   settings.is_file() and settings.stat().st_nlink == 1 and settings.stat().st_size <= 1024 * 1024), 'OWNED_PROFILE_REQUIRED')
    original = settings.read_bytes() if settings.exists() else None
    value = json.loads(original) if original is not None else {
        'formatVersion': 1,
        'automaticBackup': {'enabled': False, 'minimumBackupInterval': 'PT24H',
                            'maximumSnapshotCount': 7, 'checkInterval': 'PT1H'},
    }
    native.require(isinstance(value, dict) and type(value.get('formatVersion')) is int and value['formatVersion'] == 1, 'SETTINGS_FORMAT')
    value['closeWindowToTray'] = True
    settings.write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')
    result = case / 'packaged-main-tray-result.json'; native.require(not result.exists(), 'NEW_REPORT_REQUIRED')
    # jpackage extracts Skiko from its JAR and supplies its original path through this cfg.
    # Preserve the exact original classpath order/options instead of inventing a second launch environment.
    cfg = regular(package / 'app/ChatChatBarDesktop.cfg')
    inventoried = {row['path']: row for row in manifest['files']}
    for relative in ['app/ChatChatBarDesktop.cfg', 'app/skiko-windows-x64.dll', 'app/skiko-windows-x64.dll.sha256']:
        path = regular(package / relative); expected = inventoried[relative]
        native.require(path.is_file() and path.stat().st_size == expected['bytes'] and
                       hashlib.sha256(path.read_bytes()).hexdigest() == expected['sha256'], 'ORIGINAL_LAUNCH_RESOURCE_IDENTITY')
    lines = cfg.read_text(encoding='utf-8-sig').splitlines()
    options = [line.removeprefix('java-options=') for line in lines if line.startswith('java-options=')]
    expected_options = {'-Djpackage.app-version=' + os.environ['CCB_CANDIDATE_INSTALLER'],
                        '-Dcompose.application.resources.dir=$APPDIR\\resources',
                        '-Dcompose.application.configure.swing.globals=true', '-Dchatbar.desktop.applicationHome=$ROOTDIR',
                        '-Djava.net.useSystemProxies=true', '-Dskiko.library.path=$APPDIR'}
    native.require(len(options) == len(expected_options) and set(options) == expected_options, 'ORIGINAL_JAVA_OPTIONS_REQUIRED')
    options = [value.replace('$APPDIR', str(package / 'app')).replace('$ROOTDIR', str(package)) for value in options]
    classpath = [line.removeprefix('app.classpath=') for line in lines if line.startswith('app.classpath=')]
    native.require(classpath and len(classpath) == len(set(classpath)) and
                   all(value.startswith('$APPDIR\\') and Path(value.removeprefix('$APPDIR\\')).name == value.removeprefix('$APPDIR\\')
                       and value.endswith('.jar') for value in classpath), 'ORIGINAL_JAR_CLASSPATH_REQUIRED')
    jars = [package / 'app' / value.removeprefix('$APPDIR\\') for value in classpath]
    native.require(set(jars) == set((package / 'app').glob('*.jar')), 'COMPLETE_PACKAGED_CLASSPATH_REQUIRED')
    command = [str(native.JAVA), *options, '-cp', os.pathsep.join(map(str, [classes, *jars])),
               'ccb.synthetic.PackagedMainTrayProbe', str(package), str(result), commit]
    kernel = native.kernel; job = kernel.CreateJobObjectW(None, None); native.require(job, 'JOB_CREATE_FAILED')
    process = native.Process(); startup = native.Startup(); startup.cb = C.sizeof(startup); startup.flags = 0x100
    env = C.create_unicode_buffer('\0'.join(k + '=' + v for k, v in sorted(native.environment(case).items())) + '\0\0')
    def pids():
        data = C.create_string_buffer(8 + 512 * 8)
        native.require(kernel.QueryInformationJobObject(job, 3, data, C.sizeof(data), None), 'JOB_QUERY_FAILED')
        count = W.DWORD.from_buffer(data, 4).value; native.require(count <= 512, 'JOB_LIMIT')
        return list((C.c_size_t * count).from_buffer(data, 8))
    try:
        with (case / 'packaged-main-tray-stdout.log').open('wb') as out, (case / 'packaged-main-tray-stderr.log').open('wb') as err, open(os.devnull, 'rb') as stdin:
            for stream in (out, err, stdin): os.set_inheritable(stream.fileno(), True)
            startup.stdin = msvcrt.get_osfhandle(stdin.fileno()); startup.stdout = msvcrt.get_osfhandle(out.fileno()); startup.stderr = msvcrt.get_osfhandle(err.fileno())
            native.require(kernel.CreateProcessW(str(native.JAVA), C.create_unicode_buffer(subprocess.list2cmdline(command)), None, None, True, 0x404,
                                                  env, str(case), C.byref(startup), C.byref(process)), 'CREATE_PROCESS_FAILED')
            native.require(kernel.AssignProcessToJobObject(job, process.handle), 'SUSPENDED_JOB_ASSIGN_FAILED')
            native.require(kernel.ResumeThread(process.thread) != 0xffffffff, 'RESUME_FAILED'); kernel.CloseHandle(process.thread)
            deadline = time.monotonic() + 300
            while pids() and time.monotonic() < deadline: time.sleep(.1)
            remaining = pids(); code = W.DWORD(); kernel.GetExitCodeProcess(process.handle, C.byref(code))
            facts = json.loads(result.read_text()) if result.exists() else None
            shutdown_lines = (case / 'packaged-main-tray-stderr.log').read_text(encoding='utf-8-sig').splitlines()
            stages = [line.split()[-1] for line in shutdown_lines if line.startswith('CCB_SHUTDOWN ')]
            terminal = ['CLOSE_REQUESTED', 'COMPOSE_RETURNED', 'TASK_DRAIN_BEGIN', 'TASK_DRAIN_END',
                        'DRAFT_DRAIN_BEGIN', 'DRAFT_DRAIN_END', 'BACKUP_CLOSE_BEGIN', 'BACKUP_CLOSE_END',
                        'COORDINATOR_CLOSE_BEGIN', 'COORDINATOR_CLOSE_END', 'MIGRATION_CLOSE_BEGIN',
                        'MIGRATION_CLOSE_END', 'CONTAINER_CLOSED', 'PICKERS_CLOSED', 'OWNERSHIP_RELEASED',
                        'MAIN_RETURNED', 'SAFE_EXIT_HANDOFF']
            receipt = dict(kind='PACKAGED_CLASS_MAIN_TRAY_AND_NORMAL_EXIT',source=commit,version=manifest['version'],jobAssignedBeforeResume=True,
                           exitCode=code.value,remaining=remaining,main=facts,ordinaryLauncher=False,bundledRuntime=False,osMouseGesture=False,
                           realUserData=False,agentInjected=False,providerCalls=False,
                           originalSettingsSha256=hashlib.sha256(original).hexdigest() if original is not None else None,
                           companionSourceSha256=hashlib.sha256(companion.read_bytes()).hexdigest(),
                           driverSourceSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                           originalLauncherConfigSha256=hashlib.sha256(cfg.read_bytes()).hexdigest(),
                           originalJavaOptionsAndClasspathPreserved=True,
                           originalShutdownStages=stages, productionMainReturnTraceObserved=stages == terminal,
                           originalExitProcessUnmodified=True,
                           currentPointerUnchanged=(root / 'current.txt').read_text().strip() == current)
            native.save(case / 'packaged-main-tray-lifecycle.json', receipt)
            native.require(not remaining and code.value == 0 and facts is not None and facts.get('passed') is True and
                           facts.get('ciJdk17') is True and facts.get('source') == commit and
                           facts.get('version') == manifest['version'] and
                           Path(facts['javaHome']).resolve() == native.JAVA.parent.parent.resolve() and
                           Path(facts['mainClassOrigin']).resolve().is_relative_to((package / 'app').resolve()) and
                           stages == terminal and receipt['currentPointerUnchanged'], 'PACKAGED_MAIN_TRAY_NOT_CONFIRMED')
    finally:
        if process.handle: kernel.CloseHandle(process.handle)
        kernel.CloseHandle(job)


if __name__ == '__main__':
    main()
