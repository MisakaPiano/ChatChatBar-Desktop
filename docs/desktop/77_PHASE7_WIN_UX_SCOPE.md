# Phase 7 — Auxiliary Image Window UX (P7-WIN-01/02/03)

**SCOPE / IMPLEMENTATION PENDING / USER ACCEPTANCE PENDING / NOT MERGED TO DESKTOP**

## Source identity and ownership

- Scope branch: `feature/phase7-win-auxiliary-ux`.
- Parent is the accepted D docs-only HEAD: `ae9a0044be1b3bface7dc2933c13dae7337d9c44`.
- D frozen production SHA: `56866d96f55aa45c7cb1502c63ed1fb07f29bf17`. D-01–07 user Windows acceptance is recorded in `76_PHASE7_D_ACCEPTANCE.md`; **do not reopen it**.
- Formal validated upstream baseline remains 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`. No upstream sync or baseline promotion in this slice.
- Authority: `70_PHASE7_OUTSTANDING_UX_ISSUES.md` (P7-WIN issues), `66_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md` (unsigned section Q), `62_PHASE7_IMAGE_PRODUCT_CLOSURE.md` (§10 unified viewer), current Desktop source and official baseline `.agents/skills/chatbar-image-generation-runtime/SKILL.md`.

## Scope and evidence status

| Item | Required user-visible behavior | Current evidence and decision |
| --- | --- | --- |
| P7-WIN-01 — Maximize / restore | Large image auxiliary windows (AI Design, History, Viewer, Image Tools and any confirmed analogous surface) can maximize, use available space, restore, close/Esc and retain focus/lifecycle | **OPEN**. Existing `resizable = true` does not establish actual usability. Record failing windows individually; do not assert all are broken or delete a working control |
| P7-WIN-02 — CCB title/taskbar icon | Applicable image auxiliary windows use the same official CCB logo as the main window, without the default Java icon | **OPEN; SOURCE GAP CONFIRMED**. `Main.kt` explicitly passes `painterResource(DesktopBrandResources.LOGO_RESOURCE)`, while `DesktopImageToolWindow.kt` calls `Window` without an `icon` argument. Inspect applicable large `DialogWindow` surfaces separately |
| P7-WIN-03 — Initial size/content visibility | Task-appropriate window initial bounds fit the usable monitor area; primary content and routine Save/Cancel/Apply/Close actions remain reachable; long content scrolls rather than clipping | **OPEN**. Several callers request fixed 760–820dp heights/large widths; actual failures and affected controls must be verified on Windows rather than presumed from those numbers |

The user's previously reported absent CCB logo belongs to **P7-WIN-02**, not P7-D and not P15 Windows shell/file associations.

## Narrow source audit / likely entry points

- `Main.kt`: the main `Window` already uses `DesktopBrandResources.LOGO_RESOURCE`; reuse this branded resource rather than adding a second logo.
- `DesktopImageToolWindow.kt`: shared `Window` for Viewer, Studio AI Design, History, Image Tools, image guidance and history recipe detail. Add correct branded icon at this common owner. Audit window state, maximize and reasonable initial sizing without changing its existing onClose, onActivate, keyboard or ownership semantics.
- `DesktopNovelAiStudioPanel.kt`: `DesktopImageToolWindow` for large surfaces; `DialogWindow` for missing-guidance confirmation, metadata import and searchable choices. Small confirmations do **not** require maximize. Preserve all modal, data and navigation behavior.
- `DesktopChatBackgroundPanel.kt`: `DialogWindow` for background library; keep its existing library-to-Viewer behavior and data/selection semantics.
- `DesktopChatRegenerationDialog.kt`: larger `DialogWindow` (currently nominal 840×820dp).
- `DesktopCoverExportDialog.kt`: larger `DialogWindow` (currently nominal 900×820dp).
- `DesktopImageToolsDialog.kt`, `DesktopImageViewer.kt`, `DesktopStudioHistory.kt`: use the shared image window; preserve image interactions and current source/collection navigation.
- Installer icon `brand/ccb.ico` and the main-window icon are existing authorities. Do not replace packaging assets or implement P15 shell integration.

The list above is an **entry-point audit**, not a claim that every window visibly fails every WIN issue.

## Implementation rules

1. Apply the official branded image icon consistently to applicable image auxiliary top-level windows and meaningful large image dialogs. Do not globally rewrite unrelated application's dialogs or invent another brand asset.
2. Investigate WIN-01 and WIN-03 as a grouped, window-specific UX pass. Correct concrete defects where source/reproduction gives adequate evidence; otherwise retain OPEN and report that item's manual reproduction requirement. Do not convert unknowns to PASS.
3. A larger popup may need a responsive body, scrolling and reachable action row; maximizing or setting `resizable = true` alone is not sufficient. Never impose maximize on tiny confirmations.
4. Do not change whether a window is modal/modeless unless a specific independently identified defect requires it and Project reviews the behavioral change.
5. Do not alter accepted D Viewer navigation, B1 image context actions, B2 task Stop/ownership, C Studio preview/chrome, image decoding/animation, resource storage, Prompt, Package, Entity or API request semantics.
6. Keep changes to Desktop image-window presentation/adapters and focused tests. Do not touch upstream Android or perform unrelated reformat/refactors.
7. Use JDK 17; compile, deterministic non-Native tests and `git diff --check`. **Do not run foreground-dependent Native Robot/HWND GUI suites to prove UX**; their historic focus/Chrome obstruction is recorded separately. Retain existing tests unmodified and don't claim strict full PASS if not run/all green.
8. After Project reviews the actual implementation diff, build a fresh exact-SHA Windows package with complete file hash verification. **User performs the visible GUI acceptance**.

## Proposed user Windows acceptance matrix

Use the same accepted D app data/test-image workflow; avoid real NovelAI requests.

| Window | WIN-01 maximize/restore | WIN-02 icon title/taskbar | WIN-03 initial usable content |
| --- | --- | --- | --- |
| Studio → AI Design | User verify | User verify | User verify |
| Studio → History | User verify | User verify | User verify |
| Unified Viewer | User verify | User verify | User verify |
| Image Tools / guidance workspace | User verify | User verify | User verify |
| Background library | Per actual role (small/large dialog) | User verify | User verify |
| Image Regeneration Prompt | User verify if sizeable | User verify | User verify |
| Cover export | User verify if sizeable | User verify | User verify |

Capture the affected window name, display resolution/scaling, expected and actual result for each FAIL. One window's PASS does not close another. Untested is not PASS; do not force extra NovelAI generations to obtain images.

## Gate and handoff

- This scope document is Project-authored and **does not authorize a generic rewrite** of all Desktop windows.
- A future Codex task should implement the confirmed WIN-02 branding gap and only evidence-supported WIN-01/03 corrections in one bounded slice; Project reviews the committed GitHub diff.
- Non-Native programmatic tests/compile and exact-source package hashes are Codex responsibilities. User signs visible Windows behavior.
- All P7-WIN issues remain OPEN until a subsequent acceptance record explicitly closes them.
- `66_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md` and `70_PHASE7_OUTSTANDING_UX_ISSUES.md` are unchanged during this scope-only checkpoint.
- Phase 7 remains **IN PROGRESS / NOT ACCEPTED / NOT MERGED TO DESKTOP**. No merge or release is authorized by writing this scope.
