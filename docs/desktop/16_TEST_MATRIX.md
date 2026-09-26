# CCB Desktop Compatibility Test Matrix

原则：
- 测的是行为、不变量和协议。
- 不把持续编辑的 preset/seed 当固定测试夹具。
- Prompt 测试不机械断言可编辑自然语言全文，除非该文本是外部协议本身。
- 人工 FIXTURE 与自动 inline fixture 分开。

---

## A. Build

| 测试 | Android | Desktop |
|---|---:|---:|
| Kotlin compile | 必须 | 必须 |
| JVM unit tests | 必须 | 必须 |
| UI compile | 必须 | 必须 |
| package artifact | release gate | release gate |

---

## B. Storage

- save/load one entity
- loadAll
- flow update
- atomic replacement
- process interruption before replace
- corrupt JSON skip/visible diagnostics according to upstream behavior
- streaming prefix replacement rollback
- custom Desktop data root
- Portable Mode
- packaged `ApplicationHome` 不依赖 working directory
- whole application-image relocation 后 `ApplicationHome` 跟随新位置
- Portable packaged positive smoke
- invalid Portable authority 不得 fallback 到 default root
- migration snapshot
- restore backup

Process-local data coordination：
- independent normal operations may overlap；不同 Entity types 保留既有并发
- maintenance pending 阻止新的 unrelated normal admission；existing operations drain 后才进入 exclusive
- exclusive waiters FIFO，不发生 normal traffic starvation
- waiting / active operation cancellation 与 exception 必须释放 registration
- same-gate nested normal entry 跨 `withContext(IO)` 不重复登记；structured child work 在 outer registration 释放前完成
- snapshot mutation 使用 exclusive maintenance
- restore success 与 incomplete rollback seal `RESTART_REQUIRED`
- scheduler pause/stop/join 必须先于 exclusive maintenance
- maintenance paused / restart-required 时 settings mutation 被拒绝
- shutdown drains coordinator，不 force-interrupt in-flight filesystem transaction
- Android/default shared wiring 使用 no-op gate，保持既有 storage behavior

Per-root cross-process ownership：
- same root 阻止第二个 cooperative writable process；structured result 区分 same-JVM overlap 与 cross-process contention
- different roots 可以同时取得 ownership
- stale zero-length lock file 不阻止 startup；non-empty / unsafe lock target 必须拒绝
- normal process release 与 forced process termination 后均可 reacquire；process death 后 lock file 可保留，但 file existence 不表示 active ownership
- Windows child process 使用 stdout READY handshake、stdin command / EOF 与 `Process.waitFor()`；不使用 arbitrary fixed sleep 作为 ownership synchronization
- ownership acquisition 必须先于 container/runtime startup；失败时不构造 container，也不 fallback 到其他 root
- shutdown 顺序为 runtime → coordinator → ownership
- initialization / application / container-close failure 仍释放 ownership；primary failure 与后续 suppressed failures 保持顺序
- root lock artifact 不进入 snapshot payload / manifest；crafted snapshot lock artifact 必须拒绝，restore 必须保留 live lock
- reserved 判断只适用于 root direct child；nested `.ccb-desktop.lock` 保持 ordinary payload semantics

Migration destination / authority safety（D1）：
- destination missing 时只允许在既有 safe parent 下创建 exact final directory；identity、nesting、UNC、ApplicationHome 与 emptiness rules 必须验证
- destination ownership 在检查 empty contract 前取得并由 prepared handle 保持；除 root-level `.ccb-desktop.lock` 外任何 direct child 都视为 non-empty
- bootstrap authority writer 使用 `ChatChatBarDesktop.bootstrap.lock` 做跨进程序列化；same-JVM、child-JVM contention、normal release 与 forced termination/reacquire 均覆盖
- authority lock 内 fresh-load bootstrap，执行 expected-source CAS-like validation、higher-priority Portable revalidation、unknown-field preservation、atomic save 与 readback classification
- bootstrap save failure 区分 `CommitFailedPreCommit` 与 `CommitIndeterminate`；ambiguous authority state 不得表述为 rollback-safe
- validation：desktopApp **15 suites / 171 tests PASS**；Windows bootstrap-authority child JVM **3 executed / 0 skipped**；Desktop compile 与 `git diff --check` **PASS**

