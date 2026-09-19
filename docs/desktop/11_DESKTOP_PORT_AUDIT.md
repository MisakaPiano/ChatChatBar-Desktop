# CCB Desktop Phase 0 源码移植审计

审计日期：2026-09-19  
上游：`SaltyFishOTL/ChatChatBar`  
基线：`master @ 4c8c1eac51dc632bf9042468819cb86091b7660c`  
官方版本：`1.3.48`

> 本文是第一次架构审计，不等于已经通过 Desktop 编译验证。  
> “可复用”表示源码结构上具备 JVM/共享候选条件；真正提升为 `EXACT` 必须经过抽离、编译和 parity 测试。

---

## 1. 当前上游事实

官方当前是单模块 Android 工程：

```text
repo/
├─ app/                         # Gradle root
│  ├─ settings.gradle.kts       # include(":app")
│  └─ app/
│     └─ src/main/java/com/example/chatbar/
│        ├─ data/
│        ├─ domain/
│        ├─ ui/
│        └─ utils/
├─ device-entities/
├─ supabase/
├─ .agents/skills/
└─ AGENTS.md
```

官方 `AGENTS.md` 明确：
- 当前持久化是真正的 `JsonFileStorage`
- 没有 active SQL database
- prompt/card/RAG/model/image 等领域逻辑应在 domain，而非 Composable
- JDK 17

构建基线：
- Kotlin 2.3.20
- AGP 9.0.1
- compileSdk/targetSdk 36
- minSdk 26

依赖中同时存在大量 JVM 可用库和 Android 专属库。

JVM 友好：
- kotlinx.serialization
- kotlinx.coroutines core-compatible logic
- OkHttp / SSE
- Retrofit
- JSoup
- kotlinx.datetime
- msgpack

Android 专属或需替换：
- Android Context/Application/Activity
- AndroidX Activity/Lifecycle/Navigation3
- AndroidX DataStore（若实际路径使用）
- Media3
- Android Bitmap/Canvas/StaticLayout
- Android Keystore/SharedPreferences
- ContentResolver/Uri/Intent/SAF
- FileProvider
- ForegroundService / Notification
- AccessibilityService
- APK PackageManager/Installer

构建文件仍声明 ObjectBox，但官方仓库指南明确当前无 active SQL database；Desktop 不应据依赖声明假设 ObjectBox 是当前数据真源。

---

## 2. 当前 Package 与传输格式

### CharacterCardPackage

官方 `CardTransferModels.kt`：

- 当前 schemaVersion：9
- 接受：3..9
- v9 新增：
  - `defaultFormatCard: FormatCardPackage?`

核心顶层：
- `schemaVersion`
- `exportedAt`
- `card`
- `documents`
- `images`
- `worldBooks`
- `defaultFormatCard`

因此旧 CCB Project 的 schema 8 指南已经是历史参考，不能再作为 Desktop 真源。

### FormatCardPackage

- 当前：2
- 接受：1..2

包含：
- name
- content
- ordered `userTools`
- preset source metadata

### WorldBookPackage

- 当前：1
- 只接受 1

### CCB PNG

`CharacterCardTransferService.exportPng()`：
- 先生成 CCB 视觉封面 PNG
- JSON 编码为 UTF-8
- Base64
- 写入 PNG text chunk：ChatBar character keyword

`CharacterCardPngRenderer` 当前高度依赖 Android：
- Bitmap / Canvas / Paint
- Android font/resource
- mipmap logo

因此：
- **PNG payload codec 应共享/精确兼容**
- **视觉封面 renderer 在 Desktop 需要等位实现**
- 封面像素无需强制完全一致，但 Package payload 和用户可见关键布局应兼容

### SillyTavern 兼容

官方仍存在：
- `SillyTavernCardParser`
- `SillyTavernCardMapper`
- WorldBook ST 转换

注意：ST mapper 当前会构造 schemaVersion 5 的 CCB Package，再走官方可读兼容链。

Desktop 不应重新发明 Tavern/ST 映射；应复用或移植官方映射行为。

---

## 3. Entity 层与 Package 层并不相同

例如 `CharacterCard` Entity 包含：

- `id`
- 本地文件路径 `avatar` / `chatBackground`
- `customDocuments`
- `worldBookIds`
- `defaultFormatCardId`
- `ragIndexStatus`
- community source metadata
- moments enable
- createdAt / updatedAt
- speaker rename tasks

