from pathlib import Path
import json,hashlib,subprocess,shutil,ast,re
R=Path.cwd();G=Path(__file__).resolve().parent
E=R/'docs/desktop/evidence/p8-s2b-ui-layout-001-r7-20261019'
BASE='bad9f3c235dbb1a88af5e3fb6cbdf4aa7418cbd5'
def git(*a):return subprocess.check_output(['git',*a],stderr=subprocess.PIPE).decode().strip()
def load(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(b):return hashlib.sha256(b).hexdigest()
def save(n,v):(E/n).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
assert git('rev-parse','HEAD')==BASE and not git('diff','--name-only') and not git('diff','--cached','--name-only')
source=load(G/'source-preflight.json');manifest=load(G/'distribution-manifest.json')
for h in source['history']:assert git('rev-parse','HEAD:'+h['path'])==h['tree']
for s in source['source']:
 b=(R/s['path']).read_bytes();assert len(b)==s['bytes'] and sha(b)==s['sha256'] and git('rev-parse','HEAD:'+s['path'])==s['blob']
assert set(git('ls-files','--others','--exclude-standard').splitlines())==set(source['userOwnedUntrackedPathsOnly'])
image=G/'runtime-image'
assert {p.relative_to(image).as_posix() for p in image.rglob('*') if p.is_file()}=={p['path'] for p in manifest['files']}
for f in manifest['files']:
 b=(image/f['path']).read_bytes();assert len(b)==f['bytes'] and sha(b)==f['sha256']
assert not (G/'manual-start-once.lock').exists() and not (G/'runs').exists() and not (G/'mock-service').exists()
assert not load(G/'preparation-final-resource.json')['java'] and not load(G/'preparation-final-resource.json')['ownedApp']
cleanup=load(G/'preparation-mock-final.json');assert cleanup['exitCode']==0 and cleanup['cleanup']['serverDrained']
assert not cleanup['privateKeyRemains'] and not cleanup['trustStoreRemains']
assert not any((G/'preparation-mock-service').glob('*.pem'))
assert load(G/'helper-compile.json')['sourceSha256']==sha((G/'R7UiSupport.java').read_bytes())
assert load(G/'helper-compile.json')['exitCode']==0
for n in ['manual-supervisor.py','prepare-isolation.py','freeze-package.py']:ast.parse((G/n).read_text(encoding='utf-8'))
supervisor=(G/'manual-supervisor.py').read_text(encoding='utf-8')
assert supervisor.index('--start-r7-manual-after-user-ready')<supervisor.index('subprocess.Popen')
assert 'os.O_CREAT|os.O_EXCL' in supervisor and 'f2-profile' not in supervisor
assert 'java("seed",ROOT)' not in supervisor
assert 'r7-*.json' in supervisor
E.mkdir(exist_ok=False);(E/'.gitattributes').write_bytes(b'* -text\n')
names=['source-preflight.json','prebuild-resource.json','preparation-final-resource.json','run-stage.ps1','freeze-package.py',
 'distribution-manifest.json','task-execution-ledger.json','R7UiSupport.java','helper-compile.json','helper-compile.stdout.log','helper-compile.stderr.log',
 'fixture-audio-identities.json','prepare-isolation.py','synthetic-seed.json','synthetic-seed.stdout.log','synthetic-seed.stderr.log',
 'synthetic-data-identity.json','prepared-root-lock.json','prepared-root-lock.stdout.log','prepared-root-lock.stderr.log',
 'preparation-mock-process.json','preparation-mock.stdout.log','preparation-mock.stderr.log','preparation-mock-final.json',
 'preparation-public-cert-export.json','preparation-public-cert-export.stdout.log','preparation-public-cert-export.stderr.log',
 'fake-readiness-probes.json','runtime-watch.ps1','manual-supervisor.py','observe-owned-windows.ps1','prepared-script-parse.json']
for n in names:shutil.copyfile(G/n,E/n)
shutil.copyfile(Path(__file__),E/'collect-preparation.py')
shutil.copytree(G/'01-create-distributable',E/'01-create-distributable')
(E/'preparation-mock-service').mkdir()
for n in ['events.jsonl','ready.json','cleanup.json']:shutil.copyfile(G/'preparation-mock-service'/n,E/'preparation-mock-service'/n)
resources=[json.loads(s) for s in (G/'01-create-distributable/resources.jsonl').read_text(encoding='utf-8-sig').splitlines() if s]
stage=load(G/'01-create-distributable/stage.json')
events=[json.loads(s) for s in (G/'preparation-mock-service/events.jsonl').read_text(encoding='utf-8').splitlines()]
assert len(events)==4 and all(s['realRequests']==0 and s['status']==200 and not s['sampleSecretHeader'] for s in events)
save('preparation-status.json',dict(status='R7 WINDOWS PACKAGE READY / MANUAL USER ACTION REQUIRED',buildHead=BASE,sourceCommit=source['sourceCommit'],
 applicationPath=str(image/'ChatChatBarDesktop.exe'),guiLaunchCount=0,manualRunnerLaunches=0,robotRuns=0,junitRuns=0,boundedRuns=0,
 build=dict(exit=stage['exitCode'],durationSeconds=stage['durationSeconds'],rerunTasks=True,sourceKotlinCompile='EXECUTED',desktopKotlinCompile='EXECUTED',
  taskLedger='task-execution-ledger.json',warnings='Raw stdout/stderr preserved; no compiler error',distributionFiles=manifest['fileCount'],fishClassesVerified=len(manifest['classesVerified']),
  packageMemberSetAndHashesMatch=True),
 resources=dict(minAvailableBytes=min(s['availableBytes'] for s in resources),minCommitHeadroomBytes=min(s['commitLimitBytes']-s['commitBytes'] for s in resources),
  maxPagesPerSecond=max(s['pagesPerSecond'] for s in resources),javaAfterPreparation=load(G/'preparation-final-resource.json')['java']),
 preparedRootLock=dict(result='Independent acquire/release exit0',scope='Fresh synthetic root before application launch only; not runtime exclusivity or post-app reacquire'),
 api=dict(preparationFakeCatalogGet=2,preparationFakeSampleGet=1,preparationFakeTtsPost=1,unknownConnectRefused=True,realFishGet=0,realFishTts=0),
 fakeState='PREPARATION SERVICE EXIT0 / DRAINED; manual service NOT STARTED',temporaryTls='Preparation private key/trust store/public certificate removed',
 manualGate='NOT STARTED; explicit user readiness required',packagedLauncherJvmExit='NOT TESTED: no GUI launched',runtimeRootExclusivity='NOT TESTED',
 postAppRootReacquire='NOT TESTED',nativeAudioOutput='NOT TESTED',postAudioCacheDrain='NOT TESTED',
 packageFrozen='New isolated ignored runtime-image; no previous frozen package overwritten'))
save('manual-results.json',dict(status='NOT STARTED',userSignatures=[],groups=[dict(id=i,result='NOT TESTED',note=n) for i,n in [
 ('M1','Actual compact Popup, width/focus and two-layer Escape'),('M2','Stop icon/text in sample/generated waiting/playback, including top compact Popup; user hearing'),
 ('M3','Actual app scrollbar, wheel, explicit page Enter/click, invalid page and narrow wrap'),('M4','Resize state/cache separation, owner/editor recovery, application shutdown')]],
 technical={'activeRootLock':'NOT TESTED','launcherJvmExit':'NOT TESTED','postExitRootReacquire':'NOT TESTED','audioCacheDrain':'NOT TESTED'}))
save('scope-history-and-protection.json',dict(history=source['history'],unchanged=True,baseline='1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8',
 historyClassification={'R6D':'Native4/4 PASS on earlier test identity','R6E':'Fish15/15, affected82/82, bounded Native4/4 PASS; overall bounded cmd.exe RED',
 'R6F':'Overall bounded Robot Press0 RED, cause UNKNOWN','R6G':'Exact candidate test itself Press0 RED, cause UNKNOWN','R6I':'INCONCLUSIVE; user reported scroll; arming procedure defect',
 'R6J':'INCONCLUSIVE; real owned physical event chain but measured thumb gate truncated capture; late user reported scroll PASS'},
 symlink='Two historical SKIPPED and old NOT TESTED unchanged',winAuto001='OPEN',winBrand001='OPEN / DEFERRED',uiLayout001='HOLD / not closed',
 protectedUserFiles=source['userOwnedUntrackedPathsOnly'],protection='KEEP / UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED',
 productSourceAndGradleBusinessConfigChanged=False,skillReview=dict(skill='.agents/skills/chatbar-fish-audio-voice/SKILL.md',updateRequired=False,reason='Exact-source packaging and isolated evidence only'),
 currentParityBaselineAndOldDesktopUnchanged=True,noMergeNoRelease=True))
save('network-and-audio-boundary.json',dict(isolatedLocalAppData=str(G/'environments/r7-profile'),selectedRoot=str(G/'environments/r7-profile/ChatChatBarDesktop'),
 syntheticUserHome=str(G/'synthetic-home'),proxy='Only launched JVM environment uses HTTP/HTTPS proxy127.0.0.1; fixed two-origin CONNECT fake never forwards upstream',
 tls='Trust only this run public certificate in launched client; no global certificate/proxy/security changes',
 syntheticCredential='Fresh isolated DPAPI string, not a valid account; no real credential read',
 fakeCatalog=dict(total=60,pageSize=20,pages=3,specialRows=['r7-001 sampleA','r7-002 sampleB','r7-003 wait-sample','r7-004 wait-generation']),
 syntheticGeneration='Canned local long MP3; no provider TTS even if user clicks free generation',
 audio='Existing isolated R2 audio fixtures reused by hash, generated fixture complete frame sequence repeated5, duration31.608s structurally; actual Windows sound output NOT TESTED',
 cacheTypes='Existing production OFFICIAL_SAMPLE vs GENERATED identity unchanged; actual packaged switching awaits M4',
 materialExcludedFromGit=['runtime-image','MP3 audio binaries','DPAPI credential files','synthetic entities/profile','TLS private/public material','support-class binaries'],
 launcher='Prepared single-use explicit command, not executed; no capture-driven automatic GUI close'))
(E/'MANUAL_PLAN_CN.md').write_text('''# R7 集中人工验收计划（尚未执行）

等待用户明确准备操作后，只启动一个新实包实例。当前不要运行应用或启动脚本。

准备入口：管理 → 角色 → 铅笔编辑“R7 结构化测试卡” → Alice → 选择 / 更换音色 → 查询 / 重试。这是实际 CCB Desktop 新包。

1. **M1 Escape**：将窗口收窄至实际 client 宽度低于800dp，展开高级筛选，确认浮层覆盖列表。记录实际宽度和焦点。第一次 Escape 只收起浮层，第二次才关闭音色库。
2. **M2 Stop**：重开音色库，试听第一张“样本对照A”，确认看见停止图标及“停止预览”，点击能停声。展开免费文本生成，点卡片仅设为预览目标，再输入短文本并明确生成；它只返回本机固定中文音频。检查紧凑浮层内 Stop。需要等待状态时使用第3张“等待样本”／第4张“等待生成”；分别点击停止。未实际听到的场景不签署听觉PASS。
3. **M3 列表和分页**：拖动右侧常驻滚动条、使用滚轮。实际20条/页、共3页；输入3按Enter、输入2点跳转，0/4等无效页不查询。检查宽窗尽量同排、窄窗换行且输入/跳转紧邻。只需一轮，不要求反复拖动或单独输入录制器。
4. **M4 状态与关闭**：宽窄切换检查筛选、预览文本及当前目标保持；同一音色样本A与生成的中文音频应区分，首次生成缓存复用提示正确。关闭音色库后编辑器恢复。最后由用户正常关闭整应用，再独立核验 Launcher/JVM、运行中排他/退出后根锁重获、fake排水及临时音频/TLS收尾。

每组与技术子项分别记录PASS / FAIL / NOT TESTED，不覆盖历史结果。遇到真实产品FAIL或安全/退出问题即停。不得自动重试、修源码或重复bounded。

测试窗口不会因采集完成或短确认超时自动关闭；由用户明确完成后正常关闭。观察器不操作鼠标/前台。启动脚本要求显式用户准备参数和一次性锁。当前所有人工项均为NOT TESTED。
''',encoding='utf-8',newline='\n')
(E/'README.md').write_text(f'''# R7 — Exact-source Windows package / preparation only

**R7 WINDOWS PACKAGE READY / MANUAL USER ACTION REQUIRED**. No GUI launched. No Robot, JUnit, bounded or R6 manual runner. UI-LAYOUT-001 remains HOLD.

Build HEAD `{BASE}`, source candidate `{source['sourceCommit']}`. Formal baseline1.4.1 remains unchanged. Local old desktop remains untouched. [Exact source/blob preflight](source-preflight.json).

## Actual build and identity

One JDK17 offline `:desktopApp:createDistributable --rerun-tasks`, no daemon/parallel, one worker, low priority, Kotlin in-process,2048MiB. Exit0; elapsed{stage['durationSeconds']}s. sharedCore and desktop compileKotlin both **EXECUTED**, not cached. Warnings preserved in raw logs; [every task status](task-execution-ledger.json). No compileTestKotlin or test task executed.

New frozen package: `{image}`. Actual{manifest['fileCount']} files, all relative paths/lengths/SHA256 in [distribution manifest](distribution-manifest.json). No missing, extra or mismatched member. The launcher hash may match earlier generic launcher bytes; it is not used alone to identify the new package.

- Launcher `{manifest['launcher']['path']}` SHA256 `{manifest['launcher']['sha256']}`.
- Business JAR `{manifest['businessJar']['path']}` SHA256 `{manifest['businessJar']['sha256']}`.
- {len(manifest['classesVerified'])} classes and nested classes with SourceFile=DesktopFishPickerPresentation.kt / DesktopFishPickerWindow.kt match actual freshly compiled class bytes. Source blobs/raw hashes remain fixed. No NativeUx test/manual runner, helper/private TLS or user data included.

## Isolated preparation

[Network/data/audio boundary](network-and-audio-boundary.json). Fresh independent LOCALAPPDATA, explicit DEFAULT bootstrap selected root, two synthetic cards/sessions, explicit free model, new synthetic DPAPI string. No real profile, chats or credentials read. Helper compiled once against this new package with UTF-8, exit0. Runtime package remained unchanged.

Loopback-only preparation fake exercised page1 and3 (20 items each/total60), anonymous sample and canned generation response. Fake counts **catalogGET2/sampleGET1/POST1**, real Fish **GET0/TTS0**. Unapproved CONNECT refused locally. Readiness probes concern the support service, not product behavior or audible output. No unknown request was forwarded. Raw events and requests are separate from manual signatures.

Preparation service exited0, drained, private PKCS12/public truststore/public probe certificate removed. No owned app or Java service remains. Prepared future manual script requires an explicit user-ready argument and exclusive one-use launch lock. It has not been executed. Existing runtime watch has no forced manual lifetime expiry; application closure is user-controlled.

Fresh-root independent lock acquire/release succeeded before launch; **active exclusion, post-app lock reacquire, Launcher/JVM exit, playback/audio handles and audio-cache cleanup remain NOT TESTED** because the application has not started.

## Remaining gate

[M1–M4 compact user plan](MANUAL_PLAN_CN.md), [all results initially NOT TESTED](manual-results.json), [preparation status/resources](preparation-status.json). Later read-only owned-window geometry can record actual client width/DPI/focus/maximize state without input injection. Manual experience remains separate.

Historical R6D/R6E NativePASS, R6E overall cmd.exeRED, R6F/R6G Press0RED/UNKNOWN, R6I/R6J INCONCLUSIVE with user-reported scroll, two symlink SKIPPED/old NOT TESTED remain intact. WIN-AUTO-001 OPEN; WIN-BRAND-001 OPEN/DEFERRED. No full bounded GREEN claim, no UI closure, merge or release.

Only this evidence directory may be committed. No package, MP3, real/synthetic secret files, TLS material or user profile is committed. Four user-owned paths remain unread/unmodified/untracked/unstaged. No Kotlin/Gradle product change, CURRENT/parity/baseline update or later Phase work.

**STOP. Await explicit user readiness before starting exactly one new packaged GUI instance.**
''',encoding='utf-8',newline='\n')
members=[]
for p in sorted(E.rglob('*')):
 if p.is_file() and p!=E/'manifest.json':
  b=p.read_bytes();members.append(dict(path=p.relative_to(E).as_posix(),bytes=len(b),sha256=sha(b)))
save('manifest.json',dict(algorithm='SHA-256',selfExcluded=True,stage='R7-A build and R7-B preparation only; manual NOT STARTED',members=members))
print(json.dumps(dict(files=len(members)+1,manifestSha256=sha((E/'manifest.json').read_bytes()),status='R7 WINDOWS PACKAGE READY / MANUAL USER ACTION REQUIRED')))
