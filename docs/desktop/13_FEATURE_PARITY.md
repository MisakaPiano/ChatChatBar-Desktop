# CCB Desktop Feature Parity Matrix

状态定义：
- `PENDING`：尚未达到 parity gate
- `EXACT`：与上游共享或已通过 parity 证据证明运行语义一致
- `EQUIVALENT`：平台机制不同，但用户功能与要求等价
- `BLOCKED`：存在明确平台/外部阻塞，必须写原因
- `N/A`：仅在充分理由下使用，必须写原因

实现过程中的“正在开发”写入 `21_CURRENT_STATE.md` / `17_ROADMAP.md`，不作为 parity 状态。完整兼容声明不得留下其他状态。

当前阶段：Phase 0–6 **COMPLETE / ACCEPTED**。accepted production feature 为 `feature/phase6-s9-desktop-ux @ 86be0b0ec21aab7a8f15553c0b696b253738917f`；本次 finalization 把其与 docs closeout FF-only 集成到 `desktop`（原 `5850fe28...`）。Project review 与用户 final manual acceptance PASS。formal compatibility claim 仅绑定 validated upstream **1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8**；master 已 mirror upstream **1.4.4 @ 550409689df8c51f459fb50b4e04c8ac2fa4bf35**，`sync/1.4.4 @ 9b6378dbb595dd2f3ff5143a7a8e46653c99e721` 为 **PARKED / NOT MERGED / NOT VALIDATED**，不阻塞 P7。

Phase 6 没有 UNKNOWN 项；以下 EXACT/EQUIVALENT 为已接受范围，PENDING 的 future owner 见后表。

| 功能域 | 上游关键入口 | Desktop 目标 | 当前 |
|---|---|---:|---|
| App bootstrap / composition root | ChatBarApp.kt | EQUIVALENT | EQUIVALENT |
| Navigation / PrimaryShell | MainActivity/Navigation | EQUIVALENT | EQUIVALENT |
| Character Entity | CharacterCard.kt | EXACT | EXACT |
| STRUCTURED | CharacterCard/Edit | EXACT | EXACT |
| FREEFORM | CharacterCard/Edit | EXACT | EXACT |
| CharacterCardPackage | CardTransferModels | EXACT | EXACT |
| Character schema 3..9 read | validateForImport | EXACT | EXACT |
| Character schema 9 write | CardTransferModels | EXACT | EXACT |
| default FormatCard embed | CharacterCardTransferService | EXACT | EXACT |
| Character JSON import/export | CharacterCardTransferService | EXACT | EXACT |
| CCB PNG payload | CharacterCardTransferService/PngTextChunks | EXACT | EXACT |
| CCB PNG cover rendering | CharacterCardPngRenderer | EQUIVALENT | EQUIVALENT |
| SillyTavern character import | Parser/Mapper | EXACT | EXACT |
| Character editor | ui/character | EQUIVALENT | EQUIVALENT |
| Card duplicate/delete | repositories/deletion | EXACT | EXACT |
| FormatCard manual editor | ui/format | EQUIVALENT | EQUIVALENT |
| WorldBook manual editor | ui/worldbook | EQUIVALENT | EQUIVALENT |
| Management CRUD / bundled presets / Complete Preset Restore | repositories/preset policies | EQUIVALENT | EQUIVALENT |
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
| ModelTemplate import/export | ui/manage | EQUIVALENT | EQUIVALENT |
| Connection-test complete UX parity | ui/manage | EQUIVALENT | EQUIVALENT |
| request debug-log user surface | P15 DebugLogManager | EQUIVALENT | PENDING |
| Session Entity | ChatSession.kt | EXACT | EXACT |
| Message Entity | ChatMessage.kt | EXACT | EXACT |
| Send / blank-continuation runtime / stop (no ordinary Continue button) | ui/chat/domain | EQUIVALENT | EQUIVALENT |
| Regenerate / retry invocation + runtime | ui/chat/domain | EQUIVALENT | EQUIVALENT |
| Assistant alternatives navigation | ui/chat/ChatBubble | EQUIVALENT | EQUIVALENT |
| Per-segment actions | ui/chat/ChatBubble | EQUIVALENT | EQUIVALENT |
| Full-screen / resizable / collapsible composer | ChatScreen/FullscreenTextEditor + Desktop enhancement | EQUIVALENT | EQUIVALENT |
| Keyboard / focus / IME interaction | ChatScreen/platform input | EQUIVALENT | EQUIVALENT |
| Previous / First / Next / Bottom + reading position | ChatScreen + Desktop navigation additions | EQUIVALENT | EQUIVALENT |
| Message copy/edit/delete | ui/chat/domain | EQUIVALENT | EQUIVALENT |
| speaker tags/history runtime | SpeakerTagHistory | EXACT | EXACT |
| Roleplay chat presentation | RoleplayContentSegments/ChatBubble | EQUIVALENT | EQUIVALENT |
| speaker/avatar presentation | ChatBubble | EQUIVALENT | EQUIVALENT |
| reasoning folded presentation | ChatBubble | EQUIVALENT | EQUIVALENT |
| segmented Assistant bubble presentation | ChatBubble | EQUIVALENT | EQUIVALENT |
| message format repair | MessageFormatRepairService | EXACT | PENDING |
| Home session list/open/pin/rename | ui/home | EQUIVALENT | EQUIVALENT |
| Home session placeholder rendering | ui/home | EQUIVALENT | EQUIVALENT |
| Archived-session character relink | ui/chat | EQUIVALENT | EQUIVALENT |
| Session extra WorldBook settings | ui/chat/SessionSettingsContent | EQUIVALENT | EQUIVALENT |
| Session duplicate | SessionCopyService | EXACT | PENDING |
| JSON persistence | JsonFileStorage | EXACT | EXACT |
| atomic writes | JsonFileStorage | EXACT | EXACT |
| Desktop data directory | Desktop platform | EQUIVALENT | EQUIVALENT |
| Portable Mode | Desktop enhancement | EQUIVALENT | EQUIVALENT |
| backup snapshot/restore foundation | Desktop enhancement | EQUIVALENT | EQUIVALENT |
| data-root switch/migration | Desktop enhancement | EQUIVALENT | EQUIVALENT |
| automatic backups + settings discoverability | Desktop-only data-safety enhancement, not Android parity | EQUIVALENT | EQUIVALENT |
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
| Unified in-app import entry | Desktop platform / shared classifier | EQUIVALENT | EQUIVALENT |
| Shared file import classifier/domain core | shared-import | EXACT | EXACT |
| ACTION_SEND/VIEW ingress | Android intents | EQUIVALENT | PENDING |
| Desktop global drag/drop | Phase 15 OS integration | EQUIVALENT | PENDING |
| Open With / file association / OS registration | Phase 15 OS integration | EQUIVALENT | PENDING |
| global external-ingress UX | Phase 15 OS integration | EQUIVALENT | PENDING |
| Phase-6 core AppSettings / secure credentials / bubble scale / history-status exclusion | ui/manage/entities | EQUIVALENT | EQUIVALENT |
| Later-domain settings (P7–P14) | respective future domain | EQUIVALENT | PENDING |
| Windows integrated titlebar / native picker / second-instance UX | Desktop platform | EQUIVALENT | EQUIVALENT |
| Responsive navigation / layout | Desktop platform | EQUIVALENT | EQUIVALENT |
| Tutorial/help | P17 TutorialScreen | EQUIVALENT | PENDING |
| Crash diagnostics | diagnostics | EQUIVALENT | PENDING |
| Background AI platform/tray/notification completion | P15 FGS/platform adapters; accepted TaskRuntime foundation separate | EQUIVALENT | PENDING |
| Network-loss cancellation / broader platform hardening | P15/P17 background/model; accepted chat runtime separate | EXACT/EQUIVALENT | PENDING |
| App update check | update | EXACT | PENDING |
| APK install | Android installer | EQUIVALENT | PENDING |
| Danbooru catalog update | update | EXACT | PENDING |
| Desktop installer | Compose Desktop | EQUIVALENT | PENDING |
| Upstream watcher | Desktop downstream | Desktop-only | PENDING |
| Upstream compatibility report | downstream tooling | Desktop-only | PENDING |

