"""Source-bound observations only; never a data-delivery or owner-state oracle."""
import re
import time

PACKAGE='com.example.chatbar.lansyncnext'
GROUP=PACKAGE+'/com.example.chatbar.lanSync.LanSyncActivity'
SOURCE='f5fc060546eb9479b2566375761903d383c538b1'
READY=('立即检查','网盘设置')
JOINING=('扫描更新邀请','更新邀请并继续','继续加入')
EMPTY=('创建新设备组','扫描设备组邀请')
STATUS={
    '邀请已核对，正在验证此设备':'JOIN_VERIFYING',
    '等待邀请设备批准，加入进度已保留':'JOIN_WAIT_APPROVAL',
    '加入进度已保留，等待设备批准或重试':'JOIN_RETAINED',
    '设备组已就绪，应用正在检查同步':'READY_CHECKING',
    '同步检查完成':'CHECK_COMPLETE',
    '设备组同步尚未恢复，原件与待传队列已保留':'RESUME_PENDING',
    '设备组同步已暂停，原件与待传队列已保留':'PAUSED',
    '此设备已撤销，保留原件和待传队列':'DEVICE_REVOKED',
    '正在等待新密钥，待传队列已保留':'WAIT_NEW_KEY',
    '设备组信任证据待检查，资料已保留':'TRUST_REVIEW',
}
ERROR_CODES={
    'SYNC_METADATA_WORKING_BUDGET':'METADATA_WORKING_LIMIT',
    'SYNC_METADATA_INDEX_BUDGET':'METADATA_INDEX_LIMIT',
    'SYNC_METADATA_STRING_BUDGET':'METADATA_STRING_LIMIT',
    'SYNC_METADATA_DEPTH':'METADATA_DEPTH_LIMIT',
    'SYNC_FILE_LIMIT':'FILE_LIMIT',
    'SYNC_GROUP_PROFILE_LOCAL_HISTORY_EXISTS':'LOCAL_HISTORY_PRESENT',
    'SYNC_GROUP_OPERATION_FAILED':'OPERATION_FAILED',
}
EXPLANATIONS={
    'SYNC_METADATA_WORKING_BUDGET':'本次设备组操作需要读取的同步元数据超过了本机处理容量。',
    'SYNC_METADATA_INDEX_BUDGET':'本次设备组操作的同步索引超过了本机处理容量。',
    'SYNC_METADATA_STRING_BUDGET':'本次设备组操作遇到超出格式或大小限制的同步资料。',
    'SYNC_METADATA_DEPTH':'本次设备组操作遇到超出格式或大小限制的同步资料。',
    'SYNC_FILE_LIMIT':'本次设备组操作遇到超出格式或大小限制的同步资料。',
    'SYNC_GROUP_PROFILE_LOCAL_HISTORY_EXISTS':'本机已保留原设备组的设置进度。请使用已保存的恢复码继续该组。',
}
RESUME='设置进度已保留，可以用之前保存的恢复码继续原设备组。'
SOURCE_ACTIONS=frozenset({'打开设备组','创建设备组','完成创建','保留并放弃未完成设置',
    '接续已确认的管理操作','恢复组管理','加入设备组','继续加入','重新核对加入资料','加入证明',
    '生成邀请','核对加入设备','保存设备显示名称（成员核验结果保留）','批准加入','准备撤销设备','撤销设备',
    '重新检查同步','核对冲突处理目标','接续已确认的冲突处理','核对本机发布目标','核对传入删除或覆盖',
    '按已核对目标继续','读取本机保全记录','浏览本机保全','核对恢复目标','读取设备显示名称（原文件已保留）',
    '本机同步准备','最终本机封存'})
ACTION_CLASSES={'打开设备组':'OPEN_GROUP','加入设备组':'JOIN_GROUP','继续加入':'RESUME_JOIN',
    '重新核对加入资料':'REFRESH_JOIN','加入证明':'JOIN_PROOF','本机同步准备':'LOCAL_PREPARE'}
