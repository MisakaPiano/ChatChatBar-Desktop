# Phase 7 R2 — Desktop UX repair evidence

Date: 2026-10-06. Branch: `feature/phase7-image-novelai`.
Start: `b30cfc116249abfbd08ec2217f23b06b9293682a`.
Final R2 R1 production/test source: `b6f39a4c14e0c000e889ecd2c9b5d79aef85f1c9`. The user-authorized additive `47_PHASE7_R2_REAUDIT_AMENDMENT.md` and the subsequent narrow R1 are implemented on the same branch. Previous `1843dac` / `8a0d206` validation and package are retained below as historical pre-R1 evidence only.

Status: **READY FOR PROJECT PHASE-7 R2 R1 REVIEW**. Phase 7 is **NOT ACCEPTED / NOT MERGED**. Project reviewed `8a0d206` / source `1843dac` and accepted the architecture and majority of closure, but identified four remaining workflow mismatches. All four are addressed and validated below on the same feature branch. Previous packages remain historical; use the R1 artifact below. R2 follows `40` / `47` plus the user's narrow R1 review; screenshots remain REF only. Project R1 review and user manual acceptance are not signed by this evidence.

## Narrow R1 response

- R1-1 corrects the previous inverted missing-guidance rule. Desktop now calls the unchanged shared `requiresImageGuidanceReuseWarning`: missing-source FULL is unavailable even with confirmation; NEW_SEED and SEED_ONLY require warning confirmation. History shows disabled `缺少来源`; recent results expose only NEW_SEED / SEED_ONLY, as formal `ImagePromptToolScreen` does. No degraded-FULL path remains.
- R1-2 uses one pure Desktop presentation helper matching formal `ImageUseAsDialog` / `HistoryUseAsDialog`: V4.5 all targets; V5 only IMAGE_TO_IMAGE / INPAINT. Current Image, History and direct import choices share it; guidance runtime/model policy is unchanged.
- R1-3 adds anchored terminal Retry/Dismiss. Explicit Retry re-admits the original work closure, preserving manual hint/final requirement and regeneration inputs. Original source validity and automatic opt-in/stop checks run again. Checkpoints are bounded in-memory TaskRuntime state, never Entity/Package data or diagnostics. Dismiss removes task presentation only, not saved images.
- R1-4 derives the label from shared guidance `summary(model)`: blank/inactive is neutral, active shows the effective summary.
- Focused R1 validation: **5 suites / 37 tests PASS**, zero failures/errors/skips, including all 7 new R1 tests. sharedCore/Desktop/Android affected compiles PASS (sharedCore/Android UP-TO-DATE, Desktop executed). Full Desktop regression, replacement isolated package and smoke: **PASS** below. No sharedCore/Android source changes are needed for this R1; their affected tests are N/A. NovelAI additional real requests: 0; Phase count 1/8.
- Focused log: `app/desktopApp/build/phase7-r2-r1-focused-ssd.log`; XML/counts: `app/desktopApp/build/phase7-r2-evidence/r1-focused/`. BUILD SUCCESSFUL in **32m 20s**, exit 0. An earlier attempt was explicitly cancelled during severe H: output I/O contention, before tests ran; it is not passing evidence and no failed assertion was waived. The retry keeps the same worktree/JDK17/bounded-memory options and overrides only Desktop generated build outputs to `%TEMP%/ccb-p7-r2-r1-build` via `phase7-r2-r1-ssd.init.gradle`.

## R1 final-source full regression

**FINAL-SOURCE DESKTOP FULL REGRESSION PASS** at `b6f39a4c14e0c000e889ecd2c9b5d79aef85f1c9`: **108 suites / 976 tests / 0 failures / 0 errors / 0 skips**. `:desktopApp:test --rerun` actually executed; BUILD SUCCESSFUL in **5m 16s**, exit 0. Source has not changed since the focused gate; Desktop compile is UP-TO-DATE after its executed focused compilation. SharedCore/Android compile also passed in that gate. SharedCore/Android tests are N/A for this Desktop-only R1, not claimed rerun.

