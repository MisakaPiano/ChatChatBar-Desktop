# Phase 7 — Exact-source Final One-session Windows Package

**CODEX PACKAGE READY 210/210 SHA-256 / PROJECT CODE REVIEW PASS / 18-GROUP USER WINDOWS ACCEPTANCE PENDING**

## Source authority

- Production commit `698f0cd55e0ae370650fac012cdb50f0dd7e033f` (Studio Diagnostics editor-column alignment). Project independently accepted its one-file +5/-1 production and one focused test +29 diff. No new code changes afterward.
- Packaging checkpoint docs-only HEAD `9803cae139341e0b8ea9749c729e9e97dc3c1ca2` had only four `docs/desktop/*.md` changes relative to production; Project verified this on GitHub. Further closeout docs are also not new production binaries.
- Formal validated upstream remains 1.4.1; parked 1.4.4 sync stays unmerged.

## Codex local package evidence (user-provided report)

- Temurin JDK17 `:desktopApp:createDistributable --rerun-tasks`: **PASS**.
- Manifest: **210/210 SHA-256 matched; zero missing/extra/mismatched**.
- Windows EXE: `H:\ChatChatBar-Desktop\app\desktopApp\build\phase7-final-one-session-distribution\ChatChatBarDesktop.exe`.
- Local gate: `H:\ChatChatBar-Desktop\app\desktopApp\build\phase7-final-one-session-package-gate\gate-summary.json`; manifest `distribution-manifest.csv` in the same directory.
- EXE SHA-256: `E5D45E5389E7584B9D8477EFE34543DE1069E6550560A3A3C53D164152958B8F`.
- Desktop jar: `desktopApp-b4cbbf902ef776455bd23264e37e3975.jar`, SHA-256 `889119EDE7A056983091CBBE68D1CC54A906289C74F5F193DB679E0DEA436EE7`.
- Shared jar: `sharedCore-ab642c64b398a3d31ab9cd258c653bd.jar`, SHA-256 `CBEE22DDDFA081AC4A7F788201C6CAFB9A08950E2849A2B114767F1F4DCAA847`.
- Codex reports 4m11s, weekly quota 86% → 85%, clean worktree and no protected branch changes. No program execution, real model call or fresh test gate; historical D Native/HWND and WIN synthetic ScrollBy RED remain RED.
- Project **did not independently read** the Windows H: package files; local hash and JDK17 evidence is Codex-reported, distinct from Project GitHub source verification.

## One planned user test session

Use **`84_PHASE7_SINGLE_SESSION_MANUAL_ACCEPTANCE_CN.md`** for all 18 grouped checks with just the new package. Prepare disposable source images and Studio/chat test data when available, else report `NOT TESTED`. Do not spend real NovelAI/LLM quota or overwrite user data to fill a checkbox. Previously frozen UI acceptance does not need repeating.

Only after all 18 group results are recorded (including NT) and optional planned persistence restart is either completed or NT should the user close CCB. No further piecemeal launches as substitute for this batch.

Package readiness does not sign additional items in `66` (still **12** signed), does not prove I3 actual metadata-import behavior and does not authorize merge to `desktop`. Phase 7 **IN PROGRESS / NOT ACCEPTED / NOT MERGED**.
