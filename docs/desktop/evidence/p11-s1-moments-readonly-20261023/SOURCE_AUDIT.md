# Fixed source audit — S1, 2026-10-23 JST

Start: feature `3ec2a3239a296713b79303aff7e1aa41928d8e53`, verified against origin, fast-forward from previous R1 only. Protected untracked files and old experiment cache were retained, unread/unhashed/unstaged. No official upstream fetch or baseline update.

Read CURRENT 147 and implementation contract 148; relevant 00/10/21, 13/14, 93/95, S0/R1 handoffs, production-contract slices/storage/task drafts and backup/Moments/SaveSlot skills. No runtime behavior from Android is authorized by these references.

Fixed official references: 1.4.5 `1198a13cf3008de1358b8a69aa5e40b64797c8d9`; 1.4.6 `9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d`; 1.4.7 `b4fa75d296680f777be095f3121ad0c4db979f17`. Exact per-file Git blob identities and absent paths are in `raw/upstream-blobs.json`.

- MomentEntities declarations are identical across the fixed versions (empty source diffs). New extraction DTOs reproduce their serialized fields/defaults, with private package-specific names and no factories/repository registration. GeneratedImageMetadata uses the existing shared strict serializer; its pinned upstream declaration has no drift between the selected versions. No formal Entity is modified.
- BackupHistoricalResourcePolicy is absent in 1.4.5, present in 1.4.6/1.4.7. Only top-level moment_posts.senderAvatar receives MISSING_AT_SOURCE in codes85/86; code84 stays strict. Body and metadata edges never receive this exception.
- BackupArchive uses `chatbar-backup-file:` tokens and selected files/inventory. S1 resolves tokens only against the authenticated inventory, never through a local File/URI/network reader. Unsupported external references are UNINTERPRETED and block graph verification.
- BackupExportSelection projects top-level imagePath and generatedImageMetadata to null when momentsImages=false; senderAvatar remains independently selected. Scope omission uses S0's existing default true. Moments selection does not imply selected card/session dependencies.
- S0 transport/authentication/ZIP/CRC/inventory/hash/version/scope logic is reused through a private final validation callback. Normal inspect semantics and all restore components remain unchanged. Only inspectMoments exposes the separate sanitized S1 result.

Report identities are source digest + exact inventory record digest/length, in scan order; they are not reserialization digests. Edge reports contain field enum, raw-reference digest, resource digest/length and classified state. Source IDs, raw paths, text, prompts and credentials never leave the validation boundary. Known credentials block S1; arbitrary text is explicitly not certified credential-free.

This slice has no persistence, seal, source archive retention service, runtime Task, recovery publication, GC, root switch or target schema. Those require S2 or later authority. P11/D-038/Preview and baseline remain unchanged.
