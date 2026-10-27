# 107 — P8→P10 Single-Branch Overnight Long-Run Implementation Contract

**USER REQUEST: ONE LONG CODEX HANDOFF, NO ADDITIONAL BRANCH OR WORKTREE / PROJECT SCOPE AUTHORIZATION, NOT FINAL ACCEPTANCE — 2026-10-12 JST**

## 0. Explicit feasibility statement

A **single long, best-effort implementation session** on the already existing `feature/phase8-fish-audio` branch is authorized as an *engineering handoff*. It is **not feasible to guarantee** that P8, P9 and P10 will all reach official EXACT/EQUIVALENT parity and Windows acceptance within one unattended eight-hour-plus run: P8 has only S0/source audit, P9 has no independent completed Project source audit, P10's 1.4.1 owner spans 30 memory-domain files, journal and coordinator semantics, and a prior Windows launcher/JVM exit defect remains unresolved. The Codex platform's actual per-task lifetime, 5-hour quota windows, weekly usage and interruptions are outside Project control. **Do not insert artificial waits to fill 8 hours or claim guaranteed overnight completion.** Prefer safe, tested working code and checkpoints to a wall-clock target.

The user wants uninterrupted implementation progress while asleep. This document allows **provisional production commits within this feature branch after the previous automated gate is clean**, with Project review **deferred to the handoff**. This is NOT the Project accepting a milestone, not a waiver of Prompt-ownership approval, and not permission to merge or move a protected branch. Any failed mandatory gate blocks later dependent implementation. Do not phrase provisional S0D/S1 progress as Project PASS.

## 1. Fixed repository, source authority and execution environment

- Fork `MisakaPiano/ChatChatBar-Desktop`; local root `H:\ChatChatBar-Desktop`, Gradle root `app/`.
- **ONLY allowed branch:** `feature/phase8-fish-audio`, started from `81a6a0fde81f5f26651e215cb39bfe850641f0bc` at handoff. Verify the exact remote HEAD before changing code; if the remote has advanced, fetch and FF-only after clean diff/ancestry inspection, never rewrite unexpected work.
- **NO other feature, fix, release, sync or temp Git branches; NO additional Git worktree; NO PR or merge; NO forced ref update.** Normal temporary files in build directories may be used, with bounded cache cleanup only if already authorized by Project.
- **Do not move** `desktop`, `master`, `sync/1.4.4`; do not write upstream. No version/Package/schemaVersion blanket bump or promotion of `UPSTREAM_BASELINE.json`.
- Official exact runtime semantic source: `SaltyFishOTL/ChatChatBar 1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`; selective already accepted P7 fixes remain authoritative in fork, no incidental downgrade. Latest observed 1.4.5 is a **separate P11 .cbbackup target**, not a blanket adoption of P8–P10 behavior.
- Read Project `00`, `10`, `21`, `17`, `13`, `14`, `16`, `105`, `106`, `94`, `97`, `95`. Read official source-based Skills: `chatbar-fish-audio-voice`, `chatbar-feature-map`, `chatbar-long-term-memory` plus `references/invariants.md`, `references/state-machines.md`; `chatbar-model-request-runtime` and `chatbar-prompt-pipeline` where request/RAG/Memory injection touches them. Do not recursively read all Git trees.

## 2. Global safety, honesty and cost constraints

