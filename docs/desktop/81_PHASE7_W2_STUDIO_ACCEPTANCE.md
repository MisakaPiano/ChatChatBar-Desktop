# Phase 7 W2 — Studio Result Pane User Windows Acceptance

**W2 STUDIO S1–S3: USER 3/3 PASS / FOUR F CHECKBOXES SIGNED / PHASE 7 NOT ACCEPTED**

## Exact identity and responsibility

- Accepted production SHA: `50a7caf573827b402ddc16622941c2f18fcd3e12`; unchanged since P7-WIN production acceptance.
- Feature line: `feature/phase7-win-auxiliary-ux`. This document is a subsequent **docs-only** closeout, not a production source commit. Record its new docs HEAD separately.
- User-reported Windows acceptance date: **2026-10-12**.
- Evidence source: the user's explicit W2 Studio response after the three-check walkthrough. The user performed the Windows UI checks; Codex did not execute them and Project did not directly operate Windows.
- W2 History H1–H3 previously passed on the same production SHA and remain governed by `80_PHASE7_W2_HISTORY_ACCEPTANCE.md`.
- Previously frozen C (splitter/preview focus), D (Viewer navigation/zoom/pan), WIN (icons/maximize/size) are **not reopened or repeated**.

## S1–S3 outcome and exact checklist mapping

| User check | Previously requested Windows behavior | `66` item signed | Result |
| --- | --- | --- | --- |
| S1 — Filmstrip 折叠 | Collapse Studio right-side image preview to filmstrip and expand back; image selection remains usable | F-05 — `可折叠成右侧 filmstrip。` | **PASS** |
| S2 — 窄窗口预览布局 | Narrow the Studio window; the image adapts to available pane width and becomes a top compact image panel rather than being pushed to the bottom | F-03 — `大图随右 pane 尺寸变化。`; F-07 — `窄窗口变成顶部 compact image panel，而不是把预览挤到很下面。` | **PASS (both)** |
| S3 — 图片操作按钮 | For a selected existing result, image actions appear in one appropriate location, not as duplicate selected-image action groups | F-08 — `selected-image actions 不重复两套。` | **PASS** |

**S1/S2/S3: 3/3 USER WINDOWS PASS.** The three checks give evidence for **four distinct original F checklist items**; `66` F-03, F-05, F-07 and F-08 are signed accordingly. There is no claim of four separately performed user walkthroughs.

## Explicit non-claims and remaining W2 boundary

- F-01 default newest selection and F-02 selecting the newest after a new generation are supported by earlier deterministic/technical evidence in `68`, **not separately signed** by S1–S3. Do not initiate paid NovelAI generation to force F-02 sign-off.
- F-04 resizable divider and F-06 preview-focus already have direct user acceptance in `72`/`74` and should not be retested merely for this W2 record; their original `66` boxes remain unchanged pending whole-Phase7 reconciliation.
- G-03 Reset/Fit combination and G-06 no duplicate title retain their earlier **PARTIAL** classifications; they were not tested by the user in this round.
- H-01 adaptive grid and H-05/H-06 Shift-range/badge retain their technical evidence without being relabeled user PASS. H-02/03/04 were explicitly signed in `80`; H-07/08 have earlier user evidence in `76`/`78`.
- No new image generation, Enhance/Upscale, paid AI request, real-credential change, data migration, screenshot trace, monitor metrics or new standalone runtime test is **asserted** here. The user did not supply these details; no claim is invented.
- Historical D Native HWND/Chrome and WIN synthetic `ScrollBy` RED records remain RED. GUI user acceptance is separate authority.

## Project actions and freeze boundary

This docs-only record:
- Updates `66` section F with only the four explicit [x] items, the source/acceptance mapping, and retains all unconfirmed checkboxes.
- Updates `79` 115-row reconciliation (10 SIGNED; 12 USER-EVIDENCED; 5 PARTIAL; 37 TECH-EVIDENCE; 51 OPEN).
- Updates `00`, `16`, `21` to point at the independent Windows acceptance and current coverage.
- Does not touch Kotlin/Gradle/test assets, previously frozen source commits, official Prompt, API/Package/Entity semantics, SecretStore, upstream baseline, fork master or desktop branch.
- Does not authorize Phase 7 acceptance, feature merging or release.
