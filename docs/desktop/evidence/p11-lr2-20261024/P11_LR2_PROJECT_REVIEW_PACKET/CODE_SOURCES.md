# Source identity and authority

- Start/current production tree: `33789eba37eda8952a806671bcfd76eb95cead1c`.
- Formal baseline141: `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8` (unchanged).
- Selected official145 backup transport: `1198a13cf3008de1358b8a69aa5e40b64797c8d9`.
- Selected official147/code86 transport reference: `b4fa75d296680f777be095f3121ad0c4db979f17`; not the formal baseline or genuine145 real input.
- `raw/authority-source-identities.json`:29 targeted source identities, each Git blob/SHA-256/byte length, from current production and fixed official commits. No upstream sync was performed.
- Each final test round snapshots all new Kotlin, test-init Gradle and runner before execution. Checkpoint source-identities files identify the submitted tool bytes. Existing compiled dependencies stay on the unchanged start tree.

Transport uses existing AppBackupCodec, InspectionManifest/Preferences/Scope, BackupDataRegistry, BackupJsonStream and DesktopBackupPolicy serializers. Test fixtures reuse reviewed independent official145 wire models and pinned147 test Codec from the existing test tree; they do not generate a purported v1 positive from production Inspector DTO defaults.

Nested source semantics come from shared SaveSlotPackageStorage.Reader: imageCount is references, resourceEntries uses basename without extension, and all typed JSONL models are shared serializers. SaveSlot process restore delegates to the existing DesktopSaveSlotService through prior accepted SlotIsolatedRestoreProbe. New comparison logic is an independent test oracle with explicit exclusions, not a new business decoder or restore service.

G1B audit sources include WindowsSecretRootResolver, DesktopSecretStore, WindowsSecretStore callers, DesktopAppContainer, bootstrap authority, migration, StartupGate and current G2A/G2B/T1. Their identities are retained; LR2 does not turn their limited assertions into real protector identity or durable cross-domain authority.

`raw/final-focused` is an intermediate execution whose source was edited while the build was active; it is NOT used as exact tested final source evidence. `final-frozen`, `compatibility-final` and the later diagnostic run have separate frozen snapshots. The final snapshot, not an earlier green count, is authoritative for final tool identity.

Evidence newline preservation was checked before the first commit. Local new-directory attributes disable text normalization for raw evidence and exact tool bytes. After renormalizing only the staged NEW paths, all499 staged files matched working-file bytes exactly. Earlier `checkpoint-final-source` contains implicitly filtered `gitBlob` values:5/14 source entries and261/481 raw entries differ from the byte-preserving commit blobs; their SHA-256/byte lengths were independently rechecked and remain correct. Absolute-path hash calls still applied implicit filters in a later index. Do not use pre-sealed `gitBlob` fields as committed identities. The collector was corrected to explicit `--no-filters`; `checkpoint-sealed/index.json` verified744/744 raw SHA/length/blob identities. Old indexes remain unchanged. The267 files in the second checkpoint were also compared byte-for-byte with the staged Git objects before commit.

Source/evidence commits: initial `eb17445de1e4d67f34164749cc990a8f88d8882c`; final `31a551eb5319e287f872bd1f2657bd8eea1af489`. Final10 Kotlin blobs equal `raw/nested-classification-final/source/src` snapshots. The later collector correction affects evidence hashing only, not tested Kotlin. Final complete raw manifest is `DELIVERY_RAW_MANIFEST.json` in this packet.
