---
name: chatbar-moments
description: Maintain ChatBar's 朋友圈/Moments module. Use when changing the Moments root tab, moment generation policy, scheduler/background alarms, moment prompts, NovelAI handoff for moment images, moment storage, likes/deletion, unread red dots, debug generation, or moment image preview actions.
---

# ChatBar Moments

Desktop `DesktopMomentsSettings` lives in management settings' own `MOMENTS` / 朋友圈 group, separately from usage statistics. The retained pane keeps the existing app-scoped controller; moving between settings groups does not restart its scheduler. Automatic backup settings remain in Data, not this group.

Desktop MomentStore.begin/edit retains the selected author's owned image reference, without copying it into a separate avatar file. Typed card overwrite/deletion must therefore use the AppContainer's Core postcommit full-reference cleanup, preserving existing `moment_posts.senderAvatar` and image/version references. Historical missing-avatar transfer tolerance is not permission to delete a currently valid referenced image. The actual container retention regression uses Store-produced posts and both Core deletion/overwrite.

## Scope

Use this skill before editing 朋友圈 code or docs:

- Shared `app/sharedCore/src/main/kotlin/com/example/chatbar/data/local/entity/MomentEntities.kt`
- Shared `app/sharedCore/src/main/kotlin/com/example/chatbar/data/repository/MomentRepository.kt`
- `app/app/src/main/java/com/example/chatbar/domain/moment/`
- `app/app/src/main/java/com/example/chatbar/ui/moments/`
- 朋友圈 settings, root Tab wiring, unread badges, debug UI, or background alarm code
- 朋友圈 prompt builders in `PromptTemplates.kt`
- 朋友圈 image preview/save/share/set-card-image behavior

Also read `chatbar-novelai-prompt` before changing NovelAI prompt construction, `chatbar-image-generation-runtime` before changing shared image generation/regeneration behavior, `chatbar-background-work-runtime` before changing foreground-service/network protection, and `chatbar-shadcn-compose` before changing Compose UI.

## Product Rules

- Desktop album search/date/granularity/group/card selection lives in the controller's runtime-only `DesktopMomentAlbumSelection`. Removing the album while locating a post or leaving its workspace must not recreate defaults; no new disk preference is introduced. The actual album keeps `rememberLazyGridState` and resets it on logical filter changes, even if the matched post list stays equal. Navigation still passes only the original post ID through `MomentAlbumPolicy.timelineIndex`; query changes never edit posts. `DesktopMomentAlbumNavigationStateTest` exercises the actual parent Panel round trip and a scrolled unchanged-result query.

- Locating an album post scrolls the full timeline by its current ID and highlights that feed card with the theme primary border for 3.5 seconds. The timer remains outside the album's early return; a later different ID replaces the highlight without an older timer clearing it. Missing IDs retain the existing visible refusal and never select another row. `DesktopMomentAlbumLocateHighlightTest` covers actual Panel selection/expiry and a stale tile after deletion.

- Global `momentsEnabled` defaults false. When off, hide root 朋友圈 Tab and do not generate.
- Global `momentsImagesEnabled` defaults true (auto image generation). When off, or when NovelAI Token/image model is unavailable, posts are text-only and generated with the pure-text prompt.
- Character `momentsEnabled` defaults true. Existing persisted characters with explicit false stay disabled.
- Selected official147 generation proceeds directly to copy generation without a timing-judge request or shouldPost gates. Existing judge prompts/checkpoint fields remain compatible but are not executed by normal/debug generation; never automatically reopen old terminal SKIPPED tasks.
- Active gate: session has user/AI exchange within 48 hours.
- Generation phase sends role/card info excluding opening greetings, long-term memory, previous moment, and recent 3 full messages. Do not truncate message bodies.
- Multi-character card: moment AI chooses sender; no comment/reply simulation for other roles in MCP version.
- Moments are front-end immersion only. Do not enter chat mainline, write long-term memory, add comments, or add transfer-to-chat entry.
- Private moments show lock/`仅你可见`; base like count fixed 0.

## Scheduler Rules

