# P9-S2B R1 completed-receipt candidate evidence

2026-10-16 JST. **CODEX CANDIDATE / PROJECT REVIEW PENDING. Production P9-S2B NOT IMPLEMENTED.**

## Control and source

Initial local/tracking/live origin feature `6fb37e3a9ce9349436865d13db655e1f0628d630`, clean worktree/index. Source candidate `2368280a54f206b999cce6ba57eee8d7fb69be3a`, parent initial SHA. This separate docs-only evidence commit follows it. Same `feature/phase8-fish-audio`, normal pushes only; desktop/master/sync/upstream were not moved.

[Source compare](https://github.com/MisakaPiano/ChatChatBar-Desktop/compare/6fb37e3a9ce9349436865d13db655e1f0628d630...2368280a54f206b999cce6ba57eee8d7fb69be3a).

Only three test files changed, **+146/-24**: `DesktopDocumentIndexTransactionPrototype.kt` (+52/-9), `DesktopDocumentIndexTransactionPrototypeTest.kt` (+61/-13), `DesktopDocumentIndexTransactionProcessTest.kt` (+33/-2). `source-manifest.json` contains final filesystem SHA-256, Git blobs and sizes. No production Main/storage/Reader/Entity/Prompt/Memory/B5B code or schema changed. Project skill review found no production ownership/workflow fact made stale by this test-only correction.

## R1-A: completed live reproof

`businessOpen()` is now suspendable so it can reuse actual strict storage reads. For a single completed receipt it validates intent/seal/marker/finish evidence, then exact live card and complete vector-set hashes, and strict uncached source/card/config identities via the existing `snapshot()` authority. Target old/new set, unrelated vectors, owned document path/content hash and config raw hash must agree. `recover()`'s no-pending branch must pass this same live proof before returning an earlier outcome/open state. Cancellation is propagated.

On any mismatch, direct opening is false and recovery returns **COMMIT_UNCERTAIN / false**. Completed-state refusal does not enter pending rollback, delete/repair evidence, refresh a cache, write a receipt or trigger a model request.

Two new deterministic unit tests exercise seven completed-state tamper variants: target vector deletion/corruption, card corruption, source edit/deletion, config corruption and unrelated vector deletion. A fresh storage/repository instance rechecks without cached authority; repeated refusal leaves all source/data/receipt hashes identical. Normal completed restart is verified for both COMMITTED and recovered NOT_COMMITTED outcomes.

## R1-B: fixed one-transaction boundary

`publish()` checks for any completed receipt in its initial exclusive snapshot scope, **before UUID/plan/staging/backup/intent installation**. It throws `IllegalStateException("Test protocol permits only one completed transaction")`. Tests install a cut callback that fails if any new transaction cut is reached, compare complete data/evidence hash maps before/after refusal, and prove the first outcome remains readable and recoverable idempotently. Unsupported multiple receipts also fail closed at the opening gate.

The prior cache test exercised rollback followed by another publish in the same root. It now uses two independent synthetic roots for the rollback and successful-publish cache cases, retaining the prewarmed-cache, disk/repository, vector and Reader assertions. This aligns the test topology with the explicitly approved single-transaction protocol; it is not production generation management.

**These strict completed-state equality rules apply only to this fixed synthetic protocol.** A production design must independently define receipt-to-later-writer identity handoff and legal subsequent edits. This candidate does not ban legitimate future product edits or introduce receipt cleanup/generation schemas.

## Actual commands and JUnit

JDK `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot` (Temurin17.0.20.1+1), Gradle9.1.0. From `H:\ChatChatBar-Desktop\app` via the included `run-stage.ps1`:

```text
--offline --no-daemon --no-parallel --max-workers=1 --priority=low --console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process
```

| Run | Selection on `:desktopApp:test` | Exit | Suites/tests | Failures/errors/skips |
| --- | --- | --- | --- | --- |
| 01-completed-focused | `DesktopDocumentIndexTransactionPrototypeTest.completed*` | 0 | 1/2 | 0/0/0 |
| 02-transactions | Complete PrototypeTest and ProcessTest classes | 0 | 2/14 | 0/0/0 |
| 03-adjacent | ReaderTest18, IndexPreparationTest21, DerivedSafetyTest15, DataOperationCoordinatorTest14 | 0 | 4/68 | 0/0/0 |

Exact fully qualified filters, argument arrays, durations and exit codes are in the three matching JSON files; original output is in matching logs. Final distinct evidence is **6 suites / 82 tests / 0 failures / 0 errors / 0 skips**. The two focused tests are repeated in the full prototype class, not added again to the final count. The seven original XML files and `junit-summary.json` preserve both stages. Run01 preceded indentation-only cleanup in the cache test; run02 compiled and tested the final source, including both new methods.

Desktop test Kotlin compilation actually ran and passed in runs01/02. SharedCore/Desktop main compilation and fixtures were **UP-TO-DATE**, not new compilation claims. Android, full regression, GUI/EXE and packaging **NOT RUN**. No clean, rerun-tasks or software installation. **No new RED**; previous `p9-s2b-transaction-prototype-20261016/` 79-test/32-child evidence and original ownership RED remain unchanged.

## Fresh JVM verification

Run02 contains **57 sequential child JVM launches**, **12 controlled interruptions +45 normal exits**, all confirmed exited. Existing 32-child crash/recovery coverage is retained; 25 additional launches cover normal completed restart, second-publish refusal and all seven completed tamper variants with repeated independent-process recovery/refusal. `child-results.json` records PID, creation time, mode, exit code and alive-after-wait for every child. Raw `transaction-events.jsonl` also contains per-case assertions and original fault/corruption matrix results.

Each child has `-Xmx96m -XX:+UseSerialGC -XX:ActiveProcessorCount=1`. Only one child at a time; only an explicitly stored self-started Process with matching PID/creation time is interrupted. No global termination or old/user process handling. Normal new publish uses fixed offline vectors, no HTTP/Embedding gateway. The normal completed case opens the real Reader and the rejected second publish leaves its first result intact.

All hash comparisons cover regular source, Entity, staging, backup, marker and receipt files; only the operational `.ccb-desktop.lock` is excluded by the existing process-test hash helper. No data/evidence path is excluded. The held ownership is not cleared to obtain a green result.

## Resource and preserved boundaries

Unchanged2GB Gradle heap, one worker, low priority, in-process compiler; no concurrent Gradle. `resources.jsonl` retains periodic samples. Preflight3268MiB available /11.339GiB commit headroom. Lowest observed1847MiB available, minimum commit headroom9.415GiB, maximum sampled paging2436pages/s; largest sampled individual Java private allocation818.3MiB. These are sample extrema, **absolute peaks UNKNOWN**. Final sample2420MiB available,10.933GiB commit headroom, paging0, Java processes0, H:37.962GiB free. No OOM, sustained severe pressure or protective stop was observed. Browser/NAI pages and real user data were not inspected or changed. Shared weekly quota observed2%, not a per-task cost measurement.

The prototype remains test-only and uses marked synthetic temp roots, real storage serializers/root ownership/coordinator and bounded fixtures. Production writer admission, startup recovery, Flow/cache barrier, transaction-domain backup compatibility, later legitimate writers and receipt retention still need Project design approval. Process interruption does not prove directory fsync, Windows replacement or power-loss durability. No production indexing, Prompt injection, Android RAG changes, F4 relaxation, actual Provider requests, P9/P11 or Public Preview acceptance.

All originals were byte-compared against the independent local gate before upload. `evidence-manifest.json` hashes every evidence file except itself; `.gitattributes` preserves raw bytes/Windows line endings. Sensitive-content inspection found no keys, real messages/documents, media, credentials or full private process command lines. Existing evidence hashes were independently rechecked and unchanged.

**R1 TEST CANDIDATE COMPLETE / PROJECT REVIEW PENDING. STOP; no production acceptance claim.**
