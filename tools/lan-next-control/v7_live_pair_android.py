#!/usr/bin/env python3
"""Windows cloud only: normal V7 join UI followed by passive private app reads."""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import time
import uuid

from synthetic_clipboard import clipboard_control
import v7_original86_ui_observation as group_observation

SCAN_SHA='4ea5527a441b06d5fe5d6c4015c3aa4521a6d790c91bc5d798795851a926c568'
SESSION='ccb-v7-lab-session'
MESSAGE='ccb-v7-lab-message'
TEXT='CCB-V7-synthetic-message'
MESSAGE_FILE='files/entities/chat_messages/'+SESSION+'_'+MESSAGE+'.json'

class Refused(Exception):pass

def need(value,code):
    if not value:raise Refused(code)

def unique(pairs):
    value={}
    for k,v in pairs:
        need(k not in value,'DUPLICATE_JSON_KEY');value[k]=v
    return value

def json_read(raw):
    need(len(raw)<=65536,'JSON_LIMIT')
    try:return json.loads(raw.decode('utf-8'),object_pairs_hook=unique)
    except (ValueError,UnicodeError):raise Refused('JSON_INVALID') from None

def offer(raw):
    v=json_read(raw)
    need(isinstance(v,dict) and set(v)=={'kind','code','session','message','text','imageSha256'},'OFFER_FIELDS')
    need(v['kind']=='CCB_V7_LIVE_SYNTHETIC_OFFER' and v['session']==SESSION and v['message']==MESSAGE and v['text']==TEXT,'OFFER_FIXED_SYNTHETIC_SCOPE')
    need(isinstance(v['code'],str) and len(v['code'])<=16384 and re.fullmatch(r'CCB-GROUP-JOIN1:[A-Za-z0-9_-]+',v['code']) is not None,'OFFER_CODE_SHAPE')
    need(isinstance(v['imageSha256'],str) and re.fullmatch(r'[a-f0-9]{64}',v['imageSha256']) is not None,'OFFER_IMAGE_HASH')
    return v

def message(raw):
    v=json_read(raw)
    need(isinstance(v,dict) and v.get('id')==MESSAGE and v.get('sessionId')==SESSION and v.get('content')==TEXT,'MESSAGE_CONTENT_MISMATCH')
    need(v.get('role') in ('USER','ASSISTANT','SYSTEM'),'MESSAGE_ROLE_INVALID')
    need(isinstance(v.get('images'),list) and len(v['images'])==1 and isinstance(v['images'][0],str),'MESSAGE_SINGLE_IMAGE_REQUIRED')
    need(v.get('alternatives',[])==[],'MESSAGE_UNEXPECTED_ALTERNATIVES')
    return v

def app_image(path,files_root):
    need(isinstance(path,str) and '\x00' not in path and '\\' not in path and ':' not in path,'IMAGE_PATH_SCHEME_OR_ESCAPE')
    p=PurePosixPath(path)
    need('..' not in p.parts and all(re.fullmatch(r'[A-Za-z0-9_.-]+',s) for s in p.parts if s!='/'),'IMAGE_PATH_COMPONENT')
    if p.is_absolute():
        roots=(files_root,'/data/user/0/com.example.chatbar.lansyncnext/files','/data/data/com.example.chatbar.lansyncnext/files')
        matches=[r for r in roots if path.startswith(r+'/images/')]
        need(bool(matches),'IMAGE_PATH_OUTSIDE_APP')
        relative='images/'+path.split('/images/',1)[1]
    else:
        relative=path[6:] if path.startswith('files/') else path
        need(relative.startswith('images/'),'IMAGE_PATH_OUTSIDE_APP')
    need(relative not in ('images/','images/.') and len(relative)<=512,'IMAGE_PATH_INVALID')
    return files_root+'/'+relative

def owned_private_paths(args):
    root=Path(os.environ.get('RUNNER_TEMP','')).resolve(strict=True)
    lab=root/'ccb-v7-live'
    for p,name in [(args.offer,'live-offer.json'),(args.ready,'android-ready.json'),(args.readback,'android-readback.json')]:
        need(p.name==name and p.resolve().parent==lab.resolve(),'PRIVATE_LAB_PATH_REQUIRED')
    need(lab.is_dir() and lab.resolve().is_relative_to(root),'PRIVATE_LAB_ROOT_REQUIRED')
    need(not any(p.exists() for p in (args.offer,args.ready,args.readback,args.result)),'NEW_PROTOCOL_FILES_REQUIRED')
    need(args.result.parent.is_dir() and args.result.resolve().parent!=lab.resolve(),'SEPARATE_SAFE_OUTPUT_REQUIRED')