- Desktop existing-image arrows and explicit version selection stay usable while a worker generates. `replaceImage` permits selection-only changes after validating identical canonical retained candidates and original post identity/content, then atomically appends and selects this newly generated image. Failure/cancellation/stale refusal leaves the saved selection unchanged; changed candidate sets/content or deleted posts still refuse. Removal remains separately guarded. The app-owned worker retains its registered creative child/normal data admission, without holding chat's destructive-message reservation across network work; publication rechecks the original session/card binding. Shared prompts/private-like policy are unchanged.

- Desktop regenerated images append typed `MomentPost.desktopImageVersions` in the original atomic post owner. Successful append selects its exact new ID and matching Prompt/metadata/brief in the same durable write, retaining all older images; viewing or loading never changes selection. Selection/removal recheck the original image source including all versions. `DesktopMomentFeedCard` uses the same compact `DesktopVersionControls` as chat alternatives, selecting the retained ID through the original controller/store; one image shows1/1 with disabled directions. Its secondary More → 管理图片版本 panel retains preview/select/confirmed removal; removing current chooses the newest remaining candidate only after confirmation. Candidate cleanup receipts still run after worker completion; live JSON references protect retained versions. Post deletion includes every version/avatar with a bounded v2 receipt while old v1 receipts remain readable. `DesktopMomentImageVersionsTest` covers four retained candidates, stale completion, shared resources and deletion; `DesktopMomentImageCompletionSelectionTest` holds the real controller's image work through selection/finish/reopen and failure/cancel/source refusal, including actual feed image identity/count. `DesktopMomentImageVersionsPanelTest` retains narrow/wide manual selection and removal coverage.

- Desktop author/avatar navigation resolves the retained post ID through `DesktopMomentsController.linkedTarget`: left click selects only that post's exact session, refusing missing or relinked sessions; context actions open its exact card or bring the original card-level Moments switch into view. Duplicate names never select a recent session or create one. `DesktopCharacterEditorController.openMoments` preserves a dirty draft of the same card and uses the ordinary leave guard for another card.
- The bounded Desktop feed keeps the avatar gutter separate from the aligned author/body/image column, edit at the author row and compact timestamp/private/regenerate/delete/like actions below; copy and version management stay behind More. Regenerate opens the original editable draft and confirmation, not an immediate request. The album and timeline retain separate scroll/navigation owners.
- `DesktopMomentAlbum` is a separate Moments page, using shared official `MomentAlbumPolicy/State/Filter` for card folders, query and local generation dates at day/month/year granularity. The original full timeline retains its scroll owner; album navigation carries a post ID and resolves its current full-timeline index. NAI generation history remains separate. Ordinary feed maintenance/debug actions live behind More, while active Stop remains immediately available.

- Desktop task-center workRows bind the original generation/image worker Job and exact stopTask UUID, including its existing NonCancellable image-cleanup and refresh tail. The shared withCardDesign child remains registered for admission/drain but uses publishWorkRow=false to avoid a premature duplicate generic task. Cleanup/record-write uncertainty remains UNCONFIRMED; successful SKIPPED work is removed without a published-post success notice, and failed retry remains FAILED. MOMENTS_HOME only returns to the original feature page through Shell's existing guards; disabled Moments refuses instead of silently landing in Chat. It never generates/retries or claims exact post focus. Scheduler admission, persisted task/checkpoint policy and original stop/pause behavior are unchanged.

