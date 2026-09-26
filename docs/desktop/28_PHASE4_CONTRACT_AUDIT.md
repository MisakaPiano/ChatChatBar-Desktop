# CCB Desktop Phase 4 Contract Audit

> 状态：**AUDIT COMPLETE**
> Formal upstream baseline：ChatBar `1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`
> Observed upstream：`354f15166d8bc0462cb87d62a0ba4613794560a3`，HIGH drift，**NO SYNC**
> P4-S1 implementation：`f850ece3df7f36391b1fa4e81c286110f9610844`
> P4-S1 Project review：**PASS WITH NON-BLOCKING NOTES**
> 当前 control point：4A1 **COMPLETE / PROJECT REVIEW PASS**；4A2 **PARTIAL**
> 下一 implementation：**4A2 remainder**

## 1. Authority and audit boundary

Phase 4 的行为目标继续绑定 formal validated baseline。observed HIGH drift 已进入后续 selective/batch sync backlog；它没有直接推翻本 audit 的 Chat、Context、WorldBook 或 Prompt 边界，因此本阶段不吸收 upstream，也不修改 formal baseline。

本文件定义 Core Chat、context assembly、WorldBook request runtime 与 Prompt/request serialization 的权威合同和实现切片。Phase 3 Package transfer、data-root migration、平台 UI 与 provider transport 不得借 Phase 4 改写 persisted Chat 或 Prompt 合同。

```text
Persisted Chat Entity
!= ChatRepository / session lifecycle
!= Context / WorldBook / FormatCard request runtime
!= Prompt assembly
!= final ChatApiMessage serialization
!= provider transport
```

## 2. Chat persisted contract

`ChatSession`、`ChatMessage` 及其直接 serialized dependencies 是 JVM-shared persisted contract。Android 与 Desktop 必须读取、写入同一字段名、defaults、nullable meanings、enum representations 和 timeline identity；不得保留平台各自演化的第二套 Entity。

必须保持：

- session identity、character association、selected format/model/config references 与 session timestamps 的既有序列化含义；
- message identity、role、content、reasoning、images/attachments、created/updated ordering data；
- `sourceTurnId` / `sourceTurnOrder` 的稳定 source-turn identity；
- interrupted assistant draft、reasoning-only draft 与 alternative/history metadata 的 1.4.1 semantics；
- legacy records 缺少新字段时的既有 defaults 与 timeline compatibility；
- persisted message body 与 request-only synthetic Prompt message 的边界。

Persisted Entity 不是最终 API message。不得把 request-only continuation、WorldBook injection、FormatCard suffix 或 CCB acknowledgement 写回 Chat Entity，除非 upstream persisted contract 明确要求。

## 3. ChatRepository contract

authoritative shared `ChatRepository` 负责：

- session/message JSON persistence；
- message ordering、paging/indexing 与 stable deterministic reads；
- source-turn assignment、legacy timeline repair/migration 与 same-turn grouping；
- interrupted reply persistence所需的 same-ID update semantics；
- session preview/display-title 所需 pure policy；
- deletion/update 后保持 repository observable state 与 durable storage 一致。

Repository 不负责：

- Character greeting 的业务创建顺序；
- ContextWindow token selection；
- WorldBook matching/injection；
- PromptTemplates wording 或 final request ordering；
- provider-specific role adaptation。

P4-S1 已完成 shared `ChatRepository`、message ordering/repair、timeline/source-turn、session display-title 与 directly required pure policies；Android-local duplicate authority 已移除。该完成范围属于 4A2 的一部分，不等于整个 4A2 完成。

## 4. Session creation and greeting contract

`CharacterSessionService` 的既有成功顺序是：

```text
require CharacterCard
→ resolve available character-default FormatCard
  （不可用时按既有 warning/fallback policy）
→ construct and persist ChatSession
→ persist initial greeting as ASSISTANT message
→ return session ID
```

session 和 greeting 必须使用 shared Entity/Repository authority；平台 wiring 不得复制业务规则。Character default FormatCard resolution、fallback/warning、greeting role/body 和 creation timestamps 保持 upstream-compatible。

4A2 remaining work：共享 authoritative `CharacterSessionService`，并让 Desktop service/container 与 Android 使用同一 authority。该 wiring 尚未完成，不能将 4A2 或 Desktop chat creation parity 标为完成。

## 5. ContextWindow contract

