# Data compatibility

## Three layers
1. Transfer Package: CharacterCardPackage / FormatCardPackage / WorldBookPackage / .cbsave.
2. Persisted Entity: local IDs, local paths, runtime/index state.
3. Runtime Prompt/request: final model messages.

Never substitute one layer for another.

## CharacterCardPackage baseline
Current upstream schema 9; reads 3..9.
v9 adds optional defaultFormatCard.

Package resources use portable resource IDs and embedded content.
Entities use local owned file paths.
Do not export local absolute paths as Package resource IDs.

## Documents
Package embeds content; Entity owns a local file plus RAG state.

## FormatCard
Package v1..2. Character package v9 may embed a default FormatCard package; Entity stores defaultFormatCardId.

## CCB PNG
The PNG is a carrier. The embedded CharacterCardPackage payload is the compatibility contract.

## ST
Use upstream parser/mapper/converter behavior. Historical editor conversions are reference-only.

## SaveSlot
Current transfer protocol is .cbsave v8 ZIP with manifest/messages/rag/optional voices/media.
Do not infer the transport schema from unrelated default values in Entity classes.

## Migration
Snapshot before migration; validate after migration; never clear data as a migration strategy.

## Cross-platform gates
- Character JSON Android <-> Desktop
- CCB PNG Android <-> Desktop
- SaveSlot Android <-> Desktop
- equivalent final model request
- equivalent WorldBook selection
