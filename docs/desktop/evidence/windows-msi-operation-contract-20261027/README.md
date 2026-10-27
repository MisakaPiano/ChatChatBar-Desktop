# Windows MSI operation1 — P0 checkpoint

Status: **IMPLEMENTED CANDIDATE / NATIVE INSTALLATION NOT TESTED**. This is not P1/P2/P3/P4 or Preview acceptance.

Follow-up: [active-session correction](SESSION_ADAPTER_FIX.md) records the actual additional RED discovered after this original checkpoint, the withheld1110 package, exact Installer costing adaptation and replacement-source validation. Original logs/manifests below remain unchanged.

User-selected specification: [a20848f fixed specification](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/a20848fcca27fefa3c647a380027d34a0e64b3be/docs/desktop/design/WINDOWS_MSI_INSTALL_PROTECTION_REVISION_SPEC_20261027.txt), review baseline `b02aa6f445eb416a96a23b99d006bfd9c8bd189c`. The docs branch was fetched/read without checkout, merge or reset. Runtime Kotlin, Prompt, business Package/Entity, data/credential roots, old packages and diagnostic scenes were not modified.

## Implemented boundary

- Production no longer imports or calls `MsiEnumClientsExW` / `MsiEnumComponentsExW`. Required exact product queries still distinguish1605 from errors2/87/5; errors do not establish absence. Historical injected inventory tests remain test-only and are not evidence of a current inventory obligation.
- Preserve component1 GUIDs, stable x64 HKCU KeyPath **addresses**, directory2 and version-specific owner records. Registry record IDs may change between JPackage versions; shared-resource comparison uses actual addresses, directory ancestry and owned file/removal records instead.
- Build-time finite graph checks cover directories/names, complete feature ownership, files, component1 Registry records, null-name removal, CreateFolder, own search/locator, upgrade ranges, known actions and sequence slots. Unknown resource/GUI/action contracts reject. The actual table contents and Binary/Icon stream contents are sealed in `CcbOperationTable` / `CcbOperationStream` and independently hashed by the native reader. This binds a trusted build; editing both the package and its authored contract is outside the same-user threat boundary.
- Effective thread token, with process fallback only for ERROR_NO_TOKEN; RegOpenCurrentUser 64-bit view; KnownFolder/session correspondence; explicit unmanaged ProductCode/SID/context; finite related family; native installation location and exact cached package identity. Fresh admission requires more than an empty directory. Missing physical program resources/own values can be maintained when independent native evidence survives; present conflicts remain failures.
- Early resolution60; final readonly admission1501 **after** costing/migration/Validate1400/Initialize1500, before old removal1510 and ProcessComponents1600. Final Directory targets and formatted `[#File]` targets must match the root-relative graph. Full plan and actual rollback mode are mandatory. Administrative/advertised modes have an early explicit rejection.
- Readonly handles resolve existing ancestors/aliases, reject reparse/access errors and multiply linked existing files. Forward writes exclude known default/custom data, bootstrap and credential locations. Bootstrap follows the current format1 DEFAULT/CUSTOM locator; unknown members are skipped, malformed present content is not defaulted. Full/old-child uninstall skips bootstrap dependency and retains MSI-only resource deletion. No recursive sweep, registry repair or old-cache modification.
- Builder hashes/snapshots both native sources and inspects real PE exports. Production DLL has only `CcbResolveInstallDirectory` and `CcbValidateOperation`. Gradle tracks the included header; packaging cannot reuse an old-source/test DLL by receipt alone.

## Actual tests, not accumulated historical totals

Final separate runs: **16 + 38 + 7 = 61 tests**, 11 XML suites, zero failures/errors/skips, exit0. These are headless native/Python checks, not native Installer sessions.

