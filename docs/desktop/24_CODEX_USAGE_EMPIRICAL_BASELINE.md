# CCB Desktop Codex Empirical Usage Baseline

> 状态：CURRENT telemetry / handoff document  
> 更新时间：2026-09-26
> 适用：Project → Codex 任务预算、模型/Thinking 选择、后续会话引继  
> 当前有稳定实测记录的模型：**GPT-5.6 Sol**  
> Thinking 档位：**High / Medium / Low**

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
当前重置周期：        100% → 25%     = 0.75 weekly

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
| Phase 3B2 transfer core | bounded implementation / pure transfer extraction | 13m55s | 27% | 4% |
| Phase 3C1 Character materialization | filesystem / materialization implementation | 30m50s | 56% | 9% |
| Phase 3C1 R1 | focused durable-delete repair | 4m57s | 14% | 2% |
| Phase 3C2 shared ST/classifier | shared parser/mapper/classifier extraction | 16m42s | 34% | 5% |
| Phase 3P Prompt ownership closure | Prompt ownership implementation | 18m03s | 41% | 6% |
| Phase 3D typed transfer | Desktop typed transfer / PNG implementation | 24m46s | 61% | 10% |
| Phase 3F initial | Android ↔ Desktop interoperability / device environment | 1h01m | 100% | 16% |
| Phase 3F continuation | API 36 interoperability / failure classification | 39m17s | 45% | 7% |
| P4-S1 chat foundation window 1 | shared chat Entity/repository extraction | 9m47s | 35% | 5% |
| P4-S1 chat foundation window 2 | continuation after quota reset | 9m41s | 16% | 3% |

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

### Phase 3B2 transfer-core sample

- Date：2026-09-24
- Model：GPT-5.6 Sol
- Thinking：High
- Task type：bounded implementation / pure transfer extraction
- Runtime：13m55s
- 5h：51% → 24%（= 27%）
- Weekly：50% → 46%（= 4%）
- Commit：`8a213233dcce9db1b58afef50f7ee2fa14c9e0ad`
- Validation：`FormatCardTransferServiceTest` **7 PASS**；`WorldBookTransferServiceTest` **10 PASS**；`WorldBookReusePolicyTest` **3 PASS**；sharedCore **19 suites / 152 tests / 0 failures**；desktopApp **19 suites / 232 tests / 0 failures**；Android `WorldBookMatchingOptionsTest` **1 suite / 6 tests / 0 failures**；sharedCore compile、Desktop compile、Android compile 与 `git diff --check` **PASS**
- Full Android JVM regression：按设计未运行；两个 production services 是 byte-identical moves，且 scoped Android caller test 已通过。
- Notes：runtime、5h 与 weekly 均落在既有 bounded-implementation empirical range 内；这是一个有效的 bounded shared-transfer extraction sample。

### Phase 3C1 Character materialization sample

- Date：2026-09-24
- Model：GPT-5.6 Sol
- Thinking：High
- Task type：filesystem / resource materialization implementation
- Runtime：30m50s
- 5h：100% → 44%（= 56%）
- Weekly：44% → 35%（= 9%）
- Commit：`783af9a10f6d95c618d7ffb81a7fa2949f65b9ab`
- Validation：sharedCore **21 suites / 170 tests / 0 failures**；desktopApp **22 suites / 238 tests / 0 failures**；Android JVM **181 suites / 1147 tests / 0 failures**；Desktop compile、Android compile 与 `git diff --check` **PASS**
- Notes：实现 shared Character transfer/materialization authority、Android/Desktop resource adapters、D-027 root-relative persistence 与 D-029 failure recovery；5h/weekly burn 高于旧 filesystem range，并触发 R1 strict-delete review repair。

### Phase 3C1 R1 sample

- Date：2026-09-24
- Model：GPT-5.6 Sol
- Thinking：High
- Task type：focused durable-delete safety repair
- Runtime：4m57s
- 5h：44% → 30%（= 14%）
- Weekly：35% → 33%（= 2%）
- Commit：`74f9af25270f2bf893a4bd3699613b0037e71403`
- Validation：复用并扩展 3C1 relevant regression；sharedCore **21 suites / 170 tests / 0 failures**、desktopApp **22 suites / 238 tests / 0 failures**、Android JVM **181 suites / 1147 tests / 0 failures**；Desktop/Android compile 与 `git diff --check` **PASS**
- Notes：严格 durable Character/WorldBook/FormatCard delete failure propagation；不改变正常成功语义。

