# P8-S2B-UI-LAYOUT-001-R1 — public owner compile PASS / focused RED

**HOLD / RAW RED PRESERVED / PROJECT REVIEW PENDING**

## Fixed identities and scope

Start/local/tracking/live `7363c8e692bce23e2cddfa9564de34829c19f890`, direct parent `b53b50007ab895183b6f6bf77f8fff60c08fe7fe`. F2 documentation review PASS is the Project decision; neither its four documents nor the prior F2/first UI RED evidence was changed. Before editing, all four raw draft SHA-256 identities matched the frozen manifest and complete patch `a9345a0ee5d65798df92b5c1b8a75254173cfa03dc70a09dba40633f4f51bda7`.

Eight current production/test drafts remain UNCOMMITTED and are preserved in `draft-sources/`, `pre-focused-source.json`, complete `pre-focused-source.patch`, and `r1-vs-frozen.patch`. Original two new UI files remain untracked drafts; two new Fish test files are also untracked. Main/AppIcons/FishPanel/FishPresentationTest are tracked modifications. Four unrelated protected user files remain unread/unmodified/unstaged; no reset/rebase/force/stash/clean.

Owner repair uses public `ComposeWindow` and a Fish-only static CompositionLocal with null default. Main has exactly one added provider line in its existing public Window receiver. No Main lifecycle/close/task/ownership path, shell/controller/container, HTTP/store/runtime/MCI, binding Entity, Prompt/schema, baseline/parity or mature Studio implementation changed. Fish presentation layout was not redesigned. The only additional edit to its frozen panel draft was removing the previously recorded single extra terminal blank line before focused compilation.

`DesktopFishWindowOwnerLease` is EDT-only and identity-keyed; overlapping leases share the original enabled state and final close restores once, regardless of close order. Missing owner renders fixed failure text and no child Window. Closed callbacks are removed/guarded; only the supplied parent is modified. No internal LocalWindow, invisible-reference suppression, reflection, global Window scan or foreground HWND lookup was used.

## Actual executions (not cumulative)

| Stage | Result | Raw source |
|---|---|---|
| 01 public-owner compile | compileKotlin actually executed, exit0;36.9271487s observer /35s Gradle; no JUnit | `01-owner-compile/` |
| 02 Fish UI/window | main and test compilation succeeded;3 suites/14 tests:9 reported PASS,5 FAIL,0 errors/SKIPPED;exit1;83.448803s observer /1m13s Gradle | `02-fish-ui-window/` |

JDK17/offline/no-daemon/no-parallel/single worker/low priority/Kotlin in-process/original2048MiB heap; no clean/rerun-tasks. SharedCore remained UP-TO-DATE, Android not run. Source snapshots distinguish the compile-only five-file state from the later eight-file test state. Exactly three fresh original XML were copied before any later test; no later test was started. Both full stdout/stderr/commands/exit/resource streams preserved. No test rerun, exclusion expansion, extra skip or assertion weakening after the RED.

## Five failures and confirmed execution limits

| Case | Exact failure | Bounded interpretation |
|---|---|---|
| Layout: fold/resize/stale callbacks | LayoutTest.kt135 expected Disabled on named generate node, absent | Stops before text/resize/stale-callback steps; those later branches are NOT TESTED by this method |
| Layout: wide/compact/keyboard | LayoutTest.kt89 expected Role.Button, observed null on named use node | Earlier288dp sidebar/compact viewport/initial button-bounds checks executed; keyboard and wheel steps were not reached |
| Native public owner/max/restore | WindowTest.kt113 waited15s for both content dimensions to exceed initial; timeout | Initial actual public-WindowScope owner identity, parent disabled and one content instance assertions were reached. Maximize-size observation was NOT confirmed. Restore/reopen/parent-close steps were not reached; no Windows-maximize PASS |
| Existing Fish free-preview UI | PresentationTest.kt86 expected Disabled on named empty-text generate node, absent | Fails before setting text or generating |
| Existing Fish official-sample UI | PresentationTest.kt47 expected Disabled on named unsupported/no-sample actions, absent | Fails before sample play/stop |

