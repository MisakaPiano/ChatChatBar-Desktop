# CCB Desktop Feature Parity Matrix

状态定义：
- `PENDING`：尚未实现
- `IN_PROGRESS`
- `EXACT`：与上游共享或已证明运行语义一致
- `EQUIVALENT`：平台等位替代，用户功能等价
- `BLOCKED`
- `N/A`：必须写理由

当前阶段：Phase 0 sync validation complete / ready for integration。Desktop 实现尚未开始；因此现有实现状态继续保持 PENDING，上游同步本身不会把 parity 行自动改为 IN_PROGRESS。

| 功能域 | 上游关键入口 | Desktop 目标 | 当前 |
|---|---|---:|---|
| App bootstrap / composition root | ChatBarApp.kt | EQUIVALENT | PENDING |
| Navigation | MainActivity/Navigation | EQUIVALENT | PENDING |
| Character Entity | CharacterCard.kt | EXACT | PENDING |
| STRUCTURED | CharacterCard/Edit | EXACT | PENDING |
| FREEFORM | CharacterCard/Edit | EXACT | PENDING |
| CharacterCardPackage | CardTransferModels | EXACT | PENDING |
| Character schema 3..9 read | validateForImport | EXACT | PENDING |
| Character schema 9 write | CardTransferModels | EXACT | PENDING |
| default FormatCard embed | CharacterCardTransferService | EXACT | PENDING |
| Character JSON import/export | CharacterCardTransferService | EXACT | PENDING |
| CCB PNG payload | CharacterCardTransferService/PngTextChunks | EXACT | PENDING |
| CCB PNG cover rendering | CharacterCardPngRenderer | EQUIVALENT | PENDING |
| SillyTavern character import | Parser/Mapper | EXACT | PENDING |
| Character editor | ui/character | EQUIVALENT | PENDING |
| Card duplicate/delete | repositories/deletion | EXACT | PENDING |
| FormatCard Entity | FormatCard.kt | EXACT | PENDING |
| FormatCard package v1..2 | CardTransferModels | EXACT | PENDING |
| FormatCard user tools | FormatCardUserToolPolicy | EXACT | PENDING |
| STRONG_PROMPT_SUFFIX | Prompt pipeline | EXACT | PENDING |
| WorldBook Entity | WorldBook.kt | EXACT | PENDING |
| WorldBookPackage v1 | CardTransferModels | EXACT | PENDING |
| WorldBook ST import/export | WorldBookTransferService | EXACT | PENDING |
| WorldBook Engine | WorldBookEngine | EXACT | PENDING |
| WorldBook timed effects | ChatSession/Engine | EXACT | PENDING |
| WorldBook AI | WorldBookAiService | EXACT | PENDING |
| PromptTemplates | domain/prompt | EXACT | PENDING |
| PromptAssembler | domain/chat | EXACT | PENDING |
| ContextWindow | ContextWindowManager | EXACT | PENDING |
| final API message order | ChatViewModel | EXACT | PENDING |
| Prompt Inspector | Desktop-only convenience | EQUIVALENT+ | PENDING |
| Model Entity/settings | ModelConfig | EXACT | PENDING |
| Model discovery | ModelDiscoveryService | EXACT | PENDING |
| Provider auth/fallback | model runtime | EXACT | PENDING |
| Streaming SSE | StreamingChatService | EXACT | PENDING |
| thinking/reasoning | ThinkingRequestPolicy | EXACT | PENDING |
| cleartext local model | ProxyAwareClient/policy | EXACT | PENDING |
| request debug logs | DebugLogManager | EQUIVALENT | PENDING |
| Session Entity | ChatSession.kt | EXACT | PENDING |
| Message Entity | ChatMessage.kt | EXACT | PENDING |
| Send/regenerate/edit/delete | ui/chat/domain | EXACT | PENDING |
| speaker tags/history | SpeakerTagHistory | EXACT | PENDING |
| message format repair | MessageFormatRepairService | EXACT | PENDING |
| Home session list/pin | ui/home | EQUIVALENT | PENDING |
| Session duplicate | SessionCopyService | EXACT | PENDING |
| JSON persistence | JsonFileStorage | EXACT | PENDING |
| atomic writes | JsonFileStorage | EXACT | PENDING |
| Desktop data directory | Desktop platform | EQUIVALENT | PENDING |
| Portable Mode | Desktop enhancement | EQUIVALENT+ | PENDING |
| automatic backups | Desktop enhancement | EQUIVALENT+ | PENDING |
| RAG chunking | domain/rag | EXACT | PENDING |
| embeddings | EmbeddingService | EXACT | PENDING |
| vector search | VectorSearchEngine | EXACT | PENDING |
| document RAG | RagManager | EXACT | PENDING |
| chat-memory RAG | ChatMemoryIndexPolicy | EXACT | PENDING |
| Long-term Memory | domain/memory | EXACT | PENDING |
| Episode / Arc / Era | memory | EXACT | PENDING |
| Archive | memory/prompt | EXACT | PENDING |
| HEAD | memory/prompt | EXACT | PENDING |
| Gap/backfill/repair | memory | EXACT | PENDING |
| SaveSlot legacy 1–7 | save slot | EXACT | PENDING |
| `.cbsave` v8 | SaveSlotPackageStorage | EXACT | PENDING |
| SaveSlot image policies | save slot | EXACT | PENDING |
| SaveSlot audio | save slot/Fish | EXACT | PENDING |
| NovelAI credential | Android Keystore | EQUIVALENT | PENDING |
| NovelAI HTTP | image runtime | EXACT | PENDING |
| NovelAI model/size/seed | image runtime | EXACT | PENDING |
| NovelAI Prompt Designer | image runtime | EXACT | PENDING |
| Danbooru catalog | image runtime | EXACT | PENDING |
| tag research/suggest | image runtime | EXACT | PENDING |
| V5 natural language | image runtime | EXACT | PENDING |
| guidance / vibe / inpaint | image runtime | EXACT | PENDING |
| Studio | ui/imageprompt | EQUIVALENT | PENDING |
| history | image runtime/UI | EQUIVALENT | PENDING |
| regeneration | image runtime | EXACT | PENDING |
| automatic chat images | image/chat | EXACT | PENDING |
| APNG disguise/restore | image processing | EXACT/EQUIVALENT | PENDING |
| mosaic editor | ImageMosaicEditor | EQUIVALENT | PENDING |
| image save/share/reveal | Android share | EQUIVALENT | PENDING |
| Fish credential | Android Keystore | EQUIVALENT | PENDING |
| Fish voice library | voice domain | EXACT | PENDING |
| character Fish binding | entity/package | EXACT | PENDING |
| Fish tags | FishAudioTagService | EXACT | PENDING |
| Fish generation/batch | voice domain | EXACT | PENDING |
| voice anchor persistence | voice entities | EXACT | PENDING |
| audio playback | Media3 | EQUIVALENT | PENDING |
| QQ voice transfer | voice/qq | EQUIVALENT/BLOCKED | PENDING |
| Character AI fill | character-card-ai | EXACT | PENDING |
| Character rewrite | character-card-ai | EXACT | PENDING |
| image-to-appearance | character-card-ai | EXACT | PENDING |
| card cover/avatar AI | character-card-ai/image | EXACT | PENDING |
| FormatCard AI | format-card-ai | EXACT | PENDING |
| WorldBook AI | worldbook-ai | EXACT | PENDING |
| Research/manual URLs | domain/search | EXACT | PENDING |
| reference document retrieval | search/RAG | EXACT | PENDING |
| Moments entities/repo | moments | EXACT | PENDING |
| Moments generation | moments | EXACT | PENDING |
| Moments runtime scheduler | moments Android alarm | EQUIVALENT | PENDING |
| Moments images | moments/image | EXACT | PENDING |
| Community browse/search | community | EXACT | PENDING |
| Community download/import | community/transfers | EXACT | PENDING |
| Community upload | community | EXACT | PENDING |
| Discord OAuth | Android deep link | EQUIVALENT | PENDING |
| Community runtime switch | Supabase | EXACT | PENDING |
| Shared file import classifier | shared-import | EXACT | PENDING |
| ACTION_SEND/VIEW ingress | Android intents | EQUIVALENT | PENDING |
| Drag & drop/Open With | Desktop | EQUIVALENT | PENDING |
| Settings | ui/manage/entities | EQUIVALENT | PENDING |
| Tutorial/help | TutorialScreen | EQUIVALENT | PENDING |
| Crash diagnostics | diagnostics | EQUIVALENT | PENDING |
| Background AI protection | FGS | EQUIVALENT | PENDING |
| Network-loss cancellation | background/model | EXACT/EQUIVALENT | PENDING |
| App update check | update | EXACT | PENDING |
| APK install | Android installer | EQUIVALENT | PENDING |
| Danbooru catalog update | update | EXACT | PENDING |
| Desktop installer | Compose Desktop | EQUIVALENT | PENDING |
| Upstream watcher | Desktop downstream | Desktop-only | PENDING |
| Upstream compatibility report | downstream tooling | Desktop-only | PENDING |

