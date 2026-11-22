#!/usr/bin/env python3
"""Cloud-only isolated helper install, stdin clipboard write, ordinary UI paste."""
from __future__ import annotations
import argparse
from dataclasses import dataclass, field as private_field
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

HELPER='org.ccb.synthetic.clipboard'
ACTIVITY=HELPER+'/.ClipboardActivity'
TARGET='com.example.chatbar.lansyncnext'
CANDIDATE_CERT='801f54622926f9ca6710086ac2732854a31b546f8ec2fd38cd0c6c11837a1aa7'
INBOX='files/clipboard-inbox.bin'
RESULT='files/clipboard-result.json'
TEMP_RESULT='files/clipboard-result.tmp'
WRITE_SCRIPT='umask 077; mkdir -p files; set -C; cat > files/clipboard-inbox.bin'
FIELD_LABELS={'invitation':'加入已有设备组 · 粘贴邀请',
              'recovery':'恢复码 · 临时解锁管理',
              'prepared-recovery':'为该组保存的恢复码'}
SUBMIT_LABELS={'invitation':'检查并加入','recovery':'解锁管理','prepared-recovery':'用恢复码继续设置'}
HARDWARE_PASTE_TARGET_SHAS=frozenset((
    'cd4434719afbffac6ef22858d6499319600aa8c96f6a80b1781c4574fb1504f7',
    'ac8e1b84c6cc0327eb7c2dd5ccb4c2cab594298040939b68966d0379632ad7e0'))
MASK_CHARACTERS=frozenset('*\u2022\u25cf\u25e6\u2219')
HELPER_ERRORS=frozenset(('FOREGROUND_FOCUS_REQUIRED','OPERATION_ID_INVALID','INBOX_LIMIT','INBOX_FIELDS',
    'OPERATION_MISMATCH','PAYLOAD_SCOPE','INBOX_DELETE_FAILED','FOREGROUND_CLIPBOARD_REQUIRED',
    'CLIPBOARD_ROUNDTRIP_MISMATCH','HELPER_OPERATION_FAILED','STALE_RESULT','RESULT_RENAME_FAILED'))

class Refused(Exception):pass
def need(v,c):
    if not v:raise Refused(c)
def unique(pairs):
    d={}
    for k,v in pairs:
        need(k not in d,'DUPLICATE_JSON_KEY');d[k]=v
    return d
def parse(raw,limit=32768):
    need(len(raw)<=limit,'JSON_LIMIT')
    try:return json.loads(raw.decode('utf-8'),object_pairs_hook=unique)
    except (ValueError,UnicodeError):raise Refused('JSON_INVALID') from None
def payload_bytes(value,kind):
    need(isinstance(value,(bytes,bytearray)) and 1<=len(value)<=16384,'SYNTHETIC_PAYLOAD_LIMIT')
    try:text=bytes(value).decode('ascii')
    except UnicodeError:raise Refused('SYNTHETIC_PAYLOAD_ASCII_ONLY') from None
    prefix={'invitation':'CCB-GROUP-JOIN1:','recovery':'CCB-MGMT1.'}.get(kind)
    need(prefix is not None and text.startswith(prefix) and re.fullmatch(r'[A-Za-z0-9_-]+',text[len(prefix):]) is not None,'SYNTHETIC_PAYLOAD_SHAPE')
    return text
def envelope(operation,action,kind,value=b''):
    need(re.fullmatch(r'[a-f0-9]{32}',operation) is not None,'OPERATION_ID_INVALID')
    text=payload_bytes(value,kind) if action=='put' else ''
    need(action in ('put','clear') and (action=='put' or kind=='none'),'ACTION_INVALID')
    raw=json.dumps({'format':1,'operation':operation,'action':action,'payloadKind':kind,'text':text},ensure_ascii=True,separators=(',',':')).encode()
    need(len(raw)<=32768,'ENVELOPE_LIMIT');return raw
def write_argv(prefix):
    # adb shell concatenates argv; precisely ONE quote layer protects this fixed sh script.
    # exec-out auto-escapes argv and is deliberately not used for the script.
    return prefix+['shell','-T','run-as',HELPER,'sh','-c',shlex.quote(WRITE_SCRIPT)]
def cloud():
    need(os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('RUNNER_OS') in ('Windows','Linux'),'CLOUD_RUNNER_REQUIRED')
def checked_target(path):
    return re.fullmatch(r'/data/app/[A-Za-z0-9_./=+~-]+/base\.apk',path) is not None

