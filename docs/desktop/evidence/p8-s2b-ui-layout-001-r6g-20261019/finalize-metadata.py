from pathlib import Path
import hashlib,json,subprocess,xml.etree.ElementTree as ET
R=Path.cwd();G=Path(__file__).resolve().parent;E=R/'docs/desktop/evidence/p8-s2b-ui-layout-001-r6g-20261019'
def sha(b):return hashlib.sha256(b).hexdigest()
def save(n,v):(E/n).write_text(json.dumps(v,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
stages=json.loads((E/'stage-summary.json').read_text())
ledger=[]
for s in stages:
 for p in sorted((E/s['stage']/'junit').glob('TEST-*.xml')):
  r=ET.parse(p).getroot();ledger.append(dict(path=p.relative_to(E).as_posix(),bytes=p.stat().st_size,sha256=sha(p.read_bytes()),suite=r.attrib,testCases=[dict(t.attrib,status='SKIPPED' if t.find('skipped') is not None else 'FAIL' if t.find('failure') is not None or t.find('error') is not None else 'PASS') for t in r.findall('testcase')]))
save('junit-ledger.json',ledger)
source='docs/desktop/evidence/p8-s2b-ui-layout-001-r3-20261019/bounded.init.gradle'
old=(R/source).read_bytes();new=(E/'bounded.init.gradle').read_bytes();assert old==new
save('bounded-policy.json',dict(source=source,bytes=len(new),sha256=sha(new),identicalRawBytes=True,exclusions=['WindowsBackgroundViewerTest','WindowsStudioEdgeDragTest','WindowsWindowChromeTest','DesktopImageWindowPresentationTest.short tool workspace scrolls all actions without consuming preview space'],nativeUxExcluded=False,newExclusions=0))
native=E/'native-in-bounded.json'
nativePass=False
if native.exists():
 n=json.loads(native.read_text());nativePass=n['suite']['tests']=='4' and n['suite']['failures']=='0' and n['suite']['errors']=='0' and n['suite']['skipped']=='0'
http=[]
for s in stages:
 p=E/s['stage']/'http-followup-diagnostics.jsonl'
 if p.exists():
  rows=[json.loads(l) for l in p.read_text(encoding='utf-8-sig').splitlines() if l.strip()]
  http.append(dict(stage=s['stage'],httpStatusScenarios=[r for r in rows if 'responseCode' in r],injectedNetworkFaults=[r for r in rows if 'injectedNetworkFault' in r],recordedPhysicalRequests=sum(r.get('serverRequests',0) for r in rows),secretHeaderLeaks=sum(r.get('wireSecretHeaders',0) for r in rows)))
save('request-accounting.json',dict(realFishGet=0,realFishTts=0,manualFakeServiceStarted=False,nativeUxInBounded=dict(completed=nativePass,mockSampleGet=5 if nativePass else None,mockTtsPost=5 if nativePass else None,syntheticCatalogCalls=28 if nativePass else None,bindings=0 if nativePass else None,basis='Frozen Native test completed loops plus per-fixture physical request/type assertions: Stop4GET+4POST; resize1GET+1POST; Escape/pagination HTTP0. Catalog8+8+1+11. Not packet capture.'),httpStatusDiagnosticSubset=http,allSuiteMockAggregate='NOT INSTRUMENTED; do not sum subset counters into a whole-suite physical total; full per-scenario assertions and raw XML retained',networkBoundary='Only existing synthetic fixtures and loopback test services; no live Fish harness, real credentials, chat/card packages or user data roots accessed'))
children=[]
for s in stages:
 for p in (E/s['stage']).glob('*process*.jsonl'):
  children.append(dict(stage=s['stage'],path=p.relative_to(E).as_posix(),records=[json.loads(l) for l in p.read_text(encoding='utf-8-sig').splitlines() if l.strip()]))
save('cleanup-processes.json',dict(childProcessRecords=children,nativeFixtures=21 if nativePass else None,nativeFinally='PASS: owner restoration, own windows disposal, scope/runtime closeAndDrain and no owned mp3/part assertions' if nativePass else 'Consult actual failure and reached scopes',emptyTempDirectories='No blanket removal; fixture roots may remain empty',postGateResourceFile='POST-preflight.json',newPackagedLauncherJvm='NOT STARTED / NOT TESTED',formalPackagedRootLock='NOT TESTED; child LOCK fixture is independent synthetic evidence',manualHearing='NOT TESTED',absolutePhysicalIoDeadline='NOT PROVEN',systemConfiguration='No pagefile, driver, global proxy, certificates or user process changes'))
save('skill-review.json',dict(reviewedBeforeEvidenceCommit=True,sourceCommit=json.loads((E/'closing-state.json').read_text(encoding='utf-8'))['sourceCandidate'] or 'NOT CREATED due to HOLD',skills=['chatbar-fish-audio-voice','chatbar-shadcn-compose'],edits=[],reason='Frozen presentation with authorized test-only MouseEvent ownership correction and diagnostics; no described runtime/protocol/storage/binding contract or UI-kit component changes. Existing unrelated F2 sample-map lag is outside this narrowly authorized scope.',androidPackaging='Not applicable; explicit Desktop regression-only contract prohibits packaging'))
save('scope-review.json',dict(noProductionEditsInR6G=True,authorizedNativeObserverTestDelta=True,frozenThreeSources=True,sourceCandidateOnlyAfterAllGates=True,changes='Existing Escape priority, visible Stop, persistent scrollbar, explicit compact pagination and synthetic Native validation drafts checkpointed only if GREEN',noChanges=['Main lifecycle','Controller','HTTP','runtime','player','store','SecretStore','CharacterInfo','Prompt','Package/Entity/schema','CURRENT','parity','baseline'],notExecuted=['new distribution','Windows manual/hearing gate','merge/release','S3/G2/P9/P10/P11']))
print(json.dumps(dict(xmlCount=len(ledger),nativePass=nativePass,statusDiagnostics=http and [(h['stage'],h['recordedPhysicalRequests']) for h in http])))
