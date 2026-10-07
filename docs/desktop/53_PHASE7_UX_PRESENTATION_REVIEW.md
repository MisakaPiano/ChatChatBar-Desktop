# Phase 7 UX Presentation Closure evidence

Date: 2026-10-07. Same branch: `feature/phase7-image-novelai`.
Start: `dd3e68fa3c78204dd075391e4a779123429581c1`; previous R2/R1 production: `b6f39a4c14e0c000e889ecd2c9b5d79aef85f1c9`.

Status: **READY FOR PROJECT PHASE-7 UX PRESENTATION REVIEW**. R2/R1 semantic implementation and Project review passed, but second user manual acceptance was UX FAIL/HOLD. This task follows `48`/`50`; it does not mark Phase 7 accepted or merged. `44` is prior R2/R1 evidence, not this presentation artifact.

## Implementation boundary

- `DesktopStudioControls` supplies compact ghost actions/chips, switches, anchored keyboard menus, numeric sliders with precise readouts, disclosure, and a 420×300 custom-size editor. Only large (more than 12) card/model choices use the 520×560 searchable window.
- Generation controls use existing `NovelAiGenerationSettings`, `withActiveSettings`, `imageSize`, and shared validation. Wallpaper excludes Square as formal UI does. Sampler/model enum/API ids/capability, HTTP/Prompt runtime, Entity/Package and SecretStore are untouched. `49` is explicitly excluded implementation work.
- One Studio workspace retains Prompt, generation settings, result pane and reachable footer. Narrow layout stacks Prompt before results. AI Design, History, settings, Guidance and Current Image stay auxiliary. Settings windows are sized to task; result actions and top toolbar are compact.
- `StudioPromptTextEditor` is shared by inline and fullscreen sessions. Shared translation offsets are mapped to actual glyph line spans; wrapped Chinese labels paint beneath the corresponding English text. Exact source matching and query-keyed async state reject stale results. The raw `TextFieldValue` is unchanged by annotations; copy/export/request use the same original Prompt. Fullscreen keeps its existing confirm/cancel draft boundary and preserves opening selection on cancel.
- Tag popup retains shared lookup/ranking, shows English/Chinese, flips/clamps within the window, and supports Up/Down, safe Enter/Tab, and Esc. IME composition and selected text prevent completion shortcuts. Lookup warnings stay visible without discarding text.
- Composer image controls are one compact row below the text editor; pending thumbnails have no empty row. Attachment, automatic-image and background callbacks remain under their existing controllers.

## Formal source mapping

