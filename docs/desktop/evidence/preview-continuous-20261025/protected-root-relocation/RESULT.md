# Controlled relocation of a bound Desktop data root

The actual source-admission counterexample first validates a complete synthetic source graph, then calls the default container's directory migration. The old byte-copy materializer correctly refuses an identity-bound root. That guard remains unchanged. The missing controlled route is now wired through DesktopDataRootMigrationService.

The new route holds task, automatic-backup and audio maintenance; captures a credential-free modern self archive; verifies both current raw-root proof and authenticated portable-wire proof against their respective immutable captures; and uses the existing merge publisher to prepare a newly bound root with known consumer keys. A changed source or unexpected conflict refuses instead of resurrecting an older self snapshot. It does not copy the old native identity fence, overwrite the old namespace or modify old business records. New target ownership remains held through storage shutdown; confirmed/indeterminate authority seals old work. The regular merge entry points retain their behavior and gain only an owned alternate scratch path and commit-evidence projection.

The default container also wraps legacy root moves in task maintenance, so accepted final writers drain before directory copying. An already closed global restart admission is reflected in the result before any destination is created. The new result is separately classified in the switch controller and displays an explicit notice: **historical snapshots/recovery workspaces remain at the old root and are not copied to the new root**. The old root and old credentials are never automatically cleaned. Unknown/unregistered root data and existing 4-GiB/entry limits remain conservative refusals, not partial migrations.

## Actual runs, not cumulative totals

| Run | Suites/tests | Failures | Exit | Meaning |
|---|---:|---:|---:|---|
| Initial fixture compilation | 0 / 0 | — | 1 | Six fixture compile errors; no runtime claim |
| Fixture startup | 1 / 1 | 1 | 1 | NO_PRESET_READ injection; initialization narrowed to supplied synthetic records |
| Valid-source admission RED | 1 / 1 | 1 | 1 | Existing materializer explicitly rejects bound source |
| First implementation | 1 / 1 | 1 | 1 | Scratch prefix violated existing ownership helper grammar |
| Comparison attempt | 1 / 1 | 1 | 1 | Raw-root and portable-wire identities were conflated |
| Bounded comparison diagnostic | 1 / 1 | 1 | 1 | Identifies the wire mismatch by registered families, no record values |
| First complete route | 1 / 1 | 0 | 0 | Protected copy, distinct namespace, image/audio rebase, old state retained |
| Fault expansion | 8 / 119 | 1 | 1 | Test wrongly read through the intentionally restart-sealed old repository |
| Final affected regression | 13 / 168 | 0 | 0 | No errors or skips |
| Explicit native protection | 1 / 1 | 0 | 0 | Current-user real Windows DPAPI, invented values and owned directories only |

The source/wire correction preserves two exact checks: raw source proof is compared with raw source proof, and authenticated archive inventory with the mapped wire capture. It does not omit differences or relax schema/hash validation. The sealed-read test now positively asserts rejection through the old container, then independently verifies retained source bytes while its ownership is held; copied final-write content must also exist at the target.

Six runtime cases cover successful media/key rebinding, external source modification after capture, target protection failure, cancellation at captured-key read, an accepted task's final write, and a preexisting global restart seal. Two presentation/controller cases cover the committed protected result and truthful old-history/cleanup notices. Existing ordinary materializer binding refusals, legacy migration, snapshot, automatic backup, authority CAS, regular merge, task runtime and audio maintenance regressions remain executed. Native execution reuses the unchanged successful-move assertions, extracted into a shared test helper; it is not counted as a rerun of the other 168 tests.

The native case explicitly requires CCB_PREVIEW_NATIVE_DPAPI=1. It constructs WindowsSecretStore only with this test's newly created credential directory and passes two invented values; no ordinary credential root, real key, phone archive or user data root is opened. Protected/context blobs and runtime roots are not public evidence. Native file identities and real DPAPI were exercised; cross-account, reboot, power loss, physical Android and installer rollback were not.

The full 247-suite earlier run predates these changes and is not recast as a rerun. The 1006 app-image likewise predates this relocation implementation; a new image is required for its native app validation. No Studio evidence is counted. Baseline/parity and unresolved Preview gates remain unchanged; development continues.
