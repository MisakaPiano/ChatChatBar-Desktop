# 109 — Project Independent Code Review: P8 S0D–S2, P9/P10 Pure-Core Checkpoints

**2026-10-12 JST — GITHUB SOURCE REVIEW COMPLETED / GATE A REQUIRES FOLLOW-UP / PURE RELOCATIONS SOURCE-VERIFIED / NO PHASE ACCEPTANCE OR MERGE**

## 1. Exact reviewed snapshot, external evidence limit

- Fork: `MisakaPiano/ChatChatBar-Desktop`; feature `feature/phase8-fish-audio @ 3e384e3bbc193a40da27ebe5cc16bd76b82ba79a` (last docs-only); final production checkpoint `209978309183d45ef8f773a642a5c29367ead6b5`; starting authorized checkpoint `8b1295f108592b7bd736199e263d24699caabf94`.
- GitHub independently confirms **six consecutive ordinary single-parent commits**, not extra branches or force-updates: A `31d2a48d2097e9e778185c7b8db1a022924745b8`; B `2163e445d0bcb6351a6f181a40c4b8069d01b8d8`; C `cfdbc555b7491ba73a6b72d31f42d7437131c20f`; D `40b361661d9ae993b1826bf57872d3a065bf4990`; E `209978309183d45ef8f773a642a5c29367ead6b5`; F `3e384e3bbc193a40da27ebe5cc16bd76b82ba79a`.
- Baseline `desktop @ 5ef95428421f7c693b010ac88f51d470804ab07a` (fork GitHub), unchanged; `master @ 550409689df8c51f459fb50b4e04c8ac2fa4bf35`, parked `sync/1.4.4 @ 9b6378dbb595dd2f3ff5143a7a8e46653c99e721`, untouched. Feature ahead of desktop by 59 commits, behind 0 at this checkpoint. This review and later docs-only control are not production edits.
- **Important local/remote distinction:** Codex handoff `108` lists **local** `desktop @ 4c2af2576e2319e8041ffd2a6c5a205450f18246`. GitHub independent comparison confirms this local-claimed SHA is **11 documentation-only commits behind GitHub desktop @ 5ef954...`**. This does NOT prove Codex mutated desktop; it shows local protected ref has not been fast-forwarded. Do not silently update it in the P8 feature task.
- Reported by Codex from local JUnit XML/Windows machines, **not independently reexecuted by Project**: shared 126 suites/819 tests PASS, Android JVM 124/722 PASS, Desktop **affected 14/120 PASS, NOT full**; JDK17 three-module compiles and git diff --check PASS. A production source package at A `31d2a48...` reportedly passed 210/210 manifest and six real Windows packaged close cycles, parent/child 0/0, no residual, synthetic reopen/lock reacquisition PASS. Final production `2099783...` received no subsequent real packaged GUI/user manual exit gate. Historical P7 B2/WIN headless process cause remains UNRESOLVED.
- No production changes to official PromptTemplates, Package schemas/versions, final serialized model request assembly, memory journal writes or RAG vector index transactions in the six commits. No paid API requests, real user data, or secrets observed in new code/test literals. Do not infer all persisted features are implemented.

## 2. A — safe shutdown: architecture correct on success, UNBOUNDED WAIT FAILURE GAP

**Disposition: CONDITIONAL HOLD for P8-S0D overall; normal-success-path review POSITIVE, real-historical-root-cause UNKNOWN.**

- `Main.kt` retains `application(exitProcessOnExit=false)`, closes Compose → appContainer tasks/drafts/backups/coordinator/migration → pickers → ownership, and invokes `runDesktopProcessLifecycle` terminal exit only after clean **outer** lifetime return. This is substantially safer than `exitProcess(0)` in Window callback or any early finally.
- `DesktopUnsafeShutdownException` conservatively retains a root lock on incomplete/unknown data drain; `DesktopShutdownRecoveryWindow` is visible and retriable for explicit writer-drain timeout exceptions. Diagnostic `DesktopShutdownTrace` is opt-in and fixed stage names, not user data.
- **Verified source-level gap:** `DesktopAutomaticBackupScheduler.close()` waits on `run.job.join()` without a finite observation timeout; `DesktopDataOperationCoordinator.closeAndDrain()` awaits `closeSignal` without one. The main Compose Window has already been disposed by the time `appContainer.close()` executes; the recovery presenter in `closeDesktopWithRecovery` is entered only **after close returns/throws**, not when it hangs indefinitely. If backup or coordinator (or another close sub-operation) never completes and never throws, a silent windowless JVM can STILL remain. Six **successful** WM_CLOSE cycles cannot verify this failure path. This is a product-contract hole in `105`, NOT proof it caused old residual P7 JVMs.
- The non-retryable error recovery presenter is not a demonstrated real-Windows user flow in the handoff; only mocked callback / timeout recovery fixture evidence exists. Its deliberate no-force-exit/lock retention is data-safe in principle, but needs an actionable, observable recovery UX contract.
- The synthetic media shutdown fixture constructs an absolute test-only image path rather than exercising the Desktop-owned root-relative image store. It proves the fixture's bytes and data-root ownership under the observed run, **not full supported media portability**. Do not extend its PASS to broader image/SaveSlot data safety.
- **Required S0D-R1 correction:** An independent, bounded *visibility/diagnostics watchdog* observes the ongoing safe close and exposes a minimal **visible waiting/recovery status** on the AWT EDT when safe drain exceeds a chosen warning threshold. It MUST NOT cancel/interrupt writer IO, mark close safe, remove root locks, or invoke terminal JVM exit. On eventual clean drain, dispose waiting UI and proceed through existing outer picker/ownership releases and terminal process exit. On unsafe failure, retain visible actionable state and root authority. Prevent competing popups, inaccessible EDT, or a new non-daemon watcher leak. Simulate a deliberately suspended backup/coordinator with a controllable latch, verify that warning appears *before* completion, release latch, then verify safe exit; also test never-completing wait is visible/non-green rather than a silent zombie. Narrow unit/AWT/packaged tests and fresh exact-final-source 5+ WM_CLOSE cycles, with synthetic persisted data and lock reacquisition. Project reviews actual diff before S0D acceptance.
- Preserve old B2/WIN PIDs, their files, and data-root lock; no process termination authorization is added by this review.

## 3. B — Fish shared pure-core

**Disposition: SOURCE/BLOB REVIEW PASS within narrow 97 scope; full P8-S1 milestone acceptance remains pending closure of S0D-R1 and independent verification of local run evidence.**

- GitHub diff identifies 30 files renamed exactly, each **zero source-line delta** with full rename detection; no second Android policy implementation. Four pure Fish policy/model files `FishAudioModels`, `VoiceAnchorPolicy`, `VoiceGenerationPolicy`, `VoiceMessageVersionPolicy` each match exact official 1.4.1 blob SHA. Existing shared `FishAudioVoiceBinding` remains sole binding.
- `FishAudioEntities.kt` is identical as a Git rename to the fork's pre-port file; not equal to a monolithic official baseline blob only because `FishAudioVoiceBinding` was already split to shared before this task. Do not mistake this pre-existing structural split for a changed serialized voice contract.
- `FishAudioRequestFactory`/TTS DTO extracted from Android mixed `FishAudioService.kt` with request/headers/body preserved and minimal intentional `internal`→public visibility; Android service retains transport. Relevant RequestFactory/Anchor/Generation/Version golden tests moved byte-identically; new shared serialization/legacy-null and Android consumer tests added. The reported shared and Android test runs passed; Project did not execute them.
- No TagService/Prompt ownership, coordinator, storage, playback, key or audio transfer moved in B. Correctly separate Package/Entity and HTTP request DTO semantics. No broad P8 acceptance implied.

## 4. C — Fish credential/catalog adapter

**Disposition: REVIEWED SAFE PARTIAL FOUNDATION, NOT S2–S6 COMPLETION.**

- `DesktopSecretStore` adds a typed `FishAudioApiKey` with a separate canonical protected key. `DesktopFishAudioCredentials` operates through the already OS-protected DPAPI abstraction, never a plain Entity JSON token. Lazy `DesktopAppContainer.fishAudioCredentials/fishAudioLibrary` does not cause startup Fish requests.
- `DesktopFishAudioLibrary` uses one shared official request builder, bounded ~4MiB response parsing, sanitized HTTP error classes, explicit cancellation propagation and responses closed. Fake HTTP + synthetic DPAPI tests cover known failure/status paths. No true paid preview or TTS is reachable from this change.
- **Later S2/S3 owner requirement:** Before exposing catalog to UI or long-running audio tasks, tie active calls and OkHttp lifecycle to app-owned shutdown/drain; an unowned asynchronous catalog call must not reintroduce residual threads or a data-root race. Also test credential save failure and endpoint/query/filter against current official selected baseline. Do not claim picker, binding UI, audio/media storage, generation, playback or QQ parity.

## 5. D — RAG exact-source policies

**Disposition: SOURCE/BLOB REVIEW PASS for pure extraction; P9 RUNTIME PENDING.**

- Official 1.4.1 `ChatMemoryIndexPolicy`, `ChunkingEngine`, `RagSourcePlan`, `VectorSearchEngine` each match downstream Git blob SHA **exactly**. `RetrievalModels` preserves the fork's already split `RetrievedKnowledgeCard` owner; no new independent model.
- Tests moved with unchanged contents and extra chunk/similarity/zero-recall policy golden cases. Shared EmbeddingService already existed and still matches the 1.4.1 blob.
- No new index writes/search over Desktop persisted data, RAG retrieval UI, Chat Memory indexing, or final message injection. Android `RagManager.indexDocument` deletes old chunks before successful new embedding; it remains Android-only and **must not** be blindly wired to Desktop's strict transactional persistence. Current fork's fail-closed storage principles and final serialized request order are the gate for P9.
- `13_FEATURE_PARITY.md` RAG PENDING remains the only accurate status.

## 6. E — Long-Term Memory exact-source policies

**Disposition: SOURCE/BLOB REVIEW PASS for seven pure policies; P10 STATE MACHINE / PERSISTENCE PENDING.**

- Official 1.4.1 `MemoryHashes`, `MemorySourceFingerprint`, `MemoryTimelinePolicy`, `MemoryEpisodeBatchPolicy`, `MemoryBackfillPolicy`, `MemoryContinuityPolicy`, `MemorySnapshotImportPolicy` match downstream Git blob SHA **exactly**. `LongTermMemory.kt` was moved byte-for-byte from pre-port fork; unlike monolithic upstream it omits `DEFAULT_MEMORY_LIMIT_CHARS` already extracted before this task to shared `MemoryDefaults.kt`. No new schema/default semantics introduced in this change.
- Goldens moved with byte-identical source and `MemoryIdentityContractTest` added. Android JVM compiles/tests reported green. `LongTermMemoryService`, `MemoryRepository` journal-first persistence/replay, coordinator, prompt Archive/HEAD real request, cancellation and UI were **NOT ported**.
- No permission to replace official multi-tier memory with plain summary or to change original Prompt literals. `13_FEATURE_PARITY.md` Memory PENDING accurately remains unchanged.

## 7. Integration and handoff decision

- **DO NOT MERGE** `feature/phase8-fish-audio` into `desktop` and do not label P8/P9/P10 ACCEPTED, EXACT or EQUIVALENT. The combined branch now contains benign P9/P10 pure relocated files; a future whole-branch merge would include them even if those phases remained PENDING, so every affected shared/Android source must be reviewed and gate evidence must be retained.
- Project does **not** request revert/reset: independent GitHub blob equality supports retaining B/D/E relocated code. The only mandatory immediate fix is S0D-R1 **visibility during indefinitely pending safe shutdown** plus fresh exact-final-source process/data tests; other C/D/E features can remain partial without claiming parity.
- Codex cannot automatically accept its own work. After the narrow same-branch S0D-R1 patch, return commit SHA, full diff, focused/full relevant tests, isolated native watchdog/close failure evidence, final-source real package process tree, H: free space and any quota use. Then Project will independently review and decide P8 S0D acceptance and resumption of S2 or P9.
- Historical P7 user Windows validation and D-037/D-038 Public Preview data safety remain unchanged. No automated background continuation or release is authorized.

**CURRENT: SOURCE REVIEW CONDITIONAL / S0D-R1 REQUIRED / FISH PURE CORE VERIFIED / FISH CATALOG PARTIAL / RAG PURE CORE VERIFIED / MEMORY PURE CORE VERIFIED / P8–P10 NOT ACCEPTED.**
