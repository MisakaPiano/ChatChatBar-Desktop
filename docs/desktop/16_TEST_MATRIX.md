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
- migration snapshot
- restore backup

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

---

## H. Prompt

验证最终 logical message list：
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
- HTTPS
- allowed cleartext HTTP role adaptation
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