- No actual paid Fish/NovelAI/LLM/Embedding requests; use MockWebServer/fakes and synthetic isolated data. Never use a user's real token, DPAPI material, app-data root, Portable Mode, backups, chat media, secrets or actual personal files in tests. No remote telemetry/secret upload.
- No generated voice in ChatMessage/Prompt/RAG/Memory, no RAG node disguised as memory Archive, no memory node disguised as RAG. Preserve Package/Entity/final serialized API request ownership; compare serialized API message/request, not only UI preview.
- **Prompt literal lock:** do not modify existing official Prompt text, AI instruction strings, system confirmation, memory-generation text, or Prompt ownership to make integration easier. Exact byte-identical relocation may be possible only with proof; if a text change is required, record original literal/proposed diff/behavior and **BLOCK that subtask pending the user's explicit approval**. Continue only unrelated independent work. Never improvise another Prompt equivalent.
- Preserve JsonFileStorage atomic writes, unknown/corrupt byte retention, snapshot/root locks, DPAPI, SaveSlot portability handoff and request cancellation; don't automatically clear or rewrite any persisted user data. Do not fabricate full data migration before P11.
- Don't pull arbitrary third-party dependencies unless exact license, Windows packaging and OS architecture match are verified. No Electron/WebView/browser/localStorage/IndexedDB; Kotlin/JVM, Compose Desktop JDK17.
- Historical B2/WIN launcher/JVM pairs from P7 remain protected; **never force terminate these old processes, delete their runtime directories or unlock their user data root**. Current local build headroom reported ~38.95 GiB; recheck volume availability and keep an initial conservative >=5 GiB free threshold before heavy builds. No repeat of completed 43-directory `104` cleanup, no `git clean -fdx`, `gradlew clean` or global pruning.
- Run only one Gradle/test/package operation at a time, bounded worker count. Keep exactly one new final-package distribution per major code checkpoint unless tests require a second; don't accumulate 30 duplicates. Keep all new gate logs/manifest/RED evidence, no automatic removal of diagnostics.
- If tool/runtime limits, context capacity or quota interrupt execution, commit/push any **verified clean checkpoint**, write a precise STOP handoff, and end. Do not claim eight hours were spent unless actually observed; no self-resume in background.

## 3. Execution pipeline on SAME branch; no Project approval claims

Every checkpoint needs source SHA, changed paths, source authority, specific tests/compiles, exact blocked risks. Use targeted commits and push to **the same existing feature ref** after gates PASS. Don't bundle all three domains in one unreviewable commit. A failure in an upstream contract, data loss, test assertion or storage state blocks dependent work. A noncritical formatting/test-harness issue may be fixed within the narrow current checkpoint, never hidden.

### Gate A — P8-S0D real Windows safe shutdown, FIRST

Authority `105_DESKTOP_SHUTDOWN_PROCESS_LIFETIME_GATE.md`.

- Inspect clean exit flow `Main.kt`: `application(exitProcessOnExit=false)` → Compose returns → `appContainer.close()` drain → native picker close → DesktopDataRootOwnership release → explicit safe terminal JVM exit if proven necessary. First instrument sanitized stage IDs in a **disposable isolated profile**, then discriminate success-with-thread-residency vs earlier Compose/drain hang.
- Only if all persistence stages succeed, provide a testable outer-main final exit guarantee; never put unconditional exit in Window callback or in a finally before safe drain. Unsafe pending writers cannot be force-exited or have locks discarded; expose actionable failure/deferred-close state. If actual blocker is earlier than main return, repair that exact blocker.
- Unit tests for normal/failure ordering and ownership retention; packaged real Windows launcher + loaded-JVM child process **>=5 safe WM_CLOSE/titlebar loops** with no lingering processes; synthetic data persisted/reopened and root lock reacquired. Process fixtures may not kill the old historical P7 PIDs.
- **Gate A continuation condition**: all affected tests, real packaged process check, and data-safety checks PASS. If not, STOP further production work. You may use remaining context to prepare read-only P8/P9/P10 dependency map and patch plan, but **do not implement P8 S1 or later domains**. Mark the symptom still unresolved if reproduction/diagnostic evidence is insufficient.

### Gate B — P8-S1 exact-source Fish JVM-neutral core

Authority `97_PHASE8_S0_PROJECT_REVIEW.md`, selected upstream 1.4.1.

- Move three Voice Entities `GeneratedVoiceMessage`, `VoiceAnchor`, `VoiceAnchorState` to shared, preserving serializer shape/defaults and existing single shared `FishAudioVoiceBinding`.
- Move `FishAudioModels`, `VoiceAnchorPolicy`, `VoiceGenerationPolicy`, `VoiceMessageVersionPolicy` to shared, using existing Roleplay and message alternative version authority; extract `FishAudioRequestFactory` + `FishAudioTtsRequest` from mixed Android service into one shared physical definition. No duplicate Android implementation.
- Port exact upstream tests (RequestFactory/Anchor/Generation/Version), add serializer/legacy/null, FREEFORM/STRUCTURED card package and Android facade tests. Compiles: shared, Desktop, Android JDK17; full affected regression and `git diff --check`.
- Do NOT move or modify TagService/PromptTemplates, Fish coordinator/storage/playback, HTTP secrets or chat UI in this checkpoint.
- **Gate B continuation condition**: request serialization and versions/anchor golden tests and affected 3-module compilation PASS. Otherwise checkpoint STOP. Record P8 S1 as *PROVISIONAL ENGINEERING*, never Project ACCEPTED.

