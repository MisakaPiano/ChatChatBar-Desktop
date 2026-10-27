from pathlib import Path
import json,hashlib,subprocess,shutil
R=Path.cwd();G=Path(__file__).resolve().parent
E=R/'docs/desktop/evidence/p8-s2b-ui-layout-001-source-candidate-20261019'
BASE='044919ed9fd0c75b56b1843644012ff263ce99c3'
CAND='ec32fdbc8383853b3024b585c19db0c3b840f47f'
def sha(b):return hashlib.sha256(b).hexdigest()
def git(*a):return subprocess.check_output(['git',*a],stderr=subprocess.PIPE).decode().strip()
def load(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def save(n,v):(E/n).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
assert git('rev-parse','HEAD')==CAND and git('rev-parse','HEAD^')==BASE
assert not git('diff','--cached','--name-only') and not git('diff','--name-only')
E.mkdir(exist_ok=True);(E/'.gitattributes').write_bytes(b'* -text\n')
p=load(G/'preflight.json');paths=[s['path'] for s in p['source']]
assert set(git('diff-tree','--no-commit-id','--name-only','-r',CAND).splitlines())==set(paths)
identities=[]
(E/'candidate-sources').mkdir(exist_ok=True)
for s in p['source']:
 b=(R/s['path']).read_bytes();h=sha(b)
 expected=s['sha256'] if s!=p['source'][2] else '8beee5971355cd894b44a4743f89b6ad857c342274ae8410cfab5e99a3bbb423'
 assert h==expected
 assert subprocess.check_output(['git','show',CAND+':'+s['path']])==b
 (E/'candidate-sources'/Path(s['path']).name).write_bytes(b)
 identities.append(dict(path=s['path'],bytes=len(b),sha256=h,gitBlob=git('rev-parse',CAND+':'+s['path'])))
backup=(G/'preserved-manual-runner.kt').read_bytes();assert len(backup)==86411 and sha(backup)==p['source'][2]['sha256']
(E/'preserved-manual-runner.kt').write_bytes(backup)
for h in p['history']:assert git('rev-parse','HEAD:'+h['path'])==h['tree']
for name in ['preflight.json','precompile-resource.json','postcompile-resource.json','static-source-audit.json','staged-source.patch','staged-source-stat.txt','run-stage.ps1']:
 shutil.copyfile(G/name,E/name)
shutil.copytree(G/'01-compile-only',E/'01-compile-only',dirs_exist_ok=True)
shutil.copyfile(Path(__file__),E/'collect-evidence.py')
stage=load(G/'01-compile-only/stage.json');raw=(G/'01-compile-only/gradle.stdout.log').read_bytes();log=raw.decode('utf-8')
assert stage['exitCode']==0 and '> Task :desktopApp:compileTestKotlin FROM-CACHE' in log
assert not list((G/'01-compile-only/junit').glob('*.xml'))
assert not any(line.startswith('> Task :desktopApp:test ') for line in log.splitlines())
resources=[json.loads(s) for s in (G/'01-compile-only/resources.jsonl').read_text(encoding='utf-8-sig').splitlines() if s]
save('candidate-identity.json',dict(base=BASE,sourceCandidate=CAND,parent=BASE,status='CONDITIONAL SOURCE CANDIDATE / PROJECT DIFF REVIEW PENDING',sources=identities,
 diffstat=git('show','--format=','--stat',CAND),formalBaseline='1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8',
 originDesktop='5ef95428421f7c693b010ac88f51d470804ab07a',localDesktopUnchanged='4c2af2576e2319e8041ffd2a6c5a205450f18246',
 manualRunnerBackup=dict(buildPath=str((G/'preserved-manual-runner.kt').relative_to(R)),gitignored=True,bytes=len(backup),sha256=sha(backup)),
 productionBytesUnchanged=True,r6gSource='docs/desktop/evidence/p8-s2b-ui-layout-001-r6g-20261019/tested-sources/DesktopFishPickerNativeUxTest.kt'))
save('current-validation.json',dict(command=stage['command'],exit=stage['exitCode'],seconds=stage['durationSeconds'],compileTestKotlin='FROM-CACHE / SUCCESS',
 freshKotlinCompilerInvocation=False,newJUnitTests=0,newRobotTests=0,newManualRunnerLaunches=0,bounded='NOT RUN',distribution='NOT BUILT',windowsManual='NOT TESTED',
 realFishGet=0,realFishTts=0,junitXml='NONE; compilation only',sourceDiffCheck='PASS',
 minAvailableBytes=min(s['availableBytes'] for s in resources),minCommitHeadroomBytes=min(s['commitLimitBytes']-s['commitBytes'] for s in resources),
 maxPagesPerSecond=max(s['pagesPerSecond'] for s in resources),javaAfter=load(G/'postcompile-resource.json')['java']))
hist=[]
root=R/'docs/desktop/evidence'
for phase,testpath,classification in [
 ('r6d','tested-source.kt','NativeUx 4/4 PASS; historical test identity, not candidate R6G'),
 ('r6e','tested-sources/DesktopFishPickerNativeUxTest.kt','Focused15/15 + affected82/82; bounded159/1526:1523 PASS/1 FAIL/2 SKIPPED; Native4/4 PASS; cmd.exe CreateProcess error5 cause UNKNOWN'),
 ('r6f','tested-sources/DesktopFishPickerNativeUxTest.kt','Junction isolated1/1 PASS; bounded1523 PASS/1 FAIL/2 SKIPPED; Native Press0 cause UNKNOWN'),
 ('r6g','tested-sources/DesktopFishPickerNativeUxTest.kt','Candidate exact version: compile PASS; native1 test/1 FAIL, owned Press0; observer correction valid but physical failure cause UNKNOWN'),
 ('r6h','tested-sources/DesktopFishPickerNativeUxTest.kt','Compile PASS; native1/1 FAIL at pre-Press destination safety gate; no Press/Drag/Release sent'),
 ('r6i','prepared-sources/DesktopFishPickerNativeUxTest.kt','INCONCLUSIVE / PROVEN_MANUAL_ARMING_PROCEDURE_DEFECT; user reported scroll; baseline1545 before armed, incomplete capture'),
 ('r6j','prepared-sources/DesktopFishPickerNativeUxTest.kt','ARMED verified; measured thumb containment rejected Press; owned AWT1Press/30Drag/1Release; complete range/first pair absent; INCONCLUSIVE; late user reported scroll PASS; cleanup by confirmation timeout')]:
 d=root/f'p8-s2b-ui-layout-001-{phase}-20261019';t=d/testpath
 # Use historical source copies only when that exact path exists; no guessed identity.
 hist.append(dict(phase=phase,classification=classification,readme=str((d/'README.md').relative_to(R)),
 testSourcePath=str(t.relative_to(R)) if t.exists() else None,testSourceBytes=len(t.read_bytes()) if t.exists() else None,testSourceSha256=sha(t.read_bytes()) if t.exists() else None))
assert hist[0]['testSourceSha256']=='fdddf82225d120777c96fd8127f1584f4139c2767e5bbf0436c12db31783d238'
assert hist[1]['testSourceSha256']==hist[0]['testSourceSha256']
assert hist[3]['testSourceSha256']==identities[2]['sha256']
save('history-and-limits.json',dict(history=hist,historyTrees=p['history'],allHistoryUnchanged=True,
 twoSymlinkSkipped=['DesktopDocumentIndexTransactionInspectorTest.real symlink requires executable platform fixture and is refused without following','DesktopIndexStartupPreflightTest.real symbolic links in entities and domain are refused when supported'],
 skippedNotPromoted=True,winAuto001='OPEN',winBrand001='OPEN / DEFERRED',uiLayout001='OPEN / WINDOWS MANUAL HOLD',
 fullBoundedGreenForCandidate=False,robotRootCauseResolved=False,newDistributionAccepted=False,
 ruleException='Candidate commit only; no change to release acceptance gates'))
untracked=git('ls-files','--others','--exclude-standard').splitlines()
protected=[x for x in p['untrackedPathsOnly'] if x!=paths[2]]
assert len(protected)==4 and all(x in untracked for x in protected)
save('protection-and-skill-review.json',dict(protectedPathsOnly=protected,status='KEEP / UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED',
 manualRunnerPreserved=True,skill='.agents/skills/chatbar-fish-audio-voice/SKILL.md',skillUpdateNeeded=False,
 reason='Existing production feature contract unchanged; candidate and test identity documented in evidence, not feature instructions',
 currentParityBaselineAndHistoricalEvidenceUnchanged=True))
(E/'README.md').write_text('''# UI-LAYOUT-001 — CONDITIONAL SOURCE CANDIDATE

Project-authorized source-candidate exception only. **Not final UI acceptance, full bounded PASS, Robot root-cause closure, or new Windows distribution acceptance.**

Source candidate: `ec32fdbc8383853b3024b585c19db0c3b840f47f`.
Direct parent: `044919ed9fd0c75b56b1843644012ff263ce99c3`.
Three files: **+787/-35** (Presentation +64/-29; Window +24/-6; new NativeUxTest +699).

## Exact source and preservation

The two production sources retain reviewed raw bytes: Presentation17883 / SHA256 `194d6b7fd2e270ca29eb90734303c8a7898825ecf8939cc60ab266e8d16c4917`; Window4280 / `a003e7ad1e31a0c356df1a4291cc7eb1e4eb9fa958a0a6353ee6a77ac8f88125`.

NativeUxTest is an exact raw-byte copy of R6G tested source:49505 bytes / `8beee5971355cd894b44a4743f89b6ad857c342274ae8410cfab5e99a3bbb423`. The four JUnit method bodies and pager/Robot driver section are byte-identical to the R6D source. R6G owned-window event observation correction remains; R6H–R6J temporary Win32/manual-runner/handshake code is absent.

The replaced86411-byte human runner is fully preserved in the ignored build directory and [this evidence copy](preserved-manual-runner.kt), SHA256 `32777017563fc625d3678a9acbde3b18d35262178ea8afdbbb15aa1984ccde77`. No historical record was overwritten.

## New execution

Exactly one authorized offline JDK17 `:desktopApp:compileTestKotlin`, single worker, no daemon/parallel, low priority, Kotlin in-process,2048MiB. Exit0,41.8857188s. **compileTestKotlin FROM-CACHE**: successful cache restoration, not a fresh compiler execution. Production compile was UP-TO-DATE. No clean/rerun-tasks, tests, Robot, manual runner, GUI, packaging or distribution gate. Real Fish GET0/TTS0. No residual Java process in post-inspection.

[Raw command/logs/resource sampling](01-compile-only/command.txt), [current validation](current-validation.json), [exact patch](staged-source.patch), [candidate identities](candidate-identity.json), [static comparison](static-source-audit.json).

## Historical limits retained

R6D Native4/4 PASS and R6E bounded Native4/4 PASS used test SHA `fdddf82225d120777c96fd8127f1584f4139c2767e5bbf0436c12db31783d238`, with these same production bytes. They are not a full GREEN result for candidate R6G test SHA. R6E full bounded had a cmd.exe CreateProcess error5 RED; R6F bounded had Native Press0 RED. R6G candidate version itself had1 test/1 FAIL after successful compilation. R6F/R6G physical-input causes remain UNKNOWN. R6H failed its pre-Press destination gate without sending Press.

R6I remains INCONCLUSIVE due pre-arming protocol defect despite user-reported scroll. R6J verified ARMED and observed owned physical input, but measured-thumb rejection prevented a complete before/after capture; INCONCLUSIVE remains despite late user-reported successful scroll. No manual report replaces missing instrumented data.

Two historical symlink JUnit SKIPPED and old NOT TESTED remain unchanged. WIN-AUTO-001 OPEN; WIN-BRAND-001 OPEN/DEFERRED. UI-LAYOUT-001 remains OPEN / WINDOWS MANUAL HOLD. See [historical identities and classifications](history-and-limits.json).

## Handoff

Only the three source files were included in the source commit. This evidence is a separate commit. Four user files remain unread/unmodified/untracked/unstaged; old local desktop is untouched. CURRENT, parity, baseline, dependencies, Prompt, entities and data contracts unchanged.

Project must review the actual Git diff and separately authorize any exact distribution / Windows manual gate. No automatic S3, later Phase, merge or release. **STOP / PROJECT REVIEW PENDING.**
''',encoding='utf-8',newline='\n')
members=[]
for f in sorted(E.rglob('*')):
 if f.is_file() and f.name!='manifest.json':
  b=f.read_bytes();members.append(dict(path=f.relative_to(E).as_posix(),bytes=len(b),sha256=sha(b)))
save('manifest.json',dict(algorithm='SHA-256',selfExcluded=True,members=members))
print(json.dumps(dict(files=len(members)+1,sourceCandidate=CAND,manifestSha256=sha((E/'manifest.json').read_bytes()))))
