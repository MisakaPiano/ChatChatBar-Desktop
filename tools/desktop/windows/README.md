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

This verifies the real Gradle task, not a duplicate Python implementation of version comparison. Raw commands, exit codes, receipts and build script snapshot are retained. First install, real A→B upgrade, in-use files, rollback, normal exit, data/credential retention and uninstall remain independent native tests. A verified identity receipt does not prove any of those results or authorize cleanup.

## Generated MSI correction

An actual synthetic A→B installation exposed JDK17's recursive `WixRemoveFoldersEx`: the verbose MSI log explicitly records deletion of an unmanaged test file. `msi_safety.py` corrects this generated unsigned MSI before signing: removes the recursive action/table, keeps normal registered-file removal and empty-directory handling, blocks older versions, and schedules old-product removal after InstallInitialize. Embedded cabinets must keep exactly the same hashes and lengths. Unsupported layouts/products, static file sweeps and signed inputs reject. Original unsigned files/receipts remain in task-owned build reports. A failed correction leaves no canonical deliverable MSI.

The first Preview uses MSI; the normal application EXE remains inside it. Bootstrap EXE generation is excluded until its embedded MSI receives equivalent verified handling. This is a packaging correction, with the existing manual browser update discovery unchanged.

Synthetic MSI alone adds an explicit failure action after old-product removal, for native rollback testing. It does not exist in ordinary-profile MSI. Never infer that a corrected new installer can prevent an older unsafe installer's own recursive uninstaller; native acceptance needs corrected source and target packages. MSI removes registered program files; user data and protected credentials belong outside that tree. Modified registered binaries are not claimed preserved.

`Build-SyntheticPackage.ps1` creates a fresh owned build, freezes source inputs and pins generated MSI outside Gradle's replaceable output directory. `DesktopWindowsPackageFixture` seeds/verifies only an explicitly marked task profile and invented DPAPI values. `native_package_probe.py` accepts only the synthetic MSI identity, verifies native install/reopen, and uses a Job Object assigned before process resume to track launcher/JVM descendants; it sends WM_CLOSE only to its own window. Private installer logs and credential fingerprints stay local. No protection software is disabled, no prompt automatically approved, and an access failure alone never proves an antivirus cause.

JDK17 nested per-user program paths fail WiX ICE64 because intermediate directory cleanup is omitted. The separate single-level `<package>App` default retains ICE validation. Native MSI builds use a short, uniquely owned jpackage scratch and verbose diagnostics; WiX3 cannot open some generated paths at MAX_PATH. Python isolated mode and an explicit optimized-interpreter refusal keep MSI validation enabled.

`native_acceptance.py` checks the selected synthetic pair sequentially, including selected-root exclusion, Job drain, three unmanaged files, failure-after-removal rollback, old-version rejection and uninstall. Rollback requires both restored program bytes and product registration. A failure is retained and stops that sequence; the probe does not elevate, alter registry ACLs, dismiss protection prompts or infer antivirus cause. The explicit repair action accepts only the same retained synthetic MSI/receipt with absent related-product registration and the expected program identity. Failed repair is not acceptance.

Task-owned manual app-image and repair helpers must be exercised with the actual `WindowsPowerShell/v1.0/powershell.exe`, not only PowerShell 7. Current helpers use ASCII status/error literals, direct JSON-array assignment and framework SHA-256 stream hashing; all package identity/217-file length/hash/reparse checks remain. `-PreflightOnly` does not launch the application or installer. It proves neither successful application startup nor a human visual result: the later actual 1008 startup refusal is retained separately. See `docs/desktop/evidence/preview-continuous-20261025/manual-helper-winps51/` for the real parser/preflight failures and corrected native results.

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
