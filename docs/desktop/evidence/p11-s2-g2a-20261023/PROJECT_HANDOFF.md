# P11-S2-G2A — Project Code Review handoff

Status: **IMPLEMENTED INERT CORE / REVIEW REQUIRED**. No G2B/G2C implementation, live restore, secret migration or P11/D-038/Public Preview acceptance is asserted.

Control: `648014ffda7c9e50029de8474faf36e011783bad`, feature `feature/phase8-fish-audio`. Source/test/raw evidence commit and final receipt SHA are recorded in `DELIVERY.md` after the source commit. Complete remote compare starts at this control, not the older G1A checkpoint.

## Delivered behavior

Two new internal, unwired Kotlin files under Desktop `backup/`:

- `DesktopCredentialImportPlan`: classifies metadata from a **future trusted authenticated decoder boundary** into six existing protected key namespaces. It accepts no archive, password or credential values. CommunitySession has no sink and is UNMAPPED. Unknown kind, contradictory location, duplicate source/target, invalid identity, missing target observation and unavailable protected store fail closed. Same ID with different exact endpoint, provider template or remote model conflicts; same name/different ID and existing different credentials require future explicit resolution. There is no confirmation bypass or apply function.
- `RecoverableIntentContract`: pure validation of declared source binding, operation/root/protected-context identities, advancing generation, unique data/protected participants, old absence/existing proof and new evidence. Allowed phase checks include recovery; COMPLETE requires one ALL_OLD or ALL_NEW outcome, exact per-participant evidence/generation and no unknown/ambiguous state. A check never authorizes business. No journal, IO, filesystem paths, locks, serialization, schema registration or actual transaction is implemented.

Inputs are **attested facts**, not cryptographically unforgeable capabilities. Source authentication, target comparison, context/ownership proof, protected old/new blob creation and durable evidence are intentionally not supplied by these components. A boolean assertion is not proof of OS safety. The test-only decoder is a deliberately bounded strict official145 wire subset, not a new production importer.

## Actual validation

Final bounded run: **13 suites /150 tests /150 PASS /0 FAIL /0 errors /0 SKIPPED; exit0**, JDK17, offline headless JVM, 1m47s. New G2A=42; existing regressions actually rerun=108. Do not add historical112 or prior RED40 to150.

First executed new-suite run: **3 suites /40 tests /39 PASS /1 FAIL /0 errors /0 SKIPPED; exit1**, 1m18s. The fixture did not create Archive's required owned work directory; it failed before the intended credential guard assertion. Its XML and exact five-source snapshot remain in `raw/red/`. This was a real fixture defect, not a discovered production transaction defect. A prior sandbox/cache-lock refusal executed no tests and has its own log/exit1.

Independent synthetic code84/v1 plain+password archives are authenticated by unchanged S0 before test-only metadata projection. Exact official145 Manifest/Preferences keys omit `scope` and `retainedPreferences`; Model template and Embedding dimension defaults are checked. Strict malformed/unknown/UTF-8 negatives reject without exposing payload causes. Both six-key archive variants still hit the existing `CREDENTIAL_APPROVAL_REQUIRED` guard. Community remains explicit UNMAPPED; existing S0/S1 and v2 Prepared refusal suites pass.

## Unchanged boundaries and safety

All **existing** `src/main` files are unchanged. Only the two new Desktop backup files alter production source. Source diff/projection and 21 selected existing boundary identities are preserved; all21 retain the exact workspace-byte SHA recorded by G1A. The skill gets one compact entry-point update under AGENTS maintenance, without runtime changes. No Archive/Codec/Policy/Service/SecretStore/Startup/Journal/Root/Portable/GUI/Provider/Prompt/Entity/Android/GC wiring changed. Static references to new contract families occur only in the two new files.

G1A was rerun with existing fake protectors, not real DPAPI. It still observes unsafe mixed persisted sequences; new pure-model rejection does not repair those sequences. 429 freshly rewritten G1A artifact identities are recorded; bounded JSON state/cleanup/outcome files are retained, rather than duplicating every fake ciphertext copy. New wire fixtures and raw JUnit are retained in full. Known synthetic-value scan of19 log/XML files found zero hits; identity summaries have additional hostile-string assertions. Explicit synthetic JSON/archive files intentionally contain labelled fake values.

Six preexisting untracked files (three conversation JPGs, handoff Markdown and two Python cache files) were left unread/unhashed/unstaged/unmodified. No unknown local writer or separate active Codex at this cwd was found. Another active project is in a different cwd and was not contacted. Remote feature and desktop matched the user controls before alignment and before commit. Local `desktop` is a stale `4c2af2576e2319e8041ffd2a6c5a205450f18246`; it was not switched or moved. Remote `desktop` remains `5ef95428421f7c693b010ac88f51d470804ab07a`.

## Remaining risk / NOT TESTED

- Actual authenticated typed credential decoder and target-state collector are not implemented or wired. Full official145 Entity semantics are not claimed for the small test-only projection subset.
- No durable multi-key/data transaction, actual recovery, cross-root concurrency/CAS, cancellation rollback, secret-participant hard crash, capacity/fault durability or success receipt exists here.
- Real DPAPI/account/profile/Portable, real archives/keys, Android, GUI, provider and old-binary downgrade protection are NOT TESTED. Existing Inspector's synthetic hard-exit/cancellation regressions are not secret-transaction recovery tests.
- Community sink/lifecycle remains missing. Explicit conflict resolution, C1/C2, Moments seal/resources, old-binary barriers and later stages remain unimplemented/unauthorized.
- Validated upstream remains1.4.1; selected official1.4.5 is a fixed archive research source. No upstream fetch/sync or baseline edit.

Review sources and complete diff, then independently decide G2A acceptance. **Stop: P11-S2-G2A Project Code Review Gate.** P11/D-038/Post-P8/Public Preview HOLD remains.
