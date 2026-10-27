# DL1 Prepare r02 correction — no VM/install execution by Codex

The frozen1112 host helper failed at line34 (`Measure-Object -Property bytes`). Report received through the user's coordination thread; **physical DL1 state has not been independently observed here**. This is our host-preparation script defect, not a demonstrated1112 MSI admission failure. The old MSI, old helper, old ZIP, old failure scene and existing uncommitted work are retained.

## Before the failure

Original script SHA-256: `69379321976a3ae6912560bed290e49544af1c1ca63ed078d8b040c2835c8869`.

| Original lines | Actual reachable behavior |
|---|---|
|1–7|Arguments, process preference/strict mode, host/admin checks|
|8|Load existing Hyper-V module into the process|
|9–32|Read exact VM/checkpoint identity, destination existence and virtual-disk file metadata; assign in-memory variables|
|33–34|Construct source evidence, then terminating capacity-statistics error under ErrorActionPreference Stop|
|39|First explicit directory creation — **not reached**|
|43 onward|Snapshot export, VM import/rename and new-adapter disconnect — **not reached**|

No installer, VM start/restore/delete or cleanup is present before that point. The source supports the statement that those explicit mutation commands were not reached through the reported failure. It does **not** prove original file bytes, service/provider initialization effects, previous attempts or concurrent actions remained unchanged. Actual VM/directory/checkpoint checks are still required before continuation; unknown or existing new objects must be preserved, not removed to pass a check.

## Reproduction and fix

The original `Get-Op1SourceEvidence` function emits `System.Object[]` containing `System.Collections.Hashtable` entries with a `bytes` dictionary key of `System.Int64`. Under Windows PowerShell5.1, Measure-Object's property binding does not read that key. Using the exact original function with mocked Hyper-V metadata and real17/29-byte fixture files reproduces `GenericMeasurePropertyNotFound`. This is a synthetic reconstruction of the source's shape, **not a claim to have read DL1's actual disk evidence**. The new read-only inspector reports the actual shape on DL1.

New versioned helpers leave the old delivery unchanged. Explicit checked extraction accepts dictionary/PSCustomObject integer evidence, deduplicates the original VHD chain as before, and rejects missing/null/empty/nested/string/float/bool/zero/negative/range/overflow cases. Memory and total-capacity arithmetic also fail closed. The same `2*disk +2*startup-memory +6GiB` reserve is retained; required/available/missing bytes are reported. No unknown value becomes0, and no capacity check is deleted.

Actual validation: **Windows PowerShell5.1.19041.6456,24 cases PASS,0 failures,exit0**; exact original-function RED retained, JUnit and commands retained. Real Hyper-V module/VM/installer operations: **NOT EXECUTED**. New creation and inspection scripts parsed with PS5.1. Source-shape tests invoke extracted function definitions and mocked readers only.

## Frozen separate delivery

- `I:\CCB-OP1-PREPARE-FIX-20261027-r02.zip`,12218 bytes.
- SHA-256 `03e61c98be44af4c868d5055f7d404ad6ead84501041d9c88344fcaafe7c4aa3`.
- `Create-Operation1TestCopy-r02.ps1`:3711 bytes,`fb6bebfa4cc56ac28f4e65cc82f5f1e9b39c0a615e4c38ee6eceb7c69b5c17be`.
- `Operation1CopyMetadata-r02.ps1`:2471 bytes,`83eb08b1209a120b66d926193b90d9160c74dc20eec3ef8164e4ac8b827fe0ce`.
- `Inspect-Operation1PrepareFailure-r01.ps1`:2781 bytes,`9508c1d6160e887ffe10757e151c0e5c142938ebd8e3caac970201d8ade42f00`.

Original1112 ZIP remains `97a9c3b61e118aa72d2f21b054eb8e1f7bcace1490180f5960ef8e708ab86d6e`; MSI remains `5b2f58f9f722ac3f962d28a74d71ae7a591755c0c669c81a25cf57a3c1237e79`.

Only next human action: extract the small patch on DL1 and run its **read-only inspector**, then return JSON. It checks the executed-old-script hash, successful VM metadata reads, absence of the new VM/destination, original VM Off, exact clean checkpoint, actual disk-entry types and capacity. Do not run old creation, delete/revert anything or assume an unknown size/state is empty. New creation remains gated by `-CreateCopy` and is not part of this read-only command. Until the actual inspection returns, native VM creation and full P1 installation remain **NOT TESTED**; the unrelated MSI source and packaged payload are unchanged.
