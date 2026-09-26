# CCB Desktop — Phase 4 Contract Audit
## Core Chat / Prompt / WorldBook

> 状态：**AUDIT COMPLETE / IMPLEMENTATION IN PROGRESS**  
> Audit date：2026-09-25  
> Desktop audit HEAD：`7d9f049e99a9b8e0c266f165704d67974433501e`  
> Formal upstream baseline：ChatChatBar `1.4.1` @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`  
> Observed upstream：`354f15166d8bc0462cb87d62a0ba4613794560a3` — **HIGH / NO SYNC**  
> P4-S1 implementation：`f850ece3df7f36391b1fa4e81c286110f9610844`  
> P4-S1 Project review：**PASS WITH NON-BLOCKING NOTES**  
> P4-S2 / 4A2 remainder：`bfcd37e2f1f316ae60f733c0146846f66a4f76b4` — **PROJECT REVIEW PASS / INTEGRATED**  
> P4-S3 / 4B1：`d468f82529ec6e9fa24363e07d1e7fafe6c9ecf0` — **PROJECT REVIEW PASS / INTEGRATED**  
> P4-S4 / 4B2：`8867424df3d53bd291b6b361e51aa5059257284e` — **PROJECT REVIEW PASS / INTEGRATED**  
> P4-S5 / 4P：`e927277dabb206aa34b2374e3596c67a31f0f7db` — **PROJECT REVIEW PASS / INTEGRATED**  
> P4-S6 / 4C：`8bd876bb28c19b39f96a3abb109698132912d9a2` — **PROJECT REVIEW PASS / INTEGRATED**  
> Current control point：4A1/4A2/4B/4P/4C **COMPLETE / PROJECT REVIEW PASS**；next = **4D1**  
> Baseline policy：Phase 4 继续以 formal baseline 为固定行为目标；observed HIGH drift 仅进入后续 selective/batch sync backlog，不因进入 Phase 4 自动触发同步。

---

# 1. Audit conclusion

Phase 4 已进入 implementation，但 audit 结论不变：不能把它理解为“把 ChatViewModel 搬到 Desktop”。

正确边界是：

1. **Chat persisted contract / repository authority**
2. **Context-window semantics**
3. **WorldBook request-time runtime semantics**
4. **main-chat Prompt ownership**
5. **final logical message assembly**
6. **Desktop fake/test runtime + Prompt Inspector**

真实 Provider / SSE / auth / thinking / cleartext HTTP role adaptation / network request transport 继续属于 **Phase 5**。

因此 Phase 4 的最终验收目标是：

```text
persisted Character + Session + Message
→ resolve context / WorldBook / FormatCard / Prompt inputs
→ build one authoritative logical CCB message plan
→ fake/test driver receives exactly that plan
→ restart and rebuild the same semantics from persisted data
```

Phase 4 不得伪造“真实 Provider 已完成”。

---

# 2. Authority / source boundary

本 audit 读取并对照：

- `00_PROJECT_SOURCE_MAP.md`
- `10_UPSTREAM_BASELINE.json`
- `21_CURRENT_STATE.md`
- `13_FEATURE_PARITY.md`
- `14_UPSTREAM_COMPAT.md`
- `18_DECISIONS.md`
- `17_ROADMAP.md`
- `23_CODEX_BUDGET.md`
- `24_CODEX_USAGE_EMPIRICAL_BASELINE.md`
- `27_EDITOR_REFERENCE_ADOPTION.md`
- upstream `AGENTS.md`
- `.agents/skills/chatbar-feature-map/SKILL.md`
- `.agents/skills/chatbar-prompt-pipeline/SKILL.md`
- `.agents/skills/chatbar-model-request-runtime/SKILL.md`
- `.agents/skills/chatbar-long-term-memory/SKILL.md`
- `.agents/skills/chatbar-save-slot/SKILL.md`
- current/baseline chat, prompt and WorldBook source/tests

Reference Pack 不参与 Phase 4 semantic authority。

Prompt truth 继续是最终 logical/serialized model request，不是 Preview。

---

# 3. Current source state

本 audit 在 `7d9f049e99a9b8e0c266f165704d67974433501e` 上完成时，以下 Phase 4 核心文件与 pinned upstream baseline 保持同一 source authority / behavior：

- `ChatSession.kt`
- `ChatMessage.kt`
- `ChatRepository.kt`
- `CharacterSessionService.kt`
- `ContextWindowManager.kt`
- `PromptAssembler.kt`
- `WorldBookEngine.kt`
- `ChatRequestMemoryPolicy.kt`
- `ChatHistoryPromptPolicy.kt`

`PromptTemplates.kt` 的 Desktop 与 upstream baseline 存在 Phase 3P 已审查差异：Character NAI default-negative Prompt 的物理 authority 已移入 sharedCore，Android `PromptTemplates` facade 委托该 authority。该变化不改变 main-chat Prompt text/runtime。

P4-S1（`f850ece3df7f36391b1fa4e81c286110f9610844`）之后，当前 authority 已更新为：

- `ChatSession` / `ChatMessage` 及直接 serialized dependencies：authoritative sharedCore；
- `ChatRepository`、message ordering/repair、timeline/source-turn、session display-title 与 directly required pure policies：authoritative sharedCore；
- Android-local duplicate authorities：removed；
- `CharacterSessionService`：authoritative sharedCore；Android 通过窄 warning callback 保持 stale-format `Log.w` 行为，Desktop 使用同一 shared service/repository authority；
- `ContextWindowManager` / `PlaceholderRenderer` / `WorldBookEngine` / `WorldBookScanContext` pure runtime：4B1 authoritative sharedCore；`WorldBookRequestPlanner` request-level WorldBook source/planner orchestration：4B2 authoritative sharedCore；Android/Desktop 共用同一 authority；
- main-chat Prompt authority：4P authoritative shared `MainChatPromptAuthority`，Android `PromptTemplates` retains compatibility facade；
- `PromptAssembler` / final logical request assembler / pure `ChatApiMessage`：4C authoritative sharedCore；Android delegates and Desktop uses the same authority；
- real Provider / SSE / HTTP transport：继续属于 Phase 5。

所以 Phase 4 的工程仍然不是“Desktop 第二套实现”，而是继续把 JVM-neutral upstream authority 下沉至 sharedCore，并让 Android/Desktop 共同消费同一 authority。

---

# 4. Chat persisted contract

## 4.1 ChatSession

`ChatSession` 是 persisted Entity，不是 transport Package。

关键字段包括：

- `id`
- `characterCardId`
- `title`
- `displayTitleOverride`
- `modelId`
- `imageModelId`
- `novelAiImageModel`
- `formatCardId`
- reply length/language/style
- supplementary/player overrides
- chat background/image preferences
- audiobook/voice settings
- long-term-memory state
- source-turn state/tombstones
- context-related persisted fields
- `extraWorldBookIds`
- pin/list-preview state
- `timedWorldInfo`
- timestamps

Important current-runtime trap：

`ChatSession.contextWindowSize` 虽然仍在 persisted Entity 中，但 current Android request/runtime 的 effective context size 实际读取：

`AppSettings.defaultContextWindowSize`

而不是 session field。

Desktop 不得因为字段名看起来合适，就擅自把 `session.contextWindowSize` 恢复成 request authority。

## 4.2 ChatMessage

关键 serialized behavior：

- USER / ASSISTANT / SYSTEM
- raw `content`
- images
- generated image metadata
- alternatives + stable version IDs
- selected alternative identity
- reasoning
- generated-from relation
- format-repair notice
- stable `orderKey`
- `sourceTurnId` / `sourceTurnOrder`
- legacy `timelineTurn`

`displayContent` 是 selected alternative view，不是第二份 persisted body。

### Compose annotation boundary

当前 Android entity 带 `androidx.compose.runtime.Stable`。

该 annotation 是 UI/compiler concern，不应成为 `sharedCore` 引入 Compose runtime 的理由。

Phase 4 extraction 必须保持：

- serialized/entity semantics unchanged；
- shared entity UI-neutral；
- Android Compose stability/performance concern 如确需声明，应在 Android/Compose tooling boundary 解决。

不得为了保留一个 UI annotation 把 Compose dependency 引入 core domain。

## 4.3 Auxiliary persisted types

与 chat repository 同一 persistence/runtime contract 的纯 JVM types：

- `ReplyLength.kt`
- `TimedEffectState.kt`
- `ChatDraft.kt`
- `ChatMessageIndex.kt`
- `ChatMessageOrderBackup.kt`
- `ChatScrollPosition.kt`

`NovelAiImageModelResolution` 也是 `ChatSession` 的纯 JVM dependency，应随 entity extraction 进入 shared authority，而不是复制 resolver。

---

# 5. ChatRepository contract

`ChatRepository` 当前是 JVM-neutral repository over authoritative `JsonFileStorage`。

Entity/storage keys 包括：

- `chat_sessions`
- `chat_messages`
- `chat_message_indexes`
- `chat_message_order_backups`
- `chat_drafts`
- `chat_scroll_positions`

它不仅是简单 CRUD，还拥有：

- session list/cache
- pin/display-title handling
- message persistence/indexing
- pagination/window reads
- source-turn migration
- message-order repair + backup + undo
- streaming replacement/persistence helpers
- WorldBook scan snapshot
- context candidate reads
- draft/scroll persistence
- speaker-tag rewrite
- character rename → session title propagation

因此 Desktop 不应新写第二个 ChatRepository。

Phase 4 应移动 authoritative repository + pure supporting policies 到 sharedCore。

一个重要 dependency boundary：

`ChatRepository` 只需要 speaker-tag rename 的窄 pure behavior；不应该为了这一函数把整个 `RoleplayContentSegments.kt` 的 UI/display segmentation framework拖入 sharedCore。

应抽取最小 speaker-marker rename authority，Android roleplay segmentation 继续留在其后续 UI/domain位置。

---

# 6. Session creation / greeting contract

`CharacterSessionService` 当前行为必须保持：

1. Character 不存在 → explicit failure。
2. 新 session title = current Character card name。
3. Character `defaultFormatCardId` 只有在 referenced FormatCard 当前真实存在时才写入新 session。
4. stale Character default Format binding 不应写入 session。
5. existing session 的 format choice 不因 Character default 后续变化而自动改写。
6. 新 session 创建后持久化 greeting 为首个 **ASSISTANT** message。
7. current behavior 即使 greeting 为空，也仍创建 opening ASSISTANT message。

`CharacterSessionService` 当前唯一直接 Android coupling 是 logging；共享实现应通过窄 logging seam 或等价 JVM-neutral方式去除 `android.util.Log` dependency，不得改变上述 observable behavior。

---

# 7. Context-window contract

`ContextWindowManager` 是纯 JVM domain logic，应共享，而不是 Desktop 重写。

当前 authoritative grouping：

- 已有 `sourceTurnId`：同一 stable source turn 为一组；
- SYSTEM message 可属于其后相邻 source turn，不应无故拆 T；
- 尚未迁移的 legacy message：保留 adjacent USER/ASSISTANT fallback；
- `ChatAdjacentExchangeGroupPolicy` 是独立 legacy adjacency helper，不替代 source-turn direct-context grouping。

Direct-context window 不是简单 `takeLast(N messages)`。

`recentMessages()` 的当前语义：

- counted historical groups 按 window limit 截取；
- previous group 作为 hot previous-turn 保留；
- unanswered current USER group 保留。

`getPromptMessageGroups()` 还会：

- 从 context 中排除 latest current USER；
- 把完整上一 source turn 分离为 `previousTurnMessages` hot zone；
- 其余进入 earlier history。

这些行为必须共享并作为 parity fixtures。

---

# 8. WorldBook runtime contract

## 8.1 Engine

`WorldBookEngine` 是 JVM-neutral，核心行为包括：

- book-level / entry-level case sensitivity
- whole-word inheritance/override
- normal key matching
- secondary selective logic
- regex
- constant entries
- enabled state
- character filters
- probability
- group competition / weights
- scan depth
- recursion
- `excludeRecursion`
- `preventRecursion`
- `delayUntilRecursion`
- token budget
- `ignoreBudget`
- BEFORE_CHAR / AFTER_CHAR / OUTLET
- outlet expansion
- sticky / cooldown / delay timed behavior
- placeholder rendering

引擎本身可以进入 sharedCore。

## 8.2 Engine alone is NOT enough

当前 request-time WorldBook semantics 还有一部分实际上藏在 Android `ChatViewModel.buildWorldBookPrompt()` 中。

如果只移动 `WorldBookEngine`，Desktop 仍会被迫重新实现这些语义，因此不能宣称 WorldBook runtime EXACT。

必须同时抽取 JVM-neutral request planner/service，覆盖至少：

### source resolution

顺序来源：

1. embedded `characterBook`
2. `boundWorldBookId`
3. `worldBookIds`
4. `session.extraWorldBookIds`

duplicate ID semantics：

- linked/current repository book wins over embedded stale copy；
- 但首次出现位置决定 book order。

### scan-depth

effective scan depth = all involved books / enabled entries 的最大 relevant depth。

### scan snapshot

必须使用 repository 的 WorldBook scan snapshot contract，而不是直接把 UI 当前 message list 当作扫描源。

### transient current input

未持久化的 current user input 也可进入本轮 WorldBook scan。

### character tokens

包括：

- card name
- STRUCTURED character names
- FREEFORM `【角色名称】` extraction

### timed state key

current namespaced key：

`<bookId>::<entryId>`

并兼容 legacy plain `entryId` fallback。

### persisted timed state

WorldBook evaluation 得到的新 timed state 若变化，必须成为当前 session 的 persisted runtime state。

因此 Phase 4 应新增一个 shared request-level WorldBook planning authority，而不是在 Desktop UI 复制 Android private function。

---

# 9. Placeholder contract

`PlaceholderRenderer` 是纯 JVM semantic dependency，应进入 sharedCore。

Normalize aliases：

- `{{char}}`
- `{{user}}`
- `{char}`
- `{user}`
- `<BOT>`
- `<USER>`

统一到 `$botname` / `$username` model。

Request-time render：

- bot name always resolves；
- player name only when available；
- player name absent 时 `$username` 保留。

Persisted source text不应因为 request render 被就地改写。

---

# 10. FormatCard request runtime

Phase 3 已共享 FormatCard Entity/Package/validator，但 request behavior 仍在 Android `FormatCardUserToolPolicy`。

Phase 4 需要共享其 runtime semantics：

### RANDOM_NUMBER

- request-only；
- append 到 current USER content；
- adjacent RANDOM_NUMBER tools 按顺序合并；
- inclusive bounds；
- fixed RNG seam must remain testable；
- 不能持久化回用户原文。

### STRONG_PROMPT_SUFFIX

- 所有 configured strong suffix 按 card order 合并；
- exact multiline text preserved；
- 作为一个 logical SYSTEM message；
- position 在 current user + post-history/END requirements 之后；
- position 在 final CCB assistant/user tail 之前。

validation 继续使用已经 shared 的 `FormatCardUserToolValidator`。

---

# 11. Main-chat Prompt ownership

## 11.1 Physical ownership problem

Phase 4 的 `PromptAssembler` 和 final request assembler必须由 Android/Desktop 共同调用。

但 main-chat Prompt text/builders 当前仍物理存在于 Android module `PromptTemplates.kt`。

Desktop 不能：

- 复制 literal；
- 复制“近似 Prompt”；
- 从 Android UI module runtime dependency 偷用；
- 用历史 Reference Pack代替。

所以在 Prompt assembly shared 化之前，需要一个独立 Prompt ownership closure。

## 11.2 Proposed D-030 — NOT YET APPROVED

建议建立：

**D-030：Main-chat Prompt authority shared closure**

做法与已完成 D-028/3P 相同：

- 仅把 Phase 4 main-chat 所需官方 Prompt literals/builders 的**物理 authority**移入 sharedCore；
- 文本 byte/character-for-character 不改；
- Android `PromptTemplates` 保留原有 public symbols/facade；
- Android callers行为不变；
- Desktop 调用同一 authority；
- 非 Phase 4 的 Character AI / WorldBook AI / image / memory maintenance 等 Prompt 暂不顺手搬迁。

至少覆盖当前 main-chat pipeline实际拥有的：

- system Prompt fixed prefix/middle/suffix + override / `{{original}}`
- post-history template
- CCB handshake/contract/approval/continuation/final tail messages
- current-turn output requirements
- format-history continuity notice
- roleplay speaker-format requirement
- blank-continue request Prompt
- FormatCard random-number user suffix builders
- reply length/language/tail constraint builders
- section-heading constants used by main chat

这是 physical ownership move，不是 Prompt rewrite。

在用户明确批准 D-030 前，不得实施该 slice。

---

# 12. PromptAssembler contract

`PromptAssembler` 本身主要是 JVM-neutral domain semantics，但当前被两个 ownership dependency 卡住：

1. Android-local `PromptTemplates`
2. Android-local `RetrievedKnowledgeCard`

`RetrievedKnowledgeCard` 自身是纯 JVM model，仅依赖已经 shared 的 `ChunkSourceType` / `VectorChunk`；它可以移动到 sharedCore而不等于提前实现 RAG runtime。

PromptAssembler 当前必须保持：

- STRUCTURED / FREEFORM character body
- `basicSetting`
- `mesExample`
- systemPrompt override ownership
- post-history `{{original}}`
- WorldBook prompt
- WorldBook outlets
- setting-reference RAG：非 `CHAT_MEMORY`
- memory RAG：`CHAT_MEMORY`
- player settings
- supplementary setting
- reply language/length
- stable/dynamic/tail layer separation
- stable-prefix cacheability rules
- unresolved outlet 对 cacheability 的影响

---

# 13. Final logical message authority

当前最敏感的 message-order code仍作为 top-level helper藏在 Android `ChatViewModel.kt`：

- `buildCcbStablePrefixMessages`
- `buildCcbFinalTailSystemPrompt`
- `appendCurrentUserAndCcbTailMessages`

Phase 4 必须把这一 authority 抽成 shared domain assembler，而不是让 Desktop 重写。

Authoritative logical order：

```text
core system + creator identity
→ assistant first acknowledgement
→ user creative contract
→ assistant contract confirmation
→ optional START requirements
→ character/stable context
→ setting reference (WorldBook + non-memory RAG)
→ supplementary
→ player
→ assistant context approval
→ Archive
→ earlier chat history
→ memory RAG
→ HEAD/timeline
→ previous-turn heading + previous turn
→ CCB continuation system
→ current user
→ post-history + optional END requirements
→ optional STRONG_PROMPT_SUFFIX system
→ CCB post-user assistant acknowledgement
→ CCB post-user identity reminder user message
```

Final CCB identity reminder remains the last logical message.

`BOTH` 在 START 与 END 两处使用同一 current-turn requirements。

Prompt cache identity：

- exact stable prefix role/content sequence
- through CCB context approval
- END-only requirements不进入 stable-prefix cache identity
- Archive/history/dynamic memory/current user/final tail不进入该 stable key

---

# 14. ChatApiMessage boundary

`ChatApiMessage` 当前声明在 `StreamingChatService.kt`，但它本身是 pure serializable logical model：

- `role`
- `content: JsonElement`
- text builder
- multimodal content builders

Phase 4 final logical assembler需要它，因此应把 **logical message value type** 抽到 sharedCore。

这不等于把 `StreamingChatService` 提前搬进 Phase 4。

Phase 5 继续拥有：

- provider request body
- auth
- SSE
- retries
- thinking/reasoning transport parameters
- HTTP/network
- cleartext local template role adaptation
- final transport serialization

图片文件读取 / Base64 encoding 也是 platform/runtime preparation，不应塞入 pure logical assembler；assembler只接受已准备好的 logical multimodal content。

---

# 15. FormatPromptPosition boundary

`START / END / BOTH` 当前 enum 物理定义在 Android `ModelConfig.kt`。

Phase 4 logical assembler需要该值，但完整 `ModelConfig` 属于 Phase 5。

因此只抽取 `FormatPromptPosition` 这个 pure value type到 sharedCore，并由 Android ModelConfig 继续引用同一 type。

不得借机提前迁移完整 provider/model runtime。

---

# 16. Phase 4 / Phase 5 boundary

## Phase 4 owns

- chat/session persisted contract
- ChatRepository
- CharacterSessionService
- ContextWindowManager
- PlaceholderRenderer
- WorldBookEngine
- WorldBook request planner
- main-chat Prompt shared authority
- PromptAssembler
- FormatCard request tools
- logical ChatApiMessage value type
- logical request/message assembler
- prompt cache logical key
- fake/test model driver
- read-only Prompt Inspector for logical request
- Desktop session creation/greeting
- restart persistence smoke

## Phase 5 owns

- ModelConfig/provider runtime extraction
- model resolution/discovery/auth
- SecretStore/provider credential work
- `ProxyAwareClient`
- `StreamingChatService`
- `/chat/completions` transport
- SSE
- retry/cancellation/network
- thinking/reasoning request parameters
- cleartext local HTTP adaptation
- true final serialized transport request
- live model response persistence

### Prompt Inspector clarification

Phase 4 Prompt Inspector may show:

- logical messages
- roles
- source sections
- WorldBook trigger/reasons
- Archive/HEAD inputs if supplied by fake fixture
- previous-turn/current-user/final-tail placement
- prompt cache logical prefix/key

它不得在 Phase 4 伪造“final transport request”。

redacted final transport view 要等 Phase 5 transport authority接入后再完成。

---

# 17. Parity promotion rules

Phase 4 不能一次性把所有 chat rows 改 EXACT。

## After 4A1/4A2

可考虑：

- Session Entity → EXACT
- Message Entity → EXACT

前提：Android/Desktop使用同一 shared serialized authority且 repository regression PASS。

不能因此提升：

- send/regenerate/edit/delete
- final API message order
- Provider/runtime

## After 4B

可考虑：

- ContextWindow → EXACT
- WorldBook Engine → EXACT
- WorldBook timed effects → EXACT

前提：不仅 engine，而且 request-level source/scan/timed orchestration共享并通过 parity fixture。

## After 4P/4C

可考虑：

- PromptTemplates → EXACT
- PromptAssembler → EXACT
- FormatCard user tools / STRONG_PROMPT_SUFFIX → EXACT

但 **final API message order** 建议继续 PENDING 到 Phase 5 transport serialization gate，因为 Project invariant 以最终 serialized request为真值。

## After 4D

- Prompt Inspector → EQUIVALENT only after real Desktop UI exists and accepted.
- session creation/greeting可作为 Phase 4 acceptance事实记录。
- send/regenerate/edit/delete仍不得整体提升。

---

# 18. Proposed implementation slicing

## 4A1 — Shared Chat Entity Contract Core

状态：**COMPLETE / PROJECT REVIEW PASS**。  
Implementation：`f850ece3df7f36391b1fa4e81c286110f9610844`。

Scope：

- `ChatSession`
- `ChatMessage`
- `ReplyLength`
- `TimedEffectState`
- `ChatDraft`
- `ChatMessageIndex`
- `ChatMessageOrderBackup`
- `ChatScrollPosition`
- `NovelAiImageModelResolution`
- directly required pure serialized/value dependencies

Goals：

- one shared Entity authority
- no schema/default behavior change
- no Prompt
- no WorldBook runtime
- no transport
- no Desktop UI

Special gate：

- do not add Compose runtime to sharedCore merely for `@Stable`
- preserve Android functional behavior/compile
- move existing serialization/compatibility tests where appropriate

P4-S1 accepted evidence：focused shared **39 PASS**；sharedCore **38 suites / 235 tests / 0 failures / 0 errors / 0 skipped**；desktopApp **28 suites / 254 tests / 0 failures / 0 errors / 0 skipped**；Android JVM **171 suites / 1110 tests / 0 failures / 0 errors / 0 skipped**；Desktop compile、Android compile、`git diff --check` **PASS**。pre-extraction-compatible Android JSON → shared decode → repository rewrite/reopen fixture **PASS**。未要求 real-user-data manual migration 或 packaged/manual acceptance。

## 4A2 — Shared Chat Repository + Session Creation

状态：**COMPLETE / PROJECT REVIEW PASS**。  
Remainder implementation：`bfcd37e2f1f316ae60f733c0146846f66a4f76b4`。

Scope：

- `ChatRepository`
- `ChatMessageOrdering`
- `ChatMessageOrderRepairPolicy`
- `TimelineTurnPolicy`
- `SessionDisplayTitlePolicy`
- `SettingsDraftMerge`
- narrow shared speaker-tag rename helper
- `CharacterSessionService`
- DesktopAppContainer ChatRepository/service wiring

已完成：

- authoritative shared `ChatRepository`
- message ordering/repair
- timeline/source-turn
- session display-title
- `SettingsDraftMerge`
- narrow shared speaker-tag rename helper
- authoritative shared `CharacterSessionService`
- exact Character→Session live/stale/default Format semantics
- exact blank/nonblank opening ASSISTANT greeting persistence semantics
- DesktopAppContainer shared ChatRepository/service wiring
- Android stale-format warning bridge preserving existing `Log.w` tag/text
- Android/Desktop consume the same Chat Entity / repository / session-creation authority

Goals：**MET**。

P4-S2 validation：focused shared **8 PASS**；Desktop integration **1 PASS**；sharedCore **39 suites / 243 tests**；desktopApp **29 suites / 255 tests**；Android JVM **170 suites / 1108 tests**；全部 0 failures/errors/skipped；Desktop / Android compile 与 `git diff --check` **PASS**。packaged/manual acceptance not required.

下一 implementation slice：**4B — Shared Context + WorldBook Request Runtime**。

## 4B — Shared Context + WorldBook Request Runtime

状态：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**。

4B1 — Shared Context + WorldBook Engine Core：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**，implementation `d468f82529ec6e9fa24363e07d1e7fafe6c9ecf0`。

4B1 result：

- `PlaceholderRenderer`
- `ContextWindowManager`
- `WorldBookEngine`
- `WorldBookScanContext`

以上四个 production owners 已 byte-identical move 到 sharedCore，Android duplicate authorities removed。focused shared **37 PASS**；sharedCore **43 suites / 280 tests**；desktopApp **29 suites / 255 tests**；Android JVM **167 suites / 1076 tests**；Desktop/Android compile + `git diff --check` PASS。

4B2 — Shared WorldBook Request Planner：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**，implementation `8867424df3d53bd291b6b361e51aa5059257284e`。authoritative shared `WorldBookRequestPlanner` owns source resolution、duplicate precedence/order、effective scan depth、repository snapshot、transient current input、character tokens、composite/legacy timed-state compatibility、prompt/outlet result and updated timed states；Android `ChatViewModel` delegates，Desktop container obtains the same planner。focused shared **14 PASS**；Desktop integration **1 PASS**；sharedCore **44 suites / 294 tests**；desktopApp **30 suites / 256 tests**；Android JVM **167 suites / 1076 tests**；Desktop/Android compile + `git diff --check` PASS。

Scope：

- `PlaceholderRenderer`
- `ContextWindowManager`
- `WorldBookEngine`
- `WorldBookScanContext`
- shared request-level WorldBook planner/service
- Android ChatViewModel delegates to shared authority
- Desktop obtains same authority

No Prompt text changes.

## 4P — Main-Chat Prompt Ownership Closure

状态：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**。

D-030 was explicitly approved by the user on 2026-09-26；implementation `e927277dabb206aa34b2374e3596c67a31f0f7db`。

Result：

- shared `MainChatPromptAuthority` is the physical authority for Phase 4 main-chat Prompt symbols required by 4C
- Android `PromptTemplates` facade preserved
- Prompt literal/runtime behavior unchanged
- 27 moved literals/section constants directly verified character-for-character against pre-4P source
- 12 moved builders/helpers preserve behavior and Android delegates
- Android duplicate moved literals removed
- observed upstream `354f151...` Prompt drift not absorbed
- shared authority 16 PASS；Android facade 23 PASS；sharedCore 45 suites / 310 tests；desktopApp 30 suites / 256 tests；Android JVM 167 suites / 1078 tests；Desktop/Android compile + `git diff --check` PASS

## 4C — Shared Prompt + Logical Request Assembly

状态：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**。

Implementation：`8bd876bb28c19b39f96a3abb109698132912d9a2`。

Scope：

- `RetrievedKnowledgeCard` pure model
- `PromptAssembler`
- `ChatHistoryPromptPolicy`
- `ChatRequestMemoryPolicy`
- `PromptCacheKeyFactory`
- `FormatCardUserToolPolicy`
- `FormatPromptPosition`
- pure `ChatApiMessage`
- shared `MainChatRequestAssembler`
- fixed-RNG / inline-fixture logical-order tests
- Android ChatViewModel delegates final logical order to shared authority

Result：authoritative shared `MainChatRequestAssembler` returns logical `List<ChatApiMessage>` + logical prompt-cache key；shared `PromptAssembler` / history-memory policies / user-tool policy / `ChatApiMessage` / `FormatPromptPosition` / `RetrievedKnowledgeCard` / minimal `RoleplayStatusStripper` are available to Android/Desktop；Android caller keeps platform image/Base64, RAG/WorldBook/memory acquisition, persistence and streaming responsibilities。

Project review：final logical ordering、START/END/BOTH、Archive → earlier history → memory RAG → HEAD/timeline → previous turn、current USER exactly once、post-history/END → STRONG_PROMPT_SUFFIX → CCB final tail、stable-prefix cache boundary and transport separation **PASS**。Prompt literals/builders unchanged；observed upstream drift not absorbed。

Validation：focused shared **48 PASS**；Desktop integration **1 PASS**；Android serialization/order **3 PASS**；sharedCore **52 suites / 358 tests**；desktopApp **31 suites / 257 tests**；Android JVM **163 suites / 1030 tests**；Desktop/Android compile + `git diff --check` PASS。

No HTTP / provider / SSE.

## 4D1 — Desktop Fake Chat Runtime

状态：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**。

Implementation：`06b5243282627e5954d5ad4a4c6e6f846a30400d`。

Result：

- Desktop session create/open delegates shared `CharacterSessionService` / `ChatRepository`
- blank/nonblank opening greeting persistence preserved
- normal nonblank USER persists before assembly/capture
- persisted USER rebuild performs no duplicate message write
- explicit effective context-window input remains request authority；`session.contextWindowSize` is not promoted
- session player override beats supplied global values
- active FormatCard resolution uses shared resolver/policies
- persisted USER participates in shared WorldBook scan with no transient duplicate
- changed timed WorldBook state persists；unchanged state causes no session rewrite
- shared `PromptAssembler` + `MainChatRequestAssembler` own Prompt/logical order/cache key
- RANDOM_NUMBER remains request-only；STRONG_PROMPT_SUFFIX uses shared final placement
- fake driver captures only transport-neutral logical messages + logical cache key
- no fake ASSISTANT response；no real Provider/network/SSE

Restart acceptance：second `DesktopAppContainer` on the same app-data root reopened the same session, preserved greeting + USER order, rebuilt semantically identical logical messages/cache key, and performed zero additional message writes。

Validation：fake runtime **10 PASS**；CharacterSession Desktop integration **1 PASS**；WorldBook planner Desktop integration **1 PASS**；sharedCore **52 suites / 358 tests**；desktopApp **32 suites / 267 tests**；Android JVM **163 suites / 1030 tests**；Desktop/Android compile + `git diff --check` PASS。

No real Provider.

## 4D2 — Prompt Inspector + Phase 4 Acceptance

状态：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED / PACKAGED MANUAL ACCEPTANCE PASS**。

Implementation：`0a89b111e93c3c1a2b9a24127608e0b25e4ef9c4`。

Result：

- common `DesktopChatRequestPlanner` is shared by fake runtime and Inspector；no second Desktop Context/WorldBook/Prompt/order implementation
- `MainChatRequestAssembler` emits assembly-time provenance via message trace；Inspector source labels are authoritative, not text heuristics
- Inspector exposes logical messages/roles/sources, stable-prefix membership/messages/cacheability/key, WorldBook debug evidence, persisted/proposed timed state
- Inspector is explicitly labeled logical / transport-neutral, not provider HTTP serialization
- read-only repository paths avoid message-index repair/write；zero-write test proves deleted index is not recreated and app-data snapshot remains byte-identical
- proposed WorldBook timed state is never persisted by Inspector；normal 4D1 fake runtime still persists changed timed state
- packaged `:desktopApp:createDistributable` PASS
- user manual packaged/UI-smoke acceptance PASS
- populated-session Inspector manual visual scenario NOT RUN because no disposable persisted Desktop chat fixture was available；equivalent real-repository automated persisted-session/request/WorldBook/cache/read-only coverage PASS

Validation：MainChatRequestAssembler **6 PASS**；Desktop Prompt Inspector **5 PASS**；existing DesktopFakeChatRuntime **10 PASS**；CharacterSession Desktop integration **1 PASS**；WorldBook planner Desktop integration **1 PASS**；sharedCore **52 suites / 358 tests**；desktopApp **33 suites / 272 tests**；Android JVM **163 suites / 1030 tests**；Desktop/Android compile + `git diff --check` PASS。

Phase 4 acceptance：**COMPLETE / ACCEPTED**。

---

# 19. Validation strategy

Phase 4 tests should use inline / dedicated contract fixtures, not editable presets as golden truth.

High-value regression sets：

### Entity/repository

- old JSON defaults
- round-trip serialized fields
- source-turn identity
- message index/order
- draft/scroll state
- repair/undo stale protection
- title rewrite/display override

### session creation

- Character default Format live/stale
- existing session independence
- blank/nonblank greeting assistant-message semantics

### Context

- source-turn grouping
- legacy adjacency fallback
- abnormal SYSTEM sequences
- previous-turn hot zone
- unanswered current USER
- archive boundary

### WorldBook

- multi-book order
- duplicate-ID linked-book precedence
- entry order
- scan-depth override
- transient current input
- selective/regex/whole-word
- probability/group
- recursion
- budget
- outlet
- sticky/cooldown/delay
- legacy/plain timed-state fallback

### Prompt/logical request

- STRUCTURED/FREEFORM
- placeholders
- START/END/BOTH
- WorldBook vs setting-RAG vs memory-RAG positions
- Archive/history/HEAD/previous-turn positions
- current USER exactly once
- blank continue
- RANDOM_NUMBER fixed RNG
- STRONG_PROMPT_SUFFIX
- CCB tail always final
- stable prefix cache identity
- multimodal logical content ordering

### Phase 5 deferred gate

- cleartext role adaptation
- actual serialized request body
- provider-specific request parameters
- SSE/live transport

---

# 20. Program Budget / empirical recalibration

Phase 3 showed that initial estimates were too optimistic, especially for cross-platform verification and large extraction tasks.

Phase 4 planning should therefore use conservative envelopes.

Suggested Program Budget:

| Slice | Planned weekly |
|---|---:|
| 4A1 Shared Chat Entity Contract Core | 0.04–0.07 |
| 4A2 Shared Chat Repository + Session Creation | 0.06–0.09 |
| 4B Context + WorldBook Request Runtime | 0.05–0.08 |
| 4P Main-chat Prompt Ownership Closure | 0.05–0.08 |
| 4C Prompt + Logical Request Assembly | 0.08–0.12 |
| 4D1 Desktop Fake Chat Runtime | 0.06–0.09 |
| 4D2 Prompt Inspector + acceptance/finalization | 0.05–0.08 |

Total planning envelope：

**~0.39–0.61 weekly**

这是 Program Budget，不是必须花满，也不是 acceptance 上限。

Project audit cost：

**Codex = 0**

Reference adoption docs task latest observed telemetry（尚未写入 repo telemetry）：

- Sol Medium
- 2m48s
- 5h 42% → 35% = 7%
- weekly 75% → 74% = 1%

---

# 21. Current implementation control

当前下一 production gate：

**Phase 5 — Model Runtime + Real Chat**

4A1 / 4A2 / 4B / 4P / 4C / 4D1 / 4D2 已完成并通过 Project review，Phase 4 **COMPLETE / ACCEPTED**。下一阶段为 Phase 5 real Provider/model runtime；Phase 4 的 logical request / Prompt Inspector 仍不得被误称为 provider serialized HTTP request。

Phase 4 final invariant：Prompt Inspector remains read-only；logical request authority remains transport-neutral；Prompt literals/runtime、Package/schema/persisted chat contract remain unchanged。Phase 5 owns Provider/network/SSE、serialized request-body adaptation and live response persistence。

4B1/4B2 已完成 shared Context + WorldBook request runtime：`PlaceholderRenderer`、`ContextWindowManager`、`WorldBookEngine`、`WorldBookScanContext`、`WorldBookRequestPlanner` 均为 shared authority。Prompt text/runtime 仍未改变。

当前 compatibility claim 仍绑定 formal baseline `1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。observed upstream `354f15166d8bc0462cb87d62a0ba4613794560a3` 为 HIGH drift / NO SYNC，保留在 selective/batch sync backlog。

