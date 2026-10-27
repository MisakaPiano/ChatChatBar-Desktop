# 200 — P11-LR6 Project Independent Review: Authority Design, AVD Cleanup, Native Platform HOLD

**PROJECT VERDICT — 2026-10-24 JST — LR6-A CLOSED / QUALIFIED DESIGN AUDIT ACCEPTED; LOCAL STORAGE CLEANUP CLOSED / QUALIFIED CODEX-LOCAL EVIDENCE ACCEPTED WITH AUTHORIZATION PROVENANCE LIMIT; LR6-B ENVIRONMENT_NOT_PROVEN / NOT_TESTED. THIS IS NOT APPROVED PRODUCTION PERSISTENCE, G1B CLOSEOUT, G2C, P11 OR PUBLIC PREVIEW PASS.**

## Exact Git authority and reviewed sources

| Object | SHA / scope |
|---|---|
| Authorized feature start | `ceeeb09e3327aa70a4858548b94520d87f4c2690` — [197](197_P11_LR6_USER_AUTHORIZATION_AND_LOCAL_SPACE_SCOPE.md), [198](198_P11_LR6_AUTHORIZED_CODEX_EXECUTION_CONTRACT.md), [199](199_LOCAL_DISK_SPACE_AUDIT_AND_SAFE_CLEANUP_CONTRACT.md) |
| LR6 storage tools, real local cleanup, original logs | `3c789ee5ab6b065b7303fb410efda3e46e043411` |
| LR6-A proposal and static audit packet | `03d321e127fdda0c47d4956727c794df94fee694` |
| Codex final docs-only delivery | `00575acf2f0202e3b49629afb81e887d5dd55114` |
| Protected `desktop` | `5ef95428421f7c693b010ac88f51d470804ab07a` (unchanged) |
| Observed upstream/master | `58023edf48f40764c481d1367f62bc048c8a5059` (unchanged) |
| Formal validated upstream | official ChatBar 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8` (unchanged) |
| Selective D-038 official full backup | official 1.4.5 `1198a13cf3008de1358b8a69aa5e40b64797c8d9`, not general baseline promotion |

[Codex handoff](evidence/p11-lr6-20261024/P11_LR6_PROJECT_REVIEW_PACKET/PROJECT_HANDOFF.md) · [full start-to-delivery diff](https://github.com/MisakaPiano/ChatChatBar-Desktop/compare/ceeeb09e3327aa70a4858548b94520d87f4c2690...00575acf2f0202e3b49629afb81e887d5dd55114) · [original evidence index](evidence/p11-lr6-20261024/P11_LR6_PROJECT_REVIEW_PACKET/RAW_INDEX.json).

Project independently verified **the entire `app/` Git root-tree ID is identical** at start and delivery (`b148fecde3f3604cdf2298d089dfc14eb362ae1a`); `.agents/` also unchanged. Only new one-time `tools/desktop/interop/lr6`, docs/evidence and two short CURRENT prefixes were committed. No existing runtime, Gradle, StartupGate, SecretStore, Prompt/API request, Entity, full-backup restore, GC, baseline, parity, installer, Android code or protected branch changed. Original `H:\手机CCB导出` was OUT OF SCOPE and not accessed.

## LR6-A qualified source and design result (NOT DEPLOYMENT APPROVAL)

Project directly read [LR6-A contract](evidence/p11-lr6-20261024/P11_LR6_PROJECT_REVIEW_PACKET/LR6_A_SOURCE_AND_AUTHORITY_CONTRACT.md), [old writer challenge](evidence/p11-lr6-20261024/P11_LR6_PROJECT_REVIEW_PACKET/OLD_WRITER_FENCING_RESULT.md), [sealed ownership proposal](evidence/p11-lr6-20261024/P11_LR6_PROJECT_REVIEW_PACKET/DORMANT_OWNERSHIP_SPEC.md), native precondition, and source files for `WindowsSecretRootResolver`, `DesktopSecretStore`, `WindowsSecretStore`, `DesktopDataDirectory`, `DesktopStartupGate` and `DesktopOwnedImageCleanup`. The proposed separate-principal/broker-owned `ProgramData` recovery authority and strict versioned identity/head/intent/participant/receipt records are a **source-grounded candidate** with explicit MAC/trust, missing/unavailable/foreign/ambiguous and unproven anti-rollback/flush/old-writer boundaries; there is **no installed broker, account, service, permission change, protected key migration or selected production schema**. A broker would substantially change native I/O, installer and credential ownership; it is not automatically preferred over every alternate design without independent threat, compatibility and platform evidence.

D-045 B+inactive fresh root/OLD legacy key retention, lossless dormant historic voice/audio and C-A raw MomentPost/Task with independent three image edges remain **approved target directions**. Candidate manifests/GC/snapshot/Portable/P13 ownership are not production adapters. Community protected sink/issuer/refresh/logout and the conflict with encrypted OLD token retention are open. No release can claim whole backup success while its 11 unsupported-schema Entity records, 108 historical voice-version references and remaining dependency/credential graph lack approved complete retention/restoration.

LR6-A is **QUALIFIED DESIGN AUDIT ACCEPTED AS REVIEWABLE PROPOSAL ONLY**. This is not a frozen on-disk protocol or authorization to write `%ProgramData%`, `%LOCALAPPDATA%`, any real data root, installed CCB or `WindowsSecretStore`.

## Local project cleanup: exact scoped reconciliation and provenance

**Original [199](199_LOCAL_DISK_SPACE_AUDIT_AND_SAFE_CLEANUP_CONTRACT.md) auto-delete allowlist covered only specified compiler scratch and LR5 synthetic sandboxes, NOT these AVDs.** Codex's [supplemental authorization record](evidence/p11-lr6-20261024/P11_LR6_PROJECT_REVIEW_PACKET/STORAGE_USER_SUPPLEMENTAL_AUTHORIZATION.md) quotes a later *direct user instruction in the Codex session* **「批准核实后删除这两个测试环境」**, solely for:
- `app/desktopApp/build/p11-interop-20261022/avds`
- `app/desktopApp/build/p11-moments-poc-20261022/avds`.

Project can inspect the Git-stored documentary receipt and guarded tool code, **but it did not independently retrieve the original user→Codex conversation event or original private local identity/path ledger**. Thus permission provenance is **Codex-documented, not independently user-event-attested within this Project turn**. Do not silently broaden 199 for future work or call this a permanent automated cleanup rule. The historical AVD disk/RAM state was **intentionally discarded**, not archived or losslessly restorable; only the separate original source/test logs/acceptance evidence was retained.

Codex local original public dry runs and logs reconcile:
- Initial two exact AVD directories: **52 file entries / 8,110,202,422 logical bytes**, checked owner identity, no reparse/ADS and process-name screen. Separately checked existing **647 historical Git evidence files / 8,341,335 bytes**, 646 raw Git blobs + 1 CRLF-normalized text, retained.
- First `apply` genuinely failed with **exit2** on read-only file, **five files /69,406,721 logical bytes** already gone; no false success/automatic blanket retry. Earlier initial/second manifests and original failure log preserved.
- Re-plan confirms 47 remaining files; second `apply-readonly-skip` **exit0**, deletes **45 files / 8,040,664,629 logical bytes**, retaining exactly **two read-only files /131,072 bytes**, no chmod/elevation/force-unlock.
- Combined **50 files / 8,110,071,350 logical bytes** removed in the permitted two targets, independently arithmetically reconciled from public receipts. The exact 50 per-file identity/deletion ledger and original disk state remain Codex-local/ignored; Project did **not** execute/forensically reconstruct local deletions or re-enumerate H: itself.
- Immediate second-apply H: volume-free change `322,097,152 → 8,299,012,096` = **7,976,914,944 bytes**, not the same metric as deleted logical bytes. Compatible before/after metadata census `29,521,759,319 → 21,486,390,458` logical bytes, and free `740,913,152 → 8,221,466,624`; census is non-atomic and new logs/source snapshots occupied bytes. Intermediate direct-stat after-census `21,191,008,776` was INCOMPLETE and must NOT be used for savings. Later measured H: free `19,160,305,664` lacks an explained cause; do NOT attribute it to LR6 cleanup.
- LR5 synthetic sandboxes were **not** deleted despite matching tracked snapshots, because independent exclusive-owner/alternate-stream/active-use proof was incomplete. No Git history, raw evidence, credential store, other packaged binaries, private LR2 witness, app data or global Gradle cache cleanup took place per Codex's scoped local receipts. The other 19+GB build/package output needs a **new exact-path retention decision**, not blanket clean.

## Independent tests, source snapshots and public evidence

Project fetched and read original five local Python unittest output runs and exit receipts: initial **12 tests: 8 PASS / 3 FAIL / 1 ERROR / exit1** (Windows DirEntry identity precondition); corrected **12/12 PASS / exit0**; final focused **17/17 Python unittest PASS / exit0** (seen in `cleanup-policy-tests`, `readonly-skip-policy-tests`, and `enumeration-policy-tests`). There is **no new JVM/JUnit, Gradle, GUI, native DPAPI or Android suite**; do not sum repeated 17-test runs as unique tests. The first failed deletion and its explicit exit2 are original actual negative evidence.

Project directly reviewed deletion/guard implementation and synthetics: two path constants hardcoded (no arbitrary user delete path), original read-only evidence/HEAD binding, exact native file/dir handles, identity/read-only/alternate-stream/reparse guards, separate plan and apply, readonly skip after failure, and no blanket recursive tree deletion. Code still provides **one-time destructive functionality**, not a generally safe file-manager operation. Actual private per-file hashes/metadata were not independently read.

Project independently recomputed **15/15 selected original public raw artifacts** against [LR6 RAW_INDEX](evidence/p11-lr6-20261024/P11_LR6_PROJECT_REVIEW_PACKET/RAW_INDEX.json): byte length, SHA-256 and exact Git blob ID all matched. Project compared **8 selected source/snapshot pairs**: 5 exact Git blob matches and 3 current Kotlin source snapshots with CRLF-only differences, whose text normalized LF matches; not a general claim that every 45 source snapshots and every 90 raw file were Project-rehashed. Codex's 98/98 final public raw/tool checks and 45/45 source checks remain separately attributed. Project did not run the native tool locally.

**Storage scope conclusion: CLOSED / QUALIFIED CODEX-LOCAL RECEIPT ACCEPTED.** The specific two-AVD retirement with documented supplemental consent and local source/test evidence is credible and internally reconciled; no independent H: deletion forensic or direct original Codex permission event verified. The approved scope does NOT generalize to other files.

## LR6-B: blocked precondition, not platform test success

No independently proven disposable Windows VM/profile/principal/snapshot receipt was available. Tool discovery found no usable native VM commands but does not prove absence of a VM elsewhere. Initial volume space was also limited. **LR6-B = `ENVIRONMENT_NOT_PROVEN / NOT_TESTED`**; no test of DPAPI/SID/privileged broker, native authority publication/flush across reboot/power loss, old EXE, Win32 crypto or real protected participation, and no legitimate PASS or FAIL can be assigned. The ordinary Windows native file metadata/delete guard tests are storage-tool unit tests and **not** LR6-B.

## Final disposition

**LR6-A CLOSED/QUALIFIED DESIGN ACCEPTED; Storage cleanup CLOSED/QUALIFIED SOURCE+LOCAL-RECEIPT ACCEPTED; LR6-B PRECONDITION BLOCKED/NOT_TESTED.** No broad LR6 native platform PASS, P11 closure, G2C authorization or Preview release. Keep `G1B OPEN / G2C NOT AUTHORIZED / P11 PENDING / D-038 BLOCKED / Post-P8 HOLD / Public Preview HOLD / Preview Gate A–G 0/7 formally signed`.

Next [201](201_P11_LR7_NATIVE_LAB_AND_AUTHORITY_ADAPTER_DRAFT_NOT_AUTHORIZED.md) is a **DRAFT**, not user authorization: prioritize a provably isolated Windows guest first; then opt-in, scoped native identity/DPAPI/flush + trust-adapter falsification on fake credentials only; also review broker-vs-alternate deployability. No host virtualization install, real profile/SecretStore/DPAPI, old CCB executable, current data root, installer/Android or production wiring without separate explicit approval.
