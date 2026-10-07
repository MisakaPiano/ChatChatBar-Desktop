# Upstream → Desktop Compatibility Map

本文件用于上游更新时快速判断影响范围。

> CURRENT compatibility map。Phase 5 model/provider/real-chat foundation 已 **COMPLETE / ACCEPTED**；Phase 6 user-surface/editor parity **COMPLETE / ACCEPTED**。公开 compatibility claim 仍仅绑定 formal validated upstream `1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。Phase 3/4/5 historical contracts 分别由 `22_PHASE3_CONTRACT_AUDIT.md`、`28_PHASE4_CONTRACT_AUDIT.md` 与 `30_PHASE5_CONTRACT_AUDIT.md` 记录。

> 当前 mirror master 已到 upstream `1.4.4 @ 550409689df8c51f459fb50b4e04c8ac2fa4bf35`；`sync/1.4.4 @ 9b6378dbb595dd2f3ff5143a7a8e46653c99e721` 为 **PARKED FUTURE SYNC**，未合入 Desktop、未提升 formal baseline、不得阻塞 Phase 7。后续仅在真正打开 batch sync window 时按 `15_SYNC_PLAYBOOK.md` 处理。

各 Phase 3/4/5 contract-control 段落中的“尚未实现/next”是该 slice 当时的历史记录；Phase 0–6 accepted 状态不变；P7 final-product feature/current gate 以 `59_PHASE7_FINAL_PRODUCT_REVIEW.md` 为准；`53` 为历史 presentation 证据，`44_PHASE7_R2_REVIEW.md` 为此前 R2/R1 证据，`38_PHASE7_FINAL_REVIEW.md` 保留 pre-R2 证据；future owners 仍按 roadmap。

## Phase 7 current implementation boundary

CURRENT final-product closure (`54`/`56`, evidence `59`) follows the third UX HOLD. Provider drift owner remains `49` + this file + release gate `20`, with exact exclusions/reopen conditions in `55`. Complete Studio presets/global IA belong to P17; Moments P13, Community P14, OS integration P15. This closure adds no capability rules and does not promote the formal baseline.

P7-A/P7-B, pre-R2 validation and R2/R1 semantic implementation passed Project review. The second user manual review held the presentation; `48`/`50` then refined Desktop controls and annotation on the same feature/runtime (historical evidence `53`). The third manual UX HOLD led to the current `54`/`56` closure. Phase 7 is not accepted or merged. `49` records provider drift only: this task changes no model/sampler enum, API id, capability filtering or HTTP behavior. Final-product closure has no sharedCore/Android production diff from `a0918de`.

R2 follows `ImagePromptToolScreen`: one Studio workspace with auxiliary AI Design, History, settings and image-oriented guidance/tools. The private `desktop_character_backgrounds` authority adds a per-character library/preferred image; render order is session override → readable Desktop preference → official Character background. It never changes Entity/Package fields or the cross-platform meaning of `CharacterCard.chatBackground`. The additive re-audit `47` relocates the exact JVM-neutral `NovelAiHistoryFilter.kt` and `NovelAiAccountUiState` from Android into sharedCore, preserving package/call sites and algorithms. Desktop adds missing workflow wiring and safe image-list editing/deletion; Prompt literals, Designer protocol, automatic eligibility and NovelAI HTTP semantics stay unchanged. Exact-candidate cleanup and indeterminate-authority retention apply to the library and edited messages.

SharedCore now owns NovelAI HTTP/Prompt/tag authority, Studio and design persistence, Vibe encoding, APNG framing, focused-inpaint and raster algorithms, and catalog check/validation/index construction. Android retains thin Context/Bitmap/SQLite/proxy facades; Desktop owns AWT/JDBC/native image UX and Windows SecretStore. Generated assistant images do not enter main-chat multimodal history. Crop/export-only cover state stays Desktop-private. No Entity/Package schema promotion.

Formal baseline remains 1.4.1 with only the explicit `ace632c...` safety exception detailed below. This is not 1.4.4 compatibility validation. Live NovelAI evidence remains 1/8; P7-C uses fake/local evidence only.

| Upstream | 责任 | Desktop 策略 |
|---|---|---|
| `AGENTS.md` | 全局开发规则 | 保留，追加极小 Desktop 入口说明 |
| `.agents/skills/chatbar-feature-map` | 功能地图 | 同步阅读 |
| `.agents/skills/chatbar-prompt-pipeline` | Prompt 不变量 | EXACT |
| `.agents/skills/chatbar-model-request-runtime` | Provider/SSE | EXACT |
| `.agents/skills/chatbar-image-generation-runtime` | NovelAI/image | shared + image adapter |
| `.agents/skills/chatbar-fish-audio-voice` | Fish | shared + audio/secret adapter |
| `.agents/skills/chatbar-long-term-memory` | Memory | EXACT |
| `.agents/skills/chatbar-save-slot` | SaveSlot | EXACT protocol + image adapter |
| `.agents/skills/chatbar-moments` | Moments | EXACT domain + runtime adapter |
| `.agents/skills/chatbar-community-platform` | Community | shared backend + OAuth adapter |
| `.agents/skills/chatbar-shared-import` | external import | EXACT classifier + Desktop ingress |
| `ChatBarApp.kt` | composition root | Android 保留；Desktop 自建 root |
| `MainActivity.kt` | Android lifecycle/intents | Desktop 重做 |
| `Navigation.kt` | Android navigation | Desktop shell 等位 |
| `sharedCore/.../JsonFileStorage.kt` | JSON persistence | authoritative implementation；Android 使用 Path root + no-op gate，Desktop 使用同一实现 + `DesktopDataOperationCoordinator` gate |
| `sharedCore/.../data/local/entity/CharacterCard.kt` | Character Entity contract | authoritative shared EXACT；Android duplicate removed |
| `sharedCore/.../data/local/entity/FormatCard.kt` | FormatCard Entity contract | authoritative shared EXACT；ordered userTools/defaults preserved |
| `sharedCore/.../data/local/entity/WorldBook.kt` | WorldBook Entity contract | authoritative shared EXACT；WorldBook engine/request planner 已由 Phase 4 共享 |
| `sharedCore/.../data/repository/{Character,FormatCard,WorldBook}Repository.kt` | repositories | authoritative shared implementations over shared `JsonFileStorage` |
| `sharedCore/.../data/local/entity/{ChatSession,ChatMessage}.kt` | chat persistence Entity contract | authoritative shared EXACT；字段/default/nullable/source-turn/timeline contract 保持；Android duplicate authorities removed |
| `sharedCore/.../data/repository/ChatRepository.kt` | session/message persistence and queries | authoritative shared implementation over shared `JsonFileStorage`；Android duplicate authority removed |
| `sharedCore/.../data/local/entity/{ModelConfig,PresetModelCatalog}.kt` | Model value / serialized preset contract | P5-S1 authoritative shared EXACT；logical IDs、defaults、nullable/custom/thinking/output-token/preset metadata 保持 formal baseline semantics |
| `sharedCore/.../data/repository/ModelRepository.kt` | Model/Embedding repository | P5-S1 authoritative shared implementation；Android default identity storage key；Desktop 可注入 Windows-safe model-only storage-key policy |
| `sharedCore/.../data/repository/ModelStorageKeyPolicy.kt` | logical Model ID → physical repository key | Android identity；Desktop `ccb-model-v1-<lowercase UTF-8 hex>`；不得泄漏 physical key 到 Entity/settings/relationship/UI/Prompt/provider |
| `sharedCore/.../domain/chat/{ChatMessageOrdering,ChatMessageOrderRepairPolicy,TimelineTurnPolicy,SessionDisplayTitlePolicy}.kt` | pure repository/chat policies | authoritative shared implementation；Android 与 Desktop callers 共用 |
| `sharedCore/.../domain/chat/CharacterSessionService.kt` | Character → Session creation / greeting authority | authoritative shared implementation；Android stale-format warning 通过窄 callback 保持原 `Log.w` tag/text；Desktop 使用同一 service + repository authority |
| `data/security/*CredentialStore.kt` | Android Keystore | Desktop SecretStore |
| `sharedCore/.../domain/card/CardTransferModels.kt` | Package schema | authoritative shared EXACT；Character 9/read 3..9、Format 2/read 1..2、WorldBook 1 |
| `sharedCore/.../domain/card/CharacterCardTransferCore.kt` | Character package↔entity authority | authoritative shared transfer/materialization core；normal success semantics 保持 upstream，D-029 destructive failure safety 由同一 core 统一执行 |
| `sharedCore/.../domain/card/CharacterResourceStore.kt` | Character owned-resource boundary | authoritative JVM-neutral boundary；resource ownership、rollback 与严格 durable delete contract 在 shared 层定义 |
| `app/.../domain/card/CharacterCardTransferService.kt` | Android Character transfer facade | thin Android facade；继续提供 absolute local references、`asset:` resolution、Prompt/RAG adapters，不保留第二套 transfer authority |
| `app/.../domain/card/AndroidCharacterResourceStore.kt` | Android Character resource adapter | Android absolute-path / bundled-asset implementation；行为保持 upstream platform contract |
| `desktopApp/.../DesktopCharacterResourceStore.kt` | Desktop Character resource adapter | D-027 app-data-root-relative references；所有 filesystem transaction 通过与 `JsonFileStorage` 相同的 `DesktopDataOperationCoordinator` gate |
| `domain/card/CharacterCardPngRenderer.kt` | CCB PNG cover | Android renderer 保持 Android-owned；Desktop 使用已验收的 AWT platform-equivalent renderer |
| `sharedCore/.../domain/card/PngTextChunks.kt` | PNG metadata codec | authoritative shared EXACT；`CharacterCardPngPackageCodec` 负责 exact CCB PNG payload insertion，`CharacterCardPngExportOptions` 为 shared JVM authority |
| `sharedCore/.../domain/card/FormatCardUserToolValidator.kt` | Format Package validation | authoritative shared validator；Android runtime policy delegates |
| `sharedCore/.../domain/card/FormatCardTransferService.kt` | FormatCard transfer | authoritative shared EXACT；Android 与 Desktop typed transfer 共用同一实现 |
| `sharedCore/.../domain/card/FormatCardUserToolPolicy.kt` | Format Prompt runtime | Phase 4 shared authority；random/append/strong suffix semantics unchanged |
| `sharedCore/.../domain/card/SillyTavernCardParser.kt` | ST Character JSON/PNG parser | authoritative pure V1/V2 + Chara tEXt authority；Android Uri/ContentResolver ingress 留在 thin adapter |
| `sharedCore/.../domain/card/SillyTavernCardMapper.kt` | ST → Character Package mapping | authoritative shared mapper；schema 5、FREEFORM、placeholder/greeting/book semantics 不变；Prompt/log 通过窄 seam 注入 |
| `sharedCore/.../domain/card/SharedImportClassifierCore.kt` | content-first classifier | authoritative candidate order 与 shared Package/ST strict decoding |
| `app/.../domain/card/SharedImportClassifier.kt` | Android classifier facade | Android platform classifier facade；shared Model/ModelTemplate contract 与 provider authority 已完成，Desktop typed ModelTemplate transfer 在 Phase 6 完成 |
| `sharedCore/.../domain/card/WorldBookTransferService.kt` | WorldBook transfer / ST World Info codec | authoritative shared EXACT；World Info object form、Character Book array form 与 ST export 保持 upstream 行为；Android 与 Desktop typed transfer 共用同一实现 |
| `sharedCore/.../domain/prompt/CharacterNaiPromptDefaults.kt` | Character NAI default-negative Prompt authority | 3P authoritative shared Prompt-domain source；Android `PromptTemplates` 保留 upstream-compatible facade 并委托 shared authority |
| `sharedCore/.../domain/rag/{VectorChunk,ChunkSourceType}.kt` | serialized RAG persistence types | shared serialized contract；Android `RagRepository` 仍拥有完整 Android RAG repository/runtime，Desktop 仅实现 Character DOCUMENT cleanup |
| `desktopApp/.../DesktopTypedTransferController.kt` | typed Character/FormatCard/WorldBook transfer | shared transfer services + Windows native Common Item Dialog adapter + safe sibling-temp replace writer；typed/unified in-app ingress 已完成，不等同于 P15 OS/global external routing |
| `sharedCore/.../domain/worldbook/{WorldBookEngine,WorldBookScanContext,WorldBookRequestPlanner}.kt` | WorldBook pure runtime / request matching / request orchestration | authoritative shared EXACT；4B1/4B2 已移除 Android-only runtime authority；Android ChatViewModel thin delegation，Desktop 使用同一 planner |
| `sharedCore/.../domain/prompt/MainChatPromptAuthority.kt` + Android `PromptTemplates` facade | Phase 4 main-chat Prompt text/builders | authoritative shared EXACT；4P complete；Prompt literal/runtime zero-drift；non-main-chat Prompt families remain Android-owned |
| `domain/chat/PromptAssembler.kt` | Prompt assembly | EXACT |
| `sharedCore/.../domain/chat/{ContextWindowManager,PlaceholderRenderer}.kt` | history/context grouping + placeholder rendering | authoritative shared EXACT；Android/Desktop 共用；4B1 为 byte-identical production move |
| `ui/chat/ChatViewModel.kt` | final orchestration | 抽 domain orchestrator，UI 各自调用 |
| `domain/chat/StreamingChatService.kt` | transport | JVM shared |
| `domain/model/*` | model resolution/discovery | Phase 5 shared/runtime authority 已完成；Phase 6 user-surface parity 单独管理 |
| `domain/ProxyAwareClient.kt` | HTTP client | JVM shared，平台 proxy 再审计 |
| `domain/rag/*` | RAG | shared |
| `domain/memory/*` | long-term memory | shared |
| `domain/chat/SaveSlotPackageStorage.kt` | `.cbsave` | shared protocol + filesystem/image adapter |
| `domain/image/*` | NovelAI/image | 分解：network/policy shared；bitmap/storage adapter |
| `domain/image/DanbooruTagCatalog.kt` | Danbooru dataset/query/integrity | 数据集、查询和排序语义保持；Android SQLite/file installation 属于平台边界 |
| `domain/image/NovelAiBundledDictionary.kt` | bundled dictionary install/query | dataset version、完整性和查询语义保持；Android SQLite/file installation 属于平台边界 |
| `domain/image/RankedTagIndex*` | completion index build/read lifecycle | 索引格式、排序和结果语义保持；Android SQLite/index lifecycle 属于平台边界 |
| `domain/voice/Fish*` | Fish Audio | shared API + storage/playback adapter |
| `domain/voice/qq/*` | QQ accessibility transfer | Desktop 特殊等位/阻塞 |
| `domain/service/*` | Android background protection | Desktop TaskRuntime |
| `domain/moment/*` | Moments | policy/generation shared；alarms适配 |
| `domain/community/*` | Community | shared service；OAuth/cache UI适配 |
| `domain/update/*` | update | checker可共享，installer重做 |
| `ui/character/*` | card edit UI | Desktop UX 等位 |
| `ui/worldbook/*` | worldbook UI | Desktop UX 等位 |
| `ui/model/*` | model UI | Desktop UX 等位 |
| `ui/imageprompt/*` | image Studio | Desktop UX 等位 |
| `ui/moments/*` | Moments UI | Desktop UX 等位 |
| `ui/community/*` | Community UI | Desktop UX 等位 |
| `ui/kit/*` | UI primitives | 能兼容 Compose Desktop 时复用 |
| `utils/DebugLogManager.kt` | request debug | shared/domain + Desktop viewer |
| `utils/diagnostics/*` | crash info | shared report model + OS adapter |

## Phase 3 contract control

- Package / Entity / Prompt 三层边界、schema matrix、ST/PNG contract、repository semantics 与 test inventory：`22_PHASE3_CONTRACT_AUDIT.md`。
- D-027：Desktop-owned resource reference 使用 app-data root-relative representation。
- D-028：Character transfer Prompt dependency 使用 authoritative narrow policy；不复制 Prompt 文本。
- D-029：正常成功语义保持 parity；destructive failure path 可以做窄 data-safety hardening，并记录/report upstream。
- 3B1 shared Entity / Package contract core：**COMPLETE / PROJECT REVIEW PASS**，implementation `367a8ce7432bafbb926a176e23886c765b12a8f7`。
- 3B2 FormatCard + WorldBook transfer core：**COMPLETE / PROJECT REVIEW PASS**，implementation `8a213233dcce9db1b58afef50f7ee2fa14c9e0ad`；两个 Android-local production duplicates 已移除。
- 3C1 Character resource / materialization core：**COMPLETE / PROJECT REVIEW PASS**，implementation `783af9a10f6d95c618d7ffb81a7fa2949f65b9ab`，strict durable delete repair `74f9af25270f2bf893a4bd3699613b0037e71403`。
- 3C1 已实现 shared transfer/materialization authority、Android/Desktop resource adapters、D-027 root-relative Desktop persistence 与 D-029 failure hardening；Prompt/RAG final wiring、ST Character、PNG visual renderer 与 user-facing import/export 仍未完成。
- 3C2 ST Character + classifier split：**COMPLETE / PROJECT REVIEW PASS**，implementation `d78d76df656fa3ce9fd309a6cbe52c6cfb379f30`；Android 只保留 Uri ingress、Prompt/log wiring 与 typed ModelTemplate facade。
- 3P Prompt ownership closure：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**，implementation `f722703c33d8cd96728fc06ff617c9d7d79d9c7d`；D-028 已由 `CharacterNaiPromptDefaults` + `AuthoritativeCharacterTransferPromptPolicy` 关闭，Prompt literal/runtime behavior 未改变。
- 3D Desktop typed transfer：**COMPLETE / PROJECT REVIEW PASS / PACKAGED PASS / MANUAL ACCEPTANCE PASS / INTEGRATED**，implementation `d8987605e733b07a5deac1901ee049011d47d153`；Character CCB JSON/PNG、ST Character、FormatCard JSON 与 WorldBook ChatBar/ST typed transfer 已交付。
- 3D 的 shared classifier / typed management ingress 不代表 global SharedImport FIFO、ACTION_SEND/VIEW、drag/drop/Open With、ModelTemplate Desktop import 或完整 management UI 已完成；这些不是 3D 的完成声明；Phase 6 已完成 ModelTemplate/management/in-app ingress，OS/global external ingress 仍为 P15。
- 3F Android ↔ Desktop interoperability gate：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**，test checkpoint `717ec1a473660b5d186a4241778552bc6f12a80b`；真实 API 34 与 API 36 targeted device tests 验证 Character JSON/CCB PNG、STRUCTURED/FREEFORM、多角色、图像/UTF-8 文档、embedded WorldBook/default FormatCard/ordered tools、Fish binding、standalone FormatCard/WorldBook、ST World Info、ContentResolver/FileProvider ingress 与 corrupt/invalid atomicity 的双向互操作，未发现 production interoperability defect。
- 3F 没有改变 production source 或 formal upstream baseline；compatibility claim 仍只绑定 validated upstream `1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。完整 API 36 `connectedDebugAndroidTest` 未跑到结束，已复现的非 3F instrumented debt 不得表述为全套绿色。

## Phase 4 contract control

- controlling audit：`28_PHASE4_CONTRACT_AUDIT.md`；audit **COMPLETE**。
- 4A1 Shared Chat Entity Contract Core：**COMPLETE / PROJECT REVIEW PASS**，implementation `f850ece3df7f36391b1fa4e81c286110f9610844`。
- 4A2 Shared Chat Repository + Session Creation：**COMPLETE / PROJECT REVIEW PASS**；P4-S2 implementation `bfcd37e2f1f316ae60f733c0146846f66a4f76b4`。
- authoritative shared `ChatRepository`、repository/chat pure policies 与 `CharacterSessionService` 已完成；DesktopAppContainer 使用同一 shared ChatRepository/service authority。
- Android-local ChatSession / ChatMessage / ChatRepository / CharacterSessionService / pure-policy duplicate authorities 已移除。
- session creation 保持 missing-character failure、current card-name title、live/stale default Format binding、existing-session independence 与 blank/nonblank opening ASSISTANT greeting semantics。
- 4B Shared Context + WorldBook Request Runtime：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**。
- 4B1 implementation `d468f82529ec6e9fa24363e07d1e7fafe6c9ecf0`：authoritative shared `PlaceholderRenderer`、`ContextWindowManager`、`WorldBookEngine`、`WorldBookScanContext`；四个 production moves byte-identical。
- 4B2 implementation `8867424df3d53bd291b6b361e51aa5059257284e`：authoritative shared `WorldBookRequestPlanner`；source resolution、duplicate-ID precedence/order、scan snapshot、transient current input、character tokens、composite/legacy timed-state compatibility、unified prompt/outlets 与 updated timed state 均由 shared authority 负责；Android ChatViewModel thin delegation，Desktop container 使用同一 planner。
- 4B2 validation：shared planner **14 PASS**；Desktop integration **1 PASS**；sharedCore **44 suites / 294 tests**；desktopApp **30 suites / 256 tests**；Android JVM **167 suites / 1076 tests**；全部 0 failures/errors/skipped；Desktop/Android compile 与 `git diff --check` **PASS**。
- 4P Main-chat Prompt Ownership Closure：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**；implementation `e927277dabb206aa34b2374e3596c67a31f0f7db`。
- authoritative shared `MainChatPromptAuthority` 现拥有 Phase 4 main-chat section labels、system/post-history templates、CCB handshake/tail、current-turn requirements、continue prompt、reply helpers、FormatCard suffix builders 与 chat-memory RAG usage note；Android `PromptTemplates` 保留 upstream-compatible facade。
- Project review 对迁移前 base 与 shared authority 做直接零漂移核对：27 个 literals/section constants 逐字符相等，12 个 builders/helpers 保持实现语义；Android moved-literal duplicate removed；observed upstream `354f151...` Prompt drift 未吸收。
- 4P validation：shared authority **16 PASS**；Android `PromptTemplatesTest` **23 PASS**；sharedCore **45 suites / 310 tests**；desktopApp **30 suites / 256 tests**；Android JVM **167 suites / 1078 tests**；Desktop/Android compile 与 `git diff --check` **PASS**。
- 4C Shared Prompt + Logical Request Assembly：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**；implementation `8bd876bb28c19b39f96a3abb109698132912d9a2`。
- authoritative shared logical-request core now includes `PromptAssembler` / `PromptCachePromptLayers`、`PromptCacheKeyFactory`、`ChatHistoryPromptPolicy`、`ChatRequestMemoryPolicy`、`FormatCardUserToolPolicy`、`RetrievedKnowledgeCard`、`FormatPromptPosition`、`ChatApiMessage`、minimal `RoleplayStatusStripper` 与 `MainChatRequestAssembler`；Android production duplicates removed where owned by 4C。
- Android `ChatViewModel` now resolves platform/runtime inputs and delegates authoritative final logical-message ordering/cache-key derivation to shared `MainChatRequestAssembler`；Desktop container constructs the same shared `PromptAssembler` + `MainChatRequestAssembler` zero-write。
- Project review confirmed authoritative order, START/END/BOTH、Archive/history/memory/HEAD/previous-turn、current USER exactly-once、STRONG_PROMPT_SUFFIX placement、stable-prefix cache boundary and transport separation；`StreamingChatService` transport behavior unchanged except consuming moved `ChatApiMessage`。
- 4C validation：focused shared **48 PASS**；Desktop integration **1 PASS**；Android serialization/order **3 PASS**；sharedCore **52 suites / 358 tests**；desktopApp **31 suites / 257 tests**；Android JVM **163 suites / 1030 tests**；Desktop/Android compile 与 `git diff --check` **PASS**。
- Prompt literals/builders unchanged；observed upstream `354f151...` drift not absorbed；Provider/network/SSE、Package/schema/version unchanged。
- 4D1 Desktop Fake Chat Runtime：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**；implementation `06b5243282627e5954d5ad4a4c6e6f846a30400d`。
- Desktop `DesktopFakeChatRuntime` 以 real persisted Desktop data 串接 shared `CharacterSessionService` / `ChatRepository` / `ContextWindowManager` / `WorldBookRequestPlanner` / `PromptAssembler` / `MainChatRequestAssembler`，fake driver 只捕获 transport-neutral logical `ChatApiMessage` + logical cache key，不进入 Provider/network/SSE。
- session create/open、blank/nonblank greeting persistence、normal USER persist-before-capture、persisted-USER rebuild、explicit effective context-window authority、session player precedence、FormatCard fallback、deterministic WorldBook trigger/timed-state writeback、RANDOM_NUMBER request-only 与 STRONG_PROMPT_SUFFIX final placement 均有 Desktop integration coverage。
- restart-persistence gate：第二个 `DesktopAppContainer` 使用同一 app-data root 重开相同 session，greeting/USER 顺序保持；rebuild logical messages 与 cache key 与第一次一致；message count 不增加。
- 4D1 validation：focused fake runtime **10 PASS**；CharacterSession Desktop integration **1 PASS**；WorldBook planner Desktop integration **1 PASS**；sharedCore **52 suites / 358 tests**；desktopApp **32 suites / 267 tests**；Android JVM **163 suites / 1030 tests**；Desktop/Android compile 与 `git diff --check` **PASS**。
- Prompt/PromptAssembler/Package/schema unchanged；fake ASSISTANT 不持久化；observed upstream `354f151...` drift 未吸收。
- 4D2 Prompt Inspector + Phase 4 Acceptance：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED / PACKAGED MANUAL ACCEPTANCE PASS**；implementation `0a89b111e93c3c1a2b9a24127608e0b25e4ef9c4`。
- read-only Desktop Prompt Inspector uses common `DesktopChatRequestPlanner` + shared `MainChatRequestAssembler` trace authority；logical messages/roles/source/cache-prefix/key and WorldBook debug evidence come from the authoritative planning/assembly path, not a second preview pipeline。
- Inspector read-only path uses non-repairing repository reads；zero-write regression proves no message-index repair/recreation, no session/message/WorldBook/timed-state persistence, and byte-identical app-data snapshot after inspection。
- 4D2 validation：MainChatRequestAssembler **6 PASS**；Desktop Prompt Inspector **5 PASS**；existing DesktopFakeChatRuntime **10 PASS**；CharacterSession Desktop integration **1 PASS**；WorldBook planner Desktop integration **1 PASS**；sharedCore **52 suites / 358 tests**；desktopApp **33 suites / 272 tests**；Android JVM **163 suites / 1030 tests**；Desktop/Android compile + `git diff --check` **PASS**；`:desktopApp:createDistributable` **PASS**。
- packaged manual acceptance：user confirmed all executable manual UI/smoke checks PASS。Populated-session Inspector manual visual scenario was **NOT RUN** because no disposable persisted Desktop chat fixture was available；equivalent persisted-session/request/WorldBook/cache/read-only semantics are covered by real-repository automated integration tests and PASS。
- Phase 4：**COMPLETE / ACCEPTED**。Compatibility claim remains bound to formal baseline ChatBar 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`；observed upstream `354f151...` remains HIGH / NO SYNC and is not included in this acceptance。
- next stage：**Phase 5 — Model Runtime + Real Chat**。

## Phase 5 contract control

- controlling audit：`30_PHASE5_CONTRACT_AUDIT.md`；Phase 5 **COMPLETE / ACCEPTED**。
- P5-S1 / 5A1：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**；implementation `dd68b714c58254b7ef994b106dafebf649623bf6`，docs closeout `39268a7c2bc64b18ec034dc8dd8222472d2c9095`；`desktop` contains both reviewed commits unchanged in ancestry。
- shared Model value/repository contract is authoritative in sharedCore；Android consumes it through unchanged packages and identity storage-key behavior。
- Desktop model physical key policy is platform-equivalent only：logical `ModelConfig.id` stays unchanged while Windows physical filenames use `ccb-model-v1-<lowercase UTF-8 hex>`。
- shared logical request authority remains Phase 4-owned；Phase 5 completed shared provider serialization/auth/SSE authority, shared Settings/effective resolution, model discovery, and Windows-protected Desktop SecretStore credential persistence。
- Desktop real-chat runtime consumes the shared request/provider authorities；Desktop TaskRuntime supplies platform-equivalent task lifetime, stop, and shutdown ownership。Vertical acceptance later completed at `c6a3805698faceb8ed7e7ce36af6f49263b7b517`。
- Phase 5 completion makes no compatibility claim for Phase 6 user surfaces。Formal compatibility remains `1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8` only。

## Phase 6 final accepted control

- accepted production feature：`feature/phase6-s9-desktop-ux @ 86be0b0ec21aab7a8f15553c0b696b253738917f`。
- integration：从 `desktop @ 5850fe28d233fb1b64a71b35e1e5f5d8d44db21d` FF-only 至 accepted feature + docs finalization；no extra merge commit，master 不变。
- S4 Primary Chat：session browser/search/pin/rename/settings、model/runtime selection、roleplay narration/dialogue/thought/status、speaker/avatar、reasoning disclosure、alternatives、copy/edit/delete/regenerate/retry、per-segment actions、archived Character relink、session WorldBook binding、reading/navigation、full-screen composer；无 ordinary Continue button（保留 shared blank-continuation runtime contract）。
- S5 / S6 / S7：Character / FormatCard / WorldBook management + manual editors **COMPLETE / ACCEPTED**。
- S8：management CRUD、typed/unified in-app ingress、bundled presets、Complete Preset Restore、ModelTemplate transfer **COMPLETE / ACCEPTED**；不包含 OS external ingress。
- S9：integrated/native titlebar、native picker、second-instance UX、responsive layout、Previous/First/Next/Bottom、reading position、resizable/collapsible/full-screen composer、keyboard/focus/IME closure、AppSettings exposure、Automatic Backup settings discoverability **COMPLETE / ACCEPTED**。
- final evidence：desktopApp **99 suites / 909 PASS / 0 failures-errors-skipped**；Desktop compile / diff-check / final createDistributable / packaged launch **PASS**；capture **BLOCKED_ENVIRONMENT**；Project + 用户 final manual acceptance **PASS**。
- Automatic Backup 是 Desktop-only safety enhancement；Virtual Conversation Scrollbar 明确 deferred，不是 Phase 6 defect。
- 后续 PENDING owners：P7 Image/NovelAI/background，P8 Fish，P9 RAG，P10 Memory，P11 SaveSlot/lifecycle，P12 AI Authoring/repair，P13 Moments，P14 Community，P15 OS ingress/updater/installer/crash/request-log surface，P17 Tutorial/onboarding/Backup-Recovery Center/hardening；完整列表见 `13_FEATURE_PARITY.md`。
- BYOC full-data portability 仅为 D-035 design direction，schema 与最终 phase/slice 未冻结。
- 仅 formal **1.4.1** compatibility validated；不声明 observed **1.4.4** 兼容。

## 官方 Skill Inventory（baseline 1.4.1）

validated baseline `.agents/skills/` 共 20 个 Skill。每次 upstream sync 都应重新枚举，不能把此清单当永久固定值。

| Skill | 主要责任 | Desktop 映射 |
|---|---|---|
| `chatbar-app-update` | APK/update/release update flow | checker 可共享；Windows installer/updater 等位 |
| `chatbar-background-work-runtime` | FGS/network guard/background AI work | Desktop TaskRuntime / tray / notification 等位 |
| `chatbar-character-card-ai` | 角色卡 AI fill/rewrite/image-to-appearance/cover | EXACT domain；图像/文件平台适配 |
| `chatbar-community-platform` | Community/Supabase/Discord OAuth | shared backend + Desktop OAuth adapter |
| `chatbar-emulator-test` | Android emulator/device verification | Android 回归专用；Desktop 另建运行/打包验证 |
| `chatbar-feature-map` | 官方功能入口地图 | 每次任务 first-hop，同步阅读 |
| `chatbar-fish-audio-voice` | Fish API/voice/binding/playback/SaveSlot | shared domain + audio/secret adapter |
| `chatbar-format-card-ai` | FormatCard AI | EXACT |
| `chatbar-image-generation-runtime` | NovelAI/image runtime/history/regeneration | shared policy/network + image adapter |
| `chatbar-long-term-memory` | Episode/Arc/Era/Archive/HEAD/Gap | EXACT |
| `chatbar-message-format-repair` | message repair runtime | EXACT |
| `chatbar-model-request-runtime` | Provider/auth/SSE/thinking/local HTTP | EXACT protocol/runtime；后台生命周期适配 |
| `chatbar-moments` | Moments generation/scheduler/images | EXACT domain + Desktop runtime scheduler |
| `chatbar-novelai-prompt` | NovelAI prompt/tag design | EXACT prompt/domain |
| `chatbar-prompt-pipeline` | Prompt ownership/order/final messages | EXACT；不得 Desktop 分叉 |
| `chatbar-release-publish` | Android release publish workflow | Android 发布参考；Desktop release 另建等位流程 |
| `chatbar-save-slot` | SaveSlot legacy + v8 streaming package | EXACT protocol + filesystem/image adapter |
| `chatbar-shadcn-compose` | UI kit/Compose styling | 视觉理念复用；Desktop UI 等位 |
| `chatbar-shared-import` | ACTION_SEND/VIEW/content classifier/FIFO | EXACT classifier + Desktop ingress |
| `chatbar-worldbook-ai` | WorldBook AI | EXACT |

### Validated 1.4.1 sync record

- declared validated baseline：`1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`
- observed upstream at the time of this sync record：`354f15166d8bc0462cb87d62a0ba4613794560a3`（formal baseline 之后 1 commit / 12 changed files）
- upstream drift：detected，尚未吸收
- sync urgency：HIGH；Project impact audit 已确认不影响 3C2，作为 compatibility debt 排入后续 selective/batch sync window
- inventory drift：detected；observed commit 触及 2 个 Skill，formal baseline Skill inventory 仍为 20
- compatibility status：formal baseline validated；observed upstream compatibility **not yet validated**
- source merge：`077286fd531eb794499c0bc3e8941b23fd3235b6`
- final validation sync HEAD：`c5fcac52c3b7249ac4d6ca51ef083c835b46b395`
- source integrity：PASS
- Desktop / Android compile：PASS
- sharedCore：11 suites / 114 tests PASS
- desktopApp：12 suites / 137 tests PASS
- Android JVM：186 suites / 1161 tests PASS

1.4.1 在已验证 1.4.0 合同上的 compatibility changes：

- Interrupted replies：assistant draft 在 body 或 reasoning 任一 nonblank 时可持久化；reasoning-only regeneration 的新选中版本保持 empty body，旧正文不会重新进入请求。
- Blank continue：latest persisted message 为 USER 时复用其 body / images / ID，排除出 history 且不重复持久化；latest 非 USER 时继续使用 request-only continuation prompt。
- Lifecycle ordering：interrupted/error cleanup 先完成 durable persistence、timeline refresh 与 same-ID streaming-state cleanup，再释放 responding gate。
- Model defaults：temperature default 为 `1.0`，common preset 提供 `reasoning_effort = low`；transport schema 未改变。
- Prompt：`PromptTemplates` 官方文本未变化；continuation pipeline/runtime semantics 改变，既有 START / END / BOTH placement 保持。
- Release metadata：`versionName = 1.4.1`，`baseVersionCode = 80`。

此次发生内容变化的 3 个 Skill 已与源码核对一致：`chatbar-long-term-memory`、`chatbar-model-request-runtime`、`chatbar-prompt-pipeline`。

### 固定基线同步策略

formal validated baseline、observed upstream、drift 与 sync urgency 分别记录。普通 drift 不自动阻塞 Desktop milestone；按 `LOW` / `NORMAL` / `HIGH` / `BLOCKING` 分类，并在 major milestone、Alpha/Beta/Release 前、drift 累积过大或 Project 触发时进入 sync window。公开 compatibility claim 只绑定 formal baseline；observed-but-unvalidated upstream 不得声明兼容。完整规则见 D-022。

### Skill drift 规则

- baseline 未变化时，Skill inventory 应与该 commit 保持一致。
- upstream commit 变化时，先重新枚举 `.agents/skills/*/SKILL.md`，再比较新增、删除、重命名与内容变化。
- 新增或变化的 Skill 必须映射到 `FEATURE_PARITY.md` / 本文件对应域；不能只记录目录名。
- Skill 变化本身不自动意味着 Desktop 已兼容；仍需按受影响功能运行 parity review/test。

## Currently observed upstream

- Project finalization observation：upstream **1.4.4**。
- formal validated baseline：**1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8**。
- action：**NO SYNC**；observed compatibility：**NOT VALIDATED**；1.4.4 未吸收，不声明兼容。
- 本轮不查询新的 upstream SHA / diff 统计；不把旧 observation 数字重贴为 1.4.4。
- 历史 2026-09-29 observation：`148b3a9637eadf577afbb4947158dd6f80e17f4f` / ahead 4 / 23 changed files / HIGH / NO SYNC / NOT VALIDATED；changed Skills 与 PromptTemplates main-chat/general literal edits 未吸收。此为历史记录，不改写此前 sync observations。
- future sync 仍须独立审查 D-030/shared Prompt authority、最终 logical/serialized request 与相关平台影响；Phase 6 acceptance 不替代 upstream sync gate。

## Resolved upstream anomalies（1.4.0，1.4.1 继续保持）

1. `AGENTS.md` 已区分 business JSON persistence 与 auxiliary SQLite：**FIXED**。
2. `chatbar-model-request-runtime` 的 START / END placement wording 已与实际 assembly 对齐：**FIXED**。
3. `chatbar-image-generation-runtime` 对不存在 `chatbar-web-ai-runtime` 的引用已移除：**FIXED**。
4. `saveSingleton` non-atomic write 已改为 sibling-temp atomic replacement：**FIXED**。
5. `observeAll` KDoc 与 cache-only behavior 的矛盾已修正：**FIXED**。

以上项目可作为 1.3.49 historical record 保留，但在 1.4.0 baseline 下不再是 current anomaly。

## 高风险上游 diff 路径

任何 upstream 更新触及以下路径，都必须阻止自动“兼容升级”：

```text
CardTransferModels.kt
CharacterCard.kt
FormatCard.kt
WorldBook.kt
PromptTemplates.kt
PromptAssembler.kt
ContextWindowManager.kt
ChatViewModel.kt
StreamingChatService.kt
WorldBookEngine.kt
ModelConfig.kt
SaveSlot.kt
SaveSlotPackageStorage.kt
domain/memory/
domain/rag/
domain/image/
domain/voice/
```

必须人工审查和运行对应 parity tests 后才能更新 baseline。

## Narrow Prompt compatibility exception — Phase 7

The validated baseline remains CCB 1.4.1 at `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`.
Project explicitly approved the official-upstream Prompt safety forward-port from
`ace632cce58a3b5a57711e31990165d6a14e1c0f` for exactly:

- `PromptTemplates.GENERAL_CONTRACT_CONFIRMATION_ASSISTANT_PROMPT`
- `MainChatPromptAuthority.CCB_CONTRACT_CONFIRMATION_ASSISTANT_PROMPT`
- `MainChatPromptAuthority.CCB_CONTEXT_APPROVAL_ASSISTANT_PROMPT`

These three literals match that upstream commit exactly, rather than formal 1.4.1.
Android main-chat facades, auxiliary envelope, logical message ordering, provider
serialization, and Entity/Package schemas remain unchanged. No identity-reminder
change from `354f151`, other 1.4.2–1.4.4 drift, or parked sync work is adopted.
This is a narrow compatibility exception, not baseline promotion.

Validation: all three source literals were compared exactly against the commit above. Existing PromptTemplates facade-parity, auxiliary envelope (AiTaskRequestsTest), main-chat order (CurrentTurnMessageOrderTest/MainChatRequestAssemblerTest), and Prompt authority tests passed; shared/Desktop/Android affected compilation and git diff --check passed. Prompt directory symbols/purposes are unchanged.
