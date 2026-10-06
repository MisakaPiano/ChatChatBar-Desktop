# 47 — Phase 7 R2 Re-audit Amendment

Applies to existing `40_PHASE7_R2_UX_CONTRACT.md`.

Current inspected R2: `feature/phase7-image-novelai @ 109403ca1dc1c037a85943985cd13981733aa58a`.

This amendment is additive. **Do not restart R2, discard current work, or reimplement already-correct R2 surfaces.**

Before declaring `READY FOR PROJECT PHASE-7 R2 REVIEW`, close all GAP rows in `46_PHASE7_IMAGE_SYSTEM_REAUDIT_MATRIX.md`.

## A1 — Multiple chat attachments

Formal CCB permits repeated image picks into one pending list.

Remove Desktop's single-pending-image restriction.

Requirements:
- multiple thumbnails;
- independent remove;
- text+images and image-only send;
- expanded composer uses same list;
- persist all message images;
- retain authoritative first-USER-image model-request policy unchanged.

## A2 — Message image editing

Formal message edit loads existing images and allows add/remove.

Desktop must:
- show existing images;
- add/remove images;
- update matching generated metadata;
- persist edited authority before exact cleanup;
- retain files on indeterminate outcome.

No ChatMessage schema change.

## A3 — Assistant-message manual Generate Image

Formal eligible Assistant messages expose Generate Image.

Current R2 contains `DesktopChatImageRegeneration.generateFromAssistant()` but Project source review found no UI invocation.

Wire a discoverable message action to the existing runtime. Do not use the linked-vision control as a substitute.

## A4 — Secondary `生图要求`

Provide Desktop equivalent of upstream secondary/long-press generation action:
- one-run image-content hint;
- session image Prompt preference;
- persistence option/semantics matching upstream;
- Generate / Cancel.

Reuse existing Prompt Designer builders and session fields. No new model Prompt text.

## A5 — Source-message task presentation

`DesktopTaskEntry.targetMessageId` already exists.

Use it to render manual/automatic chat-image progress, Stop and terminal state with the relevant source Assistant message. A global fallback may remain, but generic composer-only task status is insufficient.

## A6 — Individual chat-image deletion

Allow deleting one selected image:
- remove path from `ChatMessage.images`;
- remove matching `GeneratedImageMetadata`;
- persist first;
- follow upstream validity/deletion behavior if nothing remains;
- exact-candidate cleanup after commit;
- no root sweep.

## A7 — Direct Studio Guidance action

Formal `ImagePromptToolScreen` has a direct Guidance top action in addition to Current/History Image → Use as.

Add a clear Studio toolbar Guidance entry and keep both image-oriented routes.

## A8 — Account / cost parity

Using the existing account-service seam and fake tests:
- fetch account on Studio entry;
- show Anlas;
- show approximate V5 allowance/images when available;
- perform formal visible post-success local adjustment and server reconciliation;
- keep account-fetch failures visible without falsely claiming free generation.

R2 authorizes **0 real NovelAI image-generation requests**.

## A9 — History parity

Add/reuse formal behavior:
- positive-Prompt search;
- date filter;
- fold/albums + preference;
- detail recipe/seed/model/settings/guidance;
- Full / New Seed / Seed Only;
- Use as Guidance;
- deletion.

Prefer sharing/moving JVM-neutral history filter/folding policy rather than making a second Desktop policy.

If exact reproduction needs missing Guidance source, block or explicitly confirm degraded behavior before applying; do not call it an unconditional full reproduction.

## A10 — Reverse-Prompt progress

Expose equivalent visible:
- stage/status;
- streamed result/content;
- reasoning when provided;
- Stop;
- retry;
- final candidate;
- Apply.

Keep existing Prompt/transport pipeline unchanged.

## Documentation correction

`44_PHASE7_R2_REVIEW.md` is in-progress evidence. Correct any statement that claims a workflow is mapped before the actual UI call/wiring exists.

Specifically, do not claim Assistant manual Generate Image parity until the message action invokes the runtime.

## Hard boundaries

Still prohibited:
- existing Prompt literal changes;
- Package/schema changes;
- upstream Entity changes for UI convenience;
- legacy automatic-image AI judge;
- automatic eligibility semantic rewrites;
- NovelAI HTTP semantic rewrites;
- real NovelAI generation;
- parked `sync/1.4.4`;
- Phase 8.

## Validation additions

Add focused tests for:
- multi-pending attachment vs first-image request separation;
- edit-message image add/remove and cleanup;
- Assistant manual-generation action wiring;
- per-run generation requirements mapping;
- task target-message UI mapping;
- individual image deletion;
- direct Guidance entry;
- fake account entry/reconcile state;
- History filter/date/fold/detail/reproduction protection;
- reverse progress/reasoning UI state.

Then run the original R2 required Desktop/full/affected tests, compile, diff-check, rebuild distributable, and launch smoke.

Only then report:
`READY FOR PROJECT PHASE-7 R2 REVIEW`.
