# 194 — P11-LR5-A/B Authorized Codex Execution Contract

**USER AUTHORIZED UNDER [193](193_P11_LR5_USER_AUTHORIZATION.md) / CODEX NOT YET STARTED BY DOCS WRITE / P11 PRODUCTION DATA SAFETY STILL HOLD.**

## Mission

Perform one focused autonomous **LR5-A source/authority design audit** followed by **LR5-B genuinely file-backed synthetic recovery experiment**. Stop before any deployable schema/SecretStore/Startup/backup/root-cutover implementation. Optimize for falsifiable durable recovery, **not** extra read-only ZIP counts, overlapping untested source snapshots, or invented data-safety PASS.

### 0. Source, branch, preflight

- Repo `MisakaPiano/ChatChatBar-Desktop`, local Windows checkout `H:\ChatChatBar-Desktop`; only writable branch `feature/phase8-fish-audio`. Review SHA before 193/194 docs was `37c59e01cdbe098838b5ea33056a9673f592d5dd`. **Use live remote HEAD after this task's registration**, check clean local tree and FF-only ancestry. Preserve all old/untracked work, unknown files and prior raw evidence. No branch/worktree creations, force/reset, `git clean -fdx`, broad cache cleanup or writes to `desktop/master/sync/*`.
- Project authority in order: `docs/desktop/00_PROJECT_SOURCE_MAP.md`, `10_UPSTREAM_BASELINE.json`, `21_CURRENT_STATE.md`, official relevant `.agents/skills/*/SKILL.md`, `13_FEATURE_PARITY.md`, `14_UPSTREAM_COMPAT.md`; then `18_DECISIONS.md` D-023–D-026/D-037/D-038/D-045; [191](191_P11_LR4_BOUNDED_PROJECT_ACCEPTANCE.md), [192](192_P11_LR5_DURABLE_AUTHORITY_AND_PRESERVATION_CONTRACT_DRAFT_NOT_AUTHORIZED.md), [193](193_P11_LR5_USER_AUTHORIZATION.md), [194](194_P11_LR5_AUTHORIZED_CODEX_EXECUTION_CONTRACT.md), `93`, `95`, `158`, `163`, `169`, `179`, `190` and **selected real Kotlin call sites** only.
- Formal validated official ChatBar1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`, selected official1.4.5 backup `1198a13cf3008de1358b8a69aa5e40b64797c8d9` **distinct** from private observed code86/v2. Neither general upstream sync nor Prompt edits.
- Existing accepted T0/T1/G2A/G2B/LR1–LR4 and raw evidence are **not** new engineering proof or invitation to re-run whole suites without impacted logic.

### A. Source-backed recovery ownership + falsification design

Audit actual source authority for:
1. `WindowsSecretRootResolver`, `DesktopSecretStore` all NAI/Fish/Model/Embedding/Community consumers and OLD legacy singletons including offline/unregistered third roots, startup/provider factories and old executable paths. Enumerate verified, unproven and hard-to-observe consumers separately.
2. `DesktopDataRootResolution/Ownership/Migration`, `DesktopBootstrapAuthorityTransaction`, `DesktopStartupGate`, root/backup/SaveSlot commit journals, snapshot/GC/Portable and user override order. Draw source-backed acquisition→prepare→publish→restart→reopen→recover data/credential authority sequence, write admission and required lock/CAS ordering; **do not** infer old binary can obey a new marker.
3. Historic voice and C-A Moment sealed-data lifecycle: exact raw version/media identities, independent physical ownership, quota, backup inventory, root switch, eventual P13 takeover. Unknown entity/reference cannot become a ready participant merely because the ZIP passed authentication.
4. Proposed **versioned** root+profile authority schemas: candidate fields/invariants (not an accepted on-disk version!), signed/hashed receipt strategy, native protector context, generation, participant identity/old-new proof, WAL/atomic publication with directory flush limitations, fallback/rollback and fail-closed `UNKNOWN` states. State what cannot be guaranteed by multiple atomic files, synthetic OS identity, or old same-user executable behavior. Distinguish local disk-fault and real Windows crash/power loss.
5. Cases that force **BLOCKED/RESTART_REQUIRED** rather than `ALL_NEW`: missing/corrupt intent, failed Key, stale/foreign CAS, lost receipt, old-executable third-writer, moved root/clone/Portable, offline/unseen legacy consumer, wrong account/endpoint, Community sink absent, unsupported archive entity, low disk, incomplete historical voice/Moment seal.

Deliver `LR5_A_DESIGN_REVIEW` before claiming journal readiness; a proposed contract is not approved production schema. If a source owner is unknown, say so, don't silently fill from prior documents.

### B. Opt-in synthetic file-backed authority experiment

Implement an isolated **test-only** Kotlin/JVM journal/actor with pure fake protector and synthetic participant implementation, under `tools/desktop/interop/lr5/` (or comparably isolated test sources) and **only freshly created task-owned disposable directories**. Do not wire to existing app `src/main`, Startup, Provider, SecretStore, migration, recovery, GC, file picker or installer; do not register a production persisted schema or pick an active `%LOCALAPPDATA%` path.

- Use a truly written **synthetic journal** across separate JVM processes, not only a state enum/memory map; retain OLD and NEW distinct participant bytes and evidence. Explicitly control creation, snapshots, fsync/atomic rename order, authority publication and readback; never equate one atomic rename with cross-domain atomicity.
- Verify source/root/protector/account/endpoint/consumer generation and participant registry on restart; `ALL_NEW` only for a strictly complete and independently readable synthetic set, `ALL_OLD` only a rollback class, not imported-data success. Any uncertainty produces `RECOVERY_BLOCKED/RESTART_REQUIRED`, without hydrating business.
- Fault-inject **actual child JVM halt** (after persisted stage boundaries, including before/after publication and before reopen check), missing/partial receipt, corrupt bytes/checksum, stale writer, unexpected third consumer, mismatched account/protector, deleted/offline participant, partial Key-first/data-first, low capacity/cancel, foreign write and unknown future journal version. Preserve evidence of the old source root/key and report state without inventing success.
- Use **synthetic temporary identities and fake bytes only**. A fake protected store and one-user NTFS test **cannot** prove real DPAPI/SID or old executable fencing. Document actual Windows directory fsync/metadata limitations and whether tested `halt` occurs before, after or during an atomic publication. No claims of power-loss durability without disposable platform evidence.
- Optional injected **dormant voice/Moment participant** can use LR4's nonwired candidates with dummy data to test completeness/retention, but don't make live stored audio/Task runnable or silently discard unknown forms. Avoid maintaining a second real official serializer/Prompt/persistent Entity model in production.

### C. Safety, tests, evidence

JDK17, headless, bounded Gradle/JVM memory, single worker where needed; only focused new tests + justified direct regressions. Record actual RED, final GREEN and zero-test build errors separately. Use exact compile/test arguments, pre-run source snapshots, byte+Git blob identity, JUnit XML/testcase/exit, child process exit and per-stage failure matrix; never sum overlapping suites as unique totals. No fabricated clean result, softened assertion or clearing incompatible data.

Only synthetic, fixed category and numeric evidence may enter Git. The prior permission to read `H:\手机CCB导出` remains **read-only within that exact scope** but **not required** for this journal experiment. Do not read private backups just to add test counts; do not send file names/paths/content, secrets, real source hashes to public Git. No actual SecretStore/key/DPAPI/profile/user root work; no normal user uninstall, old EXE launch, Android/ADB, paid APIs, network, GC activation, user-data migration, Prompt edit, `desktop` merge, release/tag/upstream sync.

Save bounded verified checkpoints to **the original feature branch only**. If a stage requires real persistence schema approval, exact device/VM permission or native key write, stop that slice, complete unrelated synthetic source/audit work, and include precise `DECISIONS_REQUIRED`.

### D. Deliverables / STOP

Deliver `P11_LR5_PROJECT_REVIEW_PACKET`, at minimum:
`EXECUTIVE_STATUS.md`, `SOURCE_OWNERSHIP_MATRIX.md`, `PROPOSED_AUTHORITY_AND_ROOT_SCHEMA.md`, `SYNTHETIC_JOURNAL_STATE_MACHINE.md`, `HARD_EXIT_AND_RECOVERY_MATRIX.md`, `DORMANT_OWNERSHIP_PARTICIPATION.md`, `TEST_SOURCE_COMMIT_LEDGER.md`, `ORIGINAL_RAW_EVIDENCE_INDEX.md`, `DECISIONS_REQUIRED.md`, `PROJECT_HANDOFF.md`.

Stop **`P11-LR5 PROJECT CODE REVIEW CANDIDATE / NOT PROJECT ACCEPTED`**, with exact final HEAD and full compare. Project independently reviews source and original evidence before any new production implementation permission. Do not claim `G1B CLOSED`, `G2C AUTHORIZED`, `P11 CLOSED`, `D-038 PASS`, parity promotion or Public Preview Gate PASS; keep `Post-P8/Public Preview HOLD` and **A–G 0/7 formally signed**.
