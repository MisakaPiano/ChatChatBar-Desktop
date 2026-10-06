# 39 — Phase 7 R2 CCB Structure Map

Formal baseline for the current Desktop compatibility claim:

`CCB 1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`

This map records **feature/UI ownership**, not pixel-perfect Android layout.

## 1. Studio root follows CCB `ImagePromptToolScreen`

Upstream:

`app/app/src/main/java/com/example/chatbar/ui/imageprompt/ImagePromptToolScreen.kt`

This is the controlling Studio shell.

Important structural evidence in the baseline source:

- It owns the main Prompt workspace and generation controls.
- It receives `onOpenHistory` and `onOpenAiDesign`.
- Imported image state is handled inside the Studio workflow (`showImageTools`).
- Generation options are a subordinate UI state (`showGenerationOptions`), not a peer application route.
- Guidance editor is a subordinate UI state (`showGuidanceEditor`), not a peer Studio page.
- Metadata selection, imported-image preview, "use as" actions and tag editing are all subordinate workflows of this screen.
- Prompt editing, generation state and recent image/history context belong to this same product surface.

### Desktop consequence

Do not model Studio as six peer pages:

`Prompt | 参数 | AI设计 | 图像引导 | 结果 | 历史`

Instead, Desktop should retain one Studio workspace whose subordinate actions open dialogs/drawers/panes.

A wide-screen Desktop implementation may use:
- primary Prompt/settings column,
- result/preview side pane,
- toolbar actions,
- fixed generation footer,
- drawer/modal for AI Design / History / Settings / imported-image tools.

That is an EQUIVALENT layout of the same CCB structure.

---

## 2. AI Design follows CCB `NovelAiDesignScreen`

Upstream:

`app/app/src/main/java/com/example/chatbar/ui/imageprompt/NovelAiDesignScreen.kt`
`app/app/src/main/java/com/example/chatbar/ui/imageprompt/NovelAiDesignViewModel.kt`

The baseline structure is a dedicated conversational design workflow.

Key ownership:

- new design conversation;
- design history;
- design settings;
- message/turn history;
- retry / regenerate / edit-and-branch;
- explicit apply to Studio;
- optional attachment of current Studio positive Prompt;
- the attachment has visible summary such as "base + N characters";
- design model / natural-language mode / extra requirement live in the design settings surface.

### Desktop consequence

AI Design should open from the Studio toolbar as a dedicated conversation panel/window/drawer.

Do not flatten all conversations and settings directly into the main Prompt page.

Do not invent another Prompt Designer protocol.

Reuse the existing shared `NovelAiDesignConversationRepository`, `NovelAiDesignTurnRunner`,
`NovelAiPromptDesigner` and model routing.

---

## 3. Generation History follows CCB `NovelAiHistoryScreen`

Upstream:

`app/app/src/main/java/com/example/chatbar/ui/imageprompt/NovelAiHistoryScreen.kt`
`app/app/src/main/java/com/example/chatbar/ui/imageprompt/NovelAiHistoryViewModel.kt`

History is a dedicated browsing/reuse surface, not merely another text section in the Prompt editor.

Desktop may use a side drawer or a dedicated overlay, but preserve:
- history identity,
- selection,
- reuse modes,
- deletion semantics,
- metadata/recipe ownership.

---

## 4. Guidance follows CCB `NovelAiImageGuidanceEditor`

Upstream:

`app/app/src/main/java/com/example/chatbar/ui/imageprompt/NovelAiImageGuidanceEditor.kt`

Guidance is entered from an image-oriented workflow.

The Studio root owns guidance selection/staging and opens a dedicated editor.

### Desktop consequence

Users should encounter Guidance from the current/imported image workflow, not need to discover a peer page named "图像引导".

Keep the shared official types and policies:
- `NovelAiImageUseTarget`
- img2img
- precise reference
- Vibe
- inpaint / focused inpaint
- mask/focus state
- V4.5/V5 compatibility policy

Only the Desktop presentation changes.

---

## 5. Imported-image action structure follows CCB `NovelAiImageAction`

Upstream:

`app/app/src/main/java/com/example/chatbar/ui/imageprompt/NovelAiImageAction.kt`

