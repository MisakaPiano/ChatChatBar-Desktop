# Phase 7 final implementation and review evidence

Date: 2026-10-06. Branch: `feature/phase7-image-novelai`.

Final production-source checkpoint: `f8ca2619e67a975e392a7b4563b4ace834dca89a` (complete Studio integration at `2e06d30`, followed by the validated canvas/export-test correction).
Final documentation/checkpoint SHA is the feature HEAD containing this report; it does not change the packaged production sources.

## Control and baseline

- Starting/live desktop: `b3ecd41267906526e7b603972f7388e59c90648d`.
- Production baseline ancestor: `6737f0ed382170a7891d0bd5f7e4708ca2840009`; the entry-to-live interval was handoff/docs only.
- P7-A Project PASS: `37ea1e8b422c5b986c93cd43cce51f0f2d2b5d68`.
- P7-B Project PASS: `8f12870d401624d89b02bbb2ba34bf5f5f625c74`.
- Phase 6 stays closed. No merge into desktop; final Project review and consolidated user manual acceptance remain pending.
- Formal validated upstream stays **CCB 1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8**.
- The only authorized Prompt exception is the exact three-symbol official safety forward-port from `ace632cce58a3b5a57711e31990165d6a14e1c0f`, implemented in `bc2571e`. These three literals are not described as byte-identical to 1.4.1. NovelAI literals retain formal-baseline zero drift.
- `sync/1.4.4 @ 9b6378dbb595dd2f3ff5143a7a8e46653c99e721` remains parked and unmerged. No 354f151 identity wording, image-position, Package, Entity or provider-schema adoption.

## Durable commits

| Commit | Scope |
|---|---|
| `b4d351a` | Shared image/NovelAI models and policies |
| `37ea1e8` | Desktop image workspace, chat background/attachments, Character images and export-only PNG cover controls; P7-A |
| `bc2571e` | Approved exact upstream ace632 Prompt safety exception |
| `4acb6cd` | Windows-protected NovelAI credential UI/runtime hydration and linked vision |
| `a58ccfe` | Accepted guarded real smoke evidence |
| `512cd96` | Shared NovelAI Prompt/tag authority and Android facades |
| `8f12870` | NovelAI runtime, tag infrastructure and P7-B evidence |
| `78e6080` | Shared Studio state/design persistence, Vibe encoding, APNG and pixel algorithms |
| `c5063dc` | Shared catalog check/validation/index building; immutable Desktop update activation |
| `2e06d30` | Complete Desktop Studio, history/regeneration/guidance, automatic chat images, image tools and APNG playback |
| `f8ca261` | Atomic canvas undo/redo, export-confirmation regression correction and branch-wide whitespace check |

## Implemented boundary

- **Image foundation:** Desktop-owned resource deep copies, reusable image workspace and viewer, chat background, attachment persistence/preview and text-only-model linked vision. Character avatar/chatBackground/appearance-image pick/edit use owned resources. PNG cover crop/zoom/gradient/logo/title controls and replacement-cover source remain export-only Desktop state; Entity and Package contracts are unchanged.
- **Secret/runtime:** NovelAI password UI and Windows OS-protected SecretStore only. Ordinary JSON, errors and diagnostic output do not own credentials. Shared request/frame, model/size/seed/batch/cancellation/429 semantics remain authoritative; safe Desktop error surfaces omit provider bodies. Long Studio, design, catalog and chat-image work uses existing Desktop TaskRuntime admission, Stop and shutdown drain.
- **Studio:** Tools → NovelAI Studio contains persisted Prompt and per-model settings, model/default resolution, size/aspect/custom dimensions, seed, samples, steps, guidance, sampler and CFG rescale. Explicit continuous batches retain committed results on Stop. Account/cost refresh is explicit. Results and selectable history use lazy image loading, preview, delete confirmation and full/new-seed/seed-only restoration.
- **Prompt and tags:** shared designer/research/evidence, official Prompt authority, V5 natural-language routing, tokenizer, completion, dictionary and local translation consent. Fullscreen Prompt editing is isolated until confirmation. AI-design conversations persist initial/revision context, completed or interrupted turns, retry/regenerate/edit-branch state and explicit application. Reverse-image output remains a previewable candidate.
- **Metadata/regeneration:** generated images retain PNG bytes and shared launch recipes, actual seeds, style/base/extra/characters/negative/settings. PNG import has field selection and imports embedded guidance through owned copies. Chat image regeneration edits recorded metadata, preserves character centers, and appends a linked image message while retaining its source. History-to-guidance copies survive history deletion.
- **Guidance:** img2img, precise reference, encoded Vibe/cache, normalized strengths, focused-inpaint selection/paint mask, context planning and composition. Shared Lanczos/mask/blend policies own pixel semantics. V5 pauses incompatible references without clearing saved settings. Draft/undo and saved masks/regions survive reopening; reset/cancel keep original resources intact. Canvas undo/redo uses one bounded image/mask/focus snapshot so mixed edits, clear and reset restore in operation order.
- **Automatic chat images:** explicit opt-in; shared deterministic completion policy checks raw and persisted reply content. Missing completion, refusal, transport failure, truncation, Stop, edited/deleted source and disabled state prevent handoff. Source equality and durable authority are checked again before generation and commit. There is no eligibility LLM call. Generated assistant images remain outside main-chat multimodal history. Skips/failures are transient task/UI state, not false persisted success messages.
- **Tools:** rotation, region mosaic, metadata stripping, save/copy/reveal and APNG disguise/restore. Static clipboard exposes image flavor; animation copies the original file. The bundled Skia PNG decoder exposes only one APNG frame, so Desktop playback uses the shared CRC/sequence-validated frame extractor and a platform blend/disposal adapter. Animation timing and cover exclusion are tested; static sentinel restore preserves RGBA pixels.
- **Catalog updates:** the official GitHub checker, structural validation and ranked-index algorithm are shared. Android SQLite and Desktop JDBC are adapters. Desktop verifies Git blob SHA/size and builds an immutable index before atomically replacing the current manifest. Corrupt/changed authority prevents activation; prior immutable generations remain available to readers. Cancellation interrupts download/index preparation. No NovelAI credential participates.

