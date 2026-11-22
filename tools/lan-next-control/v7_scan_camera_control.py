#!/usr/bin/env python3
"""Attach-only V7 real ScanContract observation. Never installs or seeds an app."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

PACKAGE = 'com.example.chatbar.lansyncnext'
MAIN = PACKAGE + '/com.example.chatbar.MainActivity'
CAPTURE = PACKAGE + '/com.journeyapps.barcodescanner.CaptureActivity'
APK_SHA = 'ac8e1b84c6cc0327eb7c2dd5ccb4c2cab594298040939b68966d0379632ad7e0'
INVALID_ERROR = '加入设备组：SYNC_GROUP_INVITE_OFFER'
SCAN = '扫描设备组邀请'
JOIN_MARKERS = ('扫描更新邀请', '更新邀请并继续', '继续加入')
BOUNDS = re.compile(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
PERMISSION_PACKAGES = ('com.android.packageinstaller','com.google.android.packageinstaller',
                       'com.android.permissioncontroller','com.google.android.permissioncontroller')
PERMISSION_LABELS = ('ALLOW','Allow','允许','While using the app','Allow only while using the app',
                     '使用该应用时','仅在使用该应用时')

class Refused(Exception):
    pass

def need(value, code):
    if not value:
        raise Refused(code)

def tree(raw):
    # uiautomator's informational suffix is ignored; no XML is retained.
    start, end = raw.find('<hierarchy'), raw.rfind('</hierarchy>')
    need(start >= 0 and end > start, 'UI_XML_UNAVAILABLE')
    try:
        return ET.fromstring(raw[start:end + len('</hierarchy>')])
    except ET.ParseError:
        raise Refused('UI_XML_INVALID') from None

def node_labels(node, attr=None):
    fields = (attr,) if attr else ('text', 'content-desc')
    values = set()
    for field in fields:
        value = node.get(field, '').strip()
        values.add(value)
        values.update(part.strip() for part in value.split(', '))
    return values

def labels(root):
    return set().union(*(node_labels(node) for node in root.iter('node')))

def bounds(node):
    match = BOUNDS.fullmatch(node.get('bounds', ''))
    if match:
        x1,y1,x2,y2 = map(int,match.groups())
        if x2 > x1 and y2 > y1:
            return x1,y1,x2,y2
    return None

def click_target(root, label, attr=None, packages=(PACKAGE,)):
    parents = {c:p for p in root.iter() for c in p}
    candidates = set()
    for node in root.iter('node'):
        if node.get('package') not in packages:
            continue
        if label not in node_labels(node, attr):
            continue
        while node is not None:
            if node.get('enabled') == 'false':
                break
            if node.get('clickable') == 'true':
                rectangle = bounds(node)
                if node.get('enabled') == 'true' and rectangle:
                    candidates.add(rectangle)
                break
            node = parents.get(node)
    need(len(candidates) <= 1, 'UI_TARGET_AMBIGUOUS')
    return next(iter(candidates), None)

def state(root):
    values = labels(root)
    return {
        'invalidError': INVALID_ERROR in values,
        'joining': any(x in values for x in JOIN_MARKERS),
        'empty': '创建新设备组' in values and SCAN in values,
        'cameraError': bool(values.intersection({'Camera error', 'Sorry, the Android camera encountered a problem. You may need to restart the device.', '摄像头出现问题。'})),
        'otherSyncError': any(re.search(r'(?:SYNC|STORE)_[A-Z0-9_]+', x) and x != INVALID_ERROR for x in values),
    }

class Adb:
    def __init__(self, path, serial):
        self.prefix = [str(path), '-s', serial]
        self.calls = 0

    def run(self, *args, timeout=20):
        self.calls += 1
        try:
            p = subprocess.run(self.prefix + list(args), capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            raise Refused('ADB_TIMEOUT') from None
        except OSError:
            raise Refused('ADB_LAUNCH_FAILED') from None
        need(p.returncode == 0, 'ADB_COMMAND_FAILED')
        need(len(p.stdout) <= 3*1024*1024 and len(p.stderr) <= 128*1024, 'ADB_OUTPUT_LIMIT')
        return p.stdout.decode('utf-8', errors='replace')

    def ui(self):
        # A screen transition can interrupt the accessibility snapshot. Retry
        # only the read, never the action that caused the transition.
        for attempt in range(3):
            try:
                return self.ui_once()
            except Refused as error:
                if str(error) not in ('UI_XML_UNAVAILABLE','UI_XML_INVALID','ADB_COMMAND_FAILED') or attempt == 2:
                    raise
                time.sleep(.35)

    def ui_once(self):
        # No PTY or /dev/tty assumption on API26. This unique device-only file is
        # read into memory, then removed before parsing or returning anything.
        path = '/data/local/tmp/ccb-v7-scan-' + uuid.uuid4().hex + '.xml'
        try:
            self.run('shell', 'uiautomator', 'dump', path)
            raw = self.run('exec-out', 'cat', path)
        finally:
            try:
                self.run('shell', 'rm', '-f', path)
            except Refused:
                raise Refused('UI_TEMP_CLEANUP_FAILED') from None
        return tree(raw)

    def events(self):
        # Read-only snapshots, never clear the device log. Old entries cannot qualify.
        try:
            raw = self.run('logcat','-d','-b','events','-v','threadtime',
                           '-s','am_create_activity:I','am_resume_activity:I',
                           'wm_create_activity:I','wm_resume_activity:I')
            return set(raw.splitlines())
        except Refused:
            return None

    def decoder_events(self):
        try:
            raw = self.run('logcat','-d','-b','main','-v','threadtime','-s','DecoderThread:D')
            return {line for line in raw.splitlines() if re.search(r'Found barcode in [0-9]+ ms',line)}
        except Refused:
            return None

    def tap(self, bounds):
        x1,y1,x2,y2 = bounds
        self.run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))

def target_state(root, label):
    known = [node for node in root.iter('node') if label in node_labels(node)]
    return dict(labelSeen=bool(known),
                appLabelSeen=any(node.get('package') == PACKAGE for node in known),
                enabledAppLabelSeen=any(node.get('package') == PACKAGE and node.get('enabled') == 'true' for node in known),
                appLabelBoundsSeen=any(node.get('package') == PACKAGE and bounds(node) for node in known))

def wait_click(adb, label, deadline, attr=None, *, result=None, phase=None):
    if result is not None and phase:
        result['navigationPhase'] = phase
    previous = None
    while time.monotonic() < deadline:
        root = adb.ui()
        current = click_target(root, label, attr)
        if result is not None and phase:
            result['navigationTarget'] = target_state(root,label)
        if current is not None and previous is not None and all(abs(a-b) <= 5 for a,b in zip(current,previous)):
            adb.tap(current)
            return
        previous = current
        time.sleep(.35)
    raise Refused('UI_TARGET_NOT_READY')

def open_group_page(adb, result, deadline):
    result['navigationPhase'] = 'wait-main'
    while time.monotonic() < deadline:
        root = adb.ui()
        values = labels(root)
        result['startupSurface'] = {
            'appNodes': any(node.get('package') == PACKAGE for node in root.iter('node')),
            'systemUiNodes': any(node.get('package') == 'com.android.systemui' for node in root.iter('node')),
            'loadingLabel': 'ChatBar' in values,
            'tutorialTitle': '基础教程' in values,
            'updateTitle': '发现新版本' in values,
            'manageLabel': '管理' in values,
            'groupTitle': '设备组同步' in values,
        }
        if '发现新版本' in values and '稍后' in values and not result.get('updateDeferredThroughUi'):
            wait_click(adb,'稍后',min(deadline,time.monotonic()+12),result=result,phase='defer-update')
            result['updateDeferredThroughUi'] = True
            # The dialog may have covered the earlier tutorial tap. Continue
            # only if a fresh snapshot still shows that exact tutorial.
            result['basicTutorialExitAttempted'] = False
            continue
        if '设备组同步' in values or '管理' in values:
            break
        if '基础教程' in values and '选择教程' not in values and not result.get('basicTutorialExitAttempted'):
            result['basicTutorialObserved'] = True
            result['basicTutorialExitAttempted'] = True
            result['navigationPhase'] = 'tutorial-exit'
            skip = click_target(root, '跳过')
            result['navigationTarget'] = target_state(root, '跳过')
            if skip:
                adb.tap(skip)
                result['basicTutorialSkippedThroughUi'] = True
            else:
                # Compose on this first tutorial page handles BACK as onExit.
                # Use the same snapshot that proved the tutorial was foreground.
                adb.run('shell','input','keyevent','KEYCODE_BACK')
                result['basicTutorialBackUsed'] = True
            continue
        time.sleep(.3)
    else:
        focus = adb.run('shell','dumpsys','activity','activities')
        result['mainWasResumedAtDeadline'] = any(MAIN in line for line in focus.splitlines() if 'mResumedActivity' in line or 'topResumedActivity' in line)
        try:
            result['appPidPresentAtDeadline'] = bool(adb.run('shell','pidof',PACKAGE).strip())
        except Refused:
            result['appPidPresentAtDeadline'] = False
        raise Refused('MAIN_UI_NOT_READY')
    if '设备组同步' not in labels(root):
        if click_target(root, '局域网同步') is None:
            wait_click(adb, '管理', deadline, result=result, phase='manage-tab')
        wait_click(adb, '局域网同步', deadline, result=result, phase='open-sync')

def navigate(adb, result, deadline):
    open_group_page(adb, result, deadline)
    result['navigationPhase'] = 'wait-empty-group'
    while time.monotonic() < deadline:
        root = adb.ui()
        s = state(root)
        need(not s['invalidError'] and not s['otherSyncError'], 'PREEXISTING_UI_ERROR')
        need(not s['joining'], 'PREEXISTING_JOIN_PROGRESS')
        if s['empty'] and click_target(root, SCAN) is not None:
            result['initialEmptyGroupUiObserved'] = True
            result['navigationPhase'] = 'empty-group-ready'
            return
        if '邀请设备' in labels(root) or '解锁管理' in labels(root):
            raise Refused('EXISTING_GROUP_REFUSED')
        time.sleep(.35)
    raise Refused('EMPTY_GROUP_UI_NOT_READY')

def observe_capture_activity(adb, before, result):
    after = adb.events()
    result['eventSnapshotAfterAvailable'] = after is not None
    observed = None if before is None or after is None else any(
        PACKAGE in event and CAPTURE.split('/')[-1] in event for event in after-before)
    result['newCaptureActivityEventObserved'] = observed
    return observed

def observe(args, result):
    need(re.fullmatch(r'emulator-[0-9]+', args.serial) is not None, 'EMULATOR_SERIAL_REQUIRED')
    a = Adb(args.adb, args.serial)
    need(a.run('shell','getprop','ro.kernel.qemu').strip() == '1', 'EMULATOR_REQUIRED')
    need(a.run('shell','getprop','ro.build.version.sdk').strip() == str(args.expected_api), 'API_MISMATCH')
    package = a.run('shell','dumpsys','package',PACKAGE)
    need(re.search(r'\bversionCode=86\b',package) is not None, 'VERSION86_REQUIRED')
    paths = a.run('shell','pm','path',PACKAGE).splitlines()
    need(len(paths) == 1 and paths[0].startswith('package:/data/app/') and paths[0].endswith('/base.apk'), 'SINGLE_INSTALLED_BASE_APK_REQUIRED')
    digest = a.run('shell','toybox','sha256sum',paths[0][8:]).split()
    need(digest and digest[0] == APK_SHA, 'INSTALLED_V7_APK_HASH_MISMATCH')
    result['installedExactV7ApkObserved'] = True
    result['api'] = args.expected_api
    # Only the normal exported entry is launched. The private LanSyncActivity is reached by UI.
    a.run('shell','am','start','-W','-n',MAIN,timeout=30)
    result['mainLaunchCommandAccepted'] = True
    navigate(a,result,time.monotonic()+45)
    before = a.events()
    decoder_before = a.decoder_events()
    result['eventSnapshotBeforeAvailable'] = before is not None
    wait_click(a, SCAN, time.monotonic()+20)
    result['physicalScanButtonClicks'] = 1
    permission_clicked = False
    deadline = time.monotonic()+args.scan_timeout
    while time.monotonic() < deadline:
        root = a.ui()
        values = labels(root)
        # Permission UI is constrained to an actual system permission package.
        if not permission_clicked:
            for label in PERMISSION_LABELS:
                nodes = [n for n in root.iter('node') if n.get('package') in PERMISSION_PACKAGES and
                         label in node_labels(n)]
                if nodes:
                    target = click_target(root,label,packages=PERMISSION_PACKAGES)
                    need(target is not None,'CAMERA_PERMISSION_NOT_CLICKABLE')
                    a.tap(target);permission_clicked = True
                    result['cameraPermissionUiAccepted'] = True
                    break
        result['scannerPromptObserved'] = result.get('scannerPromptObserved',False) or '扫描设备组邀请二维码' in values
        s = state(root)
        need(not s['cameraError'], 'CAMERA_UI_ERROR')
        need(not s['otherSyncError'], 'OTHER_SYNC_ERROR_OBSERVED')
        callback = s['invalidError'] if args.expect == 'invalid' else s['joining']
        if callback:
            if args.expect == 'invalid':need(s['empty'] and not s['joining'], 'INVALID_CODE_CREATED_JOIN_PROGRESS')
            observed = observe_capture_activity(a,before,result)
            result['expectedCallbackUiObserved'] = True
            result['newInvalidOfferErrorObserved'] = s['invalidError']
            result['newJoiningUiObserved'] = s['joining']
            result['adbCalls'] = a.calls
            # Unknown event visibility is preserved; never expanded to a camera proof PASS.
            result['status'] = ('INVALID_QR_SCAN_CALLBACK_OBSERVED' if args.expect == 'invalid' else
                                'INVITATION_QR_SCAN_JOINING_UI_OBSERVED') if observed is True else 'CALLBACK_UI_OBSERVED_CAPTURE_EVENT_UNPROVEN'
            return
        if args.expect == 'invitation' and s['invalidError']:
            raise Refused('INVITATION_REJECTED_INVALID_OFFER')
        time.sleep(.3)
    observe_capture_activity(a,before,result)
    decoder_after = a.decoder_events()
    result['newDecoderSuccessLogObserved'] = (
        None if decoder_before is None or decoder_after is None else bool(decoder_after - decoder_before))
    activities = a.run('shell','dumpsys','activity','activities')
    result['scannerResumedAtTimeout'] = any(
        CAPTURE in line for line in activities.splitlines()
        if 'mResumedActivity' in line or 'topResumedActivity' in line)
    raise Refused('SCAN_CALLBACK_NOT_OBSERVED')

def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb',required=True,type=Path)
    p.add_argument('--serial',required=True)
    p.add_argument('--expected-api',type=int,choices=(26,36),default=26)
    p.add_argument('--expect',choices=('invalid','invitation'),default='invalid')
    p.add_argument('--scan-timeout',type=int,default=45)
    p.add_argument('--result',required=True,type=Path)
    args=p.parse_args(argv)
    result={'kind':'CCB_V7_REAL_SCANCONTRACT_UI_OBSERVATION_V1','status':'NOT_RUN','errorCode':None,
            'installedExactV7ApkObserved':False,'mainLaunchCommandAccepted':False,'initialEmptyGroupUiObserved':False,
            'basicTutorialSkippedThroughUi':False,
            'physicalScanButtonClicks':0,'cameraPermissionUiAccepted':False,'newCaptureActivityEventObserved':None,
            'eventSnapshotBeforeAvailable':False,'eventSnapshotAfterAvailable':False,'expectedCallbackUiObserved':False,
            'newInvalidOfferErrorObserved':False,'newJoiningUiObserved':False,'cameraImageLaunchBindingVerifiedHere':False,
            'windowsPeerIdentityOrApprovalVerified':False,'lanContentDeliveryVerified':False,'humanAcceptance':False,
            'appInstalledOrClearedHere':False,'directOwnerOrInjectedActivityResultUsed':False}
    try:
        need(5 <= args.scan_timeout <= 60,'SCAN_TIMEOUT_RANGE')
        need(not args.result.exists() and args.result.parent.is_dir(),'NEW_RESULT_PATH_REQUIRED')
        observe(args,result)
        rc=0 if result['newCaptureActivityEventObserved'] is True else 3
    except Refused as e:
        result.update(status='REFUSED',errorCode=str(e));rc=2
    except Exception:
        result.update(status='REFUSED',errorCode='CONTROL_EXCEPTION');rc=2
    # Public receipt is only closed enums, Boolean/null and bounded counters. No raw UI/log/error.
    try:
        with args.result.open('x',encoding='utf-8',newline='\n') as f:
            json.dump(result,f,ensure_ascii=True,indent=2);f.write('\n')
    except OSError:
        print(json.dumps({'status':'REFUSED','errorCode':'RESULT_WRITE_FAILED'}));return 2
    print(json.dumps(result,ensure_ascii=True,sort_keys=True))
    return rc

if __name__ == '__main__':
    raise SystemExit(main())
