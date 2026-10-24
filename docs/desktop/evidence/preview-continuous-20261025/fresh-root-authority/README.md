# Fresh import-root activation and protected namespace

Actual execution: 5 suites / 44 tests, failures/errors/skips=0, exit0, JDK17 on Windows. CCB_PREVIEW_NATIVE_DPAPI=1 explicitly enabled the native test; that test executed (not skipped). All secret values were invented fixtures.

The production startup journal and Windows credential resolver recognize an owned new import root's permanent v2 activation intent. PREPARING, corrupt/missing proof, changed genesis data/key blobs, unavailable protection and cloned native root identity refuse activation. A fresh credential namespace never overwrites existing/global credentials. Protected context is bound to native directory identity; existing legacy roots retain their resolver behavior. Genesis hashes are checked before the first activation, then ordinary user edits remain possible.

The native test ran real current-profile DPAPI with fake NAI/Fish values, task-owned local-app-data and data directories; it reopened protected values through the namespace resolver. No real backup, user secret, daily credential directory, DPAPI blob or native identity payload is included in this evidence. Do not equate this with power loss, Windows reboot, another account or an old installer/EXE test.

Fake-protector fault tests are separate synthetic evidence. They include a second-key failure, protected-context corruption, changed files, target cloning and post-activation normal updates. Source and test snapshots, raw JUnit/command/log/exit follow. This is an incremental production checkpoint, not completion of the merge/import UI or Preview. No Project preapproval is required for the next implementation work.
