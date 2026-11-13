"""Windows cloud-only, original package Program swap; never builds or touches user roots."""
import base64
import ctypes as C
from ctypes import wintypes as W
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request
import uuid
import zipfile

REQUEST = Path('.github/lan-desktop-profile-upgrade-request.json')
OUTPUT = Path('profile-upgrade-evidence')
PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAgAAAAGCAYAAAD+Bd/7AAAAFklEQVR4XmMwTjvzHx9mQBdAx4NBAQAgqnLBPosE1gAAAABJRU5ErkJggg==')


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding='utf8')


def regular(path):
    require(not path.is_symlink() and not path.stat().st_file_attributes & 0x400, 'REPARSE_REFUSED')


def provenance(request):
    api = 'https://api.github.com/repos/MisakaPiano/ChatChatBar-Desktop/actions/'
    def get(path):
        headers = {'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json'}
        return json.load(urllib.request.urlopen(urllib.request.Request(api + path, headers=headers)))
    values = []
    for row in request['packages']:
        run = get('runs/' + str(row['runId']))
        artifact = get('artifacts/' + str(row['artifactId']))
        require(run['conclusion'] == 'success', 'ORIGINAL_RUN_NOT_SUCCESS')
        require(run['head_sha'] == artifact['workflow_run']['head_sha'] == row['controlSha'], 'ORIGINAL_CONTROL')
        require(artifact['workflow_run']['id'] == row['runId'] and not artifact['expired'], 'ORIGINAL_RUN')
        require(artifact['name'] == 'ccb-lan-next-independent-desktop', 'ORIGINAL_NAME')
        require(artifact['size_in_bytes'] == row['outerBytes'] and artifact['digest'] == 'sha256:' + row['outerSha256'], 'ORIGINAL_DIGEST')
        values.append({'package': row, 'artifact': artifact})
    write_json(OUTPUT/'original-provenance.json', {'controlSha': os.environ['GITHUB_SHA'], 'packages': values})


def package(row, root):
    directory = Path('input-' + row['series'])
    archives = list(directory.rglob(row['filename']))
    audits = list(directory.rglob('app-image-audit.json'))
    require(len(archives) == len(audits) == 1, 'ORIGINAL_INPUT_COUNT')
    require(sha(archives[0]) == row['innerSha256'] and archives[0].stat().st_size == row['innerBytes'], 'INNER_DIGEST')
    audit = json.loads(audits[0].read_text(encoding='utf8'))
    require(audit['identity']['commit'] == row['sourceSha'] and audit['identity']['version'] == row['version'], 'IMAGE_SOURCE')
    root.mkdir()
    with zipfile.ZipFile(archives[0]) as z:
        names = z.namelist()
        expected = {'Program/' + f['path'] for f in audit['files']} | {'启动独立资料版.cmd', '使用说明.txt', 'UserData/'}
        require(len(names) == len(set(n.casefold() for n in names)) == len(expected) and set(names) == expected, 'ARCHIVE_NAMES')
        require(z.testzip() is None and z.getinfo('UserData/').file_size == 0, 'ARCHIVE_CRC_OR_PROFILE')
        for item in z.infolist():
            require((root/item.filename).resolve().is_relative_to(root.resolve()), 'ARCHIVE_ESCAPE')
            require((item.external_attr >> 16) & 0o170000 != 0o120000, 'ARCHIVE_LINK')
        z.extractall(root)
    for f in audit['files']:
        p = root/'Program'/f['path']; regular(p)
        require(p.stat().st_size == f['bytes'] and sha(p) == f['sha256'], 'PROGRAM_BYTES')
    return len(audit['files'])


