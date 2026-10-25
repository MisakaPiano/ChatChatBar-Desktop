# Complete Desktop headless regression

Actual JDK 17 offline execution of `:desktopApp:test`: **247 suites / 2,156 tests: 2,139 PASS, 17 SKIPPED, 0 failures/errors, exit 0**. Counts come from all 247 retained JUnit XML files, not accumulated checkpoints. The earlier 2,150-test RED remains unchanged in integrated-oracle-correction.

Source identity is HEAD `5b8db3668302869952c790cb656b9c6fff31ca1d` plus the retained pre-run source snapshots. The regeneration and bounded native long-path fixes are included. The subsequent merge-progress UI change was not compiled into this execution and has its own affected regression; this broad result is not represented as a rerun after that later change.

Fourteen actual OS-window/Robot cases now explicitly require a graphical desktop instead of failing inside a headless JVM. Their native assertions remain intact. The mixed current-image/design suites skip only their native-window case; the COM dialog test that never shows a window still runs. Three existing native-opt-in cases in DocumentIndexTransactionInspector, FreshRootAuthority and IndexStartupPreflight also skip. **These 17 skips are not native UI, DPAPI, installer, reboot or power-loss PASS.**

The worker uses a new marked synthetic profile for LOCALAPPDATA, APPDATA and user.home. The ordinary short temporary parent remains, with unique fixture-owned test directories. Public evidence deliberately excludes that profile, its private pointer, runtime files and thread samples; the exact test-only init script, commands, source snapshots, output and fresh XML are retained. No real archive, user root, actual secret, paid Provider or Studio test result is part of this execution.

The prerequisites do not close the existing native MSI rollback registration failure. Current native installation and visual acceptance are tracked separately in PREVIEW_IMPLEMENTATION_PROGRESS.md.
