---
name: chatbar-desktop-character-cover
description: Maintain Desktop immutable original character covers, PNG payload replacement, editor custody and portable resources.
---

# Desktop original character covers

- Shared `CharacterCard.coverImage` and `PackagedCharacterCard.coverImageResourceId` are optional originals, separate from avatar/background. `CharacterCardTransferCore` exports bytes, separately materializes aliases and retains covers in duplicate/overwrite/delete ledgers. No-cover packages remain9; cover packages use provisional10, readable3–10. Coordinate Android schema changes before claiming parity.
- `OriginalCharacterCover` validates PNG framing/CRC and strips case-insensitive ChatBarCharacter/chara/ccv3 text chunks (tEXt/zTXt/iTXt), preserving every other encoded block. `CharacterCardPngPackageCodec` uses it before attaching current data. Classifier and typed native/ST ingress prefer embedded originals. PNG resources exclude nested old card data; nonPNG sources stay byte exact.
- `DesktopCharacterResourceStore` owns CREATE_NEW `images/character-covers` and only that additional nested directory. `DesktopCharacterDraftResources` stages/copies/materializes covers and admits restored absolute draft paths only within its strictly validated root. Editor draft/save fields and `desktopCharacterResourceReferences` retain covers.
- The editor provides replacement/clear/copy-avatar without original crop. Export dialog switches original/old layout modes; `renderExportImage` skips logo/resampling for original PNG. AppContainer wires the production JSON/PNG/edited-export same-file guard. Temporary replacements and undo affect only export copies.
- Explicit generated-cover application stages a separate full-image cover in addition to its existing avatar crop/background; candidate-only application retains the candidate boundary. `DesktopCharacterImageTest` covers retained original assignments and cover save alongside its existing retry/rollback checks.
- Backup resource registry, `desktopName` and merge `addMedia` retain dedicated cover storage. Existing reference cleanup remains authoritative. Checks: shared `OriginalCharacterCoverTest`; Desktop `DesktopOriginalCharacterCoverTest` and `DesktopOriginalCoverBackupTest`, plus original transfer/classifier/editor/backup neighbors. Android prepared source is not execution evidence.
