# CCB Desktop Data Compatibility

---

# 1. 三种数据必须区分

## Transfer Package

用于导入导出：
- CharacterCardPackage
- FormatCardPackage
- WorldBookPackage
- `.cbsave`

特点：
- 跨设备/用户传输
- 资源会内联或打包
- schemaVersion 有正式兼容范围

## Entity

应用内部持久化：
- CharacterCard
- ChatSession
- ChatMessage
- WorldBook
- FormatCard
- VectorChunk
- Memory nodes
- Moments
- Voice
- ...

特点：
- 有本地 ID
- 有本地文件 path
- 有运行状态/索引状态/时间戳
- 不等于 Package

## Runtime Prompt

最终模型看到的：
- ChatApiMessage roles/content
- provider request body

它不是简单把 Entity 字段串起来。

---

# 2. CharacterCardPackage 当前基线

schema 9：

```text
CharacterCardPackage
├─ schemaVersion
├─ exportedAt
├─ card
├─ documents
├─ images
├─ worldBooks
└─ defaultFormatCard?      # v9
```

官方读取：
`3..9`

Desktop：
- 输出当前 baseline 最新 schema
- 读取官方支持的全部历史 schema
- 不因旧 Project 模板写死 8

---

# 3. Character Entity 特有内容

当前 Entity 包含但 Package 不完全一一对应：
- local id
- local avatar/background paths
- customDocuments local paths/index status
- worldBookIds
- defaultFormatCardId
- RAG progress/status
- community metadata
- momentsEnabled
- speaker rename tasks
- timestamps

导入过程必须 materialize，而非把 Package JSON 直接当 Entity JSON 存储。

---

# 4. Images

Package：
```text
resource id -> { fileName, Base64 data }
```

Entity：
```text
resource reference -> local file path
```

规则：
- Package 引用必须存在
- materialize 后引用改成本地 path
- export 再分配/复用 resource IDs

Desktop 不能把本地绝对 path 写进 CCB Package 当资源 ID。

---

# 5. Documents

Package documents：
- fileName
- fileType
- content

Entity documents：
- id
- filePath
- hash
- RAG status/count/error

导入：
Package content → Desktop-owned file → DocumentInfo → RAG pending/index。

---

# 6. WorldBook

Package WorldBook 当前直接使用 WorldBook entity shape，但导入时仍需要：
- ID 冲突处理
- reuse policy
- new IDs
- timestamps
- card links

不要因为形状相似就跳过 materialize。

---

# 7. FormatCard v9 角色默认绑定

Package v9：
```text
defaultFormatCard: FormatCardPackage?
```

Entity：
```text
defaultFormatCardId: String?
```

导入策略：
- 同名 + content + ordered tools 相同 → reuse
- 否则 import non-default copy
- Entity 保存 ID

---

# 8. CCB PNG

PNG 内含 CharacterCardPackage JSON payload。

Desktop 需要兼容：
- payload keyword
- Base64
- UTF-8
- PngTextChunks

视觉 cover renderer 是独立层。

---

# 9. ST Compatibility

官方 ST parser/mapper 是兼容路径。

当前 mapper 会建立较旧的 CCB schema（当前代码中为 5），再进入 CCB 正式兼容读取链。

因此 Desktop 不应：
- 强制把 ST 字段假装成同名 CCB 字段
- 为了“现代化”擅自重写 mapper
- 把旧编辑器的转换规则当官方规则

---

# 10. WorldBook ST Conversion

官方转换会读取多种 ST 字段和 extension，并把原 entry JSON 信息写入 CCB `extensions` 字符串。

Desktop 应保持官方 converter 行为。

---

# 11. SaveSlot

`.cbsave` v8：

```text
ZIP
├─ manifest.json
├─ messages.jsonl
├─ rag.jsonl
├─ voices.jsonl
└─ media/
   ├─ images/
   └─ audio/
```

注意：
`SaveSlot` Entity 里存在兼容旧字段和历史 schema 默认值；
真正 v8 包协议的权威是 `SaveSlotPackageStorage.SCHEMA_VERSION = 8`。

不要从 `SaveSlot` data class 某个 default 值反推当前传输格式。

---

# 12. Json unknown fields

当前官方 JsonFileStorage/transfer Json 多处使用 `ignoreUnknownKeys = true`。

Desktop parity 应跟随 upstream。

“编辑器为了无损 round-trip 保留未知字段”属于 Package Editor 的不同产品目标，不能自动强加给 runtime Entity importer。

---

# 13. Storage failure / batch semantics

- authoritative `JsonFileStorage` implementation 位于 sharedCore；Android 使用 Path root + no-op `AppDataOperationGate`，Desktop 使用同一实现并注入 `DesktopDataOperationCoordinator` gate。
- singleton `Missing` 与 `Corrupt` / `ReadError` 必须区分；后两者保留原始 bytes，不得以 default value 覆盖。
- `saveAll` 是 per-file operation，不是 whole-batch transaction；前序成功写入在后续文件失败时仍然持久化并更新 cache。
- snapshot / migration 不得假设 repository batch atomicity，仍必须在 whole-root quiescence 下运行。

---

# 14. Migration

Desktop 迁移前：
1. 记录 app data schema/state
2. snapshot
3. 执行与 upstream 等价 migration
4. 验证
5. 失败则保留 snapshot/错误

禁止：
- 清空目录作为 migration
- 自动覆盖无法识别数据
- 悄悄降级语义

---

# 15. Cross-platform compatibility

必须证明：

### Package
Android ⇄ Desktop

### SaveSlot
Android ⇄ Desktop

### Prompt
相同业务状态 → 等价 final logical message/request

### WorldBook
相同 scan snapshot → 相同选择结果

文件路径、UI 状态和 OS-specific cache 不要求跨平台逐字一致。
