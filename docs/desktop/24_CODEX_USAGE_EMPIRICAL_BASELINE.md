# CCB Desktop Codex Empirical Usage Baseline

> 状态：CURRENT telemetry / handoff document  
> 更新时间：2026-09-24  
> 适用：Project → Codex 任务预算、模型/Thinking 选择、后续会话引继  
> 当前有稳定实测记录的模型：**GPT-5.6 Sol**  
> Thinking 档位：**High / Medium**

## 1. 目的

本文只记录已经明确观测到的 Codex 消耗，不用理论 token 价格或分钟数反推额度。

每条完整样本至少同时具备：

\`\`\`text
模型 / Thinking
任务或任务类型
wall-clock runtime
5-hour allowance before → after 或明确 delta
weekly allowance before → after 或明确 delta
\`\`\`

额度来自用户在产品 UI 中的实际读数。未来模型、计费/配额算法、产品策略变化后，本文只能作为 **GPT-5.6 Sol 当前时期的经验基线**，不能当永久常数。

---

## 2. 项目级累计锚点

用户确认：CCB Desktop 项目期间没有用 Codex 做其他项目，因此周额度周期可作为项目总消耗的账户级锚点。

\`\`\`text
项目开始时 weekly 剩余：40% → 0%      = 0.40 weekly
下一次重置：          100% → 0%      = 1.00 weekly
当前重置周期：        100% → 51%     = 0.49 weekly

Phase 0 → Phase 2 COMPLETE 累计：约 1.82 weekly
\`\`\`

这比逐任务日志求和更适合作为整个项目的累计预算真值；逐任务表主要用于估算“某类任务大概会烧多少”。

---

## 3. GPT-5.6 Sol / High — 完整实测

| 任务 | 类型 | Runtime | 5h | Weekly |
|---|---|---:|---:|---:|
| C1 concurrency/storage | filesystem / concurrency implementation | 35m07s | 53% | 8% |
| C2-A ownership primitive | ownership implementation | 8m41s | 13% | 2% |
| C2-B snapshot/restore | snapshot / restore implementation | 16m52s | 32% | 5% |
| C2-C lifecycle/subprocess | lifecycle / subprocess implementation | 16m10s | 22% | 3% |
| D0 migration audit | high-risk contract audit | 13m26s | 21% | 3% |
| D1 migration safety | migration safety implementation | 26m02s | 31% | 5% |
| D2 materialization | migration materialization implementation | 24m08s | 29% | 5% |
| upstream 1.4.1 sync implementation | upstream sync implementation | 13m34s | 26% | 4% |
| D2-R1 | focused safety repair | 9m44s | 16% | 2% |
| D3 orchestration | transaction / migration orchestration | 27m44s | 39% | 6% |
| root-switch initial | UI + transaction integration | 30m31s | 42% | 6% |
| root-switch R1 | focused cancellation-safety repair | 6m26s | 13% | 2% |
| Phase 3B1 shared contract core | medium high-risk implementation / shared contract extraction | 17m36s | 41% | 7% |

### Phase 3B1 extraction-specific sample

- Date：2026-09-24
- Task：Phase 3B1 Shared Entity / Package Contract Core
- Model：GPT-5.6 Sol
- Thinking：High
- Task type：medium high-risk implementation / shared contract extraction
- Runtime：17m36s
- 5h：100% → 59%（= 41%）
- Weekly：58% → 51%（= 7%）
- Commit：`367a8ce7432bafbb926a176e23886c765b12a8f7`
- Validation：focused shared contract/repository **6 suites / 21 tests**；sharedCore **17 suites / 135 tests / 0 failures**；desktopApp **19 suites / 232 tests / 0 failures**；Android JVM **182 suites / 1150 tests / 0 failures**；Desktop compile、Android compile 与 `git diff --check` **PASS**
- Notes：runtime 低于此前 20–35m medium-high-risk estimate；5h burn 略高于此前 25–40% empirical range；weekly burn 高于此前 4–6% empirical range，但仍在 3B1 Program Budget envelope 8–12% weekly 内。该记录作为 extraction-specific empirical sample，不改写既有样本。

### High 的经验统计

**此前复杂 implementation 基准样本**（C1、C2-A/B/C、D1、D2、D3、root-switch initial；8 次；不含随后追加的 3B1 extraction-specific sample）：

\`\`\`text
Runtime:  8m41s – 35m07s
Median:   ~25m05s

5h:       13% – 53%
Median:   31.5%
Mean:     32.6%

Weekly:   2% – 8%
Median:   5%
Mean:     5%
\`\`\`

因此对 GPT-5.6 Sol / High 的当前实用估计：

\`\`\`text
focused / bounded high-risk implementation: 约 15–30% 5h / 2–5% weekly
medium high-risk implementation:            约 25–40% 5h / 4–6% weekly
filesystem / transaction / concurrency:     约 30–55% 5h / 5–8% weekly
\`\`\`

这只是 planning range，不是 acceptance 上限。

**High focused repair**（D2-R1、root-switch R1；2 次）：

\`\`\`text
Runtime:  6m26s – 9m44s
5h:       13–16%
Weekly:   2%
\`\`\`

---

## 4. GPT-5.6 Sol / Medium — 完整实测

| 任务 | 类型 | Runtime | 5h | Weekly |
|---|---|---:|---:|---:|
| C1 docs | docs | 6m47s | 15% | 2% |
| FileLock spike | focused platform spike | 3m59s | 4% | 1% |
| C2-R1 | focused repair | 3m23s | 7% | 1% |
| C2 docs | docs | 7m24s | 16% | 3% |
| upstream 1.4.0 docs | docs | 9m56s | 14% | 1% |
| D1/D2 docs integration | docs + integration | 6m23s | 4% | 2% |
| D3 docs finalization | docs finalization | 5m46s | 12% | 1% |
| R1 packaged rebuild | package-only rebuild | 2m29s | 5% | 1% |
| root-switch R2 | tiny UI semantics repair + regression/package | 4m23s | 9% | 1% |
| Phase 2 finalization | docs + ff-only integration | 8m21s | 19% | 3% |

### Medium 的经验统计

全部 10 个完整样本：

\`\`\`text
Runtime median: ~6m05s
5h median:      10.5%
Weekly median:  1%
\`\`\`

**Docs / docs+integration 样本**（6 次）：

\`\`\`text
Runtime:  5m46s – 9m56s
Median:   ~7m06s

5h:       4% – 19%
Median:   14.5%

Weekly:   1% – 3%
Median:   2%
\`\`\`

注意：Phase 2 finalization 的 19% / 3% 属于明显偏高样本，不应把它当成普通 Markdown 修改的默认成本。

**Medium focused repair**（C2-R1 + root-switch R2）：

\`\`\`text
Runtime:  3m23s – 4m23s
5h:       7–9%
Weekly:   1%
\`\`\`

其中 root-switch R2 虽然 production 变更只是很小的 label-selection 修正，但任务还跑了完整 desktopApp regression 和 createDistributable，因此它反映的是“**小修 + 偏重验证**”成本，不是“一句话 UI 修改”的正常下限。

**Package-only 已观测样本**：

\`\`\`text
R1 packaged rebuild
Runtime: 2m29s
5h:      5%
Weekly:  1%
\`\`\`

---

## 5. 不应与完整样本混算的记录

以下信息曾被明确记录，但缺少“同一轮任务的 runtime + 5h + weekly + model/thinking”完整对应关系，因此保留为补充证据，不用于上面的统计中位数。

### upstream 1.4.0 audit + sync composite

\`\`\`text
5h:     97% → 59% = 38%
weekly:  8% →  2% = 6%
latest sync runtime recorded: 17m53s
\`\`\`

这里的 38% / 6% 是 composite 消耗，而 17m53s 只明确对应 latest sync，不能把三者当作同一原子任务样本。

### 其他不完整记录

\`\`\`text
首次 Codex 执行：明确记录约 13% weekly，但缺少完整 5h/runtime 对应值。
1.4.1 final verification：约 14s，约 1% 5h；缺少完整 weekly/model-level 对应值。
\`\`\`

这些记录可用于项目总账或历史说明，但不应进入 task-type empirical median。

---

## 6. 当前任务类型预算基线

基于现有实测，当前建议用以下区间做 **第一版 planning estimate**。

| 任务类型 | 推荐默认 Thinking | 典型 Runtime | 典型 5h | 典型 Weekly | 备注 |
|---|---|---:|---:|---:|---|
| 纯 docs / CURRENT 更新 | Medium | 6–10m | 8–16% | 1–3% | 尽量在自然 milestone 合并 |
| 小范围机械修正 / focused repair | Medium | 3–6m | 5–10% | ~1% | 不默认跑全套 regression |
| Package-only rebuild | Medium | 2–5m | ~5% | ~1% | 应尽量在 Project review PASS 后再做 |
| focused platform spike | Medium | 3–6m | 4–10% | ~1% | 只回答明确 feasibility 问题 |
| 高风险合同审计 | High | 10–20m | 15–25% | 2–4% | 未来优先由 Project offload，Codex 仅在必须本地验证时做 |
| bounded implementation | High | 10–25m | 15–30% | 2–5% | contract 已清楚、影响面有限 |
| medium high-risk implementation | High | 20–35m | 25–40% | 4–6% | schema / lifecycle / migration 等 |
| filesystem / transaction / concurrency | High | 25–40m+ | 30–55% | 5–8% | 不因额度降低安全验证 |
| focused High repair | High | 6–10m | 13–16% | ~2% | 用于安全/事务语义修复 |

这些范围必须随着 Phase 3+ 新样本继续 recalibrate。

---

## 7. 预算/执行政策（继承必读）

### 7.1 工程边界优先

\`\`\`text
工程边界决定怎么拆，额度不决定架构怎么拆。
\`\`\`

必要工程一次做够；只在自然检查点拆分。不得为了省 quota 制造额外 transaction boundary、临时 API 或未来必须返工的框架。

### 7.2 验证按风险走

\`\`\`text
label / 文案 / pure helper
→ focused test + compile + diff-check

普通业务行为
→ focused + relevant suite

filesystem / transaction / lifecycle / concurrency
→ full relevant regression
\`\`\`

不要机械地因为任何一行 production diff 都跑整个 Desktop/Android regression。

### 7.3 Packaging 时机

除非 packaging 本身就是被测能力：

\`\`\`text
implementation
→ Project review
→ repair PASS（如需要）
→ createDistributable / packaged manual acceptance
\`\`\`

避免 review 后立刻使旧 artifact 作废、再次 rebuild。

### 7.4 Docs 时机

优先把 CURRENT docs / roadmap / parity 更新合并到自然 milestone finalization。

不要为了每一个微型 sub-slice 单独开启 docs-only Codex run；Project 可先完成内容审查/起草，Codex 只做必要 apply、diff-check、commit、ff-only integration。

### 7.5 Project offload

以下工作未来优先由 ChatGPT Project 完成，不应默认消耗 Codex weekly：

- upstream source archaeology
- Package / Entity / Prompt contract audit
- dependency/extraction map
- architecture slicing
- test oracle / fixture design
- acceptance checklist
- upstream drift classification
- GitHub diff review
- final docs 内容起草

Codex 保留：

- production Kotlin/Gradle 修改
- 本地 compile/test
- Windows filesystem/lifecycle 验证
- jpackage / EXE / installer
- 实际 Android ↔ Desktop interoperability execution
- commit/push/integration

原则：

\`\`\`text
Project 决定“应该是什么”；Codex 证明“代码真的做到了”。
\`\`\`

---

## 8. 后续记录模板

每个 Codex 任务完成后追加一条：

\`\`\`markdown
### <task / slice>

- Date:
- Model: GPT-5.6 Sol
- Thinking: Medium / High
- Task type:
- Runtime:
- 5h: <before>% → <after>% (= <delta>%)
- Weekly: <before>% → <after>% (= <delta>%)
- Commit:
- Validation:
- Notes / variance reason:
\`\`\`

如果只有部分数据，不要补猜；放入“不完整记录”而不是完整样本表。

---

## 9. 与 Program Budget 的关系

本文负责 **empirical burn-rate telemetry**。

项目总预算与未来 Phase envelope 应继续由：

\`docs/desktop/23_CODEX_BUDGET.md\`

负责。

二者分工：

\`\`\`text
23_CODEX_BUDGET.md
= 未来准备花多少 / Phase planning

24_CODEX_USAGE_EMPIRICAL_BASELINE.md
= 过去实际花了多少 / 某类任务实测成本
\`\`\`

预算不能覆盖 correctness、data safety、parity 或 required validation。