class Native:
    def __init__(self, root):
        self.root = root
        self.prefix = str((root/'Program').resolve()).lower() + os.sep
        self.kernel = C.WinDLL('kernel32', use_last_error=True)
        self.user = C.WinDLL('user32', use_last_error=True)
        self.psapi = C.WinDLL('psapi')
        self.kernel.OpenProcess.restype = W.HANDLE
        self.kernel.OpenProcess.argtypes = [W.DWORD, W.BOOL, W.DWORD]
        self.kernel.CloseHandle.argtypes = [W.HANDLE]
        self.kernel.QueryFullProcessImageNameW.argtypes = [W.HANDLE, W.DWORD, W.LPWSTR, C.POINTER(W.DWORD)]
        self.callback = C.WINFUNCTYPE(W.BOOL, W.HWND, W.LPARAM)
        self.user.EnumWindows.argtypes = [self.callback, W.LPARAM]
        self.user.IsWindowVisible.argtypes = [W.HWND]
        self.user.GetWindowThreadProcessId.argtypes = [W.HWND, C.POINTER(W.DWORD)]
        self.user.GetWindowTextW.argtypes = [W.HWND, W.LPWSTR, C.c_int]
        self.user.PostMessageW.argtypes = [W.HWND, W.UINT, W.WPARAM, W.LPARAM]

    def owned(self):
        ids = (W.DWORD * 4096)(); used = W.DWORD()
        require(self.psapi.EnumProcesses(ids, C.sizeof(ids), C.byref(used)), 'ENUM_PROCESSES')
        result = []
        for pid in ids[:used.value // C.sizeof(W.DWORD)]:
            handle = self.kernel.OpenProcess(0x1000, False, pid)
            if not handle:
                continue
            try:
                text = C.create_unicode_buffer(32768); size = W.DWORD(len(text))
                if self.kernel.QueryFullProcessImageNameW(handle, 0, text, C.byref(size)) and text.value.lower().startswith(self.prefix):
                    result.append(pid)
            finally:
                self.kernel.CloseHandle(handle)
        return result

    def windows(self):
        ids = set(self.owned()); result = []
        @self.callback
        def visit(hwnd, unused):
            pid = W.DWORD(); self.user.GetWindowThreadProcessId(hwnd, C.byref(pid))
            if pid.value in ids and self.user.IsWindowVisible(hwnd):
                title = C.create_unicode_buffer(256); self.user.GetWindowTextW(hwnd, title, 256)
                if title.value == 'ChatChatBar':
                    result.append(hwnd)
            return True
        self.user.EnumWindows(visit, 0)
        return result

    def launch(self, stage, environment):
        import msvcrt
        lock = self.root/'UserData/Local/ChatChatBarDesktop/.ccb-desktop.lock'
        def held():
            if not lock.is_file():
                return False
            require(lock.stat().st_size == 0, 'ROOT_LOCK_NOT_EMPTY')
            with lock.open('r+b') as f:
                try:
                    msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
                except OSError as e:
                    require(e.errno in (13, 36) or getattr(e, 'winerror', None) in (32, 33), 'ROOT_LOCK_UNEXPECTED_ERROR')
                    return True
                msvcrt.locking(f.fileno(), msvcrt.LK_UNLCK, 1)
                return False
        require(not self.owned() and not held(), 'PRIOR_PROCESS_OR_ROOT_LOCK')
        p = subprocess.run([os.environ.get('COMSPEC', 'C:/Windows/System32/cmd.exe'), '/d', '/c', str(self.root/'启动独立资料版.cmd')],
                           cwd=self.root, env=environment, creationflags=subprocess.CREATE_NO_WINDOW,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
        require(p.returncode == 0, 'LAUNCHER_FAILED')
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            windows = self.windows()
            if len(windows) == 1 and held():
                break
            time.sleep(.25)
        require(len(windows) == 1 and held(), 'WINDOW_AND_ACTUAL_ROOT_LOCK_REQUIRED')
        time.sleep(3)  # Observe a live native window before requesting an ordinary close.
        require(len(self.windows()) == 1 and held(), 'NATIVE_WINDOW_OR_ROOT_LOST')
        require(self.user.PostMessageW(windows[0], 0x10, 0, 0), 'NORMAL_CLOSE_FAILED')
        deadline = time.monotonic() + 60
        while self.owned() and time.monotonic() < deadline:
            time.sleep(.25)
        require(not self.owned() and not held(), 'PROCESS_OR_LOCK_NOT_RELEASED')
        return {'stage': stage, 'visibleMainWindow': True, 'actualSameRootByteLockObserved': True,
                'normalClose': True, 'programProcessesDrained': True, 'lockReleased': True}


def seed(data):
    image = data/'images/profile-upgrade.png'; image.parent.mkdir(parents=True, exist_ok=True); image.write_bytes(PNG)
    card = 'profile-upgrade-card'; session = 'profile-upgrade-session'; message = 'profile-upgrade-message'
    write_json(data/f'entities/character_cards/{card}.json', {'id': card, 'name': '合成电脑升级角色', 'avatar': str(image), 'basicSetting': '合成原文\n保留第二行', 'momentsEnabled': False, 'createdAt': 1, 'updatedAt': 1})
    write_json(data/f'entities/chat_sessions/{session}.json', {'id': session, 'characterCardId': card, 'title': '合成升级会话', 'longTermMemoryEnabled': False, 'automaticImageGenerationEnabled': False, 'createdAt': 1, 'updatedAt': 1})
    write_json(data/f'entities/chat_messages/{session}_{message}.json', {'id': message, 'sessionId': session, 'role': 'USER', 'content': '合成升级消息\n逐字保留', 'images': [str(image)], 'createdAt': 1, 'updatedAt': 1, 'orderKey': 1000000})
    write_json(data/'entities/player_setting.json', {'id': 'profile-player', 'playerName': '合成玩家', 'globalPersona': '原设定保全', 'updatedAt': 1, 'desktopAvatar': str(image)})
    draft = {'basePrompt': '合成当前工作室原文\n不调用生成', 'negativePrompt': '合成负面原文', 'contentRevision': 0, 'promptContentRevision': 0, 'updatedAt': 0}
    previous = dict(draft, basePrompt='合成工作室上一步')
    write_json(data/'entities/novelai_studio_draft.json', draft)
    write_json(data/'entities/novelai_studio_history_undo.json', {'draft': previous})
    write_json(data/'entities/desktop_studio_edit_history.json', {'committed': {'entries': [previous, draft], 'cursor': 1}, 'pending': None})
    return image


def inventory(directory):
    return {str(p.relative_to(directory)).replace('\\', '/'): sha(p) for p in directory.rglob('*') if p.is_file()}


def json_bodies(directory, paths):
    values = {}
    for path in paths:
        if path.endswith('.json'):
            values[path] = json.loads((directory/path).read_text(encoding='utf8'))
    return values


def changes(before, after, bodies, directory):
    result = []
    for path in sorted(set(before) | set(after)):
        if before.get(path) == after.get(path):
            continue
        row = {'path': path, 'beforeSha256': before.get(path), 'afterSha256': after.get(path)}
        if path in bodies and path in after:
            current = json.loads((directory/path).read_text(encoding='utf8'))
            previous = bodies[path]
            if isinstance(current, dict) and isinstance(previous, dict):
                row['changedTopLevelFields'] = [k for k in sorted(set(previous) | set(current)) if previous.get(k) != current.get(k)]
        result.append(row)
    return result


def verify_seed(data, image):
    def read(name):
        return json.loads((data/'entities'/name).read_text(encoding='utf8'))
    card = read('character_cards/profile-upgrade-card.json')
    require(card['name'] == '合成电脑升级角色' and card['avatar'] == str(image) and card['basicSetting'] == '合成原文\n保留第二行', 'CARD_OR_ABSOLUTE_REFERENCE_CHANGED')
    session = read('chat_sessions/profile-upgrade-session.json')
    require(session['characterCardId'] == card['id'] and session['title'] == '合成升级会话', 'SESSION_CHANGED')
    message = read('chat_messages/profile-upgrade-session_profile-upgrade-message.json')
    require(message['sessionId'] == session['id'] and message['content'] == '合成升级消息\n逐字保留' and message['images'] == [str(image)], 'MESSAGE_OR_MEDIA_CHANGED')
    player = read('player_setting.json')
    require(player['id'] == 'profile-player' and player['globalPersona'] == '原设定保全' and player['desktopAvatar'] == str(image), 'PLAYER_CHANGED')
    draft = read('novelai_studio_draft.json'); undo = read('novelai_studio_history_undo.json'); journal = read('desktop_studio_edit_history.json')
    require(draft['basePrompt'] == '合成当前工作室原文\n不调用生成' and draft['negativePrompt'] == '合成负面原文', 'STUDIO_DRAFT_CHANGED')
    require(undo['draft']['basePrompt'] == '合成工作室上一步' and journal['pending'] is None and journal['committed']['cursor'] == 1, 'STUDIO_UNDO_CHANGED')
    require(journal['committed']['entries'][1]['basePrompt'] == draft['basePrompt'], 'STUDIO_JOURNAL_CHANGED')


def main(request):
    require(os.name == 'nt' and os.environ.get('GITHUB_ACTIONS') == 'true', 'WINDOWS_CLOUD_ONLY')
    temporary = Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
    case = temporary/('ccb-lan-profile-' + uuid.uuid4().hex); case.mkdir(); regular(case)
    require(case.resolve() == case and case.parent == temporary, 'CASE_SCOPE')
    root = case/'independent'; replacement = case/'replacement'
    counts = [package(row, dest) for row, dest in zip(request['packages'], [root, replacement])]
    require((root/'启动独立资料版.cmd').read_bytes() == (replacement/'启动独立资料版.cmd').read_bytes(), 'LAUNCHER_MISMATCH')
    outside = case/'outside-profile'; environment = os.environ.copy()
    for key, leaf in [('LOCALAPPDATA', 'Local'), ('APPDATA', 'Roaming'), ('TEMP', 'Temp'), ('TMP', 'Temp')]:
        path = outside/leaf; path.mkdir(parents=True, exist_ok=True); environment[key] = str(path)
    bootstrap = outside/'Local/ChatChatBarDesktop.bootstrap.json'
    write_json(bootstrap, {'formatVersion': 1, 'mode': 'DEFAULT'})
    outside_before = inventory(outside)
    native = Native(root); phases = []
    result = {'kind': 'EXACT_LAN2_LAN3_PROGRAM_ONLY_PROFILE_UPGRADE', 'passed': False, 'packages': request['packages'],
              'programFilesVerified': counts, 'phases': phases, 'providerCalls': False, 'actualUserRootTouched': False,
              'applicationRebuilt': False, 'pixelsVerified': False, 'peerOrIdentitySeeded': False,
              'processDrainScope': 'EXECUTABLE_PATH_UNDER_OWN_PROGRAM', 'externalExecutableDescendantsVerified': False}
    try:
        phases.append(native.launch('lan2-fresh', environment))
        data = root/'UserData/Local/ChatChatBarDesktop'; require(data.is_dir(), 'INITIAL_DATA_ROOT')
        image = seed(data)
        phases.append(native.launch('lan2-populated', environment))
        verify_seed(data, image)
        control_before = inventory(data/'entities'); control_bodies = json_bodies(data/'entities', control_before)
        phases.append(native.launch('lan2-warm-control', environment))
        verify_seed(data, image)
        before = inventory(data/'entities'); bodies = json_bodies(data/'entities', before); image_before = sha(image)
        result['oldWarmControlChanges'] = changes(control_before, before, control_bodies, data/'entities')
        result['baselineEntityFileCount'] = len(before)
        require(json.loads((data/'entities/character_cards/profile-upgrade-card.json').read_text(encoding='utf8'))['avatar'] == str(image), 'SEED_ABSOLUTE_PATH')
        backup = root/'Program-lan2-preserved'
        for p in [root, root/'Program', replacement/'Program']:
            regular(p); require(p.resolve().is_relative_to(case), 'PROGRAM_SWAP_SCOPE')
        require(not backup.exists() and not native.owned(), 'PROGRAM_SWAP_BUSY')
        (root/'Program').rename(backup); (replacement/'Program').rename(root/'Program')
        phases.append(native.launch('lan3-upgraded', environment))
        verify_seed(data, image)
        result['upgradedSyntheticSemanticChecksPassed'] = True
        after = inventory(data/'entities')
        result['upgradedEntityChanges'] = changes(before, after, bodies, data/'entities')
        require(all(after.get(path) == digest for path, digest in before.items()), 'PREEXISTING_ENTITY_BYTES_CHANGED')
        phases.append(native.launch('lan3-cold-reopen', environment))
        cold = inventory(data/'entities')
        result['coldEntityChanges'] = changes(before, cold, bodies, data/'entities')
        require(all(cold.get(path) == digest for path, digest in before.items()), 'COLD_ENTITY_BYTES_CHANGED')
        require(sha(image) == image_before == hashlib.sha256(PNG).hexdigest(), 'MEDIA_CHANGED')
        verify_seed(data, image)
        require(inventory(outside) == outside_before, 'OUTSIDE_PROFILE_CHANGED')
        result.update(passed=True, preexistingEntityFilesPreserved=len(before), entityHashesBefore=before,
                      newEntityPaths=sorted(set(cold)-set(before)), absoluteMediaReferenceUnchanged=True,
                      imageSha256=image_before, userDataRootNotMoved=True, oldProgramPreserved=True,
                      outsideProfileInventoryUnchanged=True, syntheticOnly=True)
    except Exception as failure:
        result['failure'] = {'type': type(failure).__name__, 'message': str(failure)}
        raise
    finally:
        write_json(OUTPUT/'native-upgrade-result.json', result)
    print(json.dumps({'passed': result['passed'], 'nativeLaunches': len(phases), 'preservedEntities': result.get('preexistingEntityFilesPreserved')}))


if __name__ == '__main__':
    require(os.environ.get('GITHUB_REPOSITORY') == 'MisakaPiano/ChatChatBar-Desktop', 'REPOSITORY_SCOPE')
    request = json.loads(REQUEST.read_text(encoding='utf8'))
    require(request['kind'] == 'CCB_EXACT_DELIVERED_DESKTOP_PROFILE_UPGRADE', 'REQUEST_KIND')
    require(len(request['packages']) == 2 and [r['series'] for r in request['packages']] == ['lan2', 'lan3'], 'TWO_ORDERED_PACKAGES_REQUIRED')
    require([r['version'] for r in request['packages']] == ['0.1.0-lan.2', '0.1.0-lan.3'], 'FROZEN_VERSIONS_REQUIRED')
    OUTPUT.mkdir(exist_ok=True)
    write_json(OUTPUT/'source-request.json', request)
    if '--provenance-only' in sys.argv:
        provenance(request)
    else:
        main(request)