class Adb:
    def __init__(self,path,serial):
        need(re.fullmatch(r'emulator-[0-9]+',serial) is not None,'EMULATOR_SERIAL_REQUIRED')
        self.prefix=[str(path),'-s',serial]
    def call(self,args,input_bytes=None,timeout=30):
        try:r=subprocess.run(self.prefix+list(args),input=input_bytes,capture_output=True,timeout=timeout)
        except (OSError,subprocess.SubprocessError):raise Refused('ADB_EXECUTION_FAILED') from None
        need(len(r.stdout)<=3*1024*1024 and len(r.stderr)<=65536,'ADB_OUTPUT_LIMIT')
        return r
    def run(self,*args,timeout=30):
        r=self.call(args,timeout=timeout);need(r.returncode==0,'ADB_COMMAND_FAILED');return r.stdout
    def app(self,*args):
        return self.run('shell','-T','run-as',HELPER,*args)
    def absent(self,path):
        need(path in (INBOX,RESULT,TEMP_RESULT),'FIXED_HELPER_PATH_REQUIRED')
        r=self.call(['shell','-T','run-as',HELPER,'toybox','stat','-c','%s',path])
        if r.returncode==0:return False
        # Device shell-v2 status + exact fixed target + ENOENT; all other errors refuse.
        err=r.stderr.decode('utf-8',errors='replace').strip()
        need(r.returncode==1 and not r.stdout.strip() and path in err and
             'No such file or directory' in err,'HELPER_STAT_FAILED')
        return True
    def cleanup_files(self):
        self.app('rm','-f',INBOX,RESULT,TEMP_RESULT)
        need(all(self.absent(p) for p in (INBOX,RESULT,TEMP_RESULT)),'PRIVATE_FILE_CLEANUP_FAILED')
    def ui(self):
        path='/data/local/tmp/ccb-clipboard-ui-'+uuid.uuid4().hex+'.xml'
        try:
            self.run('shell','-T','uiautomator','dump',path)
            raw=self.run('shell','-T','cat',path)
        finally:self.run('shell','-T','rm','-f',path)
        s=raw.find(b'<hierarchy');e=raw.rfind(b'</hierarchy>')
        need(s>=0 and e>s,'UI_XML_UNAVAILABLE')
        try:return ET.fromstring(raw[s:e+len(b'</hierarchy>')])
        except ET.ParseError:raise Refused('UI_XML_INVALID') from None
    def tap(self,bounds):
        x1,y1,x2,y2=bounds;self.run('shell','-T','input','tap',str((x1+x2)//2),str((y1+y2)//2))

def bounds(n):
    m=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.get('bounds',''))
    if m:
        b=tuple(map(int,m.groups()))
        if b[2]>b[0] and b[3]>b[1]:return b
    return None
def widget(root,field):
    need(field in FIELD_LABELS,'FIELD_ENUM_INVALID')
    labels={n.get(k,'') for n in root.iter('node') if n.get('package')==TARGET for k in ('text','content-desc')}
    need(FIELD_LABELS[field] in labels,'TARGET_FIELD_LABEL_NOT_OBSERVED')
    found=[n for n in root.iter('node') if n.get('package')==TARGET and n.get('class')=='android.widget.EditText' and n.get('enabled')=='true' and bounds(n)]
    need(len(found)==1,'UNIQUE_TARGET_EDITTEXT_REQUIRED');return found[0]
def observation(text,expected):
    if text==expected:return {'representation':'RAW_EXACT','charactersMatched':True,'exactTextMatched':True}
    if text and all(x in MASK_CHARACTERS for x in text):
        return {'representation':'MASKED_LENGTH','charactersMatched':len(text)==len(expected),'exactTextMatched':None}
    if not text:return {'representation':'UNAVAILABLE','charactersMatched':None,'exactTextMatched':None}
    return {'representation':'MISMATCH','charactersMatched':len(text)==len(expected),'exactTextMatched':False}

@dataclass(frozen=True)
class FieldTarget:
    """Fresh UI evidence supplied by a validated caller; text never appears in repr."""
    bounds: tuple
    mode: str
    target_package: str
    label_observed: bool
    submit_button_observed: object
    empty_observed: object
    text: object = private_field(default=None,repr=False)

def validate_field_target(value,require_empty,allow_unobservable_empty):
    need(isinstance(value,FieldTarget),'FIELD_RESOLVER_TYPE')
    need(value.target_package==TARGET and value.label_observed is True,'FIELD_RESOLVER_CONTEXT')
    need(value.mode in ('EDITTEXT','LABEL_BUTTON_RECTANGLES'),'FIELD_RESOLVER_MODE')
    need(value.mode!='LABEL_BUTTON_RECTANGLES' or value.submit_button_observed is True,'FIELD_SUBMIT_RECTANGLE_REQUIRED')
    b=value.bounds
    need(isinstance(b,tuple) and len(b)==4 and all(type(x) is int and 0<=x<=65535 for x in b) and b[2]>b[0] and b[3]>b[1],'FIELD_BOUNDS_INVALID')
    need(value.empty_observed is None or type(value.empty_observed) is bool,'FIELD_EMPTY_STATE_INVALID')
    need(value.text is None or isinstance(value.text,str) and len(value.text)<=16384,'FIELD_TEXT_INVALID')
    if require_empty:
        need(value.empty_observed is not False and not value.text,'TARGET_INPUT_NOT_EMPTY')
        need(value.empty_observed is True or allow_unobservable_empty,'TARGET_EMPTY_UNOBSERVED')
    return value

def length_in_target(root,rectangle):
    """Read only the unique real N字 indicator INSIDE this freshly resolved field."""
    x1,y1,x2,y2=rectangle;found=[]
    for n in root.iter('node'):
        if n.get('package')!=TARGET:continue
        m=re.fullmatch(r'([0-9]{1,5})字',n.get('text',''));b=bounds(n)
        if m and b and x1<=b[0] and y1<=b[1] and b[2]<=x2 and b[3]<=y2:
            found.append(int(m[1]))
    need(len(found)<=1,'FIELD_LENGTH_INDICATOR_AMBIGUOUS')
    if not found:return None
    need(found[0]<=16384,'FIELD_LENGTH_INDICATOR_LIMIT');return found[0]

def length_observation(text,observed_length,expected):
    result=observation(text,expected) if text is not None else observation('',expected)
    need(observed_length is None or type(observed_length) is int and 0<=observed_length<=16384,'UI_LENGTH_READER_INVALID')
    if observed_length is not None:
        need(not text or len(text)==observed_length,'UI_TEXT_AND_LENGTH_CONFLICT')
        if result['representation']=='UNAVAILABLE':
            result={'representation':'UI_LENGTH_ONLY','charactersMatched':observed_length==len(expected),'exactTextMatched':None}
    return result
def paste_target(root):
    parents={c:p for p in root.iter() for c in p};found=set()
    for n in root.iter('node'):
        if n.get('package') not in (TARGET,'android','com.android.systemui'):continue
        if not any(n.get(k,'') in ('PASTE','Paste','粘贴') for k in ('text','content-desc')):continue
        while n is not None:
            if n.get('enabled')=='false':break
            if n.get('clickable')=='true':
                b=bounds(n)
                if b:found.add(b)
                break
            n=parents.get(n)
    need(len(found)<=1,'PASTE_MENU_AMBIGUOUS');return next(iter(found),None)

def focused_target_edittext(root,target):
    x1,y1,x2,y2=target.bounds;found=[]
    for node in root.iter('node'):
        b=bounds(node)
        if (node.get('package')==TARGET and node.get('class')=='android.widget.EditText'
                and node.get('enabled')=='true' and node.get('focused')=='true' and b
                and x1<=b[0] and y1<=b[1] and b[2]<=x2 and b[3]<=y2):found.append(b)
    return len(set(found))==1

def current_ccb_password_ime(raw):
    """API26 CURRENT client only, never a package from start-input history."""
    if "Current input method client doesn't match the last focused" in raw:return False
    headers=list(re.finditer(r'(?m)^Input method client state for [^\n]*:\s*$',raw))
    if len(headers)!=1:return False
    service=raw[:headers[0].start()]
    if re.findall(r'\bmInputShown=(true|false)\b',service)!=['true']:return False
    body=raw[headers[0].end():]
    body=re.split(r'(?m)^Input method (?:client|service) state',body,maxsplit=1)[0]
    if re.search(r'(?m)^\s*mActive=true\b',body) is None:return False
    if re.search(r'(?m)^\s*mServedConnecting=false\s*$',body) is None:return False
    if re.search(r'(?m)^\s*mServedInputConnectionWrapper=(?!null\s*$)\S+',body) is None:return False
    lines=body.splitlines();attributes=[]
    for i,line in enumerate(lines):
        if line.strip()!='mCurrentTextBoxAttribute:':continue
        indent=len(line)-len(line.lstrip());block=[]
        for following in lines[i+1:]:
            if not following.strip():continue
            if len(following)-len(following.lstrip())<=indent:break
            block.append(following)
        attributes.append('\n'.join(block))
    if len(attributes)!=1:return False
    editor=attributes[0]
    if re.search(r'\bpackageName='+re.escape(TARGET)+r'(?=\s|$)',editor) is None:return False
    match=re.search(r'\binputType=0x([0-9a-fA-F]+)\b',editor)
    if match is None:return False
    value=int(match[1],16)
    return value&0xf==1 and value&0xf0 in (0x80,0x90,0xe0)

def submit_disabled_in_context(root,field):
    parents={child:parent for parent in root.iter() for child in parent};found={}
    for node in root.iter('node'):
        if node.get('package')!=TARGET or SUBMIT_LABELS[field] not in (node.get('text',''),node.get('content-desc','')):continue
        rectangle=bounds(node)
        if rectangle is None:continue
        current=node;disabled=False
        while current is not None and current.get('package')==TARGET:
            if current.get('enabled')=='false':disabled=True;break
            if current.get('clickable')=='true' or current.get('class')=='android.widget.Button':break
            current=parents.get(current)
        found[rectangle]=disabled
    return len(found)==1 and next(iter(found.values())) is True

def submit_context(a):
    """Current Group window plus service IME visibility; never input history."""
    def read(*args):
        value=a.run('shell',*args)
        return value.decode('utf-8',errors='replace') if isinstance(value,bytes) else value
    component=TARGET+'/com.example.chatbar.lanSync.LanSyncActivity'
    active=read('dumpsys','activity','activities');window=read('dumpsys','window','windows')
    need(any(component in line for line in active.splitlines() if 'mResumedActivity' in line or 'topResumedActivity' in line)
         and any(component in line for line in window.splitlines() if 'mCurrentFocus=' in line),'SUBMIT_CCB_GROUP_WINDOW_NOT_CURRENT')
    ime=read('dumpsys','input_method')
    values=re.findall(r'\bmInputShown=(true|false)\b',ime.split('Input method client state for ',1)[0])
    return (values[0]=='true' if len(values)==1 else None),ime

def ordinary_submit_once(a,ui,field,expected,field_reader,result):
    """One ordinary tap after positive IME/layout/content checks; no retry."""
    cloud();need(field in ('invitation','recovery'),'SUBMIT_FIELD_INVALID')
    need(isinstance(expected,str) and 0<len(expected)<=16384,'SUBMIT_EXPECTED_INPUT_INVALID')
    need(not result.get('submitControl',{}).get('tapAttempts'),'SUBMIT_ALREADY_ATTEMPTED')
    state={'imeInitiallyShown':None,'imeBackAttempts':0,'imeHiddenBeforeTap':False,
           'imeDisposition':'NOT_CHECKED','fieldCharactersMatched':None,'fieldExactTextMatched':None,
           'fieldRepresentation':'UNAVAILABLE','scopedLengthMatched':None,'freshStableButtonInViewport':False,
           'tapAttempts':0,'tapCommandAccepted':False,'productConsumed':None,'consumptionEvidence':[]}
    result['submitControl']=state
    shown,ime=submit_context(a);state['imeInitiallyShown']=shown
    need(shown is not None,'SUBMIT_IME_VISIBILITY_UNKNOWN')
    if shown:
        state['imeDisposition']='CURRENT_CCB_IME_UNCONFIRMED'
        need(current_ccb_password_ime(ime),'SUBMIT_CURRENT_CCB_PASSWORD_IME_UNPROVED')
        time.sleep(.15);again,ime=submit_context(a)
        need(again is True and current_ccb_password_ime(ime),'SUBMIT_CURRENT_CCB_PASSWORD_IME_UNPROVED')
        state['imeBackAttempts']=1;a.run('shell','input','keyevent','KEYCODE_BACK')
        state['imeDisposition']='BACK_SENT_HIDDEN_UNCONFIRMED'
    else:state['imeDisposition']='ALREADY_HIDDEN'
    # Reacquire geometry after the real IME hide, not the earlier paste rectangle.
    deadline=time.monotonic()+5;previous=None;stable=None
    while time.monotonic()<deadline:
        shown,_=submit_context(a)
        if shown is not False:previous=stable=None;time.sleep(.2);continue
        state['imeHiddenBeforeTap']=True
        if state['imeBackAttempts']:state['imeDisposition']='HIDDEN_AFTER_CONFIRMED_BACK'
        root=a.ui();target,count=field_reader(root)
        target=validate_field_target(target,False,True)
        observed=length_observation(target.text,count,expected)
        state.update(fieldCharactersMatched=observed['charactersMatched'],fieldExactTextMatched=observed['exactTextMatched'],
                     fieldRepresentation=observed['representation'],scopedLengthMatched=count==len(expected) if count is not None else None)
        need(observed['charactersMatched'] is True and observed['exactTextMatched'] is not False,'SUBMIT_COMPLETE_FIELD_NOT_OBSERVED')
        labels={value for node in root.iter('node') if node.get('package')==TARGET for value in ui.node_labels(node)}
        need(not any(re.search(r'(?:SYNC|STORE)_[A-Z0-9_]+',value) for value in labels),'SUBMIT_EXISTING_PRODUCT_ERROR')
        button=ui.click_target(root,SUBMIT_LABELS[field])
        need(button is not None,'SUBMIT_ENABLED_BUTTON_NOT_OBSERVED')
        viewports=[bounds(node) for node in root.iter('node') if node.get('package')==TARGET
                   and node.get('scrollable')=='true' and node.get('enabled')=='true' and bounds(node)]
        need(any(b[0]<=button[0]<button[2]<=b[2] and b[1]<=button[1]<button[3]<=b[3] for b in viewports),
             'SUBMIT_BUTTON_OUTSIDE_VISIBLE_APP_VIEWPORT')
        stable=(target.bounds,target.mode,button)
        if stable==previous:break
        previous=stable;stable=None;time.sleep(.2)
    need(stable is not None,'SUBMIT_HIDDEN_IME_STABLE_LAYOUT_NOT_OBSERVED')
    state['freshStableButtonInViewport']=True
    shown,_=submit_context(a);need(shown is False,'SUBMIT_IME_CHANGED_BEFORE_TAP')
    state['tapAttempts']=1;a.tap(stable[2]);state['tapCommandAccepted']=True

def note_submit_consumption(result,evidence):
    need(evidence in ('INPUT_CLEARED','JOINING_UI','READY_UI','MANAGEMENT_UNLOCKED_UI','PRODUCT_ERROR_UI'),'SUBMIT_CONSUMPTION_ENUM_INVALID')
    state=result.get('submitControl')
    if state is None:return
    state['productConsumed']=True
    if evidence not in state['consumptionEvidence']:state['consumptionEvidence'].append(evidence)

def submit_field_cleared(text,mode,count):
    # A real nonzero scoped counter outweighs an empty/omitted secure UI text.
    if count is not None:
        if count>0:return False
        return True if text in (None,'') else None
    return (text=='') if mode=='EDITTEXT' and text is not None else None

def hardware_empty_evidence(target,root,field,observed_length):
    need(observed_length is None or type(observed_length) is int and 0<=observed_length<=16384,'UI_LENGTH_READER_INVALID')
    need(not target.text and target.empty_observed is not False,'HARDWARE_PASTE_FIELD_NOT_EMPTY')
    if observed_length is not None:
        need(observed_length==0,'HARDWARE_PASTE_FIELD_NOT_EMPTY')
        return 'SCOPED_UI_ZERO_LENGTH'
    if target.empty_observed is True:return 'EMPTY_EDITTEXT_OBSERVED'
    if submit_disabled_in_context(root,field):return 'EXACT_SUBMIT_DISABLED'
    return None

def installed_hash(a,package,expected):
    need(package in (HELPER,TARGET) and re.fullmatch(r'[a-f0-9]{64}',expected) is not None,'PACKAGE_PIN_INVALID')
    paths=a.run('shell','-T','pm','path',package).decode('utf-8').splitlines()
    need(len(paths)==1 and paths[0].startswith('package:') and checked_target(paths[0][8:]),'SINGLE_INSTALLED_APK_REQUIRED')
    got=a.run('shell','-T','toybox','sha256sum',paths[0][8:]).decode('ascii').split()
    need(got and got[0]==expected,'INSTALLED_APK_HASH_MISMATCH')

class Bridge:
    def __init__(self,adb,serial,manifest,expected_api):
        cloud();need(expected_api in (26,36),'API_PROFILE_REQUIRED')
        self.expected_api=expected_api
        self.a=Adb(adb,serial)
        need(self.a.run('shell','-T','getprop','ro.kernel.qemu').strip()==b'1' and self.a.run('shell','-T','getprop','ro.build.version.sdk').strip()==str(expected_api).encode(),'EXPECTED_EMULATOR_REQUIRED')
        temp=Path(os.environ['RUNNER_TEMP']).resolve(strict=True);p=Path(manifest)
        need(p.name=='helper-build.json' and p.is_file() and not p.is_symlink() and p.resolve().is_relative_to(temp),'PRIVATE_HELPER_BUILD_REQUIRED')
        self.m=parse(p.read_bytes());self.root=p.parent;self.apk=self.root/'clipboard-helper.apk'
        need(self.m.get('kind')=='CCB_SYNTHETIC_CLIPBOARD_HELPER_BUILD_V1' and self.m.get('package')==HELPER and self.m.get('singleOfficialV2Verified') is True and self.m.get('candidateCertificateDifferent') is True and self.m.get('certificateSha256')!=CANDIDATE_CERT and self.m.get('debuggable') is True and self.m.get('testOnly') is True,'INDEPENDENT_HELPER_REQUIRED')
        need(self.apk.is_file() and not self.apk.is_symlink() and self.apk.stat().st_size==self.m['bytes'] and hashlib.sha256(self.apk.read_bytes()).hexdigest()==self.m['sha256'],'HELPER_APK_CHANGED')
    def install(self,r):
        need(not self.a.run('shell','-T','pm','list','packages',HELPER).strip(),'HELPER_ALREADY_PRESENT')
        output=self.a.run('install','-t',str(self.apk),timeout=180)
        need(re.search(rb'^Success\s*$',output,re.M) is not None,'HELPER_INSTALL_FAILED')
        self.check();r['helperInstalled']=True
    def check(self):
        installed_hash(self.a,HELPER,self.m['sha256'])
        pwd=self.a.app('pwd').decode('utf-8').strip()
        need(pwd in ('/data/user/0/'+HELPER,'/data/data/'+HELPER),'HELPER_RUN_AS_ROOT_MISMATCH')
    def operation(self,action,kind,payload,r):
        self.check()
        # files/ may not yet exist for a freshly installed, never-started helper.
        self.a.app('mkdir','-p','files')
        if action=='clear':self.a.cleanup_files()
        need(all(self.a.absent(p) for p in (INBOX,RESULT,TEMP_RESULT)),'STALE_HELPER_FILES')
        op=uuid.uuid4().hex;raw=envelope(op,action,kind,payload);r['phase']='WRITE_PRIVATE_INPUT'
        try:
            command=write_argv(self.a.prefix)
            try:p=subprocess.run(command,input=raw,capture_output=True,timeout=30)
            except (OSError,subprocess.SubprocessError):raise Refused('PRIVATE_STDIN_WRITE_FAILED') from None
            need(p.returncode==0 and not p.stdout.strip() and not p.stderr.strip(),'PRIVATE_STDIN_WRITE_FAILED')
            size=self.a.app('toybox','stat','-c','%s',INBOX).strip()
            need(size==str(len(raw)).encode(),'PRIVATE_INPUT_LENGTH_MISMATCH')
            digest=self.a.app('toybox','sha256sum',INBOX).decode('ascii').split()
            need(digest and digest[0]==hashlib.sha256(raw).hexdigest(),'PRIVATE_INPUT_HASH_MISMATCH')
            r['privateStdinLengthAndHashMatched']=True;r['phase']='FOREGROUND_HELPER'
            self.a.run('shell','-T','am','start','-W','-n',ACTIVITY,'--es','operation',op,timeout=30)
            deadline=time.monotonic()+20
            while self.a.absent(RESULT) and time.monotonic()<deadline:time.sleep(.25)
            need(not self.a.absent(RESULT),'HELPER_RESULT_NOT_OBSERVED')
            result=parse(self.a.app('cat',RESULT),4096)
            need(set(result)=={'kind','format','operation','action','focused','changed','roundTripMatched','characters','inputDeleted','clearMode','errorCode'},'HELPER_RESULT_FIELDS')
            need(result['kind']=='CCB_SYNTHETIC_CLIPBOARD_RESULT_V1' and result['format']==1 and result['operation']==op and result['action']==action,'HELPER_RESULT_BINDING')
            if result['errorCode'] is not None:
                r['helperErrorCode']=result['errorCode'] if result['errorCode'] in HELPER_ERRORS else 'UNKNOWN_HELPER_ERROR'
                raise Refused('HELPER_REPORTED_REFUSAL')
            need(result['focused'] is True and result['changed'] is True and result['roundTripMatched'] is True and result['inputDeleted'] is True,'HELPER_ROUNDTRIP_NOT_PROVED')
            need(type(result['characters']) is int and result['characters']==(len(payload) if action=='put' else 0),'HELPER_CHARACTER_COUNT_MISMATCH')
            need(self.a.absent(INBOX),'HELPER_PRIVATE_INPUT_RETAINED')
            r.update(helperForegroundRoundTripMatched=True,helperCharactersMatched=True,privateInputDeletedBeforeClipboardWrite=True,clipboardClearedHere=action=='clear')
        finally:
            # Only these three exact files in the independently owned helper package.
            self.a.cleanup_files();r['privateHelperFilesAbsentAfterOperation']=True;raw=None
    def target_foreground(self):
        raw=self.a.run('shell','-T','dumpsys','activity','activities').decode('utf-8',errors='replace')
        return any(TARGET+'/' in line for line in raw.splitlines() if re.search(r'(?:mResumedActivity|topResumedActivity|ResumedActivity)',line))
    def put_bytes(self,payload,kind,target_sha,r):
        payload_bytes(payload,kind);installed_hash(self.a,TARGET,target_sha)
        need(self.target_foreground(),'TARGET_CCB_NOT_FOREGROUND')
        self.operation('put',kind,payload,r)
        r['phase']='RETURN_TO_ORIGINAL_CCB';deadline=time.monotonic()+15
        while not self.target_foreground() and time.monotonic()<deadline:time.sleep(.25)
        need(self.target_foreground(),'ORIGINAL_CCB_NOT_RESUMED');r['originalCcbResumed']=True
        r['status']='SYNTHETIC_CLIPBOARD_ROUNDTRIP_OBSERVED'
    def resolve_field(self,field,resolver,require_empty,allow_unobservable_empty,r):
        if resolver is None:
            n=widget(self.a.ui(),field);text=n.get('text','')
            value=FieldTarget(bounds(n),'EDITTEXT',TARGET,True,None,not bool(text),text)
        else:
            # Must freshly read the current UI on EVERY call, including after helper OnStop.
            value=resolver(self.a,field)
        r['fieldResolverCalls']=r.get('fieldResolverCalls',0)+1
        value=validate_field_target(value,require_empty,allow_unobservable_empty)
        r['fieldResolverMode']=value.mode
        if require_empty:
            r['targetEmptyTextObserved']=value.empty_observed
            r['unobservableEmptyAllowedByCaller']=value.empty_observed is None and allow_unobservable_empty
        return value
    def hardware_paste(self,field,target_sha,resolver,length_reader,allow_unobservable_empty,r):
        # Only the exact V7 signed85/86 lab on a cloud API26 emulator may use it.
        cloud();need(getattr(self,'expected_api',None)==26 and target_sha in HARDWARE_PASTE_TARGET_SHAS,'HARDWARE_PASTE_API26_V7_ONLY')
        need(self.a.run('shell','-T','getprop','ro.kernel.qemu').strip()==b'1' and
             self.a.run('shell','-T','getprop','ro.build.version.sdk').strip()==b'26','HARDWARE_PASTE_API26_V7_ONLY')
        installed_hash(self.a,TARGET,target_sha);need(self.target_foreground(),'TARGET_CCB_NOT_FOREGROUND')
        r['phase']='FOCUS_BEFORE_HARDWARE_PASTE'
        target=self.resolve_field(field,resolver,False,allow_unobservable_empty,r)
        # Dismiss any unobservable toolbar with a normal tap, then re-resolve
        # after IME layout changes. Do not press Back or reuse old geometry.
        self.a.tap(target.bounds);previous=None;ready=None;deadline=time.monotonic()+10
        while time.monotonic()<deadline:
            target=self.resolve_field(field,resolver,False,allow_unobservable_empty,r)
            root=self.a.ui()
            focus='FOCUSED_EDITTEXT' if focused_target_edittext(root,target) else None
            if focus is None:
                ime=self.a.run('shell','-T','dumpsys','input_method').decode('utf-8',errors='replace')
                if current_ccb_password_ime(ime):focus='CURRENT_CCB_PASSWORD_IME'
            length=length_reader(self.a,field,target) if length_reader is not None else None
            empty=hardware_empty_evidence(target,root,field,length)
            state=(target.bounds,target.mode,focus,empty)
            if focus is not None and empty is not None and state==previous:
                ready=(focus,empty);break
            previous=state;time.sleep(.25)
        need(ready is not None,'HARDWARE_PASTE_FOCUS_OR_EMPTY_UNPROVED')
        need(self.target_foreground(),'TARGET_CCB_NOT_FOREGROUND')
        r['hardwarePasteFocusEvidence'],r['hardwarePasteEmptyEvidence']=ready
        r['hardwarePasteFocusObserved']=True;r['phase']='ORDINARY_HARDWARE_PASTE'
        need(r.get('ordinaryPasteHardwareAttempts',0)==0,'HARDWARE_PASTE_ALREADY_ATTEMPTED')
        # AOSP API26 Input sends exactly one DOWN+UP. Compose1.10.6 maps
        # Key.Paste(KEYCODE_PASTE=279) to PASTE -> selectionState.paste().
        r['ordinaryPasteHardwareAttempts']=1;r['ordinaryPasteAction']='KEYCODE_PASTE'
        self.a.run('shell','-T','input','keyevent','KEYCODE_PASTE')
        r['hardwarePasteCommandAccepted']=True;r['ordinaryPasteHardwarePairs']=1
    def paste_bytes(self,payload,kind,field,target_sha,r,*,field_resolver=None,length_reader=None,allow_unobservable_empty=False,use_paste_key=False):
        expected=payload_bytes(payload,kind);need((kind=='invitation')==(field=='invitation'),'PAYLOAD_FIELD_MISMATCH')
        installed_hash(self.a,TARGET,target_sha);need(self.target_foreground(),'TARGET_CCB_NOT_FOREGROUND')
        self.resolve_field(field,field_resolver,True,allow_unobservable_empty,r)
        self.put_bytes(payload,kind,target_sha,r)
        # CCB's real OnStop clears secrets/locks management. Reacquire the NEW empty field.
        first=self.resolve_field(field,field_resolver,True,allow_unobservable_empty,r)
        time.sleep(.2)
        second=self.resolve_field(field,field_resolver,True,allow_unobservable_empty,r)
        need(first.bounds==second.bounds and first.mode==second.mode,'RETURNED_FIELD_NOT_STABLE')
        if use_paste_key:
            self.hardware_paste(field,target_sha,field_resolver,length_reader,allow_unobservable_empty,r)
        else:
            b=second.bounds
            self.a.tap(b);x=(b[0]+b[2])//2;y=(b[1]+b[3])//2;r['phase']='ORDINARY_PASTE_MENU'
            self.a.run('shell','-T','input','swipe',str(x),str(y),str(x),str(y),'850')
            deadline=time.monotonic()+8;previous=None
            while time.monotonic()<deadline:
                current=paste_target(self.a.ui())
                if current is not None and current==previous:self.a.tap(current);r['ordinaryPasteMenuClicks']=1;break
                previous=current;time.sleep(.2)
            if r['ordinaryPasteMenuClicks']==1:r['ordinaryPasteAction']='MENU'
            elif getattr(self,'expected_api',None)==26:
                self.hardware_paste(field,target_sha,field_resolver,length_reader,allow_unobservable_empty,r)
            else:raise Refused('ORDINARY_PASTE_MENU_NOT_OBSERVED')
        r['phase']='VERIFY_WIDGET_TEXT'
        deadline=time.monotonic()+5;seen=None
        while time.monotonic()<deadline:
            target=self.resolve_field(field,field_resolver,False,allow_unobservable_empty,r)
            length=length_reader(self.a,field,target) if length_reader is not None else None
            seen=length_observation(target.text,length,expected)
            if seen['charactersMatched'] is True and seen['exactTextMatched'] is not False:break
            if seen['representation']=='MISMATCH':raise Refused('TARGET_WIDGET_TEXT_MISMATCH')
            time.sleep(.25)
        r.update(widgetTextRepresentation=seen['representation'],widgetCharactersMatched=seen['charactersMatched'],exactWidgetTextMatched=seen['exactTextMatched'])
        if seen['charactersMatched'] is None:r['status']='PASTE_CLICKED_WIDGET_CONTENT_UNOBSERVED';return 3
        need(seen['charactersMatched'] is True,'TARGET_WIDGET_CHARACTER_COUNT_MISMATCH')
        r['status']='ORDINARY_PASTE_CHARACTERS_OBSERVED';return 0
    def observe_product_submission(self,field,observer,r):
        """Passive only. The caller separately performs the ordinary product submit."""
        need(field in FIELD_LABELS and callable(observer),'SUBMIT_OBSERVER_REQUIRED')
        value=observer(self.a,field)
        need(value in ('JOINING_UI_OBSERVED','MANAGEMENT_UNLOCKED_UI_OBSERVED','PRODUCT_REJECTED_UI_OBSERVED','UNOBSERVED'),'SUBMIT_OBSERVER_ENUM_INVALID')
        need(value!='JOINING_UI_OBSERVED' or field=='invitation','SUBMIT_CONTEXT_MISMATCH')
        need(value!='MANAGEMENT_UNLOCKED_UI_OBSERVED' or field!='invitation','SUBMIT_CONTEXT_MISMATCH')
        r['submitObservation']=value
        r['productOperationAccepted']=True if value in ('JOINING_UI_OBSERVED','MANAGEMENT_UNLOCKED_UI_OBSERVED') else False if value=='PRODUCT_REJECTED_UI_OBSERVED' else None
        # This never promotes hidden text to exact equality or claims pairing/delivery.
        return value

def safe_result(action):
    return {'kind':'CCB_SYNTHETIC_CLIPBOARD_SAFE_RESULT_V1','status':'NOT_RUN','phase':'PRECHECK','action':action,'errorCode':None,'helperErrorCode':None,
            'helperInstalled':False,'privateStdinLengthAndHashMatched':False,'helperForegroundRoundTripMatched':False,
            'helperCharactersMatched':False,'privateInputDeletedBeforeClipboardWrite':False,'privateHelperFilesAbsentAfterOperation':False,
            'originalCcbResumed':False,'ordinaryPasteMenuClicks':0,'widgetTextRepresentation':'UNOBSERVED',
            'ordinaryPasteAction':'UNOBSERVED','ordinaryPasteHardwareAttempts':0,'ordinaryPasteHardwarePairs':0,
            'hardwarePasteCommandAccepted':None,'hardwarePasteFocusObserved':False,
            'hardwarePasteFocusEvidence':'UNOBSERVED','hardwarePasteEmptyEvidence':'UNOBSERVED',
            'widgetCharactersMatched':None,'exactWidgetTextMatched':None,'clipboardClearedHere':False,
            'fieldResolverMode':'UNOBSERVED','fieldResolverCalls':0,'targetEmptyTextObserved':None,
            'unobservableEmptyAllowedByCaller':False,'submitObservation':'UNOBSERVED','pairedDeliveryProved':False,
            'candidateApkModifiedOrResigned':False,'candidateOrUserKeysAccessed':False,'networkListenerOrForwardUsed':False,
            'productOperationAccepted':False,'humanAcceptance':False}

def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=('install','put','paste','clear'))
    for n in ('adb','helper-build','safe-result'):p.add_argument('--'+n,type=Path,required=True)
    p.add_argument('--serial',required=True);p.add_argument('--expected-api',type=int,choices=(26,36),default=26)
    p.add_argument('--payload-kind',choices=('invitation','recovery'));p.add_argument('--payload-file',type=Path)
    p.add_argument('--field',choices=tuple(FIELD_LABELS));p.add_argument('--target-apk-sha256')
    a=p.parse_args(argv);r=safe_result(a.action);data=None
    try:
        cloud();need(a.safe_result.parent.is_dir() and not a.safe_result.exists(),'NEW_SAFE_RESULT_REQUIRED')
        temp=Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
        need(not a.safe_result.resolve().is_relative_to(a.helper_build.parent.resolve()),'SEPARATE_SAFE_OUTPUT_REQUIRED')
        b=Bridge(a.adb,a.serial,a.helper_build,a.expected_api);rc=0
        if a.action in ('put','paste'):
            need(a.payload_kind is not None,'PAYLOAD_KIND_REQUIRED')
            if a.payload_file:
                need(a.payload_file.is_file() and not a.payload_file.is_symlink() and a.payload_file.resolve().is_relative_to(temp),'PRIVATE_SYNTHETIC_PAYLOAD_FILE_REQUIRED')
                with a.payload_file.open('rb') as f:data=bytearray(f.read(16385))
            else:data=bytearray(__import__('sys').stdin.buffer.read(16385))
            payload_bytes(data,a.payload_kind)
        if a.action=='install':b.install(r);r['status']='HELPER_INSTALLED'
        elif a.action=='put':
            need(a.target_apk_sha256 is not None,'TARGET_PROFILE_REQUIRED')
            b.put_bytes(data,a.payload_kind,a.target_apk_sha256,r)
        elif a.action=='clear':b.operation('clear','none',b'',r);r['status']='SYNTHETIC_CLIPBOARD_CLEARED'
        else:
            need(a.field is not None and a.target_apk_sha256 is not None,'TARGET_PROFILE_REQUIRED')
            rc=b.paste_bytes(data,a.payload_kind,a.field,a.target_apk_sha256,r)
        r['phase']='DONE'
    except Refused as e:r.update(status='REFUSED',errorCode=str(e));rc=2
    except Exception:r.update(status='REFUSED',errorCode='CLIPBOARD_CONTROL_EXCEPTION');rc=2
    finally:
        if data is not None:data[:]=b'\x00'*len(data)
    try:
        with a.safe_result.open('x',encoding='utf-8',newline='\n') as f:json.dump(r,f,sort_keys=True,indent=2);f.write('\n')
    except OSError:print(json.dumps({'status':'REFUSED','errorCode':'SAFE_RESULT_WRITE_FAILED'}));return 2
    print(json.dumps(r,sort_keys=True));return rc

if __name__=='__main__':raise SystemExit(main())