### Phase 3C2 shared ST/classifier sample

- Date：2026-09-25
- Model：GPT-5.6 Sol
- Thinking：High
- Task type：shared parser / mapper / classifier extraction
- Runtime：16m42s
- 5h：100% → 66%（= 34%）
- Weekly：30% → 25%（= 5%）
- Commit：`d78d76df656fa3ce9fd309a6cbe52c6cfb379f30`
- Validation：parser **5 PASS**；mapper **5 PASS**；shared classifier **7 PASS**；Android facade **2 PASS**；sharedCore **24 suites / 187 tests / 0 failures**；desktopApp **22 suites / 238 tests / 0 failures**；Android JVM **181 suites / 1141 tests / 0 failures**；Desktop / Android compile 与 `git diff --check` **PASS**
- Notes：实际 weekly burn 为 5%，落在 3C2 Program Budget `0.05–0.07 weekly` envelope 内；Prompt、Package schema 与 user-facing ingress lifecycle 未进入本 slice。

### Phase 3P Prompt ownership closure sample

- Date：2026-09-25
- Model：GPT-5.6 Sol
- Thinking：High
- Task type：Prompt ownership implementation
- Runtime：18m03s
- 5h burn：41%
- Weekly burn：6%
- Commit：`f722703c33d8cd96728fc06ff617c9d7d79d9c7d`
- Notes：关闭 D-028 shared Prompt authority dependency；Prompt literal/runtime behavior 未改变。该条是 independent implementation sample。

### Phase 3D typed transfer implementation sample

- Date：2026-09-25
- Model：GPT-5.6 Sol
- Thinking：High
- Task type：Desktop typed transfer / PNG renderer implementation
- Runtime：24m46s
- 5h burn：61%
- Weekly burn：10%
- Commit：`d8987605e733b07a5deac1901ee049011d47d153`
- Validation：sharedCore **27 suites / 196 tests**；desktopApp **27 suites / 251 tests**；Android JVM **181 suites / 1142 tests**；均无 failure/error/skip；Desktop/Android compile 与 `git diff --check` **PASS**
- Notes：该条是 independent implementation sample；超出旧 medium-high planning range，不改变 acceptance 标准。

### Phase 3D implementation + packaged operational total（DERIVED）

该汇总只把 implementation 与 packaged gate 两条 independent sample 相加，不是新的独立 sample，不进入 task-type median：

```text
Total runtime:       32m10s
Total 5h burn:       82%
Total weekly burn:   14%

Implementation: 24m46s / 61% / 10%
Packaged gate:    7m24s / 21% /  4%
```

User manual acceptance 不使用 Codex quota。

### Phase 3F initial interoperability sample

- Date：2026-09-25
- Model：GPT-5.6 Sol
- Thinking：High
- Task type：Android ↔ Desktop interoperability / device environment
- Runtime：1h01m
- 5h：100% → 0%（= 100%）
- Weekly：100% → 84%（= 16%）
- Durable checkpoint：`717ec1a473660b5d186a4241778552bc6f12a80b`
- Notes：完成可恢复的 3F test checkpoint 后因 5h quota 中断；这是 independent sample。

### Phase 3F continuation sample

- Date：2026-09-25
- Model：GPT-5.6 Sol
- Thinking：High
- Task type：API 36 interoperability / failure classification
- Runtime：39m17s
- 5h：100% → 55%（= 45%）
- Weekly：84% → 77%（= 7%）
- Validation：API 36 targeted `Phase3FAndroidInteropTest` **1/1 PASS，0 failed，0 skipped**；persistent environment 建立；known unrelated failures 完成分类
- Notes：这是 quota reset 后的独立 continuation sample。

### Phase 3F pre-finalization total（DERIVED）

该汇总只把 initial 与 continuation 两条 independent sample 相加，不是第三条独立 sample，不进入 task-type median：

```text
Runtime: 1h40m17s
5h burn: 145% across two reset windows
Weekly burn: 23%
```

