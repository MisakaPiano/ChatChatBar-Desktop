# 108 — P8–P10 single-branch provisional engineering handoff

**PROJECT REVIEW PENDING / P8–P10 NOT ACCEPTED / NOT MERGED**

Authority: [107](107_PHASE8_TO_10_SINGLE_BRANCH_OVERNIGHT_RUN.md), [105](105_DESKTOP_SHUTDOWN_PROCESS_LIFETIME_GATE.md), [97](97_PHASE8_S0_PROJECT_REVIEW.md), formal CCB 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`. This records Codex execution, not Project independent review or user Windows acceptance. Earlier P7 qualified acceptance and historical RED evidence remain unchanged.

## 1. Source and execution boundary

- Repository: `H:\ChatChatBar-Desktop`; only development branch `feature/phase8-fish-audio`.
- Verified remote starting HEAD: `8b1295f108592b7bd736199e263d24699caabf94`. Local older P8 state was clean and fast-forwarded after origin/ancestry inspection. No new branch, worktree or PR.
- Final production checkpoint: `209978309183d45ef8f773a642a5c29367ead6b5`.
- This Markdown is a subsequent documentation-only checkpoint; its Git commit is not the production SHA and cannot contain its own hash.
- Started: `2026-10-12T08:41:34.3996519+09:00`. Final timing and quota below are recorded observations, not an eight-hour claim.
- No real Fish/LLM/Embedding/NovelAI requests, real user profiles, user Secrets, data cleanup, upstream synchronization or schema/version promotion. No historical B2/WIN process was terminated.

## 2. Gate status and checkpoint history

| Gate | Status | Checkpoint / actual scope |
|---|---|---|
| A — S0D process lifetime | IMPLEMENTED, provisional | `31d2a48d2097e9e778185c7b8db1a022924745b8`: success-only outer process exit, sanitized stage trace, visible unsafe-close recovery, real packaged process/data checks. Historical residual-process root cause remains UNRESOLVED. |
| B — S1 Fish core | IMPLEMENTED, provisional | `2163e445d0bcb6351a6f181a40c4b8069d01b8d8`: single shared voice entities/models/three policies/request factory and exact golden tests. |
| C — S2–S6 Fish runtime | PARTIAL | `cfdbc555b7491ba73a6b72d31f42d7437131c20f`: typed protected Fish credential and cancellable bounded catalog GET adapter only. Picker, TTS/storage/playback/coordinator/chat UI NOT IMPLEMENTED. |
| D — P9 RAG | PARTIAL | `40b361661d9ae993b1826bf57872d3a065bf4990`: five pure shared files and official golden tests. Desktop indexing/retrieval orchestration NOT IMPLEMENTED. |
| E — P10 memory | PARTIAL | `209978309183d45ef8f773a642a5c29367ead6b5`: entities, identity/timeline, Episode grouping, Gap eligibility, coverage and snapshot-rebind pure policies. Desktop memory runtime/journal/coordinator/injection NOT IMPLEMENTED. |
| F — handoff | IMPLEMENTED | This document plus local evidence, final source/ref/diff audit and same-branch push. No acceptance promotion. |

Commits form an ordinary linear chain on this one feature branch. Each prior row's SHA is the next row's parent; Gate A's parent is the starting HEAD. Gate C stopping points do not block independent pure D/E policies under §3 of 107, but do block their own unproven runtime paths.

## 3. Actual source changes and authority

Paths in this section are repository-relative. `shared` means `app/sharedCore/src/main/kotlin/com/example/chatbar/`; Android means `app/app/src/main/java/com/example/chatbar/`. No parallel Android/Desktop copy of a relocated algorithm remains.

### A: platform lifetime

Changed Desktop production: `Main.kt`, `DesktopAppContainer.kt`; added `DesktopProcessLifecycle.kt`, `DesktopShutdownTrace.kt`, all under `app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/`.

- Keep `application(exitProcessOnExit=false)` and existing cleanup ordering. Termination code 0 is reachable only after the outer application/ownership lifetime returns successfully.
- Task/draft/reading-position drain timeout presents a platform recovery window and permits explicit safe-close retry. Unconfirmed other close failures retain root ownership and a visible diagnostic state; no automatic forced exit or deletion of locks.
- Trace is opt-in (`CCB_SHUTDOWN_TRACE=1`), fixed stage IDs/timestamps only. Recovery diagnostic contains no exception message, Prompt, credential or user path.
- Added `DesktopProcessLifecycleTest.kt`, and opt-in test-classpath `DesktopShutdownFixture.kt` / `DesktopShutdownRecoveryFixture.kt`; fixtures are not shipped in the EXE.
- Fresh trace-only build exited normally before the final guard. Therefore the guard is a **preventative completion guarantee**, not proof that either historical P7 process reached main-return or that an old deadlock was repaired. This limitation needs independent Project review.

### B/C: Fish

Moved unchanged: `data/local/entity/FishAudioEntities.kt`, `domain/voice/{FishAudioModels,VoiceAnchorPolicy,VoiceGenerationPolicy,VoiceMessageVersionPolicy}.kt` to shared. Their dependencies are serialization/JVM/shared chat/roleplay entities. Existing shared `FishAudioVoiceBinding`, CharacterInfo, AppSettings, ChatSession and request/model authorities are reused.

Extracted `FishAudioTtsRequest` and `FishAudioRequestFactory` from Android `FishAudioService.kt` into shared `domain/voice/FishAudioRequestFactory.kt`. The necessary visibility change is `internal` → public across modules; normalized body identity is recorded. Android service retains transport/storage/concurrency ownership. No TagService, Prompt literal or generation request semantic change.

Moved four exact official golden tests: `FishAudioRequestFactoryTest`, `VoiceAnchorPolicyTest`, `VoiceGenerationPolicyTest`, `VoiceMessageVersionPolicyTest`. Added shared `FishAudioSharedContractTest` and Android `FishAudioSharedConsumerTest` for serialization/legacy/null, FREEFORM/STRUCTURED binding and cross-module consumption.

Desktop C changes: added `DesktopFishAudioLibrary.kt` and `DesktopFishAudioLibraryTest.kt`; extended `security/DesktopSecretStore.kt` with the typed Fish key; added lazy adapters to `DesktopAppContainer.kt`.

- Key uses existing SecretStore/DPAPI, not ordinary Entity JSON. Catalog invokes the sole shared request factory, bounds response processing to 4 MiB, attaches cancellation through body consumption, closes responses, and emits credential-safe failure categories. No redirect/retry or paid-preview fallback.
- Mock HTTP verifies self/filter/page/sort, authentication, private access failure, malformed/oversized body and header/body cancellation. A real Windows DPAPI test uses a synthetic temporary credential profile, including store recreation/clear.
- This is a foundation without exposed voice picker/settings UI; it is not a complete S2 product or isolated end-to-end TTS smoke.

### D: RAG

Moved to shared `domain/rag/{ChunkingEngine,VectorSearchEngine,ChatMemoryIndexPolicy,RagSourcePlan,RetrievalModels}.kt`; first four match formal-baseline Git blobs. `RetrievalModels` matches the pre-port fork file: `RetrievedKnowledgeCard` was already shared by an earlier phase and is not duplicated.

Moved exact `ChatMemoryIndexPolicyTest` and `RagSourcePlanTest`; added `RagCoreContractTest` covering source metadata, chunk overlap, similarity/zero-vector/dimension mismatch, stable ranking and zero-recall disable. Existing shared EmbeddingService/VectorChunk and secure Desktop embedding adapter remain owners.

Audit found `RagManager.indexDocument()` deletes old document chunks before requesting embeddings. Blindly wiring it to Desktop would not prove fail-closed index replacement. Repository strict inventory, staging/atomic replacement, cancellation, picker/progress, configured-model routing and final serialized request ordering remain unimplemented. No vector schema, index data or Prompt was changed.

### E: memory

Moved unchanged to shared: `data/local/entity/LongTermMemory.kt`; `domain/memory/{MemoryHashes,MemorySourceFingerprint,MemoryTimelinePolicy,MemoryEpisodeBatchPolicy,MemoryBackfillPolicy,MemoryContinuityPolicy,MemorySnapshotImportPolicy}.kt`.

- Seven policy files are exact formal-baseline blobs. Entity file is exact pre-port fork blob; the baseline's `DEFAULT_MEMORY_LIMIT_CHARS` constant had already been extracted to shared `MemoryDefaults.kt` before this task. No serializer/default/schemaVersion/HEAD render string was edited.
- Exact golden tests moved: `MemorySourceFingerprintTest`, `MemorySourceRepairSerializationTest`, `MemoryEpisodeBatchPolicyTest`, `MemoryBackfillPolicyTest`, `MemoryContinuityPolicyTest`, `MemorySnapshotImportPolicyTest`.
- Added `MemoryIdentityContractTest`: derived T after tombstones/deduplication, unknown/duplicate/reversed/gapped source rejection, node order independent of legacy T, ordered hashes and required child coverage.
- Initial relocation exposed Kotlin's cross-module smart-cast restriction in `MemorySnapshotImportPolicy.kt:86–89`. Resolved by moving that pure policy and its pure coverage dependency unchanged, rather than altering import logic or assertions. Original RED compilation log retained.
- Official memory skill, invariants, state machines and full `doc/long_term_memory_v2_handoff.md` were read. The 30-file import/dependency inventory, journal sequence and coordinator platform dependencies were inspected. The 4,210-line service's complete runtime call graph was **not** proven; no full P10 parity claim.

Skill changes are limited to source-location/dependency maps in `chatbar-fish-audio-voice`, `chatbar-feature-map`, `chatbar-long-term-memory`, required because physical owners moved. Official Prompt/runtime rules remain unchanged.

## 4. Tests, failures and provenance

All counts below come from actual JUnit XML, not source-file counts or stale Desktop reports. JDK is Temurin 17.0.20.1 (`C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`). Gradle runs from `app/`, serial operations, at most two workers, offline, no daemon/build/configuration cache, 1,536 MiB JVM and in-process Kotlin.

| Checkpoint/run | Suites / tests | Failures / errors / skips |
|---|---:|---:|
| A trace-only focused | 3 / 49 | 0 / 0 / 0 |
| A expanded lifecycle/safety | 11 / 119 | 0 / 0 / 0 |
| A added in-flight finally/drain follow-up | 1 / 7 | 0 / 0 / 0 |
| B shared focused | 5 / 31 | 0 / 0 / 0 |
| B Android focused | 8 / 30 | 0 / 0 / 0 |
| B shared full JVM | 116 / 753 | 0 / 0 / 0 |
| B Android full JVM | 132 / 780 | 0 / 0 / 0 |
| C Desktop affected | 6 / 50 | 0 / 0 / 0 |
| D shared / Android / Desktop focused | 3 / 27; 4 / 11; 2 / 9 | all zero |
| D shared / Android full JVM | 119 / 780; 130 / 757 | all zero |
| E corrected shared / Android focused | 7 / 39; 3 / 15 | all zero |
| E final shared / Android full + Desktop affected | 126 / 819; 124 / 722; 14 / 120 | all zero |

A's follow-up overlaps six earlier tests: 120 unique affected tests across those runs, not an invented single 120-test execution. Shared/Android suite counts change because exact tests move between modules; do not add repeated-run totals as unique coverage.

Preserved initial failures:

1. A's first repair command attached `--tests` to custom classpath task; Gradle rejected command configuration before tests. Corrected task ordering; original log retained.
2. C's first focused run had a JUnit initialization error: one new Kotlin test inferred an exception return type instead of `Unit`. Explicit `Unit` fixed the harness, assertions unchanged. Original XML/log retained.
3. E's first Android compile failed on the four cross-module smart casts above; shared 11 tests passed but Android tests did not run. An initial journal-test selector used the wrong package; corrected to `domain.memory.MemoryRepositoryDeletionJournalTest` in the successful focused run. No assertion was removed or weakened.

Desktop full suite, old foreground-dependent Native/Robot suites, historical synthetic ScrollBy and old 18-group GUI acceptance were **not rerun**. These are not relabelled PASS. No Android instrumented/device acceptance was performed. Gradle task-level `SKIPPED` on configuration/resource tasks is not a JUnit skip.

Command pattern (full affected):

```text
.\gradlew.bat :sharedCore:test :app:testDebugUnitTest :desktopApp:test [explicit affected --tests selectors]
  :desktopApp:compileKotlin :app:compileDebugKotlin
  --rerun-tasks --no-build-cache --no-configuration-cache --max-workers=2
  -I desktopApp/build/phase7-r2-r1-ssd.init.gradle --offline --console=plain --no-daemon
  -Dorg.gradle.jvmargs="-Xmx1536m -Dfile.encoding=UTF-8" -Pkotlin.compiler.execution.strategy=in-process
