# CCB Desktop Development Roadmap

目标：完成 CCB Desktop，同时建立可持续跟随 upstream 的开发机制。

---

## Phase 0 — Bootstrap / Audit

状态：**COMPLETE**

产物：
- Source Map
- Instructions
- baseline
- audit
- architecture
- parity
- compat
- sync playbook
- test matrix
- decisions
- data compat
- release checklist
- current state

完成状态：
- Fork `MisakaPiano/ChatChatBar-Desktop` 已建立
- `desktop` branch 已建立
- 新 ChatGPT Project 已完成首次接管
- upstream 1.3.49 baseline / schema / architecture / feature-skill consistency 已复核并合入 `desktop`
- 首轮只读审计与文档修正已完成

---

## Phase 1 — Desktop Bootstrap

状态：**COMPLETE**

目标：
```text
:app Android 不受影响
:desktopApp 可启动 Windows 原生窗口
```

内容：
- Compose Desktop plugin/module
- Main entry
- application window
- Desktop version/about
- data-root discovery skeleton
- build command
- native distribution smoke config

实现结果：
- 新增纯 Kotlin/JVM `:desktopApp`，使用 Kotlin 2.3.20、Compose Desktop 1.10.3、JVM 17
- 原生 `application` / `Window` 入口标题为 `ChatChatBar Desktop`
- 数据根目录只读解析为 `%LOCALAPPDATA%\ChatChatBarDesktop`；环境变量缺失时回退到 JVM `user.home\AppData\Local\ChatChatBarDesktop`
- EXE / MSI native distribution 已配置，未执行发布或签名
- Desktop compile / unit tests 与 Android compile / 1141 项 unit tests 均通过
- manual GUI acceptance：Windows native window **PASS**
- title：`ChatChatBar Desktop`
- bootstrap content：**PASS**
- displayed data directory：**PASS**
- run process clean exit：**PASS**

不做：
- 业务功能
- shared 大重构

验收：
- Android compile：PASS
- Android unit tests：PASS（1141 tests，0 failures）
- Desktop compile / unit tests：PASS
- Desktop run / manual GUI acceptance：PASS
- no browser/webview：PASS

下一步：
- Phase 2 — Shared Storage Foundation
- Phase 2 已进入实施阶段

---

## Phase 2 — Shared Storage Foundation

状态：**IN PROGRESS**

目标：
把 JsonFileStorage 的 root 从 Android Context 中抽离。

内容：
- shared/JVM storage core
- Android adapter
- Desktop adapter
- `%LOCALAPPDATA%`
- Portable Mode
- atomic write parity
- backups/snapshot framework
- Desktop settings

完成状态：
- Phase 2A：**COMPLETE**
- Phase 2A1 shared JSON storage extraction：**COMPLETE**
- Phase 2A2 edge-case / fault-injection validation：**COMPLETE**
- Phase 2B：**IN PROGRESS**
- Phase 2B1 — App Data Snapshot Foundation：**COMPLETE**
- Phase 2B2 — Transactional Snapshot Restore Foundation：**COMPLETE**
- Phase 2B3：**IN PROGRESS**
- Phase 2B3A — Backup Provenance + Automatic Backup Policy：**COMPLETE**
- `:sharedCore` 已建立
- `JsonFileStorage` 已改为 root-driven，并由 Android/Desktop 共享的纯 JVM core 提供
- Android data path preserved：仍为 `filesDir/entities/...`
- Desktop storage core wiring 已通过 `DesktopAppContainer` 建立；尚未启用 Desktop business persistence
- App data snapshot 已具备 create、list 与 validation
- snapshot 位于 `<appDataRoot>/backups/`，通过 staging → validation → completed install 建立
- self-describing manifest format v1 使用 root-relative paths，并记录 size 与 SHA-256
- snapshot 排除 `backups/` 递归，listing 隔离 malformed snapshot
- source quiescence 由 caller 在整个 snapshot creation 期间保证
- Transactional restore 会先验证 selected snapshot，创建 mandatory completed pre-restore safety snapshot，并在任何 active mutation 前完成 restore staging
- active payload 在安装前移入 recovery；安装后验证 restored payload，并以明确 restore commit point 分隔 pre-commit rollback 与 post-commit cleanup
- pre-commit failure 会 rollback；rollback 不完整时保留 recovery evidence
- post-commit cleanup failure 不会 rollback 已提交的 restore，并通过 `SnapshotRestoreResult` 暴露 cleanup warning 与 retained workspace
- `backups/` 始终位于 restored active payload 之外；empty snapshot replacement、existing backup history retention 与 restart persistence 已验证
- snapshot formatVersion 保持 1；新增 optional purpose metadata：`MANUAL`、`PRE_RESTORE`、`AUTOMATIC`
- old v1 manifest 缺少 purpose 时按 `MANUAL` 解释且不静默 rewrite；此前产生的 legacy pre-restore snapshot 也不推断 provenance
- restore safety snapshot 现在明确标记为 `PRE_RESTORE`
- `AutomaticBackupPolicy` minimum-interval decision foundation 已建立；只有 valid `AUTOMATIC` snapshot 影响 interval
- clock rollback protection 已建立；exact minimum-interval boundary 为 eligible
- completed snapshot deletion、retention/pruning、automatic scheduler：**NOT IMPLEMENTED**
- `:sharedCore` tests：**PASS（64 tests，0 failures）**
- full Android JVM regression：**PASS（1141/1141）**
- raw/uncached behavior、cache isolation、file-set signature、`replaceWhere` 与 producer validation：**COVERED**
- deterministic installation rollback、restart persistence 与 read-side directory creation behavior：**COVERED**
- rollback restore failure branch：未自动覆盖；not deterministically testable without introducing a new production seam。该 test gap 不是当前 implementation blocker
- symlink implementation rejects / does not follow links；当前 Windows 环境无法创建可靠 symlink fixture。该 test coverage limitation 不是 implementation blocker
- deterministic copy-phase failure test deferred；without a new production filesystem seam 无法可靠触发。该 test coverage limitation 不是 implementation blocker
- install-stage-specific deterministic failure fixture deferred；without a new production filesystem seam / race 无法可靠触发。该 test gap 不是 implementation blocker
- rollback-restore-failure deterministic fixture deferred；without a new production filesystem seam / race 无法可靠触发。该 test gap 不是 implementation blocker

