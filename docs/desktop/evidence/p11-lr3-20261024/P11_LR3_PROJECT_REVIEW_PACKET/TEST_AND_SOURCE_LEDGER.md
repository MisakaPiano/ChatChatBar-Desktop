# Executed source/test ledger

Start1569f44cf2413615bdfe14b103db69c3ed5e6013, same feature only. JDK17, offline, headless, one Gradle worker, bounded heaps. All new Kotlin resides under tools/desktop/interop/lr3/src; formal Gradle, production, LR2 original evidence and Skills are unchanged. No T0/T1 or full LR2 matrix rerun.

| Round | Actual result | Exit / interpretation |
|---|---|---|
| combined-category-red |0 tests|exit1, sharedCore.jar occupied; NOT semantic RED|
| combined-category-red-retry |1 initializationError|exit1, JUnit nonvoid helper error; NOT semantic RED|
| classified-green |22 tests,21 pass/1 initialization failure|exit1,21 actual format regressions and broken new test entry; NOT final GREEN|
| semantic-red |10 tests,5 pass/5 fail|exit1, corrected JUnit with old combined LR2 classification; genuine classification counterexamples|
| final-green |4 suites,31 pass/0 fail/error/skip|exit0|
| final-identity-green |4 suites,31 pass/0 fail/error/skip|exit0, final frozen source with explicit duplicate/malformed category|
| legacy-distinction-green |4 suites,31 pass/0 fail/error/skip|exit0, final classifier separates nonnull blank legacy representation but still refuses it|
| prepare-final |0 tests|exit0, classpath preparation only|

Final31 =10 new semantic tests +21 affected format/security regressions (18 LargeArchiveValidation,1 adversarial central-name,2 resource-identity). Do not sum repeated rounds or historical113. Final9 Kotlin files match legacy-distinction-green pre-run snapshots; earlier final-source-identities is the historical first-checkpoint identity, not a replacement for later frozen snapshots. Synthetic tests invoke the actual shared official Reader on independent ZIP fixtures, with selected method equality proven against145/147, and compare stricter graph diagnosis. Tests include known/null/pruned (actual append maxVersions=1)/missing/selection-ambiguous/explicit blank/cross-session/duplicate and actual ZIP voices-before-messages plus order-independent maps. No claim that synthetic pruning proves the real archive's history.

Environment correction: PID10908 was a repository Gradle Test Executor without a parent, no running Gradle daemon; exact identity was checked before releasing that single orphan. No app/other Java task was closed, no user/cache directories cleaned. Initial build failure preserved. All subsequent failures and input-signature corrections remain separately attributed.

Raw logs, arguments, exits and XML are in each round; names do not imply success. Source versions/blobs/SHA are in official-source-identities and final-source-identities; final raw byte index is RAW_INDEX.json. Hashes are of public synthetic/source evidence only. Original raw bytes are preserved with local -text attributes; do not decode/re-encode UTF-16 logs before hashing. All checks are Codex self-checks pending Project independent review.

Skill review: full-backup and SaveSlot skills read, production ownership/limits unchanged. New tool usage is in local README; no existing Skill fact becomes stale. Unknown attachments/handoff files retained. No Prompt/schema/baseline/parity/restore-policy change.