| Retained run | Actual result | Meaning |
|---|---|---|
| Initial graph TDD |6 tests /15 subtest errors | Missing new API before implementation; standalone log, no claim of a functional regression |
| Native compile r01 |compiler exit2 | Syntax error; raw command/log/source retained locally |
| focused-r01 |11 tests,10 PASS/1 FAIL,exit1 | Native locator accepted a trailing comma; actual failure, then fixed |
| regression-r01 |30 tests,0 failures/1 setup error,exit1 | New test occupied a shared fixture directory; fixed separate test namespace |
| focused-final-r01 |3 suites/16 PASS,exit0 | Independent graph/action fixtures; actual readonly MSI hashes and synthetic file identity; required-query error classification; legacy A/B shared layout |
| regression-final-r01 |7 suites/38 PASS,exit0 | Affected directory/component/native-probe tests, including retained historical injected policy tests |
| actual-transform-final-r01 |1 suite/7 PASS,exit0 | Actual full unsigned synthetic MSI transform, all cabinet bytes preserved; input/output and invalid cases; no installation |

Raw selected logs/XML/exits and compiler receipts are under `raw/`. `EVIDENCE_SHA256.json` records those exact copies. `SOURCE_IDENTITY.json` records tested raw source SHA-256 and Git blob identities. Full local evidence, source snapshots, compiler outputs, and synthetic metadata MSI copies remain in the Git-ignored `app/desktopApp/build/preview-continuous-private-20261025/msi-operation-contract-20261027-r01/`. Preliminary metadata1102 copies are explicitly **not for installation** and are not a new ProductCode delivery.

## Limits and next executable work

1. First full operation1 synthetic install on the user's independent clean Win10 Pro19045 guest is **NOT TESTED**. No `msiexec` or application was run by these P0 checks. Native registry/cache/token/session/rollback behavior remains to be observed there. The DD1 file/metadata tests must not be presented as guest installation proof.
2. First delivery is synthetic-only. Nonempty Shortcut tables are currently denied by the native target reader. The ordinary Desktop profile needs its known-folder/link ownership adapter before that profile can ship. No ordinary-profile lifecycle claim.
3. P2 full A→B, defaults/custom/quiet, repair/missing directory, uninstall/nonpackage preservation remain **NOT TESTED** for this design. New packages must use fresh versions/ProductCodes; the existing1102 failure is retained.
4. P3 actual post-File/Registry-write failure injection, deletion/publication evidence and physical rollback are **NOT TESTED**. The existing synthetic Type19 after old removal is not proof of post-write rollback.
5. P4 old cached guards are unchanged. Cache packages without operation1 are conservatively refused before removal. Do not claim the new DLL repairs old cache code, and do not rewrite the cache, ignore removal failure or delete registration. A bounded legacy migration route remains separate work.
6. No global component-client closure, cross-user support, arbitrary transforms/patches, root relocation, native DPAPI test, power-loss guarantee or complete TOCTOU protection is claimed.

These are evidence boundaries, not new Project preapproval gates. Continue P1→P4 and independent P11/Preview work under the existing user authority.

## API interpretation notes

Known-folder explicit tokens need QUERY/IMPERSONATE and sometimes DUPLICATE rights; the implementation requests those on the effective token. [SHGetKnownFolderPath](https://learn.microsoft.com/en-us/windows/win32/api/shlobj_core/nf-shlobj_core-shgetknownfolderpath).

Product-state strings and1605 absence semantics are specific to ProductInfoEx. [MsiGetProductInfoExW](https://learn.microsoft.com/en-us/windows/win32/api/msi/nf-msi-msigetproductinfoexw). ComponentState uses a UINT return and an INSTALLSTATE output, rather than interpreting a status as state. [MsiQueryComponentStateW](https://learn.microsoft.com/en-us/windows/win32/api/msi/nf-msi-msiquerycomponentstatew).

REMOVE=ALL must be interpreted after validation; native rollback mode is inspected after initialization. [Removal conditioning](https://learn.microsoft.com/en-us/windows/win32/msi/conditioning-actions-to-run-during-removal), [MsiGetMode](https://learn.microsoft.com/en-us/windows/win32/api/msiquery/nf-msiquery-msigetmode). Actual authored sequence slots were verified against the retained JDK17 package, rather than copied without table checks.
