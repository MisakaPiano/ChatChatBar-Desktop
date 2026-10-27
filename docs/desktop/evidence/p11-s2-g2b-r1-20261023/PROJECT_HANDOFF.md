# G2B-R1 CommunitySession strict wire correction

**PROJECT RE-REVIEW REQUIRED / G2B-R1 ONLY.** Starting feature HEAD `40f0f3dec9b6f1448314ca25804bb9091e7751d2`. No new authorization for G2C, restore, real data or platform execution. Source/evidence and receipt SHAs are in DELIVERY after commit.

Project previously verified G2B Git boundaries, six source identities,15 XML/179 tests and17 raw hash/length samples. This R1 preserves those records; they are not claimed as new execution. New static finding is in STATIC_FINDING.md, written before production correction. Prior G2B24-test RED/179-test GREEN evidence remains unchanged.

## Change and source basis

Only two source paths change: DesktopCredentialSourceCensus.kt and DesktopCredentialSourceCensusTest.kt. Existing wire helper is unchanged. No Inspector/G2A/SecretStore/Archive/Codec/Policy/Startup/Journal/GUI/Prompt/Android/schema modification. Skill reviewed; existing generic source/lifecycle description remains accurate, so no out-of-scope Skill edit.

Pinned official145 `1198a13cf3008de1358b8a69aa5e40b64797c8d9` CommunityModels.kt declares `expiresAtMillis: Long = 0L`. Installed serialization-json dependency is pinned1.8.1 in app/gradle/libs.versions.toml. Before correction, longOrNull accepted a quoted numeric string. First actual regression reproduced complete=true for `"123"`.

Final guard permits missing expiry or a non-string JsonPrimitive whose original content matches JSON integer grammar `-?(0|[1-9][0-9]*)` and parses within Long bounds. Null, booleans, decimal/exponent notation, arrays, objects and overflow are incomplete. Missing uses the official default semantics; no source JSON is rewritten and no positive-only/nonnegative time policy is invented. Long.MIN_VALUE remains valid wire type. Unknown Community fields remain rejected by the existing known-field guard.

The intermediate suggested guard `!isString && longOrNull != null` fixed quoted strings but an actual expanded regression found the accessor also accepts `1e2`. That result was retained, not hidden. Integer-token validation fixes that coercion too. This is intentionally strict Long wire admission; no claim is made that all runtime JSON coercion paths reject exponent syntax.

## Test cases and safety

Three new JUnit tests generate17 independent raw CommunitySession wire variants inside official code84/v1 synthetic transport, using the unchanged independent fixture helper:

- Valid: omitted expiry,0,123,Long.MIN_VALUE and Long.MAX_VALUE.
- Invalid: quoted123,null,true,false,1.5,1.0,1e2,empty array,empty object,positive overflow and negative overflow.
- Future unknown Community field remains incomplete.

For invalid variants: census incomplete with NESTED_CONTENT; observeAndPlan yields SOURCE_INCOMPLETE/no plan; target stamp/reader counters stay zero; summaries contain no synthetic token. For valid variants: Community remains UNMAPPED/NO_PROTECTED_SINK and cannot authorize restore. Reader.observe now asserts that Community never reaches API-key comparison; this guard also covers all existing suite calls. Source archives compare unchanged and owned temporary directories are removed by unchanged fixture cleanup.

Redaction audit scans known synthetic values in diagnostic logs/XML, with explicit fake-valued fixture/source files excluded as intentional test material. It is not a universal arbitrary-input confidentiality proof. No new parser, comparator, observer, decoder boundary or recovery path is introduced. Existing restoreSupported=false, restoreAuthorized=false, authorizesBusiness=false and CREDENTIAL_APPROVAL_REQUIRED remain outside the source diff and in the complete regression selection.

## Evidence and limits

TEST_EXECUTION.md records each actual run separately. `raw/red` preserves unchanged-production repro source/XML/fixtures. `raw/intermediate` preserves the failed string-only guard and full15 XML. `raw/green` preserves final source/XML and all17 R1 archives/cleanup records. Exact commands/exits/logs, source/fixture/official identities, redaction audit and byte index remain independently available. Inspector and all other main code are checked by complete Git diff projection, not merely a sample.

The official source declaration is read from existing local Git objects; no upstream sync. No user archive/token/password/root/DPAPI/GUI/Android/Provider used. Known six untracked attachments/handoff/cache files remain unread/unhashed/unmodified/unstaged; no parallel task at this cwd observed. Remote desktop5ef95428 remains unchanged; no reset/force/branch switch.

Remaining limitations from G2B apply: restricted known-location census, incomplete other Entities/nested packages/media/custom parameters, no Community protected sink, synthetic-only target context/generation, no native identity/CAS or durable cross-root transaction. No P11/D-038/Post-P8/Public Preview HOLD promotion or baseline change (formal1.4.1).

**Stop at P11-S2-G2B-R1 PROJECT RE-REVIEW GATE.**
