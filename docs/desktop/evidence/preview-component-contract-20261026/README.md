# MSI component contract1: bounded implementation and synthetic packaging

2026-10-26 JST. Source checkpoint starts at local `cc813f61b833d676ae601d4f803edcd9bc1a1af2`; existing fixture/reuse changes were preserved and are included in the source identity. No Kotlin, Prompt, data schema, SecretStore or user-root changes.

## Problem and implementation

The never-installed Synthetic1100/1101 packages had217 different component GUIDs resolving to the same versioned ProductCode registry value. All217 shared GUIDs changed actual KeyPath addresses across those packages. Directory contract2 alone therefore could not establish component compatibility.

The build transform now recognizes only the bounded JDK17 input shape, keeps Component GUIDs/files/conditions/bitness/directories intact and gives each ordinary component a stable unique HKCU x64 Installed marker. The versioned ProductCode and saved-directory values belong to the directory component. Duplicate/missing/unmapped/unknown metadata refuses. Component contract1 is separate from directory2; the native guard reads actual cached component tables and conservatively checks current-user/machine clients, admitting only matching installed-self/selected-old user-unmanaged products. Errors/foreign contexts/partial fresh-self registration refuse. This is not all-user closure.

Every edited MSI also gets a fresh Summary PackageCode; Product and Component identities are not bulk-regenerated. Original unsigned/final packages and logs remain local. InstallInitialize1500/RemoveExistingProducts1501 and fault1502 stay unchanged.

## Actual executions, kept separate

|Round|Suites/tests|Result|
|---|---|---|
|red|1 /1|0 PASS,1 FAIL,exit1: old verify actually accepted the known duplicate layout|
|intermediate|7 /33|31 PASS,2 FAIL,exit1: snapshot-located builder inferred the wrong repository root|
|green|7 /33|33 PASS,0 failure/error/skip,exit0, actual Windows PowerShell5.1|
|memory-option|1 /3|3 PASS,0 failure/error/skip,exit0; pure resource admission tests|

The intermediate failures were test-location defects, fixed by loading the actual repository-rooted builder only after a byte-identical snapshot comparison. The production ownership/path guard was not relaxed. An earlier33-pass execution remains local as green-02; it is not added to these counts. `RAW_INDEX.json` pins byte-exact selected XML/logs/arguments; `.log` copies use `.txt` extensions solely to avoid ignore rules. `SOURCE_IDENTITY.json` distinguishes exact worktree bytes/Git blobs and tested snapshots. No Project independent review is claimed.

Evidence closeout correction: the initial source checkpoint included some index-normalized raw text despite the new raw attributes. A subsequent docs-only correction explicitly re-added the original bytes under `-text` and checked every raw Git blob against the unchanged original SHA/length ledger. Original local executions were not rerun or rewritten; the first checkpoint is retained in history. CR line endings in raw evidence are intentional, not source whitespace changes.

Native C policy runs with fake directory/client APIs. New metadata tests execute actual Windows MSI database queries on fresh synthetic tables, but do not execute an install session, enumerate live product clients or touch real registration/credentials. Live cache/session/context behavior still requires DL1.

## Actual A/B packages

Synthetic1102 and1103 were generated sequentially from the unchanged frozen1010 business runtime. Only copied build-identity resources changed; this is not fresh Kotlin class compilation. The new production guard is149504 bytes, SHA `d521e6f2362bf2c6dff39c8248fa8e0965319c7f90e19dbd8253ce6bec649dd9`.

- A1102:166334464 bytes, SHA `af3fbb1d5f652d15d45a5072c93bf5461b47669301e217df0d5ff39cf67d424d`.
- B1103:166334464 bytes, SHA `ee186196ceb0770fb7073d3662d9996c238dcdd0018b25efe75ec8c35efdb92e`.
- Both have218 unique actual KeyPaths;217 shared GUIDs have0 changed KeyPaths and identical relative file-target sets. ProductCodes and PackageCodes differ; actual embedded DLL and CAB preservation were checked. Actual prepare/summary persistence completed with exit0 for both.

Original memory refusal is preserved locally. The user explicitly accepted paging/slowness; `--accept-memory-pressure` records the actual below-threshold observation and keeps the default gate, Xmx256m/one processor and every disk/source/integrity gate. A separate real disk refusal before B is preserved. Exact duplicate scratch program files were removed only after advance notice and byte-equivalent retained-image checks; no original MSI/log/data removal or system-setting change. Detailed cleanup paths remain in local ignored receipts, not this evidence selection.

## Limits and next action

**PAIR STATIC READY FOR ISOLATED NATIVE VALIDATION; NOT INSTALL/UPGRADE/ROLLBACK ACCEPTED.** Old1307/Return:5 remain unresolved. No installer, GUI, remote desktop VM action, actual DPAPI or Provider call occurred here. DL1 remains off; the user supplied an actual checkpoint-list screenshot showing `Clean-Win10-19045-Before-CCB`. Preserve the existing failed VM and use an independent copy, not in-place rollback.

Formal baseline remains1.4.1. No desktop merge, Tag, Release, package upload, P11 completion or Preview PASS. Current work advances the installer blocker; independent P11/Preview evidence remains in the complete plan.

Microsoft rules used: [Component table](https://learn.microsoft.com/en-us/windows/win32/msi/component-table), [component codes](https://learn.microsoft.com/en-us/windows/win32/msi/changing-the-component-code), [PackageCode](https://learn.microsoft.com/en-us/windows/win32/msi/package-codes), [current-user client enumeration](https://learn.microsoft.com/en-us/windows/win32/api/msi/nf-msi-msienumclientsexw).
