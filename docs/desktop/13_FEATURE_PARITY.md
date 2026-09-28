# CCB Desktop Feature Parity Matrix

状态定义：
- `PENDING`：尚未达到 parity gate
- `EXACT`：与上游共享或已通过 parity 证据证明运行语义一致
- `EQUIVALENT`：平台机制不同，但用户功能与要求等价
- `BLOCKED`：存在明确平台/外部阻塞，必须写原因
- `N/A`：仅在充分理由下使用，必须写原因

实现过程中的“正在开发”写入 `21_CURRENT_STATE.md` / `17_ROADMAP.md`，不作为 parity 状态。完整兼容声明不得留下其他状态。

当前阶段：Phase 0–5 已完成并通过 Project review；Phase 5 **COMPLETE / ACCEPTED**。Phase 6 — Desktop Primary UI / Editors 已 **ACTIVE**。`desktop @ 5850fe28d233fb1b64a71b35e1e5f5d8d44db21d` 是 S4 前已集成控制点；`feature/phase6-s4-primary-chat-workspace @ d866f2c6d446638d9e46c99681db5e302cdf91b0` 尚未合并。Project 已完成 Phase 6 full parity re-audit 与 independent counter-audit reconciliation；P6-S4 blocker set 已冻结，但 S4 仍 **PENDING / NO MERGE / NO P6-S5**。formal compatibility claim 继续仅绑定 validated upstream `1.4.1 @ 5e76a9cb...`；observed upstream `148b3a96...` 为 HIGH / NO SYNC / NOT YET VALIDATED。

| 功能域 | 上游关键入口 | Desktop 目标 | 当前 |
|---|---|---:|---|
| App bootstrap / composition root | ChatBarApp.kt | EQUIVALENT | EQUIVALENT |
| Navigation / PrimaryShell | MainActivity/Navigation | EQUIVALENT | EQUIVALENT |
| Character Entity | CharacterCard.kt | EXACT | EXACT |
| STRUCTURED | CharacterCard/Edit | EXACT | PENDING |
| FREEFORM | CharacterCard/Edit | EXACT | PENDING |
| CharacterCardPackage | CardTransferModels | EXACT | EXACT |
| Character schema 3..9 read | validateForImport | EXACT | EXACT |
| Character schema 9 write | CardTransferModels | EXACT | EXACT |
| default FormatCard embed | CharacterCardTransferService | EXACT | PENDING |
| Character JSON import/export | CharacterCardTransferService | EXACT | EXACT |
| CCB PNG payload | CharacterCardTransferService/PngTextChunks | EXACT | EXACT |
| CCB PNG cover rendering | CharacterCardPngRenderer | EQUIVALENT | EQUIVALENT |
| SillyTavern character import | Parser/Mapper | EXACT | EXACT |
| Character editor | ui/character | EQUIVALENT | PENDING |
| Card duplicate/delete | repositories/deletion | EXACT | PENDING |
| FormatCard Entity | FormatCard.kt | EXACT | EXACT |
| FormatCard package v1..2 | CardTransferModels | EXACT | EXACT |
| FormatCard user tools | FormatCardUserToolPolicy | EXACT | EXACT |
| STRONG_PROMPT_SUFFIX | Prompt pipeline | EXACT | EXACT |
| WorldBook Entity | WorldBook.kt | EXACT | EXACT |
| WorldBookPackage v1 | CardTransferModels | EXACT | EXACT |
| WorldBook ST import/export | WorldBookTransferService | EXACT | EXACT |
| WorldBook Engine | WorldBookEngine | EXACT | EXACT |
| WorldBook timed effects | ChatSession/Engine | EXACT | EXACT |
| WorldBook AI | WorldBookAiService | EXACT | PENDING |
| PromptTemplates | domain/prompt | EXACT | EXACT |
| PromptAssembler | domain/chat | EXACT | EXACT |
| ContextWindow | ContextWindowManager | EXACT | EXACT |
| logical ChatApiMessage order | MainChatRequestAssembler | EXACT | EXACT |
| Prompt Inspector | Desktop-only convenience | EQUIVALENT | EQUIVALENT |
| Model value/repository contract | sharedCore ModelConfig/ModelRepository | EXACT | EXACT |
| Effective model resolution | EffectiveModelResolver | EXACT | EXACT |
| Provider auth/fallback | model runtime | EXACT | EXACT |
| Provider serialization / streaming SSE | StreamingChatService | EXACT | EXACT |
| thinking/output request policy | ThinkingRequestPolicy | EXACT | EXACT |
| cleartext local HTTP model policy | ProxyAwareClient/policy | EXACT | EXACT |
| Desktop real text-chat runtime / TaskRuntime | Desktop runtime/lifecycle | EQUIVALENT | EQUIVALENT |
| Model management core UI | ui/model/manage | EQUIVALENT | EQUIVALENT |
| Model discovery | ModelDiscoveryService | EQUIVALENT | EQUIVALENT |
| ModelTemplate import/export | ui/manage | EQUIVALENT | PENDING |
| Connection-test complete UX parity | ui/manage | EQUIVALENT | PENDING |
| request debug-log user surface | DebugLogManager | EQUIVALENT | PENDING |
| Session Entity | ChatSession.kt | EXACT | EXACT |
| Message Entity | ChatMessage.kt | EXACT | EXACT |
| Send / blank continue / stop | ui/chat/domain | EQUIVALENT | EQUIVALENT |
| Regenerate / retry invocation + runtime | ui/chat/domain | EQUIVALENT | EQUIVALENT |
| Assistant alternatives navigation | ui/chat/ChatBubble | EQUIVALENT | PENDING |
| Message copy/edit/delete | ui/chat/domain | EQUIVALENT | PENDING |
| speaker tags/history runtime | SpeakerTagHistory | EXACT | EXACT |
| Roleplay chat presentation | RoleplayContentSegments/ChatBubble | EQUIVALENT | PENDING |
| speaker/avatar presentation | ChatBubble | EQUIVALENT | PENDING |
| reasoning folded presentation | ChatBubble | EQUIVALENT | PENDING |
| segmented Assistant bubble presentation | ChatBubble | EQUIVALENT | PENDING |
| message format repair | MessageFormatRepairService | EXACT | PENDING |
| Home session list/open/pin/rename | ui/home | EQUIVALENT | EQUIVALENT |
| Home session placeholder rendering | ui/home | EQUIVALENT | PENDING |
| Archived-session character relink | ui/chat | EQUIVALENT | PENDING |
| Session extra WorldBook settings | ui/chat/SessionSettingsContent | EQUIVALENT | PENDING |
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
| Desktop typed Character/FormatCard/WorldBook transfer panel | Desktop platform | EQUIVALENT | EQUIVALENT |
| Shared file import classifier/domain core | shared-import | EXACT | EXACT |
| ACTION_SEND/VIEW ingress | Android intents | EQUIVALENT | PENDING |
| Desktop global drag/drop | Phase 15 OS integration | EQUIVALENT | PENDING |
| Open With / file association / OS registration | Phase 15 OS integration | EQUIVALENT | PENDING |
| global external-ingress UX | Phase 15 OS integration | EQUIVALENT | PENDING |
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

