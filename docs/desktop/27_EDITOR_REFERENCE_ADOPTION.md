# CCB Desktop — Editor Reference Adoption Map

状态：**PROJECT ADOPTION AUDIT COMPLETE**

Reference source：`CCB_EDITOR_REFERENCE_PACK.zip`

Reference identity：

`REF — Mature User-Validated Editor UX / Workflow / Regression Evidence`

本文件只记录 Reference Pack 对 CCB Desktop 的可采用价值、验证边界与未来消费位置。它不是 upstream authority、Desktop specification、Package/schema authority、Prompt authority，也不是允许直接移植的 Web implementation。

---

## 1. Authority boundary

CCB Desktop 的信息权威顺序保持不变：

1. 当前 Phase 固定的 upstream baseline source
2. 当前 fork `desktop` branch code
3. `docs/desktop/` CURRENT docs
4. Project Instructions
5. Reference assets / historical material

Reference Pack 与上述高权威来源冲突时，Reference Pack 让位。

始终严格区分：

- transport Package
- persisted Entity
- final Prompt / API-message runtime semantics

Reference Pack 中的 workspace object、export mapper、browser UI state、cover state、history state、resource picker 等均不得被直接解释为 Desktop Entity 或当前 CCB Package contract。

---

## 2. Evidence reviewed

本次 Project adoption audit 按以下顺序读取并核对：

1. `00_REFERENCE_README.md`
2. `06_LESSONS_LEARNED.md`
3. `01_FEATURE_INVENTORY.md`
4. `02_UX_WORKFLOWS.md`
5. `03_IMAGE_EDITOR_REFERENCE.md`
6. `04_DATA_AND_EXPORT_BEHAVIOR.md`
7. `08_SOURCE_MAP.md`
8. `originals/10_CCB_Package_Editor_CURRENT.html`
9. `07_ACCEPTANCE_SCENARIOS.md`
10. `09_OPEN_ISSUES.md`
11. release/source-map boundary docs

Reference editor baseline：

- Public `1.0.0`
- internal `v10.0`
- frozen `2026-09-20`

其实际成熟度来自真实多卡制卡流程和用户验收，因此 UX / workflow / regression evidence 具有高参考价值；其 Web 技术实现与 release-era mapper 只作为历史实现证据。

---

## 3. Current baseline re-audit

本次 Reference adoption 不以旧编辑器为 schema 真值，而重新对当前 Phase 3 固定 upstream baseline：

`ChatBar 1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`

以及当前 `desktop` 实现进行核对。

### 3.1 Package contracts

当前 baseline 独立确认：

- CharacterCardPackage：write schema `9` / read `3..9`
- FormatCardPackage：write schema `2` / read `1..2`
- WorldBookPackage：schema `1`

因此 Reference Pack 中的 hard-coded `SCHEMA=9` **恰好与当前 baseline 一致**，但它仍不是 authority，也不得作为未来版本常量来源。

### 3.2 FREEFORM / STRUCTURED

当前 upstream `CharacterCard` / `CharacterCardPackage` 同时保留：

- `characters`
- `freeformCharacterText`
- `editMode`

当前 Android editor 的普通 `switchEditMode()` 只改变 mode，不自动清空或转换另一套内容。

upstream 另有显式 `StructuredCharacterFreeformConverter` / conversion action；它与普通 mode switch 是不同用户操作。

因此 Reference Pack 的“普通 FREEFORM ↔ STRUCTURED 切换必须非破坏”可作为有效 regression oracle，但不得解释为“Desktop 永远不能提供显式转换命令”。

### 3.3 Image slot identity

当前 Package 独立确认的 CCB-facing image slots 为：

- `card.avatarResourceId`
- `card.chatBackgroundResourceId`
- `characters[].appearanceImageResourceId`

资源数据继续位于 Package `images` map。

Reference Pack 对 avatar / background / character appearance / share cover 的严格区分，与当前 contract 相容；share cover 仍不是第四个 CharacterCardPackage image slot。

### 3.4 PNG carrier behavior

