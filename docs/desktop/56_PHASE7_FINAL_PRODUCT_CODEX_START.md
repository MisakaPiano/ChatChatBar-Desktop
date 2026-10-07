# 56 — Phase 7 Final Product Closure — Codex Start

Repository:
`H:\ChatChatBar-Desktop`

Continue existing branch:
`feature/phase7-image-novelai`

Expected start:
`a0918deab8348ed0c2c70442b08e9312b28de247`

Do not merge `desktop`.
Do not start Phase 8.

## Status

Semantic/runtime implementation: already Project PASS.

Third user UX acceptance: FAIL/HOLD.

This is the final product closure for Phase 7.

Read:

1. Project authority docs
2. `54_PHASE7_FINAL_PRODUCT_CLOSURE.md`
3. `55_PHASE7_DEFERRED_OWNER_MAP.md`
4. `57_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md`
5. `58_PHASE7_FINAL_SCREENSHOT_INDEX.md`
6. relevant `refs/*`

Formal CCB authority includes:
- `ImagePromptToolScreen.CharacterCardImport`
- `OutputPanel`
- dynamic Generate label
- `NovelAiDesignScreen`
- Chat composer/session settings ownership

## Recommended execution

GPT-6 Astra / High / Fast OFF.

One cohesive task.
Durable internal commits.
No micro-task user loops.

Expected runtime:
60–120 minutes.

## Mandatory outcomes

Implement all items in `54`, especially:

- AI Design IME-safe editor
- safe actionable failure categories
- structured AI Design Prompt cards + per-module copy
- formal CharacterCard Prompt import description
- move Prompt Inspector under advanced/diagnostics
- visible Primary/Secondary/Tertiary action hierarchy
- common icons
- dynamic `生成免费 / 生成消耗 N Anlas`
- account status cluster
- token bars
- adaptive result preview
- recent/current image filmstrip
- attachment inside composer
- larger attachment preview + click viewer + hover ×
- remove automatic image/background from composer
- Session Settings tabs
- preserve current runtime semantics

## Deferred boundaries

Read `55`.

Do NOT implement now:

- provider sampler/model capability changes
- complete Studio config preset import/export
- global app IA overhaul
- Moments
- Community
- OS integration

Do not silently solve deferred items.

## Prompt / API hard rules

Do not change:
- Prompt literals
- Prompt Designer contract
- Package
- Entity schema
- NovelAI HTTP
- sampler API ids
- automatic image policy
- History/Guidance semantics

If AI Design bug requires a real semantic change, stop and report exact blocker.

## Network safety

No real NovelAI image generation.
No real external design-model request required.

Use fake transport/model for AI Design functional validation.

NovelAI image live count remains 1/8.

## Validation

Follow `54 §18`.

Because this is production UI change:
- full Desktop rerun
- isolated package
- launch smoke required

Final report exactly:

`READY FOR PROJECT PHASE-7 FINAL PRODUCT REVIEW`

Include:
- production SHA
- docs HEAD
- changed UI surfaces
- AI Design IME/error evidence
- structured result/copy evidence
- dynamic cost/token/status evidence
- filmstrip/preview evidence
- composer/session-settings evidence
- focused/full counts
- package/hash/smoke
- confirmation provider capability unchanged
- NovelAI remains 1/8

Do not mark Phase 7 accepted.
