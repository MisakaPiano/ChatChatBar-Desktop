# Phase 7 Slice D — Unified Desktop Viewer UX Acceptance

**P7-D USER ACCEPTED / FROZEN / NOT MERGED TO DESKTOP**

## Source identity and authority

- Feature branch: `feature/phase7-d-unified-viewer-ux`.
- Frozen accepted source SHA: `56866d96f55aa45c7cb1502c63ed1fb07f29bf17`.
- Independently reviewed production code SHA: `8a85219358d9a2cc0b58e6df6ef9c8e254a78e08`.
- From `8a852193` to the accepted source SHA, only `WindowsBackgroundViewerTest.kt` changed. No further production changes were made.
- Acceptance date: **2026-10-11**. User Windows verification: **D-01–D-07, 7/7 PASS**.
- This is a subsequent docs-only closeout. Its commit HEAD is separate from the frozen source SHA; the Git delivery identifies that HEAD. The documentation commit changes no production or test code.
- Earlier A, B1, C, B1+C and B2 accepted/frozen records remain unchanged and valid. Any earlier statement that D was OPEN describes the historical status at that earlier closeout.

## Frozen Viewer UX and user acceptance

The user explicitly confirmed all seven checks after running the P7-D Windows package. These results are **user-performed manual acceptance**, not Codex-executed or Project-executed GUI tests.

| Check | Accepted behavior | User result |
| --- | --- | --- |
| D-01 | Chat image double-click opens one unified Viewer; multi-message image order and the clicked occurrence remain correct | PASS |
| D-02 | Hovering at the image's left/right edges reveals previous/next arrows; mouse, keyboard and consecutive page navigation, counters and boundaries work | PASS |
| D-03 | Studio saved images and filmstrip double-click enter Viewer; single-click selection, existing layout and preview zoom remain usable | PASS |
| D-04 | History double-click enters unified Viewer; navigation follows the currently filtered image order across batches; recipe details remain independently accessible | PASS |
| D-05 | Background-library double-click opens an interactive collection Viewer; navigation and close/return work without changing background preferences | PASS |
| D-06 | Viewer wheel zoom, drag-pan, reset, close, animation and single-image behavior remain acceptable; single images do not show meaningless page arrows | PASS |
| D-07 | No observed regressions in the checked B1 image More/right-click actions, B2 process card and source-owned Stop, or C Studio layout | PASS |

**USER WINDOWS MANUAL ACCEPTANCE: 7/7 PASS.**

This acceptance does not imply the user ran real AI/NovelAI requests. It does not change Prompt, API request serialization, Entity/Package schemas, data ownership, saved image bytes or persistence policies.

## Code review and automated evidence

| Responsibility | Evidence | Result / limitation |
| --- | --- | --- |
| ChatGPT Project | Independent GitHub source review through `8a852193`: collection positional identity (including `[A,B,A]`), message-ID/image-ordinal entry, current mutable index for sequential keyboard/hover/footer actions, filtered History sequence and independent recipe details | **CODE REVIEW PASS** |
| ChatGPT Project | Independent GitHub review of test-only safe-layout diff `8a852193..56866d96` | **TEST-ONLY REVIEW PASS** |
| Codex local | Original D implementation focused 7 suites / 67 tests and strict 130 suites / 1141 tests, 0 failures/errors/skips | PASS on earlier implementation SHA `7dc90de`, **not final-source strict-full evidence** |
| Codex local | R1 deterministic non-Native focused, 6 suites / 56 tests; JDK 17 production/test compile and diff-check | PASS on `8a852193` |
| Codex local | Isolated Native foreground/Viewer verification, 3 suites / 4 tests | PASS on an intermediate local test-harness state |
| Codex local | Later strict full 131 suites / 1148 tests | **2 failures**, both pre-click HWND ownership checks; **historical RED** |
| Codex local | Final safe-layout SHA `56866d96` test compilation / diff-check | PASS; final Native test attempt still failed before Viewer opened |
| Codex local | Exact-final-source JDK 17 distribution and complete payload manifest | **210/210 SHA-256 PASS** |
| User | Real Windows interaction acceptance for D-01–D-07 | **7/7 PASS** |

