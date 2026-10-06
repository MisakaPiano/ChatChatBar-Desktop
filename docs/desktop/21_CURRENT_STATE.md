# CCB Desktop Current State

更新时间：2026-10-06

## 当前阶段

**Phase 6 — CLOSED / INTEGRATED / ACCEPTED**。Phase 0–5 既有 accepted 状态不变。**Phase 7 — READY FOR PROJECT PHASE-7 R2 REVIEW / NOT ACCEPTED / NOT MERGED**。

用户追加的 `47_PHASE7_R2_REAUDIT_AMENDMENT.md` 与 `46` 全部 GAP 已实现并完成验证。`b88d408` 的 959-test / package / smoke 仅为增补前历史 checkpoint；最终源码为 `1843dac42bcbcc8aac4fc7a9f46450946edc94d2`，之后仅 docs reconciliation。

Project 已通过 pre-R2 implementation 与 final-source regression，但 consolidated user manual acceptance 因 Desktop UX / discoverability 失败。R2 按 `40_PHASE7_R2_UX_CONTRACT.md` 与增补 `47` 完成；`38_PHASE7_FINAL_REVIEW.md` 为 pre-R2 证据，当前实现/验证/分发包以 `44_PHASE7_R2_REVIEW.md` 为准。R2 Project review / 用户 manual acceptance 待执行，不代签 PASS。

- 最终 R2 production/test checkpoint：`feature/phase7-image-novelai @ 1843dac42bcbcc8aac4fc7a9f46450946edc94d2`。保留既有 Studio/背景库/角色/PNG 修复；补齐多附件与消息图片编辑删除、手动要求/自动消息任务关联、账户、History、直接 Guidance、reverse 流式内容与 reasoning。
- **FINAL-SOURCE FULL REGRESSION PASS**：Desktop **107 suites / 969 tests**；sharedCore **103 / 685**；Android affected **2 / 9**，全部 0 failure/error/skip。focused Desktop **6 / 68**、Android **2 / 9**，最终 selection/scene **2 / 11** PASS；三模块 compile、diff-check、新隔离包与 launch smoke PASS。shared history/account 为 formal baseline 的精确中立提取，Android 仅跨模块空值访问适配。
- R2 evidence：`44_PHASE7_R2_REVIEW.md`；manual checklist：`43_PHASE7_R2_MANUAL_ACCEPTANCE.md`。最终验收包：`app/desktopApp/build/phase7-r2-reaudit-distribution/compose/binaries/main/app/ChatChatBarDesktop/ChatChatBarDesktop.exe`。独立空 profile 启动/正常关闭、父子进程 exit 0、stdout/stderr 0 bytes。
- live desktop 仍为 `b3ecd41267906526e7b603972f7388e59c90648d`。NovelAI real generation 保持 `1/8`；R2 新增 **0**，未消耗剩余 fuse。
- Prompt narrow compatibility exception：仅 official-upstream `ace632c...` 的三个已授权 safety literals；其余 formal baseline compatibility 不变，NovelAI Prompt zero-drift。

- accepted production feature：`feature/phase6-s9-desktop-ux @ 86be0b0ec21aab7a8f15553c0b696b253738917f`
- integration 前：`desktop @ 5850fe28d233fb1b64a71b35e1e5f5d8d44db21d`
- finalization：accepted feature + 本次 docs-only closeout commit，经 **FF-ONLY** 集成到 `desktop`；最终 SHA 以 Git `desktop` / `origin/desktop` 指向本次文档提交为准，不能把 production acceptance SHA 与 docs SHA 混为一谈。
- Phase-6 Project final review 与 final manual acceptance：**PASS**；旧 S4 frozen-blocker / NO MERGE / NO P6-S5 控制点已被当时明确授权取代。
- formal validated baseline：`1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`；master 已 mirror upstream **1.4.4 @ 550409689df8c51f459fb50b4e04c8ac2fa4bf35**，但 compatibility **NOT VALIDATED**。
- `sync/1.4.4 @ 9b6378dbb595dd2f3ff5143a7a8e46653c99e721` 已 parked，未合入 Desktop、未提升 baseline；不阻塞 Phase 7。
- Phase-6 docs close、post-close cleanup 与 reproducibility gate 均已完成；Phase 7 当前 feature 状态见上方与最终证据。

以下 Phase 0–6 slice 描述、旧测试数量及当时的“下一步/尚未实现”均为历史记录，不覆盖本页当前阶段与 Phase-7 最终证据。

Phase 2 infrastructure 完成不代表 Phase 3 业务 Entity / Package / transfer 已达到 parity。

- Phase 0：**COMPLETE**
- Phase 1：**COMPLETE**
- Phase 2：**COMPLETE**
- Phase 2A：**COMPLETE**
- Phase 2A1：**COMPLETE**
- Phase 2A2：**COMPLETE**
- Phase 2B：**COMPLETE**
- Phase 2B1：**COMPLETE**
- Phase 2B2：**COMPLETE**
- Phase 2B3：**COMPLETE**
- Phase 2B3A：**COMPLETE**
- Phase 2B3B：**COMPLETE**
- Phase 2B3C：**COMPLETE**
- Phase 2B3D：**COMPLETE**
- Phase 2B3E：**COMPLETE**
- Phase 2B4：**COMPLETE**
- Phase 2B4A：**COMPLETE**
- Phase 2B4B：**COMPLETE**
- Phase 2B4C：**COMPLETE**
- Phase 2B4C0：**COMPLETE**
- Phase 2B4C1：**COMPLETE**
- Phase 2B4C2：**COMPLETE**
- Phase 2B4D：**COMPLETE**
- Phase 2B4D0：**COMPLETE（contract audit）**
- Phase 2B4D1：**COMPLETE / PROJECT REVIEW PASS**
- Phase 2B4D2：**COMPLETE / PROJECT REVIEW PASS**
- Phase 2B4D3：**COMPLETE / PROJECT REVIEW PASS**
- Phase 2B4D migration core：**COMPLETE**
- Phase 2B4 root-switch adapter：**COMPLETE / PROJECT REVIEW PASS / PACKAGED MANUAL PASS**
- Phase 3A contract audit：**COMPLETE / PROJECT AUDIT PASS**
- Phase 3 decisions D-027 / D-028 / D-029：**RESOLVED**
- Phase 3B1 Shared Entity / Package Contract Core：**COMPLETE / PROJECT REVIEW PASS**
- Phase 3B2 FormatCard + WorldBook Transfer Core：**COMPLETE / PROJECT REVIEW PASS**
- Phase 3C1 Character Resource / Materialization Core：**COMPLETE / PROJECT REVIEW PASS**
- Phase 3C2 ST Character + Classifier Split：**COMPLETE / PROJECT REVIEW PASS**
- Phase 3P Prompt Ownership Closure：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**
- Phase 3D Desktop Typed Import/Export + PNG Renderer：**COMPLETE / PROJECT REVIEW PASS / PACKAGED PASS / MANUAL ACCEPTANCE PASS / INTEGRATED**
- Phase 3F Android ↔ Desktop Interoperability Gate：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**
- Phase 4 contract audit：**COMPLETE**
- Phase 4A1 Shared Chat Entity Contract Core：**COMPLETE / PROJECT REVIEW PASS**
- Phase 4A2 Shared Chat Repository + Session Creation：**COMPLETE / PROJECT REVIEW PASS**
- Phase 5：**COMPLETE / ACCEPTED**
- Phase 6：**COMPLETE / ACCEPTED**
- P6-S4–S9：**COMPLETE / ACCEPTED**

本 ChatGPT Project 自此作为 CCB Desktop 的长期控制中心。旧建项会话仅作为历史参考，不再维护 CURRENT 状态。

## Phase 3 control point

- controlling audit：`docs/desktop/22_PHASE3_CONTRACT_AUDIT.md`
- completed Phase 3 slices：**3A**；**3B1**；**3B2**；**3C1**；**3C2**；**3P Prompt ownership closure**；**3D Desktop Typed Import/Export + PNG renderer**；**3F Android ↔ Desktop interoperability gate**
- next stage：**Phase 4 — Core Chat / Prompt / WorldBook**
- D-027：Desktop-owned image/document resources use app-data root-relative references
- D-028：Character transfer Prompt dependency uses authoritative narrow Prompt-owned policy; no copied Prompt text
- D-029：normal success semantics stay aligned; destructive failure paths may receive narrow data-safety hardening
- typed management import/export comes before full Android global SharedImport parity
- CCB PNG Package metadata/payload target：**EXACT**
- Desktop visual cover renderer target：**EQUIVALENT**
- original Phase 3 Codex envelope：**~0.50 weekly**
- 3B1 planned Codex envelope：**0.08–0.12 weekly**
- quota/model/thinking rules：`docs/desktop/23_CODEX_BUDGET.md`

## Phase 3B1 shared Entity / Package contract core

- implementation status：**COMPLETE**；Project review：**PASS**
- integrated implementation：`367a8ce7432bafbb926a176e23886c765b12a8f7`
- authoritative shared entities：`CharacterCard` / `CharacterInfo` / `DocumentInfo`、`FormatCard`、`WorldBook` / `WorldBookEntry`，以及其纯 serialized dependencies
- authoritative shared repositories：Character / FormatCard / WorldBook repositories；继续使用 sharedCore `JsonFileStorage`
- authoritative Package contracts：Character schema write 9 / read 3..9、Format write 2 / read 1..2、WorldBook write/read 1；serialized defaults 与 Package / Entity separation 不变
- pure shared values/codecs：`FishAudioVoiceBinding`、`NovelAiImageModel` / `NovelAiTokenizerKind`、`PngTextChunks` 与小型纯 policy
- FormatCard validation：shared `FormatCardUserToolValidator` 是唯一 authoritative validator；Android `FormatCardUserToolPolicy` 继续持有 Prompt/runtime 行为并委托 validation
- Android-local duplicate implementations 已移除；11 个 Android pure-policy tests 移入 sharedCore，另新增 10 个 contract/repository tests
- validation：focused **6 suites / 21 tests PASS**；sharedCore **17 suites / 135 tests PASS**；desktopApp **19 suites / 232 tests PASS**；Android JVM **182 suites / 1150 tests PASS**；Desktop / Android compile 与 `git diff --check` **PASS**
- materialization、Desktop import/export UI、PNG cover renderer、SillyTavern full import、Prompt bridge/runtime 与 D-027 / D-028 / D-029 后续实现均未进入 3B1