而 Package 使用资源 ID + 内联资源：

- avatarResourceId
- appearanceImageResourceId
- chatBackgroundResourceId
- documents content
- images Base64
- embedded worldBooks
- optional defaultFormatCard

Desktop 必须继续维护：
`Package ⇄ materialize Entity ⇄ Prompt runtime`
三个边界。

---

## 4. 持久化审计

`JsonFileStorage`：

Android 当前 root：
```text
filesDir/entities/
```

实体：
```text
entities/<entityType>/<id>.json
```

单例：
```text
entities/<entityType>.json
```

关键行为：
- `ignoreUnknownKeys = true`
- pretty print
- encode defaults
- per-entity mutex
- cache Flow
- IO dispatcher
- temp file
- `ATOMIC_MOVE + REPLACE_EXISTING`
- 不支持 atomic move 时 fallback replace
- 大实体有 uncached/streaming API
- 支持 prefix transactional replacement

结论：

**JsonFileStorage 的核心实现非常适合抽离为路径驱动的 JVM storage。**

真正 Android 依赖主要集中在：
```kotlin
Context.filesDir
```

推荐：
```text
CoreJsonFileStorage(root: Path)
AndroidJsonFileStorage(filesDir.toPath())
DesktopJsonFileStorage(dataRoot)
```

不要为 Desktop 第一版改成 SQLite。

---

## 5. Prompt Pipeline 审计

官方 Skill 明确：**最终序列化 API message 才是真值。**

关键路径：
- `PromptTemplates.kt`
- `PromptAssembler.kt`
- `ContextWindowManager.kt`
- `ChatViewModel.kt`
- `StreamingChatService.kt`
- `ModelConfig.kt`
- `CleartextHttpChatTemplatePolicy.kt`
- Debug request logs

当前逻辑包括：
- core + creator identity
- CCB ack/contract/confirmation
- format requirements START/END/BOTH
- character
- WorldBook + setting RAG
- supplementary
- player
- CCB context approval
- Archive
- history
- memory RAG
- HEAD/timeline
- previous turn hot zone
- continuation
- current user
- post-history
- strong prompt suffix
- final CCB tail

结论：

**Prompt pipeline 必须优先抽成共享核心。Desktop 禁止自己写第二份 Prompt assembler。**

Desktop 可增加 Prompt Inspector，但只能观察，不修改默认运行语义。

---

## 6. WorldBook 审计

Entity 支持：
- scanDepth
- tokenBudget
- recursiveScanning
- caseSensitive
- matchWholeWords

Entry 支持：
- keys / secondaryKeys
- selective + 4 selectiveLogic
- BEFORE_CHAR / AFTER_CHAR / OUTLET
- priority/order
- probability
- group/groupWeight
- sticky/cooldown/delay
- regex
- character filter
- recursion controls
- per-entry scan depth
- role
- budget controls

关键：
- `WorldBookEngine.kt`
- `WorldBookScanContext.kt`
- ChatViewModel request scan
- timed effect persistence

结论：
WorldBook runtime 属于 `EXACT` 目标，不做 Desktop 简化版。

---

## 7. Model / Provider 审计

核心：
- `EffectiveModelResolver`
- `ProxyAwareClient`
- `StreamingChatService`
- `ThinkingRequestPolicy`
- `ModelDiscoveryService`
- `EmbeddingService`

行为包括：
- OpenAI-compatible requests
- SSE
- HTTP local model
- per-model/global authentication
- model fallback
- reasoning/thinking
- output token parameter selection
- JSON mode capability
- cleartext role adaptation
- retries/errors
- request logs
- model list discovery

大部分网络协议逻辑是 JVM 可复用候选。

需要平台替换：
- Android foreground/background work protection
- Android network state integration
- Android lifecycle cancellation UI

---

## 8. RAG / Long-Term Memory

### RAG

关键：
- ChunkingEngine
- EmbeddingService
- VectorSearchEngine
- RagRepository
- RagManager
- RetrievalPlanner
- ChatMemoryIndexPolicy

### Long-term Memory

当前不是简单一个摘要字段，包含：
- Episode
- Arc
- Era
- Archive
- HEAD
- Gap/backfill
- source-turn identity
- source repair
- compression
- regeneration
- application-scope auto maintenance

结论：

RAG + Memory 必须在后续阶段完整移植。不能用“桌面版先写一个简单摘要”冒充等价。

