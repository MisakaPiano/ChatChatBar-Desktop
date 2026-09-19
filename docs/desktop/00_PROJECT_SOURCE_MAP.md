# CCB Desktop Project Source Map

## UPSTREAM
Current `SaltyFishOTL/ChatChatBar` source. Highest authority for CCB schemas and runtime semantics.

## CURRENT
The current `desktop` branch and this directory.

## REF
Historical editors, schema templates and design references. They must not override current upstream behavior.

## FIXTURE
Cross-platform compatibility samples used for manual validation.

## ARCHIVE
Superseded plans.

## Authority order
1. Current upstream source at the recorded baseline commit.
2. Current fork `desktop` branch.
3. `10_UPSTREAM_BASELINE.json` and `21_CURRENT_STATE.md`.
4. Other CURRENT Desktop docs.
5. Project instructions.
6. Old Project Source snapshots / REF / old chats.

Never permanently hard-code a historical schemaVersion.

## Read order
1. 00_PROJECT_SOURCE_MAP.md
2. 10_UPSTREAM_BASELINE.json
3. 21_CURRENT_STATE.md
4. 13_FEATURE_PARITY.md
5. relevant upstream `.agents/skills/*/SKILL.md`
6. 14_UPSTREAM_COMPAT.md

## High-value upstream entry points
- AGENTS.md
- .agents/skills/chatbar-feature-map/SKILL.md
- app/app/src/main/java/com/example/chatbar/ChatBarApp.kt
- data/local/JsonFileStorage.kt
- domain/card/CardTransferModels.kt
- domain/card/CharacterCardTransferService.kt
- domain/worldbook/WorldBookEngine.kt
- domain/prompt/PromptTemplates.kt
- domain/chat/PromptAssembler.kt
- domain/chat/ContextWindowManager.kt
- ui/chat/ChatViewModel.kt
- domain/chat/StreamingChatService.kt
- domain/chat/SaveSlotPackageStorage.kt
- domain/rag/
- domain/memory/
- domain/image/
- domain/voice/
- domain/moment/
- domain/community/
- domain/update/