Authority: CCB 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`.

| Formal owner | Desktop presentation |
|---|---|
| `ImagePromptToolScreen.kt` / `GenerationSettingsSection` / `AdvancedSettings` | `StudioGenerationSettings`, chips, size editor, collapsed summary, sliders, sampler popup, Random switch |
| `ImagePromptToolScreen.kt` Prompt sections / fullscreen / translation overlay; `NovelAiPromptRendering.kt` | One common raw text editor, glyph-aware Chinese annotation painting, compact expand action, optional sections |
| `NovelAiTagEditor.kt` and shared completion/translation services | Field-anchored translated suggestions and keyboard handling; no ranking/Prompt change |
| `NovelAiDesignScreen.kt`, `NovelAiHistoryScreen.kt`, image guidance/tools | Same reviewed auxiliary owners; compact actions and task-sized windows |
| `ChatScreen.kt` composer image entry | `DesktopChatImageToolbar` and conditional pending strip; existing automatic/background ownership |

## Focused evidence

**PASS: 5 suites / 44 tests / 0 failures / 0 errors / 0 skips**. Includes 14 new presentation tests, R2 wide/narrow scene, R1 ownership safeguards, full composer and keyboard/focus scenes. Desktop compile passed. Tests use local fixtures/fake transport only; no real generation.

Checks cover chip keyboard selection and serialized draft equality; enum menu arrows/Enter/Esc including nullable Follow; advanced collapse; exact slider values; custom-size apply/reset/cancel/invalid Apply; fixed-Seed conditional editor; wrapped annotation positions and stale rejection; inline/fullscreen shared editor/request equality; live local suggestion acceptance; popup placement; compact chat callbacks. Wide/narrow Studio images and focused widget renders are in `app/desktopApp/build/phase7-ux-evidence/` (Studio renders copied there at the gate).

Final-source full suite, isolated distributable, launch smoke and CURRENT reconciliation are complete below. Shared/Android source diff is empty; conditional affected tests are N/A. NovelAI real count stays **1/8**, added **0**. Parked sync untouched. Manual checklist: `51_PHASE7_UX_PRESENTATION_MANUAL_ACCEPTANCE_CN.md`; user checkboxes remain pending.

## Full-gate findings and test correction

The first full run executed 990 tests and found two test-expectation failures; neither was waived. Production remains `4a02cbafe74605d7f14e54ee67d2b4bc846f219e`.

- `DesktopPhase7ReauditTest`: the source wiring guard hardcoded `BootstrapButton`. It now checks the same direct Guidance label-to-auxiliary action without coupling to the replaced button primitive or optional icon.
- `DesktopWorldBookEditorControllerTest`: initial `WorldBookRepository.save` legitimately stamps `updatedAt`. The old test compared the final durable object to the pre-save constructor value, failing when the clock advanced by 1 ms. The fixture now deliberately uses an old timestamp, captures the initial persisted object, and compares the full final object against it. Invalid saves must still preserve every durable field; no timestamp comparison is removed.
- Failed-run log/XML are retained in `app/desktopApp/build/phase7-ux-evidence/full-first-failed/`. These corrections change tests only, with no runtime, repository, shared or schema change. The fresh full gate completed below.
- Correction gate: **2 suites / 37 tests / 0 failures / 0 errors / 0 skips PASS**, BUILD SUCCESSFUL in 41s. Test/evidence checkpoint: `971ce89da4ccc0e9e270d9bfc43970532dbb8f35`; its production tree is identical to `4a02cba`. Log/XML/counts: `phase7-ux-evidence/test-repair/` under the Desktop build directory.
- The subsequent full run exposed a new scene-harness deadlock, not a passing run. `jcmd Thread.print` proved a lock cycle between the JUnit thread driving `ImageComposeScene` during menu remeasurement and AWT's `GlobalSnapshotManager`. Only the verified task-owned worker was stopped. The new presentation scenes now execute with an explicit `SwingUtilities.invokeLater` dispatcher (the test runtime has no installed kotlinx `Dispatchers.Main`), matching real Desktop UI thread ownership; menu scrolling and production code are preserved. Interrupted log and stack: `phase7-ux-evidence/full-harness-deadlock/`. Full validation after this harness correction passed below.
- AWT harness correction gate: **5 suites / 44 tests / 0 failures / 0 errors / 0 skips PASS**, BUILD SUCCESSFUL in 54s. Final focused XML/counts/log: `phase7-ux-evidence/focused-final/`. No new production change.

## Rendered presentation evidence

These are local Compose scenes using benign synthetic text and in-memory secrets. The account-unavailable notice in the empty Studio fixture is expected; no account or generation is contacted. They supplement automated interaction tests and do not sign off user manual acceptance.

- [1280×800 workspace](refs/phase7-ux-presentation/validation/studio-1280.png): Prompt and results side by side, size/count in one card, collapsed advanced summary and persistent footer.
- [700×650 workspace](refs/phase7-ux-presentation/validation/studio-700.png): vertically scrollable stack, reachable footer, no page-wide horizontal scroll.
- [Local translated suggestions and annotation](refs/phase7-ux-presentation/validation/field-suggestion-and-annotation.png): annotation under its source token, separate anchored popup.
- [Advanced settings and fixed Seed](refs/phase7-ux-presentation/validation/advanced-fixed.png): compact readouts, sliders and conditional Seed field.
- [Custom size](refs/phase7-ux-presentation/validation/custom-size-apply.png): local draft, explicit Apply / Reset / Cancel.
- [Expanded editor body](refs/phase7-ux-presentation/validation/prompt-editor-800.png): the same annotation renderer at fullscreen dimensions; not a claim of an automated native-window manual acceptance.

## Final-source consolidated gate

**FINAL-SOURCE FULL REGRESSION PASS** at validation checkpoint `3f638dfd2716b27eb11ab1dc357bf51e4418ea6d`, with production source unchanged from `4a02cbafe74605d7f14e54ee67d2b4bc846f219e`.

- Desktop: **109 suites / 990 tests / 0 failures / 0 errors / 0 skips**, `:desktopApp:test --rerun` executed; BUILD SUCCESSFUL in **2m 5s**, exit 0.
- Desktop compile **PASS** (UP-TO-DATE after the executed focused compilation; no production changes since). SharedCore dependency compile UP-TO-DATE. Shared/Android affected tests/Android compile **N/A** for this Desktop-only change; not claimed rerun.
- Focused final: **5 suites / 44 tests**; test-expectation correction gate: **2 / 37**; zero failures/errors/skips. The complete run includes all these classes.
- Working-tree and complete `desktop...HEAD` `git diff --check`: **PASS**. No production changes during/after the gate. No sharedCore/Android production diff from `dd3e68f`.
- Log/XML/counts: `app/desktopApp/build/phase7-ux-evidence/final-full/`. The earlier failed/interrupted runs remain evidence above; this result supersedes them without waiving tests.

JDK17 and verified bounded-memory command, run from `app/`:

```powershell
.\gradlew.bat :desktopApp:test --rerun :desktopApp:compileKotlin -I desktopApp/build/phase7-r2-r1-ssd.init.gradle --offline --console=plain --no-build-cache --no-configuration-cache --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
```

The existing init script redirects only generated Desktop build outputs to `%TEMP%/ccb-p7-r2-r1-build`; source/worktree/branch remain unchanged. The new acceptance package is copied into its own previously absent UX Presentation distribution directory, preserving all older packages.

## New isolated acceptance package and launch smoke

`:desktopApp:createDistributable --rerun`, using the same JDK17/init/bounded-memory flags above: **BUILD SUCCESSFUL in 31s**, exit 0. Production source is `4a02cbafe74605d7f14e54ee67d2b4bc846f219e`; packaging follows the final full gate without production edits.

Acceptance executable:

`H:\ChatChatBar-Desktop\app\desktopApp\build\phase7-ux-presentation-distribution\compose\binaries\main\app\ChatChatBarDesktop\ChatChatBarDesktop.exe`

Copied package files were compared with build outputs; all hashes match:

| Package file | Bytes | SHA-256 |
|---|---:|---|
| `ChatChatBarDesktop.exe` | 484352 | `E5D45E5389E7584B9D8477EFE34543DE1069E6550560A3A3C53D164152958B8F` |
| `app/desktopApp-6ad266b631ba5a1ace8c8f711efce1.jar` | 76797102 | `5D8C4000727DEC860E1A6078A36E0B8799748CFFC72ED5C561F7225AE428F5A8` |
| `app/sharedCore-61e5b677e5316ef2dd5eeb9c3a255.jar` | 3152834 | `D0A80F01B58D827F7FF2BD5BE6B14BD63C56A88E1999688D877364DA3467A8F3` |

The launcher is unchanged; the Desktop JAR identifies this UI artifact. Older R2/R1 packages are preserved and are not the current acceptance build.

Launch smoke **PASS** with an independent empty process-local APPDATA/LOCALAPPDATA profile at `app/desktopApp/build/phase7-ux-presentation-launch-2b2b8021fc8c421988ddf3d0c173f4b9`. Exact acceptance executable and application child were verified; main `SunAwtFrame`/`ChatChatBar` window found and process remained alive until normal WM_CLOSE. Launcher PID 9444 and application PID 23992 both exited **0**. Stdout/stderr each **0 bytes**. No real user profile or credential was consumed; no Generate/Retry was sent.

Local evidence in `app/desktopApp/build/`: `phase7-ux-presentation-package.log`, `phase7-ux-presentation-package-hashes.json`, `phase7-ux-presentation-launch-smoke.ps1`, and `phase7-ux-presentation-launch-result.json`.

## Durable checkpoints and final boundary

| Commit | Scope |
|---|---|
| `4a02cbafe74605d7f14e54ee67d2b4bc846f219e` | Final production source: Studio/chat presentation, focused tests and relevant skill map |
| `971ce89da4ccc0e9e270d9bfc43970532dbb8f35` | Test-expectation corrections and rendered evidence; no production changes |
| `3f638dfd2716b27eb11ab1dc357bf51e4418ea6d` | AWT scene test harness; final full regression checkpoint; no production changes |
| This docs-only closeout commit | CURRENT reconciliation and package/smoke/full-gate evidence; its Git commit is the final docs HEAD |

Skill relevance reviewed before closeout: documentation-only reconciliation does not stale the runtime skill updated with the production change. Formal baseline, approved `ace632c...` exception, Prompt/Package/Entity/SecretStore ownership and provider capabilities remain unchanged. `desktop` remains `b3ecd41267906526e7b603972f7388e59c90648d`; parked `sync/1.4.4` remains `9b6378dbb595dd2f3ff5143a7a8e46653c99e721`. No merge, no Phase 8. NovelAI real count remains **1/8**, this task **0 additional requests**. Project review and user manual acceptance are pending; Phase 7 is **NOT ACCEPTED / NOT MERGED**.