---

# 22. Resolved decision

**D-030 — Main-chat Prompt authority shared closure：APPROVED 2026-09-26**

用户已明确批准以下且仅以下范围：

- Prompt 文本不改；
- behavior 不改；
- Android facade保留；
- Desktop与Android共用一个 authority；
- 不顺手移动非 Phase 4 Prompt；
- 不吸收 observed upstream `354f15166d8bc0462cb87d62a0ba4613794560a3` 的 Prompt drift。

该决策已由 4P 实施并通过 Project review；4C 也已完成并集成。D-030 继续约束后续同步：不得把 observed upstream Prompt drift 当作已吸收。

---

# 23. Audit / implementation status

Project conclusion：

- Phase 4 architecture boundary：**RESOLVED**
- 4A1：**COMPLETE / PROJECT REVIEW PASS**
- 4A2：**COMPLETE / PROJECT REVIEW PASS**；shared ChatRepository/pure policies + CharacterSessionService + Desktop wiring complete
- 4B：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**；4B1 + 4B2 complete
- 4P：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**
- 4C：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**
- 4D1：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**
- 4D2：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED / PACKAGED MANUAL ACCEPTANCE PASS**
- Phase 4 overall：**COMPLETE / ACCEPTED**
- 4D scope：**RESOLVED**
- next implementation：**Phase 5 — Model Runtime + Real Chat**
- upstream sync：**NOT REQUIRED / NO SYNC**
- formal baseline：ChatChatBar `1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`
- observed upstream：`354f15166d8bc0462cb87d62a0ba4613794560a3`，HIGH drift，queued for later selective/batch sync
- Prompt text/runtime：**UNCHANGED**
- Package/schema versions：**UNCHANGED**
- P4-S1 implementation：`f850ece3df7f36391b1fa4e81c286110f9610844`
- P4-S1 Project review：**PASS WITH NON-BLOCKING NOTES**
- P4-S2 / 4A2 remainder：`bfcd37e2f1f316ae60f733c0146846f66a4f76b4`，**PROJECT REVIEW PASS / INTEGRATED**
- P4-S3 / 4B1：`d468f82529ec6e9fa24363e07d1e7fafe6c9ecf0`，**PROJECT REVIEW PASS / INTEGRATED**
- P4-S4 / 4B2：`8867424df3d53bd291b6b361e51aa5059257284e`，**PROJECT REVIEW PASS / INTEGRATED**
- P4-S5 / 4P：`e927277dabb206aa34b2374e3596c67a31f0f7db`，**PROJECT REVIEW PASS / INTEGRATED**
- P4-S6 / 4C：`8bd876bb28c19b39f96a3abb109698132912d9a2`，**PROJECT REVIEW PASS / INTEGRATED**
- P4-S7 / 4D1：`06b5243282627e5954d5ad4a4c6e6f846a30400d`，**PROJECT REVIEW PASS / INTEGRATED**
- P4-S8 / 4D2：`0a89b111e93c3c1a2b9a24127608e0b25e4ef9c4`，**PROJECT REVIEW PASS / INTEGRATED / PACKAGED MANUAL ACCEPTANCE PASS**
- Phase 4 final production implementation SHA：`0a89b111e93c3c1a2b9a24127608e0b25e4ef9c4`
- Project audit Codex cost：**0**
