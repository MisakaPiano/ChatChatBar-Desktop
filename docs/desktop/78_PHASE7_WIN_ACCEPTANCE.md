# Phase 7 P7-WIN — Image Auxiliary Window UX Acceptance

**P7-WIN-01/02/03 USER ACCEPTED / FROZEN / NOT MERGED TO DESKTOP**

## Source, scope and responsibility

- Active feature branch: `feature/phase7-win-auxiliary-ux`.
- Frozen accepted source SHA: `50a7caf573827b402ddc16622941c2f18fcd3e12`.
- Source review chain: first implementation checkpoint `9649ca258314fb99ec96cc276482f9c69dfd68d7`, R1 icon/test handoff `50a7caf573827b402ddc16622941c2f18fcd3e12`.
- Acceptance date: **2026-10-11**.
- This is a subsequent docs-only acceptance record; its Git commit HEAD is distinct from the frozen source SHA and changes no production or test source.
- Authority: original issue inventory `70_PHASE7_OUTSTANDING_UX_ISSUES.md`, original bounded scope `77_PHASE7_WIN_UX_SCOPE.md`, user-checklist `66_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md` section Q, and accepted D Viewer `76_PHASE7_D_ACCEPTANCE.md`.
- Original issue/scope documents are historical evidence of what was OPEN before this user acceptance. Earlier A, B1, C, B1+C, B2 and D frozen feature records remain valid; D is not reopened.

## User Windows verification: 3/3 PASS

The user explicitly confirmed all three P7-WIN checks after receiving and running the exact-source Windows package. This is **user-performed visual/interaction acceptance**. Project reviewed GitHub source, but did not personally interact with the packaged GUI. Codex did not perform the user's acceptance.

| Check | Contract accepted by user | Result |
| --- | --- | --- |
| WIN-01 — Maximize / restore | Applicable large image auxiliary windows (AI Design, History, Viewer, Image Tools) maximize/restore with usable content, size, focus and lifecycle. Small confirmation dialogs do not have to maximize | **PASS** |
| WIN-02 — Official CCB logo | Image-domain top-level windows and relevant dialogs show the same official CCB icon rather than an unintended default Java icon; modal dialogs need not create their own taskbar entries | **PASS** |
| WIN-03 — Initial bounds and content reachability | Image auxiliary windows open at workable sizes; routine buttons remain accessible when resized; actual mouse-wheel scrolling in the image tools/guidance controls makes their bottom actions (Apply where applicable and Cancel) reachable | **PASS** |

**USER WINDOWS ACCEPTANCE: 3/3 PASS.** The user did not supply per-window screenshot inventory, display resolution/scaling or exact bounds, so this record makes no such claim. The acceptance does not imply real NovelAI/AI request execution or any historical generation-count change.

The user separately reported that considerable time and Codex allowance were spent on GUI automation and synthetic-test repairs despite satisfactory product operation. This is recorded as a workflow issue, not a product regression or evidence that the failing synthetic test passed.

## Independently reviewed implementation

- Shared `DesktopImageToolWindow` and relevant image `DialogWindow` entry points reuse the official `DesktopBrandResources.LOGO_RESOURCE`. No second logo asset and no P15 shell or installer integration.
- Window initial dimensions are bounded to the selected/active display's AWT usable work area; this limits initial size, not Windows maximize.
- Background library keeps a visible completion action below its scrolling content.
- Image Tools and guidance use an independent scrollable control area while preserving preview area; crop action controls use wrapping layout.
- Existing D Viewer navigation, B1 image actions, B2 task Stop, Studio state, metadata, Prompt, API requests and persistence semantics were not intentionally modified by this work package.
- Project independently inspected the actual `aea5d3de..9649ca25` nine-file implementation and `9649ca25..50a7caf` six-file handoff GitHub diffs, including the official-icon call sites, History test waiting logic and preserved synthetic scroll assertions. **PROJECT CODE REVIEW: PASS for manual-acceptance packaging.**

## Codex local evidence and retained RED