## 1.3.49 parity contracts

以下合同细化现有功能域，不新增重复的顶级功能，也不改变当前 PENDING 状态。

### Prompt（目标：EXACT）

- `systemPrompt` 固定 prefix / suffix 的 ownership 必须保持。
- 角色卡 override 只能替换中间的 `SYSTEM_PROMPT_REPLACEABLE_CONTENT`。
- `{{original}}` 只展开为 default middle，不展开完整 prefix / suffix。
- parity 以最终 logical messages 与 serialized transport request 为准。

### Moments（目标：EXACT）

- text / judge / copy model：`session.modelId` 优先，随后回退 global default chat。
- image design / research model：`session.imageModelId` 优先，随后回退 global default image。
- NovelAI rendering model 独立解析：session override → character default → global default。

### Character bindings

- FormatCard / WorldBook 的 live repository state、stale binding 保留与显式移除语义：`EXACT`。
- searchable FormatCard single-select 与 WorldBook multi-select 的呈现和交互：`EQUIVALENT`。

### Fish（目标：EXACT）

- FREEFORM `CharacterInfo` 必须保留与 STRUCTURED 相同的 Fish voice binding 能力、ID 与 speaker-name matching 语义。

## 1.0 Gate

CCB Desktop 1.0：
- 选定 baseline 的官方用户功能不存在漏项；
- 每行最终为 EXACT / EQUIVALENT，或得到用户明确接受的 BLOCKED 说明；
- Package/Prompt/WorldBook/SaveSlot 跨端测试通过；
- 数据迁移/备份/恢复验证通过；
- 公开发布前许可问题已确认。