```

The existing SSD init script redirects **Desktop only** to `C:\Users\1\AppData\Local\Temp\ccb-p7-r2-r1-build`; actual XML is its `test-results/test`. Shared XML: `app/sharedCore/build/test-results/test`. Android XML: `app/app/build/test-results/testDebugUnitTest`. Each relevant run's XML is copied to its gate directory before later tasks replace reports.

## 5. Real Windows evidence: scope is A only

Exact A package: `H:\ChatChatBar-Desktop\app\desktopApp\build\phase8-s0d-distribution\ChatChatBarDesktop.exe`, source `31d2a48d2097e9e778185c7b8db1a022924745b8`. Complete payload 210/210 SHA-256/readback matches, zero missing/extra/mismatch.

| File relative to distribution | SHA-256 |
|---|---|
| `ChatChatBarDesktop.exe` | `E5D45E5389E7584B9D8477EFE34543DE1069E6550560A3A3C53D164152958B8F` |
| `app/desktopApp-e83b62c2262c273be0af2f13462c5c.jar` | `C94B722169ACD004B6BBA2FC6E303AFB02BD62988E763B6953D961408E87BDCD` |
| `app/sharedCore-ab642c64b398a3d31ab9cd258c653bd.jar` | `CBEE22DDDFA081AC4A7F788201C6CAFB9A08950E2849A2B114767F1F4DCAA847` |

Six real visible packaged launch→WM_CLOSE cycles: five fresh disposable profiles, then same synthetic persisted profile reopened. Launcher/JVM pairs were verified by creation time, executable path, parent relation and loaded JVM/JLI modules: `34736/4252`, `44652/12476`, `26376/43304`, `23948/43352`, `8684/30800`, `44312/20356`. All exit codes 0/0, no residual owned process. stdout 0 bytes each; stderr 1062–1071 bytes of enabled sanitized stage trace. Null entries in the recorded residual PID arrays mean no matching process, not an unverified PID.

Character/session/message/draft/media were seeded and checked after cycles 5/6, with root lock reacquired and no duplicate messages. Separate real AWT recovery fixture proved visible recovery/lock retention and explicit retry. Its button was dispatched on EDT, **not** a Robot or user-manual acceptance claim. In-flight cancellation/finally writes and backup drains were tested with synthetic tasks.

This package predates B–E and must not be offered as a final Fish/RAG/Memory acceptance artifact. No later P8–P10 package or audio listening acceptance is claimed.

## 6. Evidence and exact changed-path inventory

All local evidence below is gitignored under `H:\ChatChatBar-Desktop\app\desktopApp\build\`:

- `phase8-s0d-gate/`: `gate-summary.json`, original/corrected logs, XML, trace-only and final manifests, `final-smoke.json`, six cycle records, recovery fixture and synthetic reopen logs.
- `phase8-s1-gate/`: exact-source blob map, focused/full logs and XML, `full-counts.json`, summary.
- `phase8-s2-gate/`: pre-edit dependency map, initial RED XML/log, corrected focused XML/counts, summary.
- `phase9-core-gate/`: pre-edit dependency map, exact-source map, focused/full logs/XML/counts, summary.
- `phase10-identity-gate/`: pre-edit map and bounded-dependency addendum, 14 original blob identities, initial compile RED, corrected focused/final logs/XML/counts and commands.
- `phase8-to-10-handoff-gate/`: consolidated timing/ref/scope results and exact per-checkpoint `git show --name-status` inventory. Raw diagnostics/profile data are not committed.

Each checkpoint's complete changed paths are also reproducible with `git show --format= --name-status --find-renames <full-SHA>`. Relocations are 100% matches except the explicitly documented factory visibility seam; Git rename detection avoids falsely counting moved algorithms as rewritten code.

## 7. Remaining gates and same-branch resume specification

1. **A review/manual:** historical old-instance cause UNKNOWN. Review preventative exit and non-retryable visible recovery UX; actual failure during a user workload has not been manually accepted. Do not terminate protected old processes or release their locks.
2. **C S2 remainder:** credential settings, model picker/paging state, card bindings, stale selection, explicit preview confirmation and picker-owned preview lifecycle need UI and storage/backend gates. Current lazy adapter causes no startup network request.
3. **C S3 BLOCKED at unproven adapter:** choose a real Windows MP3 backend only after license/JDK17/package feasibility; establish duration/validation and physical listening. Trace both voice and anchor strict inventory before orphan cleanup, streamed `.part`→validated atomic publish, failed replace retention, crash/restart/corruption/path-containment tests. No new backend/dependency was guessed.
4. **C S4/S5 NOT STARTED:** exact TagService/gateway/Prompt authority, strict IDs/JSON, translation confirmation, five-permit coordinator, source-owned Stop, no partial autoplay, app-owned lease and chat playlist. Depend on validated S3; no substitute algorithm or Prompt.
5. **C S6/P11 handoff:** bindings are card Package metadata; `GeneratedVoiceMessage`/anchors are separate persisted media/version identity; they must not be injected into ChatMessage, Prompt, RAG or Memory. Official SaveSlot v8 uses `voices.jsonl` and `media/audio/`; legacy Base64 and current-only memory snapshots need P11 transactional portability. No whole `.cbbackup` implementation. Android QQ accessibility evidence does not establish Windows automation; QQ Windows remains PENDING, with any future save/copy/drag-out alternative explicitly distinct from automatic sending.
6. **D runtime BLOCKED at unproven safe index replacement:** trace `RagRepository`, `RagManager`, RetrievalPlanner and CharacterResearchService adapters before Desktop writes; current document delete-before-embedding ordering cannot be silently adopted as crash-safe. Require strict inventory, model authority, cancellation/reindex, source-specific topK/zero disable and final serialized request ordering. Current shared policy tests are not those runtime gates.
7. **E runtime BLOCKED at unproven state-machine/storage wiring:** journal-first nodes/revisions→state pointer→cleanup order must survive partial failure and strict corruption detection in Desktop root/coordinator ownership. Preserve durable Gap, current-context exclusion, source edit/delete/undo, immutable lineage, compression/retries, HEAD lag and Archive/HEAD final request separation. Full LongTermMemoryService/gateway/coordinator tracing, app-owned cancel/join, UI and isolated runtime tests remain necessary. Do not use a generic summary fallback.
8. Project independently reviews these exact diffs and evidence before claiming phase acceptance/parity. Continue only on this existing branch under the applicable authority; no automatic background continuation is scheduled.

## 8. Final audit and observed time/quota

- JDK17 shared/Desktop/Android compilation PASS. Final affected regression: 264 suites / 1,661 tests across three distinct tasks, zero failures/errors/skips; Desktop coverage is the explicit 14-suite collection, not its full suite.
- Final compiled-source synthetic profile seed + independent JVM reopen PASS, both exit 0, root re-acquired. Evidence: `phase8-to-10-handoff-gate/final-source-reopen.json`; this is not another packaged EXE run.
- Entire changed-tree `git diff 8b1295f..2099783 --check` PASS; source provenance checks PASS. Five production checkpoints: 49 changed paths with rename detection, 971 insertions / 94 deletions; many paths are byte-identical moves. This handoff adds one Markdown file only.
- Last code checkpoint local/origin feature equality verified. Final docs push/clean result and exact docs SHA are recorded after commit in `phase8-to-10-handoff-gate/final-summary.json` and the chat handoff.
- Protected local refs unchanged: desktop `4c2af2576e2319e8041ffd2a6c5a205450f18246`; master `550409689df8c51f459fb50b4e04c8ac2fa4bf35`; sync/1.3.49 `719a7a1f639913bbc513e8d89d5866053bc0e1d3`; sync/1.4.0 `475679c23b41e5258adb56f3e4e85734ca43ccd3`; sync/1.4.1 `44418b6413460a7b40ba67dca4ac8887e37102cb`; sync/1.4.4 `9b6378dbb595dd2f3ff5143a7a8e46653c99e721`. Only the existing P8 feature was pushed.
- H: free bytes: start 42,685,726,720; handoff observation 42,061,254,656 (~39.17 GiB). No cleanup or duplicate checkpoint-package accumulation; A retained one diagnostic build and one final build as required for before/after evidence.
- Weekly account allowance: start 30% used / 70% remaining; at `2026-10-12T09:38:04+09:00`, 42% used / 58% remaining. Observed change +12 percentage points, rounded and shared across the account, not an exact per-task cost. No reset credit used. Actual host model/Thinking/Fast settings are not independently exposed by the available runtime metadata; no model override is claimed.
- Observed stage intervals (analysis, commands and verification included): A start→09:00:51.952 = 19m17.6s; B until 09:10:47 = 9m55.0s; C until 09:16:30 = 5m43s; D until 09:25:01 = 8m31s; E until 09:37:18 = 12m17s; F after that. These are checkpoint intervals, not inferred pure test CPU times. Gradle final affected run reported 3m32s.
- The run stops at a source-faithful tested partial state to preserve the remaining session/handoff budget and avoid crossing unproven audio/persistence/request boundaries. Quota was not exhausted. Remaining runtime scopes require the concrete source, storage and platform validations listed above; no claim that they are impossible or already accepted.
- Handoff prepared at `2026-10-12T09:38:40.0094088+09:00`; elapsed at preparation 3425.6 seconds. Final post-push timestamps are in the local final summary.


Formal baseline, `13_FEATURE_PARITY.md` status rows, Prompt templates, Package/schema versions and protected refs are not promoted. No Public Preview or P8/P9/P10 acceptance is asserted.
