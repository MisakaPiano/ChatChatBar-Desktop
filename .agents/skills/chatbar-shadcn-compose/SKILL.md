---
name: chatbar-shadcn-compose
description: Build or migrate ChatBar Android UI using shadcn/ui design principles implemented with Jetpack Compose Foundation primitives. Use for ChatBar themes, UI kit components, responsive or compact-height layouts, screens, forms, dialogs, sheets, tabs, list items, buttons, inputs, visual consistency, or removal of Material 3 UI dependencies.
---

# ChatBar shadcn Compose

Device-group surfaces are Android `lanSync/LanSyncGroupPanel.kt` and Desktop `lanSync/DesktopLanSyncGroupPanel.kt`, over the shared LanSyncGroupPanelState/actions. They use existing native tokens/components, bounded recovery-code bodies, transient secure inputs and explicit saved-code/peer/revocation confirmation. Lifecycle disposal locks management only; app runtime attachment remains separate. Desktop LabeledField now accepts enabled (defaulttrue) so busy group fields cannot edit; existing callers retain their prior default. These new surfaces still require live controller/navigation hooks and exact UI execution before claiming app onboarding.

Desktop editor auxiliary surfaces (field Token estimate, card AI, image-to-appearance and generated-cover/avatar candidates) reuse `DesktopEditorAuxiliaryWindow` and ordinary native tool chrome. It retains modal-activity accounting and supplies its actual native picker owner; `LocalDesktopEditorAuxiliaryFrame` is only the synthetic Scene seam. Page anchors such as style/image parameters remain in the editor. Field estimates partition the existing report through `DesktopEditorBudgetGroups`, with part/person/entry subtotals and nested disclosure; no second estimator or injection prediction is introduced. Avatar import in global/session settings and card/person slots first opens the existing crop workspace, retaining transient source bytes until explicit Apply; session application checks the captured session identity. Independent cover import remains raw, with export composition handling positioning. Player animation inputs explicitly use their first frame, as before. The workspace awaits suspendable apply callbacks before releasing its busy guard.

Desktop character editor exposes the independent original-cover slot without crop controls, plus copy-avatar/clear. Cover export retains original/layout modes separately: original preview is fit-only, crop/gradient/logo/title controls appear only for layout, and temporary replacements/undo affect the export copy. See `chatbar-desktop-character-cover` for saved-original custody.

`CharacterPersonRow` places its two-line profile below the avatar/name/voice/actions row across the existing master width; it must not widen the320dp master or squeeze the detail pane. Narrow collapsed Studio puts history/viewer/image operations beside the thumbnail inside `DesktopStudioCompactResult`; expanded and wide preview toolbars keep their prior owners.

Desktop character-position canvas renders numbered markers using original draft order, draws the selected marker last, and shows the 5×5 reference grid in V5 as well as V4.5; only V4.5 snaps. Canvas bounds follow the actual generation/focused-inpaint request ratio. Keep existing live-edit guards, person/model numeric history scopes and scrollable controls; visual numbering must not alter center values or participation.

Structured character detail's image Prompt reuses `StudioPromptField` through `DesktopCharacterImagePromptField`, including local tag candidates, raw-text Chinese overlays and isolated expanded editing. It remains inside `CharacterPersonWorkspace`'s draft/person/detail key; keep the same scoped history and captured-identity publication guard when rearranging this section. The translation toggle changes the existing global display preference, never the stored Prompt text.

Cover export's restore-source action is present only with a replacement; its caption follows independent cover or legacy background. It clears the export-only replacement and resets three crop fields through the same undo history while retaining gradient/title/logo options; “重置全部” remains separate. Neither action writes the card or packaged source images.

Desktop Moments uses `DesktopMomentFeedCard` inside a bounded feed, with author/body/image alignment, an avatar gutter and compact footer actions. `DesktopVersionControls` shares chat alternative arrow/count styling with retained Moment image selection; labels and selection/persistence owners remain caller-specific. Actual-Panel synthetic narrow/wide tests retain screenshots through `CCB_FORMAT_AI_REVIEW_DIR`.

