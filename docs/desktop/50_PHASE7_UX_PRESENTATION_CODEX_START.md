# 50 — Phase 7 UX Presentation Closure — Codex Start

Repository:
`H:\ChatChatBar-Desktop`

Continue the existing branch:
`feature/phase7-image-novelai`

Expected starting HEAD:
`dd3e68fa3c78204dd075391e4a779123429581c1`

Do not merge `desktop`.
Do not start Phase 8.

## Why this task exists

Phase 7 R2/R1 semantic implementation, automated validation and Project code review passed.

Second user manual acceptance still failed because:
- Studio controls are form-like and space-heavy;
- small choices open large dialogs;
- high-frequency numeric controls are full-width text inputs instead of sliders/chips;
- Prompt translation/annotation is not presented equivalently to CCB;
- chat image controls still consume too much permanent composer space.

This is a **presentation closure**, not another runtime rewrite.

## Read

Project authority first, then:

1. `48_PHASE7_UX_PRESENTATION_CLOSURE.md`
2. `49_NOVELAI_PROVIDER_CAPABILITY_DRIFT_AUDIT.md`
3. `51_PHASE7_UX_PRESENTATION_MANUAL_ACCEPTANCE_CN.md`
4. `refs/*`

Important upstream source:
- formal `ImagePromptToolScreen.kt`
- formal Prompt translation/annotation flow
- formal Generation Settings UI ownership

## Recommended model

GPT-6 Astra / High / Fast OFF.

Expected task size:
roughly 45–90 minutes.

This is one cohesive UI closure task with durable internal commits, not many micro tasks.

## Required implementation

Complete all requirements in `48`.

In particular:

- replace generic modal `StudioChoice` usage with correct compact choice controls;
- size tier chips;
- ratio chips;
- count 1–4 chips/buttons;
- custom size compact editor;
- advanced generation settings collapsible summary;
- sliders for Steps / CFG / Rescale with precise values;
- real compact dropdown for Sampler;
- Random Seed switch + conditional compact field;
- compact toolbar;
- reduce repeated large `展开编辑` buttons;
- implement actual inline/aligned Prompt Chinese annotation equivalent, including fullscreen;
- tag suggestions should be field-anchored and compact;
- reduce chat composer image/background/auto controls to a compact toolbar;
- keep responsive wide/narrow behavior.

## Hard boundaries

Do NOT change:

- Prompt literals/builders;
- Package;
- upstream Entity schema;
- NovelAI HTTP request semantics;
- NovelAI model enum;
- NovelAI sampler enum/API ids;
- sampler-by-model capability;
- automatic-image policy;
- History semantics;
- Guidance semantics;
- SecretStore.

Do NOT "fix" provider capability drift in this task.

If current UI refactor appears to require runtime capability changes, stop and report that specific blocker.

## NovelAI live safety

0 additional real image-generation requests.

Phase live count remains 1/8.

No real Generate/Retry.

## Tests

Add focused presentation tests for:
- compact choices;
- chips;
- sliders/draft values;
- custom-size dialog;
- advanced collapse;
- Random Seed conditional editor;
- translation toggle and aligned annotation presentation model;
- fullscreen annotation;
- tag suggestion presentation;
- chat composer compact layout;
- wide/narrow Studio scenes;
- presentation operations do not change serialized/request semantics.

Then:
- Desktop full
- compile
- diff-check
- new isolated distributable
- launch smoke

Shared/Android affected tests only if shared production source changes; prefer no shared production changes.

## Final report

Push clean checkpoint and report:

`READY FOR PROJECT PHASE-7 UX PRESENTATION REVIEW`

Include:
- production source SHA
- docs HEAD if separate
- exact UI components replaced
- CCB control mapping
- translation presentation implementation
- responsive evidence
- focused/full counts
- package path/hash
- launch smoke
- NovelAI remains 1/8

Do not mark Phase 7 ACCEPTED.
