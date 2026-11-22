#!/usr/bin/env python3
"""Exact signed85 create/save -> install-r86 -> cold original-code unlock UI."""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import time

PACKAGE='com.example.chatbar.lansyncnext'
BASE_SHA='cd4434719afbffac6ef22858d6499319600aa8c96f6a80b1781c4574fb1504f7'
CANDIDATE_SHA='ac8e1b84c6cc0327eb7c2dd5ccb4c2cab594298040939b68966d0379632ad7e0'
CODE=re.compile(r'CCB-MGMT1\.[A-Za-z0-9_-]{16,16384}')

class Refused(Exception):pass
def need(value,code):
    if not value:raise Refused(code)

def file_hash(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return h.hexdigest()

def load_ui(digest):
    # The workflow pins its current shared UI helper; this adapter does not
    # freeze an obsolete navigation revision or accept a pin from app input.
    need(re.fullmatch(r'[a-f0-9]{64}',digest) is not None,'EXACT_SHARED_UI_PIN_REQUIRED')
    p=Path(__file__).resolve().with_name('v7_scan_camera_control.py')
    need(p.is_file() and not p.is_symlink() and file_hash(p)==digest,'SHARED_UI_HELPER_MISMATCH')
    spec=importlib.util.spec_from_file_location('v7_recovery_shared_ui',p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

def recovery_code(root,package=PACKAGE):
    values={n.get(k,'') for n in root.iter('node') if n.get('package')==package for k in ('text','content-desc')}
    found={x for x in values if CODE.fullmatch(x)}
    need(len(found)<=1,'RECOVERY_CODE_AMBIGUOUS')
    return next(iter(found),None)

def checked_state(root):
    parents={c:p for p in root.iter() for c in p};values=[]
    for n in root.iter('node'):
        if n.get('package')!=PACKAGE or '已妥善保存恢复码' not in (n.get('text',''),n.get('content-desc','')):continue
        while n is not None:
            if n.get('checkable')=='true':
                need(n.get('enabled')=='true','SAVE_CHECKBOX_DISABLED')
                need(n.get('checked') in ('true','false'),'SAVE_CHECKBOX_STATE_UNKNOWN')
                values.append(n.get('checked')=='true');break
            n=parents.get(n)
    need(len(values)<=1,'SAVE_CHECKBOX_AMBIGUOUS')
    return values[0] if values else None

def secure_input_node(root):
    found=[]
    for n in root.iter('node'):
        if n.get('package')==PACKAGE and n.get('class')=='android.widget.EditText' and n.get('enabled')=='true' and n.get('password')=='true':
            m=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.get('bounds',''))
            if m:
                b=tuple(map(int,m.groups()))
                if b[2]>b[0] and b[3]>b[1]:found.append((n,b))
    need(len(found)==1,'SINGLE_RECOVERY_INPUT_REQUIRED')
    return found[0]

def secure_input(root):
    return secure_input_node(root)[1]

def type_recovery_code(a,ui,root,secret,r):
    a.tap(secure_input(root))
    focus_deadline=time.monotonic()+12
    while time.monotonic()<focus_deadline:
        root=a.ui()
        field,_=secure_input_node(root)
        if field.get('focused')=='true':
            r['coldRecoveryInputFocused']=True
            break
        time.sleep(.3)
    need(r.get('coldRecoveryInputFocused',False),'COLD_RECOVERY_INPUT_NOT_FOCUSED')
    # Feed the same code to the focused field in small batches. Avoid dismissing
    # the keyboard with Back while the Activity is restoring its scroll position.
    for start in range(0,len(secret),48):
        a.run('shell','input','text',secret[start:start+48].decode('ascii'),timeout=20)
        time.sleep(.15)
    r['originalCodeTypedCount']=1
    root=a.ui()
    values=ui.labels(root)
    r['sameGroupPageAfterInput']='设备组同步' in values
    field,_=secure_input_node(root)
    r['secureTextLengthMatched']=len(field.get('text',''))==len(secret)
    r['unlockLabelSeenAfterInput']='解锁管理' in values
    need(r['sameGroupPageAfterInput'],'GROUP_PAGE_LOST_AFTER_INPUT')

def outer_scroll(root):
    found=[]
    for n in root.iter('node'):
        if n.get('package')==PACKAGE and n.get('scrollable')=='true' and n.get('enabled')=='true':
            m=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.get('bounds',''))
            if m:
                b=tuple(map(int,m.groups()));h=b[3]-b[1];w=b[2]-b[0]
                if h>=300 and w>0:found.append((h*w,b))
    need(bool(found),'VISIBLE_SCROLL_VIEW_REQUIRED');found.sort(reverse=True)
    need(len(found)==1 or found[0][0]!=found[1][0],'SCROLL_VIEW_AMBIGUOUS')
    return found[0][1]