## Phase 3B2 FormatCard + WorldBook transfer core

- implementation status：**COMPLETE**；Project review：**PASS**
- integrated implementation：`8a213233dcce9db1b58afef50f7ee2fa14c9e0ad`
- authoritative shared transfer implementations：`FormatCardTransferService` 与 `WorldBookTransferService`；Android-local production duplicates 已移除，Android 使用相同 sharedCore 实现
- FormatCard transfer：schema 1 read / schema 2 write、ordered `userTools`、duplicate/import/default reuse/overwrite 与 preset provenance 语义保持 upstream 行为
- WorldBook transfer：native Package v1、SillyTavern World Info object form、Character Book array form 与 ST export codec 行为保持 upstream-identical；repository-backed duplicate/import/overwrite identity 语义不变
- validation：FormatCard transfer **7 tests PASS**；WorldBook transfer **10 tests PASS**；WorldBook reuse **3 tests PASS**；sharedCore **19 suites / 152 tests PASS**；desktopApp **19 suites / 232 tests PASS**；Android scoped caller **1 suite / 6 tests PASS**；sharedCore / Desktop / Android compile 与 `git diff --check` **PASS**
- Desktop user-facing typed file ingress/egress 尚未实现；WorldBook ST import/export parity 继续为 `PENDING`；Character transfer/resource materialization 未进入 3B2

## Phase 3C1 Character resource / materialization core

- implementation status：**COMPLETE**；Project review：**PASS**
- implementation：`783af9a10f6d95c618d7ffb81a7fa2949f65b9ab`；strict durable-delete R1：`74f9af25270f2bf893a4bd3699613b0037e71403`
- sharedCore authority：`CharacterCardTransferCore`、`CharacterResourceStore`、side-effect ownership ledger 与 overwrite/delete commit-boundary logic；Android `CharacterCardTransferService` 已成为薄 facade
- platform resources：Android 保持 absolute local path 与 Android asset materialization；Desktop 使用 selected `appDataRoot` 下的 `images/...` / `documents/...` relative owned references，并验证 root A → B raw-copy relocation 无需改写 Entity JSON
- Desktop coordination：container 向 transfer core 注入与 `JsonFileStorage` 相同的 `DesktopDataOperationCoordinator`，整个 multi-step transfer 只做一次 outer normal admission
- D-029：pre-commit 只回滚本次新建的 files / WorldBooks / default FormatCard；replacement persistence 与 Character durable deletion 分别是 overwrite/delete commit point，resource/RAG cleanup 属于 post-commit
- R1：Character transfer delete、WorldBook rollback delete 与 default-Format rollback delete 使用 transfer-specific strict durable deletion；普通 repository delete 语义未全局改变
- validation：sharedCore **21 suites / 170 tests PASS**；desktopApp **22 suites / 238 tests PASS**；Android JVM **181 suites / 1147 tests PASS**；Desktop / Android compile 与 `git diff --check` **PASS**
- remaining dependencies：final authoritative Desktop Prompt policy、Desktop RAG runtime/final adapter、bundled-asset wiring、user-facing typed import/export 与 Desktop PNG renderer

## Phase 3C2 SillyTavern Character + classifier split

- implementation status：**COMPLETE**；Project review：**PASS**
- implementation commit：`d78d76df656fa3ce9fd309a6cbe52c6cfb379f30`
- shared authority：SillyTavern Character V1 / V2 / `Chara` PNG parser、schema-5 Character mapper 与 `SharedImportClassifierCore`；Android-local duplicate parser/mapper/classifier core 已移除
- platform adapters：Android `Uri` / `ContentResolver` ingress、Prompt/log bridge 与 typed `ModelTemplate` facade 保持 Android-owned；`ModelConfig` 与 provider runtime 未进入 sharedCore
- preserved contracts：V1/V2、missing `data`、unknown fields、MIME Base64、FREEFORM、placeholder filtering、resource cleanup、original PNG reuse、embedded Character Book 与 Package schema behavior 保持；Prompt text/runtime 未改变
- import lifecycle：`SharedImportCoordinator` / host FIFO 与 user-facing Android ingress 未改变；Desktop user-facing Character JSON / CCB PNG / ST Character ingress 仍未实现
- validation：parser **5 tests PASS**；mapper **5 tests PASS**；shared classifier **7 tests PASS**；Android facade **2 tests PASS**；sharedCore **24 suites / 187 tests PASS**；desktopApp **22 suites / 238 tests PASS**；Android JVM **181 suites / 1141 tests PASS**；Desktop / Android compile 与 `git diff --check` **PASS**
- limitation：Android `Uri` / `ContentResolver` device/provider behavior 未单独执行 device test；本 slice 的 shared parser/mapper/classifier contract 已由 JVM regression 覆盖

## Phase 3P Prompt ownership closure

- implementation status：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**
- implementation：`f722703c33d8cd96728fc06ff617c9d7d79d9c7d`
- D-028 closure：sharedCore `domain/prompt/CharacterNaiPromptDefaults.kt` 是 Character NAI default-negative Prompt 的唯一 shared Prompt-domain authority；shared production 使用 `AuthoritativeCharacterTransferPromptPolicy`
- Android compatibility：`PromptTemplates` 保留既有 public facade symbols 并委托 shared authority；Android 与 Desktop 不维护分叉 Prompt source
- invariant：Prompt literal 与 Prompt behavior 均未改变；未触及 `PromptAssembler`、`ContextWindowManager` 或 final API-message ordering

## Phase 3D Desktop typed transfer / CCB PNG renderer

- implementation status：**COMPLETE / PROJECT REVIEW PASS / PACKAGED PASS / MANUAL ACCEPTANCE PASS / INTEGRATED**
- implementation：`d8987605e733b07a5deac1901ee049011d47d153`
- typed transfer：Character CCB JSON/PNG 与 SillyTavern V1/V2 JSON/Chara PNG import；Character CCB JSON/PNG export；FormatCard JSON import/export；WorldBook ChatBar 与 supported ST World Info import/export
- conflict semantics：New / Overwrite / Cancel；community Character overwrite protection 保持；Desktop 使用 native `JFileChooser` 与 safe sibling-temp → replace external writer
- PNG boundary：`CharacterCardPngExportOptions` 与 `CharacterCardPngPackageCodec` 是 shared JVM/exact payload authority；Android visual renderer 保持 Android-owned，Desktop AWT cover renderer 为 platform-equivalent implementation
- persistence/RAG boundary：`VectorChunk` / `ChunkSourceType` 与 `VectorChunkStorageContract.ENTITY_TYPE = vector_chunks` 为 shared serialized contract；Android `RagRepository` 仍拥有完整 Android RAG runtime，Desktop 仅实现窄 Character DOCUMENT cleanup
- asset/resource evidence：narrow safe Desktop bundled-asset reader；root-relative Character owned-resource relocation/export regression PASS
- validation：sharedCore **27 suites / 196 tests**、desktopApp **27 suites / 251 tests**、Android JVM **181 suites / 1142 tests**，均为 **0 failures / 0 errors / 0 skipped**；Desktop compile、Android compile、`git diff --check` **PASS**
- packaged/manual：`:desktopApp:createDistributable`、packaged launch smoke、isolated `LOCALAPPDATA` manual Character import/export/reimport conflict/import-as-new/copy naming、Format/WorldBook transfer 与 root-switch coexistence 均 **PASS**
- deferred：global SharedImport FIFO、ACTION_SEND/VIEW/EXTRA_TEXT、drag/drop、Open With/file associations、ModelTemplate Desktop import、完整 management UI 与 full RAG runtime；这些独立用户功能不影响已完成的 3F interoperability gate

## Phase 3F Android ↔ Desktop interoperability gate

- status：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**
- checkpoint：`717ec1a473660b5d186a4241778552bc6f12a80b`；仅含 test harness / fixtures / test-only Gradle 与 artifacts，无 production behavior change
- targeted device evidence：API 34 **PASS**；API 36 `Phase3FAndroidInteropTest` **1/1 PASS，0 failed，0 skipped**
- verified：Android ↔ Desktop Character JSON、CCB PNG、STRUCTURED/FREEFORM、多角色、avatar/background/appearance images、UTF-8 documents、embedded WorldBook/default FormatCard/ordered tools、`FishAudioVoiceBinding`、standalone FormatCard/WorldBook、ST World Info、`ContentResolver`/`FileProvider` ingress、corrupt/invalid cases 与 atomicity
- finding：未发现 production interoperability defect
- limitation：完整 API 36 `connectedDebugAndroidTest` **NOT RUN TO COMPLETION**；focused known failures 重现后按 stop rule 停止。已知失败为既存、与 3F 无关的 `LongTermMemoryScopedCommitTest`、`NovelAiStylePresetGalleryTest` 与 `ChatLongScreenshotRenderTest` instrumented debt，不得宣称 full connected suite green
- environment：persistent local API 36 test environment 已建立；机器特定 helper path 不属于 repository contract

## Declared / validated upstream baseline

- repo: `SaltyFishOTL/ChatChatBar`
- branch: `master`
- version: `1.4.1`
- commit: `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`
- validation status: **PASS**

## Currently observed upstream

- upstream mirror 已到 **1.4.4 @ `550409689df8c51f459fb50b4e04c8ac2fa4bf35`**；formal validated compatibility baseline 仍为 **1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**。
- formal baseline → observed 1.4.4：**18 commits ahead / 75 changed files**；compatibility **NOT VALIDATED**。
- `sync/1.4.4 @ 9b6378dbb595dd2f3ff5143a7a8e46653c99e721` 已存在但明确 **PARKED / NOT MERGED / NOT BASELINE-PROMOTED**；不再作为 P7 前置 gate。
- upstream drift 采用 batch sync 策略：只有 security / data-loss / external API break / 当前 phase 明确依赖时才中断开发；否则延后到更高收益的同步窗口。
- D-022 watch 不等于 sync；未来真正打开 sync window 时仍按 `15_SYNC_PLAYBOOK.md` 完成验证。

## Fork

