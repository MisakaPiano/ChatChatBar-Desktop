from pathlib import Path
import hashlib,json,subprocess,shutil
R=Path.cwd();G=Path(__file__).resolve().parent;E=R/'docs/desktop/evidence/p8-s2b-ui-layout-001-r6a-20261019'
BASE='f4556199aee10c87977ddaa3224574af5bf14310'
def git(*a):return subprocess.check_output(['git',*a],stderr=subprocess.PIPE)
def sha(b):return hashlib.sha256(b).hexdigest()
def save(n,v):(G/n).write_text(json.dumps(v,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
assert git('rev-parse','HEAD').decode().strip()==BASE and not git('diff','--cached','--name-only').strip()
s=json.loads((G/'baseline-source.json').read_text(encoding='utf-8-sig'));paths=[f['path'] for f in s['files']]
assert set(git('diff','--name-only').decode().splitlines())==set(paths[:2])
ledger=[]
for f in s['files']:
 before=(G/'baseline-sources'/Path(f['path']).name).read_bytes();after=(R/f['path']).read_bytes()
 assert len(before)==f['bytes'] and sha(before)==f['sha256']
 if f['path']!=paths[2]:assert before==after
 ledger.append(dict(path=f['path'],r5Bytes=len(before),r5Sha256=sha(before),currentBytes=len(after),currentSha256=sha(after),unchanged=before==after))
assert (R/paths[2]).read_bytes()==(G/'diagnostic-source.kt').read_bytes()
old=(G/'baseline-sources'/Path(paths[2]).name).read_text(encoding='utf-8');new=(R/paths[2]).read_text(encoding='utf-8')
a=new.index('            // Read-only diagnostics precede every geometry assertion');z=new.index('            controls.forEach { (label, rect)',a)
assert new[:a]+new[z:]==old
save('source-preservation.json',dict(files=ledger,onlyDiagnosticInsertionChanged=True,sourceUnchangedAfterCompileRed=True,allOriginalAssertionsWidthsOrderAndNativeInputUnchanged=True))
stage=G/'01-pager-geometry-single-method';meta=json.loads((stage/'stage.json').read_text(encoding='utf-8-sig'))
assert meta['exitCode']==1 and not list((stage/'junit').glob('*.xml'))
errors=(stage/'gradle.stderr.log').read_text(encoding='utf-8-sig')
assert "Unresolved reference 'positionOnScreen'" in errors and "Unresolved reference 'positionInWindow'" in errors and "Unresolved reference 'id'" in errors
save('result.json',dict(gateInvocations=1,exitCode=1,failedTask=':desktopApp:compileTestKotlin',testMethodExecuted=False,junitXmlCount=0,junitXmlSha256=None,
    compileErrors=[dict(line=264,column=47,member='positionOnScreen'),dict(line=264,column=73,member='positionInWindow'),dict(line=267,column=94,member='id')],
    diagnosticCompileCause='SemanticsNode.root returns RootForTest, not SemanticsNode; the new diagnostic wrongly uses SemanticsNode geometry/id members on it',
    paginationRootCause='UNKNOWN',newGeometryRecords=0,selectedNodeAndDuplicateAnalysis='NOT OBSERVED',actual1100PagerRects=None,horizontalGap=None,
    branches={w:'NOT REACHED / compilation failed' for w in ['1100dp','720dp','360dp','drag','wheel','page jump','unknown total','Int boundary']},
    historicalR5InputPass='Historical only; no new input gate executed',newNativeUxFull='NOT TESTED',newBounded='NOT TESTED',newWindowsManual='NOT TESTED',
    retriesAfterRed=0,diagnosticFixImplemented=False,
    nextMinimalScope='Only the new assertPagerLayout diagnostics: obtain a typed SemanticsNode ancestor through its public parent chain or explicitly derive a consistent window origin; leave old assertions/12px/Robot/production unchanged; requires next Project authorization'))
save('pagination-geometry-status.json',dict(status='NO RUNTIME OBSERVATION / COMPILE RED',marker='FISH_R6A_PAGER_GEOMETRY',recordCount=0,reason='Test never started; empty observation is not evidence of valid or invalid product geometry'))
save('request-and-cleanup.json',dict(realFishGet=0,realFishTts=0,mockHttp=0,catalogCalls=0,bindings=0,fixtureStarted=False,fixtureRootCreated=False,nativeWindowsOpened=False,audioCreated=False,
    launcher='NOT STARTED',applicationJvm='NOT STARTED',rootLock='NOT TESTED',fakeService='NOT STARTED',postStageJavaProcessCount=0,
    cleanup='No fixture I/O lifecycle was entered; Gradle command completed exit1; post-stage Java/javaw inventory empty'))
history=[]
for folder in ['p8-s2b-ui-layout-001-r3-20261019','p8-s2b-ui-layout-001-r4-20261019','p8-s2b-ui-layout-001-r5-20261019']:
 p='docs/desktop/evidence/'+folder;tree=git('rev-parse',BASE+':'+p).decode().strip()
 assert not git('diff','--name-only','HEAD','--',p).strip()
 history.append(dict(path=p,gitTreeAtStart=tree,gitTreeNow=git('rev-parse','HEAD:'+p).decode().strip(),worktreeUnchanged=True))
save('history-preservation.json',history)
save('skill-review.json',dict(reviewedBeforeEvidenceCommit=True,skills=['chatbar-fish-audio-voice','chatbar-shadcn-compose'],edits=[],reason='Compile RED diagnostic and evidence only; no accepted product contract changed'))
E.mkdir(exist_ok=False);(E/'.gitattributes').write_bytes(b'* -text\n')
for folder in ['baseline-sources','01-pager-geometry-single-method']:shutil.copytree(G/folder,E/folder)
for name in ['baseline-source.json','diagnostic-source.kt','diagnostic-only.patch','diagnostic-identity.json','preflight.json','preflight-resources.json','post-red-resources.json','semantics-node-public-api.txt','stage-summary.json','source-preservation.json','result.json','pagination-geometry-status.json','request-and-cleanup.json','history-preservation.json','skill-review.json','run-stage.ps1','summarize-stages.py','freeze-evidence.py']:
 shutil.copyfile(G/name,E/name)
print(json.dumps(dict(evidence=E.relative_to(R).as_posix(),testRawBytes=len((R/paths[2]).read_bytes()),testRawSha256=sha((R/paths[2]).read_bytes()),xmlCount=0,stageExit=1)))
