# Small synthetic snapshot recovery process probe

This test-only companion supplements existing `AppDataSnapshotRestoreTest` and
`DesktopCoordinatedSnapshotServiceTest`; it does not replace or rerun their fault
matrices. It uses unchanged production classes from an explicitly frozen1010 image:
`DesktopDataRootOwnership`, `DesktopDataOperationCoordinator`,
`DesktopCoordinatedSnapshotService`, `AppDataSnapshotService`, `JsonFileStorage`,
`CharacterRepository` and `ChatRepository`.

The Python driver compiles only the companion with local Kotlin/JDK17 tools,
then runs two integration tests in six independent headless child JVMs:

1. Seed old card/session/message/draft/PNG, snapshot, then create newer state.
   Another JVM restores the selected snapshot and verifies restart-required
   admission, complete old file hashes, unchanged selected snapshot, and newer
   data preserved in the automatically created safety snapshot. Two further
   JVMs reacquire root ownership and load the restored repositories/media.
2. Seed a separate invented root, deliberately damage its snapshot PNG, then
   confirm restoration is rejected without changing live data or creating a
   second snapshot. This expected refusal is a passing negative test.

The driver requires an explicit image, compiler, optional compiler support JARs,
Java binary, expected63-JAR manifest and **new** round directory below
`app/desktopApp/build/preview-continuous-private-20261025`. It never scans data
directories, overwrites an existing round, deletes test roots or starts Gradle.
Each child receives only its own marker-bearing synthetic root. Inherited JVM
options are removed; home/AppData/temp are task-owned. No bootstrap is created.
Compilation is capped at256MiB and one active processor; child JVMs at128MiB and
one processor. Support JARs are compiler-only, not added to product/runtime JARs.

Run `python run_synthetic.py --help` for the explicit arguments. Use the retained
local round's `*.command.json` for exact executed Java/compiler commands and
`SOURCE_IDENTITY.json` for compiler/support/source identities. Never reuse an old
round name. Final public evidence and machine-independent argument description:
[snapshot-recovery](../../../../docs/desktop/evidence/preview-continuous-20261026/snapshot-recovery/README.md).

This proves small synthetic repository reopening, not GUI/StartupGate startup,
credentials/DPAPI restoration, power-loss durability, Android interoperability,
the historical real large-root snapshot's recoverability or installer acceptance.
No production source, formal Gradle configuration or live recovery wiring changes.
