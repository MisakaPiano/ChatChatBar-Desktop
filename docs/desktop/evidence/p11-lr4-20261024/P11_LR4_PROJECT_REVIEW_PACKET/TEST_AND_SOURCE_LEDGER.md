# Test and source ledger

JDK17/offline/headless/one Gradle invocation at a time. Exact arguments/logs/exits and pre-run source snapshots are in each uniquely named raw round. No cache deletion, GUI/ADB/installer/paid API or real protector use.

| Round | Actual suites/tests | PASS/FAIL/ERROR/SKIP | Exit |
|---|---:|---:|---:|
| continuation-red |1/8|4/4/0/0|1|
| continuation-green |2/18|18/0/0/0|0|
| preparation-first |10/110|110/0/0/0|0|
| preparation-final |10/113|113/0/0/0|0|
| frozen-final |10/113|113/0/0/0|0|
| identity-witness-final |11/117|117/0/0/0|0|
| prepare-final / prepare-witness-final |0/0 each|no tests|0 each|

Final117=33 new LR4 tests (continuation8, fresh-root11, dormant11, private-witness3) +84 focused existing tests (LR3 voice10, format/resource21, G2A mapping17/intent20, S1 Moments16). Repeated rounds and historical LR2/LR3 counts are not summed. Early copied-source incremental output emitted only18 requested tests: that row qualifies only18; precise incremental-build cause was not fully isolated. Subsequent -Pkotlin.incremental=false rebuilt all requested classes and actual XML/testcase totals were checked. No missing/stale XML is counted as a pass.

Functional RED reflects existing strict refusal stopping before later manifest/central results. New B/C components were tested from their initial implementation; no fake preimplementation RED is claimed. The early small encrypted negative did not locate failure after a nested observation. Final fixture is >2 Tink1MiB segments with random padding after the historic nested package; the refusal asserts MEDIA_STREAM and RECORDS_1, proving authentication failure after a fully consumed nested entry. Bad inventory/central and other fatal errors still suppress result publication.

Final16 Kotlin files (12 opt-in tool/test,2 new main,2 new test) match identity-witness-final pre-run snapshots. Only new main/test package files were added, no existing production service edited. Local -text attributes preserve new tested source bytes; Skill documentation is normalized-text-equivalent under existing Git settings, not claimed raw-byte-identical. Skill update documents new unwired entry points. Original LR2/LR3 evidence, baseline, Prompt, Entity/Package semantics and formal Gradle remain unchanged.

Selected synthetic raw/media/late-auth fixtures and child logs/exits are retained in verified-synthetic/verified-child, SHA/byte-indexed. Not every generated temporary fixture from every earlier round is separately retained; exact source fixture definitions and original test evidence are preserved. Index and source/call-site checks are Codex self-check, not Project independent review.
