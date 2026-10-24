# LR6 opt-in storage audit and one-time retirement

These standalone Python tools are not app/Gradle inputs. Run only from the
authorized checkout and task-owned ignored ledger area. They never scan outside
the repository, run Android/ADB, access DPAPI, or discover credentials.

- `StorageAudit.py before|after`: metadata-only, skips reparse descendants;
  exact paths/identities remain in the ignored local CSV, public output aggregates.
- `StorageAudit.py candidates`: verifies the accepted LR5 index and compares the
  three exact sandbox snapshots. It **does not delete**. Compiler scratch without
  proven provenance is retained; native handle/alternate-stream ownership for LR5
  remains unproved. This is not a general build/cache cleaning tool.
- `RetireAuthorizedAvds.py`: ONE-TIME exception separately approved by the human
  during LR6, only two hardcoded historical synthetic AVD directories. Separate
  `plan` / `apply` invocations. The initial apply stopped at a read-only generated
  pstore file; historical failure is retained. `plan-readonly-skip` /
  `apply-readonly-skip` compare remaining files to the original manifest, keep
  read-only files without changing attributes, pin directory handles against
  rename, exclusively pin files, reject alternate streams and changed identities,
  then delete pinned files and remove only empty directories. No force-unlock,
  privilege elevation, broad recursive delete, or generic path argument.

The historical AVD saved state has been intentionally discarded, **not archived**.
Do not rerun retirement or widen its targets. Local manifests use exclusive create;
existing historical receipts must not be overwritten. A failed apply can be partial:
reconcile the original manifest with remaining identities before any new plan.
No automatic exception retry or rollback of deleted disposable state is provided.

Tests: `python -B -m unittest discover -s tools/desktop/interop/lr6 -p 'test_*.py' -v`.
Tests write only fresh fake fixtures under the LR6 ignored ledger and retain them.
One tiny fake file is deleted to test the pinned cleanup handle. These Windows
cleanup checks are **not LR6-B native durability, power-loss, old-writer, DPAPI or
VM evidence**. No JVM suite or prior LR5 test is rerun by these scripts.

Skills reviewed: Desktop full-backup entry points and LR5 behavior are unchanged;
no existing Skill statement became stale. This one-time storage exception is
recorded in LR6 evidence instead of a permanent backup-product capability.