当前 upstream：

- keyword：`ChatBarCharacter`
- decode：接受 raw JSON 或 Base64 JSON payload
- normal export：先重新 render PNG，再插入当前 Package payload
- 未定义“保留导入 PNG 可见像素，仅替换 payload”的官方 export contract

当前 Desktop 3D 同样使用 platform renderer 生成新 PNG，再通过 shared `CharacterCardPngPackageCodec` attach payload。

另外，当前 authoritative `PngTextChunks.insertTextChunk()` 是向 PNG 的 IEND 前插入 chunk，并不是 Reference editor `insertPackage()` 那种“先移除/替换已有同 keyword chunk”的 preserve-carrier 算法。

结论：

**A7 Share-cover preserve mode 不是当前 upstream parity contract。**

它保留为未来高价值 enhancement / regression concept；若未来 Desktop 实现，必须单独设计“replace existing `ChatBarCharacter` chunk”语义并验证 stale-payload 风险，不能直接复用当前 `insertTextChunk()` 假装等价。

### 3.5 Old export mapper conflict

Reference editor `knownPackageForExport()` 会执行 release-era normalization，例如把 `card.characterBook` 迁入 `worldBooks` 后将 `card.characterBook = null`。

当前 upstream transfer/export 行为由当前源码定义，不能以该旧 mapper 覆盖。

因此 old Package normalization/export mapper 明确属于 `REJECT_IMPLEMENTATION` / historical conversion evidence。

---

## 4. ADOPT_UX

以下成熟交互正式进入 Desktop UX reference：

- unified image workbench
- image purpose slots
- full card-name workspace / directory
- alias 与真实 `card.name` 分离
- search
- multi-select
- Ctrl toggle selection
- Shift range selection
- select all / invert / clear
- drag reorder
- context actions
- direct pan
- wheel zoom
- slider precision adjustment
- existing-resource re-crop workflow

采用的是**任务模型和用户交互原则**，不是 DOM / Canvas 代码。

关键 UX 原则：

- 先告诉用户“这张图是干什么的”，再暴露 raw resource。
- active card 与 selected cards 必须是两个明确状态。
- 长名称必须完整可识别。
- 批量操作必须明确目标集合。
- direct manipulation 与 slider precision 同时保留。
- re-crop 默认创建新结果，不静默覆盖旧资源。

---

## 5. ADOPT_ORACLE — A1–A16

Reference Pack 的 A1–A16 全部保留为 Desktop test-oracle candidates，但 expected semantics 必须在实现对应功能时再次对照当时的 upstream baseline。

| Scenario | Adoption | Current interpretation |
|---|---|---|
| A1 multi-card import/navigation | `ADOPT_ORACLE` | 未来 Desktop editor/workspace UX regression oracle |
| A2 multi-selection reorder | `ADOPT_ORACLE` | 未来 workspace reorder + undo/redo oracle |
| A3 FREEFORM ↔ STRUCTURED switch | `ADOPT_ORACLE` **HIGH VALUE** | 普通 mode switch 非破坏；与显式 conversion action 分离 |
| A4 image slot identity | `ADOPT_ORACLE` **HIGH VALUE** | avatar/background/appearance 不得互相污染 |
| A5 direct crop interaction | `ADOPT_ORACLE` | preview/final crop geometry 与 direct manipulation oracle |
| A6 existing-resource re-crop | `ADOPT_ORACLE` **HIGH VALUE** | 生成新资源/新结果；原资源保持 |
| A7 share-cover preserve | `CONSIDER_ENHANCEMENT` + `REFERENCE_ONLY` for parity | 当前 upstream 未定义 preserve-carrier export contract |
| A8 cover interaction/flicker | `ADOPT_ORACLE` | future compositor responsiveness / preview-final geometry |
| A9 private safe-cover isolation | `ADOPT_ORACLE` | future Desktop-private cover source 不得污染 Package images |
| A10 cross-card resource copy | `ADOPT_ORACLE` **HIGH VALUE** | 目标卡必须获得真实资源内容/ownership；不能只复制 resource ID |
| A11 only-empty bulk avatar | `ADOPT_ORACLE` | 不得覆盖已有 scene-specific refs |
| A12 resource rename/delete safety | `ADOPT_ORACLE` | rename 要维护 refs；referenced delete 必须显式处理 |
| A13 WorldBook edit/export/re-import | `ADOPT_ORACLE` | future editor regression；字段语义仍由当前 upstream 决定 |
| A14 batch config mutation boundary | `ADOPT_ORACLE` **HIGH VALUE** | batch card-level copy 不得意外复制 card identity/characters/worldBooks/images |
| A15 global undo/redo after batch/close | `ADOPT_ORACLE` | batch transaction 应作为 coherent history unit |
| A16 export → import round trip | `ADOPT_ORACLE` **HIGH VALUE** | Package/resources/refs/defaultFormatCard/mode/body 不得 editor-induced loss |

