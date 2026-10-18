from pathlib import Path
import hashlib,json,subprocess,shutil,xml.etree.ElementTree as ET
R=Path.cwd();G=Path(__file__).resolve().parent;E=R/'docs/desktop/evidence/p8-s2b-ui-layout-001-r3-20261019'
base='55366cee0706c78f545b32ef7d75750c649dcdbd'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def git(*a):return subprocess.check_output(['git',*a],stderr=subprocess.PIPE)
assert git('rev-parse','HEAD').decode().strip()==base and not git('diff','--cached','--name-only').strip()
rows=json.loads((G/'pre-focused-source.json').read_text(encoding='utf-8-sig'))
for row in rows:assert sha(R/row['path'])==row['sha256']
paths=[row['path'] for row in rows]
assert set(git('diff','--name-only').decode().splitlines())==set(paths[:2])
patch=git('diff','--binary','HEAD','--',*paths)
ledger=[]
for n in paths:
    data=(R/n).read_bytes();norm=data.replace(b'\r\n',b'\n')
    tracked=subprocess.run(['git','cat-file','-e',base+':'+n],capture_output=True).returncode==0
    if not tracked:
        lines=norm.splitlines(keepends=True)
        patch+=(f'diff --git a/{n} b/{n}\nnew file mode 100644\n--- /dev/null\n+++ b/{n}\n@@ -0,0 +1,{len(lines)} @@\n').encode()+b''.join(b'+'+x for x in lines)
    ledger.append(dict(path=n,bytes=len(data),sha256=hashlib.sha256(data).hexdigest(),normalizedSha256=hashlib.sha256(norm).hexdigest(),baseGitBlob=git('rev-parse',base+':'+n).decode().strip() if tracked else None))
(G/'source.patch').write_bytes(patch)
(G/'source.json').write_text(json.dumps(dict(base=base,sourceCheckpointCreated=False,files=ledger,patchSha256=sha(G/'source.patch'),sameAsPreFocusedAndBoundedSource=True,productionPrintsRemovedAfterBaseline=True),indent=2)+'\n')
xml=G/'05-bounded-desktop/junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml'
root=ET.parse(xml).getroot();fails=[t for t in root.findall('testcase') if t.find('failure') is not None];assert len(fails)==1
failure=dict(test=fails[0].attrib['name'],durationSeconds=float(fails[0].attrib['time']),exception=fails[0].find('failure').attrib['type'],stack=fails[0].find('failure').text,xmlSha256=sha(xml),sourceLine=309,timeoutMillis=10000,condition='range().value() > before after native scrollbar drag',requestedFirstWidthDp=1100,actualGeometryAtFailingDrag=None,thumbHitAndCaptureProven=False,rootCause='UNKNOWN',subsequentPageJumpAndNarrowBranchesInBounded='NOT REACHED',sameMethodEarlierFocused='PASS',otherNativeMethodsInBounded=[t.attrib['name'] for t in root.findall('testcase') if t.find('failure') is None],rerunsAfterRed=0,packagingExecuted=False,manualGateStarted=False)
(G/'failure-summary.json').write_text(json.dumps(failure,indent=2)+'\n')
skipped=[]
for file in (G/'05-bounded-desktop/junit').glob('*.xml'):
    for test in ET.parse(file).getroot().findall('testcase'):
        if test.find('skipped') is not None:skipped.append(dict(suite=file.name,test=test.attrib['name']))
eventRows=[];geometryRows=[]
for stage in ['01-native-baseline-diagnostic','02-native-repaired-gate','03-fish-ui-native-focused','05-bounded-desktop']:
    file=G/stage/'junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml'
    for line in (ET.parse(file).getroot().findtext('system-out') or '').splitlines():
        if line.startswith('FISH_R3_'):
            row=dict(stage=stage,sourceXml=file.name,record=line)
            (geometryRows if line.startswith('FISH_R3_SNAPSHOT') else eventRows).append(row)
for name,data in [('native-event-trace.jsonl',eventRows),('native-geometry-state.jsonl',geometryRows)]:
    (G/name).write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in data),encoding='utf-8')
