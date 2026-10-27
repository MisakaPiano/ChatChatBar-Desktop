# 170 — P11-S2-G2B Read-Only Credential Census / Observation Candidate

**DRAFT / NOT AUTHORIZED — 2026-10-23. DO NOT EXECUTE THIS FILE AS AN ENGINEERING TASK.**

Design input: [169 G1B-R1](169_P11_S2_G1B_CROSS_ROOT_TRANSACTION_DESIGN_REVIEW.md), [168 G2A acceptance](168_P11_S2_G2A_TYPED_CREDENTIAL_AND_INERT_INTENT_PROJECT_ACCEPTANCE.md),158/162/163/160/95. Reviewed source checkpoint `99cc75a7650afe66e63e51e6242301d89b1be44d`; a future executable task must freeze its own actual remote HEAD and explicit approval. Docs-only review does not authorize Kotlin edits/tests or experiments.

## Candidate objective

Close a bounded portion of G2A's metadata trust gap: authenticated source → complete credential census for a declared supported official145/v1 code84 scope → strict typed identity metadata → synthetic target read-only observations → existing inert conflict planner. Results stay **read-only, unwired and non-restorable**. No persistent intent or topology decision is needed for this slice.

Formal baseline stays1.4.1; fixed official145 `1198a13c` is format authority, version declaration `app/app/build.gradle.kts`. Do not broaden to v2/code85/86, unknown schemas or nested content merely because S0 can recognize them.

## Proposed implementation boundary, subject to explicit approval

1. New internal Desktop backup census/extraction components and synthetic JVM tests. First audit exact pinned official DTOs and credential storage/field locations. Independent official-wire fixtures must not use Inspector DTOs to manufacture positive identity. Record canonical source blobs and original fixture bytes/hash.
2. Reuse the **complete** S0 authentication, ZIP/CRC/inventory/hash/version/scope checks; never emit trusted metadata from its early afterAuthentication callback. Current result lacks raw typed records. Before implementation, freeze one bounded adapter design: an internal post-full-validation handoff carrying owned volatile records/provenance, or another explicitly reviewed shared validated input boundary. Do not create a second unauthenticated parser or modify existing Inspector silently. If any existing signature/helper must change, enumerate the exact allowed file/diff in the executable task; absent approval, STOP and return proposed diff.
3. Census all actual known credential locations: preferences NovelAI/Fish/communitySession; AppSettings SiliconFlow; model configs; legacy and singleton embedding. Inspect nested official formats for credential-bearing payloads as well. A SaveSlot/unknown Entity/opaque record whose secret semantics are not fully interpreted makes **complete census unsupported**, not secretly credential-free. Preserve raw source identity; no dropping, normalization or rewriting unknown content.
4. Treat absence, null, empty and nonempty separately; no implied target delete/keep policy. Record original Entity family/record identity, exact source bytes SHA/length, archive binding, kind/location, scope/version and count. No plaintext credential hashes or raw JSON in shareable summaries. Enforce duplicate JSON keys/IDs/location contradictions, invalid UTF-8/types, future fields and schema rejection before asserting trusted metadata.
5. Source secret values may be transiently available inside the authenticated bounded decoder for presence classification, never returned to logs, serializable DTOs or ordinary staging. If comparison needs values, use an explicit short-lived injected comparison seam and synthetic values only. Define ownership/drain/zeroing limits; do not claim immutable String or native-memory erasure. Source archive remains user-owned and byte-identical.
6. Target observation is **injected and synthetic-only**: explicit fake-protector store/temp context or immutable test snapshot, known root/context identity and observation generation. No ambient resolver, WindowsSecretStore.create, real DPAPI, real LOCALAPPDATA or user roots. Prefer a read-only injected API without save/delete capability. Compare protected target availability/relation without secret-value output. IO/protection/missing context must be UNKNOWN/UNMAPPED, not ABSENT.
7. Target observations bind native identity/evidence and generation to the plan; re-read detects changed snapshots. Without a proved global lock/CAS protocol, do not label an observation stable until commit or shared-profile complete. Same ID/different endpoint/provider, duplicate target, name collisions, unknown consumers and Community no sink remain explicit blocked/confirmation categories. Do not acquire new production locks or initialize a live secret directory to simplify observation.
8. Feed the G2A planner only after source validation/provenance and observation requirements pass. Its `candidateConsistent` is not Prepared, durable ownership proof, restore permission or business admission. No production Service/Startup/GUI call site, public ingress or scheduled restore.

## Proposed acceptance matrix for a later authorized task

| Group | Required tests / evidence |
|---|---|
| Independent official positives | Minimal and full covered145 fields/defaults, six protected families plus unmapped Community, plain/password; exact wire keys, source/record SHA+length, strict pinned-source decodability. No circular self-generated DTO oracle. |
| Census closure | Every known location; null/empty; duplicate fields/IDs/targets; nested/unknown records return unsupported completeness. Zero keys does not imply complete census without closure proof. |
| Authentication ordering | Wrong password/tag/ZIP CRC/inventory/hash/truncation/path/unknown version/UTF-8 failure exposes no extracted metadata, cause payload or target access. Callback before EOF/validation never mints trusted input. |
| Target observation | Same/different/absent/unknown; fail-on-read/protection, account-context mismatch, reparse/path replacement and changed generation; fake protector only. Observation cannot overwrite or auto-bind accounts. |
| Redaction/lifetime | Public toString/report/error/serialized support metadata lacks values/passwords/raw JSON/full private paths/value digests; source bytes and synthetic target inventory unchanged, cancellation closes handles/owned transient buffers. |
| No activation | Existing credential refusal, v2 Prepared denial, restoreSupported=false; static call-site and exact diff proof. No durable artifact/schema/root marker or secret writes. |
| Regressions | Future task must enumerate affected S0/S1, G2A, Archive/Policy, SecretStore/secure repositories, Startup/Journal suites; JDK17 headless, exact commands/XML/exits/RED and SHA, real counts without historical accumulation. No GUI/device required or authorized by this draft. |

Unknown semantics must fail closed with precise redacted categories; do not silently shrink coverage or alter official Entity/Package/Prompt rules. Full145 migration, actual protected import, complete cross-root recovery and P11 acceptance cannot be claimed by these tests.

## Hard exclusions and activation gate

No live key write, persistent intent, recovery apply/rollback, new schema, Community sink, SecretStore/Root/Journal/Startup behavior change, provider request, GUI, Android/ADB, installer/old binary, real archive/API key/DPAPI/profile, Moments seal/Task/GC, upstream sync, baseline/parity promotion or desktop merge. No removal of CREDENTIAL_APPROVAL_REQUIRED; ordinary export remains credential-free. C1/C2 are not implemented implicitly.

Before execution: independent Project design review of169, explicit user authorization of this bounded source/test scope, freeze exact allowed files and validated-data handoff, define full census coverage/unsupported inputs and comparison lifetime, verify clean/known workspace and remote HEAD. If proof requires durable transaction or forbidden changes, STOP and submit a concrete blocker/proposed diff. Future delivery would stop at its own Project Code Review Gate, **not G2C**.

**Current status remains DRAFT / NOT AUTHORIZED. This task's completion does not activate this proposal.**
