# S1 coverage map and limits

Only isolated synthetic headless JVM archives. No GUI/Android/ADB, provider, paid API, physical device, real root/archive/credential, official Prompt or upstream sync. No network client, repository, scheduler, writable target root, restore, GC, SecretStore or new persistent domain exists in the new validator.

## Oracles

Test transport construction reuses the R1 independent official145/modern wire DTOs and pinned official147 test codec. Moment record JSON is independently written inline; production Moment extraction DTOs are not used to generate positives. Shared existing CharacterCard/ChatSession serializers generate synthetic dependency records only. Media fixtures are arbitrary exact byte blobs: no image rendering/decoding claim.

Each S1 entry invocation snapshots the complete dedicated input/journal file inventory and SHA before/after, including cancellation/failures and child exit. The source and isolated existing journal must remain unchanged, with no new files. New code has no output-directory or target-root argument. Fixture exports are deliberately written only by tests to build/s1-fixtures, then copied as evidence; they are not production staging.

| Contract dimension | Current test authority |
|---|---|
| v1/code84, v2/code85/86, plaintext/encrypted | S1 three-independent-edges matrix; original S0 official-format matrix |
| Three single edges, shared blob, body/metadata distinct | S1 three-edge and each-media tests; exact raw SHA/length/source digest asserted |
| Missing avatar version differences, missing body/metadata | S1 each-media test; code84 BLOCKED, code85/86 avatar warning only; metadata independently intact |
| Scope false/default true/old omission, moments false | S1 scope test; S0 strict scope/options/selection tests |
| Duplicate Post/Task IDs, every known/unknown status, null/resolved/orphan postId, mismatched card | S1 identity/status test; raw Task states remain untouched and nonrunnable |
| Card/session missing versus unselected, sender missing/snapshot | S1 scope and sender tests; per-record LinkField and Relation report |
| Cross-source identity | S1 sender/namespaces test, exact changed raw whitespace changes raw/source digests; no global merge/cache exists |
| Unknown fields/types/nested metadata, duplicate JSON key, malformed UTF-8 | S1 strict JSON test; S0 known-schema tests |
| HTTP/file/content/drive/UNC/traversal/ADS/encoded path/case alias | S1 unsafe-reference test; no resolver to host IO; S0 archive path/case tests |
| Inventory/hash/ZIP CRC/duplicate entry/size/ZIP32/future version/scope | Existing S0 tests rerun in this delivery on the same scan path, before the S1 callback; no second parser |
| Wrong password, encrypted tail corruption/truncation | S1 authentication test plus S0 integrity tests |
| Budgets/oversized source, cancellation, post-auth failure | S0 source/ZIP budgets rerun; S1 cancellation and injected exception |
| Child JVM exit, resource cleanup | S1 child exit73/alive=false and source/journal unchanged; S0 existing hard-exit test also rerun |
| Known credential versus arbitrary text | S1 known-key test returns BLOCKED without value; arbitraryTextCredentialFree always false |
| v2 Prepared denial and old restore safety | Original S0 restore-rejection test + full Archive, Service, Journal, Root ownership, Startup focused suites rerun |

The matrix combines newly executed S1 tests with newly rerun existing suites; it does not add historical counts. Several malformed ZIP/budget cases are exercised through the exact shared S0 scan rather than duplicated through the S1 convenience entry. These are source-seam evidence, not a separate exhaustive S1 permutation sweep.

## Limits and conservative handling

- Graph success is only SOURCE_GRAPH_VERIFIED_WITH_SCOPE for known selected Moment structures and their observed dependencies/resources. It never grants restore, full portability, persisted seal, Task execution or scheduler activation.
- Missing or unselected card/session/sender associations remain unresolved; local same-ID data is unavailable to this API. Historical orphan relationships are reported, never repaired.
- Unsupported external URI/relative references, unknown schema and unknown task status block graph verification. Only canonical NFC image inventory tokens are resolved; this narrower supported subset is deliberate and does not claim every official external URL can be ported.
- Blank optional image fields are EMPTY; projected body/metadata nulls are OMITTED_BY_SCOPE. A selected missing body image is BLOCKED. Code85/86 missing top-level avatars are MISSING_AT_SOURCE warnings, not fabricated media.
- Raw bytes are hashed while S0 reads/authenticates them and the exact inventory identity is carried to S1. Raw content is not reserialized or returned. This is not a persistence/retention or official re-export mechanism.
- No UI/user PASS, real image rendering, official Android execution, full backup migration, OS crash/power-loss durability, or S2 seal is claimed. Existing 64 MiB source/ZIP, 128 MiB expanded, 8 MiB JSON, ZIP32/40000 entry bounds remain.
- No provider/network side-effect instrumentation is simulated: production dependency inspection shows no callable network/runtime capability. Only controlled test JVMs are spawned, never unknown processes terminated.