Program Budget 为 **0.05–0.08 weekly**，实际为 **0.23 weekly**；variance **+0.15 至 +0.18 weekly**，约为 planned upper bound 的 **2.9x**。该偏差用于后续估算校准，不改变验收标准，也不包含本次 docs finalization 的未记录 telemetry。

### Phase 3C1 complete slice total（derived summary）

该汇总由 implementation、R1 与 finalization 三个 task sample 相加得到，不是第四个独立 task sample，不进入 task-type median：

```text
Total runtime:       42m50s
Total 5h burn:       88%
Total weekly burn:   16%

Implementation: 30m50s / 56% / 9%
R1:              4m57s / 14% / 2%
Finalization:    7m03s / 18% / 5%
```

### High 的经验统计

**此前复杂 implementation 基准样本**（C1、C2-A/B/C、D1、D2、D3、root-switch initial；8 次；不含随后追加的 3B1 / 3B2 / 3C1 / 3C2 / 3P / 3D slice-specific samples）：

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
filesystem / transaction / concurrency:     约 30–60% 5h / 5–10% weekly
\`\`\`

这只是 planning range，不是 acceptance 上限。

**High focused repair**（D2-R1、root-switch R1、Phase 3C1 R1；3 次）：

\`\`\`text
Runtime:  4m57s – 9m44s
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
| Phase 3B1 finalization | docs + ff-only integration | 4m15s | 8% | 1% |
| Phase 3B2 finalization | docs + ff-only integration | 5m05s | 11% | 2% |
| Phase 3C1 finalization | docs + ff-only integration | 7m03s | 18% | 5% |
| Phase 3C2 finalization | docs + ff-only integration | 6m38s | 16% | 3% |
| Phase 3D packaged gate | package-only acceptance build | 7m24s | 21% | 4% |
| Phase 3F integration + Phase 3 docs finalization | docs + ff-only integration | 5m35s | 13% | 2% |
| Editor Reference Pack adoption | docs-only reference adoption | 2m48s | 7% | 1% |
| P4-S1 reviewed integration + Phase 4 docs finalization | docs + reviewed integration | 7m43s | 24% | 4% |
| P4-S2 / 4A2 remainder | bounded shared-authority implementation | 6m53s | 15% | 2% |
| P4-S3 / 4B1 | bounded pure-authority move | 7m13s | 18% | 3% |

### Editor Reference Pack adoption

- Model：GPT-5.6 Sol
- Thinking：Medium
- Runtime：2m48s
- 5h：42% → 35%（= 7%）
- Weekly：75% → 74%（= 1%）
- Commit：`7d9f049e99a9b8e0c266f165704d67974433501e`

### P4-S1 Shared Chat State Foundation

原始预测保留，不做事后修改：

```text
Model / Thinking: GPT-5.6 Sol / High
Runtime:          20–30m
5h:               35–50%
Weekly:           6–8%
Program Budget:   0.07–0.10 weekly
```

Independent execution windows：

```text
Window 1: 9m47s / 5h 35% → 0% (=35%) / weekly 74% → 69% (=5%) / quota interrupted
Window 2: 9m41s / 5h 100% → 84% (=16%) / weekly 69% → 66% (=3%)
```

Derived completed-task total（不是第三个独立 sample）：

```text
Runtime: 19m28s
5h:      51% across two reset windows
Weekly:   8%
```

Variance：runtime 比预测下界少 32s；5h 比预测上界高 1pp；weekly 正好等于预测上界。实际 task 在完成前已识别为 **4A1 + partial 4A2**，比原本 intended 4A1-only slice 更宽；这解释实测范围，但不得回写原始预测。

### P4-S1 reviewed integration + Phase 4 docs finalization

Pre-run prediction 保持独立，不做事后修改：

```text
Runtime:        6–10m
5h:             10–18%
Weekly:         1–3%
Program Budget: 0.02–0.03 weekly
```

- Date：2026-09-26
- Task：P4-S1 reviewed integration + Phase 4 docs finalization
- Model：GPT-5.6 Sol
- Thinking：Medium
- Task type：docs + reviewed integration
- Runtime：7m43s
- 5h：84% → 60%（= 24%）
- Weekly：66% → 62%（= 4%）
- Result：integration successful；Project review found docs-only R1 requirement
- Commit：`4e83afcfd05a4674f21aaff99a3184ee912053e2`
- Variance：runtime 在预测范围内；5h 比预测上界高 **6pp**；weekly 比预测上界高 **1pp**。
- Notes：这是独立 integration/docs empirical sample；R1 文档修复不反向改写其 pre-run prediction，也不改写 Phase 4 Program Budget。

### P4-S2 / 4A2 remainder

冻结预测：

```text
Model / Thinking: GPT-5.6 Sol / Medium
Runtime:          8–14m
5h:               12–22%
Weekly:           2–4%
```

- Date：2026-09-26
- Task：P4-S2 — complete Phase 4A2 session creation authority
- Task type：bounded shared-authority implementation + Android/Desktop wiring
- Runtime：6m53s
- 5h：47% → 32%（= 15%）
- Weekly：60% → 58%（= 2%）
- Implementation：`bfcd37e2f1f316ae60f733c0146846f66a4f76b4`
- Project review：**PASS**
- Validation：focused shared 8 PASS；Desktop integration 1 PASS；sharedCore 39 suites / 243 tests；desktopApp 29 suites / 255 tests；Android JVM 170 suites / 1108 tests；全部 0 failures/errors/skipped；Desktop/Android compile + `git diff --check` PASS。
- Variance：runtime 比预测下界快 **1m07s**；5h 在预测范围内；weekly 等于预测下沿。
- Notes：authoritative `CharacterSessionService` moved to sharedCore；Android warning semantics preserved through narrow callback；Desktop uses the same shared ChatRepository/service authority；no Prompt/Package/schema/4B changes。

### P4-S3 / 4B1

冻结预测：

```text
Model / Thinking: GPT-5.6 Sol / Medium
Runtime:          10–16m
5h:               20–30%
Weekly:           3–4%
4B1 Program Budget: 0.02–0.04 weekly
```

- Date：2026-09-26
- Task：P4-S3 / 4B1 — Shared Context + WorldBook Engine Core
- Task type：bounded pure-authority move + shared contract-test migration
- Runtime：7m13s
- 5h：32% → 14%（= 18%）
- Weekly：58% → 55%（= 3%）
- Implementation：`d468f82529ec6e9fa24363e07d1e7fafe6c9ecf0`
- Project review：**PASS**
- Production：`PlaceholderRenderer`、`ContextWindowManager`、`WorldBookEngine`、`WorldBookScanContext` four byte-identical moves into sharedCore
- Validation：focused shared **37 PASS**；sharedCore **43 suites / 280 tests**；desktopApp **29 suites / 255 tests**；Android JVM **167 suites / 1076 tests**；all 0 failures/errors/skipped；Desktop/Android compile + `git diff --check` PASS。
- Variance：runtime 比预测下界快 **2m47s**；5h 比预测下界低 **2pp**；weekly 等于预测下沿；4B1 actual weekly **0.03**，在 internal **0.02–0.04** envelope 内。
- Deferred by design：`ChatViewModel.buildWorldBookPrompt` request-planner extraction = 4B2；`Math.random` deterministic parity not asserted；no packaged/manual/device gate。

### Phase 3B1 finalization sample

- Date：2026-09-24
- Model：GPT-5.6 Sol
- Thinking：Medium
- Task type：docs + ff-only integration
- Runtime：4m15s
- 5h：59% → 51%（= 8%）
- Weekly：51% → 50%（= 1%）
- Docs finalization commit：`2a6f74ec3f064850a6ff725711ec93039410b9c6`
- Notes：复用 3B1 implementation regression evidence；未修改 production source；仅完成 fast-forward integration 与 docs finalization。

### Phase 3B1 complete slice total（derived summary）

该汇总由 implementation 与 finalization 两个 task sample 相加得到，不是第三个独立 task sample，不进入 task-type median：

```text
Total runtime:       21m51s
Total 5h burn:       49%
Total weekly burn:    8%