Chat's complete previous/count/next version group lives in `DesktopChatMessageToolbar` immediately after regenerate, then More/voice; keep it one group and one footer row, with the original eligibility/selection owners. `DesktopChatDisclosure(compact=true)` and display-only status surface padding make collapsed status headers30dp. `desktopStatusDisclosureLabel` uses explicit ownership from the current closed source block with rendered placeholder text, falling back to Status; never borrow an adjacent speaker. Recognized action-choice blocks retain their own Action choices label and original callback gating. Shared screenshot-document metrics/filtering are unchanged.

Role cards keep expansion while any child in the stable Prompt editing group (tabs/input/expand) has focus; header enable/order/delete controls must not auto-expand a collapsed Prompt; a disposed positive/negative field must not collapse the card or overwrite group focus. Role inline headers combine tabs, modal expand and position, while generic Prompt fields retain their original minimum; compact role text grows by content under the existing220dp scroll cap. Studio history/viewer/layout and expanded nonrail fit/reset share the upper wrapping workspace toolbar. Image operations lead the lower result row before recipe-dependent redraw/seed actions; compact and rail layouts retain image operations without exposing recipe actions. Compact thumbnails retain pane-open and viewer-double-click gestures.

`StudioAccountActions` is the Studio-only right-aligned wrapping account row (current label, account gear, refresh), with vertical centering; allowance lines/progress and creative actions keep their separate left-aligned owners. `DesktopStudioAccountLayoutTest` uses synthetic names at220/360/640/1100 widths; do not upload user feedback screenshots as fixtures.

Studio guidance uses compact source cards in the editor and guidance window: separate stable-key rows for base, precise and each Vibe, active/retained state, dimensions, thumbnail and accessible preview/replace/remove icons. Encoded-only references explicitly lack an original thumbnail. Same-image uses are never deduplicated. The auxiliary-window open revision requests restore/front on explicit open actions without recreating its draft or native window.

Studio annotation resolution keys include the retained draft's `promptContentRevision`, translation-service identity and local replay/annotation revisions as well as visible source/consent. The shared repository deliberately leaves a same-value draft unchanged; successful explicit `controller.replace` separately increments `textHistories.annotationRevision`, without forcing an editor echo or adding a no-op undo entry. `DesktopStudioImportedAnnotationTest` holds a synthetic initially unavailable tag translation through same-text import and checks requery, actual pixels, unchanged selection and the original undo boundary without toggling consent.

Studio Prompt editing uses a per-field `InterceptPlatformTextInput` wrapper to resolve the current source-glyph caret rectangle from live decoration coordinates at platform query time, avoiding the legacy adapter's cached field-to-root position after ancestor movement. Preserve the delegated text/IME edit request. `studioPromptCaretRect` preserves the complete source-font metrics contract; `studioPromptSourceBand` clips that rectangle to actual layout line boundaries for the IME and visible caret/selection, including fractional baseline differences. Translated mode paints selection only inside source-glyph bands while keeping translation glyphs outside the editable text and native selection background transparent. `DesktopStudioInputHistorySceneTest` covers synthetic request routing and coordinates; it is not actual Windows Chinese IME acceptance.

`desktop-native-probe.yml` is a separate Windows cloud entry for explicitly selected existing synthetic graphical cases with `desktopTestHeadless=false`. The ordinary platform job remains headless; retain original graphical guards and inspect actual XML/runner scope before claiming those cases ran. It neither installs an app nor proves physical audio or all user desktop environments.

`Set-CloudNativeProbeDisplay.ps1` refuses outside the exact owned GitHub Windows runner before loading native code. It records display/audio facts and selects only an enumerated/tested temporary display mode for fixtures wider than the default 1024px cloud desktop, then restores the saved mode. No registry update or local execution. The mixed Fish Stop case is split into unchanged download/generation checks and a real-playback case using the existing wave-device assumption; missing physical output is NOT_TESTED, never fake-audio PASS.

