# Directory recovery implementation — LOCAL ONLY / NO NATIVE INSTALL

2026-10-26 JST. User-authorized packaging directory recovery fix. Base:
`f8a51ad167bbe5e12ad0c3d942a7428b0fc5b162`. No push, Release, new MSI,
installation, uninstall, repair, registry/ACL change, VM or other-project operation.

## Concrete change

The historical old product read its saved `RM_RF...` directory but continued with
default `INSTALLDIR`; DD1's FileRemove targets independently confirm this. The
current parser, run only over that retained local log, now returns
`REMOVAL_TARGET_MISMATCH` (`DD1_RETAINED_LOG_CLASSIFICATION.json`). This is not a
new native reproduction and does not explain1307 or DL1's unidentified Return:5.

- `directory_guard.c`: small x64 immediate custom action, no credential access or
  file/registration writes. New packages retain `CcbInstallDirectoryV2` through
  the MSI Registry table under the existing per-version HKCU product key and
  existing64-bit component. On maintenance/uninstall/old-product removal the
  helper verifies ProductCode ownership and resolves that saved directory.
- `msi_safety.py`: embeds the independently compiled production DLL, adds resolve
  at60 in UI/execute after AppSearch50 and before CostInitialize800, and recheck
  at1399 before InstallValidate1400/transactional old removal1501. Adds directory
  contract2 separately from safety1. INSTALLDIR is retained in SecureCustomProperties
  for UI/server transfer. Existing CAB, recursive-cleanup refusal, downgrade and
  transaction rules remain. Gradle tracks the two new production build inputs.
- Known old contract2 upgrades require exactly one related product in the supported
  current per-user unmanaged context, matching cached family/contract, owner record
  and Installer-reported location. The cached file must be local ordinary MSI with
  no observed reparse ancestors. Missing/unreadable/ambiguous evidence refuses.
- Requests conflicting with the saved location refuse. New target must be empty
  or absent; registered target must be reachable. Roots, relative/UNC/device/dot/
  ADS/reserved paths, system/data locations, observed reparse points and unavailable
  ancestors refuse. Program paths are bounded to240 characters. Checks are repeated
  before mutation; no claim of adversarial cross-process filesystem race immunity.
- `native_package_probe.py`: wrong/unknown old-child FileRemove target cannot pass
  just because final files match. It retains target classification and explicitly
  labels successful observations `FINAL_STATE_AND_SCRIPT_TARGET_ONLY`, with
  `physicalRestorationProven=false`. Script targets do not prove physical restoration.

## Old-package policy

Discoverable safety1-only/unknown cached old packages are **not** silently upgraded:
the new guard refuses before old-product removal. Existing A0.1.1002/B0.1.1003 and
their cached installers, raw logs, transfer ZIP and historical1307 evidence are
untouched. New B does not patch cached old A. Broken/missing UpgradeCode association
or other undiscoverable legacy residue is not repaired or globally ruled out by
this helper. DD1's existing partial installation remains outside permitted actions.
No migration/cleanup workaround is executed or promised.

## Actual tests and source binding

Final native **Windows PowerShell5.1.19041.6456** orchestration:
**3 suites /24 tests /24 PASS /0 failures/errors/skips /exit0**. These are actual
Python unittest methods with JUnit-compatible XML, not24 Windows Installer tests.
Subcases are not extra tests; history is not accumulated.

|Retained execution|Actual result|
|---|---|
|Pre-fix `verify()` against exact old A|1 test /1 FAIL: old safety1 package incorrectly accepted without directory contract|
|Compiler first|exit1: batch environment invocation quoting; no component tests|
|Compiler second|exit1: Windows `FAILED` macro collision under `/W4 /WX`; no component tests|
|Compiler third|exit0 after `LOOKUP_FAILED` rename; preserved test DLL identity, not rebuilt until C changed|
|Initial component run|11 PASS against hash-matching third compile|
|PS5.1 first / final / registered-missing rounds|19 PASS /21 PASS /22 PASS; each uses its own recorded source|
|PS5.1 delivery / complete rounds|23 PASS each, compiler outputs reused after hashes/source match|
|PS5.1 target-scope final|**24 PASS**; final source snapshots and XML; identical native outputs reused|

