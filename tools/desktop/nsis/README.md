# Desktop NSIS Preview packaging

`build_installer.py` audits an ordinary Compose/jpackage app-image and builds a current-user NSIS EXE. `Launcher.cs` is the stable entry point and install lifecycle helper. This is Desktop code; it does not embed Studio update services, identities, endpoints or trust roots. The design references Studio's frozen `a1202e24` implementation and `e74f7a2` handoff; its test results are not Desktop results.

From `app/`, with JDK17 and a Unicode NSIS compiler:

```powershell
.\gradlew.bat '-PdesktopVersion=0.1.0-preview.1201' '-PdesktopInstallerVersion=0.1.1201' '-PdesktopNsisCompiler=C:\tools\nsis\makensis.exe' '-PdesktopNsisOutput=I:\new-package-directory' :desktopApp:packageNsis
```

The output directory must be new. The builder accepts explicit individual audited files, never a wildcard payload. `package.json` binds the installer, app identity, launcher source and compiler. MSI tasks are independent historical tools and are never invoked here. `desktopNsisProfile=synthetic` changes only installer ownership, shell names and registration; the app-image remains the ordinary production application.

Default programs live under `%LOCALAPPDATA%\Programs\ChatChatBarDesktopNSIS`, with independent `versions/<version>-<commit>` directories. Data/bootstrap and Windows protected credentials keep their existing independent resolution. Installed versions reject portable markers; portable images retain their original route.

The installer refuses foreign roots, reparse paths, modified owned generated resources, occupied versions, running program leases and insufficient space. It never overlays a version. The production window sends a nonce/version/commit/PID acknowledgement only after normal startup, persistent initialization and display. Only then does the helper atomically replace `current.txt`. Shortcuts target the stable entry point. The program manifest stays open without write/delete sharing throughout the installed JVM lifetime; the native launcher and JVM start with the stable root as cwd.

Retirement requires an extraction-time manifest digest stored outside the retiring directory. Individual program files are hashed and marked for deletion through the same Windows handle, with parent directory handles held against replacement. Changed, unknown, busy or unowned resources remain, with `DEFERRED` in `lifecycle.log`; they do not block an already confirmed version. No recursive directory deletion discovers unknown contents. User data, credentials, backups and downloads outside the installer-owned program tree are outside cleanup authority.

The generated uninstaller and stable launcher have separate recorded digests. Uninstall checks all running leases first, retires owned program bytes, removes only matching shortcuts and matching current-user uninstall registration, and retains data and unknown files. Small receipts and the lifecycle log may remain. The user closes the running app normally before manual upgrade or uninstall; no process is killed.

`test_lifecycle.py` runs the real Windows helper against tiny native fake applications and isolated shell folders. It is not Desktop GUI, DPAPI or NSIS installation acceptance. Real app-image and installer lifecycle results belong in the Preview progress record with exact package identity and scope.