### Gate C — P8 S2–S6 best-effort official voice runtime and Desktop equivalent

Before editing, trace official entity/model/voice/concurrency/storage/UI tests and write a compact dependency/safety map to the local handoff (not a new branch). In order:

1. **S2 credentials/model library/picker/bindings:** reuse Desktop DPAPI SecretStore with typed Fish key; no plaintext tokens in regular JSON. Respect global/session Fish settings and stale-binding failures. Library GET /model with self/filter/paging, explicit preview generation and confirmation. Test with HTTP fakes and no Fish spending.
2. **S3 VoiceMessageRepository / audio storage + native playback:** strict `scanEntitiesStrict` fail-closed file authority before any orphan cleanup; no cleanup after corrupt/unreadable voices. Write MP3 through streamed .part + validation + atomic publish; preserve old voices on failed replace; Desktop-safe path/relative owned-resource and duration adapter. Select real Windows MP3 backend only after license/JDK17/package feasibility; real playback must not be claimed PASS without physical listening. Repository tests include crash/restart/partial corruption/path containment/rollback.
3. **S4 tagging/translation/TTS coordinator:** migrate official 1.4.1 rules via shared narrow text-gateway interfaces and exact unmodified Prompt authority; strict ID/JSON validation, user text-change confirmation, five-permit concurrent TTS, Stop, no partial autoplay and foreground-work lease semantics. Any 1.4.4/1.4.5 retry/thinking drift is separate; do NOT silently adopt it. Respect current auxiliary request transport and request serialization.
4. **S5 chat/settings/editor UI:** attach voice bars/progress/playlist/bindings/voice long press to Desktop presentation; do not reimplement official model policies. Keep audiobook, messages/alternative versions, speaker matching, deletion and autoplay eligibility.
5. **S6 P11 handoff and QQ:** document `voices.jsonl`, audio ownership, legacy/v8 SaveSlot mapping and session lifecycle contracts for P11; **do not implement whole .cbbackup in P8**. Windows QQ automated voice sending requires feasibility evidence; if unavailable, keep PENDING/BLOCKED with explicit reason and offer supported save/copy/drag-out UI, not an invented automatic-send EXACT label.

After each S2–S5 subsystem: focused fake transport/storage/UI tests, relevant Android+shared/Desktop compile, isolated profile smoke. Commit only if no known data-loss risks; if codec/playback/Prompt/QQ parity cannot be demonstrated, **stop that subsystem** and continue only independent, safe code/tests, never mark full P8 closed.

### Gate D — P9 RAG; exact-source audit first, then safe provisional port

P9 has not received an independent Project S0 audit. To exploit the overnight session, Codex may **audit and provisionally implement only demonstrably JVM-neutral official 1.4.1 policies** with direct golden tests. Before editing create a file-by-file dependency map, distinguishing Android-only repos/UI/content-URI adapters vs shared domain code. Official sources include `domain/rag/ChunkingEngine.kt`, `EmbeddingService.kt`, `VectorSearchEngine.kt`, `RagRepository.kt`, `RagManager.kt`, `RetrievalPlanner.kt`, `RagSourcePlan.kt`, `ChatMemoryIndexPolicy.kt`, `RetrievalModels.kt` and `domain/search/CharacterResearchService.kt`.

- Reuse shared `EmbeddingService`, `VectorChunk`, chat source-turn policies, model resolution and proxy/request authority; do not duplicate implementations or create a second vector schema.
- Implement official chunking/hash/index/search policies, separate DOCUMENT and CHAT_MEMORY retrieval/ranking/topK/thresholds, source-turn stable chunk identity, stale index replacement, cancel/reindex/progress and safe persistence. Desktop file-picker/path adapter replaces Android URI behavior; no raw Android path assumptions.
- Enforce user-configured retrieval model and embedding selection without converting stale selections to silent fallback. Preserve zero-count disable and Prompt assembler memory RAG/document placement in the final serialized request. No new Prompt wording; if unavailable, leave wiring blocked and return a concrete diff proposal.
- Tests: document/turn chunk golden cases, 600/300–400 overlong source-turn split, embeddings request+cancel with mock server, similarity and source ordering, data-root restart/unknown corrupt reads, historical deletion reindex, isolated chat final request ordering and no memory summary contamination. No paid calls.
- **If domain ownership requires unreviewed schema migration, data purge, or Prompt-text change, DO NOT implement that path.** Preserve a precise pending list rather than claiming P9 parity.

