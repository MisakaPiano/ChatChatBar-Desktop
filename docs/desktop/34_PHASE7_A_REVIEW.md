# Phase 7 — Milestone A review checkpoint

Scope: Image Foundation / Core Image UX. This is an internal Phase-7 milestone, not phase close or user visual acceptance.

## Git and authority

- Feature branch: `feature/phase7-image-novelai`.
- Starting local/remote Desktop: `b3ecd41267906526e7b603972f7388e59c90648d`.
- Entry fetched origin, checked out a clean desktop worktree, and fast-forwarded only. Production ancestor `6737f0ed382170a7891d0bd5f7e4708ca2840009` verified; all 13 intervening commits touched Desktop docs only.
- Formal baseline remains CCB 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`. Phase 6 remains closed. Parked sync/1.4.4 is unchanged.
- First durable commit: `b4d351a` — shared image policies, NovelAI types/runtime extraction and Android compatibility delegates. This extraction was already underway when the A/B review gates were added; it is retained, not a claim that B is complete.

## A implementation

- Static PNG/JPEG/WebP imports copy validated encoded bytes into root-relative owned resources. Embedded metadata survives ordinary attachment import. Cropping creates a new PNG; original files are not overwritten.
- `DesktopChatImages` coordinates image writes plus message/session persistence through the existing app-data gate. Failure checks uncached strict commit evidence before rollback. Indeterminate outcomes retain files. Pending picker bytes remain session-local in memory and are removable before sending.
- `DesktopOwnedImageCleanup` receives explicit obsolete candidates only. Under exclusive maintenance it checks every entity/draft/singleton JSON before deleting an unreferenced owned file. Corrupt/unreadable authority stops cleanup visibly. Message deletion only nominates image files belonging to this new attachment owner. Existing Character document cleanup is preserved through the same reference check.
- CREATE_NEW failure no longer deletes a preexisting colliding resource in durable or draft storage.
- `DesktopImageWorkspace` supplies drag/pan, wheel zoom, keyboard-accessible precision slider, original/square aspect, reset, apply and cancel. Re-crop is available on avatar, chatBackground and appearanceImage. Apply enters the existing Character draft/save transaction.
- Android and Desktop CCB PNG renderers plus Desktop workspace now use shared `centeredImageCrop` geometry. Preview/render coordinates are independent of display DPI. Static JPEG decode observes EXIF orientation, and display decode uses bounded sampling.
- Primary Chat renders session background, falling back to the Character background; global opacity is exposed in appearance and session settings. Baseline has no separate session opacity field. Background selection explicitly saves immediately; clearing returns to Character fallback.
- Chat supports picking/removing a pending image, image-only send, durable image messages, display, pager preview, mouse zoom/pan, arrow-key navigation, PNG save, image clipboard and Explorer reveal. Sending uses existing TaskRuntime stop/shutdown ownership.
- Shared `ChatImageRequestPolicy` preserves formal first-USER-attachment selection. Assistant/generated images do not enter multimodal history. The UI admits one pending image; existing batch messages remain displayable.
- CCB PNG export opens a live cover adjustment dialog: crop center X/Y, zoom, gradient height/strength, logo size and title size. Export-only replacement bytes affect visible pixels while the captured Package and its original image resources remain unchanged. No Entity/Package field was added.

## Explicit boundaries for subsequent Phase-7 work

- A supports direct multimodal chat models. Text-only models are rejected before send when an image is pending; their linked-vision description path must be connected with the auxiliary/Prompt adapter work after A review. It must not silently drop the image or introduce a second Prompt authority.
- GIF/APNG animation processing, mosaic, image-generation metadata UI and editable regeneration remain in the already specified later image-tools/Studio scope. Static crop rejects animation rather than flattening it.
- NovelAI SecretStore/settings, Desktop runtime wiring, Prompt Designer, catalogs/dictionary, V5 routing and Studio remain open. Shared extraction alone does not complete these features.
- Pending attachment selections are not restart-persistent; committed image messages/backgrounds are. Full visual/high-DPI/manual acceptance is reserved for the consolidated Phase-7 session.
- No paid NovelAI request, automated GUI acceptance, device install, distributable or phase-close full regression was run for A.

## Focused evidence

- Shared: image-policy/runtime suites plus `ChatImageRequestPolicyTest` — 84 tests, zero failures.
- Desktop: `DesktopPhase7ImagesTest`, Character PNG renderer/editor, Primary Chat controller, real chat runtime and typed transfer suites — 115 tests, zero failures.
- Android JVM: `NovelAiStudioModelsTest`, `NovelAiImageFeatureTest`, `AutomaticChatImagePolicyTest` — 64 tests, zero failures.
- Shared/Desktop Kotlin and Android Debug Kotlin compilation passed. `git diff --check` passed.
- New regressions cover DPI-invariant crop geometry/source preservation; batch rollback and unrelated-file retention; post-commit failure and restart; background replacement/shared references; corrupt authority retention; JPEG payload and exactly-once image-only current turn; export cover/Package isolation; embedded metadata; EXIF rotation; and filename collision safety.
- Tests use inline images/entities plus the existing HTTP protocol fixture moved/copied with its suite; no preset content assertions were introduced. `PromptTemplates.kt`, Character/Session/AppSettings Entity fields and Package schema are unchanged.

## Review / continuation

Review ownership/deletion and ambiguous commit outcomes, transient export state, crop foundation, shared/platform boundaries, and the explicit remaining vision adapter seam. Current parity/roadmap documents are intentionally not reconciled at this internal gate.

After Project P7-A review PASS, continue the same branch and Phase-7 task toward the requested P7-B gate. Do not create a new planning task or reopen Phase 6/parked sync.