ContextWindow 以 stable source turn 为基本单位，而不是把每个 persisted row 当成独立 conversational turn：

- 同一 `sourceTurnId` 的 user/assistant/derived variants 按 `sourceTurnOrder` 和既有选择政策形成稳定组；
- legacy data 继续支持相邻 user/assistant grouping；
- current turn 与 earlier history 分区必须稳定，不能让当前 user 同时出现在 history 和 current input；
- blank continue 复用 latest persisted USER 时，复用 body、images、message ID/source identity，并从 history 排除；
- reasoning-only interrupted regeneration 的旧 response body 只保留为 alternative/history metadata，不重新进入新 request；
- previous-turn、HEAD/timeline、memory RAG 的位置由 Prompt pipeline 控制，Repository 不自行注入。

Context selection 必须保持 deterministic token/window behavior。Phase 4 不以“Desktop 能显示 messages”替代 ContextWindow parity。

## 6. WorldBook engine and request-level runtime

WorldBook persisted Entity/transfer contract 已共享，但 request runtime 是独立 Phase 4 合同。每次请求必须基于 current input、selected history/context 与 existing WorldBook settings 执行既有 matching：

- primary/secondary keys、selective logic、case sensitivity、whole-word/regex detection；
- scan depth、recursive scanning、token budget；
- insertion order/priority、position/outlet mapping；
- probability/group/groupWeight；
- sticky/cooldown/delay 与 timed state；
- character filters 与 enabled/disabled policy；
- matched entries 的 deterministic ordering 与 request-only injection。

request-level runtime 不得把 WorldBook output 持久化为普通 ChatMessage。WorldBook transfer parity 不代表 engine parity；timed effects、state progression、RAG/context interaction 必须在 4B focused tests 中证明。

## 7. Placeholder contract

main-chat request rendering 继续使用 authoritative placeholder policy：

```text
{{char}} → current character speaker/display name
{{user}} → current player/user name
```

替换应用于既有允许渲染的 Character、WorldBook、FormatCard 与 Prompt-derived text；不得对 opaque JSON、resource path、tool protocol token 或 persisted source bytes做全局字符串替换。缺失/空名称、speaker-name selection 与 repeated placeholders 维持 upstream policy。

Placeholder rendering 是 pure request-time behavior，不应反写 Package DTO 或 persisted Entity。

## 8. FormatCard request runtime

FormatCard Entity/Package 与 user-tool validation 已共享；Phase 4 request runtime仍需保持：

- ordered `userTools`；
- `RANDOM_NUMBER` 生成 request-only user-message append/suffix，参数继续由 shared validator 验证；
- `STRONG_PROMPT_SUFFIX` 生成 request-only strong system suffix；
- START/END/BOTH requirements placement；
- character default FormatCard selection/fallback；
- cleartext local HTTP endpoint 所需 role adaptation；
- runtime output 不写回 FormatCard Entity，也不改变 Package schema。

shared validator 是唯一 validation authority；Prompt/runtime policy调用它，但不能把随机 suffix、message append 或 strong Prompt runtime 为编译方便搬进 Entity contract。

## 9. Main-chat Prompt ownership and proposed D-030

官方 Prompt text、section ownership、replaceable-middle semantics 和 final logical ordering属于 Prompt domain。`PromptTemplates.kt` 仍是官方自然语言 Prompt source；shared Chat state 不拥有或复制这些文字。

拟议 **D-030** 用于明确 main-chat Prompt authority 和平台共享边界。当前状态：**pending user approval before 4P**。批准前：

- 不移动、改写、简化或复制官方 Prompt text；
- 不因 Entity/Repository sharing 宣称 Prompt parity；
- 不改变 fixed prefix → replaceable middle → fixed suffix；
- `{{original}}` 仍只代表 default replaceable middle；
- 不改变 START/END/BOTH 或 final API-message order。

4P 必须等待 D-030；4A2 remainder、4B 或 4C 不得提前实现其 production semantics。

## 10. PromptAssembler contract

`PromptAssembler` 是 logical sections/layers 的组合边界，负责以既有顺序组合可缓存 stable context 与 per-request dynamic context。它必须：

- 保持 section role、ordering、omission 与 cache boundary；
- 把 Character、WorldBook、RAG、Archive/HEAD、history、current input 和 FormatCard runtime output放在各自权威位置；
- 保持 previous-turn/current-turn separation；
- 不把 preview/debug representation 当作 serialized request truth；
- 不让 reasoning preview、stream progress 或 candidate JSON进入 model messages；
- 不改变 Prompt literal ownership。