- Full log: `app/desktopApp/build/phase7-r2-r1-final-full.log`; XML/counts: `app/desktopApp/build/phase7-r2-evidence/r1-final-full/`.
- Implementation checkpoint: `77d0942`; final production refinement: `b6f39a4` preserves the previous per-attempt default transport construction while allowing fake HTTP injection in tests. No HTTP request semantics, Prompt, Entity or Package changes.
- Working directory remains `app/` in the same feature worktree. JDK17, Android SDK and bounded-memory options match prior validated gates; only Desktop generated outputs are redirected by the init script.
- Working-tree and complete `desktop...HEAD` diff-check: **PASS**. SharedCore/Android source diff from reviewed `8a0d206` is empty. `desktop` / `origin/desktop` remain `b3ecd41267906526e7b603972f7388e59c90648d`; parked sync remains `9b6378dbb595dd2f3ff5143a7a8e46653c99e721`.

```powershell
.\gradlew.bat :desktopApp:test --rerun :desktopApp:compileKotlin -I desktopApp/build/phase7-r2-r1-ssd.init.gradle --offline --console=plain --no-build-cache --no-configuration-cache --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
```

## R1 isolated distribution / launch smoke

New `:desktopApp:createDistributable` using the same SSD init override and bounded-memory flags: **PASS**, BUILD SUCCESSFUL in **1m 15s**, exit 0. The new distribution was copied to the previously absent R1 artifact directory below; EXE and both production JAR hashes match the build output. No prior distribution was overwritten. Production source remains `b6f39a4c14e0c000e889ecd2c9b5d79aef85f1c9`; subsequent reconciliation changes docs only.

- Acceptance executable: `H:\ChatChatBar-Desktop\app\desktopApp\build\phase7-r2-r1-distribution\compose\binaries\main\app\ChatChatBarDesktop\ChatChatBarDesktop.exe`.
- EXE SHA-256: `E5D45E5389E7584B9D8477EFE34543DE1069E6550560A3A3C53D164152958B8F` (shared launcher; production JAR identity follows).
- `app/desktopApp-bb346ca95bd74689479fb5ccd6b54.jar` SHA-256: `22D915E45B931782C8064BB58E3BF8BA7DFB6BE455ED115289FE0C754C643714`.
- `app/sharedCore-61e5b677e5316ef2dd5eeb9c3a255.jar` SHA-256: `D0A80F01B58D827F7FF2BD5BE6B14BD63C56A88E1999688D877364DA3467A8F3` (unchanged shared source).
- Launch smoke **PASS** using the copied acceptance EXE and new empty process-local APPDATA/LOCALAPPDATA at `app/desktopApp/build/phase7-r2-r1-launch-a43cf00584824c14ac2cc00948c3b9a3`.
- Exact PID/class/title matched the main `ChatChatBar` window; alive check and normal `WM_CLOSE` succeeded. Launcher **5816** and application **33352** exited **0**; stdout/stderr each **0 bytes**. No real user profile, credential or generation action was used.
- Local evidence: `app/desktopApp/build/phase7-r2-r1-package.log`, `phase7-r2-r1-package-hashes.json`, `phase7-r2-r1-launch-result.json` and `phase7-r2-r1-launch-smoke.ps1` in that same build directory.
- Manual checklist: `43_PHASE7_R2_MANUAL_ACCEPTANCE.md`, updated for the four R1 behaviors; all user acceptance checkboxes remain pending. Added real NovelAI requests **0**; total **1/8**.

## Source/workflow mapping

All upstream mappings use the formal baseline `CCB 1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`.