- repo: `MisakaPiano/ChatChatBar-Desktop`
- `master`：`550409689df8c51f459fb50b4e04c8ac2fa4bf35`，当前 mirror upstream 1.4.4；它不是 formal validated compatibility baseline
- `desktop`：Desktop 集成主线；Phase-6 closure control point 为 `8e10af8042783a82b3e9d9268ac2fb6c3b3b3449`，其后允许 docs-only handoff/observation refresh；任何新任务开始前均以 live `desktop` SHA 为准。公开 compatibility claim 仍绑定 1.4.1 baseline
- `feature/phase6-s9-desktop-ux`：accepted production HEAD `86be0b0ec21aab7a8f15553c0b696b253738917f` + 本次 docs finalization；feature branch 保留。旧 S4 branch/checkpoint 为历史。
- `sync/1.4.4`：`9b6378dbb595dd2f3ff5143a7a8e46653c99e721`，**PARKED FUTURE SYNC**；不合入当前 Desktop、不提升 baseline、不阻塞 P7。
- `sync/1.4.1`：已完成 upstream source merge、Desktop reconciliation、完整回归与 Project review；其 finalization HEAD 是历史 sync checkpoint，之后 `desktop` 已继续前进
- `sync/1.4.0`：已完成 upstream source merge、验证、文档 finalization 与 `desktop` integration
- `sync/1.3.49`：已完成 upstream source merge、验证、文档 finalization 与 `desktop` integration
- `feature/phase1-desktop-bootstrap`：首个 Desktop 实现分支，已通过 review 并完成集成，分支保留
- `feature/phase2a-shared-storage`：Phase 2A1 shared storage extraction，已通过 review、完整回归验证与 `desktop` integration，分支保留
- `feature/phase2a2-storage-edge-tests`：Phase 2A2 storage parity tests，已通过 review、完整回归验证与 `desktop` integration，分支保留
- `feature/phase2b1-data-snapshot`：Phase 2B1 app data snapshot foundation，已通过 Project review 并完成 `desktop` integration，分支保留
- `feature/phase2b2-snapshot-restore`：Phase 2B2 transactional snapshot restore foundation，已通过 Project review 并完成 `desktop` integration，分支保留
- `feature/phase2b3a-backup-policy`：Phase 2B3A backup provenance / automatic backup policy foundation，已通过 Project review 并完成 `desktop` integration，分支保留
- `feature/phase2b3b-safe-pruning`：Phase 2B3B safe automatic backup retention / pruning，已通过 Project review 并完成 `desktop` integration，分支保留
- `feature/phase2b3c-backup-execution`：Phase 2B3C automatic backup execution foundation，已通过 Project review 并完成 `desktop` integration，分支保留
- `feature/phase2b3d-desktop-backup-scheduler`：Phase 2B3D Desktop automatic backup scheduling adapter，已通过 Project review 并完成 `desktop` integration，分支保留
- `feature/phase2b3e-backup-settings-runtime`：Phase 2B3E Desktop backup settings/runtime 与 startup/shutdown integration，已通过 Project review 并完成 `desktop` integration，分支保留
- `feature/phase2b4a-data-root-bootstrap`：Phase 2B4A Desktop data-root bootstrap authority，已通过 Project review 并完成 `desktop` integration，分支保留
- `feature/phase2b4b-portable-root-resolution`：Phase 2B4B ApplicationHome authority 与 Portable root resolution，已通过 implementation、packaged runtime 与 manual UI acceptance，完成本次 finalization 后集成，分支保留
- `feature/phase2b4c1-data-operation-coordinator`：Phase 2B4C1 process-local data-operation coordinator，已通过 implementation 与 lifecycle ownership hardening review，完成本次 finalization 后集成，分支保留
- `feature/phase2b4c2-data-root-ownership`：Phase 2B4C2 per-root process ownership，已通过 ownership、snapshot/restore、lifecycle 与 Windows child-JVM regression review，完成本次 finalization 后集成，分支保留
- `feature/phase2b4d1-migration-safety`：Phase 2B4D1 safety foundation，commit `c0d3c005c302ebbbeecba906c579f589b70732ab`，implementation complete / Project review PASS
- `feature/phase2b4d2-migration-materialization`：Phase 2B4D2 initial materialization，commit `9205ce9b9cc3ee8d27e38fba26056ddd8611299d`，由 R1 branch 保留历史
- `feature/phase2b4d2-r1-workspace-marker`：合入 1.4.1 desktop baseline 的 merge commit `be3ff352500c0fdf04cf82b1447a68361af5340b`；workspace provenance hardening `ba847be0513d51f27f6bbfa1601d58038bf64602`；D2 Project review PASS
- `feature/phase2b4d3-migration-orchestration`：migration orchestration、authority commit 与 restart seal，implementation `525f3c8fd6e4b93af25082a4f11c01629edbbe50`；Project review PASS
- `feature/phase2b4-root-switch-adapter`：user-facing root switch `4936353155dd78b6cb69ddf951851b6b8197e662`、cancellation disposition R1 `cdaf0e510f4da9ab31bde1236be2caf27ec2834a`、destination-label R2 `9deda566f672acb06e4fc84b144d1a48a3563921`；Project review / packaged manual acceptance PASS
- `feature/phase3b1-shared-contract-core`：shared Entity / Package contract authority extraction `367a8ce7432bafbb926a176e23886c765b12a8f7`；Project review PASS，已集成到 `desktop`

## 首次接管复核

- ChatGPT Project takeover: **PASS**
- Codex first read-only audit: **PASS WITH DOC CORRECTIONS**
- schema check for declared baseline: **PASS**
- architecture direction: **PASS**
- architecture/docs factual precision: **CORRECTED**
- official baseline Skill inventory: **PASS (20/20, baseline 1.4.1)**
- current Skill inventory drift: **DETECTED (20；2 changed Skills)，随 HIGH-drift sync window 复核**
- 1.4.1 changed Skill/source consistency: **PASS**
- Phase 0 docs correction: **COMPLETE**

## 1.3.49 sync validation

- impact audit: **PASS**
- user Prompt decision: **ACCEPTED**
- source merge: **PASS** (`cd49ec93e044d0278d66cb2b78991d6e62c61f8c`)
- source integrity: **PASS**
- `:app:compileDebugKotlin`: **PASS**
- `:app:testDebugUnitTest`: **PASS**（1141 tests，0 failures）
- schemas: **UNCHANGED**
- architecture blocker: **NONE**
- Prompt 1.3.49 semantics: fixed prefix + replaceable middle + fixed suffix；`{{original}}` = default middle

## 1.4.0 sync validation

- upstream delta：**4 commits / 59 changed files**
- source merge / reconciliation：**PASS**（`9e6363027a6977727ee512a8e86882263277e248`）
- Project review：**PASS**
- storage：1.4.0 singleton failure / atomic-write / partial-`saveAll` semantics 已 reconciled 到 sharedCore authoritative `JsonFileStorage`；Android-local implementation 保持 absent
- Prompt：官方 1.4.0 text 原样同步；START / END / BOTH final serialized order 已验证
- Model / RAG：dedicated retrieval slot retired，legacy retrieval configuration 已迁移到 ordinary model storage；embedding 保持独立
- Streaming：`AiStreamProgress` coroutine-context propagation 与 meaningful-output inactivity watchdog 已同步
- NovelAI Studio：structured positive-prompt clipboard、legacy compatibility 与 clear/overwrite semantics 已同步
- Memory：per-file partial `saveAll` 与 journal-first recovery contract 已验证
- `:sharedCore:test`：**PASS**（11 suites / 114 tests）
- `:desktopApp:test`：**PASS**（12 suites / 137 tests）
- Android JVM regression：**PASS**（186 suites / 1158 tests）
- Desktop / Android compile 与 `git diff --check`：**PASS**
- 1.3.49 known anomalies 在 1.4.0 均为 **FIXED**：AGENTS auxiliary SQLite wording、model-request START/END placement、image-generation nonexistent skill reference、non-atomic `saveSingleton`、`observeAll` KDoc mismatch

## 1.4.1 sync validation

- upstream delta：**3 commits / 10 changed files**
- source merge：**PASS**（`077286fd531eb794499c0bc3e8941b23fd3235b6`）；final validation sync HEAD：`c5fcac52c3b7249ac4d6ca51ef083c835b46b395`
- Project review：**PASS**
- interrupted reply：assistant draft 在 body 或 reasoning 任一 nonblank 时可持久化；reasoning-only regeneration 的新选中版本保持 empty body，旧正文不会重新进入请求
- blank continue：latest persisted USER 的 body / images / ID 被复用为 current input，同时排除出 history 且不重复持久化；latest 非 USER 时保留 request-only continuation prompt
- responding gate：interrupted/error cleanup 完成 durable persistence、timeline refresh 与 same-ID streaming-state cleanup 后才释放
- model defaults：temperature `1.0`；common `reasoning_effort = low`；`max_tokens` 保持官方 explicit output-limit contract
- Prompt：`PromptTemplates` text **UNCHANGED**；continuation pipeline/runtime semantics 已改变并验证；1.4.0 START / END / BOTH placement 保持
- release metadata：`versionName = 1.4.1`；`baseVersionCode = 80`
- `:sharedCore:test`：**PASS**（11 suites / 114 tests）
- `:desktopApp:test`：**PASS**（12 suites / 137 tests）
- Android JVM regression：**PASS**（186 suites / 1161 tests）
- focused：`InterruptedReplyPolicyTest` 3、`CurrentTurnMessageOrderTest` 11、`ModelConfigurationTest` 28、`MemorySourceFingerprintTest` 3、`TimelineTurnPolicyTest` 4，全部 PASS
- Desktop / Android compile 与 `git diff --check`：**PASS**

## 已确认 schema

- CharacterCardPackage：9，读取 3..9
- FormatCardPackage：2，读取 1..2
- WorldBookPackage：1
- `.cbsave` package：8；legacy 1–7 保留兼容路径

## 已确认架构

- Gradle 当前包含 Android `:app`、纯 Kotlin/JVM `:desktopApp` 与纯 Kotlin/JVM `:sharedCore`
- Kotlin 2.3.20
- JDK/JVM 17
- AGP 9.0.1
- compileSdk/targetSdk 36，minSdk 26
- core/business Entity persistence：authoritative `JsonFileStorage` 位于 sharedCore；Android 使用 Path root + no-op `AppDataOperationGate`，Desktop 使用同一实现 + `DesktopDataOperationCoordinator` gate
- no active Room/ObjectBox business DB identified
- auxiliary SQLite：NovelAI/Danbooru catalog、dictionary、completion indexes（`DanbooruTagCatalog`、`NovelAiBundledDictionary`、`RankedTagIndex` / `RankedTagIndexStore`）
- Desktop 目标：Windows-first Kotlin/JVM + Compose Desktop
- 不使用 browser/WebView runtime
- 初期不强制全 KMP
- Package / Entity / Prompt runtime 三层继续分离
- Prompt 真值以最终 serialized logical messages / transport request 为准