Migration materialization（D2 / R1）：
- source 只读；raw bytes、unknown safe root entries 与 empty directories 原样 materialize，不做 JSON reserialization
- 只迁移 valid completed `MANUAL` / `AUTOMATIC` / `PRE_RESTORE` snapshots；invalid / recovery / unknown backup evidence 留在 source 并给出 warning
- destination-local staging、SHA-256 manifest/tree validation、no-overwrite install、explicit installed-entry ledger 与 complete/incomplete rollback 均覆盖
- install / validate / rollback 位于 `NonCancellable` boundary；copy cancellation、install collision、post-commit cleanup warning 与 retained workspace 均覆盖
- confirmed migration workspace 必须同时匹配 `.migration-*.tmp` 名称和 dedicated marker/token；name-only unknown safe directory 作为 ordinary payload 迁移
- marker tamper/removal、active-root provenance、backup warning provenance 与 retained-workspace marker 均覆盖
- D2 initial：desktopApp **16 suites / 186 tests PASS**、sharedCore **11 suites / 114 tests PASS**；Desktop compile 与 `git diff --check` **PASS**
- D2-R1：desktopApp **16 suites / 192 tests PASS**（materializer **21 PASS**）、sharedCore **11 suites / 114 tests PASS**；Desktop compile 与 `git diff --check` **PASS**

Migration orchestration（D3）：
- exact ordering：destination prepare → runtime pause / scheduler stop-join → coordinator exclusive → preflight → mandatory `MANUAL` safety snapshot → D2 materialization → bootstrap authority transaction → restart seal / runtime disposition
- real filesystem success：active payload byte-identical copy、safety snapshot 进入 migrated valid backup history、bootstrap readback 为 `CUSTOM(destination)`
- `Committed` / `CommitIndeterminate` 均 seal coordinator 与 runtime `RESTART_REQUIRED`；success 后 destination `FileLock` 保持到 service/container close，再可 reacquire
- preparation / pause / preflight / snapshot / materialization failures，以及 authority `Busy` / `AuthorityChanged` / `CommitFailedPreCommit`，均覆盖 source resume、destination ownership release 与无 false restart seal
- D2 structured failure evidence、scheduler precommit resume、runtime-resume failure、destination-close failure、concurrent migration serialization 与 ordered shutdown 均覆盖
- cancellation during D2 会稳定释放并恢复 source；authority commit 附近 cancellation 不能跳过 NonCancellable classification + restart seal
- validation：desktopApp **17 suites / 208 tests PASS**、sharedCore **11 suites / 114 tests PASS**；Desktop compile 与 `git diff --check` **PASS**

User-facing root switch（Phase 2B4 final adapter / R1 / R2）：
- supported bootstrap-controlled provenance、unsupported Portable / CLI provenance、directory-only picker cancel、confirmation cancel、double-confirm suppression 与 terminal restart-required behavior 均覆盖
- successful controller flow 保持 current/root identity 为 startup source，并仅把 validated committed destination 表示为 next-start root；window close 在 migration 中 defer，exit action 在 terminal state 保持可用
- real D1/D2/D3 controller migration 覆盖 mandatory `MANUAL` snapshot、source retention、destination ownership retention，以及 service/container close 后 ownership release
- retryable precommit failure 允许重新选择/重试；`CommitIndeterminate`、runtime-resume terminal failure 与 restart-sealed cancellation 保持 terminal，绝不回到可操作的 `Idle`
- runtime pause、pre-authority 与 authority/restart-seal 周边 cancellation 均使用 deterministic handshakes；migration 期间 window close defer，normal exit path 保持有序 shutdown
- precommit cancellation 在 D3 已恢复 source 且 restart probe 为 false 时回到 `Idle`；sealed cancellation 在 probe 为 true 时进入 `RestartRequired`，保留 attempted destination，`nextStartRoot = null`，并 rethrow 原始 `CancellationException`
- destination label 只在 `DesktopMigrationMaterializationResult.Materialized` 时使用 “Destination copy”；preparation / preflight / safety-snapshot / materialization failure 使用 “Attempted destination”
- non-empty destination 在 preparation 拒绝，不覆盖 existing payload，并保留 retry / choose-another actions
- initial + R1 validation：desktopApp **18 suites / 231 tests PASS**、sharedCore **11 suites / 114 tests PASS**；Desktop compile、`git diff --check` 与 `createDistributable` **PASS**
- R2 validation：focused label regression **1 PASS**；desktopApp **19 suites / 232 tests PASS**；Desktop compile、`git diff --check` 与 `createDistributable` **PASS**
- packaged positive manual acceptance（isolated `LOCALAPPDATA`）：source `H:\CCB-Acceptance\LocalAppData\ChatChatBarDesktop` → destination `H:\CCB-Acceptance\Destination`；mandatory `MANUAL` snapshot、raw payload copy、source retention、restart-required 与 relaunch `BOOTSTRAP_CUSTOM` destination **PASS**
- packaged negative manual acceptance：non-empty `H:\CCB-Acceptance\NonEmptyDestination` 在 `PREPARATION` 拒绝，`do-not-overwrite.txt` 未改变、source authority 未提交、retry actions 可用 **PASS**
- R2 label manual retest：`PREPARATION` 显示 “Attempted destination” **PASS**；该次仅验证 label，使用 normal Windows `LOCALAPPDATA`，隔离 root 的正/负迁移证据来自前两项 acceptance

