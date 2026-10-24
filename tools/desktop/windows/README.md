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

`desktop` and `synthetic` have distinct fixed package names and upgrade UUIDs; the latter is `ChatChatBarDesktopSynthetic`. Both use per-user installation under `LOCALAPPDATA/Programs/<package>`, separate from the default data directory. Ordinary builds provide menu/desktop entrypoints; synthetic builds omit those personal-shell changes. No Studio product identity, trust root, signing service, DPAPI configuration or updater dependency is used. Native identity isolation alone does not isolate application data: runtime tests must also explicitly select task-owned data/bootstrap/LOCALAPPDATA directories and must not use the ordinary installation or credential root.

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