下一步：
- **Phase 2B3B — Safe Automatic Backup Retention / Pruning**
- Portable Mode、migration/root switching：**NOT STARTED**

验收：
- save → exit → restart → restore
- Android persistence regression
- Desktop storage tests

---

## Phase 3 — Entities + Package + Import/Export

内容：
- CharacterCard/CharacterInfo/Documents
- FormatCard
- WorldBook
- Package models
- schema v3..9 character
- Format v1..2
- WorldBook v1
- Character transfer
- PNG payload
- ST compatibility
- Desktop file picker/drag-drop base

验收：
Android ↔ Desktop JSON/PNG bidirectional.

---

## Phase 4 — Core Chat / Prompt / WorldBook

内容：
- PromptTemplates
- PromptAssembler
- ContextWindowManager
- WorldBookEngine
- Session/Message repositories
- Prompt Inspector
- session creation/greeting

先用 fake transport 或 test model driver 验证最终 message list，再接真实 Provider。

---

## Phase 5 — Model Runtime + Real Chat

内容：
- ModelConfig
- model discovery
- auth
- ProxyAwareClient
- StreamingChatService
- SSE
- thinking
- local HTTP
- cancellation
- debug logs
- background TaskRuntime

验收纵向切片：

```text
import card
→ create session
→ greeting
→ user message
→ WorldBook
→ final CCB prompt
→ stream response
→ persist
→ restart
→ continue
```

里程碑：
**Desktop Alpha**

---

## Phase 6 — Desktop Primary UI / Editors

- session/card browser
- three-pane chat
- settings
- character editor
- FormatCard editor
- WorldBook editor
- model editor
- responsive narrow layout
- keyboard/mouse shortcuts
- file association/Open With

---

## Phase 7 — Image Resources + NovelAI

完整：
- image resources
- card cover renderer
- chat images
- NovelAI credentials
- generation
- models/size/seed
- metadata
- regeneration
- automatic images
- Prompt Designer
- tag research/suggestion
- V5 NL
- guidance/vibe/inpaint
- history/Studio
- APNG
- mosaic editor

---

## Phase 8 — Fish Audio + Audio

- Fish secrets
- bindings
- library
- tags
- generation
- batch
- anchors
- playback
- audiobook behavior
- SaveSlot audio

QQ voice：
单独做 Windows feasibility spike，结果进入 Decision。

---

## Phase 9 — RAG

- documents
- chunking
- embedding
- vector
- retrieval
- chat-memory RAG
- indexing status
- character research reference-doc paths

---

## Phase 10 — Long-Term Memory

完整移植官方 state machines：
- Episode
- Arc
- Era
- Archive
- HEAD
- Gap/backfill
- compression
- repair
- regeneration
- coordinator

不得简化为单摘要实现。

---

## Phase 11 — SaveSlot / Session Lifecycle

- v1–7 legacy
- `.cbsave` v8
- images/audio
- transactional restore
- session duplicate
- cross-platform archive tests

---

## Phase 12 — AI Authoring / Repair

- Character AI
- Rewrite
- image-to-appearance
- card cover/avatar
- FormatCard AI
- WorldBook AI
- research
- message format repair

---

## Phase 13 — Moments

- timeline
- scheduler
- generation
- text-only
- image
- likes/private
- retry/edit/delete
- unread

Desktop runtime scheduler，保持“App 关闭不生成”的官方产品语义。

---

## Phase 14 — Community

- browse
- download/import
- upload
- Discord OAuth
- owner actions
- remote update
- runtime switch
- preview cache

---

## Phase 15 — OS Integration

- drag/drop
- file association
- Open With
- Explorer reveal
- clipboard
- tray/task center
- notifications
- crash diagnostics
- installer
- updater

---

## Phase 16 — Upstream Automation

可以更早逐步加入，但最终完成：
- upstream watcher GitHub Action
- changed-file classification
- high-risk labels
- sync issue/report
- baseline check

自动化不自动宣布兼容。

---

## Phase 17 — Beta Hardening

- data migration
- backup restore
- corrupted data behavior
- cancellation
- long-running tasks
- high DPI
- IME
- large cards/worldbooks
- huge SaveSlot
- large images
- network/proxy/VPN
- Windows 10/11

---

## Phase 18 — 1.0 Gate

要求：
- parity matrix 完整
- selected upstream baseline 全部功能有 EXACT/EQUIVALENT
- Android/Desktop package interoperability
- prompt/worldbook parity
- SaveSlot interoperability
- migration/backup
- updater
- release packaging
- license/public distribution gate resolved

里程碑：
**CCB Desktop 1.0**