## Phase 1 Desktop bootstrap

- implementation status：**COMPLETE**
- module：`:desktopApp`
- package：`com.example.chatbar.desktop`
- UI runtime：Compose Desktop 1.10.3，Foundation/UI 原生窗口，无 browser/WebView
- Kotlin：2.3.20
- JVM：17
- entry：`application` + `Window`，标题 `ChatChatBar Desktop`
- data-root discovery：默认 `%LOCALAPPDATA%\ChatChatBarDesktop`；缺少 `LOCALAPPDATA` 时使用 JVM `user.home\AppData\Local\ChatChatBarDesktop`
- persistence behavior：仅解析并显示路径，不创建目录、不读写 Entity、不执行 migration
- native distribution：EXE / MSI smoke config 已加入；未配置签名、updater 或 installer UI
- `:desktopApp:compileKotlin`：**PASS**
- `:desktopApp:test`：**PASS**（2 tests，0 failures）
- `:app:compileDebugKotlin`：**PASS**
- `:app:testDebugUnitTest`：**PASS**（1141 tests，0 failures）
- manual GUI acceptance：Windows native window、title、bootstrap content、displayed data directory **PASS**
- `:desktopApp:run`：**BUILD SUCCESSFUL**，Desktop process clean exit **PASS**

## Phase 2A1 shared storage extraction

- branch：`feature/phase2a-shared-storage`
- implementation status：**COMPLETE**
- Project review：**PASS**
- extraction commit：`d37196ee199e5af4bfe34e8ccf74d3ff7f5c349b`
- `JsonFileStorage` 已移动到 `:sharedCore`；除构造入口改为 app data root `Path` 外，包名、类名与公共方法 API 保持不变
- Android wiring：`ChatBarApp` 传入 `filesDir.toPath()`，既有物理路径仍为 `filesDir/entities/...`
- Desktop wiring：`DesktopAppContainer` 以 `DesktopDataDirectory.resolve()` 构造 storage；仅构造不会创建目录或读写 Entity
- Portable Mode、backup、migration、Desktop business persistence：**NOT IMPLEMENTED**
- `:sharedCore:test`：**PASS**（9 tests，0 failures）
- `:desktopApp:compileKotlin`：**PASS**
- `:app:compileDebugKotlin`：**PASS**
- 受构造签名变化影响的 13 个 Android JVM 测试类：**PASS**
- full Android JVM regression：**PASS**（184 suites，1141 tests，0 failures，0 errors，0 skipped）
- Phase 2A1 foundation 本身不代表 JSON persistence / atomic writes 的整个 Desktop parity 已达到 EXACT；Phase 2A2 validation 结果见下节

## Phase 2A2 shared storage edge-case validation

- implementation status：**COMPLETE**
- Project review：**PASS**
- test commit：`b28614aebabb064926f3e46b3d3973bc6772a083`
- production behavior changed by Phase 2A2：**NO**
- new sharedCore tests：15
- sharedCore tests：**PASS**（24 tests，0 failures）
- Android JVM regression：**PASS**（184 suites，1141 tests，0 failures，0 errors，0 skipped）
- raw/uncached behavior、cache isolation、file-set signature、`replaceWhere`、producer validation：**COVERED**
- installation failure rollback：**DETERMINISTICALLY COVERED**
- restart persistence、read-side directory creation behavior：**COVERED**
- rollback restore failure branch：known/deferred test gap；not deterministically testable without introducing a new production seam；不是当前 implementation blocker
- Phase 2A 完成不直接把 JSON persistence、atomic writes 或 Desktop data directory 的整体 parity 提升为 EXACT/EQUIVALENT

## Phase 2B1 app data snapshot foundation

- implementation status：**COMPLETE**
- Project review：**PASS**
- implementation commit：`a1eb5709f36b22e59fff37dd3952dfedd4973f47`
- snapshot operations：create、list、validation
- snapshot root：`<appDataRoot>/backups/`
- installation：staging → validation → completed install
- snapshot format version：1；manifest 使用 root-relative paths，并记录 size 与 SHA-256
- `backups/` recursive exclusion、malformed snapshot isolation 与 source quiescence contract：**ESTABLISHED**
- snapshot suite：**PASS**（15 tests，0 failures）
- sharedCore tests：**PASS**（39 tests，0 failures）
- Android JVM regression：**PASS**（184 suites，1141 tests，0 failures，0 errors，0 skipped）
- symlink implementation rejects / does not follow links；当前 Windows 权限下无法创建可靠 symlink fixture。该 test coverage limitation 不是 implementation blocker
- deterministic copy-phase failure test：**DEFERRED**；without a new production filesystem seam 无法可靠触发，不是 implementation blocker
- restore：**NOT IMPLEMENTED**
- automatic backup scheduling：**NOT IMPLEMENTED**
- Portable Mode：**NOT IMPLEMENTED**
- migration/root switching：**NOT IMPLEMENTED**

## Phase 2B2 transactional snapshot restore foundation

- implementation status：**COMPLETE**
- Project review：**PASS**
- transactional restore commit：`62c2a21e10b72884fc58cc9a53f7a8deb247dd07`
- commit-point cleanup safety fix：`071ab83ffa631d0cfd088a7e86476d6bcdf9ee7e`
- restore transaction：selected snapshot validation → mandatory completed pre-restore safety snapshot → staging → active payload recovery → install → installed payload validation
- explicit restore commit point：pre-commit failures rollback；post-commit cleanup failure 不会 rollback 已提交的 restore
- incomplete rollback preserves recovery evidence：**ESTABLISHED**
- `SnapshotRestoreResult` 可报告 retained workspace 与 cleanup warning
- restore success 后 pre-restore safety snapshot 与 current `backups/` history 均保留
- empty snapshot replacement、reserved `backups/` protection 与 restart persistence：**VALIDATED**
- sharedCore tests：**PASS**（54 tests，0 failures）
- Android JVM regression：**PASS**（184 suites，1141 tests，0 failures，0 errors，0 skipped）
- install-stage-specific deterministic failure fixture、rollback-restore-failure deterministic fixture：**DEFERRED**；without a new production filesystem seam / race 无法可靠触发，不是 implementation blocker
- symlink fixture：当前 Windows 权限下不可用；不是 implementation blocker
- automatic backup scheduling：**NOT IMPLEMENTED**
- Portable Mode：**NOT IMPLEMENTED**
- migration/root switching：**NOT IMPLEMENTED**

## Phase 2B3A backup provenance and automatic backup policy

- implementation status：**COMPLETE**
- Project review：**PASS**
- implementation commit：`999d9a7ab698b1dbe5001fe33ec13595e9190c3e`
- snapshot formatVersion：**1（UNCHANGED）**
- optional purpose metadata：`MANUAL`、`PRE_RESTORE`、`AUTOMATIC`
- `createSnapshot()` default purpose：`MANUAL`
- restore-created safety snapshot：`PRE_RESTORE`
- legacy v1 compatibility：missing purpose → `MANUAL`；validation、listing 与 restore 保持兼容，manifest 不会被静默 rewrite
- legacy provenance limitation：purpose 字段引入前的 snapshot 无法可靠恢复历史 provenance；这包括旧 pre-restore safety snapshot，未来 retention 不得将其推断为 `AUTOMATIC`
- `AutomaticBackupPolicy`：只依据 valid `AUTOMATIC` snapshot 的最新 timestamp 与 minimum interval；manual、pre-restore 与 malformed snapshot 不刷新 interval
- clock rollback：保守判定 not eligible；exact minimum-interval boundary：eligible
- sharedCore tests：**PASS**（64 tests，0 failures）
- Android JVM regression：**PASS**（184 suites，1141 tests，0 failures，0 errors，0 skipped）
- completed snapshot deletion：**NOT IMPLEMENTED**
- retention/pruning：**NOT IMPLEMENTED**
- automatic execution/scheduling：**NOT IMPLEMENTED**
- Portable Mode：**NOT IMPLEMENTED**
- migration/root switching：**NOT IMPLEMENTED**

## Phase 2B3B safe automatic backup retention and pruning

- implementation status：**COMPLETE**
- Project review：**PASS**
- implementation commit：`f9c172334339edc880f21ab12ccf6cd359d7a019`
- retention scope：maximum-count only；只有 explicit valid `AUTOMATIC` snapshots 可被 pruning
- protected snapshots：`MANUAL`、`PRE_RESTORE`、legacy missing-purpose、malformed / unknown purpose
- deterministic ordering：keep newest by createdAt/name；oldest candidates first
- public deletion surface：`pruneAutomaticSnapshots(maximumCount)`
- deletion safety：candidate disk revalidation + no-follow/reparse-safe preflight + `QUARANTINE_THEN_DELETE`
- prune commit point：completed snapshot move 到 `.prune-*.tmp` 成功；completed snapshot 不直接 recursive delete
- post-commit cleanup failure：不 rollback，保留 quarantine residue，并返回 retained workspace / warning
- orphan `.prune-*` listing isolation：**ESTABLISHED**；automatic orphan cleanup：**NOT IMPLEMENTED**
- caller serialization contract：create / restore / prune 必须串行；当前 revalidation 不宣称消除任意外部并发 mutation 的最终 TOCTOU
- sharedCore tests：**PASS**（82 tests，0 failures）
- Android JVM regression：**PASS**（184 suites，1141 tests，0 failures，0 errors，0 skipped）
- Windows `NOSHARE_DELETE` move-failure fixture、DOS read-only cleanup-failure fixture：**PASS**
- symlink fixture：当前 Windows 权限下不可用；junction/reparse deterministic fixture 与 exact policy→revalidation mutation fixture：**DEFERRED**，不是 implementation blocker
- automatic execution/orchestration：**NOT IMPLEMENTED**
- background scheduling：**NOT IMPLEMENTED**
- Portable Mode：**NOT IMPLEMENTED**
- migration/root switching：**NOT IMPLEMENTED**

## Phase 2B3C automatic backup execution foundation