| Gate | Evidence | Classification |
| --- | --- | --- |
| JDK 17 compilation, source-scope checks and `git diff --check` | PASS in Codex local work | PASS |
| First focused Compose deterministic selection | 8 suites / 65 tests / 2 failures / 0 errors / 0 skips | **HISTORICAL RED** |
| Later R1 focused Compose deterministic selection | 8 suites / 65 tests / 1 failure / 0 errors / 0 skips | **HISTORICAL RED** |
| History selection after synchronization correction | Existing `范围起点` test passed in R1 focused run | PASS (specific test only) |
| Synthetic image-tool scrolling | `ScrollBy` accepted but scroll remained 0/573, resulting in a timeout at 2 seconds; Apply bounds empty `(0,0)-(0,0)` | **RED; unresolved test/runtime interaction** |
| Project-reviewed exact-source package | Temurin JDK `17.0.20.1+1`, 210/210 SHA-256 file comparisons, 0 missing/extra/mismatch | PASS (Codex local build evidence) |
| Windows user manual verification | WIN-01/02/03 and actual bottom-action scroll/access | **3/3 PASS** |

The synthetic 65-test gate **was not all green**. Do not relabel the synthetic failure as PASS or silently remove or weaken its assertion. The synthetic test uses Compose `ImageComposeScene` semantics scrolling, not the real Windows user's mouse wheel. The final user PASS supports the actual GUI UX, but **does not prove why `ScrollBy` failed**. Preserve the original local XML, log and screenshot evidence.

Project approved a **scope-limited manual substitution for Windows foreground/visual/GUI-accessibility verification**. This exception does not waive unrelated protocol, persistence, data integrity or release gates. **No final-source strict-full 0/0/0 gate has been claimed.**

## Exact-source local package

- Accepted source SHA: `50a7caf573827b402ddc16622941c2f18fcd3e12`.
- Executable: `app/desktopApp/build/phase7-win-manual-distribution/ChatChatBarDesktop.exe`.
- Package gate: `app/desktopApp/build/phase7-win-manual-package-gate/gate-summary.json`.
- Full 210-entry manifest: `app/desktopApp/build/phase7-win-manual-package-gate/distribution-manifest.csv`.
- EXE SHA-256: `E5D45E5389E7584B9D8477EFE34543DE1069E6550560A3A3C53D164152958B8F`.
- Desktop JAR SHA-256: `6A9B7720B88F2B319A921FCA9AED1F371F6F1001322B47C1A605A7132F1A1F5F`.
- SharedCore JAR SHA-256: `CBEE22DDDFA081AC4A7F788201C6CAFB9A08950E2849A2B114767F1F4DCAA847`.
- Historical first RED: `app/desktopApp/build/phase7-win-auxiliary-gate/gate-summary.json`.
- Historical R1 RED: `app/desktopApp/build/phase7-win-r1-gate/gate-summary.json`.
- The package verification task did not launch the EXE; user Windows acceptance is separate. Local outputs are gitignored and are reported by Codex, not re-executed by Project.
- Docs-only freeze: no tests, builds, packaging, API requests or migrations performed.

## Future verification discipline

1. **Change-impact first:** select deterministic tests of logic/data actually touched by each implementation diff. Preserve the possibility of broader integration verification but do not mechanically rerun every previously accepted GUI scenario for each narrow UX change.
2. **GUI acceptance ownership:** source/resource correctness and compile belong to developer/Project evidence; true foreground, title/taskbar branding, scrollability and window behavior are primarily user Windows manual checks. Robot/HWND and synthetic semantics limitations must be recorded distinctly from real defects.
3. **Failure triage:** never erase an actual deterministic RED, falsify a PASS, or discard release-risk evidence. Diagnose scope and evidence once; classify unrelated, platform-sensitive or synthetic-fixture failures separately. Product bug evidence warrants targeted correction; automation infrastructure repair requires an explicit separate reason, not retry-until-green.
4. **One active feature branch:** keep accepted historical anchors, develop the remaining Phase-7 work on the current authorized line; Project writes/reviews docs directly, Codex handles local source edits, JDK build and reproducible tests/packages.
5. **Phase-end integration:** before broad merge/release, assess the complete test inventory and remaining REDs by ownership and risk. Manual WIN acceptance does **not** equal whole-Phase7 or release acceptance.

## Remaining boundaries

- WIN-01/02/03 are **USER ACCEPTED / FROZEN**; section Q of `66` records only these three sign-offs.
- Other unchecked items in `66_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md` remain **UNSIGNED / OPEN**.
- Phase 7 stays **IN PROGRESS / NOT ACCEPTED / NOT MERGED TO DESKTOP**. No `desktop` merge, release or blanket image-parity claim follows from this WIN acceptance.
- Validated upstream baseline remains CCB **1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**. Parked sync and fork `master` are untouched; no compatibility promotion.
- Earlier frozen D, B2, B1+C and other feature branches are unchanged. No re-test/re-acceptance of them is implied.

This record documents real user acceptance while preserving the independent automated regression evidence and the remaining Phase-7 integration responsibilities.
