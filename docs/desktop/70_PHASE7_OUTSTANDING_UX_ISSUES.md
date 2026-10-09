# Phase 7 outstanding image-window UX issues

The following three issues are **OPEN — USER REPORTED / MANUAL REPRODUCTION PENDING**. They register user feedback for Phase 7 image-workspace auxiliary windows; they do not assert that every window has the same defect. Record each affected window, display resolution/scaling, expected and actual behavior, and screenshots where useful during later reproduction. The unchecked acceptance items are in `66_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md`.

Read-only code check: `DesktopImageToolWindow.kt` declares `resizable = true` and is used by Studio auxiliary surfaces, Viewer, History detail and Image Tools. `DesktopNovelAiStudioPanel.kt` also has separate `DialogWindow` entries. This declaration alone does not prove usable maximize/restore, correct icon branding, or adequate initial size for any specific window. The existing Viewer and History checks in `66` remain pending.

## P7-WIN-01 — Maximize / Restore

**Status: OPEN — USER REPORTED / MANUAL REPRODUCTION PENDING.** Some popups reportedly lack usable maximize behavior.

- Studio AI Design, History, Viewer and Image Tools large auxiliary windows must support normal Windows maximize and restore.
- Maximized content must use the additional space; restored layout must remain usable.
- Close/Esc, focus and window lifecycle must not regress.
- Small confirmation dialogs need not be forced to maximize.

Acceptance links: `66` Viewer and History checks plus the independent window-UX section below. Confirm affected windows individually; do not infer PASS from `resizable = true`.

## P7-WIN-02 — CCB Window Icon / Branding

**Status: OPEN — USER REPORTED / MANUAL REPRODUCTION PENDING.** Auxiliary popups reportedly lack the CCB application icon.

- Applicable Desktop image auxiliary windows must use the same CCB app icon as the main window in Windows title bars and taskbar presentation.
- No missing/default Java icon or obvious mismatch with the main window.
- Reuse the repository's official brand asset; do not create a second logo.

This is Phase-7 image-window UX. It does not advance P15 installer, file association, Open With, URI scheme or Explorer shell registration ownership from `64_PHASE7_SCOPE_OWNER_UPDATE.md`.

## P7-WIN-03 — Initial Window Size / Content Visibility

**Status: OPEN — USER REPORTED / MANUAL REPRODUCTION PENDING.** Some popups reportedly open too small to show their main information and controls.

- Choose task-appropriate initial width, height and layout so primary content and common Save/Cancel/Apply/Close actions are directly visible.
- Longer content may scroll; long lists need not fit without scrolling. Resizing must not clip or make actions unreachable.
- Manually check common Windows display scaling and actual desktop resolutions; capture the specific affected window and environment rather than assigning one defect to all windows.

## Ownership and closure

All three issues belong to **Phase 7 image-workspace and auxiliary-window UX**. WIN-01 relates to Viewer, History and the shared auxiliary window; WIN-02 is a distinct image-window shell branding check; WIN-03 may be split by confirmed affected window. They need explicit delivery and manual acceptance, whether addressed with a future C/D slice or a focused window-UX slice. Do not force them into Slice B. Global information architecture outside image management remains with P17.

`62_PHASE7_IMAGE_PRODUCT_CLOSURE.md` §§10–13 and `48_PHASE7_UX_PRESENTATION_CLOSURE.md` “Auxiliary surfaces” define the existing UX boundary. `69_PHASE7_SLICE_A_ACCEPTANCE.md` remains **ACCEPTED / FROZEN** and is not reopened by these issues. **Phase 7 remains NOT ACCEPTED / NOT MERGED**; no item here has been manually passed.