- implementation status：**COMPLETE**
- Project review：**PASS**
- implementation commit：`ce126f6660c06065a04cf105040af7e63bcceab7`
- protected-new-snapshot fix：`ced5c05f498bf0058c179ea7b5e1c90be1f8e2dc`
- execution API：synchronous single-run `executeAutomaticBackup(minimumInterval, maximumCount)`
- Clock authority：eligibility 与新 snapshot `createdAt` 使用同一 service Clock
- execution order：policy → create completed `AUTOMATIC` snapshot → prune
- skipped result：不 create / prune；creation failure：不 prune
- pruning pre-commit failure：保留新 snapshot，并通过 structured execution failure 暴露
- pruning post-commit cleanup warning：仍为 successful `Created` result
- same-execution retention：新 snapshot 显式受保护并占用一个 retention slot，包括 equal timestamp / `Duration.ZERO`
- public standalone pruning semantics：**UNCHANGED**
- caller serialization contract：create / restore / prune / execute 必须串行
- sharedCore tests：**PASS**（92 tests，0 failures）
- Android JVM regression：**PASS**（184 suites，1141 tests，0 failures，0 errors，0 skipped）
- Desktop / Android compile：**PASS**
- Phase 2B3C 完成时 background scheduler 尚未实现；已在 Phase 2B3D 建立
- Phase 2B3C 完成时 startup hook 与 persisted Desktop backup settings 尚未实现；已在 Phase 2B3E 建立
- Portable Mode：**NOT IMPLEMENTED**
- migration/root switching：**NOT IMPLEMENTED**

## Phase 2B3D Desktop automatic backup scheduling adapter

- implementation status：**COMPLETE**
- Project review：**PASS**
- implementation commit：`08646ea3b1370054b7633e5669129638f0393bc9`
- event-sink robustness fix：`0e19d14a26004b498f90db79d7fba831906d5792`
- runtime capability：explicit `start` / `stop` / `close`；construction 不启动 scheduler，也不产生 filesystem writes
- schedule behavior：首次检查立即执行，随后按 check interval 串行运行；scheduled executions 不重叠
- failure behavior：per-run execution failure 产生 `Failed` 并在下一 tick retry；observer / event reporting failure 不终止 loop
- stop / close behavior：允许 in-flight synchronous filesystem transaction 安全完成，不强制中断
- serialization boundary：scheduler 只串行化自身 runs；future manual create / restore / prune 必须与 scheduler 协调
- `DesktopAppContainer`：构造 snapshot service 与 scheduling capability，但不自动启动
- Phase 2B3D 完成时 `Main.kt` 尚未接入；startup activation 已在 Phase 2B3E 完成
- user-facing settings UI：**NOT IMPLEMENTED**
- `:desktopApp:test`：**PASS**（13 tests，0 failures）
- `:sharedCore:test`：**PASS**（92 tests，0 failures）
- Android JVM regression：**PASS**（184 suites，1141 tests，0 failures，0 errors，0 skipped）
- Portable Mode：**NOT IMPLEMENTED**
- migration/root switching：**NOT IMPLEMENTED**

## Phase 2B3E automatic backup settings and startup integration

- implementation status：**COMPLETE**
- Project review：**PASS**
- settings/runtime commit：`7f202359887c8cb5271b50cd8e854a653a49e033`
- settings JSON robustness fix：`bce51e64cf54bc2567e33fc9f0c5284f43ad2932`
- Desktop lifecycle integration：`ce0ba7acfc79b54c5878c7086d07ffde6a99b48c`
- persisted settings：`<appDataRoot>/desktop-settings.json`；formatVersion：1
- Project defaults：`enabled = false`、minimum backup interval 24 hours、maximum automatic snapshots 7、scheduler check interval 1 hour
- missing settings：只使用 in-memory defaults，不创建 app-data root 或 settings file，也不启动 scheduler
- corrupt / invalid / unsupported settings：保留原文件，不阻止 Desktop launch，automatic backup 保持停止
- unknown root / nested fields：explicit save 后保留
- settings write：sibling temporary file → flush/close → atomic replace；只对 `AtomicMoveNotSupportedException` fallback ordinary replace
- settings reconfiguration：stop scheduler → await in-flight backup → atomically save settings → enabled 时以新 schedule restart
- failed settings save：prior file 保持完整，并 best-effort restart previous working schedule
- `DesktopAutomaticBackupRuntime`：拥有 settings initialization、scheduler start/stop/reconfiguration 与 runtime `StateFlow`
- runtime state：settings load state/failure、effective settings、scheduler-running state、latest backup event、latest execution failure、cleanup warnings、operation failure
- runtime-local mutex：串行 initialize / apply / close；data-operation admission 已在 Phase 2B4C1 由独立 process-local coordinator 建立
- future manual snapshot / restore / prune 与 Desktop business writers 必须通过 shared operation gate 或 coordinated adapter 参与
- container construction：zero-write，不隐式 initialize 或 start runtime
- Desktop startup：resolve appDataRoot → construct `DesktopAppContainer` → initialize runtime → enter Compose application
- Compose lifecycle：`exitProcessOnExit = false`；window `exitApplication()` → Compose returns → runtime close → scheduler awaits in-flight synchronous snapshot transaction → `main` returns naturally
- force-cancel / `Thread.interrupt` / `exitProcess`：**NOT USED**
- automatic backup startup integration：**COMPLETE**；仅 persisted settings `enabled = true` 时启动，default 仍 disabled
- settings UI / Task Center：**NOT IMPLEMENTED**
- `:desktopApp:test`：**PASS**（5 suites，44 tests，0 failures，0 errors，0 skipped）
- `:sharedCore:test`：**PASS**（8 suites，92 tests，0 failures，0 errors，0 skipped）
- Android JVM regression：**PASS**（184 suites，1141 tests，0 failures，0 errors，0 skipped）
- Desktop / Android compile：**PASS**
- `git diff --check`：**PASS**
- Windows symlink fixture limitation：当前权限下仍不可可靠创建；该 coverage limitation 非 blocker

## Phase 2B4A data-root bootstrap authority

- implementation status：**COMPLETE**
- Project review：**PASS**
- implementation commit：`412e04422a2b65f14d280f38a3e44a8830226d78`
- Phase 2B4A completion-time precedence：optional explicit CLI override supplied by caller → external bootstrap selection → legacy/default LOCALAPPDATA root；Phase 2B4B 已在 CLI 与 bootstrap 之间加入 Portable authority
- actual command-line argument parsing：**DEFERRED**；CLI override 为 temporary，且不自动持久化
- external bootstrap：`%LOCALAPPDATA%\ChatChatBarDesktop.bootstrap.json`；缺少 `LOCALAPPDATA` 时回退到 `<user.home>\AppData\Local\ChatChatBarDesktop.bootstrap.json`
- bootstrap authority deliberately 位于 selected `appDataRoot` 外部，避免 bootstrap paradox 以及 snapshot/restore 错误改变根目录选择
- formatVersion：1；modes：`DEFAULT`、`CUSTOM`
- root provenance：`CLI_OVERRIDE`、`BOOTSTRAP_DEFAULT`、`BOOTSTRAP_CUSTOM`、`MISSING_BOOTSTRAP_DEFAULT`
- missing bootstrap：解析 exact legacy/default root；zero-write，不创建 bootstrap 或 selected root
- corrupt / invalid / unsupported bootstrap：structured failure；不 silent fallback，也不针对 unintended default root 构造 `DesktopAppContainer`
- `CUSTOM`：只接受 normalized absolute path；load / resolution 不创建 custom root
- persistence：sibling temporary file → full write/flush/close → atomic move + replace；仅在 `AtomicMoveNotSupportedException` 时 fallback ordinary replace
- failed replace：prior valid bootstrap 保持完整；unknown root fields 在 explicit save 后保留
- `Main.kt`：在构造 `DesktopAppContainer` 前解析 root authority；broken bootstrap 在 container construction 前产生 explicit bootstrap failure
- successful root resolution 后，`desktop-settings.json` 与 automatic-backup runtime 行为保持不变；default appDataRoot 未改变
- developer KDoc：以中文解释 + standard English technical term 记录 authority-outside-root、bootstrap paradox、invalid `CUSTOM` no-fallback 与 apparent user-data-loss compatibility trap
- `:desktopApp:test`：**PASS**（6 suites，57 tests，0 failures，0 errors，0 skipped）
- `:sharedCore:test`：**PASS**（8 suites，92 tests，0 failures，0 errors，0 skipped）
- Android JVM regression：**PASS**（184 suites，1141 tests，0 failures，0 errors，0 skipped）
- Desktop / Android compile：**PASS**
- `git diff --check`：**PASS**
- Windows symlink fixture：当前权限下仍为 permission-dependent；该 limitation 非 blocker
- Phase 2B4A 完成时 Portable marker 尚未实现；已在 Phase 2B4B 建立。process-local coordinator 已在 Phase 2B4C1 建立，cross-process ownership 已在 Phase 2B4C2 建立；actual CLI parser、root migration、root-switch UI：**NOT IMPLEMENTED**

## Phase 2B4B Portable root resolution

- implementation status：**COMPLETE**
- Project review / packaged verification / manual acceptance：**PASS**
- implementation commit：`bb7ff31047af5f705a8ac5e12a9001d04e75e63e`
- `ApplicationHome` result：`Available` / `Unavailable` / `Failure`；provenance：`PACKAGED_LAUNCHER_PROPERTY` / `INJECTED_DEVELOPMENT_TEST`；unavailable reason 区分 property absent 与 unexpanded jpackage macro；不使用 `user.dir` fallback
- packaged launcher property：`-Dchatbar.desktop.applicationHome=$ROOTDIR`
- final precedence：explicit CLI temporary override → Portable → OS-local bootstrap → default LOCALAPPDATA；first version 不使用 environment-variable root override
- Portable contract：`<ApplicationHome>/portable.flag`，UTF-8 exact token `CCB_DESKTOP_PORTABLE_V1`；data root 为 existing `<ApplicationHome>/UserData/`
- pure resolver：zero-write；不创建 marker、`UserData/` 或 probe
- activation validator：在 `UserData/` 内执行 unique temporary write → flush/close → delete probe；成功后无 residue
- authority invariant：valid marker 一旦声明 Portable authority，missing / invalid / unusable `UserData/` 必须 explicit failure，绝不 fallback 到 bootstrap/default root
- relocatability：Portable paths 不持久化 absolute `ApplicationHome`；完整 application image 移动后从新 root 重新解析
- `:desktopApp:test`：**PASS**（79 tests，0 failures）
- `:sharedCore:test`：**PASS**（92 tests，0 failures）
- Android JVM regression：**PASS**（1141 tests，0 failures）
- Desktop / Android compile：**PASS**
- `git diff --check`：**PASS**
- real packaged runtime：**PASS**；jpackage launcher runtime 将 `$ROOTDIR` 展开为 actual application-image root，且与 `user.dir`、packaged `java.home` 相互独立
- whole-image relocation：**PASS**；移动完整 image 后 `ApplicationHome` 跟随新位置
- positive Portable packaged smoke：**PASS**；write probe 无 residue，未创建 isolated default root/bootstrap
- invalid Portable no-fallback smoke：**PASS**；`UserData` 为 ordinary file 时进程以 code 1 失败，未 fallback 到 LOCALAPPDATA
- user manual packaged UI acceptance：**PASS**；窗口正常打开，显示 relocated image 的 `<ApplicationHome>/UserData`
- symlink fixture 仍受当前 Windows permissions 限制；junction/reparse detection 受 public JDK 17 NIO 能力边界约束，均为已知非阻塞 coverage limitation
- Phase 2B4B completion-time：actual CLI parser、Portable ZIP release task、data migration、root-switch UI、old-root deletion 尚未实现；其后 migration / root-switch 已在 Phase 2B4D / final adapter 完成，其他项目仍 deferred

