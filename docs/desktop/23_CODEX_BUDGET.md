# CCB Desktop Codex Program Budget

> 状态：CURRENT operational planning document  
> 更新时间：2026-09-24  
> 适用：Project → Codex task planning / quota telemetry  
> 架构原则：D-019 — quota 是调度约束，不是架构约束

## 1. 原则

Codex allowance 用于安排任务切片、选择运行时机和估计剩余开发容量。它不能成为降低 data safety、required validation、Android/shared regression、failure recovery、maintainability 或 compatibility 标准的理由。

预算数字是 planning estimate，不是运行分钟数的机械换算，也不是必须花完的目标。

## 2. 记录口径

每轮尽量记录：

```text
task / slice
model
thinking level
wall-clock runtime
5-hour allowance before → after
weekly allowance before → after
resulting commit SHA
validation scope
```

长期规划以 **weekly allowance delta** 为主要口径；5-hour allowance 用于判断当前窗口是否适合继续运行。额度值由用户 / Project 从产品 UI 记录，Codex 不得自行猜测。

## 3. 模型 / thinking

每个 implementation task 必须给出 recommended model、thinking level、expected weekly quota 和理由。

选择完成任务所需的最低充分能力档位。跨模块 extraction、schema/serialization、并发、migration、failure recovery、Prompt/runtime 高风险语义应使用更高 thinking；docs 或小范围机械修正可降低。档位选择不覆盖 acceptance gate。

## 4. Phase 3 baseline

```text
Original Phase 3 envelope: ~0.50 weekly
Planned Codex contract-audit reserve: ~0.08 weekly
Actual contract-audit Codex cost: 0
```

Audit 由 Project 完成，节省记为 favorable variance；不因此下调 Phase 3 的安全/验证预算。

| Slice | Planned weekly |
|---|---:|
| 3B1 Shared Entity / Package Contract Core | 0.08–0.12 |
| 3B2 FormatCard + WorldBook Transfer Core | 0.05–0.07 |
| 3C1 Character Resource / Materialization Core | 0.08–0.11 |
| 3C2 ST Character + Classifier split | 0.05–0.07 |
| 3D Desktop Typed Import/Export + PNG renderer equivalent | 0.07–0.10 |
| 3F Android ↔ Desktop interoperability gate | 0.05–0.08 |

`3P / Phase 4A Prompt ownership closure` 是 dependency edge，范围确定后单独预算，不能为了填满 envelope 提前扩 scope。

## 5. 当前下一轮

```text
Slice: 3B1
Recommended model: Sol
Recommended thinking: High
Planned weekly: 0.08–0.12
```

3B1 有 sharedCore / Android / Desktop 跨模块 extraction 与 serialization/schema 风险，但 contract audit 已完成、探索空间受控，因此 High 是当前最低充分档位。

## 6. Recalibration

每个 slice 完成后记录实际 weekly delta，对照计划区间并解释 variance。只调整后续调度估计：

- 超预算不能降低 acceptance；
- 低于预算不能主动扩大当前 task scope；
- upstream drift / review fixes 单独记录，避免误判 implementation baseline。
