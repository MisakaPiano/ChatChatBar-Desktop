---
name: chatbar-desktop-context-help
description: Maintain Desktop current-page help, compact title entry and its modal/chrome boundaries.
---

# Desktop Current-page Help

- `DesktopContextHelp.kt` maps each actual primary route to localized `DesktopUiStrings` instructions. It is text-only: no feature-owner getters, navigation shortcuts, generated content or maintenance actions. World-book editor help remains its existing separate entry; first-use tutorial and unified recovery guidance are not supplied by this panel.
- `DesktopPrimaryShell` captures the current route on open, blocks help under root/backup/restart locks and existing modals, and reports it as GENERAL modal activity. Help opening/closing never reloads editors or changes navigation, filters, drafts, candidates or work. Task-center and startup notices retain their existing deferred handling.
- `DesktopTitleBar` provides the small question-mark entry and records its actual bounds in `DesktopChromeLayoutRecorder.interactive`. Route tabs alone can scroll at the smallest native width; selected tabs reveal on selection/viewport changes, retaining fixed help/tasks/native captions and all enabled feature routes. Disposal clears stale hit rectangles.
- `DesktopContextHelpTest` exercises localized route headings, close geometry, 330/420/720/1100 title bounds and client/native hit contracts. Cloud captures use `CCB_CONTEXT_HELP_REVIEW_DIR`. `DesktopB1CIntegrationTest` exercises real Shell open/close/reopen and modal priority while preserving repository bytes, dirty editor/composer and request/work baselines.
- Offscreen reveal-animation checks must advance the scene's frame timestamp (`render(System.nanoTime())`); default render time is zero. Preserve the same visibility deadline and actual bounds assertions. Native drag tests find measured blank intervals between controls, since fixed help/tasks may touch caption buttons; their shared boundary is not a drag region.
