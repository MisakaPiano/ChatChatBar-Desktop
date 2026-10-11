# Phase 7 W2 — History User Windows Acceptance and Studio Pending

**HISTORY H1–H3 USER PASS (3/3) / W2 STUDIO PENDING / PHASE 7 NOT ACCEPTED**

## Source and provenance

- Existing accepted production source: `50a7caf573827b402ddc16622941c2f18fcd3e12`.
- Branch: `feature/phase7-win-auxiliary-ux`; this is a **docs-only** follow-up to the WIN source and its subsequent accepted/reconciliation docs.
- User response date (project record): 2026-10-12.
- The user provided all three History outcomes in the conversation after the W2 History walkthrough. These are **user-performed Windows checks**; not Codex tests and not GUI operations performed by Project.
- No new images or paid AI/NovelAI requests were needed as prerequisites. This record does not assert whether the user independently used APIs.
- Prior C/D/WIN accepted behavior remains frozen. This is not permission to merge `desktop`.

## User results and exact `66` checklist mapping

| User check | What the user checked | Original `66` item | Result |
| --- | --- | --- | --- |
| H1 | Existing History items display newest first where multiple dated results exist | H-02 — `newest first。` | **PASS** |
| H2 | Fold-as-album control and folding/grouping/album access behave normally | H-03 — `折叠相册仍正常。` | **PASS** |
| H3 | Search existing positive-Prompt keywords; date filter by known date, apply and clear as appropriate | H-04 — `搜索/日期正常。` | **PASS** |

The user explicitly returned **H1 PASS / H2 PASS / H3 PASS**, therefore `66` **H-02/03/04 are checked**. No separate runtime recording, screenshot, monitor measurements, fixed number of History entries or HTTP trace were supplied; do not invent any.

**Not signed by this check:** H-01 responsive/adaptive grid, H-05 Shift-range selection, H-06 selection badge/range hint. They already have documented deterministic code/test evidence in `68` and `79`, but technical verification must not be relabeled user PASS. H-07 unified History Viewer and H-08 resize/maximize already have explicit later user acceptance in `76`/`78`; those are not rerun.

## Remaining W2 Studio review

No Studio test outcome has yet been reported in this W2 walkthrough. The next user check is intentionally short and does not repeat accepted Studio splitter, preview-focus, Viewer zoom/pan, or window maximize.

- Studio filmstrip folding/unfolding with existing saved images (old `66` F-05; the rail itself was already accepted in C).
- Narrow Studio width places the compact image preview visibly at the expected top position (F-07), without content becoming inaccessible.
- Selected-image actions appear in one correct location, not two duplicated sets (F-08).
- If readily observable while testing above: image preview adapts when the pane narrows/widens (F-03). Default-newest and auto-select-new-generation (F-01/02) retain existing technical evidence; **do not create paid images solely to test F-02**.
- W2 other partial/technical questions (e.g. explicit Viewer Fit vs Reset, History Shift-range/badge) remain accurately classified in `79`; no blanket PASS is inferred.

## Project guard

This closeout:
- Changes only Project documents; no executable, tests, data/persistence, Prompt or baseline metadata.
- Does **not** rerun Codex or any synthetic GUI/Robot gate.
- Retains the historical D Native and WIN synthetic `ScrollBy` RED evidence unchanged.
- Records user acceptance separately from source review and earlier deterministic checks.
- Does not sign W2 Studio, all of `66`, all of Phase 7 or authorize release/merge.
