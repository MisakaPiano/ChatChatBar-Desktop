# Upstream → Desktop Compatibility Map

本文件用于上游更新时快速判断影响范围。

> CURRENT compatibility map。Phase 1/2 已建立 `sharedCore` 与 `desktopApp`；已实现的路径按实际结构记录，尚未实现的业务域继续表示目标策略。Phase 3 的详细 extraction / transfer contract 以 `22_PHASE3_CONTRACT_AUDIT.md` 为准。

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
| `sharedCore/.../data/local/entity/WorldBook.kt` | WorldBook Entity contract | authoritative shared EXACT；runtime engine remains later scope |
| `sharedCore/.../data/repository/{Character,FormatCard,WorldBook}Repository.kt` | repositories | authoritative shared implementations over shared `JsonFileStorage` |
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
| `domain/card/FormatCardUserToolPolicy.kt` | Format Prompt runtime | Android/runtime-owned；random/append/strong suffix semantics unchanged |
| `sharedCore/.../domain/card/SillyTavernCardParser.kt` | ST Character JSON/PNG parser | authoritative pure V1/V2 + Chara tEXt authority；Android Uri/ContentResolver ingress 留在 thin adapter |
| `sharedCore/.../domain/card/SillyTavernCardMapper.kt` | ST → Character Package mapping | authoritative shared mapper；schema 5、FREEFORM、placeholder/greeting/book semantics 不变；Prompt/log 通过窄 seam 注入 |
| `sharedCore/.../domain/card/SharedImportClassifierCore.kt` | content-first classifier | authoritative candidate order 与 shared Package/ST strict decoding |
| `app/.../domain/card/SharedImportClassifier.kt` | Android classifier facade | typed `ModelTemplatePackage` decoder facade；`ModelConfig` / provider contracts 保持 Android-owned |
| `sharedCore/.../domain/card/WorldBookTransferService.kt` | WorldBook transfer / ST World Info codec | authoritative shared EXACT；World Info object form、Character Book array form 与 ST export 保持 upstream 行为；Android 与 Desktop typed transfer 共用同一实现 |
| `sharedCore/.../domain/prompt/CharacterNaiPromptDefaults.kt` | Character NAI default-negative Prompt authority | 3P authoritative shared Prompt-domain source；Android `PromptTemplates` 保留 upstream-compatible facade 并委托 shared authority |
| `sharedCore/.../domain/rag/{VectorChunk,ChunkSourceType}.kt` | serialized RAG persistence types | shared serialized contract；Android `RagRepository` 仍拥有完整 Android RAG repository/runtime，Desktop 仅实现 Character DOCUMENT cleanup |
| `desktopApp/.../DesktopTypedTransferController.kt` | typed Character/FormatCard/WorldBook transfer | shared transfer services + native `JFileChooser` + safe sibling-temp replace writer；不等同于 global SharedImport routing |
| `domain/worldbook/WorldBookEngine.kt` | WorldBook runtime | EXACT |
| `domain/prompt/PromptTemplates.kt` | Prompt text | EXACT，共享；不得分叉 |
| `domain/chat/PromptAssembler.kt` | Prompt assembly | EXACT |
| `domain/chat/ContextWindowManager.kt` | history/context | EXACT |
| `ui/chat/ChatViewModel.kt` | final orchestration | 抽 domain orchestrator，UI 各自调用 |
| `domain/chat/StreamingChatService.kt` | transport | JVM shared |
| `domain/model/*` | model resolution/discovery | JVM shared |
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
- 3D 的 shared classifier / typed management ingress 不代表 global SharedImport FIFO、ACTION_SEND/VIEW、drag/drop/Open With、ModelTemplate Desktop import 或完整 management UI 已完成；这些仍属后续范围。
- 3F Android ↔ Desktop interoperability gate：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**，test checkpoint `717ec1a473660b5d186a4241778552bc6f12a80b`；真实 API 34 与 API 36 targeted device tests 验证 Character JSON/CCB PNG、STRUCTURED/FREEFORM、多角色、图像/UTF-8 文档、embedded WorldBook/default FormatCard/ordered tools、Fish binding、standalone FormatCard/WorldBook、ST World Info、ContentResolver/FileProvider ingress 与 corrupt/invalid atomicity 的双向互操作，未发现 production interoperability defect。
- 3F 没有改变 production source 或 formal upstream baseline；compatibility claim 仍只绑定 validated upstream `1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。完整 API 36 `connectedDebugAndroidTest` 未跑到结束，已复现的非 3F instrumented debt 不得表述为全套绿色。

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
- current observed upstream：`354f15166d8bc0462cb87d62a0ba4613794560a3`（formal baseline 之后 1 commit / 12 changed files）
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