### High-value regression oracles

必须重点保留：

- **A3** — FREEFORM ↔ STRUCTURED non-destructive switching
- **A4** — image slot identity
- **A6** — existing-resource re-crop
- **A10** — cross-card resource copy / ownership
- **A14** — batch configuration mutation boundary
- **A16** — export → import round trip

A7 单独受 §3.4 的 current-upstream audit 结论约束。

---

## 6. ALGORITHM_CANDIDATE

以下只作为 Kotlin/JVM / Compose Desktop 重新设计时的算法候选：

### Normalized crop center / zoom

Reference model：

- center X/Y：normalized `0..1`
- zoom：bounded scalar
- cover/fill crop
- preview 与 final 共用同一 source-window math

该模型与当前 upstream/Desktop `CharacterCardPngExportOptions.cropCenterX/Y/cropZoom` 的方向相容，因此是高优先级 candidate，但不要求复制浏览器函数。

### Normalized mask/text coordinates

- overlay coordinates 使用 normalized canvas space
- preview/final 使用同一几何定义
- UI display scale 与 final pixels 解耦

未来 Compose/Desktop renderer 需重新决定 precision、hit testing、DPI 和 font metrics。

### Multi-selection reorder semantics

- selected cards 作为 group 移动
- group 内 relative order 不变
- active card 与 selection 独立
- undo/redo 恢复 order + current + selection

### Image preview → materialization transform model

明确区分：

1. source/original bytes
2. edit transform state
3. preview rendering
4. committed/materialized result
5. CCB-facing slot reference

Reference Pack 的 browser Canvas pipeline 只用于理解该模型，不作为 native implementation。

---

## 7. CONSIDER_ENHANCEMENT

以下是未来 Desktop architecture/design candidates，不在本次 adoption audit 中做 production design 决策：

- reusable **Desktop Image Workspace**
- **Desktop Image Editing Foundation**
- original/master image provenance
- undo/redo editing framework
- text overlay
- rectangle masks
- mosaic
- blur

### Desktop Image Workspace / Image Editing Foundation

它是 future architecture/design candidate，不新增当前 production slice，也不改变既定 Phase 顺序。

未来可能服务：

- card avatar
- chat background
- character appearance images
- share cover
- Moments image-bearing flows
- 其他 image-bearing features

优先目标是共享**交互/变换/编辑事务基础**，不是强迫所有图片功能共享同一个巨大 UI state object。

### Original/master provenance

Reference editor 没有真正 immutable original-master provenance。

若 Desktop 将来增加：

- 不得未经 audit 写入 upstream CharacterCardPackage；
- 优先考虑 Desktop-private state、workspace metadata 或明确的 Entity-side/private metadata；
- 必须定义迁移、backup、export、cleanup 与 ownership；
- 当前不作字段/schema 决定。

### Undo/redo

未来应优先采用 transaction/command boundary：

- batch action 一次 undo
- reorder 一次 undo
- image assignment/re-crop 一次 undo
- 不把高影响 batch action 拆成大量细碎 history entries

具体 snapshot / command / persistent draft 机制留待对应 architecture slice。

---

## 8. REFERENCE_ONLY / REJECT_IMPLEMENTATION