Upstream 1.4.0 reconciliation（historical validated sync）：
- full regression：sharedCore **11 suites / 114 tests**、desktopApp **12 suites / 137 tests**、Android JVM **186 suites / 1158 tests**；均为 0 failures / 0 errors / 0 skips
- storage safety：singleton Missing / Corrupt / ReadError、corrupt overwrite prevention、atomic-write failure、retry、cancellation、concurrent singleton access、partial `saveAll` completion、cache-only `observeAll`
- model migration：legacy dedicated retrieval configuration collision / idempotence / restart safety
- Prompt transport：START / END / BOTH，覆盖 HTTPS 与 allowed cleartext local HTTP serialized order
- streaming：`AiStreamProgress` propagation、meaningful-output inactivity watchdog、whitespace output 与 terminal flush
- NovelAI Studio：structured clipboard、legacy compatibility、overwrite / clear-except-style / validation
- memory：journal-first partial-success replay / deletion recovery
- compile：Desktop / Android **PASS**；`git diff --check`：**PASS**

Upstream 1.4.1 reconciliation（latest validated sync）：
- full regression：sharedCore **11 suites / 114 tests**、desktopApp **12 suites / 137 tests**、Android JVM **186 suites / 1161 tests**；均为 0 failures / 0 errors / 0 skips
- interrupted reply：`InterruptedReplyPolicyTest` **3 PASS**，覆盖 body-only、reasoning-only 与 fully empty / wrong-role policy
- current-turn serialization：`CurrentTurnMessageOrderTest` **11 PASS**，覆盖 blank continue 的 latest-USER body/images/ID reuse、history exclusion、single occurrence 与 latest non-USER fallback
- model defaults：`ModelConfigurationTest` **28 PASS**，覆盖 temperature `1.0`、common `reasoning_effort = low` 与参数合同
- long-term-memory/source-turn：`MemorySourceFingerprintTest` **3 PASS**、`TimelineTurnPolicyTest` **4 PASS**，覆盖 reasoning-only durable evidence 与 turn identity consistency
- compile：Desktop / Android **PASS**；`git diff --check`：**PASS**；`PromptTemplates` text 未变化，continuation pipeline/runtime semantics 已验证

---

## C. Character Package

自动：
- schema 3..9 decode
- schema 10 reject until upstream supports
- schema 9 encode
- missing image refs reject
- blank name reject
- empty structured placeholders filtered
- defaultFormatCard v9 behavior

3C1 shared resource/materialization regression：
- Character import、duplicate、overwrite、delete 与 Package↔Entity materialization
- Android absolute references、bundled `asset:`、Base64 resource handling
- Desktop root-relative references、path traversal/escape rejection、`appDataRoot A → B` relocation
- 整个 Character filesystem transaction 只通过同一 data-operation gate 参与一次
- WorldBook / FormatCard exact reuse 与 conflict-new semantics
- pre-commit owned-resource rollback、post-commit cleanup failure visibility
- Character durable entity delete-before-resource/RAG ordering
- Character / WorldBook / FormatCard strict transfer-specific delete failure propagation

3C1 accepted validation：sharedCore **21 suites / 170 tests / 0 failures**；desktopApp **22 suites / 238 tests / 0 failures**；Android JVM **181 suites / 1147 tests / 0 failures**；Desktop compile、Android compile 与 `git diff --check` **PASS**。

