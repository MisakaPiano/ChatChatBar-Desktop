# CCB Desktop Phase 5 Contract Audit

更新时间：2026-09-27

## 1. Status and authority

**Phase 5 — Model Runtime + Real Chat：ACTIVE**

本文件记录已经由 Project 完成的 Phase 5 contract audit 与 implementation slicing，不新建第二套 architecture。authority order 继续遵循 `00_PROJECT_SOURCE_MAP.md`：formal baseline upstream source → current fork code → CURRENT docs → Project instructions。

Formal compatible upstream baseline：

`ChatChatBar 1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`

Observed upstream：

`354f15166d8bc0462cb87d62a0ba4613794560a3` — **HIGH / NO SYNC**

该 observed drift 未触及本次 audited P5-S1 Model value/repository contract assumptions；P5-S1 不构成对 observed-but-unsynced upstream 的 compatibility claim。

## 2. Target vertical

Phase 5 的目标纵向为：

```text
import card
→ create session
→ greeting
→ user message
→ WorldBook
→ final shared logical request
→ provider transport
→ stream response
→ persist assistant response
→ restart
→ continue
```

Phase 5 Alpha 是 text real-chat vertical；不以完整 RAG/Memory/Image/Voice/AI authoring 或完整 Phase 6 UX 作为本阶段验收范围。

## 3. Layer boundary

以下四层必须保持分离：

1. Package transfer schema
2. persisted Entity
3. logical Prompt / `ChatApiMessage`
4. provider-specific serialized transport request

Phase 4 已拥有 authoritative logical request boundary。Phase 5 消费 shared `ChatApiMessage` 与 logical cache authority，不重建 Prompt ordering。provider transport 可以按 provider/local-HTTP contract 调整 serialized representation，但不得修改 logical Prompt authority 或 Prompt literals。

## 4. Phase 5 slices

### P5-S1 / 5A1 — Shared Model Value + Repository Foundation

状态：**COMPLETE / PROJECT REVIEW PASS / NOT YET INTEGRATED TO desktop**

Reviewed implementation：`dd68b714c58254b7ef994b106dafebf649623bf6`

### P5-S2 / 5A2 — Shared Settings + Effective Model Resolution

状态：**PENDING**

### P5-S3 / 5S — Desktop SecretStore + secure Model/Settings adapters

状态：**PENDING**

### P5-S4 / 5B — Shared Provider Transport Core + model discovery

状态：**PENDING**

### P5-S5 / 5C — Desktop Real Chat Runtime

状态：**PENDING**

### P5-S6 / 5D — Desktop TaskRuntime + transport diagnostics + narrow Alpha harness/UI

状态：**PENDING**

### P5-S7 / 5E — Alpha real-chat vertical acceptance

状态：**PENDING**

P5-S2 是 P5-S1 经 Project 授权集成后的下一 implementation slice；不得从本文件推断已开始。

## 5. Program Budget

| Slice | Program Budget weekly |
|---|---:|
| S1 | 6–9% |
| S2 | 5–8% |
| S3 | 8–12% |
| S4 | 10–15% |
| S5 | 10–15% |
| S6 | 7–11% |
| S7 | 10–18% |
| **Total planning envelope** | **56–88%** |

这些数字是 Project audit planning envelopes，不是 guarantees、acceptance 上限或 observed actuals。P5-S1 total actual burn 因首轮决策边界前 telemetry 未捕获而为 **unknown / incomplete telemetry**。

## 6. P5-S1 reviewed implementation and D-034 storage-key boundary

Shared authority now includes：

- `ModelConfig`
- `EmbeddingConfig`
- `ParamValue`
- `ModelTemplate`
- `OutputTokenParameter`
- required preset model/catalog values
- `ModelRepository`
- `ModelStorageKeyPolicy`

Android compatibility：

- package names unchanged；
- Android 已有 `sharedCore` dependency；
- default `ModelRepository(storage)` 继续使用 identity storage-key policy；
- 不需要 Android facade；
- upstream logical/serialized Model semantics 与 Android physical filenames 保持不变。

