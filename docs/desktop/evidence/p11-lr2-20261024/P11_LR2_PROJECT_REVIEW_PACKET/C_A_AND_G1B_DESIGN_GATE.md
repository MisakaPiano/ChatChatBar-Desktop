# C-A and G1B-D0 — decision specification, NOT APPROVED IMPLEMENTATION

Authority182,179,169,163,158,160; baseline1.4.1 unchanged. T0/T1 are accepted limited predecessors, not rerun or expanded into production proof. Code source identity is recorded in CODE_SOURCES. No production source changes in this checkpoint.

| Candidate | Reusable fact | Required adapter / falsifier | Signoff |
|---|---|---|---|
| A profile-shared coordinator | Existing WindowsSecretRootResolver returns one profile directory, selected root only guards overlap | Enumerate/prove *all* consumers including offline/unregistered C and cached processes; any unaccounted consumer falsifies safe shared replacement | NOT SIGNED / global closure blocker |
| B root namespace | T0 synthetic isolation; T1 explicit native directory witnesses | Bind root generation, key account/provider/endpoint and actual protector principal; clone/profile mismatch must refuse, no shared-key fallback | NOT SIGNED / namespace and migration decision |
| B + inactive fresh root | Existing migration retains source, takes safety snapshot, prepares destination, then bootstrap-controlled switch and restart seal | New complete foreign-source preparer, protected participants and unique recoverable authority; one unreadable new participant forbids ALL_NEW | Conditional preferred investigation, NOT SIGNED |

Exact current call-chain audit: `DesktopAppContainer` lazy factory passes root into WindowsSecretStore but `WindowsSecretRootResolver` still selects LOCALAPPDATA/profile scope. NAI/Fish/model/legacy embedding/singleton consumers share it; NAI live fuse resolves that root separately. `DesktopSecretStore` exposes individual load/save/delete, no Community sink or batch/CAS. `DesktopBootstrapAuthorityTransaction` locks cooperating bootstrap writers and rereads source authority; it neither authenticates key participants nor fences CLI/Portable/old binaries. `DesktopDataRootMigrationService` preserves snapshot/source and restart-required ownership, but only for its supported provenance. `DesktopStartupGate` runs root-owned recovery before business; its root journal cannot recover profile keys. These facts corroborate179; they do not select topology.

Required future authority binds source version/scope/hash, root/native context evidence, protector/account identity, all OLD/NEW participants, expected generation and user conflict consent. Lock order and startup discovery must be common to all cooperating consumers; fail without writes on unavailable root/profile, missing authority, foreign CAS, ambiguous post-publication, unknown Community target or insufficient retained capacity. ALL_OLD is rollback, not import success. ALL_NEW requires independently reverified data, keys and dependencies. Multiple atomic moves do not make a cross-domain atomic transaction. Directory flush, power loss, DPAPI, old executable writes and Portable remain platform gates.

## C-A candidate persisted contract to approve before code

Three separate objects: (1) user-owned original archive, never automatically copied/deleted; (2) future validated sealed copy, exact original MomentPost/Task record bytes plus independent media; (3) eventual P13 Entities, never created/activated by sealing.

Proposed *information contract*, not chosen filenames/schema: seal format/version and official source identity; raw record path/type/ID/length/SHA; post/task/card/session/sender/postId relationships; three independent senderAvatar/imagePath/generatedImageMetadata.imagePath edges; resource length/SHA/original portable reference; independent owned target/native-copy evidence; interpretation/version/unknown-field status; preparation owner/generation and capacity reservation; snapshot/full-backup/root-switch/Portable participation receipts. Secrets and arbitrary user text never enter public metadata. Source paths/IDs/digests remain private.

Admission is all-or-refuse: authenticate source and central/inventory first, strict source graph, duplicate/conflicting identity refusal, no external/reparse resource, no missing edge silently repaired. Unknown future schema cannot be claimed interpretable; preserving raw bytes alone does not prove safe re-export. No hardlink/live alias. Capacity includes original raw records, distinct owned media, staging, rollback and safety margin; exact quota/margin remains a product decision. Insufficient capacity preserves source and existing data. Live card/session/image deletion must not collect sealed bytes; no automatic seal deletion or Task rescheduling/network. P13 takeover needs separately versioned transactional read/verify/migrate, retained old seal, idempotent restart, and explicit conflict policy.

