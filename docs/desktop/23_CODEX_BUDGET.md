# CCB Desktop Codex Program Budget

> 状态：CURRENT operational planning document  
> 更新时间：2026-09-26
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

## 5. Phase 3F 实测与下一控制点

```text
Phase 3F: COMPLETE / PROJECT REVIEW PASS / INTEGRATED
3P Prompt ownership closure: COMPLETE / PROJECT REVIEW PASS / INTEGRATED
3D Desktop typed transfer: COMPLETE / PROJECT REVIEW PASS / PACKAGED PASS / MANUAL ACCEPTANCE PASS / INTEGRATED
Next control: Phase 4 planning point
```

3C1 implementation 实测为 **30m50s / 56% 5h / 9% weekly**；R1 strict-delete repair 为 **4m57s / 14% / 2%**；finalization 为 **7m03s / 18% / 5%**。三者的 derived complete-slice total 为 **42m50s / 88% 5h / 16% weekly**；这是三个 task sample 的合计，不作为第四条独立 empirical sample。

3C2 shared parser / mapper / classifier extraction（`d78d76df656fa3ce9fd309a6cbe52c6cfb379f30`）实测为 **16m42s / 34% 5h / 5% weekly**，落在 `0.05–0.07 weekly` Program Budget envelope 内。

3P Prompt ownership implementation（`f722703c33d8cd96728fc06ff617c9d7d79d9c7d`）实测为 **18m03s / 41% 5h / 6% weekly**。3D implementation（`d8987605e733b07a5deac1901ee049011d47d153`）实测为 **24m46s / 61% 5h / 10% weekly**；packaged gate 为独立 **7m24s / 21% / 4%** sample。3D implementation + packaged 的 **32m10s / 82% / 14%** 仅是 `DERIVED` operational total，不作为第三条独立 empirical sample；manual acceptance 不消耗 Codex quota。integration-only 只确认 5h burn 3%，weekly 因 UI display/accounting discontinuity 记为 unavailable，不补猜。Program Budget 与 empirical burn 必须继续分开，也都不是 acceptance 上限。

3F 有两个 independent execution samples：initial **1h01m / 100% 5h / 16% weekly**，在 durable checkpoint `717ec1a473660b5d186a4241778552bc6f12a80b` 后因 5h quota 中断；continuation **39m17s / 45% 5h / 7% weekly**，完成 API 36 target、persistent environment 与 failure classification。两者的 Phase 3F pre-finalization total 为 **1h40m17s / 145% 5h across two reset windows / 23% weekly**，仅是 `DERIVED`，不是第三个 sample。相对 planned **0.05–0.08 weekly**，实际 **0.23 weekly**，variance **+0.15 至 +0.18**，约为上界 **2.9x**。这是明确的 budget underestimate：首轮同时承担 cross-platform harness/artifacts 构建、Android environment 建立与 instrumented validation；该偏差用于后续 Phase 4 规划校准，不改变验收标准。

## 6. Phase 4 planning envelope and evidence

Phase 4 的工程 slicing 由 `28_PHASE4_CONTRACT_AUDIT.md` 控制：4A1、4A2、4B、4P、4C、4D1、4D2。每个 slice 继续使用独立 Program Budget envelope；未开始的 slice 不因 P4-S1 实测而自动扩大或缩小。

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

P4-S1 原始预测保持不变：Sol High，runtime **20–30m**、5h **35–50%**、weekly **6–8%**、Program Budget **0.07–0.10 weekly**。实际分两个 quota windows 完成：**19m28s / 51% 5h / 8% weekly**。runtime 比预测下界少 32s，5h 比上界高 1pp，weekly 等于上界。该实测属于 empirical evidence，不能反向改写原始 Program Budget；任务实际覆盖范围比原定 4A1 更宽，包含 partial 4A2。

P4-S2 / 4A2 remainder 冻结预测为 Sol Medium、runtime **8–14m**、5h **12–22%**、weekly **2–4%**；实际 **6m53s / 15% 5h / 2% weekly**，implementation `bfcd37e2f1f316ae60f733c0146846f66a4f76b4`，Project review **PASS**。runtime 比预测下界快 1m07s，5h 命中区间，weekly 命中下沿。由于 P4-S1 是 4A1 + partial 4A2 的混合任务，不能把 P4-S1 全部 burn 与 P4-S2 简单相加并冒充“纯 4A2 actual”；4A2 Program Budget 仍保留原计划口径。

P4-S3 / 4B1 冻结预测为 Sol Medium、runtime **10–16m**、5h **20–30%**、weekly **3–4%**；实际 **7m13s / 18% 5h / 3% weekly**，implementation `d468f82529ec6e9fa24363e07d1e7fafe6c9ecf0`，Project review **PASS**。runtime 比预测下界快 2m47s，5h 比预测下界低 2pp，weekly 命中下沿。4B1 internal Program Budget **0.02–0.04 weekly**，actual **0.03**；4B overall Program Budget 仍为 **0.05–0.08 weekly**，remaining 4B2 planning reserve **0.03–0.04 weekly**。

P4-S4 / 4B2 冻结预测为 Sol Medium、runtime **10–18m**、5h **22–35%**、weekly **3–4%**、internal Program Budget **0.03–0.04 weekly**。任务跨两个 5h window：window 1 因 quota interrupted，5h **14% → 0% = 14%**、weekly **55% → 53% = 2%**；window 2 完成 validation/commit/push，5h **100% → 93% = 7%**、weekly **53% → 52% = 1%**。合计 **21% 5h / 3% weekly**；runtime 未取得完整两窗口对应值，记为 unavailable，不补造。implementation `8867424df3d53bd291b6b361e51aa5059257284e`，Project review **PASS**。5h 比冻结预测下界低 1pp，weekly 命中下沿；4B2 actual weekly **0.03**，在 internal **0.03–0.04** envelope 内。

4B derived actual：4B1 **3% weekly** + 4B2 **3% weekly** = **6% weekly**，完整落在 4B Program Budget **0.05–0.08 weekly** 内。

P4-S5 / 4P 的冻结计划为 **GPT-5.6 Sol / High**、runtime **16–26m**、5h **35–50%**、weekly **5–8%**，Program Budget **0.05–0.08 weekly**。实际执行时用户误选 **Medium**，因此 actual 必须按 Medium telemetry 记录，不能拿来校准 High 预测：runtime **9m06s**、5h **93% → 76% = 17%**、weekly **52% → 49% = 3%**。implementation `e927277dabb206aa34b2374e3596c67a31f0f7db`，Project review **PASS**。4P actual weekly **0.03**，低于 Program Budget 下沿，但该 variance 主要受实际 Thinking 档位不同影响；冻结 High 预测不得事后改写。

## 7. 每轮开始前的固定评估

Project 在给出 Codex 指令前必须同时提供：

- 推荐 model / Thinking；
- Program Budget envelope；
- empirical expected runtime；
- empirical expected 5h / weekly burn；
- interruption risk；
- 防中断 / checkpoint 措施。

若经验样本不足，必须明确标记低置信度，而不是补猜。

## 8. Recalibration

每个 slice 完成后记录实际 weekly delta，对照计划区间并解释 variance。只调整后续调度估计：

- 超预算不能降低 acceptance；
- 低于预算不能主动扩大当前 task scope；
- upstream drift / review fixes 单独记录，避免误判 implementation baseline。