## Shared ownership and data safety

SharedCore owns Studio draft/undo/checkpoint/history transforms, design conversation repository and turn runner, HTTP/Prompt/tag policies, Vibe encoding, APNG chunk protocol, raster resampling/mask/blend algorithms and catalog update/index policy. Android delegates through Context/Bitmap/SQLite/proxy adapters. Desktop adds AWT/native file dialogs/JDBC/clipboard/Explorer and DPAPI adapters; no second Android/Desktop Prompt or image-policy implementation.

Image writes follow **new owned resource → durable authority → obsolete exact-candidate cleanup**. Unknown/corrupt authority retains resources. Selected history deletion removes durable references before cleanup; no root sweep or broad deletion. Deep-copied guidance and undo sources participate in reference checks. Temporary processing files are exact task-created candidates. Shared `ImageCanvasHistory` also supplies Android/Desktop undo/redo transitions. No upstream Entity or Package schema was changed.

## Validation

The consolidated gate was run once, followed only by affected repairs/remaining tasks:

| Gate | Result |
|---|---|
| sharedCore full | **103 suites / 685 tests PASS**, 0 failures/errors/skipped |
| desktopApp full | **104 suites / 952 tests**; 951 initial PASS, one obsolete PNG export expectation corrected; its **25-test management class PASS**, 0 failures/errors/skipped |
| Android full JVM | **135 suites / 802 tests PASS**, 0 failures/errors/skipped |
| Desktop/shared/Android affected compile | PASS in the consolidated/remaining-task invocations |
| Post-gate canvas correction | **Desktop image 10 tests + Android canvas 2 tests PASS**, affected shared/Desktop/Android compiles PASS |
| Diff checks | **PASS**, including the entire `b3ecd412..f8ca261` feature diff |

The full Desktop XML report was retained before targeted reruns. Reports/logs are local under `app/desktopApp/build/phase7-final-evidence/` and `phase7-final-gate*.log`. Full suites were not repeated after every internal commit. Three trailing blank lines in earlier moved shared files were normalized when checking the entire feature diff.

Commands used JDK 17 from `app/`, with `--offline --console=plain --no-build-cache --no-configuration-cache --no-daemon --max-workers=1`, `-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8` and `-Pkotlin.compiler.execution.strategy=in-process`. The initial consolidated invocation ran `:sharedCore:test --rerun :desktopApp:test --rerun :app:testDebugUnitTest --rerun :desktopApp:compileKotlin :app:compileDebugKotlin`. Gradle stopped after the stale Desktop assertion; the next invocation reran only its management class and executed the remaining full Android suite/compiles.

Focused P7-C validation included shared Studio/pixel/APNG tests, Android repository/design/inpaint/index tests and Desktop draft/restart/corrupt-authority, history-to-guidance ownership, fake Vibe/cache/error redaction, catalog hash/ranking/restart/corrupt-pointer, automatic completion/edit gating, durable chat-image linkage and GIF/APNG timing/pixel tests.

The optional `:desktopApp:verifyPhase7LocalFixture` runner reused the already accepted V4.5 output from `.phase7-live-data` without loading credentials or creating an HTTP client. Persistence, restart, metadata reading, regeneration preparation, decoding and APNG round-trip passed; source hash stayed unchanged.