3C2 shared ST/classifier regression：
- ST V1/V2、missing V2 data、unknown fields、case-insensitive Chara、MIME Base64 whitespace、missing chunk 与 original PNG retention
- mapper schema 5、FREEFORM sections、四种 placeholders、tag/greeting cleanup、alternate promotion、creator metadata 与 injected Prompt policy
- Character Book success 进入 `package.worldBooks`；failure 非致命并触发 logging seam；embedded `characterBook` 保持 null
- content-first ChatBar/ST JSON/PNG、ST World Info object/array、ordinary/NovelAI/GIF image、BOM、ambiguity、invalid/foreign payload 与 strict manual target
- Android typed ModelTemplate facade classification / strict validation

3C2 accepted validation：ST parser **5 PASS**、ST mapper **5 PASS**、shared classifier **7 PASS**、Android ModelTemplate facade **2 PASS**；sharedCore **24 suites / 187 tests / 0 failures**；desktopApp **22 suites / 238 tests / 0 failures**；Android JVM **181 suites / 1141 tests / 0 failures**；Desktop compile、Android compile 与 `git diff --check` **PASS**。Android Uri/ContentResolver ingress 未在本 slice 单独做 device/provider test，留待 user-facing import/interoperability gate。

3P Prompt ownership closure：
- `CharacterNaiPromptDefaults` 与 `AuthoritativeCharacterTransferPromptPolicy` 为 Android/Desktop 共用 authority；Android `PromptTemplates` facade delegation 与 shared transfer policy 一致
- Prompt literal、Prompt behavior、`PromptAssembler`、`ContextWindowManager` 与 final API-message ordering 均保持不变
- implementation `f722703c33d8cd96728fc06ff617c9d7d79d9c7d`：Project review **PASS**

3D Desktop typed transfer regression：
- Character CCB JSON/PNG、ST V1/V2 JSON/Chara PNG import；CCB JSON/PNG export；default FormatCard/resources/documents/worldbooks 与 conflict New/Overwrite/Cancel
- FormatCard JSON import/export 与 WorldBook ChatBar/ST World Info import/export
- exact PNG Package payload codec 与 platform-equivalent AWT cover renderer；safe sibling-temp external writer
- community overwrite protection、root-relative resource relocation/export、bundled asset seam 与 Character DOCUMENT RAG cleanup
- shared `VectorChunk` / `ChunkSourceType` serialized contract 与 `VectorChunkStorageContract.ENTITY_TYPE = vector_chunks`

3D accepted validation：sharedCore **27 suites / 196 tests / 0 failures / 0 errors / 0 skipped**；desktopApp **27 suites / 251 tests / 0 failures / 0 errors / 0 skipped**；Android JVM **181 suites / 1142 tests / 0 failures / 0 errors / 0 skipped**；Desktop compile、Android compile 与 `git diff --check` **PASS**。

3D packaged/manual gate：`:desktopApp:createDistributable`、packaged visible-window launch smoke、isolated `LOCALAPPDATA` Character import/export/reimport-conflict/import-as-new/copy naming、Format/WorldBook typed transfer 与 root-switch coexistence **PASS**。该 gate 不替代 3F Android ↔ Desktop bidirectional interoperability；3F 已在后续独立 gate 完成。

人工跨端：
- Android export JSON → Desktop import
- Desktop export JSON → Android import
- assets, documents, worldbooks, Fish binding
- FREEFORM
- STRUCTURED
- multi-character

---

## D. CCB PNG

- payload chunk decode/encode
- same Package JSON semantics after PNG round trip
- cover without background
- custom background
- title/gradient crop
- image metadata survives
- Android PNG → Desktop
- Desktop PNG → Android

视觉 renderer 允许平台字体 raster 差异，不要求 byte-identical PNG。

3D 已验证 CCB PNG metadata/payload `EXACT`，Desktop AWT visual cover rendering `EQUIVALENT`。

