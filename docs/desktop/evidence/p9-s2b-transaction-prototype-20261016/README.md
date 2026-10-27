# P9-S2B isolated transaction candidate evidence

2026-10-16 JST. **CODEX TEST CANDIDATE / PROJECT REVIEW PENDING. Production P9-S2B NOT IMPLEMENTED.**

## Linear checkpoints

Initial local/tracking/live feature: `2058ebc65fd88a8565ff1b4cd7cd0bc1dcb24144`, clean index/worktree.

1. P9-S2A acceptance registration: `af037951a30dafa521010eb3ff7931be9f42bbc0`, parent initial SHA (docs only).
2. J/G design candidate: `f1908b7376e414d8a7453cbdc0a9dcbab845d4b8`, parent 1 (docs only).
3. Synthetic transaction tests: `728ed212fcf70aff82a011443a4e7046ecd4f9a5`, parent 2, three new test files +741/-0.
4. This independent evidence commit has checkpoint 3 as its parent. No production files or schemas changed.

[Source compare](https://github.com/MisakaPiano/ChatChatBar-Desktop/compare/f1908b7376e414d8a7453cbdc0a9dcbab845d4b8...728ed212fcf70aff82a011443a4e7046ecd4f9a5).

Protected local desktop remains `4c2af2576e2319e8041ffd2a6c5a205450f18246`; origin/desktop remains `5ef95428421f7c693b010ac88f51d470804ab07a`. Local/tracking master and sync refs unchanged. Formal baseline 1.4.1/D-036 unchanged. Existing local desktop lag is intentional.

## Actual runs

All commands ran from `H:\ChatChatBar-Desktop\app`, using `run-stage.ps1`. Common exact Gradle arguments:

```text
--offline --no-daemon --no-parallel --max-workers=1 --priority=low --console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process
```

Each `NN-*.json` records the actual argument array, start/end time, duration and exit code; matching `.log` is original output. No `clean` or `--rerun-tasks`.

| Run | Task/selection | Exit | Suites/tests | Failures/errors/skips |
| --- | --- | --- | --- | --- |
| 01 | `:desktopApp:test --tests com.example.chatbar.desktop.DesktopDocumentIndexTransactionPrototypeTest` | 0 | 1/9 | 0/0/0 |
| 02 historical RED | `:desktopApp:test --tests com.example.chatbar.desktop.DesktopDocumentIndexTransactionProcessTest` | 1 | 1/2 | 1/0/0 |
| 03 final process | Same whole process class | 0 | 1/2 | 0/0/0 |
| 04 adjacent | `:desktopApp:test` selecting `DesktopDocumentRagReaderTest`, `DesktopDocumentRagIndexPreparationTest`, `DesktopFormatRepairDerivedSafetyTest`, `DesktopDataOperationCoordinatorTest` (full qualified names in JSON) | 0 | 4/68 | 0/0/0 |

Final distinct green evidence: **6 suites / 79 tests / 0 failures / 0 errors / 0 skips**. Prototype 9, process 2, Reader 18, preparation 21, B5B 15, coordinator 14. Seven raw XML files include the historical RED as well as the six final green suites. A stage/cut is not a separate JUnit test. `junit-summary.json` records exact durations and original XML SHA-256.

Desktop test Kotlin compilation actually executed in runs 01/03 and passed. SharedCore/Desktop main compilation and test fixtures were **UP-TO-DATE**, not freshly compiled. No main code changed. Android compilation, full regression, Compose/Windows GUI, packaging and real Provider requests: **NOT RUN**.

The original RED was `AlreadyInUse` on the parent's immediate ownership reacquire after an exact child had exited. The other recovery-crash test passed. Correction only uses the existing ownership-process-test pattern: a maximum five-second, yielding OS-lock reacquire check, rejecting same-JVM contention or expiry. It does not delete the lock, change production ownership, ignore a failure or rerun until green. The next full process-class run passed. Its original XML/log remain in `02-process-red-junit/` and `02-process.log`; no earlier P9-S2A or other historical evidence was altered.

## Actual disk fault matrix

The test-only prototype uses real `JsonFileStorage`, official serializers/shared chunking, held root ownership, actual `DesktopDataOperationCoordinator` normal/exclusive scopes, real files, `force(true)`, same-volume `ATOMIC_MOVE`, SHA-256 and real repository/storage cache refresh. It accepts only marked `p9-s2b-synthetic-*` temporary roots. No HTTP gateway is present.

| Publish cut | Recovery result | Business gate |
| --- | --- | --- |
| BEFORE_STAGE | NOT_COMMITTED; no transaction effects | open |
| AFTER_STAGE, BEFORE_JOURNAL, INTENT_WITHOUT_SEAL | COMMIT_UNCERTAIN; unsealed evidence retained | closed |
| AFTER_JOURNAL, FIRST_OLD_MOVED, FIRST_NEW_INSTALLED, ALL_NEW_INSTALLED, BEFORE_CARD, AFTER_CARD, BEFORE_MARKER | NOT_COMMITTED; old card/vector bytes restored | open only after proof/recovery/cache refresh |
| AFTER_MARKER, DURING_CLEANUP, AFTER_CLEANUP | COMMITTED; full new card/vector set verified, old backup cleanup completed | open only after proof/recovery/cache refresh |

`transaction-events.jsonl` preserves fixed enum/status/count/PID diagnostics from all runs. Its `fault-matrix` rows are all fourteen actual publish cuts. Both recovery interruption cuts (first new removal, first old restore) converge to byte-identical old payload, followed by byte-identical second recovery.

Additional deterministic tests cover corruption of intent/old backup/moved-old/staged-new/live-new/card/commit marker and unknown side files (eight variants): reject before recovery writes and preserve every observed byte. Source document edit, card deletion/replacement and embedding-config change after staging are refused before old-vector installation. Manual/orphan/legacy/unknown-field/malformed/wrong-storage-ID vectors are not swept or overwritten. Synthetic normal write failure uses a nonempty directory at the intended journal-file path: exception retained, original live payload preserved, unknown residual temp evidence blocks opening. Cancellation at a controlled suspended partial install leaves no detached writer; subsequent explicit recovery restores old state. Other-character DOCUMENT, CHAT_MEMORY and immutable image hashes remain equal.

Actual CharacterRepository prewarmed card cache and JsonFileStorage vector cache remain gated during partial installation and are refreshed only after strict proof. Reader retrieval afterward sees either both old IDs or both new IDs, never a mixed valid result. This test barrier is not wired to production Flow subscribers.

The B5B test proves the existing production safety gate rejects the unknown prototype namespace. It uses a real AppContainer for that rejection, but does not claim the prototype is a production TaskRuntime writer or that its separate test coordinator shares the container's full lifetime. Production writer admission remains a blocker.

## Real child interruption and fresh-process recovery

Final run: **32 sequential child JVMs: 12 intentionally interrupted, 20 normal recovery exits**. Each uses existing JDK17, `-Xmx96m -XX:+UseSerialGC -XX:ActiveProcessorCount=1`; only one child runs at a time. Parent records PID plus creation time, validates READY from its exact Process object, terminates only that self-started child and waits for exit before any next child. All final child exit checks passed; no user/old Java process was targeted.

Eight publish-process cuts: BEFORE_STAGE, AFTER_STAGE, AFTER_JOURNAL, FIRST_OLD_MOVED, FIRST_NEW_INSTALLED, AFTER_CARD, AFTER_MARKER, DURING_CLEANUP. Two further scenarios kill recovery itself at RECOVERY_FIRST_NEW_REMOVED or RECOVERY_FIRST_OLD_RESTORED. Every case uses fresh recovery JVMs, verifies protected SHA-256, repeats recovery in another fresh JVM without byte changes, and uses the actual Reader when business opening is proven. Unsealed staging stays uncertain/closed. No model request is repeated. `final-child-results.json` contains all 32 final PID/creation/exit records. Raw events also retain the nine child runs from the RED attempt; PID reuse across attempts is distinguished by creation time.

## Resource observations

Temurin 17.0.20.1+1, Gradle9.1.0, unchanged `-Xmx2048m`, offline, one worker, low priority, in-process compiler. `environment.json`, `jdk-version.txt`, original `resources.jsonl` and normalized `resource-samples.json` preserve observations. The original resource file begins with one multiline preflight object followed by compact sample objects; the normalized file is a JSON array.

Preflight available RAM ~1.002GiB, Commit headroom8.214GiB; no Java process. Lowest periodic available sample178MiB; minimum sampled Commit headroom6.534GiB; maximum sampled paging10,568pages/s. No observed sustained combination met the protective stop condition; no OOM or disk-write resource error occurred. Final sampled available1435MiB, Commit headroom8.404GiB, paging0, Java processes0. H: ~37.971GiB free. Largest sampled individual Java private bytes897.8MiB; this is **not an absolute peak**. Sampling is periodic, aggregate physical memory is not the sum of working sets; true peaks and user's foreground responsiveness are UNKNOWN. Chrome/NAI were not read, automated, closed or changed. Account weekly usage observed2%; shared-account readout cannot establish per-task billing.

## Explicit limits / next review

J is a conditional **design/test candidate**, not an accepted production transaction service. Small fixed synthetic fixtures, at most64vectors and512KiB individual metadata/payload files, one pending transaction, bounded receipts are deliberate experiment limits. Multiple historical receipts/retention and migration need an independent production contract; no claim of arbitrary multi-generation startup recovery. Hash/seal is integrity checking, not authentication against coordinated malicious rewrites.

Windows process termination is **not power-loss durability**. Directory fsync, hardware-cache/disk-loss ordering, physical replacement guarantees under power failure, full-disk/permissions chaos and actual launcher startup/shutdown integration remain **UNKNOWN / NOT RUN**. Injected IOException/corruption and the tested real filesystem write error do not prove all physical I/O failures.

Production blockers in125 remain: schema/domain registration and D-038 backup/restore, old-source writer admission and TaskRuntime shutdown integration, Main ownership-before-container startup gate, suspended business Reader/Flow/cache publication, cross-platform/Android compatibility, receipt retention/cleanup policy. The prototype has no production call site. F4 UNKNOWN rules were not loosened. No RAG Prompt injection, production indexing, paid embedding, real data, P10/P11 migration, D-040 implementation, or Public Preview acceptance is introduced.

Source SHA-256/Git blobs: `source-manifest.json`. All evidence bytes: `evidence-manifest.json` (excludes itself). `.gitattributes` disables text normalization so original XML/log bytes survive Git. Sensitive-content inspection found no credential, real message/document/media or unredacted command line; only synthetic test identifiers and permitted PID/resource diagnostics are uploaded.

**P9-S2B DESIGN / ISOLATED EXPERIMENT: PROJECT REVIEW PENDING. Production implementation and final acceptance are not authorized by these test results.**
