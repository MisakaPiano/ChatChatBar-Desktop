# G0 actual test execution

2026-10-23 JST, Windows, Temurin OpenJDK17.0.20.1. Control a73ce170137ce084add3f7b59eddead0c57b469d. Commands ran from repository app/. Only Gradle cache/network-to-fork Git operations required normal sandbox escalation; no real data/device/GUI/provider access.

## Runs and exact commands

| Run | Actual result | Raw authority |
|---|---|---|
| Initial nine probes | exit0, 9 tests, zero failures/errors/skips | probe-attempt-1.log / exit / XML / source snapshot |
| Expanded twelve probes plus Desktop regressions | exit0, 9 suites/109 tests, zero failures/errors/skips | desktop-run-2.log / exit / source / XML |
| Final Desktop source, with additional before-call inventories | exit0, 9 suites/109 tests, zero failures/errors/skips | final.log, final-exit.txt, desktop-xml/, desktop-results.json |
| Shared snapshot primitives | exit0, 3 suites/49 tests, zero failures/errors/skips reported by JUnit | shared-snapshot.log / exit, shared-xml/, shared-results.json |

Current distinct results are **12 suites / 158 tests**, from the final Desktop run plus the independent unchanged SharedCore run. Earlier9 and109 are not added; old S1 95 is not added. This is not a single158-test command. No unexpected functional failure or compile error occurred. **RED=NOT_APPLICABLE**: probes assert real supported/refused/omitted outcomes; there is no production fix or fabricated permanent failing test.

Initial:

```powershell
.\gradlew.bat :desktopApp:test --tests '*P11S2RootParticipationProbeTest' --no-configuration-cache --console=plain
```

Expanded/final Desktop:

```powershell
.\gradlew.bat :desktopApp:test --tests '*P11S2RootParticipationProbeTest' --tests '*DesktopDataRootOwnershipTest' --tests '*DesktopStartupGateTest' --tests '*DesktopAutomaticBackupRuntimeTest' --tests '*DesktopCoordinatedSnapshotServiceTest' --tests '*DesktopDataRootMigrationMaterializerTest' --tests '*DesktopFullBackupServiceTest' --tests '*DesktopFullBackupCommitJournalTest' --tests '*DesktopFullBackupProcessTest' --no-configuration-cache --console=plain
```

Shared:

```powershell
.\gradlew.bat :sharedCore:test --tests '*AppDataSnapshotServiceTest' --tests '*AppDataSnapshotRestoreTest' --tests '*AutomaticBackupExecutionTest' --no-configuration-cache --console=plain
```

Stdout/stderr are redirected to distinct logs and LASTEXITCODE immediately saved. Final suites: Probe12, Ownership14, Startup20, AutomaticRuntime18, CoordinatedSnapshot7, MigrationMaterializer21, FullBackupService10, Journal4, Process3; Shared SnapshotService22, SnapshotRestore17, AutomaticExecution10.

## Safe fixtures and identity

Only one new test file was added. Production source is unchanged, including all src/main trees, schemas, settings, root authority, GC, snapshots, backups, prompts and SecretStore. raw/production-tree-identity.json proves control/current tree equality and empty working diffs. raw/production-source-identities.json gives exact blob/SHA/length for24 directly related files; raw/test-source-identities.json identifies final probe and reused test codec/wire/child sources. Existing regression sources are unchanged at the control SHA.

Each probe allocates its own p11-s2-g0-* temporary parent, root, tiny fake registered data and clearly fictional sentinel. No app container, real provider, live repository or SecretStore is constructed. Migration uses real service/materializer/authority with an explicit temporary bootstrap and unavailable-home adapter; Portable uses explicit temporary application home/local paths. No ambient bootstrap/root resolution is allowed in these probes.

raw/probe-records/ contains complete logical relative inventories before, during and after lifecycle calls, including directories, lengths, and file SHA-256; held Windows ownership locks are explicitly marked length-only until release. Final inventories hash released locks too. The sole sentinel blob and independent v1 unknown-family archive are retained as fixture bytes. fixture-and-inventory-sha256.json hashes every receipt/fixture. Archive randomness/timestamps can change future hashes; preserved bytes identify this run.

The hidden-root variant intentionally moves only the fixture-created sentinel into images/.synthetic-s2-sentinel before invoking FullBackup, then records both the preserved source bytes and the successful archive inspection showing omission. It is an observed counterexample, not a proposed storage layout. Other probes keep the ordinary top-level sentinel.

Controlled crash probe reuses the existing test-only DesktopFullBackupSyntheticChild. Actual exits0(schedule),71(halt during apply),0(recover), all alive=false; registered setting reverts new→old while sentinel survives outside rows. Captured inventories retain source/new/journal/log byte digests. Only an owned launched child could be forcibly terminated on timeout; no timeout/forced termination occurred. This is not new-domain crash recovery or OS power-loss proof.

Fixture cleanup verifies the absolute parent is java.io.tmpdir, the exact generated basename prefix and released source/destination ownership before deleting only its own parent. No old directory sweeping or moving of user data. Copied test receipts remain outside those temporary roots in test build output and this evidence directory.

The unchanged existing ProcessTest regression intentionally retains its own synthetic temporary journal/log workspaces for failure analysis, as its prior contract specifies. G0 does not sweep those historical/test-owned directories. The new G0 fixture parents are separately bounded and cleaned after capture.

## Explicit coverage limits

- SharedCore XML prints SYMLINK_FIXTURE_UNAVAILABLE and RESTORE_SYMLINK_FIXTURE_UNAVAILABLE. Those two branches are **NOT_TESTED**, despite JUnit reporting zero skips. Two MigrationMaterializer tests also have platform-dependent early returns; this run supplies no branch-specific proof, so their actual symlink rejection coverage is **INCONCLUSIVE**, not upgraded by green XML.
- No old Windows binary/installer or downgrade/root-write trial: NOT_TESTED/BLOCKED R03. No new domain persistence, durable seal, quotas, retention/GC policy, C1/C2, Android or GUI proof.
- Pending-deletion queue/deletion coordinator end-to-end, business reopening on migrated destination, power loss and post-authority downgrade rollback were not tested. The actual narrow cleanup/materialization/journal behavior is separately evidenced.
- The full existing MigrationService suite was not run; the new G0 test calls its real filesystem constructor with explicit safe inputs. Other selected existing suites were run exactly as listed. No full Preview or S1 integrity matrix rerun is claimed.
- Skills reviewed: Desktop full backup skill and available skill catalog. No dedicated root/snapshot skill exists in the catalog. No production behavior changed, so skill facts remain current; user scope forbids edits outside test/evidence and no skill edit was made.

Raw evidence bytes are preserved with local attributes; raw/evidence-sha256.json covers other raw evidence and excludes itself. All historical RED/XML and protected user files remain untouched.
