# Active-session adapter correction

Follow-up to `2e55ab31e17b9530f0a7a6304db4a5dfb628848e`. **Candidate only; full guest installation NOT TESTED.** The original P0 logs/manifests remain unchanged.

The first complete synthetic1110 package was generated but **withheld**. Its static checks passed; a final restricted Installer-session preflight exposed an actual admission defect before handing the package to the user. No installation of1110 occurred. Original1102 and1110 packages/logs are retained, not overwritten or replayed.

## Cause and correction

Opening the package adds nonpersistent `_Property` and `#_FolderCache`; FileCost adds `CostAdjuster`, one WindowsFolder cost directory and218 registry-cost component projections. Every original authored row remained unchanged. The first native closure incorrectly treated these engine-generated records as foreign resources.

The adapter recognizes only the observed names, nonpersistent table status and exact column name/type signatures. Registry-cost rows must be the exact `__<authored-component-id>65` / `Temporary Id` / WindowsFolder /260 / empty condition / empty KeyPath projection of an existing valid component, with no File, Registry or Feature ownership. Original component/directory hashes still match in full; all other rows, unknown tables, persistent table imitations or resource changes reject. No inventory error is reinterpreted, and no general unknown-structure fallback is added. The public MSI API exposes table persistence, not a claimed independent per-row persistence attestation; trusted authoring rejects these shapes in raw input, and runtime accepts only the bounded resource-free costing projection.

The same distinction is applied to authored component validation, full plan and resolved-target traversal. Native exact-query failures and table/phase denials now include bounded stage/status diagnostics without SID, path or secret values.

## Actual execution

- Restricted session preflight: actual RED. Static hash verification passed; opened/costed database verification failed.
- New JUnit regression against prior compiled r06: **17 tests /16 PASS /1 FAIL /exit1**. Intermediate r07: the opening-table fix passed opening but still failed FileCost. r08/r09/r10 then passed the full costing chain.
- Final current-source run: **3 suites /17 PASS /exit0**. A separate restricted handle sets an invented INSTALLDIR, runs CostInitialize/FileCost/CostFinalize, verifies authored hashes and component layout, checks the actual full-local plan and actual resolved file/directory targets. It then changes a real child target while authored hashes remain valid: target verification must reject. The target directories are never created; source MSI bytes remain unchanged.
- Final affected regression: **7 suites /38 PASS /exit0**, current r10 sources. Thus **55 actually rerun tests**, not55 plus the earlier61. The earlier7 full-transform tests are retained evidence for unchanged Python transformation logic; the replacement full MSI build separately verifies new embedded DLL identity, all authored tables and unchanged cabinets.
- Production/test compiler exit0, `/W4 /WX`. Production still exports exactly the two admission functions and imports neither component/client inventory API. Raw commands, source snapshots, exits and JUnit remain local; selected byte-exact copies and hashes accompany this document.

`MsiOpenPackageEx(IGNOREMACHINESTATE)` creates a restricted handle incapable of changing machine state or running DLL CAs. The test invokes external test-only readers of that database, not the production admission action in a real installation. [Microsoft API contract](https://learn.microsoft.com/en-us/windows/win32/api/msi/nf-msi-msiopenpackageexw). This proves the session adapter and target oracle, **not** user/token/cache admission, rollback availability, product publication, full install or lifecycle PASS.

Next: fresh version/ProductCode full synthetic package, true Win10 Pro19045 independent-guest install, then lifecycle and rollback. The ordinary-profile Shortcut adapter and old cached-guard compatibility remain separate unfinished work. Existing P11/Preview authority and data protections are unchanged.
