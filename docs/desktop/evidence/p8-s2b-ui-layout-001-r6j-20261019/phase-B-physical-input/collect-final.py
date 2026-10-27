from pathlib import Path
import json, hashlib, shutil, re, subprocess

R=Path.cwd()
G=Path(__file__).resolve().parent
E=R/'docs/desktop/evidence/p8-s2b-ui-layout-001-r6j-20261019'
RUN='f45e6c7a-1aa2-4152-8499-4d6669fffd47'
BASE='e0bd3c1e48a2cf2849f6eb4e5600b84bbb39e45c'
def sha(b): return hashlib.sha256(b).hexdigest()
def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))
def save(p,v):
 p.parent.mkdir(parents=True,exist_ok=True)
 p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def git(*a): return subprocess.check_output(['git',*a],stderr=subprocess.PIPE).decode().strip()
assert git('rev-parse','HEAD')==BASE and not git('diff','--cached','--name-only')
frozen=G/'phase-A-frozen'
for m in read(frozen/'manifest.json')['members']:
 b=(frozen/m['path']).read_bytes()
 assert len(b)==m['bytes'] and sha(b)==m['sha256']
shutil.copytree(frozen,E/'phase-A-preparation',dirs_exist_ok=True)
B=E/'phase-B-physical-input'
B.mkdir(exist_ok=True)
for name in ['02-human-physical','manual-control']:
 shutil.copytree(G/name,B/name,dirs_exist_ok=True)
for name in ['BEFORE-B-preflight.json','AFTER-B-preflight.json','user-start-authorization.json','run-registration.json',
 'armed-verification.stdout.json','armed-verification.stderr.log','capture-verification.stdout.json','capture-verification.stderr.log']:
 shutil.copyfile(G/name,B/name)
post=read(G/'AFTER-B-preflight.json')
assert not post['relevantProcesses']
for s in post['source']:
 assert sha((R/s['path']).read_bytes())==s['expected']
assert not post['index']
raw=(G/'02-human-physical/gradle.stdout.log').read_bytes()
try: log=raw.decode('utf-8'); encoding='utf-8'
except UnicodeDecodeError: log=raw.decode('cp936'); encoding='cp936'
(B/'stdout-derived-utf8.txt').write_text(log,encoding='utf-8')
markers=[s for s in log.splitlines() if any(x in s for x in ['FISH_R6J_', 'FISH_R6H_NATIVE_HITTEST','FISH_R6G_MOUSE'])]
(B/'input-markers-derived-utf8.txt').write_text('\n'.join(markers)+'\n',encoding='utf-8')
events=[s for s in log.splitlines() if s.startswith('FISH_R6G_MOUSE ')]
counts={str(i):sum(bool(re.search(r'\bid='+str(i)+r'\b',s)) for s in events) for i in [501,506,502]}
assert counts=={'501':1,'506':30,'502':1}
ctrl=G/'manual-control'/RUN
assert not (ctrl/'finish.request').exists() and not (ctrl/'capture-complete.txt').exists()
result=dict(line.split('=',1) for line in (ctrl/'result.txt').read_text(encoding='utf-8').splitlines() if '=' in line)
samples=[json.loads(s) for s in (G/'02-human-physical/resources.jsonl').read_text(encoding='utf-8-sig').splitlines() if s]
stage=read(G/'02-human-physical/stage.json')
assert stage['exitCode']==1 and result['cleanup']=='true'
save(B/'analysis.json',dict(runId=RUN,overall='INCONCLUSIVE',rawClassification=result['classification'],
 arming='PASS: same-run actual file and marker verified before instruction', baselineRange=0.0,
 measuredInitialThumb=dict(x=1120,y=155,width=8,height=104),actualPress=dict(x=1132,y=184),
 pressOutsideMeasuredRectangle=True,actualVisualThumbMissProven=False,
 win32Press=dict(ownedRoot=True,foregroundOwned=True,pointerCoordinatesMatch=True,leftDown=True),
 legacyOwnedAwtEvents=counts,humanRecorder=dict(press=1,drag=0,release=0,reason='Recorder held after initial measured-thumb rejection'),
 physicalEventChainObserved=True,completeBeforeAfterRangeAndFirstItem=False,afterRange=None,afterFirst=None,
 captureComplete=False,captureVerifierExit=2,verifierCorrectlyRejectedCompletion=True,
 finishRequestWritten=False,closeReason=result['closeReason'],cleanupReported=True,
 manualJavaExecExit=stage['exitCode'],durationSeconds=stage['durationSeconds'],
 runtimeException='Deliberate non-PASS exit after bounded cleanup; not evidence of a product crash',
 rawStdoutEncoding=encoding,rawBytesPreserved=True,
 resources=dict(samples=len(samples),minAvailableBytes=min(s['availableBytes'] for s in samples),
 minCommitHeadroomBytes=min(s['commitLimitBytes']-s['commitBytes'] for s in samples),maxPagesPerSecond=max(s['pagesPerSecond'] for s in samples)),
 javaAfterExit=post['relevantProcesses'],catalog=1,http=0,binding=0,realFishGet=0,realFishTts=0,
 robotTestsExecuted=0,junitTestsExecuted=0,boundedExecuted=False,launches=1,retries=0,
 notProven=['Visual thumb geometry matches measured rectangle','After-range / first item change','Historical Robot Press0 cause'],
 nextCandidate='Project review of measured thumb origin versus actual visible/hit layout; no repair or repeat authorized here'))
