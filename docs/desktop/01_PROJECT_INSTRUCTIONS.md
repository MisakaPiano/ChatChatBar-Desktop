**D-046 用户补充 / 2026-10-25 JST：** API、密码、真实 `.cbsave`、`.cbbackup`（含用户称作 `.cbback` 的备份）及解密/解包内容可以在已授权任务内本地使用，包括对应 API 调用、导入、迁移和测试；**不得上传 GitHub**，涵盖提交历史、PR/Issue、评论、附件、Actions 日志/cache/artifact、Release 和支持包。放本地忽略目录并在提交/打包前检查；GitHub 仅放代码、脱敏结果和人工生成的测试样本。不得回显凭据，不自动向其他云服务转移这些私密内容。

Project 仅在实现后阶段性审计，不是 Codex 跨 phase、测试、常规修复、已授权 Fork 集成或 Preview 发布的前置签字人。七项 Gate 放进同一轮工程交付；复用有效证据，普通缺陷和未覆盖环境可如实登记后续。必要真人验收直接交用户，等待时继续独立工作；不伪造测试通过。用户已提供的凭据不重复索取使用许可，手机导出原件保留，覆盖测试数据前自动留可用恢复副本。此补充覆盖下方相冲突的历史流程要求，不撤销已有实现或验证事实。

**CURRENT DESKTOP DELIVERY DIRECTIVE — 2026-10-24: CODEx CONTINUOUS IMPLEMENTATION; PROJECT POST-IMPLEMENTATION REVIEW.**

The user requires one continuous engineering program that implements P11 and all Public Preview A–G functionality, tests and a releasable candidate. Codex independently implements, fixes, tests, commits and pushes consecutive phases on the existing feature branch. **Do not pause implementation after each phase to request Project permission or review**; Project reviews completed commits after delivery and does not prevent Codex working on subsequent independent changes. Retain genuine failed tests as work items, not as a reason to stop unrelated implementation. Historic LR1–LR7 studies and approval drafts are references, not mandatory future phases.

First priority is actual Android/Desktop interoperability: inspect the 11 unsupported Entity JSON records and 108 historical voice references in a private local report, diagnose actual official data rather than guessing, then implement working full-backup restore/export and SaveSlot continuity. Next complete actual Windows installer/upgrade, credentials and the seven A–G release checks in one continuous Codex assignment. Human UI/device acceptance instructions go directly from Codex to the user. No redundant report packages or artificial phase splits.

The user's supplied backup password/API credentials may be used in the authorized local functionality and connectivity tests; use private masked/ignored/OS-protected channels, never Git or public logs. Read original archives without modifying them; use isolated disposable test copies for replay and verify existing user data preservation. This is not permission to erase an unmarked data root or bypass Prompt ownership. No separate Project approval is needed for routine non-destructive builds, tests or code fixes.

Project review and formal A–G signatures happen **after implementation**, not before Codex is allowed to code each Gate. A real loss-of-data or security defect remains a blocker to public release until fixed, but not a blocker to continuing independent engineering. No unverified claims of compatibility, no release without required author permission/actual acceptance, and no official-upstream writes; honor actual GitHub branch protections during authorized Fork integration. Project signoff is not a prerequisite for already authorized integration or release. Use existing Kotlin/Compose/JDK17 architecture and the simplest safe solution; broker/VM/security research is not the default work program.

# CCB Desktop Project Instructions

Goal: maintain a Windows-native downstream port of ChatChatBar that can continuously follow upstream.

## Invariants
- Upstream current source is the highest technical authority.
- Do not treat Tavern/SillyTavern/CCV2/CCV3 as the default CCB standard.
- Always distinguish transfer Package, persisted Entity, and final model Prompt/request semantics.
- FREEFORM and STRUCTURED are both valid.
- Do not invent image/voice/resource references.
- Desktop UI may differ; core CCB behavior may not.
- Every Android user-facing feature must be tracked as EXACT, EQUIVALENT, PENDING or BLOCKED.
- Do not silently delete Android-only features; design a Desktop equivalent first.

## Git
- `master`: upstream mirror only.
- `desktop`: integration branch.
- `feature/*`: feature work.
- `sync/*`: upstream absorption.
- Never write to the upstream repository.

## Technology
Windows-first: Kotlin/JVM + Compose Desktop + JDK 17.
Do not use Electron/WebView/localStorage/IndexedDB as the core runtime.
Do not force the whole Android project into KMP on day one. Prefer conservative JVM shared extraction.

## Upstream sync
A merge without conflicts is not proof of compatibility. High-risk semantic changes require parity tests and review before the baseline is advanced.

## Prompt ownership
Do not rewrite upstream model prompts as part of a platform port unless the task explicitly requires prompt changes and the user approves them.

## Data safety
Keep atomic JSON storage semantics, add Desktop data-directory visibility, migration snapshots, backups and Portable Mode. Never fix migration bugs by silently deleting user data.

## Secrets
Never commit API keys, NovelAI/Fish tokens, GitHub PATs or OAuth secrets. Use OS secret storage or ignored local config.

## ChatGPT Project vs Codex
Project: architecture, upstream research, task specs, semantic review, PR/commit review.
Codex: local code edits, Gradle, tests, refactors, Windows implementation, commits/PRs.