New synthetic preflight tests cover all three image edges, exact whitespace-preserving raw JSON, separate physical copies surviving explicit original deletion, insufficient capacity before copy and broken post/media relations. This is NOT a production GC/pending-deletion/snapshot/backup adapter. Existing G0/G0R evidence supplies the known omission/deletion hazards; no redundant repetition is claimed.

| Required future test | Expected fail-safe outcome | Current status |
|---|---|---|
| Schema unknown, duplicate IDs, link missing | No partially successful seal | Graph owner plus new scoped synthetic negatives; full future schema unsupported |
| Live owner deleted, full backup/snapshot/root switch | Independent seal retained and re-exportable | NEEDS_ADAPTER |
| Crash during raw/media preparation or cutover | No activated Task; old seal/source retained; discoverable owned incomplete work | NOT TESTED in production |
| P13 takeover interrupted/rollback | Old raw domain remains reconstructable, no scheduler until full commit | NOT AUTHORIZED |
| Profile/clone/Portable/old binary | No unknown protector fallback or unsafe downgrade write | NOT TESTED / RELEASE BLOCKER |

First future production slice should be a separately authorized *unwired* fresh-target preparer only after topology, authority ownership, capacity, Community and old-writer policy decisions. G2C/Startup/SecretStore wiring remains forbidden in LR2.

## Concrete candidate fields and cutover to review (DRAFT, NOT REGISTERED)

The following field names are a reviewable proposal, not an approved schema, path layout or implementation:

| Candidate record | Required typed fields / validation |
|---|---|
| SealHeader | `sealVersion: positive Int`, `sourceAppCode`, `envelopeVersion`, `sourceScope`, `sourceArchiveLength: Long`, private `sourceArchiveSha256`, `interpretationStatus` with UNKNOWN distinct from VERIFIED |
| RawRecord | `entityFamily` limited to post/task, exact `originalId`, `sourcePath`, `rawLength`, `rawSha256`, private raw-byte blob reference; duplicate ID/path refuses, bytes never reserialized |
| Relation | typed `fromRecord`, `relationKind` (postId/card/session/sender), `toIdentity`, `status` (VERIFIED/UNKNOWN_SELECTION/MISSING/CONFLICT); missing cannot silently become unselected |
| OwnedResource | `sourceReference`, `originalLength`, `sha256`, `copyIdentity`, `ownerSeal`, explicit independent-copy proof; one record for each distinct resource with all three image-edge uses retained |
| Preparation | `operationIdentity`, source/target native witness, expected root generation, required/reserved capacity, participant inventory and verified stage status; cannot be a boolean-only success declaration |
| Participation | snapshot/full-backup/root-switch/Portable format version and verified inclusion receipt; unknown adapters block publishing a supposedly portable seal |

No secret values belong in these records. Source-origin names/hashes and raw content are private. A source containing credentials is not automatically duplicated as a public or unprotected seal container. Lifetime/encryption of locally retained source material needs the G1B/R07 decision.

Proposed capacity formula is checked arithmetic over exact raw bytes + distinct media copy bytes + manifests + staging/rollback allowance + approved reserve; unknown source totals, overflow or insufficient free/reserved capacity refuse before copy. The actual reserve policy and protection from concurrent disk consumption require approval and runtime proof, not a guessed constant.

Proposed cutover sequence: SOURCE_AUTHENTICATED → GRAPH_VERIFIED → CAPACITY_RESERVED → COPIES_VERIFIED → PARTICIPANTS_PREPARED → AUTHORITY_PUBLISHED → REOPEN_VERIFIED. Until final publication, only private nonbusiness staging exists. A failure retains source/old authoritative root and blocks activation; an ambiguous authority publication requires restart reconciliation, not success or automatic cleanup. Every transition needs a durable owner and rollback/forward proof before production approval. Publishing while backup/GC/root-switch adapters are absent is forbidden. P13 may acquire ownership only through a separately versioned takeover transaction, with no Task execution merely because sealed Task bytes exist.

These proposed records would introduce a new persistence/ownership contract and participant receipts. LR2 creates none of them in production; the synthetic copy probe is deliberately insufficient evidence for durability, global GC closure or Windows power-loss behavior.