Development failures were resolved: a test selector named nonexistent Android classes; the correct TagIndexPreparation test passed. Native Skia APNG decoding was single-frame, so shared frame extraction plus Desktop playback was implemented. A build started before the final IDAT/fdAT correction was rerun against the corrected source. No fallback was reported as successful animation. The full gate also exposed a pre-P7 management export test that expected immediate PNG output; it now exercises the P7-A preview/explicit-confirmation boundary before checking the exported Package.

## Distribution and smoke

- **Isolated distributable: PASS**, built from production-source checkpoint `f8ca2619e67a975e392a7b4563b4ace834dca89a` with `:desktopApp:createDistributable` and an isolated build-directory init script.
- Executable: `H:\ChatChatBar-Desktop\app\desktopApp\build\phase7-final-distribution\compose\binaries\main\app\ChatChatBarDesktop\ChatChatBarDesktop.exe`.
- Executable SHA-256: `E5D45E5389E7584B9D8477EFE34543DE1069E6550560A3A3C53D164152958B8F`.
- **Launch smoke: PASS**. One hidden launch in a fresh isolated profile created the application's `SunAwtFrame` window titled `ChatChatBar`. The jpackage launcher and its child were identified by exact executable path and parent PID; a guarded `WM_CLOSE` was sent only to that child's main window. Both processes exited normally without a forced kill. stdout and stderr were each 0 bytes; no generation action occurred.
- Evidence limitation: the initial harness inspected only the launcher's `MainWindowHandle`, which was zero. Inspection of the same launch's child found the main window; there was no second launch. Numeric exit codes were unavailable after reacquiring the process objects, so a numeric exit-code assertion is not claimed.
- Local evidence: `app/desktopApp/build/phase7-final-package.log`, `phase7-launch-smoke-result.json` and isolated profile `phase7-launch-smoke-fc1f3d9b31594f1f869b60eb29147983` under the same build directory.

The isolated runtime explicitly includes `java.sql` and `jdk.unsupported` for SQLite/JDBC. The launch profile and its LOCALAPPDATA are isolated from user data/credentials. No UI action that sends image generation is used in smoke. Automated visual acceptance is not claimed.

## Real NovelAI freeze

Accepted live evidence remains **1/8** requests, from P7-B. **P7-C sent 0 real generation requests.** No V5, img2img, guidance/reference, Vibe, inpaint, batch, regeneration or automatic-image live request was made. These paths use shared request-shape tests, fake transports and local persisted fixtures. The 8-request fuse is a ceiling; further live generation requires fresh explicit user authorization.

## Consolidated manual acceptance checklist (pending)

Use an isolated profile and local copies. These steps do not authorize additional real generation.

1. Pick/preview/remove a chat attachment; reopen the app and verify its persisted image and background. Check ordinary text chat and settings navigation. With separately configured test models, verify direct vision and linked-vision routing.
2. Edit Character avatar/background/appearance image. Export a CCB PNG with crop/zoom and a replacement cover; verify the Character background and exported Package fields stay unchanged.
3. Open Studio, edit model/settings/Prompt/characters with completion and translation, expand/cancel/confirm a field, then restart. Verify draft restoration, clipboard import and undo.
4. Reuse the accepted output locally: preview/save/copy/reveal, inspect metadata, import selected fields, and prepare full/new-seed/seed-only regeneration. Do not press Generate with the real account.
5. Load fixture history; multi-select/delete a copy while retaining another copy as guidance. Verify preview/history restart behavior and exact-candidate cleanup.
6. Select img2img/precise/Vibe/inpaint fixtures, edit mask/focus, reopen the editor and undo/reset. Switch V4.5/V5 and back; paused references must retain their settings. No real Generate.
7. Prepare a chat image regeneration draft; verify style/base/negative/character editing and cancel. Automatic-image opt-in remains off for the real account; fake transport tests provide completion/failure/Stop evidence.
8. Mosaic/rotate a copy; undo/reset; disguise/restore a static and animated fixture. Verify real animation, duration, restored source pixels, original preservation and save/copy/reveal behavior.
9. Check catalog update with network access only if desired (public GitHub catalog; no NovelAI request). Observe download/index progress, Stop and restart; failures must stay visible and retain the active catalog.
10. Close normally with no active tasks; verify a clean restart. Final Project review and user acceptance are separate from this automated evidence.

## Review status

**READY FOR PROJECT PHASE-7 REVIEW**

Implementation, consolidated automated validation, isolated distribution, launch smoke and CURRENT-document reconciliation are complete. No implementation blocker remains. Final Project review and consolidated user manual acceptance remain pending; this is not a Phase-7 acceptance or merge into `desktop`. The final documentation checkpoint preserves the packaged production-source revision above.
