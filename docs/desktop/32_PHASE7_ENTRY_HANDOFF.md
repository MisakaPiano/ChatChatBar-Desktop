# CCB Desktop — Phase 7 Entry / Project Handoff

更新时间：2026-10-05

> 本文件是 Phase 6 完成后进入 Phase 7 的 CURRENT handoff。目标是让 Project 与 Codex 在新对话中直接继续大步开发，不再围绕 Phase-6 收尾或低收益 upstream drift 审计反复切小任务。

## 0. Immediate instruction

Phase 6 已经 **CLOSED / INTEGRATED / ACCEPTED**。

不要重新打开 Phase 6。
不要再做 Phase-6 cleanup。
不要再为 1.4.4 parked sync 做 counter-review / baseline promotion / docs-only micro-task。

下一项生产开发直接进入：

**Phase 7 — Image Resources + NovelAI**

工作方式：

- 允许大规模任务。
- 优先一个 Codex 会话完成整个 Phase 7，内部用 reviewable commits/checkpoints 保证可恢复。
- 不因为每个小 slice 完成就停下来等待 Project。
- 只有真正的 authority / schema / Prompt / data-loss / security blocker 才暂停。
- Project 在 Phase 7 大里程碑或 phase close 时审真实 GitHub diff。
- 不做低收益形式化反审。
- 不做重复的全套验证；开发中 targeted tests，Phase close 跑完整 gate。

## 1. Current Git control points

Fork:
`MisakaPiano/ChatChatBar-Desktop`

Local:
`H:\ChatChatBar-Desktop`

Current accepted Desktop:
`desktop @ 6737f0ed382170a7891d0bd5f7e4708ca2840009`

Phase-6 accepted production HEAD:
`86be0b0ec21aab7a8f15553c0b696b253738917f`

Phase-6 closure/docs control point:
`8e10af8042783a82b3e9d9268ac2fb6c3b3b3449`

Current mirror master:
`master @ 550409689df8c51f459fb50b4e04c8ac2fa4bf35`
= upstream CCB 1.4.4 mirror.

Formal validated compatibility baseline remains:
**CCB 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**

Parked, NOT accepted into Desktop:
`sync/1.4.4 @ 9b6378dbb595dd2f3ff5143a7a8e46653c99e721`

Policy for parked sync:

- keep branch;
- do not delete;
- do not merge to desktop now;
- do not promote formal baseline now;
- reuse later when a sync window is genuinely valuable;
- P7 does not wait on it.

## 2. Phase-6 post-close housekeeping

Completed:

- Project CURRENT docs/handoff refresh.
- local repository cleanup.
- repository reduced from ~8.829 GiB to ~1.048 GiB after rebuild/package.
- old generated distributions/toolchains/worktree cleaned conservatively.
- source/docs/.git/user data retained.
- system JDK verified:
  `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`
- cleanup reproducibility:
  - sharedCore 527/527 PASS
  - desktopApp 909/909 PASS
  - Android compile PASS
  - Desktop compile PASS
  - createDistributable PASS
  - packaged launch smoke PASS.

Therefore cleanup is CLOSED and must not be revisited without a concrete issue.

## 3. Working authority for Phase 7

For Phase-7 implementation:

1. formal baseline source: CCB 1.4.1 @ `5e76a9c...`
2. current Desktop `desktop`
3. CURRENT Desktop docs
4. Project Instructions
5. Editor Reference Pack only as UX/workflow/regression REF

The parked 1.4.4 sync is future-sync material, not Phase-7 acceptance authority.

Do not silently mix 1.4.4-only behavior into a 1.4.1 parity claim.

## 4. Phase 7 objective

Deliver the complete Desktop equivalent of the formal-baseline Image Resources + NovelAI domain.

The Phase must close with every P7-owned parity item either:

- EXACT
- EQUIVALENT
- BLOCKED with a concrete external/platform blocker

No UNKNOWN.
No vague “later” for functionality that belongs to P7.

## 5. Phase 7 owned functionality

P7 owns at minimum:

### Image resources / chat presentation

