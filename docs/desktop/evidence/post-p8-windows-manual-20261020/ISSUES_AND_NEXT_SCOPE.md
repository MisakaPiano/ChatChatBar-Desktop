# Observed issues and proposed next contracts

No fix implemented. This file records the manual failures and user refinements; it does not grant implementation or live-request authority.

## 1. Screenshot internal Close exits the app — USER FAIL

User explicitly identifies the screenshot interface's `关闭` text button, not Windows X/Alt+F4. Owned process trace records CLOSE_REQUESTED, complete drain and Launcher/JVM0/0. No exception/crash is established; exact event routing/root cause UNKNOWN. `DesktopChatScreenshotHost.kt` currently clears screenshot state in its text Close handler, while `Main.kt` owns application-close admission. A separate diagnosis should trace popup/owner/focus/close event identity without broad Main lifecycle changes, then test that screenshot Close/Escape/outside dismissal leaves app and data ownership active. Do not relabel the current event as normal user exit.

## 2. Repeated active-voice click — USER REPORTED FAIL

User reports one click restarts playback, another gets stuck. Later current UI was already silent/player closed/responsive; no reproduction or native-state telemetry. Review existing `DesktopPrimaryChatPanel` voice entry and `DesktopChatVoiceRuntime`/playback admission together. Define active/paused same-voice click semantics, then test rapid clicks, queue replacement, native handle ownership and maintenance admission. Existing position-preserving pause/resume and R1 maintenance safety must remain. Seek is an additional requested native/queue contract, not an implemented capability.

## 3. Single-row voice zones and compact voice bars

User requests left generation state/cancel, fixed reserved center player, right narrator selector in one row, same visual system and no overlap. Prefer complete role name; shrink only when needed to ellipsis, minimum label `旁白`; left status may ellipsize aggressively. Expanded options share width based on longest name, constrained to the host viewport. Preserve selected target and explicit TTS/auxiliary authorization. Replace inline expanding recording controls with one popup shared by right ellipsis and voice-bar right click. Voice bar keeps play, duration and animated activity; reserve an ellipsis-width slot, no extra vertical height. Whole-reply regeneration is requested separately from missing-only fill; history/replacement policy needs an explicit contract.

## 4. Message presentation

User footer should contain only Copy/Edit, pinned right; currently screenshots show extra left-positioned controls. Existing Assistant left/right groups remain separately scoped. First context-menu row should divide action area equally between Regenerate/Repair, centered even when disabled. Narrow popups must remain inside the main window; retain nonwrapping rows and reachable actions. User avatar is currently an intentional text fallback (User passes null reference); custom User-image configuration is not wired, so a missing-image resource fault is not proven.

## 5. Screenshot selection redesign and exports

Group by the corresponding User utterance/turn, with owned complete Assistant reply as one child content block. Do not title groups using the first AI sentence. Every complete reply independently expands/collapses; collapsed replies remain selectable. Top controls: expand all, collapse all, select all, deselect all, invert. Folding must not clear selections or change message/version/block IDs. This describes the screenshot-selection UI, not an implemented change to ordinary chat.

Redesign sparse layout and Preview/Save/Close affordances. User selected15 items manually, including voice rows; that preview is not evidence of automatic expansion or one-segment contamination. Existing independent voice-block selection semantics require explicit scope if defaults change. PNG remains; PDF and Word export requested as future additions, with separate rendering/resource/format requirements. Current gate produced no saved file and does not validate an export decoder.

## Unresolved validation

Controlled cross-User sequential playback, replacement feedback, generation cancel, message/version mutation, full dark/light wide/narrow screenshot/export matrix, live LLM/NovelAI/Fish smoke, persistence restart and real maintenance concurrency remain NOT TESTED in this run. Original B4 expected a narrator selector unconditionally; shared policy requires it only for relevant narrator targets, so that procedural expectation must be corrected without weakening policy. No automatic restart or source fix follows this evidence commit.