3F Android ↔ Desktop interoperability gate（checkpoint `717ec1a473660b5d186a4241778552bc6f12a80b`）：
- targeted `Phase3FAndroidInteropTest`：Android API 34 **PASS**；Android API 36 **1/1 PASS，0 failed，0 skipped**
- bidirectional artifacts：Android ↔ Desktop Character JSON、CCB PNG、STRUCTURED/FREEFORM、多角色、avatar/background/appearance images、UTF-8 documents、embedded WorldBook/default FormatCard/ordered tools 与 `FishAudioVoiceBinding`
- standalone contracts：FormatCard、WorldBook、SillyTavern World Info decode；Android `ContentResolver` / `FileProvider` ingress
- failure cases：corrupt/invalid artifacts 与 import atomicity；未发现 production interoperability defect
- full API 36 `connectedDebugAndroidTest`：**NOT RUN TO COMPLETION**；按 stop rule 在 focused known failures 重现后停止，不得记为全套 PASS
- unrelated pre-existing instrumented debt：`LongTermMemoryScopedCommitTest` **4 tests / 1 pass / 3 fail**（`NoSuchElementException` 与两个 5s timeout）；`NovelAiStylePresetGalleryTest` **5 tests / 2 pass / 3 fail**（node count、missing scroll node、duplicate `setContent`）；`ChatLongScreenshotRenderTest` **1 fail**（无 lifecycle owner，随后 Compose detach process crash）
- persistent local API 36 environment 已建立；机器特定 helper path 不属于 repository contract

---

## E. ST Compatibility

- V1/V2 JSON
- Chara PNG
- Character Book
- ST World Info
- placeholders
- unknown/unsupported lorebook visible error
- official mapper semantics preserved

---

## F. FormatCard

- v1/v2 decode
- userTools order
- RANDOM_NUMBER
- STRONG_PROMPT_SUFFIX
- default card binding
- character v9 embedded default FormatCard
- conflict reuse/new behavior
- character editor searchable single-select presentation
- repository create/edit/delete live refresh
- stale binding remains visible and requires explicit removal

---

## G. WorldBook

纯策略：
- key scan
- secondary logic 0/1/2/3
- case sensitivity inheritance
- whole-word inheritance
- regex
- probability
- group competition
- recursion
- sticky/cooldown/delay
- character filter
- token budget
- OUTLET
- current input participation
- timed effect persistence

Deterministic parity：
- 使用 `probability = 100` 或不会进入随机筛选的 fixture。
- 同一 snapshot 输入，Android/shared 与 Desktop 必须得到相同 entry IDs/order/reasons。

Probabilistic behavior：
- 分别验证概率通过与拒绝分支。
- 未来 shared core 若引入可控 random seam，使用相同 random decisions 做跨端 parity。
- RNG 未受控时，不以两个独立运行逐次得到完全相同的 IDs/order/reasons 作为验收条件。

Character binding UI：
- searchable WorldBook multi-select presentation
- repository create/edit/delete live refresh
- stale WorldBook binding remains visible and requires explicit removal

---

## H. Prompt

验证最终 logical message list：
- blank `systemPrompt` override = fixed prefix + default middle + fixed suffix
- custom override replaces only the middle
- `{{original}}` expands the default middle only
- legacy/full custom `systemPrompt` compatibility case
- final first logical system message behavior
- roles
- section inclusion/omission
- order
- START/END/BOTH
- WorldBook + setting RAG
- Archive/history/memory RAG/HEAD
- previous-turn hot zone
- current user
- post-history
- strong suffix
- final CCB tail

Transport：
- HTTPS serialized transport
- allowed cleartext HTTP role adaptation regression
- debug request equals serialized transport body

不要把 UI preview 当真值。

---

## I. Model Runtime

- auth inheritance
- blank local HTTP key
- no empty Bearer
- model fallback
- model discovery
- SSE stop
- SSE early EOF
- finish_reason
- refusal/filter
- length truncation
- read timeout
- cancellation
- reasoning/thinking fields
- output token key selection
- custom params
- connection test

---

## J. Chat

- P4-S1 shared authority gate：
  - focused shared：**39 PASS**
  - sharedCore：**38 suites / 235 tests / 0 failures / 0 errors / 0 skipped**
  - desktopApp：**28 suites / 254 tests / 0 failures / 0 errors / 0 skipped**
  - Android JVM：**171 suites / 1110 tests / 0 failures / 0 errors / 0 skipped**
  - Desktop compile、Android compile、`git diff --check`：**PASS**
  - pre-extraction-compatible Android JSON → shared decode → repository rewrite/reopen fixture：**PASS**
