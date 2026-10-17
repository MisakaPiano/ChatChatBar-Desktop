# P8-S2A-R5 exact-source validation — bounded RED preserved

**P8-S2A-R5 ENGINEERING CANDIDATE / PROJECT REVIEW PENDING** — 2026-10-18 JST.

**Overall gate: HOLD / BOUNDED REGRESSION FAIL.** This is not S2A acceptance, not a Windows UI PASS, and not a completed packaging/live gate. No production or test source was changed. Do not promote Feature Parity or begin S2B/S3 or G2 Writer/Recovery.

## Git identity and protected files

Started locally at `df25b29e5cf60fd9253fa444b38c32623fb44b97`; fetched only the authorized feature ref. Tracking/live origin was `d64f82f6fae4579353d77c114f92741cbf731b66`, exactly one docs-only child (00/02/137). Verified ancestor relationship, empty index, clean tracked worktree and no collisions with four protected untracked paths before the user-authorized **FF-only** alignment to that exact SHA. Its parent remains `df25b29e5cf60fd9253fa444b38c32623fb44b97`.

The root personal Markdown is KEEP / UNTRACKED / USER-OWNED / NON-BLOCKING; the three external JPEGs are untouched/unread/untracked. No further personal-document content investigation, synchronization, staging or copying occurred. This evidence-only commit changes no source/test, CURRENT, baseline, Prompt, Entity/schema or parity. The **tested source SHA is d64f82f6**, not a newly implemented S2A version; no empty source checkpoint is created.

Formal validated baseline stays **ChatBar 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**. Read authority 114/112/113 and official baseline Fish Skill/source; current R1/R4/R4-C1 behavior is reused rather than reimplemented. `source-manifest.json` records 20 targeted current source/test identities and normalized Git/worktree byte equivalence. `authority-ledger.json` is a narrow reference ledger, not a new global audit.

## Actual commands and results

Each stage contains `command.txt`, original UTC `stage.json`, untouched stdout/stderr, periodic resource JSONL and **copied original JUnit XML before the next test run**. All Gradle work is serial, Temurin **17.0.20.1**, Gradle **9.1.0**, offline/no-daemon/no-parallel/max-workers=1/priority=low/console=plain/no-configuration-cache/in-process Kotlin, unchanged **-Xmx2048m**. Task-local `--rerun` forces the named test tasks (and Desktop compile tasks in04), never `--rerun-tasks` or clean. Caches remain enabled. No simultaneous Gradle builds.

| Stage | Exit | Wall seconds | Suites | Tests | Reported PASS | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 01 frozen Fish/settings/character/session/DPAPI focused | 0 | 146.3502702 | 12 | 160 | 160 | 0 | 0 | 0 |
| 02 SharedCore JVM | 0 | 62.5061084 | 132 | 889 | 889 | 0 | 0 | 0 |
| 03 Android JVM | 0 | 41.9445107 | 123 | 715 | 715 | 0 | 0 | 0 |
| 04 Desktop bounded non-Native | **1** | 446.97359 | 153 | 1492 | 1489 | **1** | 0 | **2** |

These are distinct overlapping scopes; do not add them as a unique test total. The 420 XML files preserve all four stages separately. Stage01 main/test compile tasks were UP-TO-DATE; stage04 **Desktop compileKotlin and compileTestKotlin actually executed successfully**, then the test task failed. SharedCore/Android compile prerequisites were UP-TO-DATE, not falsely described as recompiled; their test tasks actually executed.

The bounded exclusions are **exactly the previously accepted four** in `bounded.init.gradle`: WindowsBackgroundViewerTest, WindowsStudioEdgeDragTest, WindowsWindowChromeTest, and DesktopImageWindowPresentationTest's short-tool-workspace method. No new exclusion, skip, fail-fast workaround or weakened assertion. Existing G0/G2 symlink JUnit skips remain two SKIPPED in04; four older symlink fixture early-return limitations remain NOT TESTED and are not counted as extra skips or proven coverage. There was no memory-triggered cancellation or automatic retry.

## New RED and scope boundary

`DesktopChatDensityTest.toolbar partitions existing eligibility without adding or losing actions` fails at **line62**, `assertTrue(overflow.all { EDIT || DELETE })`. That loop enumerates all `DesktopMessageAction.entries`. Current F4 adds `REPAIR_FORMAT`, `RESTORE_FORMAT`, `STOP_FORMAT`, and production intentionally places non-footer actions in overflow. The minimal eligible subset `[REPAIR_FORMAT]` produces footer `[]`, overflow `[REPAIR_FORMAT]`, contradicting the old edit/delete-only assertion. Blame traces that assertion to `eea723ecc`; latest action-policy change is F4 R1 `e9901a0ce7a3f764d74d3d83f0518a18e9bb8640`.

This is an identified **non-Fish test-contract inconsistency**, not evidence of a new Fish UI defect or proof every F4 runtime behavior is correct. `bounded-failures.json`, `bounded-failure-stack.txt` and the original04 XML preserve the actual failure. No assertion/policy/source was changed and no isolated rerun was used to relabel it green. Project must authorize any narrow chat-test correction and rerun separately; it is outside this task's S2A-only repair boundary.

The already running bounded task was allowed to finish and safely release its owned processes; no further heavy work was started after RED. Packaging/native/live prerequisites remain unsatisfied. All historical P8 R1/R4/C1 RED and old package/log evidence stay untouched in their existing local directories; none of their PASS results is borrowed for R5.

