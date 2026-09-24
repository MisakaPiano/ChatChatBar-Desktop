# Phase 3 Contract Audit — Entities / Package / Import-Export

> 状态：PROJECT AUDIT COMPLETE / P3-D1..D3 RESOLVED（implementation 尚未开始）  
> Desktop 基线：`76de285c3acbf8f6e9c7fa0c925c7dad4d462f5c`  
> Formal upstream baseline：ChatChatBar `1.4.1` @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`  
> 审计日期：2026-09-24  
> observed `upstream/master`：`5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`  
> drift：0 commit / 0 file；urgency：NONE

---

## 1. 审计结论

Phase 3 可以开始实现，但必须继续严格区分三层：

1. **Transfer Package**：跨设备/用户传输格式；schemaVersion、内联资源、兼容范围属于这里。
2. **Persisted Entity**：应用内部持久化对象；本地 ID、本地资源引用、RAG/runtime 状态、时间戳等属于这里。
3. **Runtime Prompt / API message**：最终模型可见内容；不能由 Package/Entity 字段名直接推断。

当前 upstream 的 Package / Entity / transfer 逻辑中，绝大部分可以共享到 JVM `sharedCore`；真正的平台边界集中在：

- Android `Context` / `Uri` / `ContentResolver` / `assets`
- Android `Bitmap/Canvas` cover renderer
- Android `Base64`
- 本地资源文件 materialization / resolution
- SAF / ACTION_SEND / ACTION_VIEW ingress
- 少量 Prompt ownership 依赖
- `FishAudioVoiceBinding` 与 `NovelAiImageModel` 当前文件归属过重，需要只抽 value type，不拖入完整 runtime

Phase 3 的第一个 production slice 可以安全地只做 **Entity / Package contract core extraction**；资源 materialization、Prompt 依赖桥和 Desktop ingress 应后置。

---

## 2. 权威来源与当前控制点

### 2.1 当前真值

- upstream：`SaltyFishOTL/ChatChatBar`
- formal baseline：`1.4.1`
- upstream SHA：`5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`
- Desktop：`MisakaPiano/ChatChatBar-Desktop`
- `desktop` SHA：`76de285c3acbf8f6e9c7fa0c925c7dad4d462f5c`
- `master` = upstream baseline
- upstream observed master = formal baseline
- D-022：无 drift，不需要 sync

### 2.2 已阅读的上游 Skill

- `.agents/skills/chatbar-feature-map/SKILL.md`
- `.agents/skills/chatbar-shared-import/SKILL.md`
- `.agents/skills/chatbar-prompt-pipeline/SKILL.md`
- `.agents/skills/chatbar-fish-audio-voice/SKILL.md`
- `.agents/skills/chatbar-image-generation-runtime/SKILL.md`

本审计不改变任何 Prompt 文本，不改变 schema，不开始 Phase 4 runtime。

---

# 3. Package / Entity / Prompt 三层边界

## 3.1 Transfer Package

当前 Phase 3 直接涉及：

- `CharacterCardPackage`
- `FormatCardPackage`
- `WorldBookPackage`

Package 的职责：

- 跨设备传输
- 跨 Android/Desktop 互操作
- 内联或逻辑引用资源
- schema compatibility
- 不包含 Desktop/Android 本地 runtime 状态

## 3.2 Persisted Entity

当前 Phase 3 直接涉及：

- `CharacterCard`
- `CharacterInfo`
- `DocumentInfo`
- `FormatCard`
- `WorldBook`
- `WorldBookEntry`

Entity 含：

- local ID
- local resource reference
- local timestamps
- RAG/index 状态
- source preset / community / moments / pending task 等本地状态

Package JSON 不能直接当 Entity JSON 落盘。

## 3.3 Runtime Prompt

Phase 3 不拥有 Prompt assembly。

但是 Phase 3 upstream transfer code存在三个 Prompt coupling：

1. `CharacterCardTransferService`
   - `PromptTemplates.effectiveCharacterNaiNegativePrompt(...)`
2. `SillyTavernCardMapper`
   - `PromptTemplates.defaultCharacterNaiNegativePrompt()`
3. `FormatCardUserToolPolicy`
   - validation 与 Prompt/runtime append logic 当前位于同一 object

这些依赖必须保留 upstream Prompt ownership，禁止为 Desktop 复制/改写 Prompt 文本。

---

# 4. JSON serialization contract

## 4.1 Entity persistence Json

Desktop 当前共享 `JsonFileStorage`：

```kotlin
Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}
```

当前没有显式：

- `isLenient = true`
- `coerceInputValues = true`
- custom serializers module

所以默认 `isLenient = false`。

目录：

```text
<appDataRoot>/entities/<entityType>/<id>.json
```

## 4.2 Android transfer Json

`ChatBarApp.kt` baseline：

```kotlin
val transferJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}
```

Character / Format / WorldBook transfer service 都注入这个实例。

因此正常 typed import/export 的 transfer Json 与 Entity persistence Json 的关键配置一致，但它们仍是不同 ownership。

## 4.3 SharedImportClassifier Json

全局 shared-import classifier 自己使用：

```kotlin
Json {
    ignoreUnknownKeys = true
    isLenient = true
}
```

它是 **content classifier / strict target decoder pipeline 的私有解析实例**，不能把这个 `isLenient=true` 错当成普通 typed transfer Json contract。

`SillyTavernCardParser` 的 JSON parser 同样是 `ignoreUnknownKeys=true; isLenient=true`。

---

# 5. Package schema matrix

| Package | 当前写版本 | 接受读版本 | 证据 |
|---|---:|---:|---|
| CharacterCardPackage | **9** | **3..9** | `CardTransferModels.kt` |
| FormatCardPackage | **2** | **1..2** | `FORMAT_CARD_PACKAGE_SCHEMA_VERSION = 2` |
| WorldBookPackage | **1** | **1** | `validateForImport()` |

不得从旧 Project 模板永久硬编码历史值。

---

# 6. CharacterCardPackage contract

## 6.1 Package shape

schema 9：

```text
CharacterCardPackage
├─ schemaVersion
├─ exportedAt
├─ card
├─ documents
├─ images
├─ worldBooks
└─ defaultFormatCard?
```

`defaultFormatCard` 使用：

```kotlin
@EncodeDefault(EncodeDefault.Mode.NEVER)
```

null 时明确省略。

## 6.2 legacy compatibility

`validateForImport()` 接受 3..9。

当前 serializer 通过字段默认值兼容 legacy；没有按每个 schema 分别维护第二套 DTO。

已验证的重要 legacy default：

- `botName = ""`
- `defaultNovelAiImageModel = null`
- `defaultFormatCard = null`
- `PackagedCharacter.fishAudioVoice = null`
- 其他具有 Kotlin default 的字段按当前 default 解码

空 editor placeholder 在 validate 前由：

```text
withoutEmptyCharacterPlaceholders()
```

过滤。

规则：

- 全空 unnamed character → 删除
- unnamed 但带 profile/image/voice 等内容 → 保留，然后 validation 因 name blank 而拒绝
- 不允许静默丢掉有内容的 unnamed character

## 6.3 validation

必须满足：

- schema 3..9
- card.name nonblank
- 所有有效 characters name nonblank
- documents fileName/fileType nonblank
- images key / fileName / data nonblank
- avatar/background/character appearance image 的 resource ID 必须存在于 images map
- embedded defaultFormatCard 必须通过 Format validation

---

# 7. Character Package → Entity mapping

## 7.1 CharacterCard

| Package / source | Entity field | 分类 |
|---|---|---|
| import-generated | `id` | GENERATED_LOCAL_ID |
| `card.name` / conflict policy | `name` | DIRECT_VALUE + LOCAL_NAME_RESOLUTION |
| `card.botName` | `botName` | DIRECT_VALUE |
| `avatarResourceId` | `avatar` | LOCAL_PATH_MATERIALIZED |
| `card.characters` | `characters` | MATERIALIZED |
| `documents` | `customDocuments` | MATERIALIZED |
| `greeting` | `greeting` | DIRECT_VALUE |
| `alternateGreetings` | `alternateGreetings` | DIRECT_VALUE |
| `chatBackgroundResourceId` | `chatBackground` | LOCAL_PATH_MATERIALIZED |
| `editMode` | `editMode` | DIRECT_VALUE |
| `basicSetting` | `basicSetting` | DIRECT_VALUE |
| `freeformCharacterText` | `freeformCharacterText` | DIRECT_VALUE |
| `defaultImagePrompt` | `defaultImagePrompt` | DIRECT_VALUE |
| `defaultImageNegativePrompt` | same | DIRECT_VALUE + Prompt-owned normalization |
| `defaultNovelAiImageModel` | same | DIRECT_VALUE |
| `systemPrompt` | same | DIRECT_VALUE |
| `postHistoryInstructions` | same | DIRECT_VALUE |
| `mesExample` | same | DIRECT_VALUE |
| creator metadata | same | DIRECT_VALUE |
| imported/reused books | `worldBookIds` | LOCAL_ID_REFERENCE |
| embedded Format | `defaultFormatCardId` | LOCAL_ID_REFERENCE |
| package `characterBook` | no persisted embedded copy | converted/imported into local WorldBook |
| import args | `sourcePresetKey/Version` | LOCAL_IMPORT_METADATA |
| — | `boundWorldBookId` | RESET_ON_IMPORT → null |
| — | RAG fields | RESET_ON_IMPORT |
| — | community metadata | ENTITY_ONLY / defaults |
| — | `momentsEnabled` | ENTITY_ONLY / default true |
| — | pending speaker rename tasks | ENTITY_ONLY / default empty |
| import operation | `createdAt` | NEW now / overwrite preserves old |
| import operation | `updatedAt` | now |

## 7.2 CharacterInfo

每个 Package character：

- 新建 local CharacterInfo ID
- name trim / duplicate resolution 由 `CharacterSpeakerNamePolicy.normalizeUnique`
- profile / appearance / clothing / abilities / habits / background / relationships / speakingStyle / imagePrompt 直接映射
- appearance image resource → local resource reference
- Fish voice binding 直接保留

## 7.3 DocumentInfo

Package document：

```text
fileName
fileType
content
```

导入后：

- 生成 local document ID
- 创建 owned file
- `filePath` 指向 local resource reference
- `addedAt = now`
- contentHash/indexedHash 初始为空
- `ragStatus = PENDING`
- chunk/index timestamp/error 使用 Entity defaults

---

# 8. Character export contract

`CharacterCardTransferService.packageCard()` 当前行为：

1. 过滤空 Character placeholder。
2. 有内容但 name blank 的 character 仍会因为 export 前 require 而失败。
3. 读取 custom document 本地文件文本，写为 `PackagedDocument(content)`.
4. 图片资源打包：
   - avatar → preferred resource ID `avatar`
   - chat background → `chat-background`
   - character appearance → `character-<index>-appearance`
5. 相同本地 path 会复用第一次分配的 resource ID，不重复写 image payload。
6. `PackagedImage.fileName` 使用源文件名；空时 fallback `<resourceId>.jpg`。
7. 普通 image export data 为 Base64。
8. `defaultImageNegativePrompt` export 前再次经过：
   - `PromptTemplates.effectiveCharacterNaiNegativePrompt`
9. `defaultFormatCardId`：
   - local referenced Format 不存在 → export 明确失败
   - 存在 → embed 当前 FormatCardPackage
10. WorldBooks：
   - `worldBookIds` 可解析的 books
   - 加 `characterBook`
   - 按 WorldBook ID distinct
11. Package 构造使用 schema 9 默认值。
12. `exportedAt` 为 Package 构造时 currentTimeMillis。

Package 不携带：

- Character local id
- CharacterInfo local id
- local path
- RAG runtime state
- community metadata
- moments flag
- pending speaker rename tasks
- Entity timestamps

---

# 9. Character import / materialization contract

## 9.1 importNew

顺序：

```text
normalize placeholder
→ validate package
→ resolve unique card name
→ materialize resources / related entities
→ repository.save(CharacterCard)
```

name conflict：

- case-insensitive + trim
- 已存在 → `NamePolicy.nextCopyName`
- 否则 normalize requested name

## 9.2 overwrite

保留：

- existing card ID
- existing card name
- existing createdAt

其他 CharacterCard 大部分字段由新 Package materialize 重建。

这意味着 overwrite 会重置或替换一批本地 runtime state，包括：

- RAG status/progress
- boundWorldBookId
- community metadata（constructor default）
- momentsEnabled（constructor default true）
- pending speaker rename state（constructor default）
- old worldBook/defaultFormat bindings

保存 replacement 成功后，再 cleanup old owned resources。

## 9.3 images

upstream Android 当前：

```text
filesDir/images/card_<now>_<uuid>.<extension>
```

extension：

- 取 package fileName extension
- 只允许 `[a-z0-9]{1,10}`
- 无效时 fallback `jpg`

普通 data：

```text
Base64.decode(..., DEFAULT)
```

特殊 data：

```text
asset:<asset path>
```

→ `app.assets.open(...)`

审计当前 official bundled character preset：

- `MujicaMyGO.json`：未发现 `asset:` resource
- `Rupa.json`：未发现 `asset:` resource

本 baseline 中只确认到 consumer，没有找到 current producer；因此它应视为 **现存兼容路径 / legacy-or-internal resource resolver path**，不能假装是当前 package exporter 输出。

Desktop 不应把它解释成普通 Windows absolute path。

## 9.4 documents

upstream Android：

```text
filesDir/documents/card_<now>_<docId>_<safeFileName>
```

unsafe filename chars：

```text
\ / : * ? " < > |
```

替换为 `_`。

## 9.5 WorldBooks

Character import：

```text
package.worldBooks + card.characterBook
→ distinctBy(source.id)
→ reuse or create local copy
```

reuse：

1. sourcePresetKey nonblank 时优先匹配 same sourcePresetKey
2. 否则/未命中，按 normalized case-insensitive name reuse
3. 否则新建

新建时：

- new WorldBook ID
- new entry IDs
- conflict name → copy suffix
- createdAt/updatedAt = import now
- source 自带 presetKey 时优先保留
- 否则可继承 character import presetKey/version

## 9.6 default FormatCard

embedded FormatCard：

- same normalized name
- same content
- same **ordered** userTools

三项同时相同才 reuse。

冲突但不完全相同：

- import non-default copy
- local existing default 不被覆盖

Character Entity 最终只保存 local `defaultFormatCardId`。

## 9.7 RAG reset

import materialization：

```text
ragIndexStatus = NOT_INDEXED
ragIndexDone = 0
ragIndexTotal = documents.size
ragIndexMessage = 无参考文档 / 参考文档待建立索引
ragIndexedAt = null
```

---

# 10. Character duplicate / delete

## 10.1 duplicate

Character duplicate 不是 raw Entity copy。

它执行：

```text
packageCard(source)
→ materialize(package)
→ saveNew
```

因此 duplicate 本质是官方 Package round-trip semantics：

- new card ID
- new CharacterInfo IDs
- copy name suffix
- reset runtime/RAG/community style state
- local WorldBook / Format 经过 reuse/import policy

## 10.2 delete

当前 upstream：

```text
previous = repository.getById
→ deleteOwnedResources(previous)
→ repository.delete(id)
```

`deleteOwnedResources` 先删：

- avatar
- background
- appearance image
- documents

然后删 RAG chunks。

详见本审计“疑似 upstream bug”。

---

# 11. FormatCard contract

## 11.1 Entity

Entity 特有：

- id
- isDefault
- createdAt

Package 不携带这些本地 ownership 字段。

## 11.2 Package

v2：

```text
schemaVersion
exportedAt
name
content
userTools[]
sourcePresetKey?
sourcePresetVersion?
```

v1：

- userTools default empty

v2：

- `RANDOM_NUMBER`
- `STRONG_PROMPT_SUFFIX`

ordered tools 顺序是 contract 的一部分。

## 11.3 transfer

export：

- 当前 schema 2
- 不导出 id/isDefault/createdAt

importNew：

- new ID
- conflict name → copy suffix
- `isDefault=false`
- preset metadata 使用 explicit import args 优先，否则 package

duplicate：

- new ID/name
- `isDefault=false`
- sourcePresetKey/version 清空

overwrite：

- 保留 existing id/name/createdAt/isDefault
- 替换 content/tools/source preset metadata

character default reuse：

```text
same name + same content + same ordered userTools
```

才 reuse。

## 11.4 Prompt coupling

`FormatCardUserToolPolicy` 同时拥有两类职责：

A. Phase 3 需要的 Package validation：
- integer parse
- max >= min
- strong suffix text nonblank

B. Phase 4 Prompt runtime：
- random suffix generation
- append user suffix block
- strong system suffix

Phase 3 implementation 不应为了 Package validation 把 Prompt runtime 一起拖入 sharedCore。

推荐：拆出单一 authoritative validation primitive；原 `FormatCardUserToolPolicy` runtime 继续复用该 validator。

---

# 12. WorldBook contract

## 12.1 Entity / Package

`WorldBookPackage v1`：

```text
schemaVersion
exportedAt
book: WorldBook
```

Package 直接复用 WorldBook serialized shape，但**语义仍不是 persisted Entity**。

## 12.2 importNew

- name conflict → copy suffix
- new WorldBook ID
- 所有 entry new IDs
- createdAt/updatedAt = now
- source preset metadata由 incoming book shape保留，除非上层 preset service再覆盖

## 12.3 overwrite

保留：

- existing WorldBook ID
- existing name
- existing createdAt

使用 incoming package 的：

- entries
- entry IDs
- sourcePreset metadata
- other book settings

updatedAt = now。

注意：overwrite 与 importNew 不同；overwrite **不会给 incoming entries 重新生成 ID**。

## 12.4 ST World Info import

支持 entries：

- object
- array

别名/字段：

- `key` / `keys`
- `keysecondary` / `secondary_keys` / `secondaryKeys`
- `order` / `insertion_order` / `insertionOrder`
- `position` / `insertion_position`
- case sensitivity
- whole words
- selective / secondary keys
- selectiveLogic
- scanDepth/depth
- role
- recursion flags
- probability
- group/groupWeight
- sticky/cooldown/delay
- regex
- outletName
- character filter
- V2/V3 extension fields

import 时每个 ST entry 都生成 local UUID。

`extensions` 存：

```json
{
  "uid": "...",
  "rawJson": "..."
}
```

其中 malformed individual entries 当前通过 `runCatching(...).getOrNull()` 被跳过；Desktop 若共享此 service 应保留当前 upstream 行为，不能自行改成另一种容错规则。

unsupported lorebook shape：

- `kind`
- `lorebookVersion`
- `items`

→ 明确提示先转 SillyTavern World Info。

## 12.5 ST export

export 为 object entries。

输出包括：

- name/description
- scanDepth/tokenBudget
- recursion/case/word flags
- keys / secondary keys
- enabled→`disable`
- constant/selective/selectiveLogic
- order/position
- match flags
- recursion flags
- probability/group/groupWeight
- sticky/cooldown/delay
- regex/outletName
- characterFilter

`originalPosition` 存在时优先保留，否则从 WorldBookPosition 映射。

---

# 13. CCB PNG contract

## 13.1 metadata codec

`PngTextChunks` 为 JVM-neutral。

CCB keyword：

```text
ChatBarCharacter
```

只处理 PNG `tEXt` chunk。

export：

```text
CharacterCardPackage JSON UTF-8
→ Base64 basic encoder
→ tEXt(ChatBarCharacter, payload)
→ 插入 IEND 前
```

import：

- payload trimStart 以 `{` 开头 → 当 raw JSON
- 否则 → Base64 basic decoder → UTF-8 JSON

因此官方 reader 同时接受：

- raw JSON text payload
- Base64 JSON payload

官方 exporter 当前写：

- Base64 JSON payload

## 13.2 PNG codec details

- 校验 PNG 8-byte signature
- parser 基于 chunk length/type
- 只读取 `tEXt`
- keyword ASCII
- text UTF-8
- insert 时计算新 chunk CRC32
- 不重写其他 chunks
- IEND 缺失 → export error
- 当前 codec 不做完整 image decode，也不负责视觉 cover

---

# 14. Character PNG renderer

`CharacterCardPngRenderer` 是 Android platform renderer，不是 Package codec。

Android-only dependencies：

- `Context`
- `Bitmap`
- `BitmapFactory`
- `Canvas`
- `Paint`
- Android text/layout
- drawable resource

职责：

- 输出普通 PNG cover bytes
- crop/zoom background
- fallback background
- gradient / title / branding

真正 Package payload 插入发生在 renderer **之后**：

```text
renderer.render(...)
→ Base64(packageJson)
→ PngTextChunks.insertTextChunk(...)
```

因此 Desktop：

- visual renderer：目标 EQUIVALENT
- PNG metadata / Package payload：目标 EXACT

不要求 pixel-perfect Android Canvas output。

---

# 15. SillyTavern Character contract

## 15.1 parser split

`SillyTavernCardParser` 当前一个文件混合：

JVM-neutral：
- `parseJson`
- V1/V2 JSON shape
- PNG `Chara` tEXt extraction

Android-only：
- Context
- Uri
- ContentResolver
- MIME
- OpenableColumns display name
- Uri stream

推荐 split pure parser + Android Uri ingress adapter。

## 15.2 ST PNG

keyword：

```text
Chara
```

extract 时：

- keyword ignoreCase
- Base64 MIME decoder
- decoded UTF-8 JSON

## 15.3 mapper

当前 mapper 输出：

```text
CharacterCardPackage(schemaVersion = 5)
```

这是明确 contract。

**不得为了“最新 schema”改成 9。**

ST → CCB mapping：

- editMode = FREEFORM
- freeform text 组合：
  - 角色名称
  - description
  - personality
  - scenario
  - mesExample
- placeholder：
  - `{{char}}` → `$botname`
  - `{{user}}` → `$username`
  - `<BOT>` → `$botname`
  - `<USER>` → `$username`
- greeting / alternates 会 clean ST wrapper/data/font tags
- primary greeting blank 且 alternate 非空 → promote first alternate
- systemPrompt / postHistoryInstructions / mesExample 保留并翻 placeholder
- creator metadata 保留
- PNG card：
  - avatarResourceId = `card-avatar`
  - chatBackgroundResourceId = `card-avatar`
  - images map 中保存原 PNG bytes
- default negative prompt 由 `PromptTemplates.defaultCharacterNaiNegativePrompt()`
- embedded `character_book` 被 parse 为 WorldBook，并放进 Package `worldBooks`
- `PackagedCharacterCard.characterBook = null`

Mapper 当前还使用 Android `Log`；共享时应拆 logging seam，不把 Android logging 带入 sharedCore。

---

# 16. Fish Audio / NovelAI value-type dependency

## 16.1 FishAudioVoiceBinding

`FishAudioVoiceBinding` 本身只是 `@Serializable data class`：

- referenceId
- title
- author metadata
- cover/sample URLs
- visibility
- languages
- tags

但当前位于 `FishAudioEntities.kt`，同文件还有：

- GeneratedVoiceMessage
- VoiceAnchor
- VoiceAnchorState

Phase 3 只需要 binding value type。

**推荐：只把 FishAudioVoiceBinding 拆成 sharedCore 同 package 的独立文件。**

不要把 Fish generation/storage/playback runtime 提前进入 Phase 3。

## 16.2 NovelAiImageModel

当前 enum 位于 `NovelAiStudioModels.kt`，同文件拥有大量 Studio/history/runtime model，并 import `PromptTemplates`。

Phase 3 Character Package 只需要：

```text
NovelAiImageModel
NovelAiTokenizerKind
```

当前值：

- V4_5_FULL
- V5_FULL

**推荐：只抽这两个 value types 到 sharedCore，同 package 保持符号 identity。**

不要移动完整 Studio model/runtime。

---

# 17. Repository contract

## 17.1 CharacterRepository

entity key：

```text
character_cards
```

行为：

- initialize → refresh cache
- list 按 `updatedAt` descending
- getById 直接 storage load
- save → saveEntity + refresh
- update → updatedAt=now then save
- delete → deleteEntity + update in-memory list
- search：card name / CharacterInfo name contains ignoreCase

JVM-neutral。

## 17.2 FormatCardRepository

entity key：

```text
format_cards
```

行为：

- default first，然后 name 排序
- save isDefault=true 时先把其他 default 改 false
- save/update persistence 走 JsonFileStorage
- setDefault 复用 save

JVM-neutral。

## 17.3 WorldBookRepository

entity key：

```text
world_books
```

行为：

- name 排序
- save 强制 `updatedAt = now`
- delete + refresh

JVM-neutral。

## 17.4 Desktop operation gate

以上 repository 所有 JsonFileStorage disk write/read 已自动进入：

```text
DesktopDataOperationCoordinator
```

但是 Character transfer 的：

- image file
- document file
- resource cleanup

并不经过 JsonFileStorage。

Phase 3 的 shared transfer service 必须为整个 durable materialization transaction 使用同一个 narrow `AppDataOperationGate` / equivalent outer normal-operation boundary，避免 snapshot/restore/root-switch 与 resource mutation 并发。

Android 可继续用 no-op gate，不改变 upstream normal behavior。

---

# 18. 关键 Desktop 路径问题：absolute path 与 root switch

这是 Phase 3 新发现的最重要架构约束之一。

Android 当前 materializer 写：

```text
File(...).absolutePath
```

进入：

- CharacterCard.avatar
- CharacterCard.chatBackground
- CharacterInfo.appearanceImage
- DocumentInfo.filePath

但 Phase 2 root migration 的合同是：

```text
raw-byte copy
→ generic destination validation
→ authority switch
```

它**不会 schema-aware rewrite Entity JSON 中的 absolute path**。

如果 Desktop 直接把：

```text
H:\old-root\images\...
```

存进 Entity，切换到：

```text
D:\new-root
```

后 JSON 被原样复制，新进程仍会引用旧 root。

后果：

- 新 root authority 已切换
- Entity resource 仍从旧 source 读取
- 数据变成 split-root ownership
- source retention 变成事实依赖
- 未来 source cleanup 不可能安全
- Portable/root relocation 也会被 absolute reference 破坏

因此 **Desktop 不应简单照抄 Android absolute-path representation**。

### PROJECT DECISION RESOLVED — P3-D1 → D-027

#### Option A — Desktop root-relative owned resource reference【SELECTED】

Desktop Entity String 中保存：

```text
images/...
documents/...
```

或等价明确的 appData-relative reference。

所有 Desktop resource consumer 通过 resolver：

```text
appDataRoot + owned reference
```

解释。

优点：

- Phase 2 raw root migration天然正确
- Portable relocation正确
- 不需要 schema-aware rewrite
- unknown future Entity 不拖累 root migration core
- Package 仍完全 portable

代价：

- Desktop Entity JSON 的 path value representation 与 Android absolute path 不同
- 所有 Desktop resource consumer 必须禁止裸 `File(ref)`

语义仍保持“本地 owned resource reference”，属于平台合理等位。

#### Option B — absolute path + migration rewrite【NOT SELECTED】

优点：

- Entity path 表面与 Android更像

缺点：

- root migration 需要识别所有已知/未来 Entity path fields
- 破坏 Phase 2 generic raw-copy contract
- upstream 新字段容易漏 rewrite
- rollback / unknown payload / Portable 更复杂

结论：不建议。

---

# 19. Shared import vs Desktop typed import

上游 skill 明确区分：

## A. Management-page typed import/export

用户明确点击：

- Character import
- Format import
- WorldBook import

目标类型已知。

Phase 3 Desktop 应优先实现这一条。

## B. Global SharedImport pipeline

Android：

- ACTION_SEND
- ACTION_VIEW
- EXTRA_TEXT
- staged bytes
- FIFO
- content-first classifier
- conflict UI
- image Studio handoff

这不是“有个 file picker”就完成 parity。

## C. Desktop Phase 3 narrow equivalent

Phase 3 需要：

- open one file
- save one file
- Character JSON/PNG
- Format JSON
- WorldBook CCB/ST JSON
- content classifier foundation
- drag/drop routing base

但以下完整 shared-import lifecycle 可以留到后续 UI/OS integration：

- OS Open With / file association
- global process queue UI
- Studio shared-image handoff
- navigation focus
- Task Center integration

现有 `DesktopDirectoryPicker` 只为 Phase 2 root directory 选择设计，不能扩大成 general file picker。

推荐另建窄接口：

```text
openOneFile(...)
saveOneFile(...)
```

不要设计 oversized platform filesystem framework。

---

# 20. Dependency / extraction map

| Source | 分类 | Phase 3 处理 |
|---|---|---|
| `CharacterCard.kt` | SHARE_EXACT_NOW | move shared after value-type dependencies |
| `FormatCard.kt` | SHARE_EXACT_NOW | move shared |
| `WorldBook.kt` | SHARE_EXACT_NOW | move shared |
| `FishAudioVoiceBinding` | SPLIT_PURE_CORE | binding only shared |
| `NovelAiImageModel` | SPLIT_PURE_CORE | enum/tokenizer only shared |
| `CharacterRepository.kt` | SHARE_EXACT_NOW | move shared |
| `FormatCardRepository.kt` | SHARE_EXACT_NOW | move shared |
| `WorldBookRepository.kt` | SHARE_EXACT_NOW | move shared |
| `CardTransferModels.kt` | SHARE_EXACT_NOW after validation seam | schema exact |
| `CharacterPlaceholderPolicy.kt` | SHARE_EXACT_NOW | pure JVM |
| `CharacterSpeakerNamePolicy.kt` | SHARE_EXACT_NOW | pure JVM |
| `NamePolicy.kt` | SHARE_EXACT_NOW | pure JVM |
| `WorldBookReusePolicy.kt` | SHARE_EXACT_NOW | pure JVM |
| `FormatCardUserToolPolicy.kt` | SPLIT | validation shared; Prompt runtime Phase 4 |
| `FormatCardTransferService.kt` | SHARE_EXACT_NOW | pure JVM |
| `WorldBookTransferService.kt` | SHARE_EXACT_NOW | pure JVM |
| `PngTextChunks.kt` | SHARE_EXACT_NOW | pure JVM |
| `CharacterCardTransferService.kt` | SPLIT_CORE_PLATFORM | resource store + Prompt dependency + RAG seam |
| `CharacterCardPngRenderer.kt` | KEEP_ANDROID + DESKTOP_EQUIVALENT | metadata codec remains shared |
| `SillyTavernCardParser.kt` | SPLIT | JSON/PNG pure; Uri Android |
| `SillyTavernCardMapper.kt` | SPLIT | pure mapping + logger seam + Prompt dependency |
| `SharedImportClassifier.kt` | SHARE_EXACT_LATER | after ST pure core |
| `SharedImportCoordinator.kt` | KEEP_ANDROID_PLATFORM | Desktop ingress separate |
| `CreateOpenableDocument.kt` | KEEP_ANDROID_PLATFORM | Desktop save picker equivalent |

---

# 21. sharedCore build prerequisite

当前 `sharedCore`：

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
}
```

dependencies 已有：

- kotlinx.coroutines
- kotlinx.serialization.json

但是 **尚未应用 Kotlin serialization compiler plugin**。

Entity / Package 的 `@Serializable` serializer 若迁入 sharedCore，Phase 3 第一轮必须补：

```text
org.jetbrains.kotlin.plugin.serialization
```

或项目 version-catalog 等价 plugin。

这是 Phase 3 production extraction 的必要 build change，不是 schema change。

---

# 22. Prompt ownership boundary

## 22.1 当前无法直接共享的 dependency

不能为了 Phase 3 简单把整个 `PromptTemplates.kt` 提前搬入 sharedCore。

它当前直接依赖：

- Character entities
- ChatMessage / ChatSession
- MomentPost
- MessageRole
- NovelAiImageModel
- PlaceholderRenderer
- WorldBook
- 以及大量 Phase 4+ runtime prompt owners

强行提前移动会把 Phase 4/13 的大量类型一起拖进 Phase 3。

## 22.2 禁止的方案

- 在 Desktop 复制 `DEFAULT_CHARACTER_NAI_NEGATIVE_PROMPT`
- 在 Desktop 自己写一个“差不多”的 default negative
- 为 transfer 简化 `effectiveCharacterNaiNegativePrompt`
- 将 ST mapper schema 5 顺手改成 schema 9

这些都会形成 Prompt / Package fork。

### PROJECT DECISION RESOLVED — P3-D2 → D-028

#### Option A — transfer core 注入 Prompt-owned policy，最终 parity gate等待 Phase 4A【SELECTED】

Phase 3 shared Character transfer core定义窄 dependency：

```text
defaultCharacterNaiNegativePrompt()
effectiveCharacterNaiNegativePrompt(value)
```

Android production implementation继续委托官方 `PromptTemplates`。

Desktop 不复制文本。

在 Phase 4A 将 authoritative Prompt ownership真正共享后，再给 Desktop production wire 同一实现，完成 Phase 3 Character transfer final parity gate。

结果：

- Phase 3 大部分可以先做
- Phase 3 final COMPLETE 与 Phase 4A 有一个明确 dependency edge
- 不制造 prompt fork

#### Option B — 把 prompt text 从 PromptTemplates 拆出去【NOT SELECTED FOR PHASE 3】

违反当前 upstream prompt skill ownership，不推荐。

---

# 23. Failure / recovery matrix

| Stage | 已发生 durable change? | upstream cleanup | retry / 备注 |
|---|---|---|---|
| decode | no | n/a | safe |
| validate | no | n/a | safe |
| name resolution | no | n/a | safe |
| image materialize | yes: new files | `createdFiles.delete()` on thrown materialize failure | delete return 未验证 |
| document materialize | yes: new files | same | safe-ish / best effort |
| WorldBook import | **yes: new Entity may persist** | later failure does **not** rollback imported books | orphan risk |
| default Format import | **yes: new Entity may persist** | later failure does **not** rollback imported Format | orphan risk |
| Character saveNew | may fail | removes materialized card files only | related Entities may remain |
| overwrite Character save | on success replacement authoritative | catch only deletes newly materialized files on save failure | old Entity intact if save itself fails |
| overwrite old-resource cleanup | replacement already saved | file deletion best effort; RAG cleanup may throw | caller may see failure after commit |
| RAG cleanup | may mutate chunks | no transaction with card save | post-commit failure possible |
| export entity lookup | no | n/a | safe |
| export local resource read | no | n/a | missing resource fails |
| default Format export lookup | no | n/a | missing binding explicit failure |
| PNG render | no app-data mutation | n/a | output bytes transient |
| PNG payload insertion | no app-data mutation | n/a | pure bytes |
| external destination write | outside Entity store | adapter-owned | Desktop 应 temp-write/replace where applicable |
| delete owned resources | **yes** | happens before Entity delete | destructive failure ordering risk |
| delete Entity | final step | no restore of prior deleted files | see upstream bug |

---

# 24. 疑似 upstream bugs

## 24.1 CARD-TRANSFER-001 — failed Character import can leave durable orphan WorldBook / FormatCard

**发现疑似 upstream bug — 建议报告作者**

- location：`CharacterCardTransferService.kt`
- baseline：`5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`
- relevant sequence：
  - materialize saves imported WorldBooks
  - materialize may import/save default FormatCard
  - final Character repository save happens afterwards
  - `saveNew()` failure cleanup only removes card-owned files
  - materialize catch only removes `createdFiles`
- behavior：
  - Character import/overwrite failure can leave new WorldBook / FormatCard entities despite Character operation reporting failure
- expected：
  - failed import should not create unrelated durable entities, or result should expose a committed-partial outcome with recovery ownership
- trigger：
  - WorldBook/Format persistence succeeds, later Format/Card save or later materialization step fails
- impact：
  - orphan resources/entities, retry may create/reuse unexpected records
- severity：MEDIUM（failure-path data consistency）
- minimal repro：
  - package includes new WorldBook/defaultFormatCard
  - inject repository save failure after related entity persistence
  - assert failed Character import leaves newly persisted related Entity
- possible fix：
  - explicit side-effect ledger + rollback for newly-created related entities
  - or transactional staged import design
- workaround：
  - Desktop 不应把该行为宣传为 atomic；实现前需决定 failure hardening policy

## 24.2 CARD-TRANSFER-002 — deleteCard deletes resources before Entity delete

**发现疑似 upstream bug — 建议报告作者**

- location：`CharacterCardTransferService.deleteCard()` / `deleteOwnedResources()`
- baseline：同上
- behavior：
  - local files/RAG cleanup occurs before `repository.delete(id)`
- expected：
  - repository deletion failure should not leave a still-live Character Entity referencing already-deleted owned resources
- trigger：
  - resource deletion succeeds, then RAG cleanup or repository delete fails
- impact：
  - live Character may reference missing image/document files
- severity：HIGH（destructive failure ordering）
- minimal repro：
  - valid Character with owned files
  - inject RAG/repository delete failure after file cleanup
  - assert Character remains while file has disappeared
- possible fix：
  - establish durable delete commit/tombstone first
  - then cleanup resources as post-commit best effort
  - or add recoverable deletion transaction
- workaround：
  - Desktop 不应为了“bug-for-bug parity”复制可能破坏用户数据的 destructive ordering

---

# 25. PROJECT DECISION RESOLVED — failure hardening

### P3-D3 → D-029

Option A：完全复制 upstream failure semantics【NOT SELECTED】  
- 优点：failure-path 也 bug-for-bug
- 缺点：与 Desktop data-safety 原则冲突

Option B：保持正常成功语义 EXACT，同时对 destructive/partial failure path 做窄 hardening【SELECTED】  
- Character Package/Entity/result semantics不变
- 记录 downstream safety divergence
- 给 upstream 提 issue
- upstream 修复后优先回收第二套 guard

项目原则“数据安全 > schedule/quota”支持 Option B。

---

# 26. Test inventory

## 26.1 应迁入/复用于 sharedCore

- `CharacterCardPackageTest`
- `CharacterFishAudioSerializationTest`
- `CharacterPlaceholderPolicyTest`
- `CharacterSpeakerNamePolicyTest`
- `NamePolicyTest`
- `WorldBookReusePolicyTest`
- `FormatCardTransferServiceTest`
- `WorldBookTransferServiceTest`
- `PngTextChunks` round-trip coverage（当前部分位于 CharacterCardPackageTest）

## 26.2 需要 split

`FormatCardUserToolPolicyTest`

- validation subset → shared Phase 3
- random/strong Prompt placement/runtime → Phase 4

`SharedImportClassifierTest`

- classifier 本体可成为 shared fixture test
- 需先完成 ST parser/mapper platform split

## 26.3 保留 Android / 新增 Desktop equivalent

`CharacterCardPngExportOptionsTest`
- options 可以共享或复制为 renderer-neutral options
- Android Canvas renderer test 留 Android
- Desktop renderer 需新测试

`SharedImportFifoQueueTest`
- queue algorithm纯 JVM
- 但完整 shared-import global queue 不属于 Phase 3 typed importer final gate；可后续共享

## 26.4 明显缺口

当前未发现专门的：

```text
CharacterCardTransferServiceTest
```

覆盖完整 resource/materialization/repository transaction。

Phase 3 必须新增：

- shared Character transfer core tests
- Desktop resource materialization tests
- Desktop repository persistence tests
- failure cleanup tests
- root-relative resource ref + root-switch regression
- Android↔Desktop fixture tests

---

# 27. Cross-platform fixture matrix

## 27.1 Character JSON

至少：

1. schema 3 minimum legacy
2. representative middle legacy schema（建议 5）
3. schema 8 + `FishAudioVoiceBinding`
4. schema 9
5. schema 9 + embedded defaultFormatCard
6. avatar + chat background + appearance image
7. documents
8. worldBooks
9. unknown fields
10. missing image resource rejection
11. blank placeholder vs unnamed-with-content
12. blank negative prompt default behavior

## 27.2 Format

- v1
- v2 RANDOM_NUMBER
- v2 STRONG_PROMPT_SUFFIX
- ordered mixed tools
- exact character-default reuse
- same name/different tools conflict

## 27.3 WorldBook

- CCB v1
- ST object entries
- ST array character_book
- extensions/rawJson
- recursion/group/regex/filter fields
- ST export→import

## 27.4 PNG

- CCB `ChatBarCharacter` Base64 payload
- raw JSON payload accepted
- malformed payload rejected
- ST `Chara` PNG
- ordinary PNG stays image in classifier

## 27.5 directions

最终 acceptance：

```text
Android export → Desktop import
Desktop export → Android import
```

Character 必须：

```text
JSON + PNG
```

Format / WorldBook：

```text
JSON
```

WorldBook 另含 ST interop。

---

# 28. Desktop resource storage recommendation

Phase 3 只需要一个窄的 card-transfer resource boundary，不需要 generic filesystem framework。

建议能力：

```text
readText(ref)
readBytes(ref)
materializeDocument(...)
materializeImage(...)
deleteOwned(ref)
resolveForDisplay(ref)
```

Android implementation：

- current filesDir paths / assets behavior
- Entity 可继续 absolute path

Desktop implementation：

- owned files位于：
  - `<appDataRoot>/images/`
  - `<appDataRoot>/documents/`
- persisted Entity 使用 root-relative owned ref
- resolver 负责转换到 current appDataRoot

`asset:` 作为单独 logical bundled-resource resolution path，不当作普通 disk path。

---

# 29. Proposed Phase 3 implementation slices

## 3B1 — Shared Entity / Package Contract Core【第一轮 Codex，推荐立即排队】

目标：

- sharedCore serialization plugin
- split FishAudioVoiceBinding value type
- split NovelAiImageModel/tokenizer value type
- move Character/Format/WorldBook entities
- move three repositories
- move CardTransferModels
- move simple policies
- move PngTextChunks
- split Format user-tool validation from Prompt runtime
- migrate corresponding pure tests

禁止：

- Character resource materialization
- Desktop file picker
- Prompt text
- renderer
- ST mapper Prompt bridge

Gate：

- Android compile
- sharedCore compile/tests
- Desktop compile
- Android relevant regression
- schema JSON fixtures byte/semantic parity

预计 Codex weekly：约 `0.08–0.12`

## 3B2 — FormatCard + WorldBook Transfer Core

目标：

- share exact Format transfer
- share exact WorldBook transfer
- ST World Info import/export
- repository round-trip tests

Gate：

- v1/v2 Format
- WorldBook v1
- ST object/array
- reuse/overwrite semantics

预计：`0.05–0.07`

## 3C1 — Character Resource / Materialization Core

前置：

- P3-D1 resource ref decision
- P3-D3 failure-hardening decision

目标：

- narrow card resource store
- Android adapter
- Desktop root-relative adapter
- whole-transfer Desktop operation-gate admission
- Character materialization / overwrite / duplicate / delete

暂不完成 Prompt-dependent final wire。

预计：`0.08–0.11`

## 3C2 — ST Character + Classifier split

目标：

- pure ST JSON/PNG parser
- Android Uri adapter retained
- mapper logging seam
- shared classifier
- content-first fixtures

受 P3-D2 Prompt dependency影响。

预计：`0.05–0.07`

## 3D — Desktop Typed File Import/Export + PNG renderer equivalent

目标：

- narrow open/save picker
- Character JSON/PNG
- Format JSON
- WorldBook JSON/ST
- Desktop cover renderer equivalent
- metadata exact

不宣称完整 Android shared-import queue parity。

预计：`0.07–0.10`

## 3P / Phase 4A dependency — Prompt ownership closure

目标：

- 不复制 Prompt
- 为 Desktop 提供 authoritative upstream-owned character negative-default helpers
- 不改 Prompt text
- 不改变 assembly

具体实现需等 Project 对 P3-D2 决策。

## 3F — Android ↔ Desktop interoperability gate

- fixture matrix
- bidirectional JSON/PNG
- malformed/corrupt cases
- resource/root-switch regression
- packaged manual import/export acceptance

预计：`0.05–0.08`

---

# 30. Budget impact

原 Phase 3 baseline：

```text
0.50 weekly
```

原本为 Codex contract audit 预留：

```text
约 0.08 weekly
```

本次由 Project 完成：

```text
Codex cost = 0
```

暂不下调 Program Budget baseline；记为 favorable variance。

当前 Phase 3 可用 baseline：

```text
~0.50 weekly original envelope
audit 已完成，不占 Codex
```

第一轮 production task `3B1` 的计划消耗：

```text
0.08–0.12 weekly
```

---

# 31. Phase 3A readiness

结论：

```text
Formal baseline verified: YES
Upstream drift: NONE

Package / Entity / Prompt separated: YES
Character schema contract verified: YES
Format schema contract verified: YES
WorldBook schema contract verified: YES
transfer Json traced: YES
Entity persistence Json traced: YES
repository keys/semantics mapped: YES
Character materialization mapped: YES
default Format reuse mapped: YES
WorldBook reuse mapped: YES
PNG contract mapped: YES
ST contract mapped: YES
Fish/NovelAI value-type dependency mapped: YES
shared-import boundary mapped: YES
test inventory mapped: YES
failure/recovery mapped: YES
implementation slices defined: YES

Production source changed: NO
Prompt changed: NO
Package/schema changed: NO
Phase 3 implementation started: NO
```

三个 Project decisions 已关闭并进入 `18_DECISIONS.md`：

1. **D-027 / P3-D1** — Desktop resource reference：root-relative owned resource reference。
2. **D-028 / P3-D2** — Prompt dependency bridge：不复制 Prompt；Character transfer final parity 使用 authoritative Prompt-owned policy，并允许显式依赖 Phase 4A closure。
3. **D-029 / P3-D3** — normal success semantics 保持 parity；destructive/partial failure path 做窄 data-safety hardening，并向 upstream 报告。

第一轮 `3B1` 可以直接实施；后续 3C1/3C2/3P 必须遵守上述 decisions。Codex 预算与实际消耗记录规则见 `23_CODEX_BUDGET.md`。
