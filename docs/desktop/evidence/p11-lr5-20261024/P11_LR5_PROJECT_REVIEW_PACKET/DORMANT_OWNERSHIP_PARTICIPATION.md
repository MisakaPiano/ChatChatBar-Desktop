# Dormant voice / C-A ownership participation

D-045 approved targets remain exact raw historic voice plus audio, and raw MomentPost/Task plus senderAvatar, imagePath, generatedImageMetadata.imagePath as three independent edges. Preserve bytes/unknown fields and source version; never rebind historical explicit version or activate RUNNING Task. LR4 strict candidates cannot decode arbitrary future schemas; unsupported source/reference means whole-import BLOCKED, not a ready participant.

Proposed ownership: independent physical sealed copies with private provenance hashes/length, bounded quota with OLD+NEW+temporary/rollback reservation, immutable ownership manifest and generation, no live deletion permission. Live source deletion must not remove sealed media. The sealed record and media form one transaction participant graph. Re-export must preserve semantic source format and byte custody; no claim that a raw copy alone is official-import-compatible.

| Participant / lifecycle | Current gap / proposed requirement |
|---|---|
| prepare/crash/cancel | all raw/media checked before publish; tentative copies retained; no partial seal success; LR5 uses dummy participant bytes only |
| full backup | registered domain + inventory/strict reference adapter needed; current unknown roots refuse; ordinary exports must not expose credentials |
| snapshot/automatic backup | scope/validator/materializer/rollback and quota rules required; tree walk inclusion alone insufficient |
| GC/pending deletion | separate owned namespace and explicit non-live ownership; old GC/Android pending-deletion not changed/tested |
| root switch/Portable | entire manifest/raw/media copies with independent identity and verification; protector not implicitly transferable |
| overwrite/upgrade/downgrade | persistent schema version, incompatible old binary fencing, retention/rollback authority required |
| P13 takeover | explicit versioned all-or-nothing import/ack; original immutable seal retained; tasks remain inert until separately authorized activation |

Experiment will include opaque synthetic dormant raw/media participants only to verify journal completeness/byte retention. It does not repeat LR4 official wire validation or implement seal, codecs, playback, P13, backup/GC adapters. Any missing participant blocks complete NEW. Actual resource policy/schema remains not authorized.
