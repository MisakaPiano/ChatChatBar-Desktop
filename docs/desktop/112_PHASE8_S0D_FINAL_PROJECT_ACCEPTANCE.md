# 112 — Project Final Engineering Acceptance: P8-S0D Safe Shutdown Lifetime

**2026-10-12 JST — P8-S0D ENGINEERING MILESTONE CLOSED / QUALIFIED PROJECT ACCEPTANCE / NO PUBLIC PREVIEW RELEASE GATE OR P8 FULL PHASE ACCEPTANCE**

## 1. Project independent source and ancestry review

Project independently read GitHub `feature/phase8-fish-audio @ d0c17eff1136e7e15e45fdeaf8a4dbf46c20d1e4`:
- Final regression stabilization commit parent is exactly `5b7ab6316b00409580d26cb141fc188199a34bcb`, and GitHub compare shows **one file changed, +1/-0 lines**: `app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/DesktopUnifiedViewerTest.kt`.
- One new test-only line after the existing 4-`配方详情` wait: `scene.until { scene.nodes().count { it.label("图片") } >= 2 }`. Existing test helper `scene.until` uses `withTimeout(5_000)`, frames/yield; this is a **bounded readiness assertion**, not an arbitrary sleep or swallowed failure. `SemanticsProperties.ContentDescription` labels are list elements; an unrelated `历史图片网格` label does not equal `图片`. Actual image `Image(..., "图片")` semantics appear after asynchronous `DesktopOwnedImage` `produceState` / IO decode succeeds.
- All original behavioral asserts remain: filtered second-image viewer order/index, no changes to history selection, no Studio draft alteration, no accidental History detail entry. The earlier 4-recipe-button readiness remains.
- **No production source/code changed between S0D-R1 production `86c7aa082510eafc0b3171045e75f95bd9fb2cec` and this acceptance snapshot**; the intermediate merge `a04f7ff25f0016a62c956b1de3b238c4f7ce63d4` preserves that R1 production tree and adds only the approved `110`/source-map/CURRENT docs. No new branch/worktree, no rebase, no force push, no protected ref movement.
- Prior independent R1 source review `111_PHASE8_S0D_R1_PROJECT_REVIEW.md` validated `DesktopShutdownWaitObserver` 5-second daemon visibility deadline, non-cancellation of ongoing writer/backup/coordinator operations, lifetime of data-root ownership, EDT presentation and safe recovery/terminal exit handoff. Initial success-only outer-JVM-exit fix `31d2a48d2097e9e778185c7b8db1a022924745b8` reviewed under `109`/ `105`.

## 2. Actual Windows and automated evidence (Codex local execution, not Project execution)

| Evidence | Result | Interpretation |
| --- | --- | --- |
| Original R1 lifecycle-focused | 4 suites / 37 tests PASS | Codex-supplied local results |
| R1 real wait/unsafe-error AWT fixture | PASS | synthetic root, visible pending and recovery, no early lock release or forced worker cancellation |
| R1 exact-source packaged manifest | 210/210 file hashes match | separate local package gate, no rebuild after test-only change |
| R1 packaged Windows close/reopen | 6 launcher/JVM WM_CLOSE cycles; exit codes 0/0, no surviving owned processes | Codex local Windows evidence, includes synthetic persisted data reopen/root lock reacquisition |
| Original bounded non-Native Desktop full regression | 133 suites / 1185 tests / **1 failure** (History readiness line); 0 errors/skips | **HISTORICAL RED PRESERVED**, not overwritten |
| Original History failed test isolated rerun after investigation | 2 individual 1/1 PASS | does NOT replace above full RED |
| Final one-line test-only fix isolated runs | 2 individual 1/1 PASS | Codex local |
| Final affected History/Viewer set | 3 suites / 43 tests PASS | Codex local |
| Final *same-scope* bounded non-Native Desktop regression | **133 suites / 1185 tests, 0 failures/errors/skips** | **NEW GREEN AT `d0c17eff` test tree**; does not erase original RED |
| Compile and git diff --check | PASS | Codex local; production code unchanged from R1 |

