# SaveSlot real-input findings and bounded corrections

This follows checkpoint `8fd4f337b20d566b4104263c6c8461557de36fdb`. Source files remain read-only. No private IDs, filenames, text, hashes, passwords or media enter this evidence.

## Streamed media budget

The existing v8 inspector streamed media with a 32 KiB buffer but counted those bytes against the same 64 MiB aggregate ceiling as structured data. A synthetic ZIP containing nine 8 MiB STORED resources reproduced refusal. Initial test compilation failed because a fixture tried to access another test's private Json; that source and log are retained. The next run stalled in Gradle TarBuildCacheEntryPacker reading a compiled output: a thread dump was retained, the exact task-owned daemon PID/start time checked and stopped, and the task runner now disables build cache. No cache deletion or other-process stop occurred. Neither environmental run executed tests.

`slot-budget-red-no-cache`: 1 suite / 3 tests / 2 PASS / 1 FAIL / exit1. `slot-budget-green`: 7 suites / 64 PASS / exit0. An additional compressed-small-source/oversized-expansion negative was then added; `slot-budget-final`: 7 suites / 65 PASS / exit0. Counts are separate rounds, not cumulative unique tests.

Production delta separates 256 MiB total streamed expansion from 64 MiB structured data. Existing 256 MiB source, 16 MiB per-entry, 1 MiB JSONL record, 256 KiB manifest, 4096 entries, 20,000 records and ratio checks remain. No S0 full-backup limit was changed. CRC, strict serializers, graph checks and later codec/restore validation remain. This expands a specifically tested media workload, not unrestricted archive admission.

## Legacy voice version identity

After that correction the real archive moved from `UNSUPPORTED/BUDGET` to `INVALID/REFERENCE`, with source bytes unchanged. A separate category-only partial reference diagnostic identified 32 voice references absent from raw message version fields; all 32 involved legacy messages needing the official version normalization policy. It did not claim these were dangling explicit versions or authorize a restore.

Fixed official145 `1198a13c:app/app/src/main/java/com/example/chatbar/domain/chat/MessageAlternativeVersionPolicy.kt` defines singleton fallback to source message ID and deterministic alternative IDs. The shared Desktop policy retains those rules. Existing `DesktopSaveSlotRestorePlan.message` normalizes before ID remapping, and `DesktopVoiceArchiveHandoff.stage` requires the resulting exact version identity. The inspector alone was checking only unnormalized raw fields.

`slot-legacy-red`: 1 suite / 3 tests / 1 PASS / 2 FAIL / exit1. An independent JDK UUID oracle generates the pinned official legacy alternative ID; a bogus explicit voice ID remains rejected. The correction reuses `MessageAlternativeVersionPolicy.versions(message)` after existing malformed-set checks. It neither calls voice text-based inference nor substitutes a current version for an unknown reference. Source archive bytes and all business Entity/schema definitions remain unchanged.

## Evidence interpretation

The Python reference probe is explicitly a partial diagnostic, not a second admission authority. Four synthetic probe tests run in each recorded round; no private source fixtures are published. The test-classpath isolated restore probe creates only a new task-owned directory, uses the existing service/journal and synthetic target session, and has no SecretStore, Provider, GUI or installed-root wiring. It must pass its synthetic smoke before any authorized real isolated run. Repository reopen in the same JVM is not an OS restart or Android round-trip. Verification covers message text/alternatives/version/turn identity and media bytes; it does not assert all session settings, RAG/Memory semantics or real-provider continuation.
