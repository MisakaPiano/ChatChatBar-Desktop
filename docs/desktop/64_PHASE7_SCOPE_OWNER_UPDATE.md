# 64 — Phase 7 Scope / Owner Update

This document updates the earlier deferred map for user-authorized items.

## Moved into Phase 7 now

The user explicitly prefers completing image-workspace-local Desktop conveniences now when they do not create harmful future coupling.

Move into P7:
- current-window Studio image drag/drop
- chat composer image drag/drop
- image clipboard paste
- multi-file attachment picker
- pending attachment drag reorder
- canonical ChatBar APNG-disguise automatic restore at chat ingress
- role compact visual collapse separate from current-upstream enabled semantic
- V5 usage percentage bar
- caret-aware Tag inspection

These are no longer deferred to P15/P17.

## Still P15

- Windows file association
- Open With
- URI scheme
- Explorer shell registration
- installer/updater/tray/OS notifications

Window-local drag/drop is not the same as shell integration.

## Still P17

- full Studio generation preset file format/import/export
- broad all-app Settings/Editor IA consistency audit
- general onboarding/beta polish

Do not invent a new preset schema in P7.

## Still P13

- Moments / generated social feed
- Moments scheduler/image workflow

## Provider capability track unchanged

Continue:
`49_NOVELAI_PROVIDER_CAPABILITY_DRIFT_AUDIT.md`
+ `14_UPSTREAM_COMPAT.md`
+ release gate.

Still not authorized:
- general new NovelAI models
- sampler catalog expansion
- model-specific sampler filtering based on guesses
- refill-rate scraping/inference

## Current-upstream selective exceptions

After implementation, `14_UPSTREAM_COMPAT.md` must list the image-domain official forward ports in `60`.

Do not change `10_UPSTREAM_BASELINE.json` validated baseline.