## Phase 2B4C1 process-local data-operation coordination

- implementation status：**COMPLETE**
- Project review：**PASS**
- branch：`feature/phase2b4c1-data-operation-coordinator`
- implementation commit：`1a535229c2a201f496b93e9a98362ff8037cafde`
- lifecycle ownership hardening：`38c8587080dbf715e60348fc2af3554c09004f63`
- coordinator state model：`OPEN`、`MAINTENANCE_PENDING`、`EXCLUSIVE`、`RESTART_REQUIRED`、`CLOSING`、`CLOSED`
- shared seam：`AppDataOperationGate` 保持 platform-neutral；Android/default shared callers 使用 no-op gate
- `JsonFileStorage` ordering：gate → existing per-entity mutex → IO/filesystem operation；不同 Entity types 保留现有并发
- nested normal contract：same-gate coroutine-context identity marker 使 direct suspend、`withContext` 与 structured child work 幂等参与；detached work 不得借用 outer registration
- maintenance admission：第一个 exclusive waiter 到达后停止新的 unrelated normal admission，等待 existing operations drain；exclusive waiters FIFO，cancellation / exception cleanup 不泄漏 state
- snapshot facade：Desktop create / restore / prune / automatic execution 使用 suspend coordinated facade；raw `AppDataSnapshotService` 不从 container public 暴露
- restore invariant：successful restore 与 incomplete rollback seal `RESTART_REQUIRED`；cleanup-warning success 同样 seal，pre-mutation / rollback-complete failure 不 seal
- runtime maintenance：pause → scheduler stop/join → coordinator exclusive；paused / restart-required 时拒绝 settings mutation，resume 不得绕过 restart seal
- scheduler integration：execution callback 为 suspend，并通过 coordinated automatic execution；既有 immediate-first-run、retry、event 与 safe stop semantics 保持
- lifecycle ownership hardening：container 不 public expose mutable settings store / scheduler bypass surface，只暴露 lifecycle-owning runtime 与 coordinated services
- process boundary：coordinator 只提供 process-local admission；cross-process ownership 不属于其职责
- validation：`:sharedCore:test` **9 suites / 94 tests PASS**；`:desktopApp:test` **10 suites / 108 tests PASS**；Android JVM **184 suites / 1141 tests PASS**；Desktop / Android compile 与 `git diff --check`：**PASS**
- review hardening validation：`:desktopApp:test` **10 suites / 108 tests PASS**、Desktop compile 与 `git diff --check`：**PASS**；因未改 sharedCore / Android source，未重复 Android full regression
- Phase 2B4C2 已实现 one writable Desktop process per selected `appDataRoot`；不同 roots 可由不同 processes 使用；JDK `FileLock` lifetime guard 与 process-local coordinator 保持分离
- migration、root switching：**NOT IMPLEMENTED**

## Phase 2B4C2 per-root process ownership

- implementation status：**COMPLETE**
- Project review：**PASS**
- branch：`feature/phase2b4c2-data-root-ownership`
- ownership primitive：`060897cb8eb3ebabc8d4c3a1c5202d43b8e949aa`
- snapshot/restore integration：`390fdc576e983e35768d27e438b0500e1eeab6f0`
- lifecycle integration：`5a16e3152b9bacdbf4e3159dc04798fec1055381`
- process-regression hardening：`8065f8ffa4a94c83159709056cc5497c94606431`
- scope：per-selected-root cooperative ownership；同一 `appDataRoot` 只允许一个 writable CCB Desktop process，不同 roots 可同时使用
- mechanism：JDK `FileChannel` + `FileLock`；channel 与 lock 在整个 application lifetime 保持
- lock artifact：selected root direct child `.ccb-desktop.lock`；zero-length persistent infrastructure，normal exit 不删除；stale file existence 不表示 active ownership
- Windows behavior：held lock file 仍可能被 move/delete，因此 CCB 在 ownership active 时绝不操作该 path，也不依赖 delete/rename denial
- root policy：missing DEFAULT root 可在 activation 时创建；Portable `UserData/`、CUSTOM 与 CLI selected root 必须已存在；authority 一旦选定不 fallback
- snapshot contract：root lock 不进入 source payload / manifest；crafted root lock payload 被拒绝；live lock 在 restore 中保留；nested same-name path 仍是 ordinary payload；formatVersion 保持 1
- startup：resolve root → acquire ownership → construct container → initialize runtime → application；ownership failure 不构造 container，也不 fallback
- shutdown：runtime → process-local coordinator → ownership；construction / initialization / application / shutdown failure 均释放 ownership，并保持 primary / suppressed failure ordering
- forced-termination regression：child `waitFor()` 确认退出后，以 5-second monotonic deadline 等待 Windows lock-release visibility；不使用 arbitrary fixed sleep，production acquisition semantics 未改变
- limitation：protocol 只约束 cooperative CCB processes；不提供 adversarial third-party filesystem protection；public JDK 17 不能宣称完整识别所有 Windows junction/reparse identity cases
- C2-A validation：`:desktopApp:test` **122 tests PASS**
- C2-B validation：`:sharedCore:test` **10 suites / 105 tests PASS**；`:desktopApp:test` **122 tests PASS**；Android JVM **184 suites / 1141 tests PASS**；Desktop / Android compile：**PASS**
- C2-C/R1 validation：`:desktopApp:test` **12 suites / 137 tests PASS**；`:sharedCore:test` **10 suites / 105 tests PASS**；Windows child-JVM **4 executed / 0 skipped / 0 failures**；Desktop compile 与 `git diff --check`：**PASS**
- C2-C/R1 只修改 Desktop/test code，未重复 Android regression；复用 C2-B 的 Android 1141 tests 与 compile evidence

## Phase 2B4D1 migration destination / bootstrap authority safety

- implementation status：**COMPLETE**；Project review：**PASS**
- implementation commit：`c0d3c005c302ebbbeecba906c579f589b70732ab`
- source scope：migration v1 仅支持 `MISSING_BOOTSTRAP_DEFAULT`、`BOOTSTRAP_DEFAULT`、`BOOTSTRAP_CUSTOM`；`PORTABLE` / `CLI_OVERRIDE` structured unsupported
- destination：local filesystem only；safe existing parent 下只创建 exact final directory；identity/nesting/ApplicationHome/UNC/unsafe-entry checks 完成后取得 destination ownership，并在持有 ownership 时验证 lock-only emptiness
- authority serialization：bootstrap sibling `ChatChatBarDesktop.bootstrap.lock`；它只序列化 authority writers，不替代 selected-root ownership
- commit protocol：fresh load → expected-source revalidation → higher-priority Portable revalidation → preserve fresh unknown fields → atomic bootstrap save → readback classification
- outcomes：`Committed`、`Busy`、`AuthorityChanged`、pre-commit failure 与 indeterminate state 可区分；ambiguous save/readback 绝不当作 rollback-safe
- validation：desktopApp **15 suites / 171 tests PASS**；Windows bootstrap-authority child JVM **3 executed / 0 skipped**；Desktop compile 与 `git diff --check` **PASS**

## Phase 2B4D2 migration materialization

- implementation status：**COMPLETE**；Project review：**PASS**
- initial implementation：`9205ce9b9cc3ee8d27e38fba26056ddd8611299d`
- 1.4.1 desktop baseline merge：`be3ff352500c0fdf04cf82b1447a68361af5340b`
- workspace provenance hardening：`ba847be0513d51f27f6bbfa1601d58038bf64602`
- source contract：read-only；active payload raw-byte copy，preserve unknown safe entries / empty directories / corrupt singleton bytes；source 与 old root 不删除
- backup policy：只迁移 valid completed `MANUAL` / `AUTOMATIC` / `PRE_RESTORE` snapshots；invalid、recovery 与 unknown evidence 留在 source 并 warning
- transaction：destination-local staging → SHA-256 manifest/tree validation → no-overwrite install → installed validation；使用 explicit installed-entry ledger 与 `NonCancellable` install/validate/rollback boundary
- `MATERIALIZATION_COMMITTED` 不是 bootstrap/root-authority commit；D2 不会改变下次启动 authority
- workspace provenance：只有 recognized `.migration-*.tmp` name + `.ccb-desktop-migration-workspace` + exact `CCB_DESKTOP_MIGRATION_WORKSPACE_V1` token 才视为 infrastructure；name-only safe directory 作为 ordinary payload 迁移
- retained workspace：rollback incomplete 与 post-commit cleanup warning 均保留 marker/evidence；cleanup 仍按整棵 workspace 处理
- D2 initial validation：desktopApp **16 suites / 186 tests PASS**；sharedCore **11 suites / 114 tests PASS**；Desktop compile 与 `git diff --check` **PASS**
- D2-R1 validation：desktopApp **16 suites / 192 tests PASS**，materializer **21 PASS**；sharedCore **11 suites / 114 tests PASS**；Desktop compile 与 `git diff --check` **PASS**
- D3 pause/exclusive、mandatory safety snapshot、authority commit、restart seal 与 destination ownership shutdown integration：**COMPLETE**；user-facing root-switch path 已由 Phase 2B4 final adapter 完成；automatic relaunch intentionally not implemented

