# P9-S2A Document Index Preparation — candidate evidence

Execution: 2026-10-15 to 2026-10-16 JST (the directory retains the start date). **CODEX CANDIDATE / PROJECT REVIEW PENDING.** No production index publication, P9-S2B transaction or full parity acceptance.

## Linear source checkpoints

- Initial local/tracking/live origin: `94310f85549d3be906d70553ecfbba7e81f8d58a`; feature/phase8-fish-audio; clean worktree/index.
- A: `8937de0686aa053a1f7ef71917cfce9cfa0e48d2`, P11-S0 Project acceptance registration only (122 + CURRENT 00/21). No old test rerun or new acceptance decision by Codex.
- B: `b337f3da50f1d0f175e48b7f56de1ed05922f3b4`, independent 123 P9-S2A narrow contract, docs only.
- C: `a3bf3ab7229d8fcfae96c7af36022560fc7665c3`, parent B. Seven files, +693/-130 (six Desktop source/test files and one feature-map Skill update). D is this independent evidence commit, parent C.
- Source identities: source-manifest.json and validated-source.json, seven working SHA-256 plus Git blobs; all compared to C and the tested working tree before staging.
- Formal baseline remains1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`. official-authorities.json records formal/current RagManager and RagRepository blobs: RagManager matches; the pre-existing RagRepository change replaces its private ENTITY_TYPE with the shared VectorChunkStorageContract authority. Neither Android file changed here. No upstream sync.

## Delivered boundary and ownership

The AppContainer explicit offline-gateway factory uses the same JsonFileStorage, data root, coordinator and TaskRuntime. Strict uncached character/config inventory and owned document validation run inside a top-level exclusive read scope. PENDING and INDEXED are admitted independently from old vector availability. Actual UTF-8 content SHA-256 is distinguished from recorded contentHash/indexedHash; stale recorded hashes are reported, never overwritten or called current index evidence.

DesktopDocumentRagSource extracts P9-S1 file/identity/norm and preallocation rules. source-extraction-check.json confirms method bodies match the prior Reader after visibility/newline normalization and the extra helper-entry filename guard (already checked by Reader). Reader still uses the same official ChunkingEngine and VectorSearchEngine, metadata/multiset proof and thresholds. No second chunker or cosine algorithm.

A bounded official text plan is created only after existing1MiB/filename256/title128/label1024/section-chunk2048/estimated2Mi UTF-16-character admission. Empty plans are rejected. Metadata is official documentId/sourceLabel plus fileName/originalDocId/actual contentHash/embeddingKey in source order. Between snapshots no exclusive is held: gateway batches are at most16 vectors and65536 floats; dimensions1..65536. Cardinality, dimension, NaN/Infinity, zero/underflow norm and Float norm overflow invalidate the whole attempt. Previous batches leave only counts/digest, not a document-wide vector set. A trusted injected gateway is responsible for its own internal allocations; these bounds do not claim control over arbitrary third-party gateway implementations.

Final full card/document/config comparison, owned-path/hash and ownership/coordinator checks run inside a new exclusive read scope. No normal-operation upgrade. This is final source reproof, **not proof-to-CAS or publication**: a later normal writer can change source after this read cut. PREPARED_ONLY contains IDs, actual hash/match flags, config identity, bounded counters and digest; no raw text, vectors, credential or persisted candidate. It is not an index receipt and cannot be used to bypass future S2B verification.

TaskRuntime withDocumentPreparation owns a caller coroutineScope child and also registers that child in activeJobs for application closeAndDrain. Same card/document pair duplicates are rejected until completion; completion callbacks release only that registration. Caller cancellation joins structured work; application shutdown cancels and drains registered work. If the observing caller remains active, application task cancellation yields CANCELLED; cancelling the caller propagates CancellationException. Existing session/chat/image/format-repair admission and histories are unchanged. No GlobalScope, detached callbacks, generic writer lock, task-center UI or automatic retry.

The new service does not hydrate/load/unprotect/persist credentials. Existing TaskRuntime assembly can construct its existing model/credential adapters; there is no new SecretStore operation or real data access. Tests inject an in-memory store and empty synthetic catalog, and whole-payload snapshots detect writes.

## Actual compile and test evidence

Existing Temurin17.0.20.1 (jdk-version.txt), Gradle9.1.0, unchanged2GB heap. Exact commands/timestamps/exit codes in numbered JSON and raw logs, from H:\ChatChatBar-Desktop\app via `& .\gradlew.bat @arguments`. All runs use offline/no-daemon/no-parallel/max-workers1/priority-low/plain/no-configuration-cache/Kotlin-in-process; no clean/rerun-tasks/install.

| Stage | Exit | Suite/test | Failure/error/skip | Notes |
| --- | ---: | --- | --- | --- |
| 01-preparation | 1 | 1/21 | 1/0/0 | Actual main/test compilation passed; test classification RED preserved |
| 02-preparation-final | 0 | 1/21 | 0/0/0 | Main recompiled after narrow fix; unchanged test compilation UP-TO-DATE; actual test run |
| 03-focused-regression | 0 | 5/65 | 0/0/0 | Incremental compilation UP-TO-DATE, actual five-class JUnit execution |

Final suites: DesktopDocumentRagIndexPreparationTest21; DesktopDocumentRagReaderTest18 (including existing1200-chunk parity/budget and unsafe-link checks); DesktopFormatRepairDerivedSafetyTest15; DesktopTaskRuntimeTest10; DesktopMainChatRequestAssemblerIntegrationTest1. Final JUnit durations are3.539/16.016/4.518/4.654/0.076 seconds respectively. Do not add intermediate21+21 runs to final65 or treat configuration-task SKIPPED as JUnit skips.

SharedCore and Android compile/tests NOT RUN separately: unchanged module trees recorded in unchanged-authorities.json. Shared prerequisites in logs are UP-TO-DATE, not newly compiled. The Desktop main/test compilations actually executed in stage01, with final main repair recompiled in02. Full regression, Windows manual/GUI, packaging and real Embedding/Provider requests NOT RUN.

## Preserved RED and bounded correction

Original test `strict inventory rejects malformed unknown and contradictory source records without writes` expected SOURCE but received INVENTORY at test line75/call294. On Windows `Path.of("assets://doc.txt")` throws InvalidPathException before the shared helper's explicit reference checks. Preparation already rejected the path without writing, but its generic exception handler classified it as inventory failure. The only fix adds InvalidPathException handling at the preparation document-read boundary and classifies it SOURCE. Shared Reader/helper behavior, rejection, budgets and all test assertions remain unchanged. No tests deleted/skipped/relaxed. red-01-source-manifest.json records the pre-fix source/test identities; original XML and exit1 log remain in junit-01-red and01-preparation.log.

## Deterministic coverage and persistence preservation

21 new tests cover exact official chunk text/metadata/order and deterministic summary; PENDING with no vectors; INDEXED with old hashes/old vectors; preallocation refusal before plan/gateway; invalid counts/dimensions/floats/norms; second-batch failure; high-dimensional batch limits; body mutation/document deletion/card overwrite/config changes/path rebind/missing config; duplicate pair; caller cancellation; real same-container shutdown; initial/final exclusive exclusion of queued normal operations; no long exclusive across gateway; normal-to-exclusive rejection; ownership loss/restart-required; strict JSON/identity and unsafe paths/junction; explicit singleton/legacy config namespaces.

CompletableDeferred controls concurrent phases, not fixed sleep. Tests use the same container/storage/root/coordinator and real task registration, not a second topology. Success/failure/cancellation snapshots compare all regular synthetic data-root payload SHA-256 (excluding only ownership lock). For deliberate writer mutations, the expected snapshot is captured after the test writer; preparation must add no further changes. No production root, Secrets, user archives, provider or preset/seed fixtures were read. Junctions and test files are temporary synthetic fixtures, cleaned only by their test teardown.

No vector namespace scan/publication proof is claimed: the prepared-only service leaves even a deliberately corrupted old index file untouched. S2B must independently validate/protect old sets and establish a durable cross-entity protocol. This is not permission to consume/rewrite an unknown index. No ragStatus/ragChunkCount/indexedHash/ragIndexedAt write, owned-media cleanup, Memory journal access or F4 safety weakening.

## Resources and artifact integrity

Initial available1062.18MiB, Commit headroom11.21GiB, H:37.99GiB, Java0. Lowest observed228.36MiB during first compile,3133pages/s, Commit9.60GiB. Highest sampled single Java WS1,103,921,152B/private1,252,302,848B. Sampling does not establish absolute maxima/minima; no observed OOM, disk error, repeated sub128MiB or sustained extreme-paging condition. Final811.55MiB, Commit10.21GiB, H:37.98GiB, Java0. Frontend responsiveness was not objectively measured; Chrome/NAI and all user processes were left untouched. One build at a time.

Seven raw XML and three logs were reviewed: empty XML stdout/stderr; the RED stack contains synthetic test/enum values and code locations, no user body, private path, key, cookie, token or auth header. junit-manifest.json/log-manifest.json contain original SHA-256; raw XML/log/runner bytes use local.gitattributes preservation. Staged Git blobs are hashed before commit, independent of working-file checks. Historical P9/P11/B5B evidence unchanged. Account weekly used reading1% near00:05JST, not a per-task charge.

## Pending boundaries

P9-S2A is CODEX CANDIDATE / PROJECT REVIEW PENDING. Production indexing/persistent staging/crash recovery/S2B, final Prompt injection, CHAT_MEMORY, full P9/P10/P11, P11 import/restore, D-040 implementation and Public Preview are not promoted. P11-S0 document A records only Project's existing qualified inspector acceptance. No protected branch movement or merge authorization.

## Exact command records
- **01-preparation**: exit 1; 140.159s; 10/15/2026 23:56:36 -> 10/15/2026 23:58:56.

  `--offline --no-daemon --no-parallel --max-workers=1 --priority=low --console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process :desktopApp:compileKotlin :desktopApp:compileTestKotlin :desktopApp:test --tests com.example.chatbar.desktop.DesktopDocumentRagIndexPreparationTest`

- **02-preparation-final**: exit 0; 61.965s; 10/15/2026 23:59:40 -> 10/16/2026 00:00:42.

  `--offline --no-daemon --no-parallel --max-workers=1 --priority=low --console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process :desktopApp:compileKotlin :desktopApp:compileTestKotlin :desktopApp:test --tests com.example.chatbar.desktop.DesktopDocumentRagIndexPreparationTest`

- **03-focused-regression**: exit 0; 58.042s; 10/16/2026 00:01:19 -> 10/16/2026 00:02:17.

  `--offline --no-daemon --no-parallel --max-workers=1 --priority=low --console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process :desktopApp:compileKotlin :desktopApp:compileTestKotlin :desktopApp:test --tests com.example.chatbar.desktop.DesktopDocumentRagIndexPreparationTest --tests com.example.chatbar.desktop.DesktopDocumentRagReaderTest --tests com.example.chatbar.desktop.DesktopFormatRepairDerivedSafetyTest --tests com.example.chatbar.desktop.DesktopTaskRuntimeTest --tests com.example.chatbar.desktop.DesktopMainChatRequestAssemblerIntegrationTest`