Final source snapshots include9 files (8 tool/native/test files plus static Gradle
input declaration), SHA recorded before execution. `SOURCE_BLOBS.json` derives Git
blobs from the unchanged pre-test bytes for index/commit comparison. `RAW_INDEX.json`
binds **111 raw files /369,074 bytes**: real failure logs/sources, compilation text
and identities, intermediate results, final commands/source snapshots and XML.
Compiled DLL/OBJ/LIB/EXP, MSI files and original private MSI logs are excluded.

The final production DLL is145,408 bytes, SHA-256
`bcfd1b86f058d423aa06133a7e05bb4d93c6cbe927243cad85522eec7bd0ba0c`;
C source SHA-256 `212abde7813f6189e24cbf775d8c4b198c6097cc2d6a5fc2b29e7052e556f8a7`.
It exports only production entries, not the fake-API test entry. Local binaries
remain in the private build round, not Git or a distributable package.

## Reproducible bounded entry

`tools/desktop/windows/Invoke-DirectoryGuardChecks.ps1` runs under actual PS5.1
with explicit Python, retained synthetic A and new evidence directory. It compiles
only the small DLLs; optional production/test build reuse requires matching source,
binary hash/size and test-export identities. It refuses stale output/unsafe evidence
parents, records every Python command and exit, and stops on a failed stage.

The final local round is
`app/desktopApp/build/preview-continuous-private-20261025/directory-guard-fix-20261026/ps51-target-scope-final`.
It reused outputs from `ps51-registered-missing-final/{production,test}`. Raw
`tests.arguments.json`, driver stdout/exit, PS runtime and sources are retained.
No fake password/API/real backup is needed. VS BuildTools x64 and Windows SDK are
build-host prerequisites; missing compiler fails rather than publishing an unguarded MSI.

## What passed / what did not run

- Native selection/refusal policy uses injected fake session, saved-record,
  old-product, attributes and directory-content callbacks. No live guard adapter
  is called. `realNativeAdapterCalled=false` means **guard live session/registry
  adapters**, not an absence of all native APIs: tests read the old MSI database
  with the existing read-only reader and load the produced DLL to check exports.
- Table insertion/verification is exercised against SQLite in memory seeded from
  the old MSI tables. Binary-stream API construction and handle release are tested
  with a fake MSI API. No real MSI database is changed or newly produced.
- Existing probe optimized-interpreter/help regressions run without native action.
  Real final fixture hash is checked before/after; no private data root is opened.
- **NOT TESTED:** real MSI session property timing, real HKCU/Installer context,
  cached old-product API observations, embedding in an actual new MSI, installation,
  physical deletion/recreation, rollback, power loss, all VM behavior and signing.
  Gradle packaging itself was not invoked; the two input declarations were statically
  reviewed. Existing seven real-MSI-metadata fixture tests were not rerun here.
- DL1's reported219-file/final-registration PASS_WITH_SCOPE is coordinator-supplied;
  this thread did not access its E:/C: evidence paths. It neither proves1307 fixed
  nor physical restoration of the chosen program directory.

Next native validation must be explicitly resumed in the designated VM, using new
contract2 packages and checking the actual old child target, before/after file and
registration state, and independent physical removal/restore evidence. Legacy
cached A needs its own safe compatibility/recovery plan; no DD1 trial is authorized.
No P11, D-038 or Preview acceptance is declared.

Protocol basis: Microsoft documents [AppSearch property selection](https://learn.microsoft.com/en-us/windows/win32/msi/searching-for-existing-applications-files-registry-entries-or--ini-file-entries),
[property setting before costing](https://learn.microsoft.com/en-us/windows/win32/msi/custom-action-type-51),
[the UI/server scope of SecureCustomProperties](https://learn.microsoft.com/en-us/windows/win32/msi/securecustomproperties)
and [separate old-product removal sessions and transaction placement](https://learn.microsoft.com/en-us/windows/win32/msi/removeexistingproducts-action).
