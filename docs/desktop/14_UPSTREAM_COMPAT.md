# Upstream -> Desktop compatibility map

| Upstream area | Desktop strategy |
|---|---|
| AGENTS.md / .agents/skills | preserve and read before feature work |
| ChatBarApp.kt | keep Android; create Desktop composition root |
| MainActivity/Navigation | Desktop UI shell |
| JsonFileStorage.kt | extract root-Path storage core |
| data/local/entity/* | shared EXACT candidates |
| data/repository/* | shared after storage abstraction |
| data/security/*CredentialStore | Desktop credential adapter |
| CardTransferModels.kt | shared EXACT |
| CharacterCardTransferService.kt | shared transfer core + resource adapter |
| CharacterCardPngRenderer.kt | Desktop image renderer |
| PngTextChunks* | shared EXACT |
| SillyTavern* | preserve upstream semantics; adapt ingress |
| WorldBookTransferService.kt | shared EXACT |
| WorldBookEngine.kt | shared EXACT |
| PromptTemplates.kt | shared EXACT; do not fork |
| PromptAssembler.kt | shared EXACT |
| ContextWindowManager.kt | shared EXACT |
| ChatViewModel orchestration | extract domain orchestration where practical |
| StreamingChatService.kt | JVM shared candidate |
| domain/model/* | shared candidate |
| domain/rag/* | shared |
| domain/memory/* | shared |
| SaveSlotPackageStorage.kt | shared protocol + filesystem/image adapters |
| domain/image/* | network/policy shared; image platform adapter |
| Fish voice domain | shared; credential/audio adapters |
| voice/qq/* | Desktop special equivalent/blocker |
| domain/service/* | Desktop TaskRuntime |
| domain/moment/* | shared domain; runtime scheduler adapter |
| domain/community/* | shared backend; OAuth/UI adapter |
| domain/update/* | checker shared where possible; installer replaced |
| UI feature packages | Desktop UX equivalent |

High-risk upstream changes always require semantic review:
- CardTransferModels
- Character / FormatCard / WorldBook entities
- PromptTemplates / PromptAssembler / ContextWindowManager
- ChatViewModel / StreamingChatService
- WorldBookEngine / ModelConfig
- SaveSlot
- memory / rag / image / voice