def visible_label(root,label):
    for n in root.iter('node'):
        if n.get('package')==PACKAGE and label in (n.get('text',''),n.get('content-desc','')):
            m=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.get('bounds',''))
            if m:
                x1,y1,x2,y2=map(int,m.groups())
                if x2>x1 and y2>y1:return True
    return False

def scroll_body(a,root,r):
    x1,y1,x2,y2=outer_scroll(root)
    x=(x1+x2)//2
    height=y2-y1
    a.run('shell','input','swipe',str(x),str(y1+height*3//4),str(x),str(y1+height//4),'300')
    r['boundedBodySwipes']+=1

def show_target(a,ui,label,r,require_enabled=True):
    # Four bounded normal swipes in the largest visible app scroll viewport.
    for i in range(5):
        root=a.ui()
        ready=(ui.click_target(root,label) is not None) if require_enabled else visible_label(root,label)
        if ready:return root
        if i==4:break
        scroll_body(a,root,r)
        time.sleep(.4)
    raise Refused('UI_TARGET_NOT_DISPLAYED')

def entry(a,ui,r):
    a.run('shell','am','start','-W','-n',ui.MAIN,timeout=30)
    deadline=time.monotonic()+60
    nav={}
    try:
        ui.open_group_page(a,nav,deadline)
    finally:
        r['navigationPhase']=nav.get('navigationPhase')
        r['startupSurface']=nav.get('startupSurface')
        r['navigationTarget']=nav.get('navigationTarget')
        r['knownUpdateDeferredThroughUi']=r['knownUpdateDeferredThroughUi'] or nav.get('updateDeferredThroughUi',False)
        r['tutorialSkippedThroughUi']=r['tutorialSkippedThroughUi'] or nav.get('basicTutorialSkippedThroughUi',False)
        r['tutorialBackUsed']=r.get('tutorialBackUsed',False) or nav.get('basicTutorialBackUsed',False)
    while time.monotonic()<deadline:
        if '设备组同步' in ui.labels(a.ui()):
            r['navigationPhase']='GROUP_PAGE'
            return
        time.sleep(.3)
    raise Refused('GROUP_UI_NOT_READY')


def installed(a,version,sha):
    need(re.search(r'\bversionCode='+str(version)+r'\b',a.run('shell','dumpsys','package',PACKAGE)) is not None,'INSTALLED_VERSION_MISMATCH')
    paths=a.run('shell','pm','path',PACKAGE).splitlines()
    need(len(paths)==1 and paths[0].startswith('package:/data/app/') and paths[0].endswith('/base.apk'),'SINGLE_BASE_APK_REQUIRED')
    digest=a.run('shell','toybox','sha256sum',paths[0][8:]).split();need(digest and digest[0]==sha,'INSTALLED_APK_HASH_MISMATCH')

def pid(a):
    try:p=subprocess.run(a.prefix+['shell','pidof',PACKAGE],capture_output=True,timeout=10)
    except Exception:raise Refused('PID_READ_FAILED') from None
    need(p.returncode in (0,1) and not p.stderr.strip(),'PID_READ_FAILED')
    values=p.stdout.decode('ascii').split();need(len(values)<=1 and all(re.fullmatch(r'[1-9][0-9]*',x) for x in values),'SINGLE_APP_PID_REQUIRED')
    return values[0] if values else None

def wait_ready(a,ui,r):
    deadline=time.monotonic()+90
    next_scroll=time.monotonic()+5
    scrolls=0
    while time.monotonic()<deadline:
        root=a.ui();v=ui.labels(root)
        need(not any(re.search(r'(?:SYNC|STORE)_[A-Z0-9_]+',x) for x in v),'GROUP_UI_ERROR_OBSERVED')
        r['lastCreateUi']={
            'completeButton': '完成创建' in v,
            'lockedReadyLabel': '恢复码 · 临时解锁管理' in v,
            'cloudSettingsButton': '网盘设置' in v,
            'checkNowButton': '立即检查' in v,
            'groupTitle': '设备组同步' in v,
        }
        if {'恢复码 · 临时解锁管理','解锁管理'}.issubset(v) or {'网盘设置','立即检查'}.issubset(v):
            return
        if scrolls<4 and time.monotonic()>=next_scroll and any(
            n.get('package')==PACKAGE and n.get('scrollable')=='true' for n in root.iter('node')
        ):
            scroll_body(a,root,r)
            scrolls+=1
            next_scroll=time.monotonic()+3
        time.sleep(.4)
    raise Refused('READY_UI_NOT_OBSERVED')

def execute(args,r):
    need(os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('RUNNER_OS') in ('Windows','Linux'),'GITHUB_WINDOWS_OR_LINUX_ONLY')
    need(re.fullmatch(r'emulator-[0-9]+',args.serial) is not None,'EMULATOR_SERIAL_REQUIRED')
    need(not args.result.exists() and args.result.parent.is_dir(),'NEW_RESULT_REQUIRED')
    temp=Path(os.environ.get('RUNNER_TEMP','')).resolve(strict=True);lab=temp/'ccb-v7-scan'
    for p,name,sha,size in [(args.base_apk,'base85.apk',BASE_SHA,109631063),(args.candidate_apk,'candidate86.apk',CANDIDATE_SHA,109663831)]:
        need(p.is_file() and not p.is_symlink() and p.name==name and p.resolve().parent==lab.resolve() and p.resolve().is_relative_to(temp),'EXACT_PRIVATE_APK_PATH_REQUIRED')
        need(p.stat().st_size==size and file_hash(p)==sha,'EXACT_ORIGINAL_SIGNED_APK_REQUIRED')
    ui=load_ui(args.ui_helper_sha256);a=ui.Adb(args.adb,args.serial)
    need(a.run('shell','getprop','ro.kernel.qemu').strip()=='1' and a.run('shell','getprop','ro.build.version.sdk').strip()=='26','API26_EMULATOR_REQUIRED')
    need(not a.run('shell','pm','list','packages','com.example.chatbar').strip(),'FRESH_NO_CCB_PACKAGE_REQUIRED')
    r['phase']='INSTALL_BASE85';need(re.search(r'^Success\s*$',a.run('install',str(args.base_apk),timeout=180),re.M) is not None,'BASE_INSTALL_FAILED');installed(a,85,BASE_SHA);r['exactBase85Installed']=True
    r['phase']='CREATE_GROUP85';entry(a,ui,r);show_target(a,ui,'创建新设备组',r);ui.wait_click(a,'创建新设备组',time.monotonic()+20);r['createButtonClicks']=1
    secret=None
    try:
        deadline=time.monotonic()+90
        while time.monotonic()<deadline:
            root=a.ui();value=recovery_code(root)
            if value is not None:
                secret=bytearray(value.encode('ascii'));value=None;root=None;break
            need(not any(re.search(r'(?:SYNC|STORE)_[A-Z0-9_]+',x) for x in ui.labels(root)),'CREATE_UI_ERROR_OBSERVED');time.sleep(.4)
        need(secret is not None,'ORIGINAL85_RECOVERY_CODE_NOT_OBSERVED');r['original85CodeCapturedFromUiInMemory']=True
        r['phase']='SAVE_ORIGINAL_CODE85';root=show_target(a,ui,'已妥善保存恢复码',r);need(checked_state(root) is False,'SAVE_CHECKBOX_NOT_OFF');r['saveCheckboxOffObserved']=True
        ui.wait_click(a,'已妥善保存恢复码',time.monotonic()+15);r['saveCheckboxClicks']=1
        deadline=time.monotonic()+15
        while time.monotonic()<deadline:
            if checked_state(a.ui()) is True:r['saveCheckboxOnObserved']=True;break
            time.sleep(.3)
        need(r['saveCheckboxOnObserved'],'SAVE_CHECKBOX_ON_NOT_OBSERVED')
        show_target(a,ui,'完成创建',r);ui.wait_click(a,'完成创建',time.monotonic()+20);r['completeCreateClicks']=1
        wait_ready(a,ui,r);r['normal85CreationCompletedUi']=True;old=pid(a);need(old is not None,'BASE85_PID_NOT_OBSERVED')
        r['phase']='INSTALL_PRESERVING86';need(re.search(r'^Success\s*$',a.run('install','-r',str(args.candidate_apk),timeout=180),re.M) is not None,'PRESERVING_INSTALL_FAILED');installed(a,86,CANDIDATE_SHA);r['exactCandidate86InstalledWithR']=True
        r['phase']='COLD_REOPEN86';a.run('shell','am','force-stop',PACKAGE);need(pid(a) is None,'FORCE_STOP_NOT_OBSERVED');r['forceStopObserved']=True
        entry(a,ui,r);new=pid(a);need(new is not None and new!=old,'DIFFERENT_COLD_PID_NOT_OBSERVED');r['differentColdPidObserved']=True
        show_target(a,ui,'解锁管理',r,require_enabled=False);root=a.ui();need('恢复码 · 临时解锁管理' in ui.labels(root),'ORIGINAL_GROUP_LOCKED_INPUT_NOT_OBSERVED');r['coldManagementLockedUiObserved']=True
        r['phase']='UNLOCK_WITH_ORIGINAL85_CODE'
        type_recovery_code(a,ui,root,secret,r)
        show_target(a,ui,'解锁管理',r);ui.wait_click(a,'解锁管理',time.monotonic()+20);r['unlockButtonClicks']=1
        deadline=time.monotonic()+45
        while time.monotonic()<deadline:
            root=a.ui();need(not any(re.search(r'(?:SYNC|STORE)_[A-Z0-9_]+',x) for x in ui.labels(root)),'ORIGINAL_CODE_UNLOCK_UI_ERROR')
            if ui.click_target(root,'锁定管理') is not None and ui.click_target(root,'邀请设备') is not None:
                r['lockManagementButtonEnabled']=True;r['inviteDeviceButtonEnabled']=True;r['original85CodeUnlocked86']=True;r['phase']='DONE';r['status']='ORIGINAL85_RECOVERY_CODE_COLD_UNLOCK86_UI_OBSERVED';return
            time.sleep(.4)
        raise Refused('UNLOCKED_MANAGEMENT_CONTROLS_NOT_OBSERVED')
    finally:
        if secret is not None:secret[:]=b'\x00'*len(secret)
        secret=None

def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('adb','base-apk','candidate-apk','result'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--serial',required=True);p.add_argument('--ui-helper-sha256',required=True);args=p.parse_args(argv)
    r={'kind':'CCB_ORIGINAL85_TO86_RECOVERY_CODE_UI_SAFE_RESULT','status':'NOT_RUN','phase':'PRECHECK','navigationPhase':None,'errorCode':None,'exactBase85Installed':False,'exactCandidate86InstalledWithR':False,'tutorialSkippedThroughUi':False,'knownUpdateDeferredThroughUi':False,'createButtonClicks':0,'original85CodeCapturedFromUiInMemory':False,'saveCheckboxOffObserved':False,'saveCheckboxClicks':0,'saveCheckboxOnObserved':False,'completeCreateClicks':0,'normal85CreationCompletedUi':False,'forceStopObserved':False,'differentColdPidObserved':False,'coldManagementLockedUiObserved':False,'originalCodeTypedCount':0,'unlockButtonClicks':0,'original85CodeUnlocked86':False,'lockManagementButtonEnabled':False,'inviteDeviceButtonEnabled':False,'boundedBodySwipes':0,'codeWrittenToFileClipboardOrOutput':False,'new86CodeCreatedOrReadInstead':False,'directOwnerOrCompanionUsed':False,'uninstallOrClearDataUsed':False,'inviteActuallyCreated':False,'humanAcceptance':False}
    try:execute(args,r);rc=0
    except Exception as e:
        known=isinstance(e,Refused) or (type(e).__name__=='Refused' and re.fullmatch(r'[A-Z0-9_]{1,100}',str(e)) is not None)
        r.update(status='REFUSED',errorCode=str(e) if known else 'CONTROL_EXCEPTION');rc=2
    try:
        with args.result.open('x',encoding='utf-8',newline='\n') as f:json.dump(r,f,ensure_ascii=True,indent=2);f.write('\n')
    except OSError:print(json.dumps({'status':'REFUSED','errorCode':'RESULT_WRITE_FAILED'}));return 2
    print(json.dumps(r,sort_keys=True));return rc

if __name__=='__main__':raise SystemExit(main())
