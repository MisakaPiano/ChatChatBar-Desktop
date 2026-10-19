from pathlib import Path
import json,hashlib,subprocess,shutil,difflib
R=Path.cwd();G=Path(__file__).resolve().parent
P=R/'docs/desktop/evidence/p8-s2b-ui-layout-001-r7-20261019';E=P/'manual-gate'
BASE='079428499fd3d4c36ef8ee2e6e60b3528d4787e4'
def git(*a):return subprocess.check_output(['git',*a],stderr=subprocess.PIPE).decode().strip()
def load(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(b):return hashlib.sha256(b).hexdigest()
def save(n,v):(E/n).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
assert git('rev-parse','HEAD')==BASE and not git('diff','--name-only') and not git('diff','--cached','--name-only')
assert not E.exists();E.mkdir();(E/'.gitattributes').write_bytes(b'* -text\n')
source=load(G/'source-preflight.json');package=load(G/'distribution-manifest.json')
for f in source['source']:assert sha((R/f['path']).read_bytes())==f['sha256']
for h in source['history']:assert git('rev-parse','HEAD:'+h['path'])==h['tree']
for f in load(P/'manifest.json')['members']:
 b=(P/f['path']).read_bytes();assert len(b)==f['bytes'] and sha(b)==f['sha256']
for f in package['files']:
 b=(G/'runtime-image'/f['path']).read_bytes();assert len(b)==f['bytes'] and sha(b)==f['sha256']
assert {f['path'] for f in package['files']}=={f.relative_to(G/'runtime-image').as_posix() for f in (G/'runtime-image').rglob('*') if f.is_file()}
result=load(G/'runs/manual01/process-result.json');fake=load(G/'mock-final.json');final=load(G/'manual-final-resource.json')
assert result['allOwnedProcessesExited'] and not result['processLifetimeBlocked'] and len(result['processes'])==2
assert all(p['exited'] and p['exitCode']==0 for p in result['processes'])
assert load(G/'active-lock.result.json')['exitCode']==75 and load(G/'post-exit-lock.result.json')['exitCode']==0
assert fake['exitCode']==0 and fake['exited'] and not fake['privateKeyRemains'] and not fake['trustRemains']
assert not final['java'] and not final['ownedApp'] and load(G/'post-exit-preview-files.json')['files']==[]
assert load(G/'before-synthetic-cards.json')==load(G/'after-synthetic-cards.json')
assert load(G/'mock-service/cleanup.json')['serverDrained']
files=['manual-start-preflight.json','manual-user-records.jsonl','manual-start-once.lock','manual-status.json',
 'before-synthetic-cards.json','after-synthetic-cards.json','active-lock.result.json','active-lock.stdout.log','active-lock.stderr.log',
 'post-exit-lock.result.json','post-exit-lock.stdout.log','post-exit-lock.stderr.log','post-exit-preview-files.json',
 'mock-process.json','mock-final.json','mock.stdout.log','mock.stderr.log','manual01.watch.stdout.log','manual01.watch.stderr.log',
 'm3-before-page-jump-counts.json','m3-page-jump-technical.json','m3-before-narrow-edit-counts.json','m3-narrow-input-technical.json',
 'm3-extra-query-observation.json','m3-extra-query-clarification.json','m4-before-resize-counts.json','m4-resize-technical.json','m4-before-cache-counts.json','m4-cache-technical.json',
 'manual-final-resource.json','manual-final-integrity.json','observe-owned-windows-original.ps1','observe-owned-windows.ps1','window-observer-initial-error.json']
for n in files:shutil.copyfile(G/n,E/n)
shutil.copytree(G/'runs',E/'runs');(E/'mock-service').mkdir()
for n in ['ready.json','events.jsonl','cleanup.json']:shutil.copyfile(G/'mock-service'/n,E/'mock-service'/n)
shutil.copyfile(Path(__file__),E/'collect-manual-evidence.py')
original=(G/'observe-owned-windows-original.ps1').read_text(encoding='utf-8');fixed=(G/'observe-owned-windows.ps1').read_text(encoding='utf-8')
(E/'read-only-observer-fix.patch').write_text(''.join(difflib.unified_diff(original.splitlines(True),fixed.splitlines(True),fromfile='original-build-only-observer.ps1',tofile='fixed-build-only-observer.ps1')),encoding='utf-8',newline='\n')
events=[json.loads(s) for s in (G/'mock-service/events.jsonl').read_text(encoding='utf-8').splitlines()]
http=[s for s in events if 'status' in s]
counts={kind:sum(s.get('interface')==kind for s in http) for kind in ['GET /model','GET sample','POST /v1/tts']}
assert counts=={'GET /model':8,'GET sample':3,'POST /v1/tts':2}
assert all(s['status']==200 and s.get('realRequests')==0 and not s.get('sampleSecretHeader') for s in http)
assert sum(s.get('event')=='HANG_SOCKET_DRAINED' for s in events)==2
save('request-accounting.json',dict(manual=counts,totalManualHttp=len(http),manualCatalogPages=[s['pageNumber'] for s in http if s['interface']=='GET /model'],
 waitingSampleRequests=2,waitingBodiesDrained=2,preparationSeparate=dict(catalogGet=2,sampleGet=1,fakeTtsPost=1),
 combinedSupportRequests=dict(catalogGet=10,sampleGet=4,fakeTtsPost=3),realFishGet=0,realFishTts=0,
 sampleSecretHeaderLeakObserved=False,manualAutomaticRetryCount='Not inferred from aggregate logs; Codex did not retry operations',
 extraOperations='User acknowledged extra Enter/jump/query and explained they were filling omitted items. Additional sample/POST counts retained, not erased or misclassified as proven client retries.',
 m3ExactSubmittedPages=load(G/'m3-page-jump-technical.json'),m4Resize=load(G/'m4-resize-technical.json'),m4CachedReplay=load(G/'m4-cache-technical.json')))
geometries=[]
for f in sorted((G/'runs/manual01').glob('*-windows.json')):
 d=load(f);visible=[w for w in d['ownedWindows'] if w['visible']]
 geometries.append(dict(file='runs/manual01/'+f.name,checkpoint=d['checkpoint'],utc=d['utc'],visibleOwnedWindows=visible))
save('geometry-observations.json',dict(observations=geometries,units='Native physical px plus DPI-derived dp',
 compactBreakpointDp=800,m1ActualClientDp=344,m3WideActualClientDp=1064,m3NarrowActualClientDp=519,
 m4BeforeResizeActualClientDp=956,m4AfterResizeActualClientDp=515,
 m2PostStopActualClientDp=1064,
 m2Caveat='User explicitly reported compact overlay Stop PASS. Native sample was taken after that report and was1064dp wide. It is not a synchronous measurement of the playing compact Popup; do not claim technical compact-Popup geometry PASS from this sample.',
 focusCaveat='Users returned focus to chat to report results. Owned foreground=false in such snapshots is not proof of missing focus during their action.',
 noInputInjected=True,ownerDisabledWhilePickerPresent=True,ownerEnabledAfterM1AndM4Close=True))
signatures=[json.loads(s) for s in (G/'manual-user-records.jsonl').read_text(encoding='utf-8').splitlines()]
groups=[dict(id='M1',manual='PASS',scope='344dp single-column floating filter; focus tag input; Escape dismisses panel then Picker; editor restores',technical='Native344dp and owner disable/restore recorded; exact key delivery is user report'),
 dict(id='M2',manual='PASS',scope='Actual sample sound/Stop; generated Chinese Stop reported in compact panel; waiting sample Stop; no binding',technical='Sample and fakeTTS recorded; two incomplete sample bodies drained; no current playback-position telemetry. Compact Popup playing geometry not synchronously verified'),
 dict(id='M3',manual='PASS WITH DECLARED OBSERVATION LIMIT',scope='Physical scrollbar drag+wheel; wide same-row pager; Enter3/click2/invalid4; narrow accessible paired input/jump',technical='Queries3 then2 exactly, no4; input-only branch contaminated by acknowledged extra submissions and is NOT INDEPENDENTLY VERIFIED'),
 dict(id='M4',manual='PASS',scope='R7 title/A target/original text retained across wide/narrow; first generated cache replay disclosed; sample/generated sound distinct; editor recovered/unbound',technical='Resize and cached replay HTTP delta0; persisted synthetic cards unchanged; parent input enabled after close')]
save('manual-results.json',dict(groupResults=groups,userGroupSignatures='4 PASS reports; M3 has explicit technical observation limit',manualFailuresReported=0,
 userJournalRecords=len(signatures),rawRecords='manual-user-records.jsonl',normalCloseUserReply='主窗口已正常关闭，没有异常',
 notTestedOrNotIndependentlyVerified=['Strict input-only no-query technical branch after extra submissions','Synchronous compact-Popup Stop playing geometry','Separate generation-wait cancellation branch','Native zero/negative/unknown-total/Int-limit cases this round','New freeform entry/maximize/device-error manual retest','Real provider GET/TTS and live CDN behavior'],
 sampleTextQuestion='User asked about transcript preview; source shows max2-line sample.text, fixture metadata is a descriptor not verbatim audio text. No UI/fixture change; complete expand/transcription capability not claimed.'))
save('exit-lock-cleanup.json',dict(launcherAndJvm=result['processes'],launcherJvmExit='0/0',allOwnedExited=result['allOwnedProcessesExited'],
 activeRootLockAcquireExit=75,postExitIndependentRootAcquireAndReleaseExit=0,fakeExit=0,fakeDrain='PASS',temporaryTlsPrivateAndTrustDeleted=True,
 previewMp3OrPartRemaining=0,syntheticCardHashesBeforeAfterEqual=True,noRemainingJava=not final['java'],noRemainingOwnedApp=not final['ownedApp'],
 appClosedByUser=True,automaticGuiClose=False,manualLifetimeSeconds=result['durationSeconds'],
 closeObservedUtcInWatch=result['closeObservedUtc'],closeMarkerCaveat='No close-request marker was written before user closure; raw watch field remains null. Actual production CLOSE_REQUESTED and complete shutdown stages are in stderr.log; user independently signed normal closure.',
 retained='Frozen package, synthetic data and fixed canned audio fixtures retained. Only run-scoped TLS materials and production-owned preview cache were removed; root lock file was not deleted.'))
save('verification-limits.json',dict(status='R7 WINDOWS SCOPED ACCEPTANCE CANDIDATE / DECLARED OBSERVATION LIMITS / PROJECT REVIEW PENDING',
 runtimeProductFailureProven=False,productSourceChanges=False,validationScriptIssue='Original read-only geometry script arithmetic failed once; only build script scalar parentheses corrected, original+error+delta preserved. Not a Kotlin, product or JUnit RED.',
 validationCountAssertion='M3 no-extra-event assertion raised due extra page1 requests; original UNKNOWN summary and observation retained. User acknowledged extra submissions to fill omissions; not classified as product automatic request failure. No retest or weakened product assertion.',
 r6D='Historical Native4/4 PASS with earlier test source',r6E='Fish15/15 + affected82/82 + boundedNative4/4 PASS; whole bounded cmd.exe RED',
 r6F='Whole bounded Native Press0 RED / UNKNOWN',r6G='Exact candidate Native test Press0 RED / UNKNOWN',r6I='INCONCLUSIVE / arming defect; user scroll report',r6J='INCONCLUSIVE / measured-thumb rejection; user scroll report',
 twoSymlinkSkipped='UNCHANGED',winAuto001='OPEN',winBrand001='OPEN / DEFERRED',uiLayout001='NOT CLOSED; Project must adjudicate',
 fullBoundedGreen=False,newRobotRuns=0,newJUnitRuns=0,newBoundedRuns=0,newR6RunnerLaunches=0,realFishGet=0,realFishTts=0,
 allHistoryTrees=source['history'],originalR7PreparationManifestUnchanged=True,
 userOwnedFiles=dict(pathsOnly=source['userOwnedUntrackedPathsOnly'],state='KEEP / UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED'),
 skillReview=dict(skill='.agents/skills/chatbar-fish-audio-voice/SKILL.md',updateNeeded=False,reason='Manual evidence only; no production feature contract/code change'),
 noMergeNoReleaseNoNextPhase=True))
(E/'README.md').write_text('''# R7 — packaged Windows manual gate

**R7 WINDOWS SCOPED ACCEPTANCE CANDIDATE / DECLARED OBSERVATION LIMITS / PROJECT REVIEW PENDING**.

Manual-start base `079428499fd3d4c36ef8ee2e6e60b3528d4787e4`; packaged build HEAD `bad9f3c235dbb1a88af5e3fb6cbdf4aa7418cbd5`; source candidate `ec32fdbc8383853b3024b585c19db0c3b840f47f`. Exactly one frozen R7 GUI instance and its loopback fake were launched after explicit readiness. No source or product Gradle changes, Robot, JUnit, bounded or R6 runner.

This directory is an append-only manual-stage supplement. The [preparation report](../README.md) and [original preparation manifest](../manifest.json) remain unchanged historical snapshots; this directory has its own manifest. Preparation NOT TESTED entries are not overwritten retroactively.

## User results and native observations

The user signed M1–M4 operation groups PASS; zero product FAIL reported. [Original records](manual-user-records.jsonl) retain exact answers/question IDs and later clarifications; collection UTC timestamps are not fabricated action timestamps. [Structured results](manual-results.json) distinguish human reports and technical proof.

- **M1 PASS:** actual Picker client344dp/DPI96; user saw filter floating over single-column results, focused tag input and reported first Escape dismisses panel, second closes Picker. Native parent disable/restore recorded.
- **M2 PASS reported:** user actually heard sample and Chinese canned generation; Stop icon+text visible and effective; waiting sample cancellation returned UI control. Two fake waiting sample bodies reached EOF/drain. **Caveat:** the post-report native snapshot was1064dp wide, not synchronous with compact-Popup playing. User's explicit compact-Popup report is retained, but synchronous native confirmation is NOT TESTED. No real TTS text-quality/content parity is claimed.
- **M3 PASS reported:** one requested physical scrollbar drag plus wheel, wide same-row pager, Enter→3 / click→2 / invalid4 rejected and new page starts top, narrow input/jump remain adjacent and accessible. Technical requests during the controlled page sequence were exactly[3,2], no4. Wide client1064dp; later narrow519dp. **Input-only limitation:** extra page1 queries appeared during the later editing interval; user acknowledged extra Enter/jump/query to fill omissions. Original count assertion/UNKNOWN and clarification are preserved; no automatic-query defect is proven, and strict input-only no-query is not independently verified. No repeat was requested.
- **M4 PASS:** titleR7, targetA and text retained across wide/narrow (sampled956dp→515dp); cached generation reused with disclosure, sampleA remained distinct; user closed Picker and reported editor usable/Alice unbound. Resize and cached replay each produced zero additional HTTP. Both persisted synthetic card hashes stayed equal.

See [actual geometry](geometry-observations.json), [request accounting](request-accounting.json), [limits](verification-limits.json). Owned foreground often false when user returned to chat; that is not proof of missing focus during their actions. No screenshot/semantic/offscreen state is substituted for hearing or human signatures.

## Single package and exit

All212 files were verified before launch and after exit against the [frozen distribution manifest](../distribution-manifest.json); no missing/extra/hash mismatch. Production source blobs/hashes and all historical evidence trees unchanged. [Final integrity](manual-final-integrity.json).

Native launcher PID4480 and JVM-host PID38324 both actually exited0. Production shutdown trace reached OWNERSHIP_RELEASED / MAIN_RETURNED / SAFE_EXIT_HANDOFF. User signed normal main-window closure without exception; application stayed open for1577.394s, with no automatic GUI timeout. The raw watch close-marker field remains null because no marker was written before user closure; it is not rewritten.

Active independent root acquire was refused75. After exit, independent acquire+release succeeded0. fake exit0/drained; ephemeral private key/truststore deleted. No preview MP3/.part remained, no Java/owned app process remained. Data, frozen package and fixed audio fixtures were retained; no root lock deletion or force kill. [Process result](runs/manual01/process-result.json), [cleanup](exit-lock-cleanup.json).

## Actual requests

Manual fake **catalogGET8 / sampleGET3 / cannedTTSPOST2**; two sample-body drain records are separate. Preparation fake **2/1/1** is kept separate. Real Fish **GET0/TTS0** throughout. All logged sample requests had no secret header; fake never forwards upstream. Extra operations and counts are preserved, not erased or turned into a proven client retry. No system-wide packet capture was performed.

## Validation issues retained

The read-only native geometry observer initially failed on PowerShell array subtraction. Only ignored build helper parentheses were corrected; [original error](window-observer-initial-error.json), original/fixed scripts and [delta](read-only-observer-fix.patch) retained. No product input or source change. The M3 event-count assertion was a validation observation with intervening user submissions, not a new JUnit/product RED. Both issues remain visible.

User also asked about sample transcript preview. Current cards display service sample.text at most2 lines/ellipsis; R7 fake text is a descriptor, not a verbatim audio transcript. No expanded transcript/automatic transcription claim or opportunistic UI/fixture change.

Historical R6D/R6E NativePASS, R6E overall cmd.exeRED, R6F/R6G Robot Press0RED/UNKNOWN, R6I/R6J INCONCLUSIVE and user scroll reports, old symlink SKIPPED/NOT TESTED remain intact. WIN-AUTO-001 and WIN-BRAND-001 remain OPEN. No full bounded GREEN, UI closure, desktop merge, release or later Phase.

Only this evidence supplement may be committed. Four user-owned files remain unread/unmodified/untracked/unstaged. No executable/JAR/audio/profile/credential/private TLS material is included. **STOP and return to Project for independent scoped adjudication, including the declared observation limits.**
''',encoding='utf-8',newline='\n')
members=[]
for f in sorted(E.rglob('*')):
 if f.is_file() and f!=E/'manifest.json':
  b=f.read_bytes();members.append(dict(path=f.relative_to(E).as_posix(),bytes=len(b),sha256=sha(b)))
save('manifest.json',dict(algorithm='SHA-256',selfExcluded=True,members=members))
print(json.dumps(dict(files=len(members)+1,userJournalRecords=len(signatures),manualFake=counts,manifestSha256=sha((E/'manifest.json').read_bytes()))))
