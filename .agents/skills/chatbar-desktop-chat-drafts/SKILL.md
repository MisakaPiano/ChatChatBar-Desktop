---
name: chatbar-desktop-chat-drafts
description: Maintain Desktop durable text and image composer drafts, send consumption, revision ownership and backup media projection.
---

# Desktop chat drafts

- `DesktopPrimaryChatController` captures text and ordered `DesktopPendingImage` attachments as one session revision. `DesktopChatDraftPersistence` is a container-owned coalescing writer; route/UI disposal does not own it. Explicit `persistComposer` retries the latest failed snapshot. Drain/close surface failure.
- `DesktopChatImages.persistDraft` materializes intact encoded files under the existing normal-operation lease, after a strict durable session-owner check. `ChatRepository.getSessionDraftSnapshot/updateSessionDraftSnapshot` strictly read owner evidence under the draft mutex. Old text-only writes preserve images; explicit full snapshots remove them. Empty text with nonempty images remains a draft.
- Portable `ChatDraft.images` is an optional ordered string list, default empty and `EncodeDefault.NEVER`. Original fields and constructor/copy bridges remain. Runtime attachment IDs and revision tokens are not portable fields.
- `DesktopTaskRuntime` forwards the real runtime's durable USER callback. Only its matching composer revision is consumed; admission, Stop during image understanding and later edits do not grant consumption. Clear bookkeeping is per session, including hidden cached attachments. An uncertain partial write preserves the draft. Selection rechecks both outgoing and target revisions after IO; attachment removal/reorder derive and publish under the same composer lock used by successful consumption.
- `DesktopOwnedImageCleanup` scans persisted JSON references, including image drafts. Failed draft publication retains candidate files for retry. It does not claim immediate orphan collection.
- `BackupExportSelection` projects both message and draft images according to the session image choice. `DesktopMergeRetainedMedia` preserves omitted computer draft media only for the same mapped session; a fork has its own owner. Full-backup serializers/resource mapping use the shared DTO and registered images field.
- Tests: shared `ChatDraftSerializationTest/ChatDraftImagesTest`; Desktop `DesktopDraftImagesLifetimeTest`, draft writer/lifetime/controller tests and `DesktopDraftImageBackupTest`. Focused cloud workflow records a frozen old-controller Stop counterexample and exact positive source separately.