def publish(path,value):
    need(not path.exists(),'PROTOCOL_FILE_ALREADY_EXISTS')
    temp=path.parent/(path.name+'.'+uuid.uuid4().hex+'.tmp')
    with temp.open('x',encoding='utf-8',newline='\n') as f:
        json.dump(value,f,ensure_ascii=True,separators=(',',':'));f.write('\n');f.flush();os.fsync(f.fileno())
    need(not path.exists(),'PROTOCOL_FILE_ALREADY_EXISTS')
    os.replace(temp,path)

def checkpoint(args,result):
    path=args.result.with_name('android-progress.json')
    temp=path.with_name(path.name+'.'+uuid.uuid4().hex+'.tmp')
    message={key:result.get(key) for key in (
        'phase','offerContractAccepted','joinFieldFocused','joinButtonPhysicalClicks',
        'joiningUiObserved','readyUiObserved','otherSyncErrorObserved',
        'expectedInvitationCountObserved','androidClipboard','exactMessageContentObserved',
        'appOwnedImageSha256Matched','readbackPublished','submitControl','groupUiObservation')}
    message['kind']='CCB_V7_ANDROID_LIVE_PROGRESS'
    with temp.open('x',encoding='utf-8',newline='\n') as stream:
        json.dump(message,stream,ensure_ascii=True)
        stream.write('\n')
    os.replace(temp,path)

def scan_module():
    p=Path(__file__).resolve().with_name('v7_scan_camera_control.py')
    need(p.is_file() and not p.is_symlink() and hashlib.sha256(p.read_bytes()).hexdigest()==SCAN_SHA,'FROZEN_SCAN_HELPER_REQUIRED')
    spec=importlib.util.spec_from_file_location('v7_live_scan_dependency',p);mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
    return mod

def read_as_app(a,*args,missing_ok=False):
    # Pass each fixed command and known app path directly. A missing file is
    # distinct from a failed or unexpectedly populated remote command.
    need(all(re.fullmatch(r'[A-Za-z0-9_./%-]+', arg) for arg in args),'RUN_AS_ARGUMENT')
    try:
        p=subprocess.run(a.prefix+['shell','-T','run-as',a.package,*args],capture_output=True,timeout=15)
    except subprocess.TimeoutExpired:raise Refused('RUN_AS_TIMEOUT') from None
    except OSError:raise Refused('RUN_AS_LAUNCH_FAILED') from None
    need(len(p.stdout)<=65536 and len(p.stderr)<=4096,'RUN_AS_OUTPUT_LIMIT')
    if missing_ok and p.returncode==1 and not p.stdout and p.stderr.startswith(b'stat:') and b'No such file or directory' in p.stderr:
        return None
    need(p.returncode==0 and not p.stderr.strip(),'RUN_AS_READ_FAILED')
    return p.stdout

