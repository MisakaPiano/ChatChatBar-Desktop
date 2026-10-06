# 43 — Phase 7 R2 Consolidated Manual Acceptance

This checklist is for the user after Project R2 diff review.

No real NovelAI generation is required.

Acceptance artifact is pending the additive re-audit `47`. The `b88d408` package predates that amendment and must not be used as final acceptance evidence. Use the final source/package identified in `44_PHASE7_R2_REVIEW.md` after all re-audit GAPs close; all manual checkboxes below remain pending.

Use local images and existing/fake results. Do not send any real NovelAI generation request, including regeneration, guidance or automatic-chat handoff; leave automatic generation off before sending an actual chat message. Phase live count remains 1/8 and R2 authorizes zero additional requests.

## A. Chat

- [ ] Image attachment icon is integrated into the composer.
- [ ] Pending image thumbnail is visible in/adjacent to composer.
- [ ] Pending image can be removed directly.
- [ ] Repeated picks add multiple images; expanded composer shows the same list and supports independent removal.
- [ ] Editing an existing message shows its images and supports add/remove; Cancel leaves the original unchanged.
- [ ] Text+image and image-only send are understandable.
- [ ] Automatic chat image control/status is discoverable near chat.
- [ ] Opening the chat image control shows relevant model/settings shortcut without dominating the chat UI.
- [ ] Assistant-message manual image-generation/regeneration action is distinguishable from image-understanding behavior.
- [ ] Secondary 生图要求 clearly separates one-run image content from the session preference; Cancel does not save. Do not press real Generate.
- [ ] Fake/local task progress, Stop and terminal state appear beside the source Assistant reply.
- [ ] Deleting one image preserves other images and the source reply; deleting the last image of an otherwise empty derived message removes that message only.
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
- [ ] Direct 图像引导 is visible in the Studio toolbar, alongside image-oriented Use-as routes.
- [ ] Account refresh/error status, Anlas and approximate V5 allowance are readable; failed account lookup does not claim free eligibility.
- [ ] Continuous-generation controls are discoverable without starting generation.
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
- [ ] Reverse stage, streamed content and optional reasoning are distinct; Stop/retry/candidate/Apply are understandable using fake/local evidence.
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

## I. History re-audit additions

- [ ] Positive-Prompt search and year/month/day filtering are discoverable and clearable.
- [ ] Folding/albums can be opened and left, and fold preferences survive reopening.
- [ ] Image detail exposes actual seed, model, dimensions, settings, positive/negative/character prompts and guidance.
- [ ] Full / New Seed / Seed Only and Use-as Guidance are discoverable without generating.
- [ ] Missing original guidance prompts for explicit degraded reuse before changing Studio; Cancel leaves the draft unchanged.
- [ ] Selecting/deleting specific history images preserves unselected images and still-used guidance copies.

## Acceptance rule

Phase 7 R2 manual acceptance is PASS only when the user can discover and use the above flows without needing Project/Codex to explain hidden entry points.

If a function technically exists but the user cannot reasonably find it, treat it as a UX defect, not a PASS.