## Phase 2B4D3 migration orchestration

- implementation status：**COMPLETE**；Project review：**PASS**
- implementation commit：`525f3c8fd6e4b93af25082a4f11c01629edbbe50`
- source identity：service 保留 startup 的完整 `DesktopDataRootResolution.Resolved`；source ownership 继续由 outer application lifetime 持有，不重复 acquire
- ordering：destination prepare → runtime pause / scheduler stop-join → one coordinator exclusive → shared preflight → mandatory `MANUAL` safety snapshot → D2 materialization → bootstrap authority transaction → restart seal / runtime disposition
- snapshot：在已持有 exclusive 时直接调用 authoritative `AppDataSnapshotService` primitive，避免 nested exclusive；completed safety snapshot 会进入 migrated valid backup history
- authority：`Committed` / `CommitIndeterminate` classification、`requireRestart()`、service restart seal 与 destination ownership retention 位于同一 `NonCancellable` boundary；旧 source runtime 不 resume
- proven-precommit：source 继续为 running authority，destination validated copy 保留但不选中，destination ownership release 后 runtime resume；不自动删除 destination
- cancellation：pause 后必须稳定到 source resumed 或 restart-required；D2 copy 保持 cancellable，authority commit 附近 cancellation 不能跳过 seal
- shutdown：automatic-backup runtime close → coordinator close/drain → migration service release retained destination ownership → outer lifecycle release source ownership
- source retention：成功迁移仍保留 source；无 old-root deletion / cleanup
- structured failures：preparation、runtime pause、preflight、safety snapshot、materialization、authority precommit / indeterminate、runtime resume、restart-required、closed/internal
- validation：desktopApp **17 suites / 208 tests PASS**；sharedCore **11 suites / 114 tests PASS**；Desktop compile 与 `git diff --check` **PASS**

## Phase 2B4 user-facing root switch

- implementation status：**COMPLETE**；Project review：**PASS**；packaged manual acceptance：**PASS**
- commits：initial `4936353155dd78b6cb69ddf951851b6b8197e662`；R1 `cdaf0e510f4da9ab31bde1236be2caf27ec2834a`；R2 `9deda566f672acb06e4fc84b144d1a48a3563921`
- supported source：bootstrap-controlled provenance only；Portable / CLI override 保持 structured unsupported
- UI flow：directory-only picker → D1 validation → confirmation → D3 migration；确认明确说明 copy、mandatory safety snapshot、source retained 与 restart required
- success semantics：当前 process 的 current/running root 保持 startup source；validated destination 只作为 next-start root；controller terminal `RestartRequired`，不 hot-swap、不自动 relaunch、不 `exitProcess`
- cancellation：precommit cancellation 且 D3 已恢复 source 时回到 `Idle`；restart seal 已成立时进入 terminal `RestartRequired`，保留 attempted destination，未知 commit classification 时 `nextStartRoot = null`，并原样传播 `CancellationException`
- retryable labels：只有 `Materialized` 才显示 “Destination copy”；preparation / preflight / snapshot / materialization failure 显示 “Attempted destination”
- automated validation：initial + R1 desktopApp **18 suites / 231 tests PASS**、sharedCore **11 suites / 114 tests PASS**；R2 focused **1 PASS**、desktopApp **19 suites / 232 tests PASS**；Desktop compile、`git diff --check` 与 `createDistributable` **PASS**
- packaged positive acceptance（isolated `LOCALAPPDATA`）：`H:\CCB-Acceptance\LocalAppData\ChatChatBarDesktop` → `H:\CCB-Acceptance\Destination`；MANUAL snapshot、payload、source retention、restart、relaunch destination / `BOOTSTRAP_CUSTOM` 与 ownership reacquire **PASS**
- packaged negative acceptance：non-empty `H:\CCB-Acceptance\NonEmptyDestination` 在 `PREPARATION` 拒绝，`do-not-overwrite.txt` unchanged、source remains authoritative、retry available **PASS**
- R2 label retest：`PREPARATION` 显示 “Attempted destination” **PASS**；该次使用 normal Windows `LOCALAPPDATA`，不替代此前 isolated positive/negative acceptance evidence

## 文档真源

- GitHub `MisakaPiano/ChatChatBar-Desktop` 的 `desktop` 分支是 CURRENT 真源。
- 本 Project Sources 用于提供 Bootstrap 详细内容与历史快照；与 GitHub CURRENT 冲突时，以 GitHub `desktop` 为准。
- 本 Project 负责长期架构、parity、upstream sync、Codex 任务规格与 diff review。
- Codex 以 GitHub 仓库真实工作树、commit SHA / PR 为实现交接点。

## Editor Reference adoption

- `CCB_EDITOR_REFERENCE_PACK.zip` Project adoption audit：**COMPLETE**；identity = `REF — Mature User-Validated Editor UX / Workflow / Regression Evidence`。
- controlling adoption map：`docs/desktop/27_EDITOR_REFERENCE_ADOPTION.md`。
- authority boundary unchanged：current pinned upstream baseline → current `desktop` code → CURRENT docs → Project Instructions → REF/history。
- Package / persisted Entity / runtime Prompt/API-message 三层继续严格分离；Reference editor workspace/export mapper/Web implementation 不进入 authority chain。
- completed 3C1 / 3C2 / 3D scopes **unchanged and not reopened**；A4/A10 只作为 resource ownership / failure-case regression oracle，A16 作为 round-trip oracle。
- A7 PNG preserve-carrier 已对 current baseline 重新审计：当前 upstream/Desktop normal PNG export 是 fresh render + `ChatBarCharacter` payload attach，未定义 preserve imported visible pixels 的 parity contract；preserve mode 仅保留为 future enhancement candidate。
- future heavy consumption point：Phase 6 Character/other editors 与 Phase 7 image resources；`Desktop Image Workspace / Image Editing Foundation` 仅登记为 future architecture/design candidate，不插入当前 production slice。
- Reference maturity does not promote any `13_FEATURE_PARITY.md` PENDING item.

## 当前未完成 / 后续范围

Phase 6 没有未分类项；后续未实现功能保持 **PENDING**，不作为 Phase 6 缺陷：

P7 图像范围已实现，最终审查证据见 `38_PHASE7_FINAL_REVIEW.md`；以下为仍未实现的后续 owner。

| Owner | PENDING 后续范围 |
|---|---|
| P8 | Fish Audio / audio / QQ voice feasibility；现有 Entity 字段保留不等于 voice runtime 完成 |
| P9 | RAG / embedding / vector / document / chat-memory retrieval |
| P10 | Long-Term Memory / Episode / Arc / Era / Archive / HEAD / Gap |
| P11 | SaveSlot / Session Lifecycle / session duplicate；SaveSlot media 协同 P7/P8 |
| P12 | AI Authoring / Character、Format、WorldBook AI / research / Message Format Repair |
| P13 | Moments |
| P14 | Community / Discord OAuth |
| P15 | OS integration / external ingress FIFO / drag-drop / Open With / file association / installer-updater / crash diagnostics / AI request-log user surface / tray-notifications |
| P16 | Upstream watcher / compatibility automation |
| P17 | Tutorial/onboarding / beta hardening / Backup-Recovery Center |

Automatic Backup 是 **Desktop-only data-safety enhancement**，runtime + settings discoverability 已交付，不声明 Android parity；Backup/Recovery Center 是独立 P17 backlog。
Virtual Conversation Scrollbar 明确 deferred，待 realistic long/cross-device histories 后再评估，不是 Phase 6 defect。
Cross-device Full Data Portability / BYOC Sync 仅冻结方向，见 D-035；约 P11 后重访架构，最终 phase/slice number 未冻结。
actual CLI parser、Portable ZIP release packaging、Portable/CLI migration 等既有平台扩展继续留在后续 OS integration / hardening；不自动删除 source，不扩本次范围。

## 授权与发布依据

- factual observation：upstream repository 尚未观察到标准 `LICENSE` 文件。
- authorization status：upstream 作者已直接授权用户开发与发布 CCB Desktop；该直接授权是项目 development / public release 的授权依据。
- 标准 `LICENSE` 元数据缺失本身不再是 development / public-release blocker。
- Release packaging 前应保留并确认原始授权记录；不据此虚构授权原文、日期、URL、截图或额外法律条款，也不添加或伪造 license file。

## 当前风险

1. QQ voice 依赖 Android Accessibility；Desktop 等位能力待单独调查。
2. Android 图像/音频/secret/background/update 等平台代码需要 adapter。
3. 未来 upstream Prompt diff 仍须按高风险路径审查最终 logical messages / transport；不得维护 Desktop Prompt fork。
4. Skill inventory 数量一致不自动证明内容兼容；每次 upstream sync 仍须比较并核对源码。
5. NovelAI/Danbooru 辅助 SQLite 需要单独的平台边界，但不改变核心 Entity 的 JSON storage 路线。

## 下一项任务

Phase 6 已 **CLOSED / INTEGRATED / ACCEPTED**，post-close cleanup 与 reproducibility gate 也已完成。

**直接开始 Phase 7 — Image Resources + NovelAI。**

执行入口：
- `32_PHASE7_ENTRY_HANDOFF.md`
- `33_PHASE7_CODEX_MEGA_TASK.md`

工作策略：大任务连续推进、内部 durable commits、Phase 末集中 review/验收；不要再创建低收益 pre-P7 audit / counter-review / docs-only micro-task。parked `sync/1.4.4` 不阻塞 P7。

## Post-Phase-6 cleanup / reproducibility

- local repository cleanup：**COMPLETE**
- repository logical size：约 **8.829 GiB → 1.048 GiB**（重建/打包后）
- source/docs/.git/user data：未删除
- old generated distributions/toolchain staging/obsolete worktree：按白名单清理
- system JDK：`C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`
- post-clean rebuild：
  - sharedCore **527/527 PASS**
  - desktopApp **909/909 PASS**
  - Android compile **PASS**
  - Desktop compile **PASS**
  - `createDistributable` **PASS**
  - packaged launch smoke **PASS**
- cleanup 已关闭，不是 Phase 7 blocker；无具体问题不得重开。

## Phase 5 completion record