Windows persistence boundary：

- `ModelConfig.id` 是唯一 authoritative logical ID，serialized value 不变；
- `PRESET_MODEL_ID_PREFIX` 继续为 `preset:`；
- Desktop Windows-safe physical key 为 `ccb-model-v1-<lowercase UTF-8 hex>`；
- logical `preset:vision` 对应 physical `ccb-model-v1-7072657365743a766973696f6e`；
- repository get/save/delete、preset restore/upsert 与 legacy planning-model migration 使用 injected policy；
- `visionModelId`、settings references、source preset metadata 与其他 relationships 始终保留 logical ID；
- global `JsonFileStorage` filename behavior 未改变。

P5-S1 前不存在 Desktop `ModelRepository` production wiring 或 Desktop persisted-model user data，因此不需要 Desktop model migration。snapshot/root-switch 对 Windows-safe files 继续按普通 raw payload 处理；本决定不创建 Android raw app-data-root → Desktop migration protocol。

## 7. D-031 credential boundary

Upstream/runtime `ModelConfig.apiKey` contract 保持 authoritative。P5-S1 没有实现 Desktop SecretStore，也没有新增把真实 key/token 写入 ordinary plaintext JSON 的 Desktop production path。

后续 P5-S3 必须建立 secure credential persistence/hydration boundary；Windows implementation 使用 OS protection。SecretStore unavailable 时不得 silently fall back 到 plaintext persistence。runtime 可以接收 hydrated `ModelConfig.apiKey`，从而保持 resolver/provider semantics 与 upstream compatible。

## 8. D-032 provider transport boundary

Phase 5 将建立一个 JVM-shared provider transport / serialization / SSE authority；Android 可为 Android/non-main-chat callers 保留 thin facade。不得为了移动 `StreamingChatService` 而迁移或复制 auxiliary Prompt families。

Main-chat transport 只消费 Phase 4 shared logical messages。provider-specific serialization 可以调整 transport representation，但不能改写 `MainChatPromptAuthority`、logical order 或 Prompt literals。

## 9. D-033 Phase 5 Alpha scope

包含：

- normal text send
- blank continue
- streaming content/reasoning
- user stop/cancellation
- model resolution/auth
- local HTTP behavior
- thinking controls
- assistant persistence
- restart and continue
- required Desktop task lifetime

明确不包含：

- RAG/Embedding feature implementation（current request semantics 的必要 dependency 除外）
- Long-Term Memory feature implementation
- format repair / AI authoring
- NovelAI/image
- Fish/audio
- full Phase 6 model/chat production UX

## 10. P5-S1 verification

- focused model/repository：**16/16 PASS**
- `preset:vision` persistence/reopen/lookup/delete：**PASS**
- preset idempotence/logical relationships：**PASS**
- identity + collision-safety policy tests：**PASS**
- sharedCore：**56 suites / 374 tests PASS**
- desktopApp：**33 suites / 272 tests PASS**
- Android JVM：**162 suites / 1023 tests PASS**
- Desktop compile：**PASS**
- Android compile：**PASS**
- `git diff --check`：**PASS**
- Prompt literals：**UNCHANGED**
- Desktop plaintext-secret production persistence：**NOT ADDED**

未执行 real provider/network/manual runtime test；P5-S1 没有 user-facing Model UI/runtime wiring，因此不要求 manual test。

## 11. Deferred after P5-S1

P5-S1 未实现：

- Desktop model runtime wiring
- `AppSettings` extraction/resolution
- `EffectiveModelResolver`
- Desktop SecretStore
- `ModelDiscoveryService`
- `ProxyAwareClient`
- `StreamingChatService` / provider transport
- SSE
- real chat runtime
- Desktop TaskRuntime

下一步只有在 Project 完成 feature integration/control-point update 后才进入 P5-S2。