`DesktopCharacterEditorController` preserves nonblank negative-prompt source bytes through display, draft and unrelated saves. Blank values keep the existing displayed/materialized default; final generation still uses the separate effective-value helper. Do not normalize source editing by calling the request helper in `displayCard` or save projection.

Desktop card editing follows the pinned official147 section sequence: card identity, greeting, background, Moments, common setting, NAI style/gallery, collapsible image advanced settings, negative prompt, person workspace, advanced fields, documents and bindings. Desktop alternate greetings remain in their adjacent disclosure. Displaying nullable image parameters does not materialize an override; Restore removes only the parameter override, preserving selected model. Original draft/save/cancel and read-only owners remain. `DesktopCharacterEditorOrderTest` covers actual section semantics, compact reachability and draft lifecycle; `DesktopStyleGalleryTest` opens the image disclosure after jumping. Animated jump review in `DesktopCharacterEditorOrderTest` uses the unique style tag’s unclipped position relative to the real scroll viewport, and the same monotonic `System.nanoTime` for waits and final capture; `ImageComposeScene.render()` defaults to time0 and can redraw an animation’s starting frame. Merged Text ancestors or clipped nonzero bounds alone do not establish the destination is visible.

`StudioPromptTextEditor` keeps raw text as the BasicTextField/IME source and translations as glyph overlays. In translated mode its blinking caret is drawn from source-font metrics at the current source baseline, rather than the enlarged annotation line height; nontranslated caret, selection and IME text remain native. `studioPromptCaretRect` is checked with actual shaped multiline/wrapped text.

`DesktopReversePromptPane` is the actual imported-image reverse surface: editable bounded scene input with the shared caret token, explicit scene confirmation, collapsible transcript/reasoning and a separate final Apply action. Retry recognizes the image again; editing clears only the final candidate. Its420/1100 Scene regression uses the real controller and `CCB_FORMAT_AI_REVIEW_DIR`; no native/provider operation is implied.

Desktop community uses `DesktopCommunityPanel` inside the primary Shell, gated by service status. Its private dialog wraps short content and caps long scroll bodies, fixes title/close/actions, projects errors inside the active dialog, and preserves the original management transfer-conflict overlay. Snapshot preview chunks retain Unicode pairs and the original card creator/version/default image text; publishing and owner deletion retain explicit confirmation. The actual-Panel Scene records420 Chinese dark/1100 English light views with an opaque page background. Its six detail/snapshot/delete images and the one real-Shell route image use `CCB_FORMAT_AI_REVIEW_DIR`; other Shell captures retain their original directory. None invokes native/browser/provider operations.

`DesktopInputFocus.kt` supplies `LocalDesktopModalActivity` at the main application root. Ordinary `DesktopModalSurface` instances register their lifetime; the startup update notice opts out of registering itself and waits while another modal is present, avoiding recursive self-suppression.

Desktop text inputs use `DesktopBootstrapColors.inputCaret` with their foreground text color. Compose's default black caret is invisible on the dark input surface; keep composer, search, editors, Studio and credential fields on the same theme token. The separate Swing startup/shutdown diagnostic text areas are read-only.

Desktop chat bubbles consume `DesktopTaskRuntime.activeChatStreams`, the full process-local active text/reasoning, not scrubbed/bounded `DesktopTaskEntry` previews. The active map retires with the job; viewport update keys use the same full stream. `DesktopRealChatObserver.onUserMessagePersisted` reports the durable USER ID separately from text updates; Panel effects refresh that stable ID once while RUNNING, reading the stored entity and preserving drafts/settings and historical pages. Existing stream observers retain first-token timing. `DesktopLiveChatDisplayTest` drives actual Panel effects and semantics with held synthetic SSE, preserving EOF error-Assistant versus explicit-stop partial policies. The temporary CI baseline request overlays only those tests/fixture on public1228 and retains expected failures in a separate artifact; remove the request after proof, preserving receipts.

Desktop management and Data use `DesktopWorkspacePage` for bounded side navigation, switching to a scrolling top row below 700dp. `DesktopRetainedPane` keeps subsection drafts composed while removing hidden content from placement, focus and semantics. Keep original requestLeave, busy, confirmation and controller callbacks. The Studio creation surface retains its own full-width preview/title/divider geometry; history organization must not wrap it in a second sidebar.