The final-source Native attempt reported **1 suite / 2 tests, 1 failure / 0 errors / 1 skipped (fail-fast)**. The failure occurred before opening Viewer: target point `960,155`, fixture HWND `0x17d0dd0`, hit root HWND `0x50800`, resolved to external `chrome.exe` at the time. The safe pre-click check correctly prevented Robot input to another application. Other Native methods were not executed in that attempt.

The earlier full RED likewise came from foreground/pointer ownership preconditions and is **not** retroactively reclassified as passing. Neither that RED nor a pre-Viewer focus failure is evidence that Viewer product navigation failed. At the same time, **there is no exact-final-SHA strict-full 0/0/0 claim**.

Project explicitly approved a **scope-limited user manual acceptance substitution for foreground-dependent Native GUI automation**, retaining the original Native/full RED evidence and all test assertions. This exception does not waive unrelated unit, protocol, data integrity or release gates. The user's direct Windows confirmation is a separate acceptance authority. No tests were rerun in this docs-only closeout.

## Accepted exact-source package and local evidence

- Source: `56866d96f55aa45c7cb1502c63ed1fb07f29bf17`.
- Package gate: `app/desktopApp/build/phase7-d-manual-package-gate/gate-summary.json` (local, gitignored).
- Accepted Windows EXE: `app/desktopApp/build/phase7-d-manual-distribution/ChatChatBarDesktop.exe`.
- Complete payload: **210/210 SHA-256 matches**, 0 missing, extra or mismatched files (Codex local report).
- EXE SHA-256: `E5D45E5389E7584B9D8477EFE34543DE1069E6550560A3A3C53D164152958B8F`.
- Desktop application JAR SHA-256: `0D1B1394C3723FA2C6C6A588F169C0103BB1CCDFD0F624F010E44E7B101FD553`.
- SharedCore JAR SHA-256: `CBEE22DDDFA081AC4A7F788201C6CAFB9A08950E2849A2B114767F1F4DCAA847`.
- Earlier deterministic gate: `app/desktopApp/build/phase7-d-r1-deterministic-gate/gate-summary.json`.
- Final-source Native RED: `app/desktopApp/build/phase7-d-safe-layout-validation-gate/gate-summary.json`.
- Earlier RED and HWND diagnostics remain in their original gitignored evidence directories; they are not overwritten or uploaded.
- The package task did **not** perform an isolated launcher/WM_CLOSE smoke and does not claim to have done so. The user separately ran the executable and confirmed the listed Viewer interactions.

This docs-only closeout performs no new tests, packaging, data migration or real AI/NovelAI request. It does not infer the user's live API consumption or revise the historical NovelAI 1/8 record.

## Phase boundary and remaining work

**PHASE 7 IN PROGRESS / NOT ACCEPTED / NOT MERGED TO DESKTOP.** D is independently accepted and frozen, not a Phase-7-wide sign-off.

- P7-WIN-01: auxiliary-window maximize / restore — **OPEN**.
- P7-WIN-02: auxiliary-window CCB icon / branding — **OPEN**.
- P7-WIN-03: initial size and content visibility — **OPEN**.
- All unconfirmed Phase-7-wide items in `66_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md` remain unsigned; the checklist is unchanged. D's own user verification does not automatically sign other Viewer/History-wide boxes.
- Formal validated upstream baseline remains CCB **1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**; parked upstream sync remains untouched.
- No merge to `desktop` or protected branch movement is authorized. Historical acceptance documents, source Prompt, Package, Entity, request/runtime semantics, image data and user persistence remain untouched.

This document records the user's completed D acceptance, without claiming that historically failing automated tests became green.
