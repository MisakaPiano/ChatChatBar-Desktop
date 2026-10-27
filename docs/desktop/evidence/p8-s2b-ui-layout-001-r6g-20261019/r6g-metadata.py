from pathlib import Path
import hashlib, json, shutil, subprocess, xml.etree.ElementTree as ET
R=Path.cwd(); G=Path(__file__).resolve().parent
E=R/'docs/desktop/evidence/p8-s2b-ui-layout-001-r6g-20261019'
def sha(b): return hashlib.sha256(b).hexdigest()
def save(name,value): (E/name).write_text(json.dumps(value,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
shutil.copytree(G/'baseline-sources',E/'baseline-sources')
p=E/'01-native-method/junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml'
b=p.read_bytes(); root=ET.fromstring(b); out=root.findtext('system-out') or ''; lines=out.splitlines()
test=root.find('testcase'); failure=test.find('failure')
(E/'native-system-out.txt').write_text(out,encoding='utf-8')
records=[line for line in lines if line.startswith(('FISH_R6G_','FISH_R5_','FISH_R4_','FISH_R6C_PAGER_OUTER','FISH_R6D_PAGER_ROWS'))]
(E/'awt-ownership-observations.jsonl').write_text(''.join(json.dumps({'record':line},ensure_ascii=False)+'\n' for line in records),encoding='utf-8')
owner=[line for line in lines if line.startswith('FISH_R6G_OWNER_CHECK ')]
events=[line for line in lines if line.startswith('FISH_R6G_MOUSE ')]
plans=[line for line in lines if line.startswith('FISH_R5_PRESS ')]
admission=[line for line in lines if line.startswith('FISH_R5_ADMISSION ')]
assert len(owner)==1 and len(plans)==1 and len(admission)==3 and len(events)==0
save('event-ownership-result.json',{
 'xmlBytes':len(b),'xmlSha256':sha(b),'suite':root.attrib,'test':test.attrib,
 'failure':failure.attrib,'failureSourceLine':514,'compile':'PASS',
 'deterministicOwnershipAssertions':'PASS before real Robot input: old ancestor(self)null, new(self)child, old/new Skia descendant child, owner/detached rejected',
 'deterministicOwnerRecords':owner,'physicalPressPlanRecords':plans,'pointerAdmission':admission,
 'awtClassifications':{kind:sum('classification='+kind+' ' in line for line in events) for kind in ['OWNED_WINDOW_SELF','OWNED_DESCENDANT','OTHER_IN_JVM']},
 'observationSnapshots':[line for line in lines if line.startswith('FISH_R6G_OBSERVED ')],
 'actualPressComponent':'NOT OBSERVED / cannot identify',
 'oldLookupWouldMissActualPress':'UNKNOWN because no matching real MouseEvent was observed',
 'newLookupCoversStaticWindowSelfGap':'PROVEN by deterministic public AWT object assertions, not physical event injection',
 'r6fHistoricalPress0RootCause':'UNKNOWN; no Window-self/descendant/region-other event captured in new run',
 'wholeNativeClass':'NOT RUN; only original method executed','bounded':'NOT STARTED due Gate A RED',
 'reached':{'width1100':'Outer pager geometry passed; stable pointer/Robot Press call reached; owned Press0 FAIL',
            'width720':'NOT REACHED','width360':'NOT REACHED','drag16':'NOT REACHED',
            'wheel':'NOT REACHED','pageJump':'NOT REACHED','unknownTotalIntBoundary':'NOT REACHED'},
 'newTimeoutOrRetry':False,'productionScrollbarDefect':'NOT PROVEN'
})
save('request-accounting.json',{'realFishGet':0,'realFishTts':0,'mockHttp':0,'syntheticCatalogCalls':1,'bindingCount':0,
 'basis':'Original finally snapshot queries1 requests0 bindings0; no preview action/later page branch executed; not packet capture',
 'manualFakeServiceStarted':False,'bounded':'NOT STARTED','realCredentialsOrUserDataRead':False})
save('cleanup-processes.json',{'nativeFixturesExecuted':1,'ownedWindows':'Synthetic picker and logical owner; diagnostics create no extra Window',
 'finally':'Original finally returned without replacing Press assertion: owner restore, window disposal, runtime/scope drain and mp3/part absence checks unchanged',
 'postJavaCount':0,'postResourceFile':'POST-preflight.json','emptyTempDirectories':'May remain; no broad deletion or independent post-fixture inventory',
 'newLauncherJvm':'NOT STARTED','formalRootLock':'NOT TESTED','systemPrivilegeOrConfigurationChange':False})
close=json.loads((E/'closing-state.json').read_text(encoding='utf-8'))
close.update(status='R6G HOLD',gateB='NOT STARTED',sourceCandidate=None,productionFilesFrozen=True,
 nativeOnlyAuthorizedObserverDelta=True,r6fPress0Cause='UNKNOWN',r6eError5Cause='UNKNOWN / unchanged',
 newRed='Gate A owned Press count expected1 actual0 at shifted line514',notReclassifiedAsWinAuto001=True)
save('closing-state.json',close)
items=json.loads((E/'source-identities.json').read_text(encoding='utf-8')); paths=[item['path'] for item in items]
patch=subprocess.check_output(['git','diff','--binary','HEAD','--',*paths[:2]],stderr=subprocess.PIPE)
data=(R/paths[2]).read_bytes(); content=data.splitlines(keepends=True); name=paths[2]
patch+=(f'diff --git a/{name} b/{name}\nnew file mode 100644\n--- /dev/null\n+++ b/{name}\n@@ -0,0 +1,{len(content)} @@\n').encode()+b''.join(b'+'+line for line in content)
(E/'full-source.patch').write_bytes(patch); delta=(E/'observer-only.patch').read_bytes()
save('source-diff-identity.json',{'base':'15d35ab8a46decdf710d09d12c9b0d3b87656444',
 'nativeObserverDelta':{'bytes':len(delta),'sha256':sha(delta),'addedLines':47,'removedLines':3},
 'fullDraftPatch':{'bytes':len(patch),'sha256':sha(patch),'newNativeLines':len(content)},
 'twoProductionRawBytesUnchanged':True,'sourceCheckpointCreated':False,'sourceDraftsNotStaged':True,'noChangeAfterGate':True})
old=R/'docs/desktop/evidence/p8-s2b-ui-layout-001-r6e-20261019'; reused=[]
for stage in ['01-fish-ui-focused','02-affected-regression']:
 rows=[]
 for q in sorted((old/stage/'junit').glob('TEST-*.xml')):
  rows.append({'path':q.relative_to(R).as_posix(),'bytes':q.stat().st_size,'sha256':sha(q.read_bytes()),'suite':ET.parse(q).getroot().attrib})
 reused.append({'stage':stage,'sourceCommit':'1bb342f3228c0b054a3708dc770c400aa595cd03',
  'newR6gExecution':False,'productionSourceUnchanged':True,'nativeOnlyAuthorizedObserverDelta':True,'xml':rows,
  'scope':'Historical focused reference allowed, not a substitute for current failed Native Gate'})
save('reused-focused-evidence.json',reused)
save('skipped-classification.json',{'newJUnitSkipped':0,'gateA':'0 SKIPPED /1 FAIL',
 'gateB':'NOT RUN; historical symlink SKIPPED not re-executed or promoted','newExclusions':0})
save('evidence-processing-notes.json',{'initialInlineMetadataGenerator':'SyntaxError before execution; no raw artifact or source changed',
 'resolution':'Rewritten as this explicit metadata script; no test rerun or Kotlin changes',
 'initialRawStderrFileAvailable':False,'toolReportedExcerpt':'SyntaxError: invalid syntax in initial inline metadata generation'})
shutil.copyfile(__file__,E/Path(__file__).name)
for stage in json.loads((E/'stage-summary.json').read_text(encoding='utf-8')): print(stage['stage'],stage['resources'])
print('XML',len(b),sha(b)); print('full patch',len(patch),sha(patch))