The retained management composition collects `DesktopModelSettingsController.observeModelChoices`; its explicit revision restarts the reader after an independent visible read failure. Publish selector summaries and saved-default diagnostics only, preserving settings/model editor drafts and ordinary busy/error state. Do not replace this with repeated loadSettings calls on model emissions.

`DesktopTaskbarPinEntry` scopes wrapping FlowRows to its shortcut/pin buttons and modal actions; it does not change global ActionRow behavior. The real Data workspace Scene asserts all three shortcut/pin entry bounds at compact widths without invoking native actions.

Workspace keeps selected items visible across the 700dp layout boundary. The wide sidebar uses `BringIntoViewRequester`; the narrow fixed-width row waits for placement and scrolls its original ScrollState using the same rounded width/gap/padding geometry, since a fully clipped tab's default request can be empty. Format and WorldBook editors keep header/footer outside a weighted scroll body; WorldBook entry dialogs have a separate original-save/cancel actions slot. Narrow tool/leave actions wrap; WorldBook header reserves a wrapping title slot and explicit action gaps. `DesktopCompactEditorLayoutTest` exercises actual controller commits, two-stage entry/entity saving, bilingual compact bounds and programmatic Data navigation with same-Scene resize; it does not verify native window chrome.

`DesktopWorkspaceNavigationItem` supplies equal-width, single-line accessible tabs: the 164dp sidebar fills each row; narrow workspace navigation scrolls horizontally. Settings groups reuse it in a wrapping FlowRow so later groups remain reachable. Production Studio has no empty Studio/diagnostics tabs: actual HTTP logs and the explicitly named logical Prompt inspector live in Data support diagnostics, with local return. Studio's divider stays inside the content frame. App-owned Swing diagnostics use `DesktopBrandResources.applyTo`; the ownership MessageBox keeps its native mechanism and gets a temporary thread-local icon hook from `DesktopNativeDialogBrand`.

Character, World Book and Format management share `DesktopManagementHeading` and `DesktopManagementToolbar`: search/New/Import share a row from 560dp; below that, the two actions share a second row. `DesktopManagementImportAction` retains existing admission/import callbacks; `DesktopManagementImportAndDrafts(showImport=false)` still renders recovery/progress/conflict state. Character edit/more live inside the summary row beside Start, matching World Book actions.

`DesktopChatUiPreferences` owns display-only people width/hidden/filter and single/multiple inline-status choices. The independent people column has count/settings in one row and one collapse/restore handle on its divider; double-click returns automatic width. `DesktopAutomaticChatColumns` applies hysteresis: reclaim people width, then close sessions, then people; automatic closures restore without changing manual preferences. Grow chat to 880dp before people names/status width and external navigation. Only the narrowest layout exposes the header quick list. 活跃 view retains enabled characters; 启用 view includes all characters, including disabled ones. Multiple inline statuses is a persisted secondary option under inline mode, default off; each open row closes only on its own click. Green counts require a valid resident status; pins and participation keep their separate owners.

Implement shadcn's open-code, semantic-token, variant-driven component model in native Compose. Preserve Android behavior and accessibility; do not imitate web markup or depend on Material 3 visuals.

## Required Reading

Read [references/shadcn-compose.md](references/shadcn-compose.md) before creating or changing UI kit components. Read relevant existing screen and ViewModel before migration.

## ChatBar Screen Quick Path

1. If feature location is unclear, use `chatbar-feature-map` first.
2. Read target `*Screen.kt` and paired `*ViewModel.kt` before broad search.
3. For cross-layer features, read relevant domain service/repository after screen state flow is clear.
4. Use focused `rg -n "term" path` searches. On Windows, avoid regex alternation, pipes, and command chaining.

## Workflow