PromptAssembler output仍不是 transport request；最终必须经过 `ChatApiMessage` boundary 和 endpoint role adaptation。

## 11. Final logical message order

formal baseline 的最终 logical order 为：

```text
core/system contract + creator identity
→ CCB first acknowledgement / contract confirmation
→ optional START requirements
→ character / stable character context
→ WorldBook + setting RAG
→ supplementary context
→ player identity/context
→ CCB context approval
→ Archive
→ earlier history
→ memory RAG
→ HEAD / timeline
→ previous turn
→ CCB continuation acknowledgement
→ current user
→ character post-history + optional END requirements
→ optional FormatCard strong system suffix
→ CCB post-user assistant acknowledgement
→ final user identity reminder
```

Position contract：

- START：contract confirmation之后、Character/stable context之前；
- END：current user之后，与character post-history同阶段、strong/final tail之前；
- BOTH：两个位置都出现；
- blank continue/latest USER reuse：current user只出现一次，且 images/ID不重复；
- latest persisted message不是 USER 时：使用 request-only `continueGenerationUserPrompt()`，不新增 persistent USER row。

HTTPS 与允许的 local cleartext HTTP 可有 role adaptation差异，但 logical content/order必须相同。Prompt truth 是最终 serialized request，不是 UI preview。

## 12. ChatApiMessage boundary

`ChatApiMessage(role, content: JsonElement)` 是 logical chat pipeline 与 OpenAI-compatible provider serialization 的边界：

- text content 与 multimodal array保持既有 JSON shape；
- reused latest USER images只序列化一次；
- role adaptation只发生在允许的 provider/endpoint policy边界；
- request-only system/user/assistant tail不持久化为 Chat Entity；
- whitespace、reasoning、tool/protocol fields按 transport contract处理；
- provider parameters、auth、retry、SSE/watchdog属于 model-request runtime，不属于 ChatRepository。

Desktop 达到 UI parity 前仍必须以最终 serialized `ChatApiMessage` 序列断言为准。

## 13. FormatPromptPosition boundary

`FormatPromptPosition.START / END / BOTH` 是 persisted model/config value type。它只决定 Format requirements 的 logical placement：

- `includesStart`：START、BOTH；
- `includesEnd`：END、BOTH。

它不改变 FormatCard ordered user tools、不拥有 Prompt wording，也不能用于重排其它 sections。Entity decode/default、UI selection 与 final serialization必须保持同一 enum semantics；不得借 extraction新增 schema version。

## 14. Phase 4 / Phase 5 boundary

Phase 4 交付 shared chat/domain/request foundation 与 Desktop adapter，范围止于可验证的 chat lifecycle、context、WorldBook、Prompt/runtime 和 model-request integration。

Phase 5 承担完整 Desktop end-user chat experience及其成熟化，例如完整会话工作台、编辑/管理 UX、streaming/progress呈现、media-rich interaction、manual/packaged acceptance及后续产品层能力。Phase 4 不因 foundation compile或测试通过而提前宣称这些 user-facing能力完成。

不得把以下内容带入 Phase 4 foundation slice：unrelated UI redesign、new provider features、Prompt copy editing、Package/schema migration、data-root migration、updater、SecretStore或跨平台发布体系。

## 15. Parity promotion rules

任一 parity row晋升必须同时有：

1. authoritative shared/domain implementation完成；
2. Android继续消费同一 authority且行为未回归；
3. Desktop production wiring实际可达；
4. focused contract tests覆盖 persisted/ordering/failure boundary；
5. relevant sharedCore/Desktop/Android regression通过；
6. final serialized request parity在涉及 Prompt/runtime时通过；
7. Project review PASS；
8. user-facing能力需要 packaged/manual acceptance时，该 gate 已完成。

Entity、Repository或基础设施完成本身不能晋升 end-user parity。observed upstream但未验证的行为不能写成 Desktop compatibility claim。

## 16. Approved implementation slicing

### 4A1 — Shared Chat Entity Contract Core

状态：**COMPLETE / PROJECT REVIEW PASS**。

交付 authoritative shared `ChatSession`、`ChatMessage`及直接 serialized dependencies；保持 Android JSON compatibility与Package/schema不变。

