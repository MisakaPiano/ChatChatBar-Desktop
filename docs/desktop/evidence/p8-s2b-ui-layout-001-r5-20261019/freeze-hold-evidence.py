from pathlib import Path
import hashlib, json, shutil, subprocess, xml.etree.ElementTree as ET

R=Path.cwd();G=Path(__file__).resolve().parent;E=R/'docs/desktop/evidence/p8-s2b-ui-layout-001-r5-20261019'
BASE='8f36e0849413300638880ccf2276a92eb0b6e989'
def git(*a):return subprocess.check_output(['git',*a],stderr=subprocess.PIPE)
def sha(b):return hashlib.sha256(b).hexdigest()
def save(n,v):(G/n).write_text(json.dumps(v,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
assert git('rev-parse','HEAD').decode().strip()==BASE and not git('diff','--cached','--name-only').strip()
old=json.loads((G/'baseline-source.json').read_text(encoding='utf-8-sig'));paths=[f['path'] for f in old['files']]
assert set(git('diff','--name-only').decode().splitlines())==set(paths[:2])
assert sha((R/'docs/desktop/evidence/p8-s2b-ui-layout-001-r4-20261019/source.patch').read_bytes())=='21cca30223dd289aa87f02edcd56f32a884a43e131a936269d52e489c77495d8'
for f in old['files']:
 b=(G/'baseline-sources'/Path(f['path']).name).read_bytes();assert sha(b)==f['sha256'] and len(b)==f['bytes'] and sha(b.replace(b'\r\n',b'\n'))==f['normalizedSha256']
assert sha((R/paths[1]).read_bytes())==old['files'][1]['sha256']
for p in paths:assert (G/'final-sources'/Path(p).name).read_bytes()==(R/p).read_bytes()

ledger=[];patch=git('diff','--binary','HEAD','--',*paths[:2])
for f in old['files']:
 p=f['path'];b=(R/p).read_bytes();n=b.replace(b'\r\n',b'\n')
 if p==paths[2]:
  lines=n.splitlines(keepends=True)
  patch+=(f'diff --git a/{p} b/{p}\nnew file mode 100644\n--- /dev/null\n+++ b/{p}\n@@ -0,0 +1,{len(lines)} @@\n').encode()+b''.join(b'+'+line for line in lines)
 ledger.append(dict(path=p,bytes=len(b),sha256=sha(b),normalizedSha256=sha(n),r4RawSha256=f['sha256'],unchangedFromFrozenR4=sha(b)==f['sha256']))
(G/'source.patch').write_bytes(patch)
save('source.json',dict(base=BASE,sourceCheckpointCreated=False,files=ledger,patchSha256=sha(patch),sourceChangedAfterRed=False,
    initialStage='Native test only; two production drafts unchanged',finalStage='Presentation pager plus Native test; Window unchanged from R4'))
for p in [paths[0],paths[2]]:
 result=subprocess.run(['git','diff','--no-index','--',str(G/'baseline-sources'/Path(p).name),str(R/p)],capture_output=True)
 assert result.returncode==1
 (G/(Path(p).stem+'-r5-delta.patch')).write_bytes(result.stdout)
save('preflight.json',dict(head=BASE,parent='3d46c6448b6748644c561ea27d3ed2b016e1b045',branch='feature/phase8-fish-audio',trackingAtStart=BASE,liveAtStart=BASE,fetchSucceeded=True,indexInitiallyEmpty=True,rawAndNormalizedFrozenFilesVerified=3,unknownChanges=0,protectedUntrackedFiles=4,protectedContentsRead=False))

stage1=G/'01-native-input-admission';stage2=G/'02-native-class-with-pager'
xml1=stage1/'junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml'
xml2=stage2/'junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml'
a=ET.parse(xml1).getroot();b=ET.parse(xml2).getroot()
assert a.attrib['tests']=='1' and a.attrib['failures']=='0'
assert b.attrib['tests']=='4' and b.attrib['failures']=='1' and b.attrib['skipped']=='0'
text=a.findtext('system-out') or ''
assert text.count('FISH_R5_PRESS ')==2 and text.count('FISH_R5_DRAG ')==32 and 'FISH_R5_ADMISSION_REJECT' not in text
assert text.count('phase=R5-real-drag-completed')==2
failureTest=next(t for t in b.findall('testcase') if t.find('failure') is not None);failure=failureTest.find('failure')
assert 'Page input and jump must remain adjacent' in failure.attrib['message']
records=[]
for stage,root in [(stage1,a),(stage2,b)]:
 for line in (root.findtext('system-out') or '').splitlines():
  if line.startswith(('FISH_R4_','FISH_R5_','FISH_R3_')):records.append(dict(stage=stage.name,record=line))
(G/'native-events-and-geometry.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in records),encoding='utf-8')
save('input-admission-result.json',dict(stage=stage1.name,suites=1,tests=1,reportedPass=1,failures=0,exitCode=0,testSeconds=float(a.attrib['time']),xmlSha256=sha(xml1.read_bytes()),
    requestedWidthsDp=[1100,720],observedFrameWidths=[1100,720],dpiScale=[1,1],
    thumbRects=[dict(x=1120,y=155,width=8,height=89),dict(x=740,y=261,width=8,height=54)],
    admittedPresses=[dict(planned=[1124,200],system=[1124,200],awt=[1124,200]),dict(planned=[744,288],system=[744,288],awt=[744,288])],
    stablePointerSamplesPerWidth=3,relocationsUsed=0,physicalPressesPerDrag=1,continuousDragStepsPerWidth=16,releaseVerified=True,
    beforeFirstVisibleItem=1,afterFirstVisibleItems=[9,9],semanticRangeIncreased=True,queryCountAtDragCompletionPerFixture=1,mockHttpPerFixture=0,bindingCountPerFixture=0,
    originalPageValidationAndUnknownTotalIntBranches='PASS in this stage before pager layout change',
    rootCauseLimits='This run demonstrates correct admitted native input; extra R4 movement origin and historic R3 root cause remain UNKNOWN'))
save('failure-summary.json',dict(stage=stage2.name,suites=1,tests=4,reportedPass=3,failures=1,errors=0,skipped=0,exitCode=1,
    failedTest=failureTest.attrib,testSeconds=float(b.attrib['time']),failure=failure.attrib,stack=failure.text,xmlSha256=sha(xml2.read_bytes()),
    failureAt='assertPagerLayout line250; caller line472 before dragThumbFromTop',requestedFirstWidthDp=1100,
    failingPredicate='jump.x >= field.x + field.width AND jump.x - field.x - field.width <= 12',
    actualPagerRectsAtFailure=None,geometryLogging='FISH_R5_PAGER println follows assertions and was not reached; cannot fabricate values',
    rootCause='UNKNOWN: genuine adjacent-layout violation vs semantics-coordinate observation not distinguished',
    passedTests=[t.attrib for t in b.findall('testcase') if t.find('failure') is None],
    notReached=['drag after pager change','native wheel after pager change','720/360dp pager assertions','post-layout page and Int boundary branches'],
    rerunsAfterRed=0,nextFocusedStarted=False,boundedStarted=False,distributionBuilt=False,manualGateStarted=False))
history=[]
for folder in ['p8-s2b-ui-layout-001-20261018','p8-s2b-ui-layout-001-r1-20261018','p8-s2b-ui-layout-001-r2-20261018','p8-s2b-ui-layout-001-r3-20261019','p8-s2b-ui-layout-001-r4-20261019']:
 p='docs/desktop/evidence/'+folder;tree=git('rev-parse',BASE+':'+p).decode().strip()
 assert git('rev-parse','HEAD:'+p).decode().strip()==tree and not git('diff','--name-only','HEAD','--',p).strip()
 history.append(dict(path=p,gitTreeAtStart=tree,gitTreeNow=tree,worktreeUnchanged=True))
save('history-preservation.json',history)
save('request-accounting.json',dict(realFishGet=0,realFishTts=0,manualFakeStarted=False,
    stage01=dict(syntheticCatalogFunctionCalls=8,mockHttpGet=0,mockHttpTts=0,bindingCalls=0,basis='2 known-total fixtures x3 page queries plus unknown-total x2 queries'),
    stage02=dict(mockSampleGet=5,mockTtsPost=5,basis='Completed Stop fixtures 4 GET/4 POST plus resize-cache 1 GET/1 POST; pagination failed before network actions; Escape HTTP0'),
    note='Mock/native totals only. No provider requests; no API key or real data read. Stage counts are independent.'))
save('closing-state.json',dict(status='P8-S2B-UI-LAYOUT-001-R5 HOLD / RAW RED PRESERVED',sourceDraftsPreservedUncommitted=3,sourceChangedAfterRed=False,newSourceCheckpoint=False,
    newDistribution='NOT BUILT',manualGate='NOT STARTED',launcherExit='NOT TESTED / NOT STARTED',appJvmExit='NOT TESTED / NOT STARTED',rootExclusion='NOT TESTED',rootReacquire='NOT TESTED',manualFake='NOT STARTED',temporaryTls='NOT CREATED',
    nativeFinally='No owner/window/drain/audio-file cleanup assertion failure; stage02 finally completed',postRunJavaProcessCount=0,
    automaticStage02IsNotManualPass=True,winAuto001='OPEN; not attributed as cause of scrollbar RED',winBrand001='OPEN / DEFERRED',
    protectedUserFiles=dict(untracked=4,read=False,modified=False,staged=False),
    originalLimits=['MCI/device compatibility','TOCTOU','absolute physical I/O deadline','historic symlink NOT TESTED and existing JUnit SKIPPED unchanged']))
save('pagination-layout-status.json',dict(userRequirement='When width permits, Previous / page caption / adjacent page-input + Jump / Next on same row; wrap only as needed',
    draftImplementation='FlowRow with unweighted caption and grouped input/action Row; conditional validation below',status='IMPLEMENTED AS UNCOMMITTED DRAFT / NEW ADJACENCY ASSERTION RED',accepted=False))
save('skill-review.json',dict(reviewedBeforeEvidenceCommit=True,skills=['chatbar-fish-audio-voice','chatbar-shadcn-compose'],edits=[],reason='HOLD evidence does not promote uncommitted UI/test changes as accepted product capability; accepted skill facts unchanged'))
E.mkdir(exist_ok=False);(E/'.gitattributes').write_bytes(b'* -text\n')
for folder in ['baseline-sources','01-source','final-sources','01-native-input-admission','02-native-class-with-pager']:shutil.copytree(G/folder,E/folder)
for name in ['baseline-source.json','source.json','source.patch','DesktopFishPickerPresentation-r5-delta.patch','DesktopFishPickerNativeUxTest-r5-delta.patch',
    '01-source-hashes.json','final-source-hashes.json','pre-native-resources.json','post-red-resources.json','preflight.json',
    'stage-summary.json','input-admission-result.json','failure-summary.json','native-events-and-geometry.jsonl','history-preservation.json',
    'request-accounting.json','closing-state.json','pagination-layout-status.json','skill-review.json','run-stage.ps1','summarize-stages.py','freeze-hold-evidence.py']:
 shutil.copyfile(G/name,E/name)
print(json.dumps(dict(evidence=E.relative_to(R).as_posix(),sourcePatchSha256=sha(patch),currentSourceLines=len((R/paths[2]).read_text(encoding='utf-8').splitlines()))))