## Fish coverage versus real Windows coverage

| Contract | Actual evidence | Limit |
| --- | --- | --- |
| Fish DPAPI save/recreate/clear; failure preservation | DesktopFishAudioLibraryTest real isolated DPAPI profile, WindowsDpapiSecretProtectorTest/WindowsSecretStoreTest; credential-state failure tests | Synthetic profiles only; no real Fish credential was loaded |
| Global Fish TTS/tag/audiobook settings; auxiliary-only/stale model; merge/rebase | DesktopFishSettingsBindingTest, ModelSettingsControllerTest, SettingsCoherenceTest, five Fish presentation methods | UI presentation is offscreen Compose, not a native window signature |
| Session inherit/on/off, raw language/normalization, dirty/discard/save/switch; in-flight edits and cancellation | 17 FishSettingsBinding methods plus PrimaryChat controller/draft tests | Actual Windows keyboard/IME and native save/reopen NOT TESTED |
| STRUCTURED/FREEFORM explicit binding, stable person/draft identity, late results, cancel/remove/discard | Fish controller/presentation tests, CharacterEditorControllerTest | Real picker dialog operation NOT TESTED |
| Full binding snapshot through JSON/CCB PNG, duplicate/import/save/reopen | Existing FishSettingsBinding transfer tests and CharacterTransferIntegration | No schema change or real user package used |
| GET /model authority/auth/self/filter/sort/page/error/oversize/cancel/body drain/app catalog close | Ten FishAudioLibrary methods using localhost MockWebServer | No actual remote query or native app network gate |

No S2A production defect was reproduced in these green focused tests, so no implementation was changed. A passing focused subset does not cancel the bounded RED or prove native UI correctness.

## Windows, package and live gates: not executed

**No R5 distribution generated, no Launcher started, no native app window observed/operated.** Launcher exit code, JVM exit code, process disappearance, data-root lock held/released/reacquired, image/launcher/JAR SHA-256 are each **NOT TESTED / NOT GENERATED** for R5. Do not reuse the old f020f294 package as a current source package. No installer or release was produced.

Computer Use was initialized only; there was no app enumeration, screenshot, activation or input. **No FrameArrived timeout occurred in R5.** Native testing is blocked by the preceding regression, not diagnosed as a capture environment failure. `WINDOWS_MANUAL_ACCEPTANCE_CN.md` is a single consolidated25-item plan, all NOT TESTED, with actual Chinese entry names, expected results and screenshot/process/lock fields; it is not a manual PASS or instruction to use an old binary now.

Latest optional live authorization is recorded in `live-gate.json`: **GET /model≤3, TTS≤1, single concurrency, ≥30 seconds between requests, TTS≤120 characters and only s2.1-pro-free**. Any restriction/auth/rate/billing/uncertain-free-eligibility signal stops without retry. Actual R5 **GET0 / TTS0**, no actual Fish credential or historical conversation/binding was read; account eligibility/billing was NOT VERIFIED/NOT INSPECTED. No audio artifact was generated or placed in any user session. This NOT TESTED result does not negate the passing mocked tests.

Official pricing was read on2026-10-18: [Pricing & Rate Limits](https://docs.fish.audio/developer-guide/models-pricing/pricing-and-rate-limits) lists s2.1-pro-free at $0/M UTF-8 bytes. That generic table does not verify this account's eligibility, so it is not a live-gate PASS or a reason to call another paid model. The catalogue reference-page fetch was unavailable; no API request was made as a substitute. Nothing from newer docs changes the formal1.4.1 implementation baseline.

An uncompiled/unexecuted validation-support Java draft was prepared under the local gitignored gate before RED. It was never invoked, made no key/certificate/network/file-fixture changes, and is excluded from the published evidence as non-execution material. No new production endpoint/config/transport seam was introduced.

## Resources, preservation and handoff

68 periodic samples: minimum Available **0.326 GiB**, minimum Commit headroom **11.264 GiB**, max **11,262 pages/sec**, Java count at most4. Max sampled aggregate Java Working Set2,949,902,336 bytes and Private Bytes3,518,824,448. These are **sampling extrema, not absolute peaks or attribution**; Working Set can count shared pages repeatedly. No OOM or write-disk error observed. Java process count after completion **0**; final H free15.46GiB. Chrome/NAI responsiveness NOT TESTED, and those apps were neither operated nor terminated. No low-RAM hard gate or system/pagefile change was applied.

Evidence originals are copied byte-identically and checked via `raw-copy-provenance.json`. Local attributes preserve CRLF and treat raw console logs and the standalone failure-stack mirror as binary artifacts so original daemon-line trailing spaces are not trimmed. No real credentials, Authorization header, sensitive response body, user chat/card package or JPEG content is included. Manifest covers all other published records, not itself. Ordinary evidence Markdown/JSON/XML and source whitespace checks remain active.

Only an **evidence-only commit** is created because there is no source/test change to submit. Tracked worktree/index should be clean after publication; whole worktree remains unclean due to the user-owned Markdown and three JPEGs. Stop and return to Project: bounded RED needs its own scoped disposition; native/package/live gates remain unrun. No S2A CLOSED declaration, S2B/S3/S4/S5/S6, G2-B/C, Writer/Recovery, upstream sync, protected-branch movement, merge or release.

**P8-S2A-R5 ENGINEERING CANDIDATE / PROJECT REVIEW PENDING — AUTOMATION HOLD / RAW RED PRESERVED.**
