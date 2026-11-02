---
name: chatbar-desktop-studio-preset
description: Maintain Desktop complete generation preset files, resource transport, preview/apply/undo and exact staging/payload custody.
---

# Desktop Studio generation presets

- Shared `domain/image/NovelAiGenerationPreset.kt` is the explicit versioned generation contract. Include both model settings, continuous-mode semantics and paused guidance resources. History `toRecipe()` is intentionally incomplete. Existing `NovelAiStudioPromptClipboard` is separate positive-text interchange, not a full preset.
- `DesktopGenerationPresetArchive` writes `.ccbpreset`: first `preset.json`, then declared content-addressed image/vibe members. Sequential ZIP inspection bounds manifest/member count, checked total/available disk and actual member bytes/digest; image decoding keeps `DesktopImageEditing` limits. Vibe payloads use `NovelAiInlinePayloadStore.copyResolved/retainStream/validateStream`, never whole-resource reads.
- `DesktopNovelAiStudioController` resolves actual default model at export, owns preview baseline and explicit apply under original admission/mutex, and preserves repository undo. Publish and applied outcome are one non-cancellable segment. Post-commit cleanup failures are APPLIED_CLEANUP_PENDING, not permission to reapply. `DesktopAppContainer` closes an initialized preset owner without creating one during exit.
- `DesktopStudioPresetExchange` supplies the normal tool-window shell; its `DesktopStudioPresetExchangeContent` is the same fixed-header/footer, scroll-body content used by Scene tests. Studio's original Prompt toolbar entry remains separate from AI design and positive-text actions.
- `DesktopPresetCustody` writes metadata-only staging and write-before-publication claims. Finish only exact IDs from the current action. Existing or racing content-addressed files are not newly owned; unknown publication retains its claim. `DesktopOwnedImageCleanup` recognizes payload markers in existing JSON and refuses deletion on unknown backup/large/corrupt authorities. Cleanup uncertainty keeps files/receipts; startup exposes pending state without automatic broad recovery.
- Tests: shared `NovelAiGenerationPresetTest`, `NovelAiInlinePayloadStoreTest`; Desktop `DesktopGenerationPresetArchiveTest`, `DesktopPresetCustodyTest`, `DesktopStudioPresetControllerTest`, `DesktopStudioPresetExchangeTest`. Scene PNGs use existing `CCB_MEDIA_USAGE_REVIEW_DIR`. See `docs/desktop/264_STUDIO_COMPLETE_GENERATION_PRESET.md` for exact transport and supported-platform scope.