TOKEN=re.compile(r'(?<![A-Z0-9_])(?:SYNC|STORE)_[A-Z0-9_]{1,100}(?![A-Z0-9_])')
RECT=re.compile(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
PHASES=frozenset({'BEFORE_READBACK','AFTER_READBACK'})


def active_group(text):
    rows=[line for line in text.splitlines() if 'mResumedActivity' in line or 'topResumedActivity' in line]
    if not rows:return None
    return all(re.search(re.escape(GROUP)+r'(?=[\s}])',line) is not None for line in rows)


def rectangle(node):
    match=RECT.fullmatch(node.get('bounds',''))
    if match:
        x1,y1,x2,y2=map(int,match.groups())
        if x2>x1 and y2>y1:return x1,y1,x2,y2
    return None


def node_values(node):
    return {node.get(name,'').strip() for name in ('text','content-desc')} - {''}


def error_class(text):
    stage,separator,body=text.partition('：')
    if not separator or stage not in SOURCE_ACTIONS:return None
    tokens=TOKEN.findall(body)
    if len(tokens)!=1:return None
    code=tokens[0]
    if body==code:
        return {'action':ACTION_CLASSES.get(stage,'OTHER_SOURCE_ACTION'),
                'class':ERROR_CODES.get(code,'UNCLASSIFIED_SOURCE_CODE')}
    explanation=EXPLANATIONS.get(code)
    if explanation and body in {explanation+'（'+code+'）',explanation+RESUME+'（'+code+'）'}:
        return {'action':ACTION_CLASSES.get(stage,'OTHER_SOURCE_ACTION'),'class':ERROR_CODES[code]}
    return None


def classify(root,foreground):
    parents={child:parent for parent in root.iter() for child in parent}
    nodes=[]
    for node in root.iter('node'):
        if node.get('package')!=PACKAGE or not rectangle(node) or node.get('visible-to-user')=='false':continue
        cursor=node;private=False
        while cursor is not None:
            if cursor.get('password')=='true' or cursor.get('class')=='android.widget.EditText':private=True;break
            cursor=parents.get(cursor)
        if not private:nodes.append(node)
    whole=set().union(*(node_values(node) for node in nodes)) if nodes else set()
    labels=set(whole)
    for value in whole:labels.update(value.split(', '))
    surface=foreground is True and bool(labels.intersection({'设备组同步',*READY,*JOINING,*EMPTY,*STATUS}))
    result={'surface':'GROUP_PAGE' if surface else 'UNKNOWN_SURFACE','controlStage':'UNKNOWN_SURFACE',
        'readyControlPairVisible':None,'readyControlsEnabled':{'check':None,'cloud':None},
        'statusClasses':[],'sourceErrors':[],'unclassifiedAppTokenSeen':None}
    if not surface:return result
    def control(label):
        matches={}
        for node in nodes:
            values=set(node_values(node))
            for value in list(values):values.update(value.split(', '))
            if label not in values:continue
            cursor=node
            while cursor is not None and cursor.get('clickable')!='true':cursor=parents.get(cursor)
            target=cursor if cursor is not None else node
            bounds=rectangle(target)
            if bounds:matches[bounds]=None if cursor is None or cursor.get('enabled') not in ('true','false') else cursor.get('enabled')=='true'
        return len(matches)==1,next(iter(matches.values())) if len(matches)==1 else None
    check,check_enabled=control(READY[0]);cloud,cloud_enabled=control(READY[1])
    ready=check and cloud;joining=bool(labels.intersection(JOINING));empty=set(EMPTY)<=labels
    stages=[name for name,present in (('READY_CONTROLS',ready),('JOINING_CONTROLS',joining),('EMPTY_CONTROLS',empty)) if present]
    result.update(controlStage=stages[0] if len(stages)==1 else 'CONFLICTING_CONTROLS' if stages else 'NOT_OBSERVED',
        readyControlPairVisible=ready,readyControlsEnabled={'check':check_enabled,'cloud':cloud_enabled})
    statuses={STATUS[value] for value in whole if value in STATUS}
    if any(re.fullmatch(r'同步检查完成，[0-9]+ 项需要审阅或补齐依赖',value) for value in whole):statuses.add('CHECK_NEEDS_REVIEW')
    result['statusClasses']=sorted(statuses)
    errors=[];unknown=False
    for value in whole:
        parsed=error_class(value)
        if parsed:
            if parsed not in errors:errors.append(parsed)
        elif TOKEN.search(value):unknown=True
        if re.fullmatch(r'已隔离 [0-9]+ 个未通过验证的密文候选，原始证据已保留；正确副本可继续同步。',value):
            errors.append({'action':'LOCAL_PREPARE','class':'QUARANTINED_CIPHERTEXT'})
    result.update(sourceErrors=sorted(errors,key=lambda row:(row['action'],row['class']))[:8],unclassifiedAppTokenSeen=unknown)
    return result


class Observer:
    def __init__(self):
        self.history=[];self.first=[];self.seen=set();self.ready=False;self.data=False;self.read_failures=0
    def add(self,root,foreground,phase,seconds):
        if phase not in PHASES:raise ValueError('OBSERVATION_PHASE')
        sample=classify(root,foreground);sample.update(relativePhase=phase,secondsFromSubmit=max(0,min(int(seconds),200)))
        self.ready=self.ready or sample['controlStage']=='READY_CONTROLS'
        kinds=[('CONTROLS',sample['controlStage'])]+[('STATUS',value) for value in sample['statusClasses']]
        kinds += [('ERROR',row['class']) for row in sample['sourceErrors']]
        if sample['unclassifiedAppTokenSeen']:kinds.append(('APP_TOKEN','UNCLASSIFIED'))
        for kind,value in kinds:
            if (kind,value) not in self.seen:
                self.seen.add((kind,value))
                if len(self.first)<24:self.first.append(dict(kind=kind,value=value,relativePhase=phase,secondsFromSubmit=sample['secondsFromSubmit']))
        self.history.append(sample);self.history=self.history[-8:]
    def failed_read(self):self.read_failures=min(100,self.read_failures+1)
    def summary(self):
        return {'kind':'CCB_ORIGINAL86_GROUP_UI_OBSERVATION_V1','androidUiSource':SOURCE,
            'dataReadbackVerified':self.data,'readyControlsEverConfirmed':self.ready,
            'readyEvidence':'VISIBLE_READY_ONLY_CONTROLS' if self.ready else 'NOT_CONFIRMED',
            'readFailures':self.read_failures,'firstSeenCapped':len(self.seen)>len(self.first),
            'lastControlStage':self.history[-1]['controlStage'] if self.history else 'UNKNOWN_SURFACE',
            'firstSeen':list(self.first),'lastSamples':list(self.history)}


def sample(a,observer,root,phase,started):
    try:foreground=active_group(a.run('shell','dumpsys','activity','activities',timeout=3))
    except Exception:foreground=None;observer.failed_read()
    observer.add(root,foreground,phase,time.monotonic()-started)


class ObservationBudget(Exception):pass


def after_readback(a,observer,started):
    """At most8s observation work+one final<=2s UI-temp cleanup; no clicks/BACK."""
    original=a.run;deadline=time.monotonic()+8;swipes=0
    def bounded(*args,timeout=20):
        cleanup=(len(args)==4 and args[:3]==('shell','rm','-f') and
                 re.fullmatch(r'/data/local/tmp/ccb-v7-scan-[a-f0-9]{32}\.xml',args[3]) is not None)
        remaining=deadline-time.monotonic()
        if not cleanup and remaining<=0:raise ObservationBudget()
        return original(*args,timeout=min(timeout,2 if cleanup else 3,2 if cleanup else remaining))
    a.run=bounded
    try:
        while time.monotonic()<deadline:
            root=a.ui();sample(a,observer,root,'AFTER_READBACK',started)
            latest=observer.history[-1]
            if latest['surface']!='GROUP_PAGE' or latest['controlStage']=='READY_CONTROLS' or swipes>=2:break
            boxes={rectangle(node) for node in root.iter('node') if node.get('package')==PACKAGE and node.get('scrollable')=='true'}-{None}
            boxes={box for box in boxes if box[3]-box[1]>=200}
            if len(boxes)!=1:break
            x1,y1,x2,y2=boxes.pop();x=(x1+x2)//2;h=y2-y1
            start,end=(y1+h*3//4,y1+h//4) if swipes==0 else (y1+h//4,y1+h*3//4)
            a.run('shell','input','swipe',str(x),str(start),str(x),str(end),'300',timeout=2)
            swipes+=1;time.sleep(min(.3,max(0,deadline-time.monotonic())))
    except Exception:observer.failed_read()
    finally:a.run=original
