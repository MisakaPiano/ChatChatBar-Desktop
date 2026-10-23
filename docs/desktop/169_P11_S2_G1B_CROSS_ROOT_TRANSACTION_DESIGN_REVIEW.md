# 169 — P11-S2-G1B-R1 Cross-Root Recovery Design Review

**2026-10-23 — DESIGN CANDIDATE / PROJECT REVIEW REQUIRED / NO IMPLEMENTATION AUTHORITY.**

Review checkpoint: `feature/phase8-fish-audio @ 99cc75a7650afe66e63e51e6242301d89b1be44d`; remote desktop `5ef95428421f7c693b010ac88f51d470804ab07a`. Local tracked tree was clean; six known untracked attachment/handoff/cache files were preserved unread/unhashed/unstaged. App task inventory showed only this active task at this cwd; no unknown parallel local changes were observed. Both169/170 were absent before creation. No reset, branch switch, upstream fetch or runtime operation occurred.

Authority: Source Map, baseline JSON, Current State, [168 acceptance](168_P11_S2_G2A_TYPED_CREDENTIAL_AND_INERT_INTENT_PROJECT_ACCEPTANCE.md), [163 candidate](163_P11_S2_G1B_UNIFIED_DATA_SECRET_TRANSACTION_DESIGN_GATE.md), [162 observations](162_P11_S2_G1A_CREDENTIAL_TRANSACTION_PROJECT_ACCEPTANCE.md), [158 R07](158_P11_S2_G1_R07_DIRECT_IMPORT_PASSWORD_AND_CREDENTIAL_POLICY.md), [160 blueprint](160_P11_S2_G1_INTEGRATED_PRESERVATION_AND_CREDENTIAL_SAFETY_BLUEPRINT.md), [95 portability gate](95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md). Existing acceptances remain scoped; this review does not accept G1B. Formal baseline remains1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`. Official1.4.5 @ `1198a13cf3008de1358b8a69aa5e40b64797c8d9` is fixed backup-format authority only, with version defaults in **app/app/build.gradle.kts**. No new upstream compatibility claim.

## 1. Static source evidence

All paths below are relative to `app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/` at the review checkpoint. These are source observations, not newly executed tests. Immutable source root: [99cc75a7 Desktop source](https://github.com/MisakaPiano/ChatChatBar-Desktop/tree/99cc75a7650afe66e63e51e6242301d89b1be44d/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop).

| Source / entry point | Relevant fact and missing guarantee |
|---|---|
| security/WindowsSecretRootResolver.kt, resolve | Uses LOCALAPPDATA/ChatChatBarDesktopCredentials; selected data root only checks non-overlap. Different selected roots under the same profile resolve to the same secret directory. Path spelling is not proof of Windows account/protector identity. |
| security/DesktopSecretStore.kt, DesktopCredentialKey/canonicalValue | Global NovelAI/Fish/SiliconFlow, model ID, legacy embedding ID and singleton embedding namespaces. No data-root ID/provider endpoint in the key, no Community sink, batch or CAS API. |
| security/WindowsSecretStore.kt, load/save/delete/AtomicSecretBlobWriter | Per-key protected envelope. Temporary file + atomic move with a non-atomic fallback when unsupported; no batch intent or global owner protocol. Writer does not establish a flushed-directory/power-loss guarantee. |
| DesktopDataRootOwnership.kt, acquire | Held FileChannel/FileLock for one data root, target/path checks and process-local ownership bookkeeping. Does not lock the independent shared SecretStore or all consumer roots. |
| DesktopFullBackupCommitJournal.kt, schedule/recover/apply/rollback | Registered root-relative data rows, old/next evidence, scheduled/started/committed markers; writeNew calls force(true), installation uses atomic move. No external secret participant. File flush and individual rename do not prove directory persistence or cross-volume atomicity. |
| DesktopStartupGate.kt, run/recoverIndex | Owned selected root, full-backup then index/session recovery before business callback. No profile-wide pending-intent discovery or shared-secret recovery before other roots hydrate. |
| DesktopFullBackupService.kt, inspect/scheduleRestore | Instance Mutex plus data coordination/audio/scheduler barriers; root journal only, no credential participant. Instance mutex is not cross-process profile exclusion. |
| backup/DesktopFullBackupArchive.kt inspect; DesktopBackupPolicy.kt entity | Current incoming credential refusal and strict registered-store admission remain. Cannot obtain a credential-bearing Prepared by treating successful source authentication as restore authorization. |
| backup/DesktopBackupReadOnlyInspector.kt scan | Consumes authentication to EOF, then ZIP/inventory/schema checks; afterAuthentication hook occurs **before** complete archive validation. Public result is categories/counts/digests, may identify uninterpreted content; restoreSupported=false. It is not a complete typed credential inventory. |
| backup/DesktopCredentialImportPlan.kt classify | Exact scoped identity and conflict model, source-digest binding, no values/IO; relies on supplied metadata and target relation. ABSENT/EMPTY are not a future deletion/preservation policy. |
| backup/RecoverableIntentContract.kt check | UUIDs, generations, sourceFullyVerified/ownership/intentEvidenceProven and observed blob digests are supplied assertions; no native identity, locking, provenance constructor or durable journal proves them. authorizesBusiness=false always. |

The full-backup Skill was read and remains accurate: its G2A entry explicitly calls the contracts unwired and the test decoder a subset. No Skill edit is needed for this docs-only proposal; no other listed skill governs a newly changed runtime area.

## 2. Shared-profile versus per-root namespace

| Concern | A: profile-shared store + global coordinator | B: per-data-root credential namespace + controlled legacy transfer |
|---|---|---|
| Compatibility | Keeps existing logical key addressing; but replacing one model/global key can change another root's account binding. A lock alone cannot repair this semantic conflict. | Separates equal IDs/global settings across roots; requires new addressing, migration and explicit user choice for legacy shared keys. Neither format nor schema is approved. |
| Concurrent roots/processes | Every read/hydration/write consumer must join one generation/lease protocol, including already-running roots. Unknown/legacy consumers invalidate the guarantee. | Same-root exclusion plus profile migration coordination still needed; unrelated roots can run after proven isolation. Root clones need distinct identities or explicit rebind, not accidental shared namespace. |
| Offline disk/root | Shared key updates may affect an unmounted root whose references cannot be checked. Must reject potentially affected shared-key replacement unless a safe consumer inventory/ownership policy is proved. | Offline root's isolated keys need not be overwritten; orphan retention and identity registry/lifetime need design. Never delete unknown keys because a disk is absent. |
| Root switch | Same store persists, but pending recovery must be resolved before switching or letting another root hydrate. | Switch selects namespace only after ownership/identity validation; old keys remain until migration acceptance, not move-and-delete. |
| Portable/profile | Data path portability does not carry profile DPAPI decryptability. Shared pending intent cannot depend solely on removable media. | Namespace isolation is not DPAPI portability; transfer/re-protection needs explicit account authorization, conflict UI and rollback. |
| Old versions | Old binaries use legacy keys and bypass the new coordinator; native lock on a new marker does not constrain them. | Old binaries still address old shared keys and may write new data roots; namespace change alone does not prevent unsafe downgrade. |
| Cost / failure radius | Smaller initial key API change, substantially harder all-consumer closure and cross-root data restoration. | Higher migration cost/new durable identity decisions; smaller steady-state overwrite radius, but requires full migration proof. |

**Conditional recommendation:** keep A as the compatibility reference for investigation, but do not approve live shared-key replacement merely by adding a global lock. Study B as the long-term preferred isolation direction because dormant/unavailable roots make shared account replacement hard to prove safe. Before selecting B, Project/user must approve namespace identity, legacy retention/consent, cloning and Portable semantics and verify downgrade protection. Until those conditions close, neither scheme is production-approved. The next read-only slice can remain topology-neutral and expose unknown consumer/context relationships rather than inventing a registry.

## 3. Candidate unified protocol and evidence

This is a logical recoverability proposal. Multiple atomic moves never form a cross-domain atomic commit. No new persistent schema, authority filename, root marker or coordinator API is selected here.

Identity must bind operation ID; source archive digest/length/version/scope; exact source Entity records and credential locations; native data-root/volume identity plus generation; actual Windows principal/protector context and secret-store identity; each logical key plus provider/endpoint/config binding; protected old/new blob identities. Paths, UUIDs or user-supplied booleans alone are insufficient. No plaintext key digest belongs in public metadata.

| Phase | Required future evidence | Current capability / safety rule |
|---|---|---|
| SOURCE_VERIFIED | Complete authentication, ZIP/CRC/inventory/hash/version/schema and complete credential-source census, dependency interpretation; immutable source binding | S0 supplies bounded authentication/integrity facts, not complete credential decoding or general portability. G2A accepts declarations. A scan with unknown content cannot claim complete source inventory. |
| PREPARED | Revalidate target generation/identity under exclusive protocol; protected old/new reversible evidence for every declared participant; authorized absence/tombstones; protected staging ownership and capacity reserve | Nothing currently prepares a unified transaction. Reject unavailable context, ambiguous absence, incomplete mappings or missing rollback material before any live write. Source password does not authorize overwriting another account. |
| INTENT_DURABLE | Single authoritative, tamper-detectable owned intent binds participants/decision/generations/evidence; verified durable publication and startup discovery | G2A's flag is not evidence. Secondary root references cannot independently authorize conflicting outcomes. No live mutation until discovery and publication proof complete. |
| APPLYING | Reproof/CAS of every expected old or already-installed exact new state; retain old/new protected artifacts; write progress recoverably | Sequential writes may leave mixed bytes. Block all affected business consumers, do not publish success, do not overwrite foreign changes. |
| RECOVERING | Reacquire same context/ownership and validate authority/progress/evidence, choose one allowed outcome; verify every participant and generation | Runs before repositories/providers/schedulers/hydration in every affected process. Missing/unmounted domain means wait/blocked, not “restore remaining domains.” |
| COMPLETE | Uniform reverified outcome, dependency/account bindings, durable decision/receipt; startup cannot revive a conflicting incomplete intent | ALL_OLD means rollback completed, **not import success**. ALL_NEW means candidate installed only within proven scope. Current pure check never authorizes business. |

### Locks, ownership and startup discovery

Proposed future order for all cooperating processes: profile/context-wide coordinator lock → affected data-root native locks in stable identity order → per-key locks in stable canonical-key order. Release in reverse. A running process already holding a root must not wait for the coordinator while a coordinator waits for that root: use nonblocking acquisition/refusal and explicit quiescence/restart handoff. No forced lock stealing, timestamp-based ownership or termination of unknown processes. Every legacy writer that bypasses this protocol remains a release blocker; kernel locks only help when all relevant actors honor them.

A global coordinator should hold a participant-specific exclusion/fencing generation until complete proof, not simply until schedule returns. Existing process-local Mutex and isHeld cannot stand in for this. Native identity reproof must cover symlink/reparse and path replacement, and context/profile changes require a new authorization/proof, not reinterpretation of old ciphertext.

Candidate authority placement:

- Profile-owned protected coordinator workspace: discoverable before any root hydrates, available when source/removable disk disappears; needs ACL/protector identity, shared consumer closure and protection against untrusted/old binaries. Prefer for option A experiments, **not an approved storage location**.
- Data-root-only intent: travels with data but is undiscoverable to other roots when offline; insufficient as sole authority for shared key changes.
- Replicated root/secret manifests: discoverability improves but split-brain requires one primary decision authority, monotonic generation and reconciliation rules. Two independent “committed” markers are not a solution.

Startup must query authoritative pending operations for the selected root **and any shared keys it consumes** before business construction. A missing pointer/registry entry must not be interpreted as no pending intent without proven registry integrity. Old binary discovery cannot be patched retrospectively by a new StartupGate; physical separation, access restrictions or another experimentally validated upgrade barrier require a separate design/user decision. Do not claim a new marker alone prevents downgrade.

### Protected evidence and outcome conditions

Old ciphertext must be copied into independently owned, access-controlled rollback storage before replacement; new protected ciphertext must be prepared for the actual destination context without ordinary JSON/plaintext staging. Bind each blob to logical participant, context, length/hash and verified availability; hash alone is not authenticity/ownership. Key absence is an authenticated observation/tombstone, not a missing file silently inferred after IO failure. Never log values or retain raw key buffers in summaries; volatile lifetimes and zeroing limitations (Strings/native buffers) need explicit review. Original source remains user-owned under R07, not an automatic recovery vault.

Within a future **proved trusted adapter/threat model**, necessary and jointly sufficient conditions to *declare* ALL_OLD/ALL_NEW are: valid unique authority/decision; closed complete participant set and identities; exclusive ownership/fencing and no unaccounted consumers; every actual data row/secret blob/absence equals the chosen generation's exact expected evidence; restored account/config/resource relationships; required context can consume the protected material; durable receipt and pending-discovery semantics cannot disagree. These are logical acceptance criteria, **not a proof that the current implementation meets them**. Missing any item blocks the declaration. Roll forward additionally requires an authorized complete new set; rollback requires an intact complete old set. No arbitrary choosing whichever files remain.

If live bytes are neither expected OLD nor exact authorized NEW, or authority/evidence is lost/corrupt, stop before business activation, retain all evidence and user data, and report a fixed redacted failure category. Do not guess rollback, clear a key, reconstruct secrets from logs, or sweep scratch. If one root is offline, quarantine the operation and every conflicting key consumer until recoverable; unaffected-operation admission needs a separately proved dependency partition.

`force(true)` on a file, a successful rename, a returned save and a well-formed digest are distinct facts. Parent directory flush/rename ordering, cross-volume behavior, non-atomic fallback, filesystem/controller caches, sudden power loss and Windows restart durability are **not proved**. Future tests must define supported filesystem/platform bounds, refusal on unsupported publication, and cut points before/after every publication and receipt. No hardware-level atomicity claim is proposed.

## 4. Failure and validation matrix

Existing observations refer to162/168, not new runs in this review. “Synthetic next” means candidate tests requiring a separately authorized task. W=Windows isolated machine/profile validation; A=official Android isolated interop; all unresolved live safety rows remain release blockers.

| Case | Existing evidence | Synthetic next / platform requirement / blocker |
|---|---|---|
| KEY_FIRST | G1A observed DATA_OLD/KEY_NEW; G2A rejects model completion | Cross-process unified protocol cut points; W persisted restart; unresolved |
| DATA_FIRST | G1A DATA_NEW/KEY_OLD | Same, prove no hydration before recovery; unresolved |
| SECOND_KEY_FAIL | G1A A_NEW/B_OLD | Multi-key reverse/forward recovery and failing recovery writer; W; unresolved |
| Cancel | G1A injected cancellation; G2A unknown outcome rejected | Cancellation at each boundary + drain; real process termination distinct; unresolved |
| Post-publication ambiguous | G1A delegated-writer then injected throw; not native rename failure | Readback/reproof, no blind retry/duplicate commit; native W fault investigation; unresolved |
| Hard process exit | Existing data/Inspector child-JVM evidence only, not unified secret recovery | Children at every intent/data/key/receipt cut; W fake-protector then separately authorized DPAPI; unresolved |
| Windows restart/power loss | NOT TESTED for unified protocol | VM/physical restart, filesystem/power-loss bounds and directory durability; W; unresolved |
| Space exhausted | No unified reserve proof | Partial staging/publication/disk-full/recovery reserve; W, never delete valid old data; unresolved |
| SecretStore unavailable | Existing failure categories, no unified recovery | Permission/volume/protector failures; W, not empty-state inference; unresolved |
| DPAPI profile changes | NOT TESTED | Fake context rejection first; W separate accounts/profiles with authorization; no silent reprotection; unresolved |
| Concurrent roots | Resolver/key namespace exposes shared domain; no global protocol proof | Two-root/two-process CAS, idle consumers, lock inversion, external writer; W; unresolved |
| Portable | No complete credential portability | Clone/root switch/profile mismatch/removable media; W, A for actual end-to-end migration; unresolved |
| Source disk disconnect | No unified protocol proof | Before full read reject; after prepare require independent complete owned recovery evidence, not source reopening; W removable disk; unresolved |
| Target/offline root disconnect | Shared scope unresolved | Do not mutate shared keys unless closed consumers can be recovered; W; unresolved |
| Old binary opens new root | No enforced barrier | Separately authorized disposable-root old-binary test and enforceable design, no installer this task; W; unresolved |
| CommunitySession lacks sink | Explicit G2A UNMAPPED | Synthetic inventory must retain category/count, no API-key substitution; session lifecycle/protected sink + A/W interop require later scope; unresolved |
| Corrupt/lost evidence or foreign target modification | Existing data journal refusal, not unified guarantee | Tamper/auth/identity/path replacement/unknown family/CAS loss cases; W reparse+IO boundaries; unresolved |

## 5. Recommended next boundary and required decisions

[170](170_P11_S2_G2B_BOUNDED_NEXT_SLICE_DRAFT_NOT_AUTHORIZED.md) proposes a read-only, unwired source census/strict typed extraction and injected target-observation adapter, using only synthetic inputs. It can close G2A's trusted-metadata gap **within an explicitly covered official wire subset** without choosing a persistent intent topology. It cannot prove global target completeness, atomic recovery or protected account usability. The existing S0 afterAuthentication hook is too early for this handoff; do not secretly repurpose it. Any required change to existing Inspector interfaces must be explicitly approved in the eventual executable contract.

Before persistent/live work, Project/user must resolve: A versus B ownership/migration/retention; all-consumer coordination including offline/legacy roots; authoritative intent storage/discovery/integrity; protected context identity/DPAPI scope; key absence/replacement/conflict consent; Community lifecycle; filesystem/durability/capacity contracts; enforceable downgrade barrier. No default schema or retention numbers are adopted here.

No Gradle, GUI, Android, real backup/key/root or DPAPI was used. Original evidence remains untouched. P11/D-038/Post-P8/Public Preview HOLD and all parity ratings remain unchanged. **Stop: P11-S2-G1B-R1 PROJECT DESIGN REVIEW GATE.**
