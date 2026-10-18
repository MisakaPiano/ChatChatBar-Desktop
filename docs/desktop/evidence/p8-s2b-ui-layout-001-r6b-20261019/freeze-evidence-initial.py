from pathlib import Path
import hashlib,json,re,subprocess,shutil,xml.etree.ElementTree as ET
R=Path.cwd();G=Path(__file__).resolve().parent;E=R/'docs/desktop/evidence/p8-s2b-ui-layout-001-r6b-20261019'
BASE='33cf1beeb5dfcb655e261a6fadff7610310af522'
def git(*a):return subprocess.check_output(['git',*a],stderr=subprocess.PIPE)
def sha(b):return hashlib.sha256(b).hexdigest()
def save(n,v):(G/n).write_text(json.dumps(v,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
assert git('rev-parse','HEAD').decode().strip()==BASE and not git('diff','--cached','--name-only').strip()
pre=json.loads((G/'preflight.json').read_text(encoding='utf-8'));paths=[f['path'] for f in pre['initialSourceIdentities']]
ledger=[]
for f in pre['initialSourceIdentities']:
 before=(G/'baseline-sources'/Path(f['path']).name).read_bytes();after=(R/f['path']).read_bytes()
 assert len(before)==f['bytes'] and sha(before)==f['sha256']
 if f['path']!=paths[2]:assert before==after
 ledger.append(dict(path=f['path'],r6aBytes=len(before),r6aSha256=sha(before),currentBytes=len(after),currentSha256=sha(after),unchanged=before==after))
assert (G/'diagnostic-source.kt').read_bytes()==(R/paths[2]).read_bytes()
save('source-preservation.json',dict(files=ledger,onlyAuthorizedRootDiagnosticChanges=True,allOriginalAssertionsInputAndOrderUnchanged=True,sourceChangedAfterTestRed=False))
stage=G/'01-pager-geometry-single-method';xml=stage/'junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml';root=ET.parse(xml).getroot()
assert root.attrib['tests']=='1' and root.attrib['failures']=='1' and root.attrib['errors']=='0' and root.attrib['skipped']=='0'
test=root.find('testcase');failure=test.find('failure');stdout=root.findtext('system-out') or ''
assert 'Page input and jump must remain adjacent' in failure.attrib['message']
lines=[s for s in stdout.splitlines() if s.startswith('FISH_R6A_PAGER_GEOMETRY')]
assert len([s for s in lines if ' MATCH ' in s])==5 and len([s for s in lines if ' NODE ' in s])==6 and any(' RELATION ' in s for s in lines)
(G/'geometry-system-out.txt').write_text(stdout,encoding='utf-8')
(G/'geometry-raw.jsonl').write_text(''.join(json.dumps(dict(record=s),ensure_ascii=False)+'\n' for s in lines),encoding='utf-8')
def rect(line,key):return list(map(float,re.search(re.escape(key)+r'=Rect.fromLTRB\(([^)]+)\)',line).group(1).split(', ')))
def point(line,key):return list(map(float,re.search(re.escape(key)+r'=Offset\(([^)]+)\)',line).group(1).split(', ')))
nodes=[];matches=[];layouts=[]
for line in lines:
 if ' MATCH ' in line:
  m=re.search(r'MATCH label=(.*?) candidates=\[(.*?)\] chosen=(\d+) reason=(.*?) originalHybridRect=',line)
  matches.append(dict(label=m[1],candidateIds=[int(v) for v in m[2].split(', ')],selectedId=int(m[3]),selectionReason=m[4]))
 if ' NODE ' in line:
  m=re.search(r'NODE label=(.*?) id=(\d+) chosen=(true|false) parent=(\d+) children=(\[[^]]*\]) pathToRoot=(\[[^]]*\]) semanticRootId=(\d+)',line)
  nodes.append(dict(label=m[1],id=int(m[2]),selected=m[3]=='true',parent=int(m[4]),children=json.loads(m[5]),pathToRoot=json.loads(m[6]),semanticRootId=int(m[7]),
    role=re.search(r' role=(\S+)',line)[1],setText='setText=true' in line,onClick='onClick=true' in line,disabled='disabled=true' in line,
    positionOnScreen=point(line,'positionOnScreen'),positionInWindow=point(line,'positionInWindow'),positionInRoot=point(line,'positionInRoot'),
    boundsInWindow=rect(line,'boundsInWindow'),boundsInRoot=rect(line,'boundsInRoot'),touchBoundsInRoot=rect(line,'touchBoundsInRoot'),screenBounds=rect(line,'screenBoundsFromWindow'),
    layoutSize=list(map(int,re.search(r'layoutSize=(\d+)x(\d+)',line).groups())) )
 if ' LAYOUT ' in line:
  m=re.search(r'LAYOUT label=(.*?) node=(\d+) depth=(\d+) identity=(\d+) semanticsId=(\d+) parentIdentity=(\d+|null) size=(\d+)x(\d+) coordinatesSize=(\d+) x (\d+)',line)
  layouts.append(dict(label=m[1],nodeId=int(m[2]),depth=int(m[3]),identity=int(m[4]),semanticsId=int(m[5]),parentIdentity=None if m[6]=='null' else int(m[6]),
    layoutSize=[int(m[7]),int(m[8])],coordinateSize=[int(m[9]),int(m[10])],screenOrigin=point(line,'screenOrigin'),windowOrigin=point(line,'windowOrigin'),rootOrigin=point(line,'rootOrigin')))
save('geometry-parsed.json',dict(origin='Parsed from original XML system-out; raw XML authoritative',matches=matches,nodes=nodes,layouts=layouts))
field=next(n for n in nodes if n['selected'] and n['setText']);jump=next(n for n in nodes if n['label']=='跳转')
fieldLayouts=[v for v in layouts if v['nodeId']==field['id']];jumpLayouts=[v for v in layouts if v['nodeId']==jump['id']]
inputContainer=next(v for v in fieldLayouts if v['depth']==2)
pair=next(v for v in fieldLayouts if v['depth']==3);flow=next(v for v in fieldLayouts if v['depth']==4)
assert pair['identity']==jumpLayouts[1]['identity'] and flow['identity']==jumpLayouts[2]['identity']
innerGap=jump['screenBounds'][0]-field['screenBounds'][2]
containerRight=inputContainer['screenOrigin'][0]+inputContainer['layoutSize'][0]
outerGap=jump['screenBounds'][0]-containerRight
innerOverlap=min(field['screenBounds'][3],jump['screenBounds'][3])-max(field['screenBounds'][1],jump['screenBounds'][1])
source='app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopFishAudioPanel.kt'
raw=(R/source).read_bytes();normalized=raw.replace(b'\r\n',b'\n');assert git('show','HEAD:'+source)==normalized
text=normalized.decode('utf-8');start=text.index('internal fun DesktopFishField(');end=text.index('\n}\n',start)+2;snippet=text[start:end]
assert '.padding(8.dp)' in snippet and '.fillMaxWidth()' in snippet
(G/'DesktopFishField-source-excerpt.txt').write_text(snippet+'\n',encoding='utf-8')
save('field-source-identity.json',dict(path=source,gitBlob=git('rev-parse','HEAD:'+source).decode().strip(),rawSha256=sha(raw),normalizedSha256=sha(normalized),worktreeEqualsGitNormalized=True,excerptStartLine=text[:start].count('\n')+1,excerptPurpose='Existing 8dp padding and fillMaxWidth establish that editable semantics bounds describe inner text, not painted input border'))
pad=(field['layoutSize'][0]-(field['screenBounds'][2]-field['screenBounds'][0]))/2
outerField=[field['screenBounds'][0]-pad,field['screenBounds'][1]-pad,field['screenBounds'][2]+pad,field['screenBounds'][3]+pad]
assert innerGap==16 and outerGap==8 and pad==8
save('root-cause.json',dict(classification='PROVEN_TEST_OBSERVATION',scope='First 1100dp adjacency RED only; not complete UI acceptance',
    inputSelectedNode=field,jumpSelectedNode=jump,innerSemanticsGapPx=innerGap,innerVerticalOverlapPx=innerOverlap,
    consistentWindowGapPx=jump['boundsInWindow'][0]-field['boundsInWindow'][2],consistentRootGapPx=jump['boundsInRoot'][0]-field['boundsInRoot'][2],
    observedInputContainer=inputContainer,observedPairRow=pair,observedFlowRow=flow,outerContainerToButtonGapPx=outerGap,
    reconstructedPaintedInputBorderScreenLTRB=outerField,paintedInputBorderMeasurement='DERIVED from observed 72x19 editable bounds, 88x35 layout size and unchanged symmetric 8dp padding; not a directly logged outer coordinator rectangle',
    paintedInputButtonVerticalOverlapPx=min(outerField[3],jump['screenBounds'][3])-max(outerField[1],jump['screenBounds'][1]),
    duplicateMeaning='Input label has two matches: static label and editable SetText node; selected functional node is correct for input operations, wrong measurement area for outer-control adjacency',
    coordinateOriginMixProven=False,reason='Screen/window/root gaps all 16. The false RED compares input inner text bounds to full button bounds; actual 88dp container and full button have 8px gap',
    productionLayoutViolationProven=False,nextMinimalScope='Only assertPagerLayout measurement helper: compare actual input container/outer border and button outer border in one coordinate frame; retain 12px and all operation assertions; keep SetText node for editing; no production repair implemented'))
save('result.json',dict(exitCode=1,compilation='PASS',suites=1,tests=1,reportedPass=0,failures=1,errors=0,skipped=0,testSeconds=float(test.attrib['time']),xmlSha256=sha(xml.read_bytes()),
    failure=failure.attrib,stack=failure.text,assertionLine=300,callerLine=522,geometryRecordCount=len(lines),requestedWidthDp=1100,
    gateInvocations=1,retriesAfterRed=0,branches=dict(first1100Geometry='OBSERVED; adjacency assertion FAIL',width720='NOT REACHED',width360='NOT REACHED',drag='NOT REACHED',wheel='NOT REACHED',pageJump='NOT REACHED',intBoundary='NOT REACHED'),
    historicalR5InputPass='Historical only; not rerun',nextGates='NOT TESTED / NOT STARTED',classification='PROVEN_TEST_OBSERVATION'))
save('requests-cleanup.json',dict(realFishGet=0,realFishTts=0,mockHttp=0,syntheticCatalogCalls=1,bindings=0,
    source='Raw WINDOW geometry snapshot queries=1 http=0 bindings=0; failure precedes any preview or paging operation',
    nativeFixtureStarted=True,nativeFixtureFinally='Owner restore/window dispose/scope-runtime drain/no-mp3-part assertions completed without cleanup failure',
    audioGenerated=False,manualFakeStarted=False,launcher='NOT STARTED',appJvm='NOT STARTED',rootLock='NOT TESTED',postGateJavaCount=0))
history=[]
for folder in ['p8-s2b-ui-layout-001-r3-20261019','p8-s2b-ui-layout-001-r4-20261019','p8-s2b-ui-layout-001-r5-20261019','p8-s2b-ui-layout-001-r6a-20261019']:
 p='docs/desktop/evidence/'+folder;tree=git('rev-parse',BASE+':'+p).decode().strip();assert not git('diff','--name-only','HEAD','--',p).strip()
 history.append(dict(path=p,gitTreeAtStart=tree,gitTreeNow=git('rev-parse','HEAD:'+p).decode().strip(),worktreeUnchanged=True))
save('history-preservation.json',history)
save('skill-review.json',dict(reviewedBeforeEvidenceCommit=True,skills=['chatbar-fish-audio-voice','chatbar-shadcn-compose'],edits=[],reason='Evidence-only test diagnostic changes no accepted product behavior or skill contract'))
E.mkdir(exist_ok=False);(E/'.gitattributes').write_bytes(b'* -text\n')
for folder in ['baseline-sources','01-pager-geometry-single-method']:shutil.copytree(G/folder,E/folder)
for name in ['preflight.json','preflight-resources.json','post-gate-resources.json','diagnostic-source.kt','diagnostic-only.patch','diagnostic-identity.json','source-preservation.json','stage-summary.json','geometry-system-out.txt','geometry-raw.jsonl','geometry-parsed.json','DesktopFishField-source-excerpt.txt','field-source-identity.json','root-cause.json','result.json','requests-cleanup.json','history-preservation.json','skill-review.json','run-stage.ps1','summarize-stages.py','freeze-evidence.py']:
 shutil.copyfile(G/name,E/name)
print(json.dumps(dict(evidence=E.relative_to(R).as_posix(),geometryLines=len(lines),nodeCandidates=len(nodes),innerGap=innerGap,outerContainerGap=outerGap,classification='PROVEN_TEST_OBSERVATION',xmlSha256=sha(xml.read_bytes()))))