## Phase 6 accepted scope and future ownership

S4 六项历史 blocker 已关闭。Primary Chat 的 session/search/settings、roleplay/speaker/avatar/alternatives、message/per-segment actions、archived relink、WorldBook binding、reading/navigation 与 full-screen composer 已接受；普通 Continue button 不暴露，不删除 shared blank-continuation contract。
S5/S6/S7 manual editors、S8 management/presets/Complete Preset Restore/ModelTemplate/typed-unified in-app ingress、S9 Windows chrome/native picker/second-instance/responsive/input/settings 已接受。

- EXACT Entity/Package/runtime rows表示共享 authority 或已验证合同，不是 Desktop UI 逐像素复制。
- FREEFORM/STRUCTURED 字段保留、default Format embed 与 Package transfer 已验证；Fish voice runtime 仍属 P8。
- Automatic Backup runtime + settings 为 **Desktop-only data-safety enhancement**；不冒充 Android parity，P17 Backup/Recovery Center 未随之完成。
- Virtual Conversation Scrollbar：**PENDING / explicitly deferred** until realistic long/cross-device histories；owner 为后续独立 Desktop UX 评估，最终 slice 未冻结，不是 Phase 6 defect。
- Cross-device Full Data Portability / BYOC Sync：**PENDING**，D-035 仅冻结设计方向，约 P11 后重访，不冻结 schema/phase。
- formal 1.4.1 验证范围未因 observed 1.4.4 升级；Phase 6 closure 不等于所有未来 domain 的 1.0 gate。

| Owner | PENDING 后续范围 |
|---|---|
| P7 | Image Resources / NovelAI / chat background / image workspace、图像处理与目录更新 |
| P8 | Fish Audio / audio / QQ voice feasibility；现有 Entity 字段保留不等于 voice runtime 完成 |
| P9 | RAG / embedding / vector / document / chat-memory retrieval |
| P10 | Long-Term Memory / Episode / Arc / Era / Archive / HEAD / Gap |
| P11 | SaveSlot / Session Lifecycle / session duplicate；SaveSlot media 协同 P7/P8 |
| P12 | AI Authoring / Character、Format、WorldBook AI / research / Message Format Repair |
| P13 | Moments |
| P14 | Community / Discord OAuth |
| P15 | OS integration / external ingress FIFO / drag-drop / Open With / file association / installer-updater / crash diagnostics / AI request-log user surface / tray-notifications |
| P16 | Upstream watcher / compatibility automation |
| P17 | Tutorial/onboarding / beta hardening / Backup-Recovery Center |


历史 Phase 3 的窄 typed transfer / interoperability 与 Phase 5 shared Model/Settings/provider/real-chat foundation 保持 accepted；旧“ModelTemplate/management/settings 尚未完成”只描述当时 slice，不再代表当前状态。

## 1.4.x parity contracts

以下合同细化 formal 1.4.1 功能域；当前完成状态以上表为准，未来 domain 保持 PENDING。

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
