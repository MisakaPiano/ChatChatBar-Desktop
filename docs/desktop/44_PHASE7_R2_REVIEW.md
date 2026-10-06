# Phase 7 R2 — Desktop UX repair evidence

Date: 2026-10-06. Branch: `feature/phase7-image-novelai`.
Start: `b30cfc116249abfbd08ec2217f23b06b9293682a`.

Status: **IMPLEMENTATION IN PROGRESS / VALIDATION PENDING**. Phase 7 is not accepted or merged. The earlier implementation/full-regression PASS did not satisfy consolidated user UX acceptance. R2 follows the user-authorized `40_PHASE7_R2_UX_CONTRACT.md`; screenshots in `refs/` are REF only.

## Source/workflow mapping

All upstream mappings use the formal baseline `CCB 1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`.

| Upstream source / workflow | Desktop R2 equivalent |
|---|---|
| `ui/imageprompt/ImagePromptToolScreen.kt`: main Prompt and generation controls; `onOpenAiDesign`, `onOpenHistory`, `showImageTools`, `showGenerationOptions`, `showGuidanceEditor` | One `DesktopNovelAiStudioPanel` workspace, responsive Prompt/parameter column and selected-result preview, persistent generation footer, auxiliary dialogs |
| `NovelAiDesignScreen.kt`: `EmptyDesignConversation`, `DesignComposer`, `PendingStudioPromptAttachment`, `PromptReplyBubble`, `FailedDesignBubble` | Dedicated AI Design conversation surface, separate design settings/history, new conversation, explicit current positive attachment summary, retry/edit-branch/regenerate/apply |
| `NovelAiHistoryScreen.kt`: history gallery/detail/reuse | Auxiliary generation-history browser, image selection, confirmed deletion, full/new-seed/seed-only reuse; selected current-result shortcuts |
| `NovelAiImageGuidanceEditor.kt` / `NovelAiImageAction.kt` | Current Image → use as img2img / precise / Vibe / focused inpaint → existing guidance editor; shared types and preparation unchanged |
| `ChatScreen.kt`: composer `onImage`, selected-image strip, Assistant generation action | Attachment toolbar and thumbnails inside composer; compact image menu with explicit automatic opt-in, model summary, session requirements and Open Studio |
| `ChatViewModel.generateNovelAiImage` / `startNovelAiImageGeneration` | Assistant text action calls the existing shared Designer, model/size resolution and generation runtime; persisted-source recheck and existing linked image persistence; metadata regeneration stays image-owned |
| Existing Desktop `CharacterSessionService` adapter and `DesktopCharacterManagementPresentation` | Shared character summary card in New Chat and Management; both call `DesktopPrimaryChatController.createSession`, then select/navigate to Chat |
| `ImageMosaicEditor` and existing Desktop Image Workspace / CCB PNG export boundary | Continuous mosaic/black/white cover-up brush, common visual editor, bounded undo/redo/reset; cover drag/wheel and zoom slider, export-only replacement copy |

Desktop uses searchable choices, native pickers, wide side-by-side panes and narrow stacked layout rather than phone spacing. AI Design settings and history remain auxiliary to its conversation. Studio settings hold catalog/local annotation/copy preferences. Imported image actions include metadata, reverse candidate/progress/Stop, image editing, copy/save/reveal and guidance use. The screenshot's standalone enhancement/upscale action is omitted because the currently shared Desktop runtime has no corresponding standalone enhancement transport; no fake action was added.

## Private background ownership

`DesktopCharacterBackgrounds` owns `entities/desktop_character_backgrounds/<characterId>.json`, with root-relative, deep-copied image resources. It is included in existing app-data backup/root migration, but never serialized into CharacterCard or Card Package. `CharacterCard.chatBackground`, `ChatSession.chatBackground` and global opacity retain their formal meaning.

Rendering precedence: session override → readable Desktop preferred character background → official CharacterCard background. Chat's Background dialog exposes source, session choose/clear, global opacity, library add/delete and preferred/clear actions. Missing/corrupt preferences retain resources and visibly fall back. Mutation requires strictly readable authority. Deletion publishes the reference change before exact-candidate cleanup; generic cleanup validates the private library structure and protects references from other owners. No root sweep occurs.

## Boundaries

- No sharedCore or Android source changes, Entity/Package changes, Prompt literal changes, Designer protocol changes or NovelAI HTTP changes.
- Existing automatic completion/refusal/truncation/Stop/edit/deletion policy is unchanged; no legacy eligibility judge is called.
- TaskRuntime still owns work admission, cancellation and drain. Manual image tasks carry the source message ID for contextual progress.
- CCB PNG edit/crop/cover-up state is transient export state, not Character data. Cancel discards the copy.
- SecretStore remains the only credential owner. R2 sends **0 real NovelAI generation requests**; Phase live count remains **1/8**.
- Formal baseline and the approved exact `ace632c...` safety exception remain unchanged; parked `sync/1.4.4` remains untouched.

## Validation and artifact

R2_VALIDATION_PENDING

R2_PACKAGE_SMOKE_PENDING

## Manual acceptance

Use the new R2 artifact and `43_PHASE7_R2_MANUAL_ACCEPTANCE.md`. The checklist covers composer/images, backgrounds, character start-chat, Studio, AI Design, Current Image/guidance/tools, PNG export and restart persistence. Use local/fake fixtures; do not press real Generate. Existing P7 distribution is not the R2 acceptance artifact. Manual acceptance is still pending and must be performed by the user after Project review.

## Durable checkpoints

R2_COMMITS_PENDING
