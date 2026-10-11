# Phase 7 W3 — Metadata Import Dialog UX Acceptance

**M1–M3 USER WINDOWS 3/3 PASS / LOCAL P7-WIN-03 FOLLOW-UP CLOSED / PHASE 7 NOT ACCEPTED**

## Source and authorities

- Exact production SHA: `f43c08564a89607b9fdb53658f0a2eb044384f22` on `feature/phase7-win-auxiliary-ux`.
- Project independently reviewed the one-file `DesktopNovelAiStudioPanel.kt` patch (+8/-4) against `f550f5f`: 560×620dp work-area-bounded dialog, independent scrolling options, fixed footer actions, same CCB icon and existing import/cancel callbacks. **CODE REVIEW PASS**.
- Codex reported Temurin JDK17 compilation / scope / diff-check PASS, and eventually **210/210 SHA-256 distribution PASS**, 0 missing/extra/mismatched files. Earlier 201/210 incomplete disk-full copy was **NOT** valid; the user freed disk space and Codex completed the exact-source package without recompiling source.
- Package: `app/desktopApp/build/phase7-w3-metadata-manual-distribution/ChatChatBarDesktop.exe`. Gate evidence: `app/desktopApp/build/phase7-w3-metadata-manual-gate/gate-summary.json` and `distribution-manifest.csv` (gitignored local outputs, not Project-run).
- EXE SHA-256 `E5D45E5389E7584B9D8477EFE34543DE1069E6550560A3A3C53D164152958B8F`; Desktop JAR `8E43FD6310F81DB699BFBE0DB674B76D2C691B80CCA4D8922CF74F172B6532E7`; sharedCore JAR `CBEE22DDDFA081AC4A7F788201C6CAFB9A08950E2849A2B114767F1F4DCAA847`.
- No new strict-full or Native test was run. Historical D Native/HWND and WIN synthetic ScrollBy RED are preserved without relabeling.

## User Windows acceptance (2026-10-12)

The user explicitly confirmed all three on the exact-source EXE:

| Check | Behavior | Result |
| --- | --- | --- |
| M1 | Metadata dialog opens with visible bottom `导入`/`取消` without manual resize | **PASS** |
| M2 | After resizing smaller, options scroll and footer controls remain accessible | **PASS** |
| M3 | Adjusting options then pressing Cancel closes dialog and leaves current Prompt unchanged | **PASS** |

**USER WINDOWS M1–M3: 3/3 PASS.** This closes only the metadata dialog visibility/cancel UX issue in `70`. It does not prove the actual role metadata import OFF/REPLACE/APPEND or ID/enabled/center preservation. **W3 I3 and `66` I-05/06/07 remain unsigned**; W3 I2 structured-positive copy/paste also remains NOT TESTED.

The acceptance is user-provided, distinct from Codex build evidence and Project source review; no real NovelAI calls or user-data migration are inferred.

## Manual-test scheduling correction

The user noted that after completing the short M1–M3 request they closed the application, unaware that further Phase-7 tests would be requested. Future user Windows acceptance must be organized into **one comprehensive per-launch test batch**: first verify actual on-screen Chinese entry points and available fixtures, then give all feasible steps and expected results grouped in navigation order, with NOT TESTED and paid-call boundaries. Explicitly identify partial checks versus the end of a batch, and say the app can be closed only at the end. Previously frozen tests should not be repeated. **Do not ask the user to restart the EXE now.**

Docs-only closeout: no Kotlin/Gradle edits, no new testing, no baseline or protected branch updates, no Phase-7-wide acceptance or `desktop` merge. Total `66` signed boxes stay **12/115**, as M1–M3 are a supplemental UX gate, not new I3 import coverage.

**PHASE 7 IN PROGRESS / NOT ACCEPTED / NOT MERGED.**
