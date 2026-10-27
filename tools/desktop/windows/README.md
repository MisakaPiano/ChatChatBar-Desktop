# Desktop Windows installer identity

`app/desktopApp/build.gradle.kts` separates the user-visible Desktop semantic version from the Windows three-number installer version. Windows ignores a fourth version field and cannot order Preview suffixes: [Microsoft ProductVersion](https://learn.microsoft.com/en-us/windows/win32/msi/productversion), [Compose native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html).

Every MSI packaging task requires an explicit `desktopInstallerVersion` in `0..255.0..255.0..65535` with no leading zeroes. Supply the previously distributed numeric version as `desktopPreviousInstallerVersion` for an upgrade; equal or lower versions reject. The receipt records whether a previous version was supplied; omission does not prove upgrade eligibility. Stable releases must continue increasing this numeric identity even when their semantic version loses the Preview suffix.

Example argument array for an isolated synthetic build (these are not public release versions):

```powershell
$packageArgs = @(
  '-PdesktopVersion=0.1.0-preview.1001',
  '-PdesktopInstallerVersion=0.1.1001',
  '-PdesktopPreviousInstallerVersion=0.1.1000',
  '-PdesktopPackagingProfile=synthetic',
  ':desktopApp:packageMsi'
)
# From app/, with JDK17 and the task's independent build directory configured:
.\gradlew.bat @packageArgs
```

Keep dotted property arguments quoted in PowerShell: an unquoted `-P...=0.1.1000` can split into `-P...=0` and `.1.1000`. The integration verifier passes exact argument strings without relying on that shell parsing.

`desktop` and `synthetic` have distinct fixed package names and upgrade UUIDs; the latter is `ChatChatBarDesktopSynthetic`. Both use per-user installation under `LOCALAPPDATA/<package>App`, separate from the default data directory. Ordinary builds provide menu/desktop entrypoints; synthetic builds omit those personal-shell changes. No Studio product identity, trust root, signing service, DPAPI configuration or updater dependency is used. Native identity isolation alone does not isolate application data: runtime tests must also explicitly select task-owned data/bootstrap/LOCALAPPDATA directories and must not use the ordinary installation or credential root.

`verifyDesktopInstallerIdentity` writes `build/reports/desktop-installer/identity.properties` only on successful validation; do not use a receipt left by an older invocation to certify a failed build. `desktop-build.properties` also records installer assignment/profile alongside the application version and actual Git commit. An app-image without assigned installer identity is allowed for development and is not an upgrade-tested installer.

Offline integration checks, without compiling or running an installer:

```powershell
python tools/desktop/windows/verify_installer_identity.py --evidence app/desktopApp/build/preview-continuous-private-20261025/installer-identity-UNIQUE
```

This verifies the real Gradle task, not a duplicate Python implementation of version comparison. Raw commands, exit codes, receipts and build script snapshot are retained. First install, real A鈫払 upgrade, in-use files, rollback, normal exit, data/credential retention and uninstall remain independent native tests. A verified identity receipt does not prove any of those results or authorize cleanup.

## Generated MSI correction

An actual synthetic A鈫払 installation exposed JDK17's recursive `WixRemoveFoldersEx`: the verbose MSI log explicitly records deletion of an unmanaged test file. `msi_safety.py` corrects this generated unsigned MSI before signing: removes the recursive action/table, keeps normal registered-file removal and empty-directory handling, blocks older versions, and schedules old-product removal after InstallInitialize. Embedded cabinets must keep exactly the same hashes and lengths. Unsupported layouts/products, static file sweeps and signed inputs reject. Original unsigned files/receipts remain in task-owned build reports. A failed correction leaves no canonical deliverable MSI.

Operation contract1 retains directory2/component1, including original GUIDs and KeyPath addresses. `directory_guard.c` includes `operation_guard.h`; Gradle and the builder track both sources. Root resolution remains at60 in UI/execute. Final admission is read-only at1501, after CostFinalize1000/Migrate1200/Validate1400/Initialize1500 and before old removal1510 and ProcessComponents1600. Slots are checked against the actual generated tables. Rollback must be enabled; no policy is altered. Synthetic failure-after-removal moves to1511. Administrative/advertised sequences receive an early explicit rejection.

Admission binds the effective token/SID, RegOpenCurrentUser 64-bit view, known folder and exact unmanaged product tuple. Finite related-product lookup must complete. Native location, cached product/package identity, actual resource contract and directly observed owner conflicts are checked independently; narrow query failures remain UNKNOWN/DENY. Physically missing program directories/files or own values may be maintainable when independent native evidence survives; present contradictory values are never treated as missing. Fresh installation needs more than an empty folder: self/family/owner/marker/target/full-plan/rollback checks all apply. Reparse/alias/path failures refuse. Known default/custom data, bootstrap and credential locations are excluded from writes, without reading secrets. Uninstall/old-child removal do not depend on bootstrap parsing. No recursive cleanup is introduced.

`operation_resource_graph` checks Directory ancestry and names, file targets, complete Feature ownership, Registry component1 shape, bounded null-name RemoveFile/CreateFolder, own AppSearch/RegLocator, Upgrade ranges, custom/standard actions and ordering. Unsupported resource/GUI tables reject. `CcbOperationTable` and `CcbOperationStream` record length-prefixed UTF-16 table hashes and stream hashes; the native guard checks actual active/cached content and the resolved Directory/File targets. These authored fingerprints bind a trusted build, not an anti-tampering security boundary against a same-user MSI editor. Actual `[#File]` formatting is compared with the root-relative graph. Shared GUID compatibility compares real registry addresses and resource paths, not version-dependent Registry row IDs. Test DLL exports are forbidden in packages; real PE exports and both source snapshots are verified.

This first operation1 slice is synthetic-only. Nonempty Shortcut tables remain a native refusal until the ordinary-profile known-folder/link adapter is implemented. Older cached guards are unchanged and can still block servicing; neither native Windows lifecycle nor old-package compatibility follows from compiling this revision. Never replace a cached MSI or clean product registration to pass a test.

No-installer verification, in **Windows PowerShell5.1**, against the explicit retained synthetic A:

```powershell
& tools/desktop/windows/Invoke-DirectoryGuardChecks.ps1 -Python <python.exe> -LegacyFixture <retained-A.msi> -Directory2Fixture <retained-negative.msi> -OperationFixture <new-operation1.msi> -Evidence <fresh-directory-under-app/desktopApp/build>
```

This compiles only production/test DLLs, runs native policy against fake API callbacks, exercises table authoring in SQLite memory and reads the old MSI database without modifying/installing it. Optional `-ProductionBuild` and `-TestBuild` reuse task-owned outputs only after exact source/DLL hash, size and test-export checks. Raw sources, commands, exits, PS runtime and actual unittest/JUnit-compatible XML remain in the fresh round. It does not validate the live registry adapters or Windows Installer session/rollback behavior. Historical A/B and source snapshots stay unchanged.

The first Preview uses MSI; the normal application EXE remains inside it. Bootstrap EXE generation is excluded until its embedded MSI receives equivalent verified handling. This is a packaging correction, with the existing manual browser update discovery unchanged.

Synthetic MSI alone adds an explicit failure action after old-product removal, for native rollback testing. It does not exist in ordinary-profile MSI. Never infer that a corrected new installer can prevent an older unsafe installer's own recursive uninstaller; native acceptance needs corrected source and target packages. MSI removes registered program files; user data and protected credentials belong outside that tree. Modified registered binaries are not claimed preserved.

The optional synthetic post-write probe (`build_directory_contract_fixture.py --write-probe-build <owned-build>`) adds a separate read-only failure DLL, compiled by `build_synthetic_write_fault.py`. Ordinary/default MSI cannot carry that fixture. Setter51 at5001 transfers exact product/version/root/new-main-JAR hash; synchronous impersonated deferred DLL1025 at5002 observes actual file bytes and own synthetic version Registry values after the corresponding script writes. It runs only with `CCB_SYNTHETIC_FAIL_AFTER_WRITES=1 AND NOT Installed AND NOT REMOVE~="ALL"`; same-product repair is excluded because existing content is not new-write evidence. Scheduled/rollback/commit modes are distinguished using the deferred-supported flags. Verified observation and intentional failure have separate fixed logs; unknown evidence fails with a different marker. No custom resource/credential write or cleanup is implemented. `test_synthetic_write_fault.py` uses fake Registry plus tiny files; real post-write rollback remains a guest test and requires restored OLD/absent-new registration, files and retained unmanaged data, not just1603.

`Build-SyntheticPackage.ps1` creates a fresh owned build, freezes source inputs and pins generated MSI outside Gradle's replaceable output directory. `DesktopWindowsPackageFixture` seeds/verifies only an explicitly marked task profile and invented DPAPI values. `native_package_probe.py` accepts only the synthetic MSI identity, verifies native install/reopen, and uses a Job Object assigned before process resume to track launcher/JVM descendants; it sends WM_CLOSE only to its own window. Private installer logs and credential fingerprints stay local. No protection software is disabled, no prompt automatically approved, and an access failure alone never proves an antivirus cause.

The MSI correction's input, workspace and final output must all stay under the repository's `app/desktopApp/build`; the existing builder satisfies that guard. Relocating Gradle's build directory to another drive can produce an unsigned MSI but then correctly fail the safety step. Use a fresh owned directory within that permitted root for MSI packaging; do not widen the guard or treat the retained unsigned file as deliverable. App-image builds/audits can use a separately selected external directory. Candidate 1010 retains this actual external-directory failure and the subsequent successful repository-owned MSI build separately.

JDK17 nested per-user program paths fail WiX ICE64 because intermediate directory cleanup is omitted. The separate single-level `<package>App` default retains ICE validation. Native MSI builds use a short, uniquely owned jpackage scratch and verbose diagnostics; WiX3 cannot open some generated paths at MAX_PATH. Python isolated mode and an explicit optimized-interpreter refusal keep MSI validation enabled.

`native_acceptance.py` checks the selected synthetic pair sequentially, including selected-root exclusion, Job drain, three unmanaged files, failure-after-removal rollback, old-version rejection and uninstall. Rollback requires both restored program bytes and product registration. A failure is retained and stops that sequence; the probe does not elevate, alter registry ACLs, dismiss protection prompts or infer antivirus cause.

The fail-upgrade probe now also rejects a missing/ambiguous old child session or FileRemove script target outside the observed program directory. Equal final hashes alone cannot pass that target check. Its retained result explicitly says `FINAL_STATE_AND_SCRIPT_TARGET_ONLY` and `physicalRestorationProven=false`: log targets are not independent physical delete/recreate evidence or complete Gate B acceptance. Do not use the full native sequence for the currently paused VM's bounded A-install/B-failure task.

### Component contract1

`msi_safety` now requires `CCB_COMPONENT_CONTRACT=1` independently of directory2. Verified JDK17 input components retain their GUIDs, files, bitness, conditions and directories; each ordinary component gets its own stable `HKCU/Software/ChatChatBar/<product>/Components/x64/<GUID>/Installed` string marker. The versioned ProductCode/directory values belong only to the directory component. Unknown layouts/resources or duplicate/unmapped KeyPaths refuse; a marker alone cannot certify the shape. The transform also assigns a new Summary PackageCode to each edited MSI; this is not a change of Component or Product codes.

Production no longer imports/calls `MsiEnumClientsExW` or `MsiEnumComponentsExW`. Universal foreign-client exclusion and complete inventory health are no longer admission obligations. Standard MSI reference handling is retained. Historical injected inventory-policy tests remain clearly separate from current production evidence. Required exact-product/cache/owner/target errors still refuse; status2 is never redefined as absence. Never re-install a changed same-ProductCode test package over its old layout; use a new version/ProductCode on an independent test branch.

The PS5.1 check entry additionally requires `-Directory2Fixture` pointing to the fixed never-installed Synthetic1100 negative fixture (SHA `cf7283ff4005ca5ce4aeeb00ee174c715e9edff1ee42c820731237608c15aba0`). It is read-only. `test_msi_component_contract.py` covers uniqueness/stability, immutable rejected inputs, actual native metadata queries and injected client failure/conflict; no installer or live registry adapter is executed. The reuse tests load the actual repository-rooted builder only after comparing it byte-for-byte with the frozen test snapshot; the production path guard is not relaxed.

The retained `repair-registration` CLI name is now a **diagnostic refusal**, not an installer invocation. It retains exact MSI/receipt, absent-association and program-version guards, queries only the explicit synthetic product/DefaultFeature, rechecks association stability, and writes a fresh-label report. Advertised or registered residue without the expected association is `NATIVE_REPAIR_PARTIAL_REGISTRATION`; read/permission failure is unavailable, not absence. A consistent observation, an advertised product and no reported registration are separate categories; none authorizes repair or proves global OS health. All paths return nonzero because no complete re-publication strategy has been verified. The old `REINSTALL=ALL/vomus` attempt is not repeated. Its strong exit0/exact-association/unchanged-file-map success predicate is retained as `assert_repair_success`, currently an unconnected oracle, not a recovery implementation. Install/upgrade/uninstall/lifecycle actions remain unchanged. `test_native_registration_diagnosis.py` uses invented snapshots and forbids or mocks every native/installer boundary; it does not query the real registry or fix the original rollback1307.

The standalone `native_package_probe.py` refuses Python `-O`/`-OO` at module entry, including import, before its assertion-based guards or native actions can be bypassed. `test_native_probe_validation.py` exercises only help/import admission in tiny synthetic working directories; it never invokes an installer. This guard does not repair any existing registration failure. Previously executed, hash-pinned one-shot helpers retain their original source identity and must not be replayed against new probe code; a future authorized action needs its own reviewed receipt.

Task-owned manual app-image and repair helpers must be exercised with the actual `WindowsPowerShell/v1.0/powershell.exe`, not only PowerShell 7. Current helpers use ASCII status/error literals, direct JSON-array assignment and framework SHA-256 stream hashing; all package identity/217-file length/hash/reparse checks remain. `-PreflightOnly` does not launch the application or installer. It proves neither successful application startup nor a human visual result: the later actual 1008 startup refusal is retained separately. See `docs/desktop/evidence/preview-continuous-20261025/manual-helper-winps51/` for the real parser/preflight failures and corrected native results.

## Bounded real-MSI directory fixtures

`msi_safety.py --guard-build <owned-build> --expected-guard-sha256 <explicit-sha>` can embed an already compiled production guard after source/body/hash/size/test-export identity checks; test DLLs and changed bytes refuse. No flag bypasses the existing MSI root/layout/CAB checks. Without these options a fresh guard is compiled as before.

The retained1102 clean-guest client-enumeration failure and later diagnostics stay unchanged. The selected revision removes those inventory queries from production admission, rather than converting their errors to an empty result. `test_msi_operation_contract.py` covers independent resource/action fixtures, required-query error classification, actual MSI metadata fingerprints, shared resource addresses, bootstrap syntax and read-only synthetic file identity. The original static tests did not open an Installer session; the added regression below uses a restricted session. First install, maintenance and rollback still require native Windows evidence.

The later active-session regression uses the documented restricted `MsiOpenPackageEx(IGNOREMACHINESTATE)` handle, which cannot mutate machine state or run DLL CAs. Installer opens/costs with three known nonpersistent implementation tables and resource-free registry-cost component projections. Exact schemas, projection origin/shape and absence of resource/Feature ownership are checked; authored rows must still match every original hash. A static-only1110 candidate was withheld after this actual RED. The added test also changes a real resolved child target while retaining valid authored hashes; the final-target reader must reject it. Restricted session/costing success is not real installation/rollback acceptance.

`build_directory_contract_fixture.py` is an isolated **test fixture** factory, not release packaging. It verifies frozen1010 inputs and unchanged runtime sources, copies those classes/runtime, changes only build identity in a new main-JAR copy and preserves other entry hashes. It uses bounded direct JDK17 jpackage and the production safety transform. Class compilation provenance and packaging provenance are recorded separately; do not call this a fresh Kotlin build. `--preflight` creates no image/MSI. Actual generation requires explicit resource/slot authority, at least1GiB available memory,1.5GiB free in H and1GiB in I. It never runs an installer/app or cleans anything. Directory embedding is not full MSI component/upgrade acceptance: pairwise resolved Component GUID/KeyPath review and native evidence remain necessary.

With explicit user acceptance of slowness/paging, `--accept-memory-pressure` may waive only that synthetic factory's physical-memory reserve. The receipt records actual available memory, whether the normal gate passed and the explicit option; Java remains Xmx256m/one processor and packages run sequentially. Disk, path, source, payload and component checks are unchanged. Default invocations retain the1GiB memory gate; this option is not permission for cleanup or system-setting changes.

## Read-only application-image audit

`audit_app_image.py` checks an explicitly selected jpackage image without launching or changing it. Supply the expected commit, application/installer versions and profile from that build, with a new report path outside the image:

```powershell
python -I tools/desktop/windows/audit_app_image.py --image $image --commit $commit --version $version --installer-version $installerVersion --profile synthetic --report $report
```

The report records exact file hashes/lengths, the single packaged build identity, every app JAR's CRC, launcher classpath and class-entry count. Compare its file inventory to the frozen build manifest; the count is ZIP class entries, not distinct JVM classes. Known archive/credential filenames, data directories and known private companion classes refuse. Windows `ZipInfo.filename` normalizes backslashes and truncates NUL, so validation uses `orig_filename`; independent raw-wire fixtures cover both cases. No ZIP extraction occurs.

This is a build inspection, not a universal secret scanner, signature/source attestation, native lock proof, installer acceptance or permission to publish. Nested JAR contents are not recursively inspected. Keep the existing private-data review and separate native acceptance. Tests use tiny independent synthetic images only:

```powershell
python -I tools/desktop/windows/test_audit_app_image.py
```

The existing `.github/workflows/release.yml` builds Android APKs and is not the Desktop publishing path.

## Fixed component1 guest first-install entry

For the **new operation1** first install, use the separately frozen1112 bundle built by `build_operation_guest_bundle.py`. `Create-Operation1TestCopy.ps1` is DL1-host-only: exact original VM/checkpoint, fresh destination/name, conservative disk preflight, export/import with a new VM ID, source metadata recheck, no start/revert/delete, and disconnection of only the new VM network. It is not executed by DD1. Preserve the old1102 scene. The copy operation's real platform outcome remains untested until the user runs it locally.

**Prepare r02 correction:** the frozen original host script failed at line34: PS5.1 `Measure-Object -Property bytes` does not read Hashtable keys. Its first explicit directory write was line39; export/import/rename/disconnect were later and are not reached through this terminating error. This is control-flow evidence, not proof of unchanged physical bytes or unrelated activity. Preserve the original1112 ZIP/script. The separate small r02 patch provides `Inspect-Operation1PrepareFailure-r01.ps1` for actual read-only VM/destination/checkpoint/capacity/hash checks, then `Create-Operation1TestCopy-r02.ps1` with the definitions-only metadata library. Do not retry the old script. Explicit integer extraction supports Hashtable and PSCustomObject while refusing missing/null/nested/noninteger/nonpositive/overflow input; it does not reduce the space reserve. `Test-Operation1CopySpace-r02.ps1` exercises exact original-function output with mocked Hyper-V readers and tiny files under PS5.1. Only the human later runs real VM operations on DL1.

`Invoke-Operation1FreshGuest.ps1` requires the user-selected independent Win10 Pro19045 x64 Hyper-V guest and fixed manifest hash. It invokes a full quiet1112 installation once into the default directory, then verifies exact product/feature/package,216 authored payload hashes,218 component states/KeyPaths,220 own Registry values and the guard's rollback-enabled admission log. Image-only `app/.jpackage.xml` is deliberately not an installed payload file. Optional visible application startup uses separate fake LocalAppData/home/temp; the user closes normally. Launcher observation is not Job-descendant drain or root-lock proof. Failure leaves output/registration untouched by cleanup and forbids automatic retry. `Test-Operation1FreshGuest.ps1` runs PS5.1 fake-boundary and tiny-file tests only; it compiles declarations but calls no Installer/native registration API or GUI.

The old entry below remains historical and must not be replayed as operation1 acceptance.

`Invoke-Comp1FreshGuest.ps1` is a standalone Windows PowerShell5.1 entry for the pinned synthetic1102/1103 pair. Put it beside the unchanged transfer ZIP contents (including both MSIs, `IDENTITY.json` and `Verify-Packages.ps1`). Run only in the user-selected new Hyper-V guest, with `-ConfirmIsolatedGuest`. It checks exact input hashes, delegates static metadata validation to the pinned verifier, refuses existing synthetic registration or output, and installs1102 once into a fresh child of guest LocalAppData. It does not launch CCB, upgrade1103, repair, uninstall, clean, change execution policy persistently, or reboot. Failure preserves the scene and must not be retried automatically.

Guest model/SID checks reject the known DD1/DL1 hosts and service accounts; they do not prove the host-side VM ID. Selecting the independent copy remains an explicit human step. `RESULT.json`, native verbose log and program hashes remain local under `%LOCALAPPDATA%\CCB-Comp1-Fresh-1102-r01`. Success means observed fresh installation registration and packaged identity, not upgrade/rollback/lifecycle acceptance. `Test-Comp1FreshGuest.ps1 -Evidence <new-directory>` exercises orchestration with fake native boundaries and independent tiny JARs; it never installs an MSI. Tests are separate from actual guest evidence.
