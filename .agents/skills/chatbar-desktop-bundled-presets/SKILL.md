---
name: chatbar-desktop-bundled-presets
description: Maintain Desktop bundled manifest dependencies, initial import, typed recovery and complete suite restore.
---

# Desktop bundled presets

- `PresetManifest.kt` is the shared catalog model. Optional `defaultFormatPresetKey` and the shared `domain/card/PresetCharacterDependencies.kt` resolver follow official147: load declared world books with preset provenance, prefer embedded default format, otherwise resolve the declared format, and deduplicate worlds by preset key/id. This is distinct from Studio generation `.ccbpreset` files.
- `DesktopPresetSource.characterPackage` is the suspend, decode-only dependency authority used by startup, `DesktopTypedTransferController.recoverPreset` and complete suite restore. It resolves dependencies before calling an entity-writing transfer. Missing manifest dependencies stay visible failures.
- `DesktopPresetBootstrap` retains seen-version/user-edit/document-repair policy; shared world-book source-key reuse and format content/name conflict rules remain in the existing transfers. `DesktopPresetSuiteRestoreService` preloads a missing card's resolved package before creating missing worlds, retaining strict durable discovery, existing-card defaults, observed commits and retry/uncertainty handling. It does not overwrite an existing card just because manifest metadata advanced.
- Suite `bindingsAdded` counts declared initial-to-final additions, including bindings already committed inside a newly imported character package. A retry whose character/links already committed reports no new addition. Missing declared dependencies refuse the new character before commit; explicit recovery now includes declared and embedded books. Legacy tests that expected partial cards or ignored manifest relationships must retain retry/ledger/identity/user-deletion protections under this official147 behavior.
- Tests: original official pure resolver cases in `PresetCharacterDependenciesTest`; actual Desktop bootstrap/reopen, typed conflict/cancel, suite retry and dependency refusal in `DesktopPresetDependencyIntegrationTest`, alongside existing bootstrap/recovery/suite tests. See274 for pinned-source and execution scope.