Implementation: 17m36s / 41% / 7%
Finalization:     4m15s /  8% / 1%
```

### Phase 3B2 finalization sample

- Date：2026-09-24
- Model：GPT-5.6 Sol
- Thinking：Medium
- Task type：docs + ff-only integration
- Runtime：5m05s
- 5h：24% → 13%（= 11%）
- Weekly：46% → 44%（= 2%）
- Docs finalization commit：`4cacc154458a3c10e0175499db1ebd5d288962f6`
- Notes：复用 3B2 implementation evidence；未修改 production source；仅完成 fast-forward integration 与 docs finalization。

### Phase 3B2 complete slice total（derived summary）

该汇总由 implementation 与 finalization 两个 task sample 相加得到，不是第三个独立 task sample，不进入 task-type median：

```text
Total runtime:       19m00s
Total 5h burn:       38%
Total weekly burn:    6%

Implementation: 13m55s / 27% / 4%
Finalization:     5m05s / 11% / 2%
```

### Phase 3C1 finalization sample

- Date：2026-09-24
- Model：GPT-5.6 Sol
- Thinking：Medium
- Task type：milestone docs + ff-only integration
- Runtime：7m03s
- 5h：30% → 12%（= 18%）
- Weekly：35% → 30%（= 5%）
- Docs finalization commit：`4b775bf01fe63ff1d92f50cbcd64fcd3b25348ad`
- Notes：复用 3C1 implementation / R1 regression evidence；未修改 production source；完成 docs finalization 与 ff-only integration。

### Phase 3C2 finalization sample

- Date：2026-09-25
- Model：GPT-5.6 Sol
- Thinking：Medium
- Task type：docs + ff-only integration
- Runtime：6m38s
- 5h burn：16%
- Weekly burn：3%
- Notes：independent finalization sample；不得与 derived total 重复计数。

### Phase 3D packaged gate sample

- Date：2026-09-25
- Model：GPT-5.6 Sol
- Thinking：Medium
- Task type：package-only acceptance build
- Runtime：7m24s
- 5h burn：21%
- Weekly burn：4%
- Commit：无 production commit；packaged reviewed `d8987605e733b07a5deac1901ee049011d47d153`
- Validation：`:desktopApp:createDistributable` 与 packaged launch smoke **PASS**
- Notes：independent packaged-gate sample；manual acceptance 不使用 Codex quota。

### Phase 3F integration + Phase 3 docs finalization

- Date：2026-09-25
- Task：Phase 3F integration + Phase 3 docs finalization
- Model：GPT-5.6 Sol
- Thinking：Medium
- Task type：docs + ff-only integration
- Runtime：5m35s
- 5h：55% → 42%（= 13%）
- Weekly：77% → 75%（= 2%）
- Result：PASS
- Docs finalization commit：`8c764d5157eb27e3fbd1400b13c4c2efb8afbc5e`
- Notes：ff-only integration of reviewed Phase 3F checkpoint；docs-only Phase 3 closure；no tests rerun；no production source changed；this is an independent empirical sample。

### Medium 的经验统计

全部 20 个完整样本：

\`\`\`text
Runtime median: ~6m31s
5h median:      13.5%
Weekly median:  2%
\`\`\`

**Docs / docs+integration 样本**（13 次）：

\`\`\`text
Runtime:  4m15s – 9m56s
Median:   ~6m38s

5h:       4% – 24%
Median:   14%

Weekly:   1% – 5%
Median:   2%
\`\`\`

注意：P4-S1 reviewed integration 的 **24% / 4%** 是当前 5h 高端样本，Phase 3C1 finalization 的 **18% / 5%** 是当前 weekly 高端样本；它们都不应被当成普通 Markdown 修改的默认成本。

**Medium focused repair**（C2-R1 + root-switch R2）：

\`\`\`text
Runtime:  3m23s – 4m23s
5h:       7–9%
Weekly:   1%
\`\`\`

其中 root-switch R2 虽然 production 变更只是很小的 label-selection 修正，但任务还跑了完整 desktopApp regression 和 createDistributable，因此它反映的是“**小修 + 偏重验证**”成本，不是“一句话 UI 修改”的正常下限。

**Package-only 已观测样本**：

\`\`\`text
R1 packaged rebuild: 2m29s / 5% 5h / 1% weekly
3D packaged gate:    7m24s / 21% 5h / 4% weekly
\`\`\`

---

## 5. GPT-5.6 Sol / Low — 初始实测

当前只有 1 条完整 Low 样本，因此**不足以建立稳定 range、median 或默认预算**。本条用于证明机械 docs-only repair 可以在 Low 完成，不应外推到 production implementation、架构判断或高风险 review。

| 任务 | 类型 | Runtime | 5h | Weekly |
|---|---|---:|---:|---:|
| Phase 4 audit truth R1 | docs-only truth repair | 5m39s | 13% | 2% |

### Phase 4 audit truth R1

- Date：2026-09-26
- Task：Phase 4 docs truth repair
- Model：GPT-5.6 Sol
- Thinking：Low
- Task type：docs-only truth repair
- Runtime：5m39s
- 5h：60% → 47%（= 13%）
- Weekly：62% → 60%（= 2%）
- Commit：`31fc0b72aab9142f479865008c0060d82db4fe5a`
- Changed docs：`23_CODEX_BUDGET.md`、`24_CODEX_USAGE_EMPIRICAL_BASELINE.md`、`28_PHASE4_CONTRACT_AUDIT.md`
- Validation：production source unchanged；`git diff --check` **PASS**；Project post-review = **R2 DOCS TRUTH REPAIR REQUIRED**，因为 `28_PHASE4_CONTRACT_AUDIT.md` 仍丢失/改写了已批准的 Prompt logical-order 与 Phase 4/5 transport boundary；Project 随后直接以 approved draft 修复
- Prediction policy：Thinking 改为 Low 后，因为此前没有稳定 Low empirical sample，Project **没有补猜具体 5h/weekly 数字**；本条是第一条 Low empirical anchor。
- Notes：该 sample 不证明 Low 适合 production code、架构决策或高风险 semantic review；本次甚至说明即使是 Project-authored docs truth，Low apply 仍可能发生语义压缩，必须保留 Project post-review。最终 Project-side truth repair commit：`515d7c60f369d70783650dd8cf6d24e5c06435b1`。

---

## 6. 不应与完整样本混算的记录

以下信息曾被明确记录，但缺少“同一轮任务的 runtime + 5h + weekly + model/thinking”完整对应关系，因此保留为补充证据，不用于上面的统计中位数。

### upstream 1.4.0 audit + sync composite

\`\`\`text
5h:     97% → 59% = 38%
weekly:  8% →  2% = 6%
latest sync runtime recorded: 17m53s
\`\`\`

这里的 38% / 6% 是 composite 消耗，而 17m53s 只明确对应 latest sync，不能把三者当作同一原子任务样本。

### P4-S4 / 4B2 — quota-interrupted two-window record

冻结预测：

```text
Model / Thinking: GPT-5.6 Sol / Medium
Runtime:          10–18m
5h:               22–35%
Weekly:           3–4%
4B2 Program Budget: 0.03–0.04 weekly
```

实际：

```text
Window 1:
5h      14% → 0%   = 14%
Weekly  55% → 53%  = 2%
Result  quota interrupted after implementation + focused validation

