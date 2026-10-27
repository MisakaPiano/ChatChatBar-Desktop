# Retained media growth: actual refusal, two counterexamples, bounded correction

## Actual large relocation refusal

The frozen 1007 implementation/source `85d16a67130a954d30bab81a87c41d35eabdc18f` returned move exit 1 / MATERIALIZATION. The failing production statement sums mapped file lengths against the existing **4 GiB** expanded bound. It did not reach successful publication or the independent reopen step. Original sensitive raw reports remain local; only the explicitly reviewed numeric/category aggregate is included.

A subsequent read-only bootstrap check confirms it still references the retained source and does not reference the refused target. The source directory exists. Its audio inventory contains 5,840 MP3 files / 1,123,095,547 bytes and 4,050 receipts. These counts are observations, not permission to delete unreferenced files. Full post-failure source/key equality was **not independently rechecked by that aggregate**. No original phone archive was written and no Provider action was triggered.

## Two actual synthetic failures

- A source-identical selection with no incoming records still added a second set of phone image/audio/receipt files, despite the same logical path and authenticated content hash already being retained.
- Restoring a source with a valid existing Desktop MP3/receipt copied that file and then always derived another MP3/receipt. The small counterexample observed two MP3s instead of one.

Actual RED: 2 suites / 12 tests, **10 PASS / 2 FAIL**, exit 1. Original failures and pre-execution source snapshots remain.

## Minimal correction and invariants

`DesktopMergeDestinationPlan` shares the already-retained resource only when **no incoming records are selected**, the logical path is identical and both verified hashes match. Newly selected/forked incoming consumers and different bytes are not coalesced; no content-only or name-only account/record merge is introduced. This prevents self-import from growing an unnecessary second media tree while preserving all distinct incoming files.

`DesktopFullBackupMapping` reuses a copied Desktop clip only when its mapped path matches the session's managed audio layout and its registered corresponding receipt maps to that exact MP3. The existing AudioStore verifies path/receipt/length/hash/MP3; a corrupt receipt throws and cannot become an unowned fallback. Actual MP3 duration is derived under the same limit. Other clips still use the existing managed persistence route, retaining their original imported files. Original source duration/length checks, message/version identity, historical custody, all source files and capacity bounds remain. No GC, Prompt, schema, SecretStore or transaction-publication change is made.

Tests compare the complete relocated image/audio/receipt **hash multiset**, not only a current clip; original source fingerprints and keys remain checked. Added negative coverage retains distinct bytes/live consumers, rejects bad hashes/lengths and proves unowned original MP3 bytes survive with a separately verified managed copy.

## Every actual execution, without accumulating history

| Round | Suites | Tests | PASS | FAIL | ERROR | SKIP | Exit |
|---|---:|---:|---:|---:|---:|---:|---:|
| RED | 2 | 12 | 10 | 2 | 0 | 0 | 1 |
| First focused green | 3 | 20 | 19 | 0 | 0 | 1 | 0 |
| First expanded regression | 6 | 55 | 55 | 0 | 0 | 0 | 0 |
| Verified final regression | 12 | 99 | 99 | 0 | 0 | 0 | 0 |

The first green skipped the explicitly opt-in native DPAPI case. Both expanded executions explicitly set `CCB_PREVIEW_NATIVE_DPAPI=1`, using only invented values and task-owned roots, and the native case executes without a skip.

During review of the 55-test snapshot, its new corruption test was found to throw an `IllegalStateException` inside an `assertFailsWith<IllegalStateException>` success branch. That would falsely accept admission. The test was corrected to retain any unexpected returned plan and close it in `finally`, with no matching exception thrown by the test. Only the corrected **99-test** execution is final evidence of that negative oracle. The earlier source/XML is preserved, not rewritten.

Fresh XML contains 12 actual suites. An extra requested `*DesktopFullBackupGraphValidationTest` pattern matches no standalone class; no suite is invented for it. Graph validation is executed in the actual Mapping, RootPreparation, Service and independent HistoricalSaveSlotProcess tests. The initially misqualified ImportService filter in the 55-test round also did not execute that suite; the final wildcard filter does. Counts come from the actual XML, never the filter list.

The current source change is two production files and three test files; three JUnit methods were added and existing mapping/relocation assertions strengthened. The supplied official serialization/models, limits, restore gates and ownership contracts are not weakened. This checkpoint is not the successful rerun of the actual large case. Its new target must be separate from the retained failed preparation, and source/old keys must remain intact. Final-source freezing and the actual retry follow separately.