---

## 9. SaveSlot 审计

官方当前 `.cbsave` v8 是流式 ZIP：

```text
manifest.json
messages.jsonl
rag.jsonl
voices.jsonl      # optional
media/images/
media/audio/
```

特点：
- bounded memory
- 流式消息/RAG
- image NONE/COMPRESSED/ORIGINAL
- audio 独立开关
- 先 stage/validate/materialize
- transaction-like restore
- 旧 schema 1–7 仍兼容

Android 依赖：
- Context/filesDir
- BitmapFactory / Bitmap 压缩

结论：
- ZIP/package protocol：`EXACT`
- 图片压缩 backend：Desktop adapter
- Android 与 Desktop 必须双向 `.cbsave` 恢复

---

## 10. NovelAI / 图像系统

当前图像域规模很大，包括：
- NovelAI image generation
- model resolution
- Prompt Designer
- Danbooru catalog
- tag research/suggestion
- V5 natural language
- vibe encoding
- inpaint/focused inpaint
- image guidance
- history
- canvas/history
- metadata
- regeneration
- seed/dimensions
- 429 retry
- batch
- Studio
- APNG disguise/restore
- mosaic/image processing
- automatic chat images
- card cover/avatar generation

可复用候选：
- HTTP/API policy
- Prompt logic
- metadata models
- size/model resolution
- tag policy
- retries

需 Desktop 图像 backend：
- Bitmap decode/encode
- Canvas/image processing
- Android resource/font
- Android sharing/saving

推荐使用 JVM/Skia/Compose Desktop 图像能力统一承担这些平台操作，但具体 API 在实现阶段验证。

---

## 11. Fish Audio / Voice

当前包括：
- Fish credentials
- voice bindings
- tag AI
- generation
- batch generation
- voice anchors
- storage
- playback
- SaveSlot transfer

Android 专属：
- Android Keystore credential store
- Media playback backend

Desktop：
- 共享 Fish API/domain
- Windows SecretStore
- Desktop audio backend

---

## 12. QQ Voice：明确的 Android-only 功能域

官方 Manifest 包含：
- QQ package query
- media foreground service
- AccessibilityService

`QqVoiceAccessibilityService` 会：
- 判断 QQ 是否前台
- 查找 QQ 按住说话 View ID
- 通过 Android Accessibility gesture 长按/释放

这无法在 Desktop 直接复用。

Parity 目标：

- 如果 Desktop QQ 客户端存在可稳定、安全调用的公开/系统接口，则实现等位“把 CCB 生成语音发送到 QQ”。
- 如果没有可靠公开路径，标记 `BLOCKED(platform/external)`，保留生成语音文件、复制/拖放/打开文件所在位置等桌面等位工作流。
- 不允许用脆弱的硬编码鼠标自动化冒充 `EXACT`。

这是 1.0 前必须明确解决或正式记录的唯一特殊平台门槛之一。

---

## 13. Moments

当前包括：
- Moments entity/repository
- scheduler
- generation
- NovelAI images
- likes/private
- retry/edit/delete
- unread
- text-only fallback

官方 Skill 明确：
Moments 不会在 App 未运行时自动生成。

因此 Desktop 不需要 Windows Scheduled Task；运行时 scheduler 可等价实现。

Android AlarmReceiver/AlarmScheduler 属于平台替换，不是功能删除。

---

## 14. Community

当前：
- Supabase
- runtime enable switch
- anonymous browse/search/download
- Discord OAuth upload
- owner overwrite/delete
- package validation
- preview cache
- community-downloaded cards read-only/update

Desktop：
- 后端协议尽量复用
- OAuth callback 改为 Desktop URI scheme 或 localhost callback
- 浏览/下载/上传语义保持一致

---

## 15. Shared Import

Android 当前：
- ACTION_SEND
- ACTION_VIEW
- URI priority
- staging
- content-first classifier
- FIFO
- automatic import
- image handoff

Desktop 等位：
- file association / Open With
- drag & drop
- clipboard/path paste（如需要）
- command-line open argument
- staging + classifier + FIFO 逻辑继续共享

分类必须基于内容，不依赖扩展名/MIME。

---

## 16. App Update

Android 当前：
- GitHub Release update
- APK download
- APK package/version validation
- FileProvider
- unknown source permission
- system installer

