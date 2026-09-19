# CCB Desktop Development Roadmap

目标：完成 CCB Desktop，同时建立可持续跟随 upstream 的开发机制。

---

## Phase 0 — Bootstrap / Audit

状态：**本 Bootstrap Pack 已完成第一轮**

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

当前状态：
- Fork `MisakaPiano/ChatChatBar-Desktop` 已建立
- `desktop` branch 已建立
- 新 ChatGPT Project 已完成首次接管
- upstream baseline / schema / architecture / feature-skill drift 首次复核已通过

下一步：
- Codex 按 `03_CODEX_START.md` 做本地只读复核
- Project 审查 Codex 报告
- 通过后才进入 Phase 1 `:desktopApp` bootstrap

---

## Phase 1 — Desktop Bootstrap

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

不做：
- 业务功能
- shared 大重构

验收：
- Android compile
- Desktop run
- no browser/webview

---

## Phase 2 — Shared Storage Foundation

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