| Upstream source / workflow | Desktop R2 equivalent |
|---|---|
| `ui/imageprompt/ImagePromptToolScreen.kt`: main Prompt and generation controls; `onOpenAiDesign`, `onOpenHistory`, `showImageTools`, `showGenerationOptions`, `showGuidanceEditor` | One `DesktopNovelAiStudioPanel` workspace, responsive Prompt/parameter column and selected-result preview, persistent generation footer, auxiliary dialogs |
| `NovelAiDesignScreen.kt`: `EmptyDesignConversation`, `DesignComposer`, `PendingStudioPromptAttachment`, `PromptReplyBubble`, `FailedDesignBubble` | Dedicated AI Design conversation surface, separate design settings/history, new conversation, explicit current positive attachment summary, retry/edit-branch/regenerate/apply |
| `NovelAiHistoryScreen.kt`: history gallery/detail/reuse | Auxiliary generation-history browser, image selection, confirmed deletion, full/new-seed/seed-only reuse; selected current-result shortcuts |
| `NovelAiImageGuidanceEditor.kt` / `NovelAiImageAction.kt` | Current Image → use as img2img / precise / Vibe / focused inpaint → existing guidance editor; shared types and preparation unchanged |
| `ChatScreen.kt`: composer `onImage`, selected-image strip, Assistant generation action | Attachment toolbar and thumbnails inside composer; compact image menu with explicit automatic opt-in, model summary, session requirements and Open Studio |
| `ChatViewModel.generateNovelAiImage` / `startNovelAiImageGeneration` | Assistant text action calls the existing shared Designer, model/size resolution and generation runtime; persisted-source recheck and existing linked image persistence; metadata regeneration stays image-owned |
| Existing Desktop `CharacterSessionService` adapter and `DesktopCharacterManagementPresentation` | Shared character summary card in New Chat and Management; both call `DesktopPrimaryChatController.createSession`, then select/navigate to Chat |
| `ImageMosaicEditor` and existing Desktop Image Workspace / CCB PNG export boundary | Continuous mosaic/black/white cover-up brush, common visual editor, bounded undo/redo/reset; cover drag/wheel and zoom slider, export-only replacement copy |

Desktop uses searchable choices, native pickers, wide side-by-side panes and narrow stacked layout rather than phone spacing. AI Design settings and history remain auxiliary to its conversation. Studio settings hold catalog/local annotation/copy preferences. Imported image actions include metadata, reverse candidate/progress/Stop, image editing, copy/save/reveal and guidance use. The screenshot's standalone enhancement/upscale action is omitted because the currently shared Desktop runtime has no corresponding standalone enhancement transport; no fake action was added.

## Private background ownership

`DesktopCharacterBackgrounds` owns `entities/desktop_character_backgrounds/<characterId>.json`, with root-relative, deep-copied image resources. It is included in existing app-data backup/root migration, but never serialized into CharacterCard or Card Package. `CharacterCard.chatBackground`, `ChatSession.chatBackground` and global opacity retain their formal meaning.

Rendering precedence: session override → readable Desktop preferred character background → official CharacterCard background. Chat's Background dialog exposes source, session choose/clear, global opacity, library add/delete and preferred/clear actions. Missing/corrupt preferences retain resources and visibly fall back. Mutation requires strictly readable authority. Deletion publishes the reference change before exact-candidate cleanup; generic cleanup validates the private library structure and protects references from other owners. No root sweep occurs.

## Boundaries

- The re-audit moves unchanged JVM-neutral `NovelAiHistoryFilter.kt` and `NovelAiAccountUiState` from Android to sharedCore, preserving the existing `ui.imageprompt` package and Android call sites. No Entity/Package changes, Prompt literal changes, Designer protocol changes or NovelAI HTTP changes.
- Existing automatic completion/refusal/truncation/Stop/edit/deletion policy is unchanged; no legacy eligibility judge is called.
- TaskRuntime still owns work admission, cancellation and drain. Manual/regeneration and automatic image tasks carry the source message ID for contextual progress.
- CCB PNG edit/crop/cover-up state is transient export state, not Character data. Cancel discards the copy.
- SecretStore remains the only credential owner. R2 sends **0 real NovelAI generation requests**; Phase live count remains **1/8**.
- Formal baseline and the approved exact `ace632c...` safety exception remain unchanged; parked `sync/1.4.4` remains untouched.

## Re-audit implementation closure

The audit inspected `109403c`; the implementation is preserved. A3's existing invocation is in `DesktopImageViewer.DesktopMessageImages`, called by `PrimaryMessageBubble`; A5 already rendered manual task anchors there. These are verified through the real source call chain, not inferred from a service method. Automatic handoff was missing the target ID and now supplies it. The generic composer status is retained only for unanchored work.

