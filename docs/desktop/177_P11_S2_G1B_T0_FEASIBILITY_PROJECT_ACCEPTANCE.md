# 177 — P11-S2-G1B-T0 Synthetic Cross-Root Feasibility: Project Acceptance

**CURRENT PROJECT DECISION — 2026-10-23 — G1B-T0 CLOSED / QUALIFIED SYNTHETIC FEASIBILITY ACCEPTED. G1B UNIFIED PRODUCTION DESIGN REMAINS OPEN / NOT AUTHORIZED.**

This Project decision accepts the *experiment and its observed limits*, not the implementation of either topology, a durable transaction schema, actual protected credential portability, or a release-gate claim.

## 1. Exact Git authority and source scope

| Checkpoint | SHA / status |
| --- | --- |
| Starting feature HEAD | `5bb326808a7138f0701cedca7793ef080b92a9c6` |
| Tested two test-only Kotlin files and main evidence | `d8fe491cc1d9ee6436e233c191b8eaea9df6cf57` |
| Final evidence-only receipt HEAD | `8137a04fea44cb8e449a1ce5ed36edd26b0da42a` |
| Existing `desktop` | `5ef95428421f7c693b010ac88f51d470804ab07a` — unchanged |

The source commit added only:
- `app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/g1bt0/T0RecoveryLab.kt`
- `app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/g1bt0/T0RecoveryLabTest.kt`
- independent `docs/desktop/evidence/p11-s2-g1b-t0-20261023/` evidence.

The receipt commit adds only [DELIVERY](evidence/p11-s2-g1b-t0-20261023/DELIVERY.md) and COMMITTED_VERIFICATION; original test/evidence source remains unchanged. No `src/main`, existing tests, Gradle, SecretStore/DPAPI, formal journal/StartupGate, business Domain, Prompt, schema, original evidence, master/upstream or desktop integration changed.

## 2. Independent Project code and execution review

The reviewer read both new source files and [handoff](evidence/p11-s2-g1b-t0-20261023/PROJECT_HANDOFF.md), [feasibility report](evidence/p11-s2-g1b-t0-20261023/FEASIBILITY_REPORT.md), [matrix](evidence/p11-s2-g1b-t0-20261023/TEST_MATRIX.md) and [decisions](evidence/p11-s2-g1b-t0-20261023/DECISIONS_REQUIRED.md).

- Initial real Gradle/JUnit: **8 suites /118 tests, 117 PASS /1 FAIL, exit1**. Failure: cloned directory accidentally kept logical identity when relying on insufficient NIO fileKey. Corrected test-only lab uses native Windows volume/file-ID evidence.
- Separate malformed Gradle command: **zero tests executed, exit1**, with old copied XML quarantined as stale; not counted as another failing test round.
- Receipt-loss focused real RED: **1/1 FAIL, exit1**. A finished receipt with missing decision could incorrectly fall back to OLD; final test-only recovery rejects rather than inventing authority.
- Final real Gradle/JUnit: **8 suites /119 tests, 119 PASS, 0 failures/errors/skips, exit0**. Exact 20 new `T0RecoveryLabTest` methods + 99 rerun existing tests; historical counts are not accumulated.
- Project individually inspected **8 final XML + 8 original XML + 1 receipt-loss XML**, corroborating counters, testcase totals and real failures; checked exact final source blobs against both GREEN snapshots, and verified prior source snapshots differ appropriately.
- Project independently recomputed **18/18 selected raw evidence SHA-256 and byte lengths**. Codex reports **703/703** raw-index self-verification and 2 source / 3 unchanged main tree checks; this is *Codex attribution*, not Project full 703 verification.
- From 3,382 source-fixture inventory entries, Project independently counted **84 `child-*.log.exit` files and 84 paired child logs**, matching three reported exit-content identities: 52 normal0, 28 hard-halt71 and 4 hard-halt72. These are child JVM executions, **not JUnit tests**. Project did not itself run Gradle or child processes.

## 3. Accepted feasibility findings, strictly bounded

- The test lab uses real WindowsSecretStore and native data-root ownership/file-ID interfaces against **test-owned paths**, with a deliberately noncryptographic `FakeProtector`, synthetic account bindings and a test-only manifest/decision/receipt protocol. It does not read authentic `.cbbackup` or invoke a real Repository/Provider/startup restore.
- **A (profile-shared)**: A and B genuinely share logical key files. A declared transaction cannot proceed safely when a participating root is offline or its provider/endpoint conflicts. A test with *unknown/unregistered C* demonstrates that a closed two-root test registry cannot prove global consumer closure; a cooperative FileLock does not constrain an old/noncooperative store writer.
- **B (test-only per-root namespaces)**: a transaction on A leaves B's modeled key files/data undisturbed when B is offline or bound to a conflicting provider/endpoint. **This proves simpler scoped isolation only under this synthetic, fully cooperating namespace model.** It does not prove real namespace assignment, global key semantics, existing-token migration or safe Portable/account movement.
- Both topologies' synthetic fail-first/key-first/data-first/hard-crash/repeated-recovery probes show real partial file states; cooperating admission blocks ambiguous mixtures; intact synthetic proof can restore ALL_OLD, while complete NEW receipts are verified before synthetic callback consumption.
- Lost/corrupt/conflicting receipt, missing intent, changed rollback material, external target mutation, invalid fake context, and legacy bypass are refused. ALL_OLD means an import was *not applied*. The lab's unkeyed SHA/checksum authority can be forged by a sufficiently powerful actor, and its durability under power loss is unproved.

## 4. Unresolved production blockers and next recommendation

**No A/B production choice is adopted. G1B remains OPEN.** Prior [163](163_P11_S2_G1B_UNIFIED_DATA_SECRET_TRANSACTION_DESIGN_GATE.md) and [169](169_P11_S2_G1B_CROSS_ROOT_TRANSACTION_DESIGN_REVIEW.md) still govern candidate architecture.

Release-blocking unresolved issues: durable per-root/account/protector identity; how model/global/singleton key consumers are counted or isolated; reversible retention/migration/consent for legacy shared credentials; cloned/offline/root-switch/Portable provenance; CommunitySession protected lifecycle/sink; authenticated single authority, generation/fencing, durable cross-root recovery and receipt-loss protocol; enforceable old-binary downgrade barrier; actual Windows DPAPI/SID/HKCU, disk-full/cross-volume, reboot/power-loss, Windows account/old executable tests; Android interoperability. These remain **NOT TESTED or not designed**, regardless of T0's green tests.

**Recommended next small slice (candidate, not approved by this review):** an internal topology-neutral, **unwired, read-only target-root/store identity and consumer-evidence observer** with explicit unknown/unavailable/conflicting failure categories. It should bind verified G2B source metadata to actual target context facts without reading/exposing secret values, creating durable registry, writing keys/intents, altering target identity or authorizing recovery. New `src/main` code or live observer access needs separate user task authorization and Project review.

Formal validated upstream baseline remains **ChatBar 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**; selected official 1.4.5 is only the D-038 backup-format reference. **G2C NOT AUTHORIZED / P11 PENDING / D-038 BLOCKED / Post-P8 HOLD / Public Preview HOLD**; all prior Feature Parity ratings unchanged. No real archive, API Token, personal DPAPI or production root accessed.

**STOP: G1B-T0 PROJECT FEASIBILITY ACCEPTANCE REGISTERED / UNIFIED PRODUCTION RECOVERY NOT AUTHORIZED.**