- shared fixture 覆盖 ChatSession/ChatMessage defaults、nullable fields、alternatives/display content、generated metadata、order/source-turn/legacy timeline round-trip。
- shared repository 覆盖 session CRUD/pin/display title、draft/scroll、message append/update/delete/insert-after、paging/index/reopen、preview、replace、source-turn assignment/lazy migration/tombstone/helpers。
- P4-S1 未要求也未执行 real-user-data manual migration 或 packaged/manual acceptance；未改变 Prompt、Package/schema、formal baseline 或 upstream source。
- P4-S2 / 4A2 remainder gate：
  - focused shared `CharacterSessionServiceTest`：**8 tests / 0 failures**
  - focused Desktop integration：**1 test / 0 failures**
  - sharedCore：**39 suites / 243 tests / 0 failures / 0 errors / 0 skipped**
  - desktopApp：**29 suites / 255 tests / 0 failures / 0 errors / 0 skipped**
  - Android JVM：**170 suites / 1108 tests / 0 failures / 0 errors / 0 skipped**
  - Desktop compile、Android compile、`git diff --check`：**PASS**
  - shared service covers missing Character failure/no session、current-name title、valid/blank/stale default Format binding、warning seam、existing-session independence、nonblank greeting and blank opening ASSISTANT greeting。
  - Desktop container integration proves the same shared `ChatRepository` + `CharacterSessionService` can persist/read session + opening greeting。
  - packaged/manual acceptance：**not run / not required**。
- P4-S3 / 4B1 shared Context + WorldBook core gate：
  - focused shared：PlaceholderRenderer **3 PASS**；ContextWindowManager **15 PASS**；WorldBookEngine **13 PASS**；WorldBookMatchingOptions **6 PASS**；total **37 PASS**
  - sharedCore：**43 suites / 280 tests / 0 failures / 0 errors / 0 skipped**
  - desktopApp：**29 suites / 255 tests / 0 failures / 0 errors / 0 skipped**
  - Android JVM：**167 suites / 1076 tests / 0 failures / 0 errors / 0 skipped**
  - Desktop compile、Android compile、`git diff --check`：**PASS**
  - production `PlaceholderRenderer` / `ContextWindowManager` / `WorldBookEngine` / `WorldBookScanContext` were byte-identical moves into sharedCore
  - Android `WorldBookMatchingOptionsTest` retains only editor/transfer integration; pure engine/scan-context assertions are shared
  - `Math.random` deterministic probability parity intentionally not asserted
  - 4B2 request-planner extraction、packaged/manual acceptance、device instrumentation：**not run / not required for 4B1**
- P4-S4 / 4B2 request-planner gate：
  - focused shared `WorldBookRequestPlannerTest`：**14 PASS**
  - focused Desktop planner/container integration：**1 PASS**
  - sharedCore：**44 suites / 294 tests / 0 failures / 0 errors / 0 skipped**
  - desktopApp：**30 suites / 256 tests / 0 failures / 0 errors / 0 skipped**
  - Android JVM：**167 suites / 1076 tests / 0 failures / 0 errors / 0 skipped**
  - Desktop compile、Android compile、`git diff --check`：**PASS**
  - source resolution、duplicate-ID precedence/order、effective scan depth、transient USER、regeneration exclusion、character tokens、composite+legacy timed keys、BEFORE/AFTER unified prompt、OUTLET split、player placeholder 与 no-book diagnostic 均有 focused coverage
  - Android delegation 通过完整 JVM regression + compile 验证；未新增 giant ViewModel harness
  - packaged/manual acceptance、device instrumentation：**not run / not required for 4B2**
- P4-S5 / 4P main-chat Prompt ownership gate：
  - focused shared `MainChatPromptAuthorityTest`：**16/16 PASS**
  - Android `PromptTemplatesTest`：**23/23 PASS**，含 facade parity coverage
  - sharedCore：**45 suites / 310 tests / 0 failures**
  - desktopApp：**30 suites / 256 tests / 0 failures**
  - Android JVM：**167 suites / 1078 tests / 0 failures**
  - Desktop compile、Android compile、`git diff --check`：**PASS**
  - Project direct source review：27 moved literals/section constants character-for-character equal；12 moved builders/helpers preserve behavior；Android duplicate moved literals absent；`354f151...` Prompt drift not absorbed
  - PromptAssembler/final logical order/provider/package/schema：unchanged
  - packaged/manual acceptance、device instrumentation：**not run / not required for 4P**
