from pathlib import Path
import hashlib,json,subprocess,xml.etree.ElementTree as ET
R=Path.cwd();G=Path(__file__).resolve().parent;E=R/'docs/desktop/evidence/p8-s2b-ui-layout-001-r6f-20261019'
def sha(b):return hashlib.sha256(b).hexdigest()
def save(n,v):(E/n).write_text(json.dumps(v,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
env=json.loads((E/'gate0-environment.json').read_text(encoding='utf-8-sig'))
save('gate0-assessment.json',dict(explicitExecutableOrAclAnomaly=False,systemCmdSignatureValid=all(x['signatureStatus']=='Valid' and x['microsoftSigner'] for x in env['cmdCandidates']),cmdShadowDetected=False,administratorRoleEnabled=env['privilege']['administratorRoleEnabled'],denyExecuteRules=env['acl']['denyExecuteRules'],effectiveProcessCreationRight='Not claimed from ACL alone; original test exercises it',securityEventResult='All three queried channels returned NoMatchingEventsFound for the R6E bounded time window and selected deny/audit IDs; does not prove absence of all security enforcement',rawFullEnvironmentOrAccountDump=False,securityConfigurationChanged=False,diagnosticHelperFirstAttempt='PowerShell boolean literal error, original script/error excerpt retained; corrected helper only, no test retry',rootCauseR6eError5='UNKNOWN'))
source='app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/DesktopImageClipboardFilesTest.kt'
blob=subprocess.check_output(['git','rev-parse','HEAD:'+source]).decode().strip();assert blob=='5ffa98db6c83d7fa181bd65e35724804d496676d'
rows=[]
for stage in ['01-junction-isolated','02-bounded-desktop']:
 p=E/stage/'junit/TEST-com.example.chatbar.desktop.DesktopImageClipboardFilesTest.xml'
 if not p.exists():continue
 r=ET.parse(p).getroot();t=next(t for t in r.findall('testcase') if t.attrib['name']=='junction target is never inspected changed or cleaned')
 status='SKIPPED' if t.find('skipped') is not None else 'FAIL' if t.find('failure') is not None or t.find('error') is not None else 'PASS'
 rows.append(dict(stage=stage,suite=r.attrib,test=dict(t.attrib,status=status),xmlBytes=p.stat().st_size,xmlSha256=sha(p.read_bytes()),junctionCreatedAndOriginalAssertionsReached=status=='PASS',basis='Unchanged original Windows test has no early-return/mock/skip path: ProcessBuilder mklink /J then clipboard copy, target bytes unchanged, link NOFOLLOW exists, target entry count1, link removed in finally; outer fixture finally recursive cleanup',cleanup='Original finally blocks completed without exception' if status=='PASS' else 'Consult actual failure; do not infer unreachable assertions',independentPostCleanupFilesystemInventory='NOT INSTRUMENTED'))
save('junction-verification.json',dict(testSource=source,testSourceGitBlob=blob,noKotlinModification=True,executions=rows,r6eHistoricalError5='Retained FAIL; root cause UNKNOWN even if new runs PASS'))
b=(R/source).read_text(encoding='utf-8')
(E/'junction-source-excerpt.txt').write_text('\n'.join(f'{i}: {l}' for i,l in enumerate(b.splitlines(),1) if i in range(15,22) or i in range(274,293))+'\n',encoding='utf-8')
old=R/'docs/desktop/evidence/p8-s2b-ui-layout-001-r6e-20261019'
reused=[]
for stage in ['01-fish-ui-focused','02-affected-regression']:
 ps=old/stage;stageInfo=json.loads((ps/'stage.json').read_text(encoding='utf-8-sig'));xml=[]
 for p in sorted((ps/'junit').glob('TEST-*.xml')):
  r=ET.parse(p).getroot();xml.append(dict(path=p.relative_to(R).as_posix(),bytes=p.stat().st_size,sha256=sha(p.read_bytes()),suite=r.attrib))
 reused.append(dict(sourceCommit='1bb342f3228c0b054a3708dc770c400aa595cd03',stage=stage,stageIdentity=stageInfo,xml=xml,newR6fExecution=False,reason='Same exact three frozen raw source hashes; R6E focused results retained, not rerun'))
save('reused-r6e-focused-evidence.json',reused)
close=json.loads((E/'closing-state.json').read_text(encoding='utf-8'));close['r6eError5RootCause']='UNKNOWN';close['r6eHistoryNotReclassified']=True;close['gate0NoExplicitExecutableOrPermissionAnomaly']=True;close['fishFocusedEvidence']='R6E 3/15 and8/82 reused, not R6F executions';save('closing-state.json',close)
skips=json.loads((E/'skipped-tests.json').read_text(encoding='utf-8'))
expected={('com.example.chatbar.desktop.DesktopDocumentIndexTransactionInspectorTest','real symlink requires executable platform fixture and is refused without following'),('com.example.chatbar.desktop.DesktopIndexStartupPreflightTest','real symbolic links in entities and domain are refused when supported')}
save('skipped-classification.json',dict(skips=skips,onlyKnownHistoricalSkips=all((s['suite'],s['test']) in expected for s in skips),newUnknownSkipped=[s for s in skips if (s['suite'],s['test']) not in expected],noSkipPromotedToPass=True))
print(json.dumps(dict(junction=[(r['stage'],r['test']['status'],r['test']['time']) for r in rows],rootCause='UNKNOWN',sourceCandidate=close['sourceCandidate'])))
