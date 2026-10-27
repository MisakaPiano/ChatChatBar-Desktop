# Dormant voice and C-A ownership candidate

**PROPOSED / NOT USER APPROVED PERSISTED SCHEMA / NO PRODUCTION SEAL CREATED.**
D-045 preservation direction is approved. LR4's preparation and LR5's fake
participants remain scoped experiments; LR6 designs the missing owner/adapters.

## Three distinct custodians

1. Original user source archive remains theirs, unmodified; R07-A does not grant
   automatic custody of the entire `.cbbackup`. No LR6 real archive read.
2. Proposed validated dormant copy owns exact raw records and independent physical
   media. It is neither a live Entity nor a scheduler input.
3. Future P13 formal Moment entities/runtime have no activation or migration
   permission here. A Task's stored status/date is archival data, never a command.

Candidate location inside a future broker-owned fresh root:
`preserved/v1/<sealId>/records/`, `media/`, `manifest.json`, `ownership.receipt`.
These paths are proposals only. Do not create them under current production roots
or rely on a hidden directory to evade backup/GC. Existing FullBackup policy can
reject unknown domains/hidden bytes; no adapter exists merely because files fit
under a selected root.

## Exact candidate manifest and record/edge rows

Top-level manifest required fields: `schema`, `version`, `sealId`, `rootBinding`,
`sourceVersion`, `scopeReceiptMac`, `sourceValidationReceiptMac`, `records`,
`edges`, `retainedDependencies`, `quota`, `ownerEpoch`, `activation`, `manifestMac`.
`schema=CCB_DORMANT_PRESERVATION`, `version=1`, `activation=NEVER_AUTOMATIC`.
Strict duplicate/unknown field/type/version rejection follows the authority
contract; original raw JSON is stored separately without decode/reencode.

Record row fields: `recordId`, `recordKind`, `sourceLocation`, `rawRef`,
`rawLength`, `rawSha256`, `sourceSchema`, `sourceId`, `parentSessionId`,
`characterCardId`, `senderId`, `messageId`, `messageVersionId`, `postId`,
`taskId`, `dependencyStatus`, `ownerBinding`, `validationStatus`.
Nullable source fields remain explicitly nullable; missing/null/empty distinctions
are recorded in separate raw-field-state evidence, never default-filled into IDs.
Raw source IDs remain scoped by source archive + entity family, not globally
deduplicated by filename. Public reports disclose only counts/categories.

Edge row fields: `edgeId`, `recordId`, `fieldPath`, `originalReference`,
`referenceState`, `mediaRef`, `mediaLength`, `mediaSha256`, `nativeOwnerBinding`,
`sourceResourceReceiptMac`, `ownershipReceiptMac`.
`fieldPath` independently identifies:

- `senderAvatar`
- `imagePath`
- `generatedImageMetadata.imagePath`
- historical voice audio reference with its exact original message/version anchor.

Three Moment paths are separate even if their source strings/bytes coincide.
Each owned physical copy has its own native identity; no hardlink/symlink/alias to
a live image or another root. SHA equality is integrity evidence, not ownership.
Original paths/JSON remain in private raw custody; only reviewed export projection
may rewrite recognized resource fields, with the original retained unchanged.

`retainedDependencies` preserves raw identity-bearing card/session/sender records
needed to interpret Post IDs, Task IDs and Task.postId. Duplicate IDs, conflicting
owners, unknown associations, missing required references, outside paths, unknown
media/schema, corrupted bytes or unknown versions block the **whole restore**.
Official version/scope-qualified historical missing associations and intentional
media omission remain distinct statuses; the seal must not invent extra exceptions.
Supported URL references retain the known original semantics without fetching;
unknown external content cannot become an allegedly complete owned media copy.

Historical voice preserves the nonempty unmatched version ID, original enclosing
message/version structures, original audio bytes/length/hash and all required
references. Never rebind to the current version, prune records, regenerate audio,
repair text, autoplay, enqueue TTS, or infer why official history was pruned.
LR3's first rejected nested-package counts are not a complete source inventory.

