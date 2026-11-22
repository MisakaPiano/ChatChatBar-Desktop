---
name: chatbar-desktop-reply-versions
description: Maintain Desktop stable reply alternatives, candidate deletion and whole-message confirmations.
---

# Desktop reply alternatives

- Shared `MessageAlternativeVersionPolicy.append` preserves all candidates by default. Explicit `maxVersions` remains available for callers that deliberately request bounded retention. Stable IDs distinguish identical candidate text and preserve narrative modes; `remove` returns null only when no candidate survives.
- `RoleplayContentSegments.editRoleplayMessageSegment` removes only the active candidate when its final text is deleted with no shared images. Shared-image captions can remain blank; surviving candidates and shared media stay intact.
- `ChatRepository.updateMessageIfUnchanged` and `deleteMessageIfUnchanged` compare the complete strict durable snapshot under the original append/data mutex before existing index/preview/tombstone writes. They are same-instance conditional operations, not cross-process transactions.
- Primary uses the existing task/voice mutation owners for current-candidate, last-candidate, and whole-assistant deletion, including an edit that empties the final segment. `DesktopSaveSlotService.commitRemovedAlternatives` journals the exact confirmed message, its index and preview, and affected voice rows. A failed install verifies rollback or preserves the pending journal for startup recovery. Media cleanup can retry from a private receipt. The preceding user turn and ordinary empty-composer continuation path remain independent.
- `DesktopPrimaryChatPanel` opens current-candidate confirmation first; a full-width outlined `DesktopDeleteAllAlternativesAction` with a real chevron opens the separate whole-message confirmation. Confirmation carries the captured full message so later edits or new candidates cannot be erased.
- Sources: Android owner frozen d2c86922 (candidate deletion to zero), a2953dd (unbounded default/stable removal/segment semantics). Desktop retains its own source-safety, maintenance, derived-memory and media ownership. Per-version format-repair checkpoints follow stable alternative identity; see `chatbar-message-format-repair` for their recovery and stale-action owners. Portable Moments image versions have a separate owner.
- Focused cloud workflow `desktop-reply-versions-focused.yml` runs shared policy/repository and real Desktop controller/regeneration/scene tests with synthetic messages and loopback providers. These are not human acceptance or public update evidence.