- Desktop `DesktopMomentsController` owns the app-lifetime worker and explicit local admission; `DesktopMomentGeneration` adapts the shared core with lazy NAI owners and compiled memory. `DesktopMomentTextGateway` uses `streamMoment`/`completeMoment`, preserving selected model parameters and the original streaming versus complete request modes. These owners are lazy in `DesktopAppContainer`; merely loading settings/posts never opens provider clients.
- `DesktopMomentStore` serializes UI/generation mutations and persists task→reserved post identity before requests, then placeholder checkpoints before the next phase. RUNNING is never automatically reset to PENDING; explicit `reconcileSaved` only links a matching already-saved final without networking. Late completion cannot recreate a deleted placeholder. `DesktopMomentImageCleanup` writes exact-candidate receipts before deletion/image materialization and runs the production full-reference cleaner after normal admission is released. Its private receipts live under `backups/.moment-image-cleanup`, outside transport entities.
- Desktop `DesktopMomentAdmissionStore` keeps exact approved PENDING task hashes outside the data/transport root. Its active grant binds the resolved root identity and local restore epoch; normal reopen resumes only matching tasks, while copied roots and same-root full restores cannot inherit approval. `DesktopFullBackupCommitJournal` rotates `backups/.moment-admission-epoch` before live restore writes. Unicode task IDs remain valid single filename components. Unread uses existing `lastSeenMomentsAt`, advancing only while the timeline is viewed.
- `DesktopMomentsPanel` exposes generation, checkpoint retry, local likes, content/author edit, copy, confirmed deletion, schedule history and full debug exchanges. Author selection uses shared `MomentPostEditing` stable card/person identities. The original-sender option passes null and retains the saved snapshot even with a missing card. New card/person selections are resolved from their stable key in the latest strict card read inside `DesktopMomentStore.edit`'s mutation admission; removed/corrupt/ambiguous choices refuse before writing, while current names/avatars replace stale editor fields. Text-only edits keep the original sender. Media, task and post IDs remain unchanged. `DesktopMomentsControllerTest` covers stale selection refusal/current fields/original retention through actual Controller/Store. RUNNING never auto-replays; missing-placeholder recovery requires explicit confirmation of uncertain prior cost and preserves its reserved identity. `DesktopMomentImages` provides image/debug ports; the production generator resolves the existing selected models. Viewer extensions preserve its standard controls and add image regeneration/card-image actions. Card-image commit runs on IO under exclusive admission, prepares old-image cleanup before replacement and finishes cleanup after releasing admission.

- `MomentGenerationCore` shares generation through `MomentTextGateway` and lazy image/designer suppliers, image-save and compiled-memory ports. Selected147 removes judge/shouldPost gates from normal/debug and applies card default image settings to both image requests. Android `MomentGenerationService` retains `withAiTaskRun` around each public generation call; `AndroidMomentTextGateway` maps the original task contexts and transport defaults. The shared core does not own platform admission, credentials, scheduling or persistence. Text-only/checkpoint paths must not open image clients; completed draft/design checkpoints remain reusable even with an old negative decision. Debug cancellation/refusal propagates.

- `MomentEntities`, `MomentRepository` and `MomentPolicy` have one shared JVM-neutral authority; Android imports/FQNs remain unchanged. `MomentSchedulePreparation` shares only the original future-record planning and active-session selection. Android `MomentScheduler` retains its mutex, global enable guard, alarm cancellation, due-task generation and logging, delegating future planning via repository ports. Desktop retains a task's existing session binding; only a missing original session falls back to its card's most recent active session.

- Candidate frequency: per enabled card defaults to every 2-13 hours and is user-adjustable in settings.
- When frequency settings change, delete only future pending schedule tasks and regenerate them with the new range; do not delete due/running/completed/failed history.
- Limits: per-card max 4 posts/day; global max 18 posts/day.
- Catch-up generation is not separately capped; process due scheduled items one by one.
- Count pending, completed, and failed tasks when enforcing daily schedule limits.
- Do not double-count a generated post and its linked task for daily limits; count orphan posts only for legacy/debug data without a task.
- Moments do not generate while app is not running. Do not use boot/package/alarm receivers to start generation in background.
- `MomentScheduler.runOnce()` is foreground/startup catch-up path: cancel legacy alarms, ensure schedules, process due tasks, then ensure schedules again.
- `ensureFutureSchedules()` creates real pending schedule records for preview/debug and scheduler preparation. It must not process due tasks.
- `ensureFutureSchedules()` must not schedule background alarms.
- Debug timeline may display next 12 hours, but do not silently change underlying schedule horizon without checking scheduler behavior.

## Fast Investigation Patterns

- Empty future preview: check global `momentsEnabled`, per-card `momentsEnabled`, active-session gate, pending task rows, preview window length, and daily limit counts before changing generation.
- Default enable changes require all layers: entity default, edit/create ViewModel initial value, JSON missing-field compatibility, and explicit persisted false preservation.
- Settings exposure changes require persisted `AppSettings`, domain normalization, scheduler/repository propagation, UI controls, and tests for old settings JSON.
- Frequency changes should invalidate future pending schedules only; keep due/running/completed/failed records as audit and limit history.
- Schedule limit bugs often come from counting linked task plus generated post twice; count linked task once and count orphan posts only for legacy/debug data.
- Dependent UI should gate on global feature enable while keeping the global switch always reachable.

