# Desktop Windows installer identity

`app/desktopApp/build.gradle.kts` separates the user-visible Desktop semantic version from the Windows three-number installer version. Windows ignores a fourth version field and cannot order Preview suffixes: [Microsoft ProductVersion](https://learn.microsoft.com/en-us/windows/win32/msi/productversion), [Compose native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html).

Every EXE/MSI packaging task requires an explicit `desktopInstallerVersion` in `0..255.0..255.0..65535` with no leading zeroes. Supply the previously distributed numeric version as `desktopPreviousInstallerVersion` for an upgrade; equal or lower versions reject. The receipt records whether a previous version was supplied; omission does not prove upgrade eligibility. Stable releases must continue increasing this numeric identity even when their semantic version loses the Preview suffix.

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

`desktop` and `synthetic` have distinct fixed package names and upgrade UUIDs; the latter is `ChatChatBarDesktopSynthetic`. Both use per-user installation. No Studio product identity, trust root, signing service, DPAPI configuration or updater dependency is used. Native identity isolation alone does not isolate application data: runtime tests must also explicitly select task-owned data/bootstrap/LOCALAPPDATA directories and must not use the ordinary installation or credential root.

`verifyDesktopInstallerIdentity` writes `build/reports/desktop-installer/identity.properties` only on successful validation; do not use a receipt left by an older invocation to certify a failed build. `desktop-build.properties` also records installer assignment/profile alongside the application version and actual Git commit. An app-image without assigned installer identity is allowed for development and is not an upgrade-tested installer.

Offline integration checks, without compiling or running an installer:

```powershell
python tools/desktop/windows/verify_installer_identity.py --evidence app/desktopApp/build/preview-continuous-private-20261025/installer-identity-UNIQUE
```

This verifies the real Gradle task, not a duplicate Python implementation of version comparison. Raw commands, exit codes, receipts and build script snapshot are retained. First install, real A→B upgrade, in-use files, rollback, normal exit, data/credential retention and uninstall remain independent native tests. A verified identity receipt does not prove any of those results or authorize cleanup.