- controlling audit：`docs/desktop/30_PHASE5_CONTRACT_AUDIT.md`；Phase 5 **COMPLETE / ACCEPTED**。
- P5-S1 / 5A1：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**。
- reviewed implementation：`dd68b714c58254b7ef994b106dafebf649623bf6`；docs closeout：`39268a7c2bc64b18ec034dc8dd8222472d2c9095`；`desktop` contains both commits unchanged in ancestry。
- authoritative shared foundation：`ModelConfig`、`EmbeddingConfig`、`ParamValue`、`ModelTemplate`、`OutputTokenParameter`、required preset values、`ModelRepository`、`ModelStorageKeyPolicy`。
- Android compatibility：unchanged packages + existing sharedCore dependency；default `ModelRepository(storage)` 使用 identity storage-key policy，无需 facade。
- Desktop Windows physical policy：`ccb-model-v1-<lowercase UTF-8 hex>`；logical/serialized ID 与 relationships 保持 upstream values。global `JsonFileStorage` unchanged。
- no prior Desktop ModelRepository production wiring / persisted model data；no Desktop migration required。
- P5-S1 verification：focused **16/16 PASS**；sharedCore **56 suites / 374 tests**；desktopApp **33 suites / 272 tests**；Android JVM **162 suites / 1023 tests**；Desktop/Android compile 与 `git diff --check` **PASS**。
- Prompt literals unchanged；no Desktop plaintext-secret production persistence；no real provider/manual runtime testing claimed。
- P5-S1 evidence above is historical；subsequent Settings/resolution、SecretStore、transport/discovery、real chat、TaskRuntime/diagnostics 与 Alpha vertical acceptance 均已完成并通过 Project review。Phase 5 acceptance chain culminated in `c6a3805698faceb8ed7e7ce36af6f49263b7b517`。
- formal baseline remains `1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`；P5-S1 时 observed `354f15166d8bc0462cb87d62a0ba4613794560a3` was HIGH / NO SYNC and did not touch P5-S1 audited assumptions。

## Phase 6 final acceptance / integration control

- S4 Primary Chat：session browser/search/pin/rename/settings、model/runtime selection、roleplay narration/dialogue/thought/status、speaker/avatar、reasoning disclosure、alternatives、copy/edit/delete/regenerate/retry、per-segment actions、archived Character relink、session WorldBook binding、reading/navigation、full-screen composer；无 ordinary Continue button（保留 shared blank-continuation runtime contract）。
- S5 / S6 / S7：Character / FormatCard / WorldBook management + manual editors **COMPLETE / ACCEPTED**。
- S8：management CRUD、typed/unified in-app ingress、bundled presets、Complete Preset Restore、ModelTemplate transfer **COMPLETE / ACCEPTED**；不包含 OS external ingress。
- S9：integrated/native titlebar、native picker、second-instance UX、responsive layout、Previous/First/Next/Bottom、reading position、resizable/collapsible/full-screen composer、keyboard/focus/IME closure、AppSettings exposure、Automatic Backup settings discoverability **COMPLETE / ACCEPTED**。

- accepted production HEAD：`86be0b0ec21aab7a8f15553c0b696b253738917f`
- final package gate：desktopApp **99 suites / 909 tests PASS**；failures/errors/skipped **0**；Desktop compile、`git diff --check`、final `createDistributable` **PASS**。
- final artifact：`app/desktopApp/build/phase6-final-acceptance-distribution/compose/binaries/main/app/ChatChatBarDesktop/ChatChatBarDesktop.exe`
- isolated packaged launch/main window/normal shutdown：**PASS**；stdout/stderr empty。
- computer-use capture：**BLOCKED_ENVIRONMENT**（首次 FrameArrived timeout + activation retry timeout），不判产品失败，不伪称自动 UI capture 成功。
- final manual acceptance：**PASS — Project + 用户已确认**，为本次 finalization 的授权事实；不与自动 capture 证据混淆。
- docs-only finalization 复用上述证据，不重跑测试/打包；不修改 baseline、Prompt/provider/schema 或 production。
- integration：`5850fe28...` → accepted feature + docs commit，**FF-ONLY / no extra merge commit**；master 保持 formal 1.4.1 mirror。

## Phase 4 chat foundation control point

- controlling audit：`docs/desktop/28_PHASE4_CONTRACT_AUDIT.md`；audit **COMPLETE**。
- P4-S1 implementation：`f850ece3df7f36391b1fa4e81c286110f9610844`；Project review：**PASS WITH NON-BLOCKING NOTES**。
- P4-S2 / 4A2 remainder implementation：`bfcd37e2f1f316ae60f733c0146846f66a4f76b4`；Project review：**PASS**；已 ff-only 集成到 `desktop`。
- 4A1：authoritative shared `ChatSession` / `ChatMessage` contract core，**COMPLETE / PROJECT REVIEW PASS**。
- 4A2：authoritative shared `ChatRepository`、ordering/repair/timeline/display-title policies、`CharacterSessionService` 与 Desktop shared service/container wiring，**COMPLETE / PROJECT REVIEW PASS**。
- session creation contract：missing Character explicit failure；title=current card name；仅 live nonblank default Format binding 写入新 session；stale/blank→null；existing session independent；blank/nonblank greeting 均持久化为 opening ASSISTANT。
- P4-S2 validation：focused shared **8 PASS**；Desktop integration **1 PASS**；sharedCore **39 suites / 243 tests / 0 failures / 0 errors / 0 skipped**；desktopApp **29 suites / 255 tests / 0 failures / 0 errors / 0 skipped**；Android JVM **170 suites / 1108 tests / 0 failures / 0 errors / 0 skipped**；Desktop / Android compile 与 `git diff --check` **PASS**。
- packaged/manual acceptance：**not run / not required**。
- Prompt text/runtime、Package/schema version、formal baseline 与 upstream source 均未改变；D-030：**APPROVED 2026-09-26**，只授权 4P physical ownership move。
- 4B1 / P4-S3：`d468f82529ec6e9fa24363e07d1e7fafe6c9ecf0`，**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**；shared `PlaceholderRenderer`、`ContextWindowManager`、`WorldBookEngine`、`WorldBookScanContext`；四个 production moves byte-identical。
- P4-S3 validation：focused shared **37 PASS**；sharedCore **43 suites / 280 tests**；desktopApp **29 suites / 255 tests**；Android JVM **167 suites / 1076 tests**；全部 0 failures/errors/skipped；Desktop / Android compile 与 `git diff --check` **PASS**。
- 4B2 / P4-S4：`8867424df3d53bd291b6b361e51aa5059257284e`，**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**；authoritative shared `WorldBookRequestPlanner`；Android `ChatViewModel` thin delegation；Desktop container 使用同一 planner。
- P4-S4 validation：focused shared planner **14 PASS**；Desktop integration **1 PASS**；sharedCore **44 suites / 294 tests**；desktopApp **30 suites / 256 tests**；Android JVM **167 suites / 1076 tests**；全部 0 failures/errors/skipped；Desktop / Android compile 与 `git diff --check` **PASS**。
- 4B overall：**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**。
- 4P / P4-S5：`e927277dabb206aa34b2374e3596c67a31f0f7db`，**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**；shared `MainChatPromptAuthority` 为 Phase 4 main-chat Prompt 唯一 physical authority；Android `PromptTemplates` facade retained；Prompt literal/runtime zero-drift；`354f151...` drift not absorbed。
- P4-S5 validation：shared **16 PASS**；Android PromptTemplates **23 PASS**；sharedCore **45 suites / 310 tests**；desktopApp **30 suites / 256 tests**；Android JVM **167 suites / 1078 tests**；Desktop/Android compile + `git diff --check` **PASS**。
- 4C / P4-S6：`8bd876bb28c19b39f96a3abb109698132912d9a2`，**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**；shared `MainChatRequestAssembler` owns final logical order + logical cache key；Android delegates；Desktop has same shared authority。
- P4-S6 validation：focused shared **48 PASS**；Desktop integration **1 PASS**；Android serialization/order **3 PASS**；sharedCore **52 suites / 358 tests**；desktopApp **31 suites / 257 tests**；Android JVM **163 suites / 1030 tests**；Desktop/Android compile + `git diff --check` **PASS**。
- Prompt literal/runtime、Provider/network/SSE、Package/schema unchanged；observed upstream drift not absorbed。
- 4D1 / P4-S7：`06b5243282627e5954d5ad4a4c6e6f846a30400d`，**COMPLETE / PROJECT REVIEW PASS / INTEGRATED**；Desktop fake runtime uses real persisted data + shared session/context/WorldBook/Prompt/request authorities；fake driver captures logical messages/cache key only；no Provider/network/SSE。
- P4-S7 validation：fake runtime **10 PASS**；CharacterSession Desktop integration **1 PASS**；WorldBook planner Desktop integration **1 PASS**；sharedCore **52 suites / 358 tests**；desktopApp **32 suites / 267 tests**；Android JVM **163 suites / 1030 tests**；Desktop/Android compile + `git diff --check` **PASS**。
- restart persistence：same app-data root reopened with greeting/USER preserved in order；logical messages/cache key identical；no extra message write。
- 4D2 / P4-S8：`0a89b111e93c3c1a2b9a24127608e0b25e4ef9c4`，**COMPLETE / PROJECT REVIEW PASS / INTEGRATED / PACKAGED MANUAL ACCEPTANCE PASS**；read-only Prompt Inspector uses common Desktop planning + shared assembler trace authority；no second Prompt/order implementation。
- P4-S8 validation：MainChatRequestAssembler **6 PASS**；Desktop Prompt Inspector **5 PASS**；existing fake runtime **10 PASS**；CharacterSession **1 PASS**；WorldBook planner **1 PASS**；sharedCore **52 suites / 358 tests**；desktopApp **33 suites / 272 tests**；Android JVM **163 suites / 1030 tests**；Desktop/Android compile + `git diff --check` **PASS**；`:desktopApp:createDistributable` **PASS**。
- user manual acceptance：packaged executable launch / empty-state Prompt Inspector / remaining executable manual checks **PASS**。Populated-session Inspector manual visual scenario **NOT RUN** because no disposable persisted Desktop chat fixture was available；equivalent persisted-session/request/WorldBook/cache/read-only semantics have automated real-repository PASS coverage。
- Phase 4 overall：**COMPLETE / ACCEPTED**。
- next：**Phase 5 — Model Runtime + Real Chat**。
- formal baseline：ChatBar `1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。
- observed upstream：`354f15166d8bc0462cb87d62a0ba4613794560a3`，HIGH drift，进入后续 selective/batch sync backlog；**NO SYNC**。