- A1/A2/A6: repeated pending picks; inline/expanded composer share one list; edit existing image lists with transient additions; matching metadata filtering; selected generated/plain-image deletion, empty derived-message deletion and source-message preservation. Durable reference publication precedes exact cleanup; unknown updates retain resources.
- A3/A4/A5: discoverable Assistant Generate and secondary requirements dialog, one-run content hint versus saved session preference, source-message task status/Stop/terminal state. Existing shared Designer builders are called; no new Prompt.
- A7/A8: direct toolbar Guidance plus image-use routes; Studio-entry account load, Anlas/V5 allowance, exact shared local accounting/reconciliation, visible fetch failure and unknown free eligibility.
- A9: positive-Prompt search, year/month/day date filter, nested albums and per-depth persisted fold preference via shared filter/folding; full recipe detail and existing reuse/use-as/deletion. R1 corrects the original degraded-apply claim: missing-source FULL is blocked, and both NEW_SEED and SEED_ONLY require formal warning confirmation, including recent-result shortcuts.
- A10: distinct full streamed reverse content, stage, optional reasoning, Stop/retry/candidate/Apply, using existing transport callbacks.

Re-audit focused gate: **Desktop 6 suites / 68 tests and Android 2 suites / 9 tests PASS**, zero failures/errors/skips; sharedCore/Desktop/Android affected compiles PASS. `phase7-r2-reaudit-focused.log`: BUILD SUCCESSFUL in 5m 6s. XML archived in `phase7-r2-evidence/reaudit-focused/{desktop,android}`. Initial compile/test failures were resolved, not waived: test call parameters, a corrupt-record fixture's session-prefixed filename, and Android's cross-module nullable scope access. Final visible-history selection guard plus responsive scene: **2 suites / 11 tests PASS**, zero failures/errors/skips, with Desktop compile PASS (`phase7-r2-reaudit-selection.log`, XML `phase7-r2-evidence/reaudit-selection`). No additional real NovelAI image request is permitted or sent.

## Pre-R1 re-audit regression (historical)

**FINAL-SOURCE FULL REGRESSION PASS**, production/test source `1843dac42bcbcc8aac4fc7a9f46450946edc94d2`. No source changes during or after this gate.

| Module / scope | Suites | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|---:|
| sharedCore full | 103 | 685 | 0 | 0 | 0 |
| Desktop full | 107 | 969 | 0 | 0 | 0 |
| Android affected account/history | 2 | 9 | 0 | 0 | 0 |

- All three test tasks actually executed with `--rerun`; **BUILD SUCCESSFUL in 3m 14s**, exit 0. All three affected compiles PASS/UP-TO-DATE after executed compiles in focused validation. This is Android affected validation, not a claimed Android full rerun.
- Log: `app/desktopApp/build/phase7-r2-reaudit-final-full.log`. XML and counts: `app/desktopApp/build/phase7-r2-evidence/reaudit-final-full/`.
- Working-tree, R2-start and desktop-base `git diff --check`: PASS. Formal-baseline comparison verifies byte-equivalent history/account declarations after newline normalization; Android history scope access has only an equivalent cross-module null-safe adaptation.
- The final full suite includes R2 Studio/chat/background/character-summary tests and all new re-audit tests. Updated responsive offscreen scenes at 1280×800 and 700×650 were inspected; direct Guidance and visible account failure state remain clear, with fixed generation footer. This does not sign user manual acceptance.

Executed from `app/`, existing JDK17/Android SDK and bounded-memory configuration:

```powershell
.\gradlew.bat :sharedCore:test --rerun :desktopApp:test --rerun :app:testDebugUnitTest --rerun --tests '*NovelAiAccountUiStateTest' --tests '*NovelAiHistoryFilterPolicyTest' :sharedCore:compileKotlin :desktopApp:compileKotlin :app:compileDebugKotlin --offline --console=plain --no-build-cache --no-configuration-cache --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
```

## Pre-R1 re-audit distribution / smoke (historical)

New isolated `createDistributable`: **PASS**, BUILD SUCCESSFUL in **1m 38s**, exit 0. Production source remains `1843dac42bcbcc8aac4fc7a9f46450946edc94d2`; the final reconciliation commit changes docs only. Earlier distributions are preserved and are not this acceptance artifact.