The previous `DesktopUnifiedViewerTest.kt` and `DesktopStudioHistory.kt` blobs were confirmed unchanged from accepted P7; the failing original line indexed second `图片` before asynchronous image semantics reached the test-ready state. This explains the **likely test-readiness race**, but cannot prove every previous intermittent scheduling contributor. The new readiness guard checks the needed precondition directly. No test exclusion, behavior-assertion weakening, production UI workaround or excessive wait was introduced.

**Evidence limits**: Project reviewed GitHub code and compare; did **not** mount H: test XML/EXE or re-run the six Windows cycles. Codex local process/test assertions cannot be described as user-manual acceptance or independently executed Project tests. Native/Robot, historical synthetic ScrollBy RED and older P7 user checklist evidence remain at their existing declared status. Final package runs against identical R1 production code, **not** a regenerated binary for the test-only fix.

## 3. Final acceptance with enumerated exceptions

**DECISION — P8-S0D SAFE SHUTDOWN LIFETIME: QUALIFIED ENGINEERING ACCEPTED / CLOSED for the defined development slice.**

Accepted:
- Outermost safe successful Compose/container/picker/ownership shutdown leads to true JVM child+launcher exit, rather than merely closing the window; testable terminal exit is not called from early UI close callback.
- Safe-write drain failures retain ownership; explicit exception paths surface visible recovery; an otherwise unbounded but live writer/backup/coordinator wait produces user-visible warning after 5 seconds on a responsive EDT, *without interrupting the operation or declaring false success*. Window dismissed on successful completion.
- Exact-source Windows process-level 6-cycle evidence, synthetic restart/lock tests, and a fresh same-scope 133/1185 all-green regression (Codex local) satisfy this scoped engineering gate, with no silent acceptance of original RED.

Not proven/NOT accepted:
1. Root cause of the **two older P7 packaged JVMs and their launcher parents** remains UNKNOWN; these historical processes were not automatically killed, and this fix cannot retroactively shut them down or certify their persisted root safe to clean.
2. An **EDT itself deadlocked/unresponsive** may prevent the AWT warning and synchronous cleanup: this is an architectural residual risk, not a claim that every OS hang produces UI. A real reproducible EDT-hang/data-loss issue must reopen the gate and is a Public Preview release blocker; do not force exit/kill writers to disguise it.
3. No Android real-device, user manual Windows UI acceptance of the *new* failure-window path, long-running paid generation, live backup under real user data, or broader public upgrade data-safety matrix is signed by this decision. Public Preview Data-Safety Gate `93`/`20`, D-038 whole-app `.cbbackup` `95`, and session SaveSlot interoperability still require separate acceptance.
4. P8 Fish S1 pure-core source/BLOB review is positive in `109`, but this shutdown closure does **not** automatically accept full P8 audio, P9 RAG or P10 Memory; runtime and feature parity matrix rows remain PENDING. No `desktop` merge or upstream sync is authorized by this decision.

## 4. Next stage / control

- Project may resume the narrow already-reviewed P8 Fish work on **the same existing feature branch**, beginning with explicit S1 core engineering acceptance/closeout and then staged S2 credential + voice-binding UI ownership, followed by stringent S3 voice file fail-closed persistence and MP3 playback feasibility before tagging/coordinator/chat runtime.
- Keep P11 early `.cbbackup` M0/M1 synthetic/read-only inspector as separate conditional planning; official Android 1.4.5 whole-backup export currently has a reported top-level `profileinstaller_profileWrittenFor_lastUpdateTime.dat` registry admission defect, and user awaits upstream author's fix. Do not manually delete Android private app data to bypass the error.
- Before issuing Codex instructions, fetch feature HEAD and do **not** write to that same ref while Codex has an in-flight unpushed commit (avoids past docs/code concurrency); preserve clean linear same-feature history and existing red/gate evidence.

**CURRENT: P8-S0D QUALIFIED ENGINEERING CLOSED; P8 FISH FEATURE/P9/P10/P11 STILL PENDING; PUBLIC PREVIEW GATE NOT REACHED; NO MERGE.**