The CCB workflow presents actions around the current imported/generated image.

The attached screenshots additionally demonstrate:
- metadata action,
- mosaic action,
- reverse Prompt action,
- enhance/enlarge action where the upstream screen exposes it,
- remove current image,
- image editing / mask workflow,
- visible reverse-Prompt progress.

### Desktop consequence

Desktop should have a clear **Current Image / Image Tools** surface.

Do not require users to understand internal categories like "Guidance page" before they can use an imported image.

If a screenshot shows a control whose runtime is not part of the formal baseline implementation currently shared to Desktop, do not fake it. Keep it disabled/omitted with explicit scope evidence rather than inventing behavior.

---

## 6. Chat image attachment follows CCB `ChatScreen` / `ChatComposer`

Upstream:

`app/app/src/main/java/com/example/chatbar/ui/chat/ChatScreen.kt`

Baseline structure:

- selected image strip belongs with the composer;
- the composer receives an `onImage` action;
- image selection is part of composing a chat message;
- generation actions are attached to eligible Assistant messages;
- generated-image task state is shown near the relevant message;
- long-press / image actions are message/image actions.

### Desktop consequence

The Desktop "添加图片" control must be integrated into the chat composer.

A standalone block above/below the composer is not an acceptable final user surface.

Desktop-native equivalence:
- attachment/icon button inside the composer toolbar;
- pending thumbnails inside/adjacent to the composer;
- remove pending attachment directly from the thumbnail;
- send text+image or image-only through the same composer.

---

## 7. Automatic chat images follow chat completion ownership

Do not revive legacy `AUTOMATIC_CHAT_IMAGE_JUDGE_*` execution.

Keep the already reviewed shared:
- `AutomaticChatImagePolicy`;
- persisted completion evidence;
- refusal / transport / truncation / Stop gates;
- source-message equality recheck;
- explicit opt-in.

### Desktop consequence

The user-facing entry must still be discoverable:
- quick automatic-image toggle/status near chat composer or chat image menu;
- detailed per-session settings may remain in session settings;
- automatic generation progress/result should attach to the relevant chat/task context.

The runtime is already correct. R2 repairs the cockpit.

---

## 8. Character selection should share one Desktop presentation model

This is a Desktop UX consolidation, not a new upstream domain.

Current Desktop already has:
`DesktopCharacterManagementPresentation`

with avatar/name/count information.

### Desktop consequence

New-chat character selection should reuse the same summary-card presentation instead of plain name-only buttons.

Management character rows should expose **Start Chat** using the existing session creation authority and then navigate to Chat.

Do not put session business logic into a second management-only implementation.

---

## 9. Background semantics remain upstream-first

Authoritative cross-platform fields remain:
- `CharacterCard.chatBackground`
- `ChatSession.chatBackground`
- `AppSettings.backgroundOpacity`

R2 must improve discoverability without changing their meaning.

### Approved Desktop enhancement

Add a Desktop-private per-character background asset library and preferred Desktop background.

This is additive and must not alter the upstream CharacterCard/Package schema.

Suggested rendering precedence:

1. `ChatSession.chatBackground` (existing formal session override)
2. Desktop-private preferred character background
3. `CharacterCard.chatBackground` (official cross-platform fallback)

Requirements:
- multiple Desktop background assets per CharacterCard;
- one optional preferred Desktop background;
- root-relative owned resource paths;
- restart persistence;
- backup/data-root participation;
- exact-candidate cleanup only;
- missing/corrupt Desktop preference falls back safely;
- exporting/syncing the CharacterCard does not silently replace `CharacterCard.chatBackground`.

This enhancement should be documented as Desktop-private ownership at Phase close.

---

## 10. CCB PNG remains the official export workflow

The core P7 PNG behavior already passed manual acceptance.

R2 only refines presentation:
- direct drag/pan remains primary positioning;
- wheel zoom + zoom slider;
- do not expose ordinary X/Y center sliders unless an accessibility fallback is needed;
- mosaic and solid-color cover-up tools operate on an export working copy;
- undo/redo/reset;
- no mutation of `CharacterCard.chatBackground`;
- no fake Package field.
