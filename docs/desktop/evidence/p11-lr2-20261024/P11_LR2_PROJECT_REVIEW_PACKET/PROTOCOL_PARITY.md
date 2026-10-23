# Protocol and admission boundaries

Authority:182; baseline1.4.1 is unchanged. This is a test-classpath engineering harness, not a new application decoder or restore entry point.

| Layer | Reused authority / actual check | Limit |
|---|---|---|
| Envelope | Existing AppBackupCodec.decryptingForInspection; encrypted EOF authentication | Password only masked Console; plain integrity is not authenticity |
| ZIP | New bounded ZIP32 forward reader compares local and central name, offset, CRC, size, flags and method; EOCD and actual EOF | ZIP64, split disks, uncommon flags, stored descriptors and reordered central directory unsupported; no extraction |
| Manifest/preferences/inventory | Existing strict transport DTOs and duplicate-key/UTF-8 lexer, exact file lengths and SHA, exact Entity counts | code84 rejects modern scope/retainedPreferences keys; code85/86 are distinct newer transport inputs, not baseline promotion |
| Entities | Existing registered strict serializers, plus selected Moments snapshots | Serializer acceptance is not complete official-version semantic parity. Unsupported records remain counted and block full admission |
| Scope | Known options, character/session and selected-media counts, explicit choices | Aggregate-only choice omission is UNKNOWN, not an invented per-session selection; no claim of full dependency auto-inclusion proof |
| Resources | Registered portable references, missing/opaque references and basic owner IDs; S1 remains separate | All-domain graph closure unavailable; known credentials continue to block S1 admission. No fake credential-free call |
| Nested v8 | Shared SaveSlot/ChatMessage/VectorChunk/GeneratedVoiceMessage serializers, bounded inner central ZIP and claims | imageCount is attachment/background reference count, not ZIP entry count. Resource IDs are basename without extension. Complete Memory/RAG semantics are not proved by this pass |
| Logical names | Official source namespace may contain internal colon characters | This harness never materializes these names; Windows materialization requires a separate adapter. First-component drive syntax/traversal/backslash still refuse; S0 is unchanged |
| Restore | None in full-backup tool | Always SEMANTICALLY_BLOCKED and restoreSupported=false; no Prepared, census trust or scheduleRestore handoff |

Independent code84/v1 fixtures use the previously reviewed official145 wire models rather than the Inspector's output DTO. Modern test transport is pinned official147; source identities are indexed. Strict source authentication and partial schema evidence do not establish full portability or safe protected credential import.

Separate SaveSlot process harness deliberately writes only a new task-owned isolated root through the existing service. Message comparison excludes only resource relocation/session rebind; session checks exclude derived Memory caches and apply existing monotonic next-order rules. RAG IDs/session metadata are rebound. Current Memory comparison normalizes node IDs and RUNNING→PAUSED, excludes recomputed sourceHash/coverageHash, and refuses ambiguous canonical node identity. Voice comparison excludes regenerated ID, anchor, decoded duration and updated time. These exclusions must remain visible; neither derived hash formulas nor complete voice-anchor equivalence are claimed.

No serialized Provider request, official Prompt, Package/Entity schema, production Gradle or application runtime changed. Request parity is unchanged by source identity, not newly accepted through these tools. Android physical round trip, genuine145 whole-app direction, complete unknown-domain and credential transactions remain separate gates.
