# UI-LAYOUT-001 — first compile RED / STOP

**P8-S2B-UI-LAYOUT-001 HOLD / RAW RED PRESERVED / PROJECT REVIEW PENDING**

## Git and independent documentation

Start/local/tracking/live `642e70776b26fc3e8bd396b785e3757f3e6c96a0`; parent `0b0a2f6c88555cc1eecbf1ea6370cd88582418bc`. F2 Project acceptance was registered first in docs-only `b53b50007ab895183b6f6bf77f8fff60c08fe7fe`, direct parent the start, four Markdown files +56/-0. Scope is 140 plus minimal Source Map/CURRENT/Roadmap entries. Local links and docs diff-check PASS; **Tests rerun for that documentation: NO**. No F2 evidence or acceptance result was overwritten. F2 remains qualified accepted; this UI task remains OPEN.

## Draft scope and actual failure

Four uncommitted production UI drafts are preserved byte-for-byte in `draft-sources/` and `source-manifest.json`, with a reconstructable full `source.patch`. No UI source/test checkpoint was committed. Source patch SHA-256 `a9345a0ee5d65798df92b5c1b8a75254173cfa03dc70a09dba40633f4f51bda7`.

- `DesktopFishAudioPanel.kt`: replace picker DialogWindow call with Fish-specific window wrapper; original draft/person revalidation and picker/preview disposal remain in the draft.
- `DesktopAppIcons.kt`: only existing-dependency Play/Check additions.
- new `DesktopFishPickerPresentation.kt`: StudioAction variants; 288dp settings sidebar at width>=800dp; compact single column with bounded settings popup; two actions/card; explicit presentation-only preview target; folded advanced/free-text controls; revalidation before actions; conditional stop. These are unverified draft intentions, NOT actual UI acceptance.
- new `DesktopFishPickerWindow.kt`: native Frame maximize/restore intent and Fish-local parent input lease. This file fails compilation and has no runtime validation.

First and only Gradle invocation: `01-compile-main/command.txt`; JDK17/offline/no-daemon/single worker/low priority/Kotlin in-process/original2048MiB heap; **:desktopApp:compileKotlin FAILED, exit1**. Observer duration26.410025s; Gradle reports23s. Compiler diagnostic:

`DesktopFishPickerWindow.kt:50:17 Cannot access 'val LocalWindow: ProvidableCompositionLocal<Window?>': it is internal in file.`

The current Compose1.10.3 JVM bytecode exposes a public accessor to javap, but Kotlin metadata marks the property internal. Bytecode visibility was insufficient evidence of a supported Kotlin API. No invisible-reference suppression, reflection workaround, dependency change, retry or source fix followed the RED. Opting into ExperimentalComposeUiApi does not grant internal access.

The existing DialogWindow default was inspected as document-modal; a native Frame can maximize but has no automatic AWT owner relationship. Therefore preserving editor input isolation/focus/lifetime requires a supported parent-owner acquisition path in the allowed Fish presentation scope. This dependency is unresolved. A future narrow fix must use a supported public mechanism, retain captured draft/person identity and disposal, and verify focus/parent restoration. If it requires Main/global window infrastructure/controller/runtime changes, Project expansion approval is required. No proposed alternative was implemented after STOP.

## Execution boundaries and remaining gates

- Desktop compile only; sharedCore compilation was UP-TO-DATE. **JUnit executions0 / raw XML0**; empty junit directory is intentional. No focused/bounded test, no new exclusion/skip or weakened assertion. Existing tests were not edited yet.
- No new distribution, Launcher/JVM, fake service, credentials, cache or synthetic data root were created for a manual gate. Windows maximize/restore, responsive bounds, focus, keyboard, actual playback/stop/binding and process/root-lock checks all **NOT TESTED in this slice**. Historical F2 green evidence is not reused as new UI PASS.
- Real Fish GET0/TTS0. No real user data/key/chat read; no system proxy/cert/pagefile/driver/Computer Use changes. No screenshot capture retry.
- Two compile-stage resource samples: min available1.71193GiB, min commit headroom16.39935GiB, max3177pages/sec; sampled extrema, not absolute peaks. No OOM observed. No RAM hard gate reinstated or user process terminated.
- User-owned personal Markdown and three historical JPEGs remain protected/unread/unmodified/unstaged. The two new UI files are also untracked drafts; they must not be mistaken for protected JPEGs or silently deleted. Tracked UI worktree is dirty; source drafts remain uncommitted.
- Fish controller/runtime/HTTP/store/MCI, Main, schema/Prompt/baseline/parity and mature Studio behavior were not modified. No S3, chat audio, SaveSlot/QQ, G2 Writer/Recovery/dispatch, desktop merge or release.
- **UI-LAYOUT-001 OPEN / NEXT UX GATE**; no new user acceptance. **WIN-AUTO-001 OPEN**. The accepted F2 anonymous sample safety/drain scope remains intact.

Before this evidence commit, Fish/Compose skills were reviewed: no successful implementation to register, so no skill-map promotion. Docs commit stayed within its explicit four paths. Recommended model/High was a user specification; actual model setting/weekly quota delta were not independently observable and no consumption percentage is claimed.

Documentation commit diff-check PASS. Frozen draft source diff-check returns2 for an additional terminal blank line in DesktopFishAudioPanel.kt; this is recorded without changing the failed source snapshot. Immutable compiler stdout includes the original Gradle trailing-space banner; raw evidence is not trimmed to obtain a whitespace PASS. Raw stderr carries the actual compiler error. Manifest covers every other published file, excludes itself, and scoped `* -text` preserves raw bytes. STOP after the documentation/evidence push; no automatic repair or next slice.
