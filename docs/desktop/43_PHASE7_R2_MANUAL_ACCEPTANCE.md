# 43 — Phase 7 R2 Consolidated Manual Acceptance

This checklist is for the user after Project R2 diff review.

No real NovelAI generation is required.

## A. Chat

- [ ] Image attachment icon is integrated into the composer.
- [ ] Pending image thumbnail is visible in/adjacent to composer.
- [ ] Pending image can be removed directly.
- [ ] Text+image and image-only send are understandable.
- [ ] Automatic chat image control/status is discoverable near chat.
- [ ] Opening the chat image control shows relevant model/settings shortcut without dominating the chat UI.
- [ ] Assistant-message manual image-generation/regeneration action is distinguishable from image-understanding behavior.
- [ ] No large inactive image feature block wastes permanent chat space.

## B. Background

- [ ] A chat-side Background entry is easy to find.
- [ ] Effective source is understandable.
- [ ] Session background can be set and cleared.
- [ ] Opacity can be reached from chat without hunting through Management.
- [ ] Multiple Desktop character backgrounds can be stored.
- [ ] One Desktop preferred background can be selected.
- [ ] Clearing Desktop preference falls back to official `CharacterCard.chatBackground`.
- [ ] Android/cross-platform Character background is not modified by selecting Desktop preference.
- [ ] Restart preserves Desktop background library/preference.

## C. Character → Chat

- [ ] New Chat character chooser shows avatar + full name + useful summary rather than name-only buttons.
- [ ] Search works.
- [ ] Starting a chat from chooser creates/selects the correct session.
- [ ] Management → Characters shows Start Chat.
- [ ] Start Chat navigates to the chat and does not open the editor.

## D. Studio main workspace

- [ ] Studio feels like one coherent workspace rather than six unrelated pages.
- [ ] Prompt fields and character Prompt cards are easy to scan.
- [ ] Generation settings are accessible without leaving the Studio mental context.
- [ ] Current result/preview is visible without hunting through a separate page.
- [ ] Generate/Stop and token/cost state are persistent/obvious.
- [ ] Model / size / ratio / count / steps / CFG / sampler / seed controls are usable.
- [ ] Resize/minimize/narrow-window behavior remains usable.

## E. AI Design

- [ ] AI Design opens from Studio toolbar.
- [ ] New conversation empty state is clear.
- [ ] History is available from AI Design.
- [ ] Settings are available from AI Design.
- [ ] Current design model is visible.
- [ ] Attach-current-Studio-Prompt control is clear.
- [ ] Attachment summary shows base + character count.
- [ ] Remove attachment works.
- [ ] Apply result back to Studio is clear.
- [ ] Retry/regenerate/edit-branch are understandable.

## F. Imported image / Guidance / tools

- [ ] Importing/opening an image brings up a clear current-image tool surface.
- [ ] Metadata action is discoverable.
- [ ] Mosaic is discoverable.
- [ ] Reverse Prompt is discoverable.
- [ ] Rotation/privacy/save/copy/reveal are discoverable as applicable.
- [ ] img2img / precise reference / Vibe / inpaint are reachable from image-oriented actions.
- [ ] Focus/mask editor is understandable.
- [ ] Undo/redo/reset work in visual order.
- [ ] Cancel does not mutate the original.
- [ ] No real Generate is pressed.

## G. CCB PNG

- [ ] Crop/position is drag-first.
- [ ] Wheel zoom and zoom slider work.
- [ ] Ordinary X/Y center sliders are not cluttering the default UI.
- [ ] Mosaic works on export working copy.
- [ ] Solid-color cover-up works on export working copy.
- [ ] Undo/redo/reset work.
- [ ] Export succeeds.
- [ ] Character background is unchanged after replacement-cover export.

## H. Persistence

- [ ] Close normally.
- [ ] Reopen.
- [ ] Studio draft remains.
- [ ] Desktop background library/preference remains.
- [ ] Session settings remain.
- [ ] Imported/owned assets referenced by saved state remain.
- [ ] No launcher error or startup warning.

## Acceptance rule

Phase 7 R2 manual acceptance is PASS only when the user can discover and use the above flows without needing Project/Codex to explain hidden entry points.

If a function technically exists but the user cannot reasonably find it, treat it as a UX defect, not a PASS.