### 4A2 — Shared Chat Repository + Session Creation

状态：**PARTIAL**。

已完成：shared `ChatRepository`、message ordering/repair、timeline/source-turn、session display-title及required pure policies。

剩余：authoritative `CharacterSessionService` session creation/greeting与Desktop service/container wiring；Android必须消费同一authority。下一 implementation为 **4A2 remainder**。

### 4B — ContextWindow + WorldBook request runtime

状态：**PENDING**。

实现/共享 stable turn grouping、context selection、WorldBook engine/request-level matching、timed state和request-only injection；不改Prompt text。

### 4P — Main-chat Prompt ownership closure

状态：**PENDING / BLOCKED BY D-030 APPROVAL**。

在D-030批准后建立narrow authoritative Prompt-owned bridge，保持官方文字、replaceable-middle与serialized order。

### 4C — Prompt assembly + final request contract

状态：**PENDING**。

共享/适配PromptAssembler、FormatCard runtime placement、ChatApiMessage serialization、HTTPS/local HTTP logical parity与focused serialized-order tests。

### 4D1 — Desktop chat lifecycle adapter

状态：**PENDING**。

将shared session/repository/context/request services接入Desktop container/lifecycle，保持data operation coordinator、root ownership与shutdown ordering。

### 4D2 — Desktop chat surface + packaged acceptance

状态：**PENDING**。

实现最小user-facing Desktop chat path、state/error/progress integration及packaged/manual acceptance；不得顺带扩展Phase 5产品能力。

## 17. Validation strategy

按slice风险逐级验证：

- Entity/Repository：inline legacy JSON fixtures、round-trip、defaults、ordering/source-turn、compatibility rewrite/reopen；
- session creation：Character/Format fallback、persist session-before-greeting、failure behavior、Android/Desktop同authority；
- Context/WorldBook：stable grouping、current-user dedup、matching/options/timed effects、token/scan budget、request-only output；
- Prompt/runtime：START/END/BOTH、blank continue、HTTPS/local HTTP、multimodal、final serialized logical order；
- Desktop adapter：lifecycle、coordinator participation、close/error propagation；
- user-facing surface：packaged current-OS smoke和Project-defined manual acceptance。

每个production slice先跑focused tests，再跑受影响模块。跨shared authority extraction至少验证`sharedCore:test`、`desktopApp:test`、Desktop compile、Android compile和relevant Android JVM regression；是否跑full Android regression由具体影响面决定，不以quota为由削弱必要gate。所有commit前执行`git diff --check`。

P4-S1 accepted evidence：focused **39 PASS**；sharedCore **38 suites / 235 tests**；desktopApp **28 suites / 254 tests**；Android JVM **171 suites / 1110 tests**；全部0 failures/errors/skipped；Desktop/Android compile与diff-check **PASS**。real-user-data manual migration及packaged/manual acceptance不属于该slice。

## 18. Program Budget

| Slice | Program Budget weekly |
|---|---:|
| 4A1 | 0.04–0.07 |
| 4A2 | 0.06–0.09 |
| 4B | 0.05–0.08 |
| 4P | 0.05–0.08 |
| 4C | 0.08–0.12 |
| 4D1 | 0.06–0.09 |
| 4D2 | 0.05–0.08 |
| **Total planning envelope** | **~0.39–0.61** |

该表是approved Program planning envelope，不因单次实测反向改写。P4-S1原始empirical prediction、actual和variance在`23_CODEX_BUDGET.md`与`24_CODEX_USAGE_EMPIRICAL_BASELINE.md`分别保留；quota只影响调度，不改变slice边界、correctness或validation gate。

## 19. Current control point

- 4A1：**COMPLETE / PROJECT REVIEW PASS**。
- 4A2：**PARTIAL**；shared ChatRepository/pure policies complete；CharacterSessionService + Desktop wiring remaining。
- next implementation：**4A2 remainder**。
- P4-S1 implementation：`f850ece3df7f36391b1fa4e81c286110f9610844`。
- P4-S1 review：**PASS WITH NON-BLOCKING NOTES**。
- D-030：**pending user approval**。
- formal baseline：`5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。
- observed upstream：`354f15166d8bc0462cb87d62a0ba4613794560a3`，HIGH，**NO SYNC**。
- Prompt text/runtime、Package/schema、baseline：本control point均未改变。
