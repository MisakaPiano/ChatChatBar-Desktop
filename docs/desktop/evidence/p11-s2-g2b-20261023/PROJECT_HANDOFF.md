# P11-S2-G2B — Read-only credential census, Project review handoff

**IMPLEMENTED BOUNDED INTERNAL COMPONENTS / PROJECT REVIEW REQUIRED.** Stop at G2B Code Review; no G2C, real credentials, durable transaction, P11/D-038 or Preview acceptance.

Start `0db537d37e309550833c1e3693e0b4d0284e897b`. Separate governance commit171 records the user's current G2B engineering instruction and Project's G1B-R1 **PASS WITH SCOPE**;170 remains historical DRAFT/NOT AUTHORIZED. G1B topology/durable recovery is not accepted for production. Exact governance/source/evidence/receipt SHAs are recorded in DELIVERY after the source commit. Remote desktop remains `5ef95428421f7c693b010ac88f51d470804ab07a`; local stale desktop ref is untouched.

## Delivered and proved scope

- Only existing production modification: `DesktopBackupReadOnlyInspector.kt`. New internal `DesktopCredentialSourceCensus`, `VerifiedCredentialSourceMetadata`, `DesktopCredentialTargetObservation` are unwired. Source/test diff is `CODE_DIFF.patch`; no Archive/Codec/Policy/Service/Startup/SecretStore/Journal/Root/GUI/Provider/Prompt/Entity/Gradle change. Skill gets a compact entry-point/lifetime update under AGENTS maintenance.
- `inspectCredentialSources` is opt-in. The same invocation performs all existing authentication, ZIP/CRC, strict JSON/duplicate keys, inventory/hash/Manifest, Entity and scope checks. The early afterAuthentication callback does not publish census. Final publication happens after the same scan's final checks; a cancelled return closes published transient buffers. Existing inspect/inspectMoments paths do not instantiate a builder and retain their report/restore behavior.
- Known-location completeness supports **default official145/code84/v1** Preferences and four Entity families: AppSettings, ModelConfig, legacy EmbeddingConfig and singleton EmbeddingConfig. Seven credential kinds include Community. Exact raw field states and per-file byte SHA/length survive independently of G2A presence mapping. Records retain private source record identity; account metadata preserves exact ID/endpoint/template/remote model.
- Missing and explicit-null map to G2A ABSENT; EMPTY to EMPTY; NONEMPTY to NONEMPTY. This is not a future target clearing/preservation policy. Nonnullable official Entity fields can be rejected by the existing strict schema before census if missing/null; Preferences demonstrate all four states.
- Full pinned Model fields/defaults and AppSettings fields including numeric appearance objects are covered by raw source-backed synthetic goldens. Nonempty customParams, all other Entity families, non-entity media/packages, unknown fields/version and malformed/future Community payload make census incomplete or fail the shared schema scan. Incomplete results never access the target reader. **Most full-app archives with chats/cards/media therefore remain unsupported for complete census. This is not whole-app census closure or migration support.**
- Target reader has no storage writer API and no ambient implementation. Synthetic snapshots expose context/root/generation assertions only. Failures yield UNKNOWN; mismatched context, unknown consumers or changed generation yield no plan. A plan is created only from a complete source and a stable declared synthetic observation. These facts prove no OS ownership/lock/profile/CAS.
- Community is parsed only for known source shape; it has no protected sink and is **never passed to an API-key comparator**. G2A continues UNMAPPED. Scoped comparator exposes equality only, expires after each call; copied source chars are cleared on close/consumption/cancellation. Parser Strings/JSON/native buffers/JVM heap erasure and a hard deadline for a blocked injected reader are not proved.

## Actual execution

JDK17, headless/offline synthetic JVM only. Final **15 suites /179 tests /179 PASS /0 failures/errors/skips /exit0**,1m50s. New G2B27 plus existing152 actually rerun in this invocation, including historical G2A suites and two Mapping policy regressions. Historical150 is not added to this count.

Initial real RED: **24 tests /23 PASS /1 FAIL /0 errors/skips /exit1**,1m35s. Test reader wrongly compared a Community JSON session to an API-key literal; it exposed that Community must not be sent to a key comparator. Fix skips that impossible destination while preserving G2A UNMAPPED. RED XML and exact six-source snapshot are retained. Three additional final tests cover owned char wiping, legacy transport field identity and full pinned fields. No fabricated baseline RED.

Evidence collector initially assumed every old selected boundary was unchanged and refused the intentionally modified Inspector. Collector was corrected to record its old identity separately and enforce unchanged bytes on the other20; this is an evidence-tool correction, not another test run or production change. Original test logs/XML remain intact.

## Review artifacts and provenance

- `TEST_EXECUTION.md`: commands, per-suite counts and limits; `CENSUS_SCOPE_AND_TRUST_MATRIX.md`: detailed supported/incomplete/observation outcomes.
- `SOURCE_AUDIT_AND_NONWIRING.md`: fixed official identities and static audit, including the sole existing-file modification.
- `raw/red/` and `raw/green/`: original JUnit and source snapshots. `raw/green/fixtures` preserves every generated synthetic input and cleanup records; `raw/green/wire` extracts original plaintext positive bytes without rewriting.
- `raw/tested-source-identities.json`: actual tested workspace SHA/length **and** Git canonical LF SHA/blob. The Inspector workspace has CRLF; Git canonicalizes it. Its retained raw snapshot is exact tested bytes; committed code matches after CRLF→LF normalization. Other five new Kotlin files match bytes directly. Do not describe all six raw workspace blobs as Git's identical byte blobs.
- `raw/official-source-identities.json`, `regression-test-identities.json`, `inspector-before-identity.json`, `unchanged-executing-boundaries.json`, `fixture-identities.json`, `nonwiring.txt`, `redaction-audit.json`, and raw byte index. Original frozen evidence is untouched.

Known synthetic secret-marker audit covers diagnostic log/XML only, not an arbitrary future-input proof. Explicit synthetic fixtures and source intentionally contain labelled fake values. No real archive/key/password/root/DPAPI, Android/GUI/provider/installer or upstream fetch was used. Known six untracked attachments/handoff/cache files remain unread/unhashed/unstaged/unmodified. No parallel task at this cwd was observed; remote was rechecked before commit.

## Remaining blockers / NOT TESTED

No general credential census for other official Entities, nested SaveSlots, opaque custom parameters or media. No protected Community lifecycle. No native target observer, global consumer registry, A/B topology choice, real principal identity, cross-process CAS/locks, durable intent, key write or rollback. Fake context assertions cannot establish any of these. Source record SHA authenticates a whole validated file, not account authority or a plaintext secret fingerprint.

No real DPAPI/profile/Portable, actual Android/Windows full migration, old-binary prevention, secret-participant hard restart/power-loss, durable capacity or Moments seal. Existing Inspector/Journals synthetic crash/cancel tests do not prove a new transaction. `restoreSupported=false`, `restoreAuthorized=false`, `authorizesBusiness=false`, v2 restore denial and `CREDENTIAL_APPROVAL_REQUIRED` remain. Baseline1.4.1 and P11/D-038/Post-P8/Public Preview HOLD unchanged. Independent Project review required; no next-stage implementation.