## Prompt Rules

- Original `MOMENT_*` literals and builders have one shared `MomentPromptAuthority`; Android `PromptTemplates` retains facades/header symbols and fingerprints this source. NovelAI image-input facades continue to delegate to `NovelAiPromptAuthority`. Generation/checkpoint/progress DTOs are shared `MomentGenerationModels`; the generation service remains Android-owned until gateway/storage/lifetime adaptation.
- The five original `MOMENT_*` literals remain unchanged; selected147 removes their timing-judge call site, adds sender editing and applies card image settings. Source implementation and exact-source test evidence remain separate from a global baseline upgrade.

- Normal/debug generation requests use MOMENT_GENERATION; historical judge helpers remain unused. Typed stream errors stop parsing; failed generation remains a visible failure/placeholder through existing orchestration.

- Moment judge/copy resolves `session.modelId > global default chat`; image research/design resolves `session.imageModelId > global default image`. Scheduler, debug, retry and on-demand image entry points use this order and retain selected model parameters/thinking. Only the NovelAI rendering model has a character-card default. Existing resolver behavior for stale IDs remains in effect.
- Debug generation must expose full AI inputs and outputs.
- Moment copy: 0-60 Chinese characters, short, private, suggestive, like an accidental life fragment. Do not recap chat logs.
- Text-only posts (image gen off or no token) use `PromptTemplates.momentGenerationTextSystemPrompt` and target ~40-90 Chinese characters; `MomentGenerationService` picks the prompt and the `compactMomentText` hard cap (`IMAGE_MOMENT_MAX_LENGTH=60` / `TEXT_ONLY_MOMENT_MAX_LENGTH=150`) based on an internal `textOnlyPost` flag. Keep the hard cap well above the prompt's requested range so it only catches AI that does not follow instructions; never tune the hard cap to the target range (that would clip normal output). The pure-text prompt still emits a hidden `imageBrief` so on-demand image design can reuse `designForMoment`.

## Image Rules

- Moment AI outputs image intent/brief only.
- If NovelAI token is not configured, save text-only moment instead of failing image generation.
- NovelAI prompt design uses shared `NOVELAI_IMAGE_PROMPT_SYSTEM` via `PromptTemplates.novelAiImagePromptSystem(...)`.
- Do not add `NOVELAI_IMAGE_PROMPT_MOMENT_TEMPLATE` or feature-specific NovelAI system prompts.
- If moments need visual guidance, add only small modifiers: photo style, private/life-slice feeling, composition, candid/low-angle/door-gap/mirror/distant/phone snapshot when suitable.
- Text/image generation failure should create a visible placeholder moment with failure reason and retry action; do not hide primary failure with a success-looking fallback.
- Failed placeholders persist completed generation checkpoints. Retry resumes after completed draft and NovelAI Prompt-design phases instead of repeating them; legacy decision fields remain readable and do not block generation.
- Generated images persist full `GeneratedImageMetadata`. Regeneration reuses `NovelAiImageRegenerationDialog`, exposes editable main/character/negative prompts, preserves original dimensions, and always requests a new seed.
- New moment images use `NovelAiImageSizePolicy.resolve(global novelAiImageAspectRatio, SQUARE)` across scheduled generation, checkpoint retry, debug, and on-demand generation. Pass the setting into `MomentGenerationService`; reject invalid nonblank ratios visibly. Blank settings retain the original square default; AI-chosen presets do not override this policy. Existing-image regeneration retains its original dimensions unless the user edits them; timeline thumbnails remain square crops, and full preview shows the original image.
- Moment generation, retry, debug, on-demand generation, and regeneration resolve the NovelAI model as `ChatSession.novelAiImageModel` > `CharacterCard.defaultNovelAiImageModel` > global `AppSettings.novelAiImageModel`.
- Legacy moment images load regeneration data from persisted metadata, then PNG metadata, then `imagePrompt` plus decoded image dimensions.
- On-demand image generation for a text-only post reuses `novelAiPromptDesigner.designForMoment(card, post.imageBrief, ...)` then `novelAiImageService.generate(...)`, persists the new path+metadata before replacing/creating, and streams design text + image progress live. Do not change the post text/content.

