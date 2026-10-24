# Desktop 用户授权入口

Desktop 工作先读 [权限边界](docs/desktop/USER_AUTHORITY_AND_PROJECT_REVIEW_BOUNDARY.md)与[开发原则](docs/desktop/01_PROJECT_INSTRUCTIONS.md)。用户决定范围；Project 只后置阶段性审查，未经用户采纳的新限制仅为建议，不撤销、收窄或暂停已有授权。Codex 按已有授权跨 phase 连续定位、修复、必要验证并推进到 Preview，不在每个 checkpoint 等 Project 批准。当前交付授权已包含必要测试，下面 Android 测试默认规则不要求 Desktop 对同一授权反复确认。

诊断与修复不自动授权原件改写、真实用户根/凭据写入、自动合并或公开发布；这些动作按实际已有用户授权执行。保留 Prompt ownership、真实兼容声明、实际平台约束和仓库保护。只暂停真实阻断影响的动作，其他独立工作继续；必要人工验收由 Codex 直接交用户。

真实数据、备份原文、API Key/Token/密码、.cbsave/.cbbackup/.cbback 和解包内容不上 GitHub，含 Actions Secrets/日志/cache/artifact/Release；本地忽略目录使用并检查提交/打包内容。下面保留官方 Android 工程指导，不用其历史流程否定用户对 Desktop 的最新明确授权。

当前 P11 已包含合并式迁移：保留电脑已有数据，把手机数据合并进来，冲突由用户选择；新根用于准备合并结果，不能仅加载手机数据代替合并。现有执行者按 [当前任务](docs/desktop/203_P11_TO_PUBLIC_PREVIEW_CONTINUOUS_CODEX_TASK.md) 连续实现到 Preview，不新增 Project 前置审批。

# Repository Guidelines

## Project Structure & Module Organization

ChatBar is a single-module Android project under `app/`. Run Gradle from that directory, not repository root.

- `app/app/src/main/java/com/example/chatbar/`: Kotlin source. Main layers are `ui/`, `data/`, and `domain/`.
- `app/app/src/test/`: JVM unit tests.
- `app/app/src/androidTest/`: instrumented Compose/device tests.
- `device-entities/`: seed JSON for characters, models, sessions, and related local data.
- `app/gradle/libs.versions.toml`: dependency versions.

Core/business entity persistence is JSON-file based through `JsonFileStorage`. Auxiliary catalogs, dictionaries, and search indexes use SQLite.

## Build, Test, and Development Commands

Use PowerShell from `app/`:

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat test
.\gradlew.bat assembleDebug
.\ci.ps1 -SkipAssemble
.\ci.ps1
```

- `compileDebugKotlin`: fast Kotlin syntax/type check.
- `test`: JVM unit tests, no emulator required.
- `assembleDebug`: builds debug APK at `app/app/build/outputs/apk/debug/app-debug.apk`.
- `ci.ps1 -SkipAssemble`: tests plus Android test compilation.
- `ci.ps1`: full local verification. JDK 17 required.

## Post-Change Packaging and User Testing

After completing a feature or fix, query `adb devices -l` and package the release build:

- Exactly one authorized device in `device` state: run `.\redeploy.bat --no-pause` from the project root. This builds the release APK, performs a data-preserving reinstall, refreshes the launcher, and opens ChatBar.
- No authorized connected device: run `.\redeploy.bat --build-only --no-pause` and provide the APK path.
- Multiple devices, or any ambiguous/offline/unauthorized target: do not guess or install. Build only, report device states, and ask the user to select a target if installation is wanted.

Use data-preserving installation only. Stop on `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Do not uninstall, clear data, or switch signing keys unless the user explicitly confirms the data risk.

After packaging, always provide a concise feature-specific manual test flow covering setup, actions, expected results, and nearby regression checks. Do not automatically run tests, start an emulator, capture screenshots, or read device logs. Automated test execution and diagnostic device interaction require an explicit user request.

## Coding Style & Naming Conventions

Use Kotlin with standard 4-space indentation. Keep package names under `com.example.chatbar`. Name Compose screens `*Screen`, ViewModels `*ViewModel`, factories `*ViewModelFactory`, repositories `*Repository`, and tests `*Test`. Prefer existing `ui/kit` components and app theme primitives before adding new UI styles. Keep domain logic out of Composables; place chat, RAG, model, card, and image behavior in `domain/`.

## Testing Guidelines

Add JVM tests in `app/app/src/test` for domain and repository behavior when useful. Add instrumented tests in `app/app/src/androidTest` for Compose UI or device-only behavior only when their maintenance value is clear. Match existing test names such as `ContextWindowManagerTest` or `TutorialScreenTest`. Never run automated tests unless the user explicitly requests testing.