1. Inventory screen behavior, states, controls, callbacks, insets, keyboard handling, scrolling, and accessibility semantics.
2. Map every control to an existing ChatBar UI Kit primitive. Add missing primitive before editing page code.
3. Define semantic tokens and component variants centrally. Never embed page-specific colors or duplicate component styling.
4. Build primitives from Compose Foundation/UI/runtime. Material icons may remain temporarily; Material 3 components, theme, defaults, and color scheme may not enter new code.
5. Compose screen from explicit anatomy: header, content groups, items/fields, actions, overlays. Keep state ownership unchanged.
6. Budget compact-height space before styling. Bound fixed chrome, reserve flexible height for primary detail/editor content, and move conditional maintenance/status clusters into a bounded scrollable dialog or sheet.
7. Preserve enabled, pressed, focused, selected, invalid, loading, destructive, and disabled states.
8. Compile after each migrated component or screen. Search for remaining Material 3 imports after each migration batch.
9. Remove Material 3 dependency only after imports and fully qualified usages reach zero.

## Fullscreen and IME Insets

- Settings use `SettingsBrowser.kt`: eight global categories, six session parameter categories, metadata-only search, explicit per-category scroll state, and bounded `SettingsDetails` editors. Keep stable entry IDs and `searchItems` for fields inside a detail group. `SettingsSwitch` provides a labeled 48dp target; credentials use non-saveable secure input state. Drafts remain outside category composition and rebase only untouched fields with `rememberSettingDraft`.

- Every screen must keep actionable content above gesture/navigation controls and IME. Apply navigation-bar and IME insets to scrolling or bottom-action region, including Android three-button navigation.
- `SettingsSwitch` owns input and accessibility on its 48dp wrapper. Its `CbSwitch` uses a null callback for visual-only rendering without a nested pointer handler; `enabled = false` alone still installs a handler and can block the wrapper's taps.
- `CbDialog` owns safe-drawing/IME padding, available-window height, and fixed title/action regions. Do not duplicate those insets in ordinary dialog content. Large dialog bodies must still provide their own bounded `LazyColumn` or `verticalScroll` container so the flexible body can scroll on 360×640dp screens.
- Fullscreen custom `Dialog` pages do not inherit `CbDialog` behavior; keep status-bar handling in `CbTopBar` and apply navigation-bar padding to the full-page content.
- Every multi-line or otherwise large text input must expose standard `CbField(onFullscreenEdit = ...)` entry and reuse `FullscreenTextEditor`; keep screen state source of truth.
- `CbInput` uses the state-based `TextFieldState` pipeline. Compatibility overloads must preserve text, selection, and IME composition; constrained fields filter through `InputTransformation`, not lossy `onValueChange` rewriting.
- `CbInput` and fullscreen overlays retain the `onTextLayout` result provider and read it in composition so snapshot changes refresh long-text annotations; do not freeze its result inside the callback.
- Numeric fields use `NumberInput.kt`: `CbNumberInput` for string-owned forms with existing submit validation, `CbNumberValueInput` for numeric models. Both allow empty intermediate drafts and numeric keyboards; value-backed fields publish only validated numbers, preserve numeric echoes without rewriting decimal/leading-zero text, and restore the last valid model value on blur if unfinished. Set decimal/signed and range validation per field; key repeated rows by entity identity.
- Compact fixed-width actions can opt into `CbButton(autoSizeText = true)`: primary/supporting text each stays on one line, shrinks to a 10sp floor, then ellipsizes. Keep full explanations in the associated options surface.
- `FullscreenTextEditor` owns an internal transient text draft. Dismiss/× discards it; confirm/√ commits once. Custom confirm callbacks receive the final `String` or `TextFieldValue`; use `canConfirm` when validity depends on the transient draft.
- Fullscreen entry/exit must preserve exact `TextFieldValue` selection and cursor, request focus, show the IME for immediate typing, keep status/navigation bars available, and apply status/navigation/IME insets without hiding phone controls.
- `FullscreenTextEditor` draws into its caller's Compose tree. A fullscreen settings `Dialog` should overlay it in the same root `Box`, keeping underlying tool drafts alive and disabling the covered browser's back handler. When an editor is instead hosted outside an ordinary `CbDialog`, stop composing that dialog while the editor is visible so its separate window does not cover the editor.
- Chat keeps its composer above IME and uses explicit bottom anchoring so an already-bottomed timeline rises with the keyboard; historical reading positions must not be forced to the bottom. Do not attach `imeNestedScroll` to the timeline because reaching its end must never open the keyboard.