save(B/'user-record.json',dict(
 actionQuestionId='call_ffe648d2eb2341189eb498c233c4077a',
 exactActionReply='已拖动一次并释放，先保持窗口',
 experienceQuestion='本次记录已停止并保存不完整结果，请勿重拖。你这一次拖动时，音色列表实际是否向下滚动？也请确认操作已结束，允许我写入同一 run ID 的 finish.request 正常关闭测试窗口。',
 experienceReply=None,experienceStatus='NOT SIGNED at evidence collection; no scroll success inferred',
 finishAuthorization=None,timeout='Runner bounded 120-second confirmation timeout elapsed; no finish.request forged'))
save(B/'scope-and-preservation.json',dict(source=post['source'],indexBeforeStaging=post['index'],
 userFiles='Four protected paths remain untracked; contents unread, unmodified, unstaged',
 history=read(E/'history-preservation.json'),sourceChangesDuringB=False,
 skillReviewed='.agents/skills/chatbar-fish-audio-voice/SKILL.md',skillUpdateNeeded=False,
 reason='Evidence-only manual fixture result; production feature semantics unchanged',
 nativeFixtureCleanup='Result cleanup=true after existing dispose/server.close; no remaining JVM. Not a distribution/root-lock gate.'))
readme='''# R6J — controlled human input capture

Status: **INCONCLUSIVE / HOLD**. One manual launch; no retry. No product repair or source candidate commit.

## Observed

- Preparation compiler and deterministic protocol checks passed; frozen phase-A evidence is preserved byte-for-byte in [phase-A-preparation](phase-A-preparation/README.md).
- Same-run actual `armed.txt` and `FISH_R6J_ARMED` were verified before the drag instruction. Baseline range was 0, recorder registered and owned window valid.
- User said: “已拖动一次并释放，先保持窗口”. This is an action report, not a signed scroll-success result.
- The physical Press at (1132,184) reached the owned SkiaLayer. Read-only Win32 sampling confirmed owned hit root/foreground and matching pointer coordinates with left button down.
- Recorded initial thumb was (1120,155,8,104). Press was outside that measured rectangle. The dedicated human recorder immediately entered HELD with raw classification `MANUAL_HIT_MISS`.
- The independent existing owned-AWT observer retained 1 Press, 30 Drag and 1 Release. Do not confuse its counts with the truncated human recorder counts (1/0/0), or double-count duplicate R4 log lines.
- No CAPTURE_COMPLETE was produced; the capture verifier correctly rejected completion. After-range and after-first-item are absent. No success is inferred.
- Recorder held the window for its bounded confirmation period. It then closed with `CONFIRMATION_TIMEOUT`, cleanup=true. No finish.request was written and no user finish permission was fabricated. Manual JavaExec exited 1 after cleanup, not a JUnit result or proven product crash.
- Post-run inspection found no Java/javaw/relevant runner process. Catalog1 / HTTP0 / binding0; real Fish GET0/TTS0. No Robot, JUnit, bounded, packaging or distribution root-lock gate executed.

## Interpretation and limits

The Press did not satisfy the recorder's measured-thumb containment condition. This does **not** independently prove the user missed the actual visible thumb. Whether measured thumb origin/hit geometry matches the native visual target remains UNKNOWN. Complete physical-scroll acceptance is INCONCLUSIVE, not PASS or product failure.

R6F/R6G historical Robot Press0 causes remain UNKNOWN. Earlier Native PASS and all historical RED remain unchanged. UI-LAYOUT-001 remains HOLD; WIN-AUTO-001 and WIN-BRAND-001 remain OPEN.

Project may consider a narrow review of the thumb measurement versus actual layout before any next attempt. No such repair or repeat was performed here.

## Evidence

- [Raw control records](phase-B-physical-input/manual-control/f45e6c7a-1aa2-4152-8499-4d6669fffd47/result.txt)
- [Raw stdout](phase-B-physical-input/02-human-physical/gradle.stdout.log), [stderr](phase-B-physical-input/02-human-physical/gradle.stderr.log)
- [Derived analysis](phase-B-physical-input/analysis.json), [separate user record](phase-B-physical-input/user-record.json)
- [Post-run resources/process/source inspection](phase-B-physical-input/AFTER-B-preflight.json)
- [SHA-256 manifest](manifest.json)

Three engineering drafts remain unstaged/uncommitted. The four user-owned files remain unread/unmodified/untracked. Only this evidence directory may be committed. No CURRENT, baseline, parity, Prompt, Entity or runtime change.
'''
(E/'README.md').write_text(readme,encoding='utf-8')
manifest=[]
shutil.copyfile(Path(__file__),B/'collect-final.py')
for p in sorted(E.rglob('*')):
 if p.is_file() and p!=E/'manifest.json':
  b=p.read_bytes();manifest.append(dict(path=p.relative_to(E).as_posix(),bytes=len(b),sha256=sha(b)))
save(E/'manifest.json',dict(algorithm='SHA-256',selfExcluded=True,stage='R6J A+B / INCONCLUSIVE / HOLD',members=manifest))
for m in manifest:
 b=(E/m['path']).read_bytes();assert len(b)==m['bytes'] and sha(b)==m['sha256']
print(json.dumps(dict(members=len(manifest),manifestSha256=sha((E/'manifest.json').read_bytes()),eventCounts=counts,exit=stage['exitCode'])))
