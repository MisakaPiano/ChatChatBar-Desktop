# 49 — NovelAI Provider Capability Drift Audit

## Purpose

This document separates:

1. **CCB parity** — Desktop must follow declared upstream CCB behavior.
2. **Provider compatibility** — upstream CCB itself may lag the current NovelAI service/web application.

Do not silently fix provider drift only in Desktop.

Audit date: 2026-10-07.

Formal CCB baseline:
`1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`

Observed CCB mirror checked:
`1.4.4 @ 550409689df8c51f459fb50b4e04c8ac2fa4bf35`

External source:
current official NovelAI documentation, checked 2026-10-07.

User also supplied current NovelAI Web UI screenshots as REF.

---

# 1. Image model set

## CCB 1.4.1 / observed 1.4.4

`NovelAiImageModel` exposes only:

- V4.5 Full
- V5 Full

## Current official NovelAI docs

Current docs describe at least:

- V5 Full
- V5 Curated
- V4.5 Full
- V4.5 Curated
- additional older models

### Status

**CONFIRMED PROVIDER DRIFT**

This does not authorize Desktop to add Curated/older models.

A shared upstream-compatible capability decision is required first.

---

# 2. Sampler catalog

## CCB 1.4.1 / observed 1.4.4

Global enum:

- Euler Ancestral
- Euler
- DPM++ 2S Ancestral
- DPM++ 2M
- DPM++ SDE
- DDIM

The Studio UI directly uses all enum entries regardless of model.

There is no model-specific sampler capability layer.

## Current official NovelAI docs

Official Sampling Methods docs currently list:

- DPM++ 2M
- Euler Ancestral
- Euler
- DPM2
- DPM++ 2S Ancestral
- DPM++ SDE
- DPM Fast
- DDIM

Therefore CCB's list is already incomplete relative to current official documentation.

User-provided current NovelAI Web screenshot additionally shows a provider UI value labelled:

`DPM++ 2M SDE`

That label is not present in current CCB enum and is not clearly documented with an API id in the official docs inspected here.

### Status

**CONFIRMED CATALOG DRIFT**
**EXACT CURRENT API MAPPING: UNRESOLVED**

Do not guess API ids.

---

# 3. Model-specific sampler availability

User reports the sampler list differs by selected NovelAI model.

Current CCB has no such filtering.

The official documentation inspected describes sampling methods globally and does not provide a complete model × sampler compatibility matrix.

### Status

**POTENTIAL PROVIDER COMPATIBILITY GAP / NEEDS AUTHORITATIVE EVIDENCE**

Before changing runtime, obtain one of:

1. official NovelAI API documentation with model-specific sampler constraints;
2. inspectable current provider request schema/metadata;
3. deterministic no-cost provider capability endpoint;
4. current NovelAI Web client mapping with sufficiently authoritative evidence.

Do not use trial-and-error paid generation to discover this.

---

# 4. Steps / Guidance / Rescale

CCB:
- Steps 1–50
- Guidance 1–10
- CFG Rescale 0–1

Official NovelAI docs:
- explain Steps and Guidance;
- recommend Guidance around 5–6 for V3+;
- describe Prompt Guidance Rescale;
- current Web UI uses slider-style controls.

No provider-breaking range mismatch has been established by this audit.

### Status

`NO CONFIRMED RUNTIME DRIFT`
`UI PRESENTATION DRIFT: YES`

UI presentation is handled by `48`.

---

# 5. Resolution / count

CCB formal Studio:
- Small / Normal / Large / Wallpaper
- Portrait / Square / Landscape
- custom size
- batch count 1–4

Current NovelAI Web also uses direct resolution/aspect/count controls.

No runtime mismatch is established here.

### Status

`UI EQUIVALENCE IMPROVEMENT ONLY`

---

# 6. V5 allowance

CCB already contains V5 allowance/account UI state.

Current NovelAI docs confirm V5 has an Opus usage allowance while prior models are treated differently for free-generation limits.

### Status

`CCB HAS CURRENT CONCEPTUAL SUPPORT`

Exact provider accounting remains service-owned.

---

# 7. Translation is not provider drift

Prompt Chinese annotation in CCB is a CCB/local-tool feature:
- local dictionary/catalog
- annotation overlay
- tag suggestion translation

It does not depend on NovelAI image-generation API capability.

Desktop's incomplete presentation belongs to Phase 7 UX closure, not this provider audit.

---

# 8. Recommended future architecture — NOT AUTHORIZED YET

If authoritative provider evidence confirms model-dependent capability rules, prefer one shared authority such as:

`NovelAiModelCapabilities`

Possible fields:
- available samplers
- default sampler
- max/recommended steps
- guidance range/default
- supported reference modes
- supported generation actions
- resolution constraints
- free-generation eligibility metadata

Then:
- Android UI
- Desktop UI
- request validation

must consume the same capability authority.

Do not create:
- `DesktopNovelAiCapabilities`
- hardcoded sampler filtering in Desktop UI only
- duplicated provider rules.

---

# 9. Acceptance impact

For declared **CCB 1.4.1 parity**, provider drift is not automatically a Desktop parity defect if Desktop faithfully follows formal CCB.

However:

- if a CCB-allowed combination is proven to be rejected by the current NovelAI provider, that becomes a real provider compatibility defect;
- it must be tracked explicitly and fixed in shared/upstream-equivalent authority;
- do not call current provider compatibility fully validated until resolved.

This audit itself does not block the presentation-only closure unless a deterministic provider incompatibility is demonstrated.

---

# 10. External evidence summary

Long-term ownership: this audit + `14_UPSTREAM_COMPAT.md`, with public-release gate in `20_RELEASE_CHECKLIST.md` per `55_PHASE7_DEFERRED_OWNER_MAP.md`. Every future upstream NovelAI diff must be checked here for model/sampler/request impact. Final Product Closure `54` does not implement provider capability changes. Reopen only with authoritative provider/upstream evidence or a proven rejection of a formal CCB-allowed request; no Desktop-only silent divergence.

Official NovelAI docs checked on 2026-10-07:

- Image Generation Models
- Sampling Methods
- Steps & Prompt Guidance
- FAQ / Opus V5 usage limits

The user-provided NovelAI Web screenshots are useful REF for control layout and observed current labels, but are not sufficient by themselves to define API ids or hidden provider validation rules.