Desktop 等位：
- 仍可使用 GitHub Release / manifest
- 下载 MSI/EXE
- hash/signature/version validation
- 用户确认安装
- updater/restart

不要把 Android APK manager 直接抽进 shared。

---

## 17. UI / Navigation

Android：
- portrait-only MainActivity
- Navigation3
- phone-first screens

Desktop：
- 功能等价，不做像素复制
- 宽屏三栏优先：
  - 左：会话/卡
  - 中：聊天
  - 右：Context/Tools/Inspector
- 窄窗口退化为单栏/双栏
- 保留官方 UI kit 的视觉理念，可复用 Compose 组件时复用
- Desktop navigation shell 单独实现

---

## 18. Secret 与设置

NovelAI/Fish 当前使用：
- SharedPreferences
- Android Keystore
- AES/GCM

Desktop 必须建立：
```text
SecretStore
```

Windows backend 使用 OS 保护机制。

Portable Mode 默认**不**把可解密 secrets 随目录搬走；Portable 数据包可以迁移业务数据，但 secrets 需要在新机器重新配置。

ModelConfig 当前 `apiKey` 仍是 Entity 字段，这一事实必须按上游保持；长期是否抽离所有模型 key 属于单独安全设计，不在移植时擅自改变数据语义。

---

## 19. 许可风险

GitHub repository metadata 当前：
```text
license: null
```

根目录审计未见 LICENSE。

结论：
- 可以继续做技术审计和私人开发/Fork 工作流。
- 在公开发布派生源码或二进制之前，必须确认上游作者许可。
- 不在本文做法律结论。

---

## 20. 移植分类

### A — 优先共享 / EXACT 候选

- serializable entities
- CardTransferModels
- WorldBook entity + WorldBookEngine
- PromptTemplates / PromptAssembler
- ContextWindowManager
- Model request policies
- EffectiveModelResolver
- many chat policies
- RAG pure algorithms
- Memory policies/state machines
- SaveSlot package models/protocol
- NovelAI request/prompt policies
- Fish API/domain models
- Moments policy
- Community package models

### B — 小型抽象后共享

- JsonFileStorage（Context → root Path）
- repositories
- CharacterCardTransferService（Context/resource storage）
- SaveSlotPackageStorage（filesystem/image codec）
- NovelAI storage/image codecs
- FishAudioStorage
- shared import staging
- crash/report file IO

### C — 平台 Adapter

- data root
- file picker
- file association
- drag/drop
- clipboard
- OS share/reveal
- SecretStore
- audio playback
- image codec/canvas
- background task protection
- notification/tray
- OAuth callback
- updater/install
- crash platform info

### D — Desktop 重做

- MainActivity
- portrait navigation shell
- Android Service
- Android notification channels
- AccessibilityService QQ automation
- APK PackageManager flow
- Android permission UI

---

## 21. 主要风险

1. **过早全项目 KMP 化**  
   会制造巨大上游 merge 冲突。第一阶段使用 JVM shared 模块更稳。

2. **复制 domain 代码而非抽共享代码**  
   会导致 Android/Desktop 两套语义漂移。

3. **UI 先行**  
   很容易做出“像 CCB”的 Desktop，但 Prompt/WorldBook/Package 已经不同。

4. **资源路径与 ID 混淆**  
   Package resource ID 与 Entity 本地 path 必须分离。

5. **Prompt parity 缺少最终 request 对比**  
   UI Preview 不能作为验收。

6. **Memory / SaveSlot 被低估**  
   都有复杂持久化/状态机，不适合临时简化。

7. **上游持续快速变化**  
   必须从第一天维护 baseline + sync report。

8. **无明确 License**  
   公开 release 前单独处理。

---

## 22. Phase 1 建议结论

第一笔代码不应移业务功能。

先做：

1. Fork `MisakaPiano/ChatChatBar-Desktop` 与 `desktop` 分支已经建立，并已完成 baseline 复核。
2. 在 Gradle root `app/` 新增 `:desktopApp`。
3. 只建立 Compose Desktop 空窗口。
4. 不抽 shared，不改 Android domain。
5. Android 原 `:app` 仍可编译。
6. Desktop `run` 成功。
7. 建立 `DesktopEnvironment` / data-root 最小接口骨架。
8. 记录 Desktop 构建命令。

通过后，Phase 2 再抽 `JsonFileStorage(root)`，随后才进入 Package/Entity。
