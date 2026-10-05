# CCB Desktop — Phase 6 Completion / Phase 7 Entry Handoff

更新时间：2026-10-05

> 本文件是 Phase 6 关闭后的 CURRENT 引继入口。它不替代 live upstream source、live `desktop` 或其它 CURRENT docs。

## 1. 当前控制点

Phase 6：**CLOSED / INTEGRATED / ACCEPTED**

Phase-6 accepted production HEAD：

`86be0b0ec21aab7a8f15553c0b696b253738917f`

Phase-6 closure/docs integration control point：

`8e10af8042783a82b3e9d9268ac2fb6c3b3b3449`

closure integration：

- previous desktop: `5850fe28d233fb1b64a71b35e1e5f5d8d44db21d`
- FF-only
- no extra merge commit
- production finalization diff: none
- feature branches retained

本文件和 observation refresh 本身可能让 live `desktop` 在 closure SHA 之后再前进 docs-only commits；任何任务开始前必须重新核验 live branch。

Formal validated upstream baseline：

**ChatBar 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**

Latest verified observation：

**ChatBar 1.4.4 @ `550409689df8c51f459fb50b4e04c8ac2fa4bf35`**

Baseline → observed:

- 18 commits ahead
- 75 changed files
- HIGH drift
- NO SYNC
- NOT VALIDATED

观察值不提升 compatibility baseline。

## 2. Phase 6 final evidence

Final accepted package gate:

- desktopApp: 99 suites / 909 tests PASS
- failures/errors/skipped: 0
- Desktop compile: PASS
- git diff --check: PASS
- isolated createDistributable: PASS
- packaged launch/main window/normal shutdown: PASS
- automatic capture: BLOCKED_ENVIRONMENT
- final manual acceptance: PASS

P6-FINAL-R1 Full-screen Composer：

- Project actual-diff review PASS
- user manual acceptance PASS

## 3. 已关闭范围

S4 Primary Chat、S5 Character Editor、S6 FormatCard Editor、S7 WorldBook Editor、S8 Management/Ingress/Presets/ModelTemplate、S9 Desktop UX/Settings 均 accepted。

Phase 6 没有 UNKNOWN。

Automatic Backup runtime/settings 是 Desktop-only data-safety enhancement，不冒充 Android parity。

Virtual Conversation Scrollbar 明确 deferred，待 realistic long/cross-device histories 后再评估，不是 Phase-6 defect。

## 4. 后续 owner

- P7 — Image Resources + NovelAI / chatBackground / chat images / image workspace
- P8 — Fish Audio
- P9 — RAG
- P10 — Long-Term Memory
- P11 — SaveSlot / Session Lifecycle
- P12 — AI Authoring / Message Format Repair
- P13 — Moments
- P14 — Community
- P15 — OS Integration + Diagnostics / AI request-log user surface / installer-updater
- P16 — Upstream Automation
- P17 — Tutorial/onboarding / Backup-Recovery Center / beta hardening
- P18 — 1.0 Gate

## 5. Cross-device Full Data Portability / BYOC Sync

D-035 仅冻结方向，不冻结 schema 或最终 phase：

- Android ↔ Desktop ↔ Desktop/Android
- shared/upstream protocol preferred
- first step = complete archive export / validate / transactional import
- never use cloud folder as live shared data root
- user-owned transport first
- Desktop local/sync folder + Android SAF first
- third-party cloud client may sync committed immutable archives
- no developer-operated server required
- ordinary archive excludes API keys/tokens/secrets
- automatic multi-master merge is a separate later problem
- revisit architecture around/after P11, especially after SaveSlot matures

## 6. P15 diagnostics direction

Future Diagnostics & Support Bundle should prefer：

- structured local logs
- strict secret redaction
- request metadata by default, not Prompt/chat body
- rotating size limits
- launcher/bootstrap diagnostics
- crash records
- optional enhanced diagnostics
- explicit user opt-in before including chat/Prompt content

## 7. P16 upstream automation direction

Mature maintenance model：

**continuous observation + controlled sync windows**

Future watcher should：

- detect upstream commits/tags/releases
- classify changed files by Feature Map / Skill
- label high-risk Package/Entity/Prompt/WorldBook/Provider/RAG/Memory/SaveSlot/storage/secret paths
- generate drift/sync report
- guard validated baseline

It must never auto-merge or auto-declare compatibility。

## 8. Phase-7 boundary

Do **not** start P7 production implementation immediately。

Required pre-P7 sequence：

1. verify live `desktop` / `master` / upstream state；
2. refresh ChatGPT Project Sources to current CURRENT docs + this handoff；
3. perform post-Phase-6 safe local cleanup；
4. rebuild/test/package after cleanup to prove reproducibility；
5. perform fresh upstream 1.4.1→1.4.4 image/NovelAI impact review；
6. decide whether a sync window is required before P7；
7. freeze P7 contract and implementation slices。

Only after all above are closed may P7 implementation begin。

## 9. Local cleanup safety

Repo was reported around 8 GB。

First do read-only size inventory of：

- `.git`
- `.gradle`
- `.kotlin`
- module `build/`
- repeated isolated distributions
- package/test outputs
- Codex worktrees

Delete only confirmed reproducible ignored/untracked artifacts/caches and obsolete worktrees through Git worktree commands。

Never delete：

- source
- CURRENT docs
- `.git` history
- user data
- secrets
- unknown files merely because they are large

After cleanup run rebuild/test/package verification。

## 10. New-conversation read order

1. `00_PROJECT_SOURCE_MAP.md`
2. `10_UPSTREAM_BASELINE.json`
3. `21_CURRENT_STATE.md`
4. relevant upstream Skill
5. `13_FEATURE_PARITY.md`
6. `14_UPSTREAM_COMPAT.md`
7. this file
8. `15_SYNC_PLAYBOOK.md`
9. `16_TEST_MATRIX.md`
10. `17_ROADMAP.md`
11. `18_DECISIONS.md`
12. `27_EDITOR_REFERENCE_ADOPTION.md` when entering P7 image/editor UX

Do not indiscriminately read the whole repository。

## 11. First message for the next Project conversation

> 继续 CCB Desktop 项目。Phase 6 已 CLOSED / INTEGRATED / ACCEPTED。请先按 `00_PROJECT_SOURCE_MAP.md` 的 CURRENT read order 恢复上下文，并核验 live GitHub。formal validated baseline 仍是 ChatBar 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`；latest verified observation 是 1.4.4 @ `550409689df8c51f459fb50b4e04c8ac2fa4bf35`，18 commits / 75 changed files，NO SYNC / NOT VALIDATED。不要立即开始 P7。先完成 post-Phase-6 本地安全清理与 cleanup 后 rebuild/test/package gate；随后做 P7 前的 upstream image/NovelAI impact review 和 contract freeze。
