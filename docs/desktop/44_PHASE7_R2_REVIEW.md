# Phase 7 R2 — Desktop UX repair evidence

Date: 2026-10-06. Branch: `feature/phase7-image-novelai`.
Start: `b30cfc116249abfbd08ec2217f23b06b9293682a`.
Pre-amendment R2 production/test checkpoint: `b88d4088fe42c3cfa7ab1de8576acd464e5ba896`. The user subsequently added `47_PHASE7_R2_REAUDIT_AMENDMENT.md`; implementation and validation are continuing on the same branch. The 959-test run and package below are historical pre-amendment evidence, not final re-audit acceptance evidence.

Status: **R2 RE-AUDIT IN PROGRESS**. Phase 7 is **NOT ACCEPTED / NOT MERGED**. All GAP rows in `46_PHASE7_IMAGE_SYSTEM_REAUDIT_MATRIX.md` are mandatory under the additive `47` amendment. R2 Project review and consolidated user manual acceptance are pending. R2 follows `40_PHASE7_R2_UX_CONTRACT.md` plus `47`; screenshots in `refs/` are REF only.

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
- TaskRuntime still owns work admission, cancellation and drain. Manual image tasks carry the source message ID for contextual progress.
- CCB PNG edit/crop/cover-up state is transient export state, not Character data. Cancel discards the copy.
- SecretStore remains the only credential owner. R2 sends **0 real NovelAI generation requests**; Phase live count remains **1/8**.
- Formal baseline and the approved exact `ace632c...` safety exception remain unchanged; parked `sync/1.4.4` remains untouched.

## Re-audit closure in progress

The audit inspected `109403c`; the implementation is preserved. A3's existing invocation is in `DesktopImageViewer.DesktopMessageImages`, called by `PrimaryMessageBubble`; A5 already rendered manual task anchors there. These are verified through the real source call chain, not inferred from a service method. Automatic handoff was missing the target ID and now supplies it. The generic composer status is retained only for unanchored work.

- A1/A2/A6: repeated pending picks; inline/expanded composer share one list; edit existing image lists with transient additions; matching metadata filtering; selected generated/plain-image deletion, empty derived-message deletion and source-message preservation. Durable reference publication precedes exact cleanup; unknown updates retain resources.
- A3/A4/A5: discoverable Assistant Generate and secondary requirements dialog, one-run content hint versus saved session preference, source-message task status/Stop/terminal state. Existing shared Designer builders are called; no new Prompt.
- A7/A8: direct toolbar Guidance plus image-use routes; Studio-entry account load, Anlas/V5 allowance, exact shared local accounting/reconciliation, visible fetch failure and unknown free eligibility.
- A9: positive-Prompt search, year/month/day date filter, nested albums and per-depth persisted fold preference via shared filter/folding; full recipe detail and existing reuse/use-as/deletion. Missing original guidance requires explicit confirmation before any degraded apply, including selected-result shortcuts.
- A10: distinct full streamed reverse content, stage, optional reasoning, Stop/retry/candidate/Apply, using existing transport callbacks.

Re-audit focused gate: **Desktop 6 suites / 68 tests and Android 2 suites / 9 tests PASS**, zero failures/errors/skips; sharedCore/Desktop/Android affected compiles PASS. `phase7-r2-reaudit-focused.log`: BUILD SUCCESSFUL in 5m 6s. XML archived in `phase7-r2-evidence/reaudit-focused/{desktop,android}`. Initial compile/test failures were resolved, not waived: test call parameters, a corrupt-record fixture's session-prefixed filename, and Android's cross-module nullable scope access. Final visible-history selection guard plus responsive scene: **2 suites / 11 tests PASS**, zero failures/errors/skips, with Desktop compile PASS (`phase7-r2-reaudit-selection.log`, XML `phase7-r2-evidence/reaudit-selection`). Final full regression, replacement package and smoke: **PENDING**. No additional real NovelAI image request is permitted or sent.

## Pre-amendment validation and artifact (historical)

- Focused Studio/image/chat/browser/background/character-summary validation: **6 suites / 61 tests PASS**, 0 failures/errors/skips. Log `app/desktopApp/build/phase7-r2-focused.log`; XML archive `app/desktopApp/build/phase7-r2-evidence/focused/`.
- **FINAL-SOURCE DESKTOP FULL REGRESSION PASS: 106 suites / 959 tests**, 0 failures/errors/skips. `:desktopApp:test --rerun` actually executed; `:desktopApp:compileKotlin` PASS. Final run: **BUILD SUCCESSFUL in 3m 33s / exit 0**. Log `app/desktopApp/build/phase7-r2-final-full.log`; XML/counts `app/desktopApp/build/phase7-r2-evidence/final-full/`.
- The complete green run used exactly the production/test files committed as `b88d4088fe42c3cfa7ab1de8576acd464e5ba896`; there were no source edits during or after that run. Desktop production compile executed on this source in the preceding run and was UP-TO-DATE in the green rerun. The isolated package build compiles the same source again.
- Initial full validation found one obsolete row-click source assertion: R2 replaces the clickable editor row with a shared character summary and explicit Start Chat action. The test now checks the busy guard on that action and retains the Edit-enabled guard; all service/fixture assertions remain. It was not ignored: the whole 959-test suite was rerun to green. Earlier build-time edit/compiled-output mismatch was also resolved before the final source run. Initial full XML/log retained in `phase7-r2-evidence/initial-full/`.
- Responsive offscreen Studio rendering at **1280×800** and **700×650** PASS; preview changes from side-by-side to stacked, footer Generate stays visible, and layout launches no task. Local images: `app/desktopApp/build/phase7-r2-studio-1280.png` and `phase7-r2-studio-700.png`. These are layout evidence, not user manual acceptance.
- New behavior coverage includes background restart/preference, unchanged official Character background, shared-reference retention, exact-candidate deletion, corrupt/missing authority retention, continuous brush copy preservation, shared character summary, automatic opt-in and direct Prompt-requirement save without committing unrelated unsaved settings.
- SharedCore/Android source diff relative to R2 start is empty; affected shared/Android tests are **N/A**, not claimed as rerun. Existing P7 shared/Android full evidence remains historical in `38_PHASE7_FINAL_REVIEW.md`.
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

Use the new R2 artifact and `43_PHASE7_R2_MANUAL_ACCEPTANCE.md`. The checklist covers composer/images, backgrounds, character start-chat, Studio, AI Design, Current Image/guidance/tools, PNG export and restart persistence. Use local/fake fixtures; do not press real Generate. Existing P7 distribution is not the R2 acceptance artifact. Manual acceptance is still pending and must be performed by the user after Project review.

## Durable checkpoints

| Commit | Scope |
|---|---|
| `109403ca1dc1c037a85943985cd13981733aa58a` | Studio/auxiliary surfaces, chat composer/message actions, private backgrounds, shared character summary/start-chat navigation, export-copy tools, focused tests, R2 reference/contract/checklist and skill map |
| `b88d4088fe42c3cfa7ab1de8576acd464e5ba896` | Direct chat Prompt-requirement save, unrelated-draft preservation and updated Start Chat busy-guard test; pre-amendment production/test checkpoint |