## API Rules

- Character editor resource bindings use `CharacterResourcePicker` in `CharacterEditScreen.kt`: compact summary buttons open searchable, height-bounded lazy lists; format cards are single-select and world books multi-select. `CharacterEditViewModel` subscribes to both repository flows for live create/edit/delete updates. Missing bindings remain visible for explicit removal rather than silently rewriting the draft.

- `SegmentActionScope` supplies redundant scope cues in the floating menu: 本段 uses an Article icon and muted surface; 整条 uses Layers, accent surface and primary foreground. Delete stays destructive in both rows; scope styling must not alter callbacks or target IDs.
- `RoleplayMarkdownText` in `ChatBubble.kt` uses a separate Compose `Box.clearAndSetSemantics` to expose rendered Markdown text without color markers; native `AndroidView` is visual-only and excluded from accessibility. Keep the semantics owner separate from the interop layout node so clickable bubble parents can merge its text. Generic message `contentDescription` labels must not mask body text. `RoleplayStatusPanel` has no `SelectionContainer`: its parent owns tap-to-expand and long-press menus; collapsed body text is absent.
- `ChatScreen.kt` segment actions use `CbAnchoredActionPopup`, anchored by `ChatBubbleSegmentAction.anchorBounds` from the text surface's `boundsInWindow()`. It has no dim/title/footer, flips above/below the fragment, clamps to safe bounds, and closes on outside tap/back. Whole-message actions retain `CbDialog`. `SegmentActionRow` renders regeneration/format repair, 本段, 整条 with dividers, borderless icon/caption buttons, 48dp minimum targets, 4dp gaps and destructive foreground for deletion. Narrow rows scroll horizontally. `CbButton(icon = ...)` uses `text` for accessibility and `supportingText` for a visible caption; `contentColor` overrides foreground only.

- Root-tab swipes in `Navigation.kt` must pass a lambda capturing the current `rootRoutes` to `swipeToAdjacentTab`. A local callable reference can compare equal across recompositions and leave its `rememberUpdatedState` callback holding the startup route list before Moments/community settings load.

- Prefer `variant` and `size` enums over Boolean style flags.
- Prefer slot-based composition: `leading`, `trailing`, `content`, `actions`.
- Keep primitives small and open code. Pages may compose primitives, not restyle internals.
- Use paired semantic colors: surface/foreground, primary/onPrimary, muted/mutedForeground, destructive/onDestructive.
- Use one radius scale, spacing scale, control-height scale, and typography scale.
- Use visible focus treatment for keyboard/D-pad navigation.
- Use Android minimum touch target of 48dp even when visual control is smaller.
- Icon-only fixed actions require an unambiguous standard icon, `contentDescription`, enabled/loading semantics, and a 48dp touch target. Keep text when icon meaning is unclear.
- Use dialogs for focused decisions; sheets for mobile action lists and dense settings.
- Use `Field` anatomy for forms: label, control, description, error.

## Verification

For UI-visible changes, run from `app/`:

```powershell
.\gradlew.bat :app:compileDebugKotlin --rerun-tasks
powershell -ExecutionPolicy Bypass -File .\ci.ps1 -SkipAssemble
```

If an Android device is connected, use `chatbar-emulator-test` data-preserving install flow.

## Migration Gate

A screen is migrated only when:

- No `androidx.compose.material3` import or qualified use remains.
- Existing user actions and state transitions remain available.
- Primary detail/editor content retains usable height when every conditional warning, error, progress, or maintenance state is present.
- UI uses semantic tokens and UI Kit components.
- Loading, empty, error, disabled, and destructive states remain represented.
- Kotlin compile passes.

Full migration completes only when Material 3 dependency is removed and CI-equivalent verification passes.