Prompt tests should assert behavior, parameter inclusion, ordering, omission, and required machine-protocol tokens. Do not assert editable natural-language wording or section titles exactly unless that literal text is itself a required external protocol.

Strictly prohibit tests from reading bundled preset or seed assets (`app/app/src/main/assets/presets/`, `device-entities/`) or asserting their content, including tool lists, natural-language text, `schemaVersion`, or structural fields. Presets are continuously edited product content, not test fixtures; changing a preset must never break a test. Behavior tests must use inline JSON fixtures. The only allowed asset-based check is a stable-contract decodability sweep that loads every entry and runs `validateForImport` without comparing any concrete value.

## Prompt Ownership

Do not modify existing user-facing prompt text, system prompt text, or prompt template text without explicit user approval. Adding a new prompt for a requested feature does not require separate confirmation. `PromptTemplates.kt` contains both prompt text and normal Kotlin code; helper functions, parameter plumbing, length handling, serialization, and other non-prompt code in that file may be changed as normal code. If an existing prompt text change seems necessary, first explain the exact problem, the proposed prompt diff, and the expected behavior change; wait for user confirmation before editing. After the user confirms the prompt text change, implement it directly as part of the current task. Non-prompt code fixes must not opportunistically rewrite prompts. Prompt text should describe the AI's task, input, output, and quality criteria; do not include irrelevant implementation details such as backend limits, API behavior, UI plumbing, storage, or execution flow unless the user explicitly wants those exposed to the AI.

All newly added hardcoded AI prompts, prompt templates, and prompt-builder text must live in `app/app/src/main/java/com/example/chatbar/domain/prompt/PromptTemplates.kt`. Feature code should call `PromptTemplates` constants/functions instead of embedding prompt text inline, so prompts remain easy for the user to review and edit in one place. New prompt text must be written in Chinese by default; use English only for required protocol tokens, JSON field names, model-facing tag formats such as NovelAI/Danbooru tags, or quoted external identifiers.

## Fallback Policy

Fallback paths are failure signals, not success paths. When fallback is triggered, first investigate and fix the upstream failure reason, and make the failure visible in status/debug output. Do not optimize fallback behavior to hide broken primary functionality unless the user explicitly asks for graceful degradation.

## Intent-Preserving Bug Fixes

Before fixing a bug, reconstruct why the current design exists from its call sites, data flow, user-visible behavior, history, and surrounding architecture. Separate the intended outcome from the faulty mechanism. Preserve the original outcome and invariants with the smallest scoped change possible; do not remove caching, synchronization, fallback, persistence, validation, or other behavior merely because it appears in the failing path. If the design intent remains uncertain and plausible fixes would change behavior differently, explain the inferred intent and tradeoffs and obtain user confirmation before implementation.

## Skill Maintenance

Before changing an area covered by a project skill under `.agents/skills`, read the relevant `SKILL.md`. After any change, update related skills in the same turn only when the change makes their existing content stale, incomplete, or misleading. Keep skills compact: replace stale facts, avoid logs, and do not add generic knowledge that Codex should already know.

Before every commit the user requests, review the pending changes for skill relevance: update any `SKILL.md` whose content the change makes stale, incomplete, or misleading, and consider creating a new compact skill when a stable new area is introduced. Do this review before staging and include skill edits in the same commit.

Skills must not repeat global rules already present in this AGENTS.md, including prompt ownership, fallback policy, shell safety, coding style, verification defaults, or device install safety. Put only feature-specific file maps, workflows, exceptions, and project facts in skills. If a skill needs to reference a globally governed area, name the feature-specific entry point without restating the global rule.

## Advanced Tutorial Maintenance

Every user-visible long-press action must be documented in the advanced tutorial in `ui/tutorial/TutorialScreen.kt`. When adding, removing, renaming, or changing a long-press gesture or its menu actions, update the advanced tutorial in the same change.

## Commit & Pull Request Guidelines

Git history uses short, scope-focused summaries, often Chinese imperatives such as `优化RAG` or `美化界面`. Keep commits concise and focused on one change. PRs should include problem, solution, verification commands, and screenshots or recordings for visible UI changes. Link related issues when available and call out data migration or seed-data changes.

## Architecture & Configuration Notes

`ChatBarApp` wires repositories and services; access app-level instances through `ChatBarApp.instance`. Navigation routes live in `NavigationKeys.kt`, with wiring in `Navigation.kt`. The app is portrait-only and uses `INTERNET` plus `ACCESS_NETWORK_STATE`. `DebugConfig.SHOW_DEBUG_UI` controls debug overlays.