def read_image_proof(a,ui,path,r):
    # run-as already enters this package's private cwd. API26 cannot necessarily
    # canonicalize its protected ancestors, so never readlink -f an absolute path.
    r['phase']='READ_PRIVATE_FILES_ROOT'
    raw=read_as_app(a,'pwd')
    roots=('/data/user/0/'+ui.PACKAGE,'/data/data/'+ui.PACKAGE)
    try:app_root=raw.decode('ascii').strip()
    except UnicodeError:raise Refused('APP_PRIVATE_CWD_INVALID') from None
    need(app_root in roots,'APP_PRIVATE_CWD_MISMATCH')
    r['privateAppCwdObserved']=True
    files_root=app_root+'/files'
    image=app_image(path,files_root)
    relative=image[len(app_root)+1:]
    parts=PurePosixPath(relative).parts
    need(3<=len(parts)<=16 and parts[:2]==('files','images') and
         str(PurePosixPath(relative))==relative and all(x not in ('.','..') for x in parts),'IMAGE_RELATIVE_PATH_INVALID')
    prefixes=['.']+['/'.join(parts[:i]) for i in range(1,len(parts)+1)]
    def inspect():
        identities=[]
        for i,prefix in enumerate(prefixes):
            # API26 Toybox stat defaults to lstat; -L is deliberately absent.
            value=read_as_app(a,'toybox','stat','-c','%f/%s/%d/%i',prefix)
            match=re.fullmatch(rb'([a-fA-F0-9]{1,8})/([0-9]{1,20})/([0-9]{1,20})/([0-9]{1,20})\s*',value)
            need(match is not None,'IMAGE_LSTAT_METADATA_INVALID')
            mode=int(match[1],16);size,device,inode=(int(match[j]) for j in (2,3,4))
            need(mode&0xf000==(0x8000 if i==len(prefixes)-1 else 0x4000),'IMAGE_PATH_SYMLINK_OR_WRONG_TYPE')
            need(inode>0,'IMAGE_LSTAT_IDENTITY_INVALID')
            identities.append((mode,size if i==len(prefixes)-1 else None,device,inode))
        need(0<identities[-1][1]<=4*1024*1024,'SMALL_IMAGE_SIZE_LIMIT')
        return identities
    before=inspect();r['imagePathComponentsVerified']=len(parts);r['imageNoSymlinkPathObserved']=True
    r['phase']='READ_IMAGE'
    digest=read_as_app(a,'toybox','sha256sum',relative)
    match=re.fullmatch(rb'([a-f0-9]{64})  '+re.escape(relative.encode('ascii'))+rb'\r?\n?',digest)
    need(match is not None,'IMAGE_HASH_OUTPUT_INVALID')
    after=inspect();need(before==after,'IMAGE_PATH_CHANGED_DURING_READ')
    r['imageReadIdentityStable']=True
    return match[1].decode('ascii'),before[-1][1]

def editable(root,package,focused=False):
    found=[]
    for n in root.iter('node'):
        if n.get('package')==package and n.get('class')=='android.widget.EditText' and n.get('enabled')=='true' and n.get('password')=='true':
            if focused and n.get('focused')!='true':
                continue
            m=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.get('bounds',''))
            if m:
                b=tuple(map(int,m.groups()))
                if b[2]>b[0] and b[3]>b[1]:found.append(b)
    need(len(found)<=1,'SINGLE_SECURE_JOIN_INPUT_REQUIRED')
    return found[0] if found else None

