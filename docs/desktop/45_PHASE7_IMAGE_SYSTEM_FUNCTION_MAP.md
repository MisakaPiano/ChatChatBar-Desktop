# 45 — Phase 7 Image-System Function Map

## Purpose

This is a traceability map, not a new semantic authority or parity declaration. It models CCB image behavior as connected workflows so Desktop maintenance does not reduce the image system to isolated feature checkboxes.

Formal authority: `CCB 1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`.

## 1. Topology

```text
CHAT
 ├─ USER attachments
 │   ├─ composer image list
 │   ├─ persist all message images
 │   ├─ direct multimodal OR linked-vision description
 │   └─ edit/remove/preview
 │
 ├─ ASSISTANT reply
 │   ├─ manual Generate Image
 │   │   ├─ direct action
 │   │   └─ secondary "生图要求"
 │   └─ automatic image
 │       └─ completion policy → Prompt Designer → generation
 │
 └─ generated chat image
     ├─ preview
     ├─ editable regeneration
     ├─ delete selected image
     └─ source-message linkage

STUDIO — `ImagePromptToolScreen`
 ├─ Prompt workspace
 ├─ generation settings
 ├─ account / cost state
 ├─ current/recent result
 ├─ Generate / Stop
 ├─ AI Design
 ├─ direct Guidance
 ├─ Image Tools
 └─ History

AI DESIGN
 ├─ new/current conversation
 ├─ history
 ├─ settings
 ├─ attach current Studio positive Prompt
 ├─ retry/regenerate/edit-branch
 └─ Apply to Studio

CURRENT / IMPORTED IMAGE
 ├─ metadata
 ├─ reverse Prompt
 ├─ mosaic/edit
 ├─ save/share equivalents
 └─ Use as: img2img / Precise / Vibe / inpaint

HISTORY
 ├─ search / date filter / fold-albums
 ├─ detail recipe
 ├─ full reproduction
 ├─ new-seed reuse
 ├─ seed-only reuse
 ├─ Use as Guidance
 └─ delete

IMAGE RESOURCES
 ├─ Character avatar/chatBackground/appearanceImage
 ├─ ChatSession background
 ├─ Desktop-private background library
 └─ CCB PNG export working copy
```

## 2. Chat attachments

Upstream `ChatScreen.kt` keeps `selectedImages` as a mutable list. Repeated picks add multiple images. Sending persists the whole list. Full-message editing also owns an editable image list.

This must stay separate from request semantics: the model-request policy may intentionally consume only the first USER image while the message still owns multiple images.

Desktop rule: support multiple pending/persisted images without changing the shared first-image request policy.

## 3. Assistant manual image generation

Upstream `ChatScreen.kt` exposes `onGenerateImage` on eligible Assistant messages. A secondary/long-press path opens `生图要求`, including one-run image-content hint plus session Prompt preference.

Desktop may use a context menu or inline action, but must preserve the same source Assistant identity, Prompt Designer/model resolution, per-run requirements, and task anchor.

## 4. Automatic chat images

Authority: `AutomaticChatImagePolicy`, `ChatViewModel`, `chatbar-image-generation-runtime`.

Rules: explicit opt-in, completed persisted Assistant reply, no legacy AI eligibility judge, and no handoff on missing completion/transport failure/refusal/truncation/blank/Stop/edit/delete/disabled state.

Manual generation, automatic generation, and linked vision are different workflows.

## 5. Generated chat-image actions

Formal generated/reusable image actions include editable regeneration and selected-image deletion while preserving the source message. Images remain actionable derived resources, not view-only attachments.

## 6. Studio root

Owner: `ImagePromptToolScreen.kt` / `ImagePromptToolViewModel.kt`.

One workspace owns Prompt fields, character prompts, generation settings, account/cost state, current results, Generate/Stop, AI Design, direct Guidance, Image Tools, and History. Desktop can use wide panes but should not invent a different ownership hierarchy.

## 7. Account / cost state

Upstream `NovelAiAccountService` + Studio ViewModel/Screen:
- reads Anlas and V5 allowance on Studio entry;
- displays approximate V5 availability;
- refreshes/reconciles after successful generation;
- makes immediate local visible accounting adjustments;
- cost estimate updates with settings/guidance;
- account-fetch failure must not falsely claim free eligibility.

## 8. AI Design

Owners: `NovelAiDesignScreen`, `NovelAiDesignViewModel`, conversation repository.

Includes new conversation, history, settings, model/natural-language/extra requirement, explicit current-Studio-Prompt attachment, send/stop, retry, regenerate, edit-and-branch, Apply to Studio.

## 9. History

Owners: `NovelAiHistoryScreen`, `NovelAiHistoryViewModel`, `NovelAiHistoryFilter`.

Formal history includes Prompt search, date filter, folding/albums, fold preference, detail recipe/seed/settings/guidance, full reproduction, new-seed reuse, seed-only reuse, Use as Guidance, and deletion. Exact reproduction must not pretend to be exact when required Guidance sources are missing.

## 10. Guidance

Formal Studio has both:
1. direct Guidance entry from `ImagePromptToolScreen`;
2. current/history image → Use as → Guidance.

Supported semantics include img2img, Precise Reference, Vibe, inpaint/focused inpaint, mask/focus state, and model compatibility.

## 11. Current/imported image

`StudioImageToolsDialog` / `NovelAiImageAction` make the current image a first-class working object: metadata, mosaic, reverse Prompt, preview, replacement/removal and Guidance-use actions.

## 12. Reverse Prompt

Formal UI exposes stage/status, streamed result, reasoning where provided, Stop, retry, final candidate and explicit Apply. Runtime still uses the same Prompt-tool design pipeline.

## 13. Metadata / regeneration

Keep layers distinct:
- `GeneratedImageMetadata` follows upstream chat schema;
- Studio history recipe owns complete generation settings and actual seed;
- regeneration uses recorded metadata only;
- do not infer missing old style from current Character data.

## 14. Image processing / APNG

Platform codecs may differ, but preserve pixel/frame semantics, original preservation, explicit apply/cancel, and save/share equivalents.

## 15. Character/background resources

Cross-platform authority remains Character/Session/AppSettings fields. Approved Desktop-only background library is additive.

Desktop effective background:
1. session override;
2. readable Desktop preferred Character background;
3. official `CharacterCard.chatBackground`.

Do not serialize Desktop preference into CharacterCard Package.

## 16. CCB PNG

Export-working-copy UX may add drag/zoom/mosaic/cover-up/undo. It must never create a fake Character/Package field.

## 17. Upstream sync use

Use this map to target re-audit:
- `ChatScreen.kt` → chat attachments/manual generation/generated-image actions.
- `AutomaticChatImagePolicy.kt` → automatic-image completion policy.
- `ImagePromptToolScreen.kt` → Studio/account/Guidance/current-image workflow.
- `NovelAiHistory*` → History.
- `NovelAiImageGuidance*` / Vibe code → Guidance.
- `NovelAiImageService` → HTTP/request runtime.
- `ChatMessage` / Studio models → high-risk schema/data review.

Compilation alone is not compatibility evidence for these workflows.