## Capacity and preparation

Quota exact candidate fields: `limitBytes`, `requiredPeakBytes`, `reservedBytes`,
`volumeBinding`, `reservationReceiptMac`, `perRecordLimit`, `perMediaLimit`,
`maxRecords`, `maxEdges`. Numerical limits must be explicitly approved before
production; no inherited unlimited value or free-space-only “reservation”.
Checked arithmetic counts every independent physical copy, OLD escrow, staging,
publication copies, largest temporary unit, full raw manifests and receipts.
Candidate margin is `max(64 MiB, ceil(peak/20))`; this five-percent proposal is not
an approved guarantee. Per-volume reservation must cover the entire peak plus
margin, with source ZIP compression/sparsity not treated as final disk size.
Overflow, capacity unknown, quota exceeded or lost allocation refuses preparation;
failure never deletes existing data to make room.

Prepare uses owned CREATE_NEW files, bounded streaming copy, cancellation and
progress, and pinned roots. Verify every length/hash/source edge independently;
seal manifest/receipt only after complete verification and native authority proof.
Cancellation/fault retains prior authoritative generation and source; partially
prepared bytes remain inert task-owned recovery evidence, not garbage to sweep.
No partial seal can authorize restore. Durable publication inherits all unproved
Windows gates in the authority contract, rather than pretending a JSON hash is MAC.

## Required lifecycle adapters (none implemented here)

| Owner | Required integration and failure behavior |
|---|---|
| Live image GC / actual pending-deletion chain | Dormant copies physically outside live deletion candidates; typed ownership validator refuses cross-owner candidates. Unknown/corrupt seal stops relevant deletion, never means no references. Do not protect only by one live Entity string. |
| Card/session/sender deletion | Keep archival dependency records and copies; deleting live originals does not transfer ownership or delete sealed data. Explicitly test live deletion and real pending-deletion execution. |
| FullBackup / official reexport | Complete manifest/raw/media inventory, lengths/hash/CRC and source/scope semantics; preserve original bytes. A separate reviewed projection may retarget only known official resource fields. Compare all retained fields and every edge; unknown projection semantics blocks whole export/import, not partial success. |
| Snapshot / automatic backup | Explicit immutable seal participant and integrity receipts, with restore/cancel/capacity tests. Unknown reserved-directory exclusion cannot silently omit seal. |
| Root switch / Portable / cloning | Copy owned bytes with complete native rebinding under approved authority; OLD copy retained. Missing protector/source/offline volume is unsupported/blocked, not a portable credential promise. |
| Upgrade / downgrade | Schema reader/writer admission before any live loader; enforceable old writer fence required. Old EXE may not mistake raw Task files for runnable tasks. |
| Crash / rollback | Seal included in complete OLD/NEW participant receipt, no independent “successful” media commit. Receipt loss/foreign bytes retains scene and blocks business. |
| P13 takeover | Reviewed version-aware read, independent staging and byte/graph proof, explicit owner-epoch CAS and receipt, no duplicate active ownership. Rollback preserves dormant raw copy; activation/scheduling requires separate product permission. |
| Deletion/retention | No automatic expiry, dedupe, GC reclamation or source deletion. A future explicit retention/erasure policy must address privacy and recovery conflicts. |

Required negative matrix: each missing/corrupt one of the three images; identical
source referenced through three paths; Task.postId absent/conflicting; deleted
card/session/sender; duplicate IDs; raw whitespace/unknown fields preserved but
uninterpretable; unknown future schema; hardlink/reparse/external escape; source
mutation; quota overflow; cancellation per copy and before/after seal publication;
pending-deletion replay; restore interruption; clone/root switch; old writer;
failed P13 transfer and repeated takeover. All are future adapter tests, not LR6
passes. If byte/reference/reexport preservation cannot be demonstrated, keep the
full archive restore BLOCKED, as D-045 requires.
