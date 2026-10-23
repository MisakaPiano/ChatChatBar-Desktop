# Census scope and trust matrix

| Boundary | Proved by this slice | Not proved / fail-closed |
|---|---|---|
| Transport | Same invocation consumes existing authenticated codec stream to EOF and complete ZIP/CRC/inventory/manifest/entity/scope checks before result | Authentication alone does not publish; malformed late inventory/manifest tests access no target. Does not authorize restore. |
| Source version | Default official145/code84/v1, exact legacy Manifest keys and Preferences whitelist | v2/code85/86 remains independently S0-readable but census incomplete. `scope:null`/retainedPreferences cannot inherit official145 completeness. |
| Known fields | Preferences NovelAI/Fish/Community; AppSettings SiliconFlow; ModelConfig; legacy and singleton Embedding | Other entities (even recognized by S0), media/packages, unknown fields, opaque nonempty customParams are incomplete. No silent partial “full census.” |
| Field states | MISSING, EXPLICIT_NULL, EMPTY, NONEMPTY remain in records | Nonnullable Entity fields rejected by shared strict DTO need not produce a record. No implied future target delete/preserve contract. |
| Presence mapping | MISSING/NULL→ABSENT; EMPTY→EMPTY; NONEMPTY→NONEMPTY | Original distinction retained, never destroyed by mapping. |
| Record provenance | Whole validated source file SHA-256/byte length plus private record identity and archive SHA/length/version | No secret-value digest, no assertion that an ID/path grants account authority. Native provenance/capability cannot be manufactured from UUIDs. |
| Identity | Exact Model ID/endpoint/template/remote model, distinct legacy/singleton namespace | Invalid identity makes incomplete. G2A name/endpoint/provider/duplicate/conflict regression remains in final run; no account binding by name. |
| Duplicate JSON/IDs | Shared strict transducer rejects duplicate/escaped-equivalent keys; census detects duplicate type+ID and duplicate singletons | No last-wins; input preserved. |
| Community | Known nested source shape/defaults recognized, unknown/malformed nested fields incomplete | No sink; never offered to API-key comparator; G2A UNMAPPED persists. No login/refresh/expiry transfer. |
| Target relation | Injected SAME/DIFFERENT/ABSENT/UNKNOWN, reader errors UNKNOWN, no errors/values in output | No ambient store adapter, real target read or key write. Comparator equality is synthetic, not online account verification. |
| Context/generation | Before/after declared stamp match and known-consumer assertion required for plan | Mismatch/read stamp failure/unknown consumers/changed generation return no plan. No OS identity, fencing, stable-until-commit or cross-process CAS proof. |
| Lifetime | Copied chars wiped on use/close/cancel; scoped comparator revoked, source bytes unchanged, owned temp removed | Strings/JSON/native/JVM-heap wiping and blocked-reader deadline not proved. Incomplete result also requires close via AutoCloseable. |
| Integration | Complete source + acceptable synthetic observation feed unchanged G2A planner | No GUI/Service/Startup/Archive/SecretStore/Journal call site. No Prepared or scheduleRestore conversion, no durable intent. |

Known location coverage is deliberately explicit. A complete result for only the four supported Entity families is not a complete inspection of arbitrary full-app state. Unsupported archives retain their verified raw source; no records are silently dropped to manufacture complete=true. Failure/incomplete is not fallback success.

Full Model/AppSettings positive goldens cover pinned fields and valid defaults; arbitrary custom parameters may encode credentials so nonempty maps remain unsupported, even when syntactically valid official inputs. Source decoding uses current strict schema plus fixed selected145 field admission; this can reject unsupported otherwise-valid official inputs. Such refusal is not whole-app compatibility acceptance.