Window 2:
5h      100% → 93% = 7%
Weekly  53% → 52%  = 1%
Result  full regression + commit/push complete

Derived total:
5h      21%
Weekly   3%
Runtime unavailable across the two windows
```

- Implementation：`8867424df3d53bd291b6b361e51aa5059257284e`
- Project review：**PASS**
- Validation：shared planner **14 PASS**；Desktop integration **1 PASS**；sharedCore **44 suites / 294 tests**；desktopApp **30 suites / 256 tests**；Android JVM **167 suites / 1076 tests**；all 0 failures/errors/skipped；Desktop/Android compile + `git diff --check` PASS。
- Variance：derived 5h 比预测下界低 **1pp**；weekly 命中预测下沿；4B2 actual weekly **0.03**，在 internal budget 内。
- Statistical handling：因缺少完整 runtime，此记录**不进入完整样本 runtime/5h/weekly median**，但 weekly/5h 可用于 slice/program budget reconciliation。
- 4B derived actual：4B1 3% + 4B2 3% = **6% weekly**，Program Budget 5–8%。

### 其他不完整记录

\`\`\`text
首次 Codex 执行：明确记录约 13% weekly，但缺少完整 5h/runtime 对应值。
1.4.1 final verification：约 14s，约 1% 5h；缺少完整 weekly/model-level 对应值。
Phase 3D integration-only：5h burn = 3%；weekly UI readings 出现 display/accounting discontinuity，无法可靠求 delta，记为 unavailable。不得把观察到的 UI movement 推导为负 usage。
\`\`\`

这些记录可用于项目总账或历史说明，但不应进入 task-type empirical median。

---

## 7. 当前任务类型预算基线

基于现有实测，当前建议用以下区间做 **第一版 planning estimate**。

| 任务类型 | 推荐默认 Thinking | 典型 Runtime | 典型 5h | 典型 Weekly | 备注 |
|---|---|---:|---:|---:|---|
| 纯 docs / CURRENT 更新 | Project direct 优先；必要时 Low/Medium | — | — | — | GitHub docs-only 可由 Project 直接写；仅需本地机械操作时优先用户人工执行；Low 当前只有 1 条样本，不建立稳定区间 |
| 小范围机械修正 / focused repair | Medium | 3–6m | 5–10% | ~1% | 不默认跑全套 regression |
| Package-only rebuild / gate | Medium | 2–8m | 5–21% | 1–4% | 应尽量在 Project review PASS 后再做；3D packaged gate 是当前高端样本 |
| focused platform spike | Medium | 3–6m | 4–10% | ~1% | 只回答明确 feasibility 问题 |
| 高风险合同审计 | High | 10–20m | 15–25% | 2–4% | 未来优先由 Project offload，Codex 仅在必须本地验证时做 |
| bounded implementation | High | 10–25m | 15–30% | 2–5% | contract 已清楚、影响面有限 |
| medium high-risk implementation | High | 20–35m | 25–40% | 4–6% | schema / lifecycle / migration 等 |
| filesystem / transaction / concurrency | High | 25–40m+ | 30–60% | 5–10% | 不因额度降低安全验证 |
| focused High repair | High | 5–10m | 13–16% | ~2% | 用于安全/事务语义修复 |

这些范围必须随着 Phase 3+ 新样本继续 recalibrate。

---

## 8. 预算/执行政策（继承必读）

### 8.1 工程边界优先

\`\`\`text
工程边界决定怎么拆，额度不决定架构怎么拆。
\`\`\`

必要工程一次做够；只在自然检查点拆分。不得为了省 quota 制造额外 transaction boundary、临时 API 或未来必须返工的框架。

### 8.2 验证按风险走

\`\`\`text
label / 文案 / pure helper
→ focused test + compile + diff-check

普通业务行为
→ focused + relevant suite

filesystem / transaction / lifecycle / concurrency
→ full relevant regression
\`\`\`

不要机械地因为任何一行 production diff 都跑整个 Desktop/Android regression。

### 8.3 Packaging 时机

除非 packaging 本身就是被测能力：

\`\`\`text
implementation
→ Project review
→ repair PASS（如需要）
→ createDistributable / packaged manual acceptance
\`\`\`

避免 review 后立刻使旧 artifact 作废、再次 rebuild。

### 8.4 Docs 时机

优先把 CURRENT docs / roadmap / parity 更新合并到自然 milestone finalization。

不要为了每一个微型 sub-slice 单独开启 docs-only Codex run。Project 应先完成内容审查/起草；具备 GitHub 写权限时，纯 docs-only remote commit 可由 Project 直接完成。仅需 fetch/pull、已知 checkout、文件复制/压缩、Project Sources 上传等机械本地动作时，优先由用户按 Project 给出的精确步骤人工执行。只有需要真实工程实现、构建验证或复杂 Git reconciliation 时才默认使用 Codex。

### 8.5 Project offload

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
- GitHub docs-only CURRENT/audit/budget/source-map 修改与提交（当 Project 具备写权限）
- Project Sources replacement 判断与文件准备

Codex 保留：

- production Kotlin/Gradle 修改
- 本地 compile/test
- Windows filesystem/lifecycle 验证
- jpackage / EXE / installer
- 实际 Android ↔ Desktop interoperability execution
- production commit/push/integration
- 需要复杂 Git reconciliation/conflict resolution 的本地写操作

原则：

\`\`\`text
Project 决定“应该是什么”；Codex 证明“代码真的做到了”。
\`\`\`

---

## 9. 后续记录模板

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

## 10. 与 Program Budget 的关系

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