# 172 — P11-S2-G2B Credential Census: Final Project Acceptance

**CURRENT PROJECT DECISION — 2026-10-23 — P11-S2-G2B CLOSED / QUALIFIED ENGINEERING ACCEPTED.**

Docs-only registration of the user's supplied independent Project review of G2B and R1. Acceptance is limited to the declared internal read-only census and synthetic observation scope. No production implementation or next-slice authority follows.

## Git authority and original evidence

| Checkpoint | Exact SHA |
|---|---|
| G2B authorization | `5a7b9919cd561e4a0c39bd8a8999495406643bf5` |
| Tested original G2B source/evidence | `36a414596beeb850fbded9efa1386351fc83c927` |
| Original delivery | `40f0f3dec9b6f1448314ca25804bb9091e7751d2` |
| Tested R1 source/evidence | `8b05d47b30878da017925aab4f7ff10455892fb9` |
| Final R1 delivery / registration starting point | `f4c97643d89c04a068b1ea7c4072ce9d8c727745` |
| Remote desktop, unchanged | `5ef95428421f7c693b010ac88f51d470804ab07a` |

Original [G2B evidence](evidence/p11-s2-g2b-20261023/DELIVERY.md) and [R1 evidence](evidence/p11-s2-g2b-r1-20261023/DELIVERY.md) remain separate and unchanged, including static finding, real REDs, source snapshots, commands/exits, original JUnit, fixture identities and byte indices. This registration does not rewrite them.

Preflight matched local/origin final delivery on feature/phase8-fish-audio. No tracked dirt or another active task at this cwd observed. Six known untracked attachment/handoff/cache files were preserved unread/unhashed/unmodified/unstaged. No reset/force, other branch movement or real-data operation.

## Accepted engineering boundary

Original G2B modified only existing DesktopBackupReadOnlyInspector.kt, added three internal components and corresponding tests, with its authorized Skill maintenance entry. R1 modified only DesktopCredentialSourceCensus.kt and DesktopCredentialSourceCensusTest.kt; fixture helper and other execution components remained unchanged.

Trusted census handoff occurs only after the same scan completes ZIP/CRC, Manifest, Inventory/hash, Entity and Scope validation. The earlier afterAuthentication callback cannot authorize census. Existing inspect/inspectMoments behavior and restoreSupported=false remain; no formal restore, SecretStore or business-startup wiring was added.

Fixed official1.4.5 `1198a13cf3008de1358b8a69aa5e40b64797c8d9` CommunitySession declares `expiresAtMillis: Long = 0L`. Final R1 checks a non-string numeric primitive, integer-token grammar and valid Long range. Missing/default, zero, positive and both Long bounds are valid; strings, decimal/exponent notation, null, booleans, containers and overflow cannot incorrectly produce complete=true. Unknown Community fields remain conservative. Invalid source does not call target observer; Community stays UNMAPPED and never enters API-key comparison.

## Actual tests and attribution

| Execution | Actual tests | Outcome |
|---|---|---|
| Original G2B final |15 suites/179|179 PASS,0 failures/errors/skips,exit0|
| R1 pre-fix reproduction |3|2 PASS/1 FAIL,exit1|
| R1 intermediate correction |15 suites/182|181 PASS/1 FAIL,exit1|
| R1 final |15 suites/182|182 PASS,0 failures/errors/skips,exit0|

R1 adds **three JUnit tests containing17 wire cases**, not17 new JUnit tests. Historical179 and current182 are not accumulated. First R1 RED reproduced quoted123; intermediate RED exposed exponent1e2 coercion; both histories remain intact. All these numbers describe original Codex executions, not new runs during acceptance registration.

Project independently confirmed both final R1 source Git blobs match tested snapshots; individually read all15 final XML files confirming182 passing tests; independently recomputed SHA-256/byte length for17 original evidence samples, **17/17 PASS**. **Project did not rerun Gradle.** Codex's **127/127** raw-file self-verification remains separately attributed; Project's sample review is not a claim of full127-file independent verification.

## Explicit retained limits

- Only declared supported credential locations in default official145/code84/v1 are covered. Other Entity families, media, SaveSlot, unknown/nested content or opaque custom parameters may make census incomplete. No general whole-app completeness or Android interoperability claim.
- Target observation remains synthetic injection. Real DPAPI/profile identity, global consumer closure, CAS/locks, cross-root transaction and Community protected sink are unimplemented or unverified.
- CREDENTIAL_APPROVAL_REQUIRED remains. No real credential import, durable unified intent or formal restore wiring exists. G1B unified transaction design remains open; G2C is not authorized.
- P11 **PENDING**, D-038 **BLOCKED**, Post-P8 **HOLD**, Public Preview **HOLD** remain. No Feature Parity row becomes EXACT/EQUIVALENT; desktop is not merged.
- Formal validated baseline remains **ChatBar1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**; official145 is fixed format authority only, not an upstream sync.

This closeout changes only172 and directly related CURRENT control text. No Kotlin/test/raw evidence/Skill/Prompt/schema/upstream change, no Gradle execution or real test data. Historical NEXT entries retain their dated meaning;172 supersedes them only for G2B's qualified closeout.

**Stop: G2B FINAL ACCEPTANCE REGISTERED / G2C NOT STARTED.**