```powershell
.\gradlew.bat :desktopApp:createDistributable -I desktopApp/build/phase7-r2-reaudit-distribution.init.gradle --offline --console=plain --no-build-cache --no-configuration-cache --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
```

The init script changes only the Desktop build directory. Log: `app/desktopApp/build/phase7-r2-reaudit-package.log`.

- Acceptance executable: `H:\ChatChatBar-Desktop\app\desktopApp\build\phase7-r2-reaudit-distribution\compose\binaries\main\app\ChatChatBarDesktop\ChatChatBarDesktop.exe`.
- EXE SHA-256: `E5D45E5389E7584B9D8477EFE34543DE1069E6550560A3A3C53D164152958B8F`.
- Packaged `app/desktopApp-74e85eb085bf14a2cae2dafd02185a.jar` SHA-256: `9230C88A2ECBB5B376C193E64F42A10632325607996E3CCC19F4F3C10F2EF769`.
- Packaged `app/sharedCore-61e5b677e5316ef2dd5eeb9c3a255.jar` SHA-256: `D0A80F01B58D827F7FF2BD5BE6B14BD63C56A88E1999688D877364DA3467A8F3`.
- Launch smoke **PASS** with new empty process-local APPDATA/LOCALAPPDATA at `app/desktopApp/build/phase7-r2-reaudit-launch-cf7aa85a44a14180ab56fefebbdbe3f3`. No real user profile or credential was used.
- Exact child PID/class/title identified the main `ChatChatBar` window; alive check passed; normal `WM_CLOSE` succeeded. Launcher **34504** and application **22508** both exited **0**; stdout/stderr both **0 bytes**. No forced termination or generation action.
- Machine result: `app/desktopApp/build/phase7-r2-reaudit-launch-result.json`; helper: `phase7-r2-reaudit-launch-smoke.ps1` in the same build directory.
- Added real NovelAI requests: **0**. Total accepted Phase-7 live count remains **1/8**; the remaining fuse is frozen.

## Pre-amendment validation and artifact (historical)

- Focused Studio/image/chat/browser/background/character-summary validation: **6 suites / 61 tests PASS**, 0 failures/errors/skips. Log `app/desktopApp/build/phase7-r2-focused.log`; XML archive `app/desktopApp/build/phase7-r2-evidence/focused/`.
- **FINAL-SOURCE DESKTOP FULL REGRESSION PASS: 106 suites / 959 tests**, 0 failures/errors/skips. `:desktopApp:test --rerun` actually executed; `:desktopApp:compileKotlin` PASS. Final run: **BUILD SUCCESSFUL in 3m 33s / exit 0**. Log `app/desktopApp/build/phase7-r2-final-full.log`; XML/counts `app/desktopApp/build/phase7-r2-evidence/final-full/`.
- The complete green run used exactly the production/test files committed as `b88d4088fe42c3cfa7ab1de8576acd464e5ba896`; there were no source edits between that run and creation of the `b88d408` checkpoint. Desktop production compile executed on this source in the preceding run and was UP-TO-DATE in the green rerun. The isolated package build compiles the same source again.
- Initial full validation found one obsolete row-click source assertion: R2 replaces the clickable editor row with a shared character summary and explicit Start Chat action. The test now checks the busy guard on that action and retains the Edit-enabled guard; all service/fixture assertions remain. It was not ignored: the whole 959-test suite was rerun to green. Earlier build-time edit/compiled-output mismatch was also resolved before the final source run. Initial full XML/log retained in `phase7-r2-evidence/initial-full/`.
- Responsive offscreen Studio rendering at **1280×800** and **700×650** PASS; preview changes from side-by-side to stacked, footer Generate stays visible, and layout launches no task. Local images: `app/desktopApp/build/phase7-r2-studio-1280.png` and `phase7-r2-studio-700.png`. These are layout evidence, not user manual acceptance.
- New behavior coverage includes background restart/preference, unchanged official Character background, shared-reference retention, exact-candidate deletion, corrupt/missing authority retention, continuous brush copy preservation, shared character summary, automatic opt-in and direct Prompt-requirement save without committing unrelated unsaved settings.
- At the pre-amendment `b88d408` checkpoint, SharedCore/Android source diff relative to R2 start was empty; affected shared/Android tests were **N/A**, not claimed as rerun. Existing P7 shared/Android full evidence remains historical in `38_PHASE7_FINAL_REVIEW.md`.
- Working-tree, R2-start and desktop-base `git diff --check`: **PASS**.