Phase 6 CURRENT gate: S4 remains open for six Project-frozen blocker bundles — authoritative Primary Chat presentation, Assistant alternatives, core copy/edit/delete actions, Session WorldBook settings, archived-session relink, and Home/session placeholder rendering. Connection-test disclosure/redaction/key wording, Character/FormatCard/WorldBook full manual editors, ModelTemplate transfer and other Phase-6-later items remain `PENDING` but are not S4 blockers. OS drag/drop/Open With/file association are owned by Phase 15, not Phase 6.

3B1 的 Entity / Package `EXACT` 表示 Android/Desktop 已共享同一 authoritative serialized contract、repository implementation 与验证规则；3C1/3C2 建立 Character materialization、SillyTavern parsing/mapping 与 classifier authority；3P 关闭 D-028 Prompt ownership dependency；3D 交付窄 typed management-style Character / FormatCard / WorldBook transfer。Character JSON、CCB PNG payload、SillyTavern Character 与 WorldBook ST transfer 达到 `EXACT`；Desktop AWT CCB PNG cover renderer 为平台 `EQUIVALENT`。3F 已完成双向 artifacts、resource payload、embedded contracts、provider ingress 与失败原子性的 interoperability gate。global SharedImport user-facing feature、ACTION_SEND/VIEW、drag/drop/Open With、global FIFO、ModelTemplate Desktop import 与完整 management UI 仍为 `PENDING`。Automatic backup runtime foundation 已完成，但完整用户设置界面仍 deferred，因此该用户功能保持 `PENDING`。

P5-S1 / 5A1 在 reviewed commit `dd68b714c58254b7ef994b106dafebf649623bf6` 建立 shared `ModelConfig` / preset values / `ModelRepository` / `ModelStorageKeyPolicy` authority，并以 Android identity policy 与 Desktop Windows-safe physical key policy 证明 logical Model semantics 等价。此为 Phase 5 历史 foundation record；其后 Settings、credential persistence、resolver、provider transport、SSE 与 real chat 已在 Phase 5 完成。Phase 6 用户界面 parity 仍按上表逐项判定。

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