Four failures concern the observable role/disabled semantics of the selected action nodes. The named FishAction wrapper and its StudioAction child are a source-level investigation lead; an actual semantic-tree diagnosis was NOT performed after STOP. Do not declare them harmless test-driver errors or lower the assertions without proving the accessible/action behavior. The native timeout may involve state application, geometry or the test observer; root cause remains UNKNOWN. No screenshot/FrameArrived failure occurred, and this is not automatically WIN-AUTO-001.

Passing new methods: absent owner fails closed; identity-scoped overlapping leases in either order, idempotent close, original disabled state and listener removal; error/empty/loading/late catalog completion after close. Six other existing Fish presentation methods also passed. Whole failed methods are not reclassified PASS because earlier assertions ran.

## Unexecuted gates and process/resources

Affected F1/F2 Sample/Preview/Settings regression, bounded Desktop, new distribution, loopback manual service, user hearing/binding/layout/maximize/restore/focus/Escape and packaged Launcher/JVM/root-lock/cache cleanup are **NOT TESTED this slice**. Historical F1/F2 green,2 JUnit SKIPPED and old symlink NOT TESTED remain historical facts, not fresh R1 results.

The native JUnit fixture did create its own Main-like public WindowScope host and picker; fixture finally blocks close their compositions/windows. This is distinct from a packaged app launch. After the completed Gradle stage, a read-only Java/javaw process inventory observed count0. No process was killed. No packaged Launcher/JVM0/0, root-lock recovery or fake drain claim is made.

Real Fish GET0/TTS0 by isolated inline catalog providers and loopback MockWebServer transports. No actual user root/key/chat read; no real Fish credential or sample download. No system configuration or other application was modified. There was no native user/manual acceptance or new screenshot collection.

11 resource samples across the two stages: compile min available1.12061GiB, commit headroom15.48905GiB,max218pages/sec; focused min available0.69426GiB,headroom15.05679GiB,max2960pages/sec. Sampled extrema only; no OOM observed. Low memory alone did not trigger cancellation; the new focused RED triggered STOP.

**UI-LAYOUT-001 remains OPEN**. **WIN-AUTO-001 remains OPEN**. No S3/chat audio/SaveSlot/QQ/G2 Writer/Recovery/dispatch, upstream sync, desktop merge or release. Formal baseline remains1.4.1.

## Independent deferred governance request

**WIN-BRAND-001 — CCB 系统诊断／错误窗口 Logo 统一: OPEN / IMPLEMENTATION PENDING.** Full user-directed handoff in `deferred-WIN-BRAND-001.json`. At the next safe docs-only governance checkpoint, register it in CURRENT, Roadmap and the appropriate pre-release UX checklist. It is not part of this active UI source scope, not a current production change, not WIN-AUTO-001, and P7-WIN-02 PASS is not evidence for it. Reuse DesktopBrandResources; separately evaluate native MessageBox and preserve diagnostic/shutdown/ownership/retry/process semantics; require packaged Windows user verification.

## Publication

Skill relevance reviewed before staging: failed drafts do not promote accepted feature behavior; no Skill edit in this evidence-only publication. No CURRENT/Roadmap/acceptance document edits were made in R1. Actual model/quota delta not independently measured; no consumption percentage claimed.

Source diff-check PASS after the explicit EOF-only cleanup. Immutable Gradle logs/unified patches may retain original whitespace warnings; do not trim raw RED evidence to manufacture a full evidence whitespace PASS. Source/metadata checks are separate. Scoped `* -text` preserves raw bytes. Manifest covers all other files and excludes itself. Evidence-only commit follows the start commit; all eight source/test drafts stay local. STOP after normal feature push, await Project narrow follow-up.