Commands run from `app/` with JDK `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot` and the existing Android SDK:

```powershell
.\gradlew.bat :desktopApp:test --rerun :desktopApp:compileKotlin --offline --console=plain --no-build-cache --no-configuration-cache --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
.\gradlew.bat :desktopApp:createDistributable -I desktopApp/build/phase7-r2-distribution.init.gradle --offline --console=plain --no-build-cache --no-configuration-cache --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
```

The init script sets only `:desktopApp`'s build directory to `build/phase7-r2-distribution`, preserving the pre-R2 package.

Isolated R2 `createDistributable`: **PASS**, BUILD SUCCESSFUL in **9m 22s**, exit 0. Log: `app/desktopApp/build/phase7-r2-package.log`.

- R2 executable: `H:\ChatChatBar-Desktop\app\desktopApp\build\phase7-r2-distribution\compose\binaries\main\app\ChatChatBarDesktop\ChatChatBarDesktop.exe`.
- EXE SHA-256: `E5D45E5389E7584B9D8477EFE34543DE1069E6550560A3A3C53D164152958B8F`.
- Packaged production JAR: `app/desktopApp-2b5a16c6153a6435f26f3d1e3fcc2e9.jar` inside that application directory; SHA-256: `76730B78F229D64E9ECCA0727A0978BC9914D8B2EE8FD3B69DD8890E86608E6C`.
- Launch smoke: **PASS** with a new empty process-local APPDATA/LOCALAPPDATA profile at `app/desktopApp/build/phase7-r2-launch-f911d69289284f17995bba3afd1e48b3`. Real user data and credentials were not accessed.
- Main `ChatChatBar` window identified by exact child PID/class/title and remained alive; normal `WM_CLOSE` succeeded. Launcher PID 25860 and application PID 23844 both exited **0**. stdout/stderr **0 bytes**. No force termination, no generation action, no additional live request.
- Machine-readable result: `app/desktopApp/build/phase7-r2-launch-result.json`; local smoke helper: `app/desktopApp/build/phase7-r2-launch-smoke.ps1`.
- This is the historical pre-amendment artifact from `b88d408`; it is not the final re-audit acceptance artifact. The pre-R2 `f8ca261` distribution was also preserved.

## Manual acceptance

Use the new `phase7-r2-reaudit-distribution` artifact and `43_PHASE7_R2_MANUAL_ACCEPTANCE.md`. The checklist covers multi-image composer/edit/delete, per-run requirements/source task state, backgrounds, character start-chat, Studio/account, AI Design, History search/date/albums/detail/protected reuse, Current Image/reverse/guidance/tools, PNG export and restart persistence. Use local/fake fixtures; do not press real Generate. Earlier P7/R2 distributions are not this acceptance artifact. Manual acceptance is still pending and must be performed by the user after Project review.

## Durable checkpoints

| Commit | Scope |
|---|---|
| `109403ca1dc1c037a85943985cd13981733aa58a` | Studio/auxiliary surfaces, chat composer/message actions, private backgrounds, shared character summary/start-chat navigation, export-copy tools, focused tests, R2 reference/contract/checklist and skill map |
| `b88d4088fe42c3cfa7ab1de8576acd464e5ba896` | Direct chat Prompt-requirement save, unrelated-draft preservation and updated Start Chat busy-guard test; pre-amendment production/test checkpoint |
| `1843dac42bcbcc8aac4fc7a9f46450946edc94d2` | Re-audit chat-image workflows, shared account/history authorities, Studio account/Guidance/History/reverse UX, focused tests and audit response; final production/test source |
| Feature HEAD containing this final report | Docs-only reconciliation of final-source regression, new package/smoke and all re-audit closure evidence |
