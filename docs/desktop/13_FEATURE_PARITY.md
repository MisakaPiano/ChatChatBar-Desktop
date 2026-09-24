# CCB Desktop Feature Parity Matrix

状态定义：
- `PENDING`：尚未达到 parity gate
- `EXACT`：与上游共享或已通过 parity 证据证明运行语义一致
- `EQUIVALENT`：平台机制不同，但用户功能与要求等价
- `BLOCKED`：存在明确平台/外部阻塞，必须写原因
- `N/A`：仅在充分理由下使用，必须写原因

实现过程中的“正在开发”写入 `21_CURRENT_STATE.md` / `17_ROADMAP.md`，不作为 parity 状态。完整兼容声明不得留下其他状态。

当前阶段：Phase 3A contract audit 与 Phase 3B1 shared Entity / Package contract core 已完成。3B1 只使经过共享 authority 与 fixture 验证的 Entity / Package contract 行达到 `EXACT`；transfer、resource materialization、Import/Export UI、PNG renderer、SillyTavern 与 Prompt/runtime 仍保持 `PENDING`。

| 功能域 | 上游关键入口 | Desktop 目标 | 当前 |
|---|---|---:|---|
| App bootstrap / composition root | ChatBarApp.kt | EQUIVALENT | EQUIVALENT |
| Navigation | MainActivity/Navigation | EQUIVALENT | PENDING |
| Character Entity | CharacterCard.kt | EXACT | EXACT |
| STRUCTURED | CharacterCard/Edit | EXACT | PENDING |
| FREEFORM | CharacterCard/Edit | EXACT | PENDING |
| CharacterCardPackage | CardTransferModels | EXACT | EXACT |
| Character schema 3..9 read | validateForImport | EXACT | EXACT |
| Character schema 9 write | CardTransferModels | EXACT | EXACT |
| default FormatCard embed | CharacterCardTransferService | EXACT | PENDING |
| Character JSON import/export | CharacterCardTransferService | EXACT | PENDING |
| CCB PNG payload | CharacterCardTransferService/PngTextChunks | EXACT | PENDING |
| CCB PNG cover rendering | CharacterCardPngRenderer | EQUIVALENT | PENDING |
| SillyTavern character import | Parser/Mapper | EXACT | PENDING |
| Character editor | ui/character | EQUIVALENT | PENDING |
| Card duplicate/delete | repositories/deletion | EXACT | PENDING |
| FormatCard Entity | FormatCard.kt | EXACT | EXACT |
| FormatCard package v1..2 | CardTransferModels | EXACT | EXACT |
| FormatCard user tools | FormatCardUserToolPolicy | EXACT | PENDING |
| STRONG_PROMPT_SUFFIX | Prompt pipeline | EXACT | PENDING |
| WorldBook Entity | WorldBook.kt | EXACT | EXACT |
| WorldBookPackage v1 | CardTransferModels | EXACT | EXACT |
| WorldBook ST import/export | WorldBookTransferService | EXACT | PENDING |
| WorldBook Engine | WorldBookEngine | EXACT | PENDING |
| WorldBook timed effects | ChatSession/Engine | EXACT | PENDING |
| WorldBook AI | WorldBookAiService | EXACT | PENDING |
| PromptTemplates | domain/prompt | EXACT | PENDING |
| PromptAssembler | domain/chat | EXACT | PENDING |
| ContextWindow | ContextWindowManager | EXACT | PENDING |
| final API message order | ChatViewModel | EXACT | PENDING |
| Prompt Inspector | Desktop-only convenience | EQUIVALENT | PENDING |
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
| JSON persistence | JsonFileStorage | EXACT | EXACT |
| atomic writes | JsonFileStorage | EXACT | EXACT |
| Desktop data directory | Desktop platform | EQUIVALENT | EQUIVALENT |
| Portable Mode | Desktop enhancement | EQUIVALENT | EQUIVALENT |
| backup snapshot/restore foundation | Desktop enhancement | EQUIVALENT | EQUIVALENT |
| data-root switch/migration | Desktop enhancement | EQUIVALENT | EQUIVALENT |
| automatic backups | Desktop enhancement | EQUIVALENT | PENDING |
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

3B1 的 Entity / Package `EXACT` 表示 Android/Desktop 已共享同一 authoritative serialized contract、repository implementation 与验证规则；它不表示 transfer/materialization 或用户界面已完成。Character JSON import/export、default FormatCard materialization、CCB PNG payload/rendering、SillyTavern import、WorldBook transfer、Desktop Import/Export UI 与 Prompt/runtime 仍保持 `PENDING`。Automatic backup runtime foundation 已完成，但完整用户设置界面仍 deferred，因此该用户功能保持 `PENDING`。

## 1.4.x parity contracts

以下合同细化现有功能域，不新增重复的顶级功能，也不改变当前 PENDING 状态。

### Prompt（目标：EXACT）

- `systemPrompt` 固定 prefix / suffix 的 ownership 必须保持。
- 角色卡 override 只能替换中间的 `SYSTEM_PROMPT_REPLACEABLE_CONTENT`。
- `{{original}}` 只展开为 default middle，不展开完整 prefix / suffix。
- parity 以最终 logical messages 与 serialized transport request 为准。
- `START`：CCB contract confirmation → START requirements → character/stable context。
- `END`：current user → character post-history + END requirements → optional strong suffix / CCB final tail。
- `BOTH`：同时在上述两个固定位置注入 requirements；HTTPS 与允许的 cleartext local HTTP 可以做 role adaptation，但 logical content/order 必须一致。

### Model / RAG（目标：EXACT）

- 1.4.0 不再提供 dedicated retrieval model slot。
- Chat retrieval planner 使用 current chat model。
- Character / WorldBook research 使用该 operation 选定的 generation model，并遵循官方 fallback policy。
- embedding model 仍为独立能力，不与上述 generation/retrieval 选择合并。

### Interrupted reply / blank continue（目标：EXACT）

- assistant interrupted draft 在 body 或 reasoning 任一 nonblank 时可持久化；fully empty placeholder 与 USER-role draft 不持久化。
- reasoning-only interrupted regeneration 的新选中版本 body 为空；旧 response text 只能留在 alternative/history metadata，不得重新进入新请求。
- blank continue 在 latest persisted message 为 USER 时复用其 body、images 与 message ID，并将该 message 排除出 history；不得重复持久化 USER，也不得再注入 `continueGenerationUserPrompt()`。
- latest persisted message 非 USER 时，继续使用 request-only `continueGenerationUserPrompt()`，且不新增 persistent USER row。
- current USER 的内容、images 与 source-turn identity 在最终 serialized model messages 中只出现一次。

### Model editing defaults（目标：EXACT）

- temperature default 为 `1.0`。
- common parameter preset 包含 `reasoning_effort = low`；`max_tokens` 保持官方 1.4.1 的 explicit output-limit contract。
- 这些默认值变化不构成 `ModelConfig` transport schema bump。

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
