# CCB Desktop Codex Program Budget

> 状态：CURRENT operational planning document  
> 更新时间：2026-09-25
> 适用：Project → Codex task planning / quota telemetry  
> 架构原则：D-019 — quota 是调度约束，不是架构约束

## 1. 原则

Codex allowance 用于安排任务切片、选择运行时机和估计剩余开发容量。它不能成为降低 data safety、required validation、Android/shared regression、failure recovery、maintainability 或 compatibility 标准的理由。

预算数字是 planning envelope，不是运行分钟数的机械换算，也不是必须花完的目标。历史实测 burn rate、runtime 和模型/Thinking 经验基线由 `24_CODEX_USAGE_EMPIRICAL_BASELINE.md` 维护；23 与 24 不得混成同一个数字。

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
Next control: 3P / Phase 4A Prompt ownership closure audit
Following production slices: 3D → 3F
3P Program Budget envelope: NOT SET — Project audit 后按实际范围确定
```

3C1 implementation 实测为 **30m50s / 56% 5h / 9% weekly**；R1 strict-delete repair 为 **4m57s / 14% / 2%**；finalization 为 **7m03s / 18% / 5%**。三者的 derived complete-slice total 为 **42m50s / 88% 5h / 16% weekly**；这是三个 task sample 的合计，不作为第四条独立 empirical sample。

3C2 shared parser / mapper / classifier extraction（`d78d76df656fa3ce9fd309a6cbe52c6cfb379f30`）实测为 **16m42s / 34% 5h / 5% weekly**，落在 `0.05–0.07 weekly` Program Budget envelope 内。下一控制点 3P 是 audit/dependency closure，未完成范围确认前不补猜预算。Program Budget 与 empirical burn 必须继续分开，也都不是 acceptance 上限。

## 6. 每轮开始前的固定评估

Project 在给出 Codex 指令前必须同时提供：

- 推荐 model / Thinking；
- Program Budget envelope；
- empirical expected runtime；
- empirical expected 5h / weekly burn；
- interruption risk；
- 防中断 / checkpoint 措施。

若经验样本不足，必须明确标记低置信度，而不是补猜。

## 7. Recalibration

每个 slice 完成后记录实际 weekly delta，对照计划区间并解释 variance。只调整后续调度估计：

- 超预算不能降低 acceptance；
- 低于预算不能主动扩大当前 task scope；
- upstream drift / review fixes 单独记录，避免误判 implementation baseline。
