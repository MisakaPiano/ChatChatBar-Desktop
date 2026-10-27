# P11-S1 Moments raw graph read-only validation — Project handoff

2026-10-23 JST. **IMPLEMENTED / ENGINEERING TESTS PASS / PROJECT CODE REVIEW REQUIRED**. Stop at S1; no S2–S6 authority, P11 closure, D-038 acceptance, HOLD removal or Preview approval.

## Control and exact scope

- Parent/control: `3ec2a3239a296713b79303aff7e1aa41928d8e53`, branch `feature/phase8-fish-audio`, verified origin before fast-forward. Existing protected user files and old cache retained unread/unhashed/unstaged. No reset/force, desktop merge or official upstream sync.
- S0 accepted production remains `b531ff0263ab70857c716730049771e8a103fd51`; 147/148 govern this slice. Formal upstream baseline remains 1.4.1. Official145/146/147 are fixed format references only.
- New `MomentArchiveReadOnlyValidator.kt`, transient `MomentArchiveSnapshots.kt`, one S1 test class, backup skill map and this independent evidence directory. Existing Inspector changes only extract its unchanged scan into a private method and invoke a private callback after full successful authentication/ZIP/inventory/hash/manifest/scope validation.
- Codec, Archive, Service, Controller, Prepared/scheduleRestore, restore transactions, SecretStore, formal Entity/schema, Main/GUI, Android and Prompt files are unchanged. No new persisted domain/repository/scheduler/task activation exists.
- Exact tested source lengths/SHA-256/Git blobs: `raw/source-identity.json`. Exact implementation commit and evidence delivery are recorded in DELIVERY.md and the external handoff. Full diff is control SHA to final delivery SHA.

## Delivered behavior

`DesktopBackupReadOnlyInspector.inspectMoments(Path, CharArray?)` runs the same bounded S0 scan and returns an independent sanitized report. Ordinary `inspect` and its resourceGraphVerified=false semantics remain unchanged. v2 cannot enter Prepared or scheduleRestore.

S1 strictly extracts fixed MomentPost/Task fields, rejects unknown nested schemas/status, checks duplicate IDs within each type/source, and binds record SHA/length to the exact authenticated original bytes and source archive SHA. It separately reports card/session/sender and Task.postId relationships. It never borrows live objects or matches identity by names. Missing or unselected dependencies remain unresolved; task/post ownership conflict blocks verification.

Three media edges are independent: senderAvatar, imagePath and generatedImageMetadata.imagePath. Reports retain field identity, raw-reference digest, verified resource digest/length and state. Shared resources do not collapse edges; body and metadata may differ. Only canonical inventory image tokens are resolved. External references are blocked as uninterpreted without host/network IO. Code84 missing avatar blocks; code85/86 top-level historical missing avatar is a warning. Missing body/metadata blocks. Scope-projected null body/metadata is distinct from empty and missing; old momentsImages omission defaults true.

Known credentials block S1. Arbitrary text is never certified credential-free. Output contains only enums/counts/digests/lengths, with no paths, source IDs, text, Prompt or secret values. `restoreSupported`, `fullPortabilityAccepted`, `persistedSeal`, `taskRunnable`, `schedulerActivated` are always false.

## Actual evidence

- Functional baseline RED: one S0 graph-capability assertion fails; exit1 and original test/XML retained. This proves the absent capability, not a defect in the accepted S0 guarantee. Intermediate compilation and one manifest-fixture failure are separately preserved.
- Final run: **8 suites / 89 tests / 0 failures / 0 errors / 0 skipped / exit0**: S1 10, S0 Inspector18, old restore-denial1, Archive12, Service10, CommitJournal4, RootOwnership14, Startup20. No historical result is added to this count.
- S1 isolated child actual exit73 / alive=false. Inputs and dedicated synthetic journal file inventories/SHA unchanged across success, failure, cancellation and hard exit. No production plaintext scratch or target root created.
- Twelve current no-secret generated plain/encrypted/shared/distinct archives and their exact Post JSON retained with SHA/length. Source digest/raw Post digest/length and separate per-edge resource digests are asserted. Final source identity was checked unchanged after execution.
- Complete logs, exit files, final XML and intermediate REDs are retained in raw. `raw/final-results.json` is the current authoritative suite summary; raw hashes survive Git checkout through local attributes.
- No GUI/user PASS, Android/emulator/device, installer, real data/credential or provider execution. Selected malformed ZIP/budget cases are rerun through the same S0 scan rather than duplicated across every S1 parameter; see COVERAGE_AND_LIMITS.md.

## Independent review focus and remaining limits

Review callback placement after complete container validation, three independent media paths and versioned avatar exception, source-bound raw identities, strict unknown-field handling, dependency classifications, constant denied capabilities and absence of writable/runtime dependencies. Verify final raw hashes and source blobs using DELIVERY.md.

Only selected known graph structures are validated. Unsupported paths/unknown schemas block, absent selected dependencies remain unresolved, and no result certifies a complete backup or official Android re-export. No durable seal/retention, GC/root ownership integration, task quarantine, C1/C2 or full restore is delivered. These limits are intentional S1 boundaries, not S2 implementation approval.

**Stop: P11-S1 Project Code Review Gate.** Project independently decides acceptance; P11, D-038, Post-P8 HOLD and Public Preview remain unchanged.
