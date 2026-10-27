# R2 bounded diagnosis and narrow repair

Starting HEAD: f073749a6f57490516e7c2b1df516c5e6c53d13f (parent 7363c8e692bce23e2cddfa9564de34829c19f890).
All eight raw worktree SHA-256 values matched R1 pre-focused-source.json before diagnostic edits. Frozen patch SHA-256 b6147bef47293bd496b268e9ea8674814cae4c3f290ed35362f1311f55615429 matched.

## One diagnostic execution

01-bounded-diagnostic intentionally selected the existing five failing methods, preserving their assertions. Exit 1, 3 XML suites / 5 tests / 5 failures. This is a separately preserved reproduction, not a new classification of R1's 3/14 (9 PASS, 5 FAIL).

Synthetic semantics demonstrate distinct nodes: the outer Box has the complete action description but no Role, Disabled, OnClick or RequestFocus. Its child StudioAction has Role.Button, click/focus and the true enabled/disabled state, but the short name. The preview-target parent is a separate Role.RadioButton, not the action button. Raw IDs, parents, property names, descriptions/text and bounds are in diagnostic XML system-out. No user storage was read.

The initial native snapshot had measured content 560x437 but no WINDOW_OPENED child identity yet. Setting placement then was followed by Floating / AWT extendedState 0 / bounds 600x500 after the bounded wait. Thus measured content alone was an insufficient window-readiness condition; this run did not prove a displayed native maximize request failed. No second diagnostic attempt was made.

## Repair

StudioAction gains only accessibilityLabel defaulting to label; semantics use it on the existing clickable Row. FishAction removes the named Box and passes its full description to that Row. Visual text, styling, enabled/clickable/focus handlers remain unchanged. Shared control regression covers all five styles, default descriptions, selected CHIP, custom name with unchanged visual text, disabled pointer suppression, focus and Enter.

The native test observes the exact synthetic child's WINDOW_OPENED event through an AWT event listener. It never scans Window.getWindows or guesses an owner. Owner identity still comes from the public main WindowScope. It waits for displayable/showing child identity before maximize; no production window changes were required by R2.

The repaired method retains BOTH content-growth checks, adds actual AWT MAXIMIZED_BOTH, work-area bounds with Windows invisible-border allowance, exact restored window size, and retains composition count, parent enabled/identity and reopen/parent-close disposal assertions. The original timeout was not lengthened. On the repaired execution: 600x500 -> (-8,-8,1936x1056), extendedState 6; content 560x437 -> 1896x993; monitor 1920x1080, usable area 1920x1040, scale 1; restored 600x500 / state 0 / content 560x437. One composition instance and the same disabled owner throughout maximize/restore.

Raw geometry is an automated native fixture result, not user layout approval or audio hearing evidence. WIN-AUTO-001 remains OPEN. No capture driver, system settings, brand code, Fish transport/runtime/store, Prompt, schema or baseline changed. Real Fish GET 0 / TTS 0.