- P4-S6 / 4C shared logical-request gate：
  - focused shared：**48 PASS**（PromptAssembler 21 / history 9 / memory 3 / user-tool 5 / main assembler 6 / ChatApiMessage 3 / FormatPromptPosition 1）
  - focused Desktop container/assembler integration：**1 PASS**
  - focused Android serialized-request/order：**3 PASS**
  - sharedCore：**52 suites / 358 tests / 0 failures / 0 errors / 0 skipped**
  - desktopApp：**31 suites / 257 tests / 0 failures / 0 errors / 0 skipped**
  - Android JVM：**163 suites / 1030 tests / 0 failures / 0 errors / 0 skipped**
  - Desktop compile、Android compile、`git diff --check`：**PASS**
  - Project review：full logical order、START/END/BOTH、Archive/history/memory/HEAD/previous-turn、current USER exactly-once、STRONG_PROMPT_SUFFIX placement、stable-prefix cache boundary、transport separation **PASS**
  - live provider、packaging、device instrumentation：**not run / not required for 4C**
- create session
- greeting
- send
- streaming
- user stop draft
- regenerate
- edit/delete
- abnormal history roles
- speaker tags
- format repair
- session pin
- duplicate session

---

## K. RAG

- chunking
- embeddings
- vector search
- document status
- retrieval planner
- chat-memory source grouping
- prompt injection position

---

## L. Long-Term Memory

按官方 skill/state machines：
- Episode
- Arc
- Era
- compression
- Archive
- HEAD
- Gap
- backfill
- source mutation
- tombstone
- repair
- regenerate
- cancellation
- persisted failure/retry
- app-scope coordinator

Desktop 必须证明 UI 页面关闭不会错误取消 application-owned work。

---

## M. SaveSlot

旧：
- schema 1–7 list/import/load

v8：
- manifest
- messages JSONL
- RAG JSONL
- optional voices
- image NONE
- COMPRESSED
- ORIGINAL
- cancellation
- media materialization
- transactional replacement
- cleanup

跨端：
- Android create → Desktop restore
- Desktop create → Android restore

---

## N. NovelAI

- auth
- account
- models
- size
- seed
- Prompt Designer
- tag research
- translation
- metadata
- image stream
- batch
- 429
- guidance
- vibe
- inpaint
- regeneration
- automatic chat images
- history
- Studio

图像 codec/resize 可采用不同底层，但必须满足上游策略输出。

---

## O. Fish Audio

- secret store
- voice binding
- tag model
- generation
- batch
- storage
- playback
- anchors
- SaveSlot
- language/audiobook mode
- FREEFORM `CharacterInfo` Fish binding save/reopen
- `CharacterInfo` ID preservation
- speaker-name matching
- character package round-trip regression

---

## P. QQ Voice

Android 现有行为建立参考手工测试。

Desktop：
- 如果找到可靠 Windows QQ 等位集成，验证端到端
- 否则保持 BLOCKED，并验证替代工作流不会假装自动发送成功

---

## Q. Moments

- enable gates
- schedule range/limits
- catch-up
- text-only
- image
- retry checkpoint
- like/private
- edit/delete
- unread
- no generation while app closed（保持产品语义）
- scheduler session chat-model precedence
- debug session chat-model precedence
- retry session chat-model precedence
- image design/research session image-model precedence
- on-demand image design precedence
- null/stale model ID fallback
- NovelAI rendering model priority remains independent: session → character default → global

---

## R. Community

- runtime enable
- anonymous browse
- pagination
- preview
- download
- conflict
- Discord auth
- upload
- overwrite
- delete
- read-only downloaded card
- remote update detection

---

## S. Shared Import

- file association/Open With
- drag/drop
- content-first detection
- wrong extension/MIME
- FIFO
- conflict handling
- unknown format
- images
- ST/CCB PNG
- staged file cleanup

---

## T. Update

- update metadata
- version compare
- download
- incomplete file
- hash/signature validation（Desktop 方案确定后）
- user-confirmed installer
- cancellation
- current build remains usable on failure

---

## U. Release Gate

完整 release 前：
- `FEATURE_PARITY` 无 UNKNOWN
- baseline 固定
- migration backup tested
- data restore tested
- clean install
- update install
- no secret in repository/artifact/log
- upstream license/public redistribution permission reviewed
