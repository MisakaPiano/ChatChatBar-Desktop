# P11-S1 R1 Graph Admission — Project review handoff

2026-10-23 JST. **R1 FIX IMPLEMENTED / 95 TESTS PASS / PROJECT CODE REVIEW REQUIRED**. Authority: CURRENT149 and contract150. This is a production correction to S1, not a test-only change or P11 acceptance.

Control/parent: `3fc458135b68c5c03623d027bc3c1c5c0c06b594`, branch feature/phase8-fish-audio. Local tree safely fast-forwarded from the previous S1 delivery; the only preexisting untracked paths were protected user files/attachments and old experiment cache, all retained unread/unhashed/unstaged. No reset/force, official upstream sync, baseline change or desktop merge.

## Findings corrected

**A — Unknown family admission.** The pre-fix production validator gave SOURCE_GRAPH_VERIFIED_WITH_SCOPE to a closed known Moment graph plus moment_extension, and NOT_APPLICABLE to a future-only archive. Real RED captured both outcomes, after successful S0 format recognition and uninterpreted counting. S1 now checks every parsed family before interpreting dependencies or reaching NOT_APPLICABLE. Only current DesktopBackupPolicy.schemas types (already strictly checked by S0) and explicitly supported moment_posts/moment_tasks qualify. Other families return BLOCKED/UNKNOWN_ENTITY_FAMILY, with no source type name, field, path or payload in the report. This guard applies to arbitrary new families, not just names starting with moment.

S0 inspect remains unchanged and continues to read/count unknown families. The guard is entirely in MomentArchiveReadOnlyValidator; no authentication/parser/restore rule was modified.

**B — Sender/card reason consistency.** The third real RED captured v1 missing CARD=MISSING but SENDER=NOT_SELECTED. Missing dependencies now share one conservative reason function. With no scope, missing card and sender are both MISSING. v2 zero selected records proves NOT_SELECTED for that family; a positive aggregate count cannot prove selection of a specific absent ID, so UNKNOWN_SELECTION is used. It remains UNRESOLVED_DEPENDENCIES and cannot promote a case to VERIFIED. Known card/sender IDs still resolve by ID, duplicate sender identity conflicts block, and null sender retains SNAPSHOT_ONLY. No live target data is available for gap filling.

## Scope and evidence

Only two existing source files changed: MomentArchiveReadOnlyValidator.kt and MomentArchiveReadOnlyValidatorTest.kt. Six methods were added; the original S1 ten tests and helpers remain text-equivalent after newline normalization. The other seven suites are unchanged. Evidence: raw/test-preserved-delta.json, source-identity.json and boundary-identity.json.

RED: **3 tests, 3 assertion failures, exit1**, original XML/log/source/source hashes and actual input archives retained. It was run before production edits on control source. Unknown-family GREEN tests cover v1/code84 and v2/code85/86, plaintext/encrypted, both known-plus-unknown and unknown-only. Additional tests cover a known AppSettings positive, arbitrary new settings family, v2 count ambiguity/zero count and sender match/missing/conflict/snapshot cases. Known credentials and all runtime-denial flags remain governed by the original tests and unchanged guard.

Final current execution result and exact suite counts are recorded in raw/results.json, raw/final-xml/ and TEST_EVIDENCE.md. No historical counts are added. Fixture and source SHA/length/Git identities are retained; raw/evidence-sha256.json covers other raw evidence and excludes itself. Local attributes preserve exact raw bytes. Implementation/source commit and final receipt are recorded in DELIVERY.md and the external handoff.

Actual final result: **8 suites / 95 tests / 0 failures / 0 errors / 0 skipped / exit0**. S1=16, S0=18, old restore denial=1, Archive=12, Service=10, Journal=4, RootOwnership=14, Startup=20. The controlled S1 child exited73/alive=false. All tested source hashes remained unchanged across the final run. Complete source delta is also captured in raw/source.diff.

Boundary identities confirm S0 Inspector, Codec, Archive, Service, Controller, Journal, Startup, RootOwnership, SecretStore code and transient snapshots are unchanged. Formal Entity/Prompt/Android/GUI/Scheduler/runtime/persistence/budgets are unchanged. Source/journal preservation, authentication/CRC/hash/cancel/child-exit and v2 Prepared denial remain covered by the full eight-suite run.

Skill review before staging: existing desktop backup skill remains accurate (separate S1 report, unresolved dependencies, denied runtime/persistence and restoration); no entry point or workflow changed, so no skill edit was needed.

## Limits and stopping point

Known selected source graph classification only. FORMAT_READABLE is not RESTORE_SUPPORTED or FULL_PORTABILITY_ACCEPTED. UNKNOWN_SELECTION does not claim a specific ID was omitted or lost. OMITTED_BY_SCOPE still describes source projection policy, not proof a pre-export image existed. Arbitrary text is not certified credential-free.

No GUI/Android/ADB, real archives/roots/credentials, provider/network runtime, migration, installer, Task execution or new persistence. Existing budget and ZIP32 bounds remain. Original RED and Project decisions are not overwritten.

Stop at **P11-S1 R1 Project Code Review Gate**. Project decides acceptance. P11/D-038/Post-P8 HOLD/Preview remain unchanged; S2–S6/C1/C2 are not started.
