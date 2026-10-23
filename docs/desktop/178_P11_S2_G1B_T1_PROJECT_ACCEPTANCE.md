# 178 — P11-S2-G1B-T1 Target Identity Evidence: Project Acceptance

**CURRENT PROJECT DECISION — 2026-10-23 JST — G1B-T1 CLOSED / QUALIFIED ENGINEERING ACCEPTED WITH DECLARED LIMITS. G1B PRODUCTION DESIGN STILL OPEN.**

This accepts one **internal, unwired, synthetic-only read-only evidence slice**, not production root/account identity, global consumer closure, topology A/B selection, SecretStore migration, persistent intent/recovery or a Preview Gate.

## Exact source control

| Control | Commit |
|---|---|
| Start/base | `e90fc229e6ea057bbbc05d12b943608811885cbe` |
| Tested T1 source + original evidence | `c05570ab3cc02833d586c341a07e2994cce74d5e` |
| Final delivery-only receipt | `da83c035f9f769389f5883607ac617a6e17728cc` |
| Unmoved `desktop` | `5ef95428421f7c693b010ac88f51d470804ab07a` |

[Full T1 diff](https://github.com/MisakaPiano/ChatChatBar-Desktop/compare/e90fc229e6ea057bbbc05d12b943608811885cbe...da83c035f9f769389f5883607ac617a6e17728cc) · [original evidence](evidence/p11-s2-g1b-t1-20261023/PROJECT_HANDOFF.md) · [raw index](evidence/p11-s2-g1b-t1-20261023/raw/index.json)

The tested source adds only:
- `app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/DesktopTargetIdentityEvidence.kt`
- `app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/ExplicitSyntheticDirectoryWitnessReader.kt`
- `app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/backup/DesktopTargetIdentityEvidenceTest.kt`
- one small `.agents/skills/chatbar-desktop-full-backup/SKILL.md` entry and separate T1 evidence.

The delivery commit adds only `DELIVERY.md` and `COMMITTED_VERIFICATION.json`. The GitHub diff changes **no existing production Kotlin files**, Gradle, official Prompt, schema, StartupGate/Journal/SecretStore, `desktop` or upstream. Codex's separate `764` existing-main-blob identity check remains **Codex-attributed**, not Project's own 764-file rehash.

## Independent Project source and evidence review

Project read both new `src/main` files, the 23-method test source, T1 reports, existing G2A/G2B target plan boundary, and the actual GitHub compare. No existing production caller wires the new classes. The synthetic reader requires explicitly supplied absolute descendant paths, calls the existing Windows same-handle native **attribute-only** observer, and does not enumerate target files, read key payloads, call `WindowsSecretStore`, acquire a production lock, create a registry or stage an intent. This synthetic sandbox precondition is **not** OS-based authorization to inspect arbitrary real roots.

`TargetConfigurationSnapshot` marks target secret relations `UNKNOWN`; model/provider/endpoint metadata and two-pass generations are **synthetic injected evidence**, not trusted Repository data or OS CAS. Native directory witnesses can detect the tested clone/replacement changes but are not durable root IDs, SID/DPAPI context, account ownership, a global consumer census or a lock/fence. `globalConsumerClosure`, `protectionContext`, `accountBinding`, and every key relation remain unproved; `readyToCommit=false` and `restoreAuthorized=false` for every output.

Project directly read **26 original JUnit XML** across first(1), functional RED(1), GREEN(12), final(12) and reconciled all suite counters with raw testcases:
- First: 1 JUnit initialization failure, exit 1; **no functional assertions** executed.
- RED: 1 suite / 21 tests = 18 PASS + **3 real FAIL**, exit 1. Defects: conflicting duplicate/Community precedence, cross-directory account conflict classification, and cancellation diagnostic leakage.
- GREEN: 12 suites / **175 PASS / 0 failure/error/skip**, exit 0.
- Final: 12 suites / **175 PASS / 0 failure/error/skip**, exit 0; **23 new T1 tests + 152 rerun existing tests**. Earlier GREEN, RED, G2A/G2B and T0 counts are not added to final 175.

Project confirmed the **3 final source Git blobs** equal their original final evidence snapshots, and independently checked **8/8 selected raw evidence SHA-256 and byte lengths**. The final Gradle log reports `BUILD SUCCESSFUL in 2m 52s`; **Project did not rerun Gradle or Windows tests**. Codex's **82/82** full raw self-check, fixture inventory and complete-diff verification remain attributed to Codex, not re-described as Project full self-verification.

## Non-blocking review notes for future production

1. Current nested duplicate/key and cross-declaration comparisons are acceptable in an unwired bounded synthetic lab, but before any real configuration wiring must gain an **aggregate work budget/indexed comparison**, not merely a per-list item cap.
2. Content-addressed synthetic evidence includes `native-observation.txt` with **actual test-host Windows volume/file IDs**. No real Key/user backup was identified in this T1 scope, but future **public evidence** should avoid unneeded device-identifying native IDs; retain sensitive raw machine evidence locally or with separate disclosure review. Do not rewrite Git history automatically.
3. Two-pass object/metadata equality is not an atomic snapshot, durable identity, lease, absence proof or protection against malicious/legacy writers. No real Windows account/DPAPI, ACL, removable disk, Portable, old executable, physical power loss or Android/full-app restore was tested.

## Disposition and next gate

**G1B-T1 CLOSED / QUALIFIED ENGINEERING ACCEPTED WITH LIMITS.** T1 does not need an implementation R1 for this scope. Proceed to **G1B production design decision**, using [163](163_P11_S2_G1B_UNIFIED_DATA_SECRET_TRANSACTION_DESIGN_GATE.md), [169](169_P11_S2_G1B_CROSS_ROOT_TRANSACTION_DESIGN_REVIEW.md), [177](177_P11_S2_G1B_T0_FEASIBILITY_PROJECT_ACCEPTANCE.md), this acceptance, and [179 design candidate](179_P11_S2_G1B_PRODUCTION_TOPOLOGY_CUTOVER_DECISION_DRAFT.md).

**NO A/B PRODUCTION CHOICE; NO G2C AUTHORIZATION; NO real archive/credentials, persistent schema/intent, recovery, Prompt edit, baseline promotion, parity change, `desktop` merge or release.** `P11 PENDING / D-038 BLOCKED / Post-P8 HOLD / Public Preview HOLD` and validated upstream **ChatBar 1.4.1** remain.
