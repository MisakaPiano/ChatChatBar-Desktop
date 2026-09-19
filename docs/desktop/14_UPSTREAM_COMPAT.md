# Upstream → Desktop Compatibility Map

本文件用于上游更新时快速判断影响范围。

> Phase 0 映射；真正建立 `sharedCore/desktopApp` 后，应把 Desktop 实际路径补上。

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
| `data/local/JsonFileStorage.kt` | JSON persistence | 抽 root Path 后共享 |
| `data/local/entity/*` | Entities | 优先 shared EXACT |
| `data/repository/*` | repositories | 依赖 storage 后 shared |
| `data/security/*CredentialStore.kt` | Android Keystore | Desktop SecretStore |
| `domain/card/CardTransferModels.kt` | Package schema | EXACT shared |
| `domain/card/CharacterCardTransferService.kt` | package↔entity | 共享核心 + resource adapter |
| `domain/card/CharacterCardPngRenderer.kt` | CCB PNG cover | Desktop renderer 等位 |
| `domain/card/PngTextChunks*` | PNG metadata | EXACT shared |
| `domain/card/SillyTavern*` | ST compatibility | EXACT，Uri parser部分适配 |
| `domain/card/WorldBookTransferService.kt` | WorldBook transfer | EXACT |
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

## 官方 Skill Inventory（baseline 1.3.49）

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

### Validated 1.3.49 sync record

- declared validated baseline：`1.3.49 @ 6b1817cd2dc65e6509e6ae350bef1a8e1a1250de`
- current observed upstream：`1.3.49 @ 6b1817cd2dc65e6509e6ae350bef1a8e1a1250de`
- upstream drift：none
- inventory drift：none；Skill 数量仍为 20
- compatibility status：validated
- source merge：`cd49ec93e044d0278d66cb2b78991d6e62c61f8c`
- source integrity：PASS
- Android compile：PASS
- JVM unit tests：PASS（1141 tests，0 failures）

1.3.49 的主要 parity 影响：

- Prompt `systemPrompt` ownership / runtime semantics 改为 fixed prefix + replaceable middle + fixed suffix；`{{original}}` 只展开 default middle。
- Moments 使用 session chat/image model precedence，并保持 NovelAI rendering model 的独立三级解析。
- Character editor 增加 resource picker、live resource state 与 stale binding 显式移除。
- FREEFORM `CharacterInfo` 支持 Fish voice binding。

此次发生内容变化的 4 个 Skill 已与源码核对一致：`chatbar-prompt-pipeline`、`chatbar-moments`、`chatbar-fish-audio-voice`、`chatbar-shadcn-compose`。

### Skill drift 规则

- baseline 未变化时，Skill inventory 应与该 commit 保持一致。
- upstream commit 变化时，先重新枚举 `.agents/skills/*/SKILL.md`，再比较新增、删除、重命名与内容变化。
- 新增或变化的 Skill 必须映射到 `FEATURE_PARITY.md` / 本文件对应域；不能只记录目录名。
- Skill 变化本身不自动意味着 Desktop 已兼容；仍需按受影响功能运行 parity review/test。

## Known upstream documentation anomalies（baseline 1.3.49 / 6b1817cd）

1. root `AGENTS.md` 写有 “there is no active SQL database”，但源码存在 `DanbooruTagCatalog`、`NovelAiBundledDictionary`、`RankedTagIndex` / `RankedTagIndexStore` 辅助 SQLite。Desktop 将其解释为“无 active SQL business Entity DB”，不能解释为整个 App 无 SQLite。
2. `.agents/skills/chatbar-model-request-runtime/SKILL.md` 的 START/END placement 描述与当前实际 Prompt assembly 不一致。Prompt ownership 与顺序以 baseline 当前源码、`chatbar-prompt-pipeline` 和最终 serialized request 为准。
3. `.agents/skills/chatbar-image-generation-runtime/SKILL.md` 引用了当前 20-Skill inventory 中不存在的 `chatbar-web-ai-runtime`。不得据此凭空增加 Desktop WebView/browser runtime 功能。

这些异常在 1.3.49 中仍存在。它们属于 upstream baseline 自身的文档问题；不得在 downstream `master` 中修改 upstream 文件，只能在 Desktop 文档与后续 sync audit 中记录和处理。

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
