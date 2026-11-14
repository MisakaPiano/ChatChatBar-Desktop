---
name: chatbar-desktop-session-gallery
description: Maintain Desktop full live-session image overview, occurrence identity, strict read-only projections, lazy thumbnails and exact source-message navigation.
---

`DesktopSessionImageGallery` uses `ChatRepository.mapMessagesReadOnlyStrict` and `JsonFileStorage.mapEntitiesByPrefixStrict`. Decode one selected-prefix entity at a time, validate filename/session/message ownership, retain only small projections, then order by the existing message timeline. A corrupt or foreign selected record fails the overview visibly; unrelated prefixes are outside the scan. The reader never repairs message indexes or restores SaveSlots.

The gallery contains every image occurrence in current live history, including messages outside the bounded reading window. Identity is message ID plus image index, never resource path; repeated references remain separate. Text alternatives share their owning message's images. Known omitted-media placeholders are counted separately; missing physical files retain their tile and normal read error. Archive-only media stays with the existing SaveSlot viewer.

`DesktopSessionImageGalleryPanel` owns the native overview, refresh and lazy grid with bounded decode admission. Opening the shared `DesktopSessionImageViewer` captures the exact overview snapshot/index, unaffected by later refresh. All existing viewer operations remain; its added source action reads the current page index. Source navigation awaits the original controller `revealMessage` before closing the gallery, preserving the composer and bounded history owner.

`DesktopSessionImageGalleryTest` covers strict complete-history projection/unchanged source bytes, corrupt selected data, actual controller old-message reveal with retained draft, compact/wide lazy error tiles and source-page identity. Shared strict-scan and conditional-update tests cover the refactored reader's existing consumers. These are synthetic checks, separate from user acceptance.