- render CharacterCard `chatBackground` in Primary Chat
- session chat-background behavior
- global/session background opacity
- scaling / DPI / fallback / readability
- chat image attachments
- image picker for chat
- pending-image UX
- persisted chat images
- image preview/navigation
- image save/copy/reveal equivalents where owned by image domain

### Character and Package image UX

Existing Entity/package fields remain authoritative:

- `CharacterCard.avatar`
- `CharacterCard.chatBackground`
- `CharacterInfo.appearanceImage`

Do not invent a new Entity/package `cover` field.

CCB PNG cover behavior:

- background source from official semantics
- crop center X/Y
- crop zoom
- gradient
- logo scale
- title scale
- proper Desktop adjustment UI

Approved Desktop enhancement:

- export-only replacement cover source
- must not mutate `CharacterCard.chatBackground`
- must not change Package schema

### Desktop Image Workspace

Use the mature Editor Reference Pack as UX/regression reference, not schema authority.

Expected Desktop-equivalent editing foundation:

- native visual crop
- zoom slider
- mouse wheel zoom
- drag/pan
- recrop/reposition
- reset
- apply/cancel
- usable for cover and other owned image fields
- no flashing/recomposition loop
- predictable high-DPI behavior

Reuse one image-workspace foundation rather than bespoke crop implementations per screen.

### NovelAI

Formal-baseline complete capability:

- secure NovelAI credential
- HTTP runtime
- model selection
- image dimensions/aspect policy
- seed
- batch/sample behavior
- generation progress/error/cancellation
- Prompt Designer
- Danbooru catalog
- tag research/suggestion
- prompt editing/translation behavior
- V5 natural-language behavior
- guidance / vibe / inpainting where present in formal baseline
- Studio
- Studio persistence/history
- regeneration from generated-image metadata
- automatic chat images
- image metadata persistence/import as defined by baseline
- APNG disguise/restore
- mosaic/image editing
- image save/share Desktop equivalent
- related global settings
- related Session settings

### Automatic images

Preserve official runtime semantics:

- automatic image generation is opt-in
- integrates with persisted completed reply
- no duplicate eligibility implementation
- no Prompt fork
- cancellation/failure must not create false success
- durable image ownership and message linkage

## 6. Architecture rules

- Kotlin/JVM + Compose Desktop + JDK17.
- no browser/WebView.
- share/move JVM-neutral official logic whenever practical.
- do not maintain Android/Desktop duplicate domain policies.
- Android platform pieces receive Desktop adapters.
- filesystem paths replace Content URI/SAF mechanics where appropriate.
- native Desktop picker is allowed.
- global OS drag/drop/Open With/file association remain P15 unless a purely in-app image workspace drop target is explicitly needed and does not create global ingress ownership.

## 7. Prompt protection

P7 contains NovelAI AI-design Prompt behavior.

Rules:

- official model Prompt text is protected.
- do not rewrite Prompt literals as part of Desktop port.
- if logic is moved/shared, move text exactly.
- if a Prompt literal appears to require modification, STOP only for that concrete Prompt and report original/diff/reason.
- do not block unrelated P7 work while waiting.

Prompt truth remains final logical/request messages.

## 8. Data / persistence / secrets

- preserve JsonFileStorage atomic semantics.
- image metadata and owned files must survive restart.
- never auto-clear user data.
- image replacement must not orphan/delete unrelated owned resources.
- cleanup only current-owned obsolete resources.
- NovelAI token must use Desktop SecretStore / OS protection.
- never store token as ordinary plaintext JSON.
- never include credential in export/package/log/test fixture.

## 9. Windows equivalents

Android mechanisms map to Desktop equivalents:

- SAF -> native picker
- Share Sheet -> Save / Copy / Reveal in Explorer as appropriate
- Content URI -> Path/File
- Android background/FGS -> existing Desktop TaskRuntime when needed
- Android image viewer gestures -> Desktop mouse/keyboard equivalents

Do not delete official capability merely because Android UI mechanics differ.

## 10. Validation strategy

Avoid micro-task churn.

During implementation:

- use focused tests around changed subsystem
- compile affected modules
- commit durable checkpoints

At Phase-7 close, run one consolidated gate:

