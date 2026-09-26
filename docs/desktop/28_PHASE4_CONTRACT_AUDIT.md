# CCB Desktop Phase 4 Contract Audit

> 状态：**AUDIT COMPLETE**
> Formal upstream baseline：ChatBar `1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`
> Observed upstream：`354f15166d8bc0462cb87d62a0ba4613794560a3`，HIGH drift，未验证、未吸收
> 当前 implementation control point：4A1 COMPLETE；4A2 PARTIAL

## 1. Authority and sync boundary

Phase 4 行为目标继续绑定 formal validated baseline。observed HIGH drift 已进入后续 selective/batch sync backlog；进入 Phase 4 本身不是 sync trigger，本次没有执行 upstream sync，也没有改变 formal baseline。

本文件控制 Core Chat / Prompt / WorldBook 的 implementation slicing。Package transfer、data-root migration 与平台 UI 不得借 Phase 4 改写 chat persistence、Prompt 或 final request contract。

## 2. Contract boundaries

```text
Persisted Chat Entity
!= repository/session lifecycle
!= Prompt/context assembly
!= final provider request/runtime
```

- `ChatSession` / `ChatMessage` persisted JSON 字段、defaults、nullable meanings、source-turn 与 legacy timeline semantics 必须保持兼容。
- repository authority 负责持久化、index/paging、ordering、source-turn assignment/migration 与 session preview；不能替代 Prompt/runtime。
- Prompt truth 仍是最终 serialized logical messages/request；不得从 Entity extraction 推导 Prompt parity。
- Android 与 Desktop 对 JVM-neutral contract 使用单一 shared authority；平台 adapter 不保留第二套独立实现。

## 3. Approved slicing

| Slice | 状态 | Control point |
|---|---|---|
| 4A1 Shared Chat Entity Contract Core | **COMPLETE / PROJECT REVIEW PASS** | shared `ChatSession` / `ChatMessage` 与直接 serialized dependencies |
| 4A2 Shared Chat Repository + Session Creation | **PARTIAL** | shared `ChatRepository` 与 required pure policies 已完成；`CharacterSessionService` session-creation/greeting authority + Desktop service/container wiring 待完成 |
| 4B | **PENDING** | 不得在 4A2 remainder 中提前进入 |
| 4P | **PENDING** | D-030 必须先完成；不得提前改 Prompt text/runtime |
| 4C | **PENDING** | 不得由 chat-state extraction 隐式宣称完成 |
| 4D1 | **PENDING** | 后续独立 implementation/validation slice |
| 4D2 | **PENDING** | 后续独立 implementation/validation slice |

下一 implementation slice 是 **4A2 remainder**，不是 4B。

## 4. P4-S1 reviewed result

- implementation：`f850ece3df7f36391b1fa4e81c286110f9610844`
- Project review：**PASS WITH NON-BLOCKING NOTES**
- 4A1 complete：authoritative shared Chat Entity contract core
- 4A2 completed portion：authoritative shared `ChatRepository`、message ordering/repair、timeline/source-turn、session display-title 与 directly required pure policies
- Android-local duplicate authorities：removed
- compatibility：pre-extraction-compatible Android JSON → shared decode → repository rewrite/reopen **PASS**
- validation：focused **39 PASS**；sharedCore **38 suites / 235 tests**；desktopApp **28 suites / 254 tests**；Android JVM **171 suites / 1110 tests**；全部 0 failures / errors / skipped；Desktop/Android compile 与 diff-check **PASS**
- not required：real-user-data manual migration、packaged/manual acceptance
- unchanged：Prompt text/runtime、Package/schema versions、formal baseline、upstream source

## 5. D-030

状态：**pending user approval before 4P**。

D-030 未因 4A1/partial 4A2 自动解决；在 Project/user approval 前不得把其后续语义写入 production，也不得以当前 shared chat-state foundation 推断 Prompt/runtime 已完成。

## 6. Remaining 4A2 gate

4A2 完成前至少需要：

- 共享 authoritative `CharacterSessionService` session-creation/greeting behavior；
- Desktop service/container wiring 使用该 authority；
- Android 继续消费同一 authority，不保留 divergent duplicate；
- focused compatibility tests 与 sharedCore/Desktop/Android relevant regression；
- 保持 Prompt text/runtime、final message ordering 与 provider transport 不变。

完成并通过 Project review 后，才进入 4B。