### Gate E — P10 official long-term Memory; high-risk conditional implementation

P10 has not received an independent Project S0 audit, so begin with exact 1.4.1 skill `chatbar-long-term-memory`, its `references/invariants.md`/`state-machines.md`, `doc/long_term_memory_v2_handoff.md`, and real source/tests. Roughly 30 domain/memory Kotlin files, plus entities, MemoryRepository partial-write journal, coordinator, RAG, Archive/HEAD Prompt integration and UI. **This is a distinct, large system—not a summarizer.**

Only implement domain portions with traced source-to-target contract and golden tests. Recommended order: stable source-turn identity and derived T, Episode exact grouping and current-context exclusion, Gap/backfill eligibility and durable repair evidence, MemoryRepository journal-first partial commit/replay, Arc/Era coverage/compression invariants, Archive/HEAD validity and injection separation, regeneration/revision and app-owned maintenance coordinator with cancellation/lease, then Desktop UI/Settings and chat request integration. Never create a second separate Android/Desktop algorithm.
- Preserve full source fingerprint semantics, tombstones, current snapshot, immutable tier lineage, no invented coverage, repeatable corruption recovery, fail-closed model/refusal/transport outcomes, exact output-token/thinking behavior under selected 1.4.1, and model work only on explicit fake requests.
- Archive is its own message, HEAD after memory RAG, and actual final serialized request order must agree with upstream. No editing original model-facing prompt literals. If moving exact source text into shared is needed, prove literal identity and ownership; if behavior requires a literal change, STOP and report original/diff/reason for user decision.
- Ensure Page/ViewModel lifetime cannot cancel application-owned maintenance work incorrectly; terminal session deletion must cancel/join its jobs before deleting durable data. Preserve SaveSlot current-only memory snapshot handoff to P11.
- Tests: interruption/partial-journal replay, source edits/deletes/undo and T identity, overlapping/duplicate coverage rejection, HEAD lag and stale source, Gap persistence/backfill, compression plan and retries, cancellation/drain, no embedding of RAG into Archive, final API request injection golden. No real AI calls.
- **Do not ship or claim full P10 parity based solely on policy tests**; no client memory UI real-device acceptance can be faked in overnight run. If state-machine implementation or request-message semantics cannot be fully traced, stop at a source-faithful tested partial state and report owner gaps. Never solve time pressure by writing a generic one-summary fallback.

### Gate F — Consolidated artifact and Project handoff

- Keep **one branch** with ordered commits, recommended message prefixes `fix(desktop): ...`, `refactor(shared): ...`, `feat(desktop): ...`, `test(...): ...`; each commit must be understandable alone. Record full SHA and exact changed files per checkpoint.
- Before final handoff: full JDK17 affected compile/regression by modified domain, `git diff --check`, isolated persisted profile reopen, data-root corruption safety, real Windows process exit package if S0D changes, no secrets in diff/log; DO NOT run paid API. Reconcile any pre-existing baseline failing Android instrumented tests separately, never re-label RED as PASS.
- Push successful checkpoint commits to the **same `origin/feature/phase8-fish-audio` ref**; never merge into `desktop`, change `master`, open `sync/*`, make another branch/worktree or PR.
- Write a focused `docs/desktop/108_P8_P10_OVERNIGHT_HANDOFF.md` at the end (or early STOP) showing each Gate A–E as `IMPLEMENTED / PARTIAL / NOT STARTED / BLOCKED`, tests run and counts, source SHA, exact source-contract deltas, risks, known failed runs, manual Windows/Android coverage still needed, actual runtime and quota observations, and **unreviewed Project acceptance boundary**. Do not promote `13_FEATURE_PARITY.md` PENDING rows to EXACT/EQUIVALENT without independent Project review, or claim Public Preview Gate pass.
- Safety and budget: no requirement to actually occupy >=8 wall-clock hours; no speculative long waits. Check quota when visible and leave margin for Project review; if remaining quota/5h window blocks progress, STOP at the last clean pushed commit with a resume specification on the **same** branch, not an automatic background promise.

**CURRENT: OVERNIGHT MULTI-PHASE PROVISIONAL IMPLEMENTATION AUTHORIZED AS A SPECIFICATION; ACTUAL CODEX RUN NOT STARTED; NO GUARANTEE OF 8H WALL TIME, P8–P10 COMPLETION OR PROJECT ACCEPTANCE.**
