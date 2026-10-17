# P8-S2A-T2-UI-001-R1 — focused RED, Windows gate not started

**HOLD / RAW RED PRESERVED / PROJECT REVIEW PENDING.** This is an evidence-only handoff. S2A remains PENDING. The three UI/test source drafts remain uncommitted and unstaged; the complete patch is evidence, not an accepted source commit.

## Identity and narrow draft

Start/source baseline: `b9775b1a764653ad87466a3d578841f7dbf358a3`, parent `0f2f4c5d62114474c9e878f549ce97bd714a2b0a`, branch `feature/phase8-fish-audio`. Local/tracking/live matched at initial preflight. Protected personal root Markdown and three historical JPEGs were not read, modified or staged. The overall worktree is not clean.

The draft changes only:

- `DesktopManagePanel.kt`: expose existing `ManageHeading` internally for reuse; its appearance is unchanged.
- `DesktopFishAudioPanel.kt`: use that same heading for “语音与朗读”; retain “Fish Audio” as subordinate text; fields and persistence behavior are unchanged.
- `DesktopFishPresentationTest.kt`: add a real management-panel Compose test for equal heading styles and parent level, unique voice heading, existing controls and unavailable-model warning, narrow scrolling and save/discard behavior. Existing tests are unchanged.

`source.patch` is the complete +84/-2 draft, SHA-256 **`8175acf5f78b56014a5f0d0dc688619cab5bcadf63aab75fff6947bcd0a3e441`**. `source-manifest.json` records worktree raw bytes, LF-normalized bytes and baseline Git blob identities. Patch equality to the immediate Git diff and all three draft hashes was checked after execution. No Controller, Repository, SecretStore, request protocol, shared Entity/schema, Prompt, baseline or Feature Parity was modified. Fish skill relevance reviewed: this narrow heading reuse does not alter the skill's feature map or contracts, so no skill edit is included.

## Actual execution

One Gradle call only; full exact command in `01-fish-settings-focused/command.txt`, metadata in `stage.json`. JDK17 / offline / no-daemon / no-parallel / single worker / low priority / Kotlin in-process / existing 2GB heap. Task-local `test --rerun` forces this requested test selection; **no `--rerun-tasks`, clean, packaging or full regression**.

- Desktop `compileKotlin`: executed, PASS.
- Desktop `compileTestKotlin`: executed, PASS.
- `test`: executed, exit **1**, measured wrapper duration **93.7473575 seconds**.
- Actual XML: **5 suites /54 tests: 53 reported PASS, 1 failure, 0 errors, 0 skipped**.
- `DesktopFishAudioLibraryTest`:10/10; `DesktopFishSettingsBindingTest`:17/17; `DesktopModelSettingsControllerTest`:14/14; `DesktopSettingsCoherenceTest`:7/7.
- `DesktopFishPresentationTest`:6 tests,5 PASS,1 FAIL. New test `management voice module matches appearance heading and remains operable in narrow layout` timed out after5000ms at line95, waiting for `ScrollByOffset`'s coroutine to complete. Exact stack is preserved in the original XML.

The new test reached the scroll-completion wait after its heading-style, shared parent, uniqueness and existing-field assertions. It did not reach the later viewport-bound, save/discard assertions. This is not a passing test, native UI proof or a confirmed diagnosis of the timeout's underlying cause. No assertion, timeout, exclusion, skip or production behavior was changed after RED. No retry was made.

Per the task's explicit stop rule, bounded Desktop regression, new distribution, W02-R1 user signature and W03-W25 continuation were **NOT RUN / NOT STARTED**. No application Launcher, manual UI, fake service or temporary TLS material was created this round. There is therefore no new distribution identity, Launcher/JVM exit status, app root-lock proof or fake-service cleanup result to claim. The earlier T2 evidence retains its own actual process/lock/service results; these are not reused as new-UI results.

SharedCore compilation was **UP-TO-DATE**, Android **NOT RUN**. Unchanged SharedCore/Android and prior Fish/bounded results are historical evidence only, not this execution. Original T2 **4 PASS /1 FAIL /20 NOT TESTED** and all T1/T2 reports remain immutable. No old NOT TESTED or W02 FAIL has been promoted.

## Resource and data boundaries

Nine stage samples: minimum Available RAM **1.357582 GiB**, minimum Commit headroom **11.987309 GiB**, maximum **6297 pages/sec**. These are sampled stage extrema, not absolute peaks or a responsiveness acceptance. No OOM observed; Gradle completed with a test failure. Post-validation query found zero Java/Javaw processes. Raw samples and retained process identity are included.

Tests used synthetic temporary fixtures and localhost mocks. No real data/credentials/chat was inspected. **Real Fish increment GET0 /TTS0**. No system proxy, certificate store, Registry, pagefile or user process was changed. No G2/Writer/Recovery, later Fish slice, upstream sync, desktop merge or release occurred.

## Evidence identity and history

The copied Gradle stdout/stderr, command, stage/process/resources and five JUnit XML are byte-identical to retained local originals. `run-stage.ps1` and the unchanged four-exclusion bounded init script are included for provenance; the bounded script was **not executed**. `.gitattributes` disables text normalization in this evidence directory. `evidence-manifest.json` covers every other file, including the publication scan and draft patch; the manifest itself is reported separately.

Old T2 directory: `../p8-s2a-r5-t2-manual-20261018/`; old T1: `../p8-s2a-r5-t1-20261018/`. Neither was overwritten. The old T2 manifest identity is recorded in publication checks and verified against its Git HEAD blob. Raw local logs remain under `app/desktopApp/build/p8-s2a-t2-ui-001-r1-20261018/` (gitignored).

**P8-S2A-T2-UI-001-R1 — HOLD / FOCUSED RED / PROJECT REVIEW PENDING.** Source drafts are retained for Project's next narrow disposition. No S2A closure or Windows UI PASS is declared.