def reveal(a,ui,target):
    for attempt in range(4):
        root=a.ui()
        candidate=target(root)
        if candidate:
            return candidate
        if attempt==3:
            break
        viewports=[ui.bounds(node) for node in root.iter('node')
                   if node.get('package')==a.package and node.get('scrollable')=='true']
        viewports=[b for b in viewports if b and b[3]-b[1]>=200]
        need(viewports,'GROUP_SCROLL_VIEW_REQUIRED')
        x1,y1,x2,y2=max(viewports,key=lambda b:(b[2]-b[0])*(b[3]-b[1]))
        x=(x1+x2)//2
        h=y2-y1
        a.run('shell','input','swipe',str(x),str(y1+h*3//4),str(x),str(y1+h//4),'300')
        time.sleep(.4)
    raise Refused('JOIN_CONTROL_NOT_VISIBLE')

JOIN_FIELD_LABEL = '加入已有设备组 · 粘贴邀请'
JOIN_SUBMIT_LABEL = '检查并加入'


def join_form_labels(root,ui):
    rectangles = []
    for label in (JOIN_FIELD_LABEL, JOIN_SUBMIT_LABEL):
        candidates = {ui.bounds(node) for node in root.iter('node')
                      if node.get('package') == ui.PACKAGE and label in ui.node_labels(node)
                      and ui.bounds(node)}
        if len(candidates) != 1:
            return None
        rectangles.append(candidates.pop())
    field_label, submit_button = rectangles
    if submit_button[1] - field_label[3] < 30:
        return None
    return field_label, submit_button


def labeled_join_field(root,ui):
    labels = join_form_labels(root,ui)
    if labels is None:
        return None
    exposed = editable(root,ui.PACKAGE)
    if exposed:
        return exposed
    field_label, submit_button = labels
    x = (field_label[0] + field_label[2]) // 2
    y = (field_label[3] + submit_button[1]) // 2
    return x - 1, y - 1, x + 1, y + 1


def scoped_invitation_count(root,ui):
    labels = join_form_labels(root,ui)
    if labels is None:
        return None
    field_label, submit_button = labels
    counts = set()
    for node in root.iter('node'):
        box = ui.bounds(node)
        if node.get('package') != ui.PACKAGE or not box:
            continue
        if box[1] < field_label[3] or box[3] > submit_button[1]:
            continue
        for value in ui.node_labels(node):
            match = re.fullmatch(r'([0-9]{1,5})字', value)
            if match:
                counts.add(int(match[1]))
    return next(iter(counts)) if len(counts) == 1 else None


def resolve_invitation_target(adb,field,ui,root=None):
    need(field == 'invitation','INVITATION_FIELD_REQUIRED')
    root = adb.ui() if root is None else root
    rectangle = labeled_join_field(root,ui)
    need(rectangle is not None,'INVITATION_FORM_LABELS_NOT_OBSERVED')
    visible = [node for node in root.iter('node')
               if node.get('package') == ui.PACKAGE and node.get('class') == 'android.widget.EditText'
               and node.get('password') == 'true' and ui.bounds(node) == rectangle]
    need(len(visible) <= 1,'INVITATION_EDITTEXT_AMBIGUOUS')
    text = visible[0].get('text','') if visible else None
    count = scoped_invitation_count(root,ui)
    empty = count == 0 if count is not None else not bool(text) if visible else None
    mode = 'EDITTEXT' if visible else 'LABEL_BUTTON_RECTANGLES'
    return clipboard_control.FieldTarget(rectangle, mode, ui.PACKAGE, True, True, empty, text)


def join_field_focused(a,ui):
    tree=a.ui()
    if editable(tree,ui.PACKAGE,focused=True):
        return True
    if '加入已有设备组 · 粘贴邀请' not in ui.labels(tree):
        return False
    ime=a.run('shell','dumpsys','input_method')
    return bool(re.search(r'\bmInputShown=true\b',ime) and 'packageName='+ui.PACKAGE in ime)

def execute(args,r):
    need(os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('RUNNER_OS')=='Windows','WINDOWS_GITHUB_RUNNER_REQUIRED')
    need(os.environ.get('CCB_V7_CLEAN_ANDROID_INSTALL')=='true','FRESH_INSTALL_PATH_REQUIRED')
    need(5<=args.offer_timeout<=1200 and 5<=args.delivery_timeout<=180,'TIMEOUT_RANGE')
    owned_private_paths(args);ui=scan_module()
    need(re.fullmatch(r'emulator-[0-9]+',args.serial) is not None,'EMULATOR_SERIAL_REQUIRED')
    a=ui.Adb(args.adb,args.serial);a.package=ui.PACKAGE
    need(a.run('shell','getprop','ro.kernel.qemu').strip()=='1' and a.run('shell','getprop','ro.build.version.sdk').strip()=='26','API26_EMULATOR_REQUIRED')
    pkg=a.run('shell','dumpsys','package',ui.PACKAGE)
    need(re.search(r'\bversionCode=86\b',pkg) is not None,'VERSION86_REQUIRED')
    paths=a.run('shell','pm','path',ui.PACKAGE).splitlines()
    need(len(paths)==1 and paths[0].startswith('package:/data/app/') and paths[0].endswith('/base.apk'),'SINGLE_INSTALLED_APK_REQUIRED')
    need(a.run('shell','toybox','sha256sum',paths[0][8:]).split()[0]==ui.APK_SHA,'INSTALLED_V7_APK_HASH_MISMATCH')
    r['exactApkAndApi26Observed']=True
    need(read_as_app(a,'toybox','stat','-c','%s',MESSAGE_FILE,missing_ok=True) is None,'SYNTHETIC_MESSAGE_ALREADY_EXISTS')
    r['syntheticMessageAbsentBeforeJoin']=True
    a.run('shell','am','start','-W','-n',ui.MAIN,timeout=30)
    nav={'initialEmptyGroupUiObserved':False,'basicTutorialSkippedThroughUi':False}
    try:
        ui.navigate(a,nav,time.monotonic()+65)
    finally:
        r['navigationPhase']=nav.get('navigationPhase')
        r['navigationTarget']=nav.get('navigationTarget')
    r['emptyGroupUiObserved']=nav['initialEmptyGroupUiObserved'];r['tutorialSkippedThroughUi']=nav['basicTutorialSkippedThroughUi']
    r['phase']='LOCATE_JOIN_INPUT'
    field=reveal(a,ui,lambda root:labeled_join_field(root,ui))
    a.tap(field)
    deadline=time.monotonic()+12
    while time.monotonic()<deadline and not join_field_focused(a,ui):
        time.sleep(.3)
    need(time.monotonic()<deadline,'JOIN_FIELD_NOT_FOCUSED')
    r['joinFieldFocused']=True
    clipboard = clipboard_control.Bridge(args.adb, args.serial, args.helper_build, 26)
    clipboard_result = clipboard_control.safe_result('paste')
    r['androidClipboard'] = clipboard_result
    r['phase'] = 'INSTALL_CLIPBOARD_HELPER'
    clipboard.install(clipboard_result)
    try:
        run_pairing(args, r, a, ui, clipboard, clipboard_result)
    finally:
        if clipboard_result['helperInstalled'] and (clipboard_result['helperForegroundRoundTripMatched']
                or clipboard_result['phase'] in ('FOREGROUND_HELPER','RETURN_TO_ORIGINAL_CCB','ORDINARY_PASTE_MENU','VERIFY_WIDGET_TEXT')):
            cleanup = clipboard_control.safe_result('clear')
            try:
                clipboard.operation('clear', 'none', b'', cleanup)
                r['clipboardClearedAfterAttempt'] = cleanup['clipboardClearedHere']
            except clipboard_control.Refused:
                r['clipboardClearedAfterAttempt'] = False
            if r.get('readbackPublished'):
                need(r['clipboardClearedAfterAttempt'],'SYSTEM_CLIPBOARD_CLEANUP_UNOBSERVED')



def run_pairing(args,r,a,ui,clipboard,clipboard_result):
    r['phase']='WAIT_OFFER'
    publish(args.ready,{'kind':'CCB_V7_ANDROID_READY','ready':True})
    checkpoint(args,r)
    deadline=time.monotonic()+args.offer_timeout
    while not args.offer.exists() and time.monotonic()<deadline:time.sleep(.5)
    need(args.offer.is_file() and not args.offer.is_symlink(),'OFFER_NOT_OBSERVED')
    v=offer(args.offer.read_bytes());r['offerContractAccepted']=True;r['phase']='INPUT_INVITATION'
    checkpoint(args,r)
    need(join_field_focused(a,ui),'JOIN_FIELD_LOST_FOCUS')
    need(scoped_invitation_count(a.ui(),ui) in (None,0),'JOIN_FIELD_NOT_EMPTY')
    payload = bytearray(v['code'], 'ascii')
    try:
        paste_status = clipboard.paste_bytes(
            payload, 'invitation', 'invitation', ui.APK_SHA, clipboard_result,
            field_resolver=lambda adb,field:resolve_invitation_target(adb,field,ui),
            length_reader=lambda adb,field,target:scoped_invitation_count(adb.ui(),ui),
            allow_unobservable_empty=True,
            use_paste_key=True,
        )
    finally:
        payload[:] = bytes(len(payload))
    r['expectedInvitationCountObserved'] = clipboard_result['widgetCharactersMatched'] is True
    need(paste_status in (0,3),'SYSTEM_PASTE_NOT_OBSERVED')
    r['navigationPhase']='submit-join'
    r['phase']='SUBMIT_INVITATION'
    checkpoint(args,r)
    clipboard_control.ordinary_submit_once(a,ui,'invitation',v['code'],
        lambda root:(resolve_invitation_target(None,'invitation',ui,root),scoped_invitation_count(root,ui)),r)
    r['joinButtonPhysicalClicks']=1;r['phase']='WAIT_JOIN_AND_AUTOMATIC_RECEIVE'
    checkpoint(args,r)
    observation_started=time.monotonic();observer=group_observation.Observer()
    r['groupUiObservation']=observer.summary()
    deadline=time.monotonic()+args.delivery_timeout
    while time.monotonic()<deadline:
        root=a.ui();s=ui.state(root);values=ui.labels(root)
        group_observation.sample(a,observer,root,'BEFORE_READBACK',observation_started)
        r['groupUiObservation']=observer.summary()
        if join_form_labels(root,ui) is not None:
            target=resolve_invitation_target(None,'invitation',ui,root)
            if clipboard_control.submit_field_cleared(target.text,target.mode,scoped_invitation_count(root,ui)) is True:
                clipboard_control.note_submit_consumption(r,'INPUT_CLEARED')
        if s['invalidError']:
            clipboard_control.note_submit_consumption(r,'PRODUCT_ERROR_UI')
            clipboard.observe_product_submission('invitation',lambda adb,field:'PRODUCT_REJECTED_UI_OBSERVED',clipboard_result)
            raise Refused('INVITATION_INVALID_OFFER')
        if s['joining'] and not r['joiningUiObserved']:
            clipboard_control.note_submit_consumption(r,'JOINING_UI')
            clipboard.observe_product_submission('invitation',lambda adb,field:'JOINING_UI_OBSERVED',clipboard_result)
        updated=s['joining'] and not r['joiningUiObserved'] or s['otherSyncError'] and not r['otherSyncErrorObserved']
        ready={'立即检查','网盘设置'}.issubset(values)
        if ready:clipboard_control.note_submit_consumption(r,'READY_UI')
        if s['otherSyncError']:clipboard_control.note_submit_consumption(r,'PRODUCT_ERROR_UI')
        updated=updated or ready and not r['readyUiObserved']
        r['joiningUiObserved']=r['joiningUiObserved'] or s['joining']
        r['otherSyncErrorObserved']=r['otherSyncErrorObserved'] or s['otherSyncError']
        r['readyUiObserved']=r['readyUiObserved'] or ready
        if updated:
            checkpoint(args,r)
        if read_as_app(a,'toybox','stat','-c','%s',MESSAGE_FILE,missing_ok=True) is None:
            time.sleep(1)
            continue
        raw=read_as_app(a,'cat',MESSAGE_FILE)
        r['phase']='READ_MESSAGE';m=message(raw);r['exactMessageContentObserved']=True
        actual,n=read_image_proof(a,ui,m['images'][0],r)
        need(actual==v['imageSha256'],'IMAGE_HASH_MISMATCH')
        r['appOwnedImageSha256Matched']=True;r['imageBytes']=n
        # Publish only after values have been obtained from actual app storage.
        publish(args.readback,{'kind':'CCB_V7_ANDROID_READBACK','session':m['sessionId'],'message':m['id'],'text':m['content'],'imageSha256':actual,'received':True})
        r['readbackPublished']=True;r['phase']='DONE';r['status']='EXPECTED_APP_MESSAGE_AND_IMAGE_OBSERVED'
        observer.data=True
        # Readback/ACK and data PASS above stay independent of this optional UI witness.
        group_observation.after_readback(a,observer,observation_started)
        r['groupUiObservation']=observer.summary()
        return
    if r.get('submitControl',{}).get('productConsumed') is not True:
        r['submitControl']['productConsumed']=False
        raise Refused('JOIN_SUBMIT_CONSUMPTION_NOT_OBSERVED')
    raise Refused('AUTOMATIC_APP_MESSAGE_NOT_OBSERVED')


def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--adb',type=Path,required=True);p.add_argument('--serial',required=True)
    for name in ('offer','ready','readback','result'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--helper-build',type=Path,required=True)
    p.add_argument('--offer-timeout',type=int,default=1200);p.add_argument('--delivery-timeout',type=int,default=180);args=p.parse_args(argv)
    r={'kind':'CCB_V7_ANDROID_LIVE_SAFE_RESULT','status':'NOT_RUN','phase':'PRECHECK','errorCode':None,
       'exactApkAndApi26Observed':False,'syntheticMessageAbsentBeforeJoin':False,'emptyGroupUiObserved':False,'tutorialSkippedThroughUi':False,
       'offerContractAccepted':False,'joinButtonPhysicalClicks':0,'joiningUiObserved':False,'readyUiObserved':False,'otherSyncErrorObserved':False,
       'exactMessageContentObserved':False,'appOwnedImageSha256Matched':False,'readbackPublished':False,'imageBytes':None,
       'scannerCameraPathTestedHere':False,'manualSyncOrOwnerProbeUsed':False,'directAppStorageWriteOrReseedByController':False,'humanAcceptance':False}
    try:execute(args,r);rc=0
    except Exception as e:
        # Foreign helper errors are closed enums; never copy native messages/cause.
        known=isinstance(e,Refused) or (type(e).__name__=='Refused' and re.fullmatch(r'[A-Z0-9_]{1,100}',str(e)) is not None)
        r.update(status='REFUSED',errorCode=str(e) if known else 'CONTROL_EXCEPTION');rc=2
    try:
        with args.result.open('x',encoding='utf-8',newline='\n') as f:json.dump(r,f,ensure_ascii=True,indent=2);f.write('\n')
    except OSError:print(json.dumps({'status':'REFUSED','errorCode':'RESULT_WRITE_FAILED'}));return 2
    print(json.dumps(r,sort_keys=True));return rc

if __name__=='__main__':raise SystemExit(main())
