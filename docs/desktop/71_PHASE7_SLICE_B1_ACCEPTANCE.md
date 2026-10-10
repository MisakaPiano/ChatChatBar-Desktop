# Phase 7 Slice B1 — Chat Image Display and Context Actions acceptance

**P7 Slice B1 — ACCEPTED / FROZEN / NOT MERGED.**

- Frozen production SHA: `8cd718eba84a125c97eb80cec03eb25c6399fe8e` on `feature/phase7-image-novelai`.
- This record is a docs-only closeout after that production SHA. The docs-only feature HEAD is the commit containing this file, recorded separately from the frozen production SHA in the Git handoff.

## Frozen behavior

- Chat images use a maximum 280×280dp thumbnail, preserve aspect ratio and the original-pixel no-upscale ceiling, and open the existing Viewer on click. EXIF orientation and display-density behavior remain intact.
- Each image has its own More button to its right, aligned at the bottom. More and image right-click expose the same permitted actions for that image and never mix in whole-`ChatMessage` actions. Multiple images keep their own references and action targets.
- Image actions retain the existing metadata/regeneration and running-task eligibility, image-delete confirmation, and cancellation behavior. Mixed text-and-image messages retain their ordinary message toolbar and legitimate message actions.
- Segmented image-only messages do not acquire a synthetic speaker title, avatar, extra visible message control, or fabricated blank right-click area merely to expose message actions. Existing compact chat navigation remains unchanged.

## Acceptance ownership and evidence

| Decision or check | Owner | Result |
|---|---|---|
| Independent GitHub production-diff review, including R2-7 | Project | CODE REVIEW PASS |
| Final Windows image More positioning and menu on another image after scrolling | User | PASS; **SLICE B1 USER ACCEPTED / FROZEN** |
| Earlier image More position/content and duplicate speaker-name behavior | User | Confirmed acceptable before the final positioning recheck |
| R2-7 focused Desktop Compose tests | Codex local execution on frozen production source | 19/19 PASS, including pointer/coordinate coverage |
| Desktop full `:desktopApp:test --rerun-tasks --no-build-cache --no-configuration-cache` | Codex local execution on frozen production source, JDK 17 | 122 suites / 1101 tests / 0 failures / 0 errors / 0 skips |
| Desktop compile and `git diff --check` | Codex local execution | PASS |
| R2-7 exact-SHA distributable and isolated Windows launch | Codex local execution and report | 210/210 build-output/sealed-file SHA-256 matches; main window appeared; normal `WM_CLOSE`; launcher/application exit 0/0; stdout/stderr 0/0 bytes; no residual process |

Package and launch gate: `app/desktopApp/build/phase7-b1-r2-7-gate/gate-summary.json` (gitignored local evidence). The independent acceptance executable is under `app/desktopApp/build/phase7-b1-r2-7-distribution/`. These automated checks made no real AI request. Codex performed the local checks; they are not described as Project-run tests or as a substitute for the user's Windows acceptance. The R2-5/R2-6 historical manual failures were superseded by later repairs and this final recheck, not relabeled as PASS.

## Phase boundary

Slice B1 is frozen as a local feature acceptance, not a Phase-7-wide acceptance. Slice A remains **ACCEPTED / FROZEN** under `69_PHASE7_SLICE_A_ACCEPTANCE.md`. Slice C Studio Preview Workspace still needs independent layout acceptance. B2, Viewer navigation, and P7-WIN-01/02/03 remain open; this record does not sign the other boxes in `66_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md`.

**Phase 7 remains NOT ACCEPTED / NOT MERGED.** Neither this feature nor Slice C is merged into `desktop`. No upstream sync or baseline promotion is part of this closeout. The formal validated upstream baseline remains **CCB 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**.
