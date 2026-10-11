# Phase 7 outstanding image-window UX issues

The following three issues were originally **OPEN — USER REPORTED / MANUAL REPRODUCTION PENDING**. Their original description and acceptance criteria are retained for audit. On 2026-10-11, the user explicitly confirmed **WIN-01/02/03: 3/3 Windows PASS** on source `50a7caf573827b402ddc16622941c2f18fcd3e12`. Their latest state is **USER ACCEPTED / FROZEN / NOT MERGED TO DESKTOP**, controlled by `78_PHASE7_WIN_ACCEPTANCE.md`. The user did not supply per-window display metrics; do not invent them. Section Q alone is signed in `66_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md`; all other unconfirmed Phase-7 items stay OPEN.

Read-only code check: `DesktopImageToolWindow.kt` declares `resizable = true` and is used by Studio auxiliary surfaces, Viewer, History detail and Image Tools. `DesktopNovelAiStudioPanel.kt` also has separate `DialogWindow` entries. This declaration alone does not prove usable maximize/restore, correct icon branding, or adequate initial size for any specific window. The other, separately listed Viewer and History checks in `66` retain their own historical/unsigned status; acceptance of WIN-01/02/03 is recorded in section Q.

## P7-WIN-01 — Maximize / Restore

**Status: USER ACCEPTED / FROZEN (2026-10-11; `78`).** Original report: some popups lacked usable maximize behavior.

- Studio AI Design, History, Viewer and Image Tools large auxiliary windows must support normal Windows maximize and restore.
- Maximized content must use the additional space; restored layout must remain usable.
- Close/Esc, focus and window lifecycle must not regress.
- Small confirmation dialogs need not be forced to maximize.

Acceptance links: `66` Viewer and History checks plus the independent window-UX section below. Confirm affected windows individually; do not infer PASS from `resizable = true`.

## P7-WIN-02 — CCB Window Icon / Branding

**Status: USER ACCEPTED / FROZEN (2026-10-11; `78`).** Original report: auxiliary popups lacked the CCB application icon.

- Applicable Desktop image auxiliary windows must use the same CCB app icon as the main window in Windows title bars and taskbar presentation.
- No missing/default Java icon or obvious mismatch with the main window.
- Reuse the repository's official brand asset; do not create a second logo.

This is Phase-7 image-window UX. It does not advance P15 installer, file association, Open With, URI scheme or Explorer shell registration ownership from `64_PHASE7_SCOPE_OWNER_UPDATE.md`.

## P7-WIN-03 — Initial Window Size / Content Visibility

**Status: USER ACCEPTED / FROZEN (2026-10-11; `78`).** Original report: some popups opened too small to show their main information and controls.

- Choose task-appropriate initial width, height and layout so primary content and common Save/Cancel/Apply/Close actions are directly visible.
- Longer content may scroll; long lists need not fit without scrolling. Resizing must not clip or make actions unreachable.
- Manually check common Windows display scaling and actual desktop resolutions; capture the specific affected window and environment rather than assigning one defect to all windows.

## Ownership and closure

All three issues belong to **Phase 7 image-workspace and auxiliary-window UX**. WIN-01 relates to Viewer, History and the shared auxiliary window; WIN-02 is a distinct image-window shell branding check; WIN-03 may be split by confirmed affected window. These issues received independent delivery and explicit user manual acceptance in `78`, after the D slice. Their original scope does not imply that all broader image-window conditions were signed. Do not force them into Slice B. Global information architecture outside image management remains with P17.

`62_PHASE7_IMAGE_PRODUCT_CLOSURE.md` §§10–13 and `48_PHASE7_UX_PRESENTATION_CLOSURE.md` “Auxiliary surfaces” define the existing UX boundary. `69_PHASE7_SLICE_A_ACCEPTANCE.md` remains **ACCEPTED / FROZEN** and is not reopened by these issues. **Phase 7 remains NOT ACCEPTED / NOT MERGED**; all three specific WIN items have since been manually accepted and frozen, without signing the whole Phase-7-wide checklist.
