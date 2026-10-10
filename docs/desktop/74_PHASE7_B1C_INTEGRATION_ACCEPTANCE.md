# Phase 7 B1+C Integration Acceptance

**B1+C INTEGRATION ACCEPTED / FROZEN / NOT MERGED TO DESKTOP**

## Frozen source and document identity

- Integration branch: `feature/phase7-b1-c-integration`.
- Frozen production merge SHA: `15d4689ccfc684550e1d72e621221461ba90dce9`.
- First parent, B1 docs HEAD: `30c5703e944146cbfc2fe617374b69e7e2ad8119`.
- Second parent, C docs HEAD: `e143ac5f25c1629b7eaa08dc0929dccc7856f4b3`.
- B1 frozen production: `8cd718eba84a125c97eb80cec03eb25c6399fe8e`.
- C frozen production: `849ca384344edae0a9effb4bcf169ac3532427e1`.
- This acceptance closeout is a subsequent docs-only commit. Its HEAD is reported separately in the Git delivery; it is not the production merge SHA and changes no production or test source.

The two original feature branches retain their docs HEADs above. `desktop` remains `b3ecd41267906526e7b603972f7388e59c90648d`; the accepted integration has not been merged into it. Records `69_PHASE7_SLICE_A_ACCEPTANCE.md`, `71_PHASE7_SLICE_B1_ACCEPTANCE.md` and `72_PHASE7_SLICE_C_ACCEPTANCE.md` remain unchanged and valid.

## Evidence and responsibility

The following completed evidence is recorded from Project's acceptance instruction. No tests or package builds were repeated for this docs-only closeout.

| Authority | Completed evidence | Result |
| --- | --- | --- |
| Project independent review | GitHub integration production diff | PASS |
| Codex local automated verification | Focused integration: 10 suites / 57 tests | PASS |
| Codex local automated verification | Strict Desktop full: 127 suites / 1115 tests / 0 failures / 0 errors / 0 skips | PASS |
| Codex local automated verification | JDK 17 compile and diff-check | PASS |
| Codex local automated verification | Exact-source package: 210/210 file SHA-256 comparisons | PASS |
| Codex local automated verification | Fresh isolated Windows profile startup, normal WM_CLOSE, launcher/application exit 0/0 | PASS |
| User Windows manual acceptance | INT-01: chat-image More, right-click and cross-image scrolling/menu ownership | PASS |
| User Windows manual acceptance | INT-02: narrow CHAT → Studio → CHAT state/navigation | PASS |
| User Windows manual acceptance | INT-03: Studio full-height preview, splitter, 116dp rail and focus | PASS |
| User Windows manual acceptance | INT-04: maximize/restore/top-right blank drag area, no clipping | PASS |
| User Windows manual acceptance | INT-05: MANAGE → create Character chat → correct session | PASS |

Codex local tests were not executed by Project and do not replace user manual acceptance. User acceptance is separately supplied by the user for the actual Windows application. Earlier failed diagnostic attempts remain historical evidence; the final all-green gate and subsequent user acceptance control this record. No real AI/NovelAI generation request was made during the integration gate. This closeout does not infer or revise any historical live-request count.

## Frozen combined behavior

- B1 chat thumbnails retain their 280×280dp maximum, aspect ratio, original pixel ceiling, EXIF/DPI handling and Viewer entry. Each image's right-side bottom-aligned More and image right-click share image-only actions with correct reference ownership and deletion confirmation. Segmented image-only messages acquire no synthetic speaker/header; mixed messages retain their ordinary toolbar.
- B1 process-local compact navigation survives CHAT ↔ TOOLS ↔ MANAGE routing and enters newly created Character chats through the existing successful-entry gate.
- C Studio retains its full-height right preview, approximately 49% default split, adjustable splitter, 116dp rail, focus and narrow layout. Footer Tokens/Undo/Redo and Generate/Stop remain on the approved row.
- C native Chrome retains maximized nonresizable outer edges, restored resize and the right-top blank caption drag area without clipping. Interactive preview and splitter regions retain their own input behavior.
- The composed Shell preserves both sets of accepted behavior. This record adds no Prompt, request, cancellation, storage, schema or runtime semantics.

## Local evidence

- Final integration gate: `app/desktopApp/build/phase7-b1-c-integration-r1-gate/gate-summary.json`.
- The same directory retains test XML/statistics, native diagnosis and screenshots, manifests, hashes and isolated launch-smoke evidence.
- Accepted executable: `app/desktopApp/build/phase7-b1-c-integration-r1-distribution/ChatChatBarDesktop.exe`.

These are gitignored local evidence and distribution paths, not committed artifacts. The gate summary's original review-pending status describes when the automated gate was run; this document records the later Project review and user acceptance decision. No private Android UX reference screenshot is uploaded by this closeout.

## Remaining Phase 7 scope

**PHASE 7 IN PROGRESS / NOT ACCEPTED / NOT MERGED TO DESKTOP.** A, B1 and C are individually frozen, and B1+C integration is now accepted/frozen; none of these decisions signs the whole Phase 7 checklist.

- B2 remains **NOT IMPLEMENTED / NOT ACCEPTED**. `73_PHASE7_B2_UX_CONTRACT.md` remains a future UX contract only: Stop is outside the process card, to its right and bottom-aligned. No B2 implementation is authorized by this closeout.
- D Viewer navigation and P7-WIN-01/02/03 auxiliary-window issues remain pending. Main-window C-06 acceptance does not close auxiliary-window P7-WIN-01.
- Unconfirmed items in `66_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md` remain unchecked; this task does not alter that file.
- Formal validated upstream baseline remains CCB 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`. No 1.4.4/1.4.5 absorption, baseline promotion or parked-sync work is performed.

This docs-only commit is submitted for independent Project review; it does not merge any branch or authorize the next implementation slice.
