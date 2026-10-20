# Deferred chat context-menu and voice presentation requirements

Date: 2026-10-20 JST. Status: USER REQUEST / PROJECT SCOPING PENDING. Frozen product unchanged; no implementation or new test PASS.

## User-requested interaction

- Replace current right-click actions with a compact icon-and-text popup. Left-click outside dismisses it, following ordinary context-menu lifecycle.
- Row 1, whole assistant reply: regenerate (unavailable for a non-last reply), format repair.
- Row 2, clicked/current segment: copy, edit, delete, screenshot, generate voice. User clarified that generate voice is one action, not two buttons.
- Row 3, whole containing reply: copy, edit, delete, screenshot, generate voice.
- Clearly distinguish a segment from its containing assistant reply. Right-click selection must not silently retarget to the last message.
- Move appropriate voice actions into this menu. Move generation status/actions currently between history and composer into a compact deck inside the bottom of the conversation area, shown during generation. Narrator selection becomes a dropdown.
- Playback uses a narrow floating control centered at the bottom of the conversation area: animated playing indicator and stop icon. First stop retains position and changes the indicator into a play/resume button; play resumes at that position. A second stop closes the control. A subsequent playback opens it again. No idle control before playback/generation. Exact post-generation idle visibility requires contract clarification.
- User requests automatic continuation through segments and across intervening user messages into the next assistant reply.

## Read-only source observations

- DesktopChatVoiceRuntime.play obtains placementsForMessage(current) and passes only that message's voices to coordinator.playFrom. Manual playback therefore has same-message sequencing, not traversal of subsequent replies across user turns.
- Coordinator pending generation autoplay can enqueue successful batches in message order. This is different from traversing existing conversation audio after a manual play click.
- DesktopVoicePlayback.stop cancels its job, clears queue and state, and drains native output. Current API has no pause/resume position contract. Requested resumable floating controls need a separately reviewed playback contract; they are not a presentation-only change.
- Desktop source search found no chat screenshot implementation. CURRENT Roadmap / Feature Parity contain no explicit chat screenshot delivery item found by this review. Screenshot evidence infrastructure and historical Android ChatLongScreenshotRenderTest debt are not a Desktop screenshot feature. Register an independent Desktop segment/whole-reply screenshot scope candidate; do not claim all historical plans were exhaustively absent.

## Existing UX observations retained

- Session-settings-saved and recording-deleted notices persist through later operations and remain after dismissing batch status. User prefers dismissible popup feedback.
- Replacement completion feedback was not easy to find, leading to additional explicit user actions.
- Generation controls at the bottom of each assistant reply make ownership unclear. Current implementation binds actions to that reply's message identity, not universally to the last reply.

## Scope / evidence limits

No frozen source, runtime, package, CURRENT, parity or baseline changed. No GUI restart, Robot, JUnit, bounded or real API request performed for this note. W5 persistence reopening was subsequently explicitly authorized and completed; see README and raw restart evidence.
Reference images supplied in the conversation are Android CCB interaction references, not Desktop acceptance evidence. No content from those screenshots is a product instruction beyond the user's accompanying request.

## User clarification / additional rule

Generate voice is a single action. Generation Deck must remain strictly one line. When audiobook mode is enabled, show the official CCB label 角色音色 immediately left of its dropdown, both in a fixed area on the right. Hide this persistent group when audiobook mode is disabled; other necessary character choices follow official policy through on-demand selection. Deferred only: W5 does not alter UI.