## UI Rules

- Root tabs when enabled: `聊天 / 朋友圈 / 社区 / 管理`.
- Management settings must keep the global enable switch visible; detailed 朋友圈 settings and debug generation sections show only when global `momentsEnabled` is on.
- Timeline should resemble WeChat/QQ Moments: white background, avatar, nickname, copy, single image, time, like button, like count.
- No comment input, no comment list, no reply-to-chat, no long-term-memory entry.
- Like toggles local state. Public moment display count changes with local like; private moment base remains 0 and may show only local-liked state if product explicitly asks.
- Placeholder failed moments show retry action and stream retry progress; they should not look like successful posts.
- A non-placeholder post whose `imagePath` is blank shows a dark `未生成图片` placeholder (lock icon + 点击查看私密图片) instead of an empty gap; tapping it triggers on-demand generation that streams design text and image progress inline. Old posts with real images are not blanked and still open through the shared preview dialog.
- Bottom 朋友圈 and chat tabs show red dot when unread items exist.
- Deletion stays unobtrusive; tapping the delete icon opens a destructive confirmation dialog.
- Android's existing long-press edit/copy entry still uses text-only editing. Desktop's explicit edit window additionally selects the author through shared147 sender identities; keep post identity, image metadata, likes, visibility and generation history in both cases.
- Moment image interactions should reuse shared chat image preview module where possible: open large image, zoom, save/share, set as card avatar/background.

## Persistence

- JSON-file persistence goes through repository/storage patterns already in project.
- Keep entity migrations backward compatible for existing moment JSON.
- Deleting a moment should remove repository record and clean owned image files when safe.
- Deleting a character retains all historical posts, saved sender identity, images, likes, and completed task history. `DeletionCoordinator` protects files referenced by any post's `senderAvatar`/`imagePath` and calls `deletePendingTasksForCharacter` to remove only pending generation tasks. Missing-card posts remain visible; card-dependent generation still requires an existing card.
- Moment image regeneration keeps post identity/content/likes/time. Persist the replacement path and metadata before deleting the previous owned image; failures keep the previous image.
- Do not delete user-owned unrelated images.

## Verification

- From `app/`, run `.\gradlew.bat :app:compileDebugKotlin` for Kotlin changes.
- Run `.\gradlew.bat test` for policy, prompt assembly, repository, scheduler, or parsing changes.
- Run `powershell -ExecutionPolicy Bypass -File .\ci.ps1 -SkipAssemble` for UI, navigation, background alarm, Android API, or shared behavior changes.
- Add/update JVM tests around `PromptTemplates`, `MomentPolicy`, scheduler limits, repository likes/delete, and debug input assembly when touched.

## Desktop physical image observations

- Manual post-image generation/regeneration in `DesktopMomentGeneration` applies the card's optional `defaultImageGenerationSettings` to the original model-resolved new-seed settings, matching the shared scheduled/debug core. An explicit regeneration draft still owns Prompt and dimensions; that draft does not carry a full historical sampling recipe. This does not change Studio/history settings priority or implement multiple retained Moment image versions.

- DesktopMomentGeneration injects the optional original MomentGenerationCore.imageGeneration flow adapter for scheduled and debug generation; manual post-image generation reuses that same flow. Android keeps the default original service. Final raster validation/counting is metadata-only; original Core retries, checkpoints, save and error results remain authoritative. Completed network output is recorded before later local materialization.
- Each generation entry captures the selected NovelAI account/key together through DesktopAccountStore (or preserves an existing frozen call). Manual generateImage carries the original launchOwnedWork UUID/post/session/card; scheduled generation retains its task entity. The `moments:IMAGE` group exposes only bounded image metadata through the existing request-log modal’s 动态配图 tab, without turning its display group into a ChatSession ID. DesktopMomentImageUsageTest covers original core/checkpoint, real manual worker/account switch and failure/cancellation.