preservation=[]
for folder in ['p8-s2b-ui-layout-001-20261018','p8-s2b-ui-layout-001-r1-20261018','p8-s2b-ui-layout-001-r2-20261018']:
    path='docs/desktop/evidence/'+folder
    before=git('rev-parse',base+':'+path).decode().strip();now=git('rev-parse','HEAD:'+path).decode().strip();assert before==now
    preservation.append(dict(path=path,gitTreeAtStart=before,gitTreeNow=now,unchanged=True))
(G/'history-preservation.json').write_text(json.dumps(preservation,indent=2)+'\n')
closing=dict(state='P8-S2B-UI-LAYOUT-001-R3 HOLD / RAW RED PRESERVED',bounded=dict(suites=159,tests=1526,reportedPass=1523,failures=1,errors=0,skipped=2,exitCode=1),skippedTests=skipped,newManualDistribution='NOT BUILT',newLauncherExit='NOT TESTED / NOT STARTED',newAppJvmExit='NOT TESTED / NOT STARTED',newManualRootLock='NOT TESTED / NOT STARTED',manualFakeService='NOT STARTED',manualAudioFixturesPrepared=False,realFishGetIncrement=0,realFishTtsIncrement=0,sourceDraftsPreservedUncommitted=True,sourceChangedAfterRed=False,temporaryNativeWindows='fixture finally blocks dispose own windows and restore own parent; no cleanup assertion RED',nativeCacheCleanup='fixture finally asserts no own mp3/part; no cleanup assertion RED',postStageJavaProcessCount=0,physicalIoAbsoluteTimeout='NOT PROVEN',winAuto001='OPEN; no FrameArrived capture used',winBrand001='OPEN / DEFERRED; not implemented')
(G/'closing-state.json').write_text(json.dumps(closing,indent=2)+'\n')
counts=dict(realFish=dict(get=0,tts=0),manualFakeRequests=0,manualFakeServiceStarted=False,newNativeFixtureOnly=dict(baseline=dict(sampleGet=2,ttsPost=0),repairedGate=dict(sampleGet=5,ttsPost=5),fishFocused=dict(sampleGet=5,ttsPost=5),bounded=dict(sampleGet=5,ttsPost=5)),basis='Native fixture physical server.requestCount assertions in completed Escape/Stop/resize branches; baseline records; pagination fixture has no HTTP provider. Derived from recorded successful assertions, not a new network capture.',allOtherExistingMockTestsAggregate='NOT AGGREGATED; per-case request-count assertions and HTTP diagnostics retained. Do not present Native subtotal as all-suite HTTP total.')
(G/'request-accounting.json').write_text(json.dumps(counts,indent=2)+'\n')
(G/'skill-review.json').write_text(json.dumps(dict(reviewedBeforeEvidenceCommit=True,skills=['chatbar-fish-audio-voice','chatbar-shadcn-compose'],skillEdits=[],reason='HOLD drafts are not committed/promoted as available product capability; evidence-only commit changes no accepted skill contract. Reassess compact UI map on a later authorized source checkpoint.'),indent=2)+'\n')
E.mkdir(exist_ok=False);(E/'.gitattributes').write_bytes(b'* -text\n')
for stage in ['01-native-baseline-diagnostic','02-native-repaired-gate','03-fish-ui-native-focused','04-affected-regression','05-bounded-desktop','baseline-sources','repaired-sources','final-sources']:shutil.copytree(G/stage,E/stage)
for name in ['preflight.json','baseline-source.json','repaired-source.json','pre-focused-source.json','source.patch','source.json','failure-summary.json','stage-summary.json','native-event-trace.jsonl','native-geometry-state.jsonl','history-preservation.json','pre-bounded-resources.json','closing-state.json','request-accounting.json','skill-review.json','run-stage.ps1','bounded.init.gradle','summarize-stages.py','freeze-hold-evidence.py']:
    shutil.copyfile(G/name,E/name)
print(json.dumps(dict(sourcePatchSha256=sha(G/'source.patch'),xmlCount=len(list(E.glob('*/junit/*.xml'))),sourceDraftCount=len(paths),newSourceCommit=False,evidencePath=E.relative_to(R).as_posix())))