- sharedCore full tests
- desktopApp full tests
- Android JVM tests for shared moves
- Desktop compile
- Android compile
- relevant image/NovelAI focused tests
- git diff --check
- createDistributable
- packaged launch smoke

Computer-use:

- optional launch/window/activation/shutdown smoke
- screenshot/capture at most one best-effort attempt
- capture timeout = BLOCKED_ENVIRONMENT, not product failure
- real visual/image-editor acceptance remains user manual acceptance

## 11. User-acceptance focus

Manual acceptance should be one consolidated Phase-7 session, not one per tiny slice.

Cover at least:

- chat background
- image attachment/send/view
- Character image edit
- CCB PNG cover crop/zoom
- Desktop Image Workspace pan/zoom/crop
- NovelAI credential/model/settings
- one successful generation
- Studio
- history/regeneration
- automatic chat image
- APNG/mosaic/save/reveal
- restart persistence
- error/cancel path

If paid NovelAI generation cannot be run, report which runtime parts are automated-only and leave only the external paid call as manual evidence.

## 12. Large-task execution policy

Codex should create:

`feature/phase7-image-novelai`

from current `desktop`.

Inside one long Codex session it may create multiple commits such as:

- shared image runtime/policy
- Desktop image storage/workspace
- chat background/images
- NovelAI runtime/settings
- Studio/history/regeneration
- automatic images
- image tools/APNG/mosaic
- final P7 integration/tests

These are internal durable checkpoints, not separate user round-trips.

Do not stop after each commit.

Only stop early for:

- real schema/Prompt authority ambiguity
- potential data loss
- SecretStore/security ambiguity
- inability to preserve shared authority
- unrecoverable build/test blocker
- quota/capacity interruption

If interrupted only by quota/capacity:

- leave a clean durable commit
- push branch
- report exact resume point
- do not invent a new planning task.

## 13. Upstream policy during P7

Do not fetch/chase upstream merely because new commits appear.

Current parked `sync/1.4.4` is sufficient evidence that later sync can be resumed.

Only interrupt P7 for upstream if:

- security/data-loss issue
- external API break
- current P7 implementation is impossible/incorrect without newer upstream contract

Otherwise continue P7 against formal baseline and batch-sync later.

## 14. Phase-7 close

At close:

- Project reviews actual GitHub diff once at meaningful phase scale.
- perform bounded repair only for concrete findings.
- one consolidated manual acceptance.
- reconcile CURRENT docs once.
- merge reviewed feature to `desktop`.

Avoid chains of:
audit -> micro-task -> counter-audit -> micro-repair -> docs-only task
unless a real defect justifies it.

## 15. Project new-conversation starter

> 继续 CCB Desktop。Phase 6 已 CLOSED / INTEGRATED / ACCEPTED，post-close cleanup 与 reproducibility gate 也已完成，不得重开。请读取 `32_PHASE7_ENTRY_HANDOFF.md`，再按 `00_PROJECT_SOURCE_MAP.md` 的 authority/read order恢复上下文。当前 accepted Desktop 是 `desktop @ 6737f0ed382170a7891d0bd5f7e4708ca2840009`；formal validated baseline 仍为 CCB 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。master 已 mirror 1.4.4，`sync/1.4.4 @ 9b6378db...` 只是 parked future sync，禁止为了它继续做低收益审计或 baseline promotion。现在直接进入 Phase 7。工作策略是大任务、大步推进、内部 durable commits，不要把完整 Phase 7 拆成大量需要用户往返的小任务。Project 只在真正高风险阻塞或大里程碑时介入。

## 16. Codex new-conversation starter

> 继续 CCB Desktop，读取 `docs/desktop/32_PHASE7_ENTRY_HANDOFF.md` 与 `docs/desktop/33_PHASE7_CODEX_MEGA_TASK.md`。Phase 6 已关闭，不得重开。不要处理 parked sync/1.4.4。直接从 `desktop @ 6737f0ed382170a7891d0bd5f7e4708ca2840009` 创建 `feature/phase7-image-novelai`，按 mega-task 连续完成整个 Phase 7。内部用多 commit 保证可恢复，但不要每个小阶段停下来等待用户。只有真实 authority/schema/Prompt/data-loss/security blocker 或额度中断才停止。