以下明确不得直接移植或作为 Desktop 真值。

### REFERENCE_ONLY

- release-era field defaults
- browser MIME inference
- browser preview/render scheduling实现
- editor workspace/carrier/cover/history object shape
- private safe-cover data URL representation
- shallow image-history optimization
- old validation warnings
- old WorldBook normalization defaults
- release-era compatibility workarounds

### REJECT_IMPLEMENTATION

- hard-coded schema 9 as permanent truth
- old Package normalization/export mapper
- DOM implementation
- Web Canvas implementation details
- localStorage
- requestAnimationFrame workaround as architecture requirement
- browser font approximation
- `prompt()`-style resource picker
- custom ZIP writer
- custom Web PNG writer/parser as preferred native infrastructure

这些可以解释历史 bug、交互取舍和算法意图，但不能成为 Desktop production implementation authority。

---

## 9. Phase mapping

### Current chronology

本 Reference Pack 在 CCB Desktop 的接管时间点晚于 3C1 / 3C2 / 3D implementation。

因此它**不得重新打开或扩大已完成 slice**。

### 3C1 — Character Resource / Materialization Core

状态与 scope 保持不变。

Reference Pack 对 3C1 仅提供 retrospective resource-ownership / failure-case oracle：

- A4 image slot identity
- A10 cross-card resource copy / ownership
- postcondition verification principle

不把 crop/workbench/cover/undo/batch editor UI 塞入 3C1。

### 3C2 — ST Character + classifier split

scope 保持不变。

Reference Pack 不改变 ST parser / mapper / classifier authority，也不把旧 Web importer 当作 ST/CCB format truth。

### 3D — Desktop Typed Import/Export + PNG renderer

3D 已按 current upstream contract 完成。

Reference Pack 的 retroactive audit 结论：

- A16 round-trip 与 3D/3F interoperability目标高度一致，可继续作为 regression oracle；
- resource/package round-trip、private cover isolation 等概念可服务未来 editor/image work；
- A7 preserve-carrier 已重新审计：**不是 current upstream contract，不追溯修改 3D**；
- Reference fixtures 可作为 future test-input candidates，但在使用前必须经过 current Package decoder/validator，并不得升级为 schema authority。

### 3F — Android ↔ Desktop interoperability

A16 与 cross-platform Package/resource round-trip 可作为补充 oracle。

Reference Pack 不改变 3F 的 authoritative acceptance evidence。

### Future Character Editor / Image Workspace

大量消费 Reference Pack 的主要阶段是未来：

- Phase 6 — Desktop Primary UI / Editors
- Phase 7 — Image Resources + NovelAI

`Desktop Image Workspace / Image Editing Foundation` 只登记为 future architecture/design candidate。

它不是当前新 production slice。

---

## 10. Parity rule

Reference Pack adoption **不会自动提升** `13_FEATURE_PARITY.md`。

只有当前 Desktop 已有真实实现并满足 upstream-aligned acceptance 时，才能使用：

- EXACT
- EQUIVALENT

Reference editor 已经成熟实现某功能，不代表 Desktop 已实现。

因此 multi-card editor、undo/redo、full image workspace、text/masks/mosaic/blur 等仍按 Desktop CURRENT 实际状态记录。

---

## 11. Adoption result

Project conclusion：

- Reference Pack 足以支持正式 Adoption Map。
- 它对成熟 editor UX、image-workflow、batch mutation boundary 和 regression-oracle 价值很高。
- 没有发现要求推翻当前 Package/Entity/Prompt authority 的证据。
- 发现两类必须明确隔离的 historical divergence：
  1. old export normalization / mapper 不能代表 current upstream；
  2. PNG preserve-carrier 是成熟 editor enhancement，不是 current upstream export contract。
- 已完成的 3C1 / 3C2 / 3D 不因本 Reference Pack 重开或扩 scope。
- Future Desktop editor/image architecture 可以大量消费本 Reference Pack，但必须重新用 Kotlin/JVM / Compose Desktop 设计。
