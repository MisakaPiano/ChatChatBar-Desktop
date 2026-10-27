# Synthetic helper correction: partial registration diagnosis

2026-10-26 JST. Base `ed24741bed865decfb0c2ee6a5e3c25285fbf7a6`; tested source/test Git blobs, disk SHA-256 and byte lengths are pinned in `raw/green-final/SOURCE_IDENTITY.json`. This checkpoint changes the Windows **test/diagnostic helper**, not Desktop Kotlin, the MSI payload, an installed product, or any backup/credential runtime.

## Problem and bounded change

The retained native failure is not closed: after old A was removed, the B synthetic fault caused rollback; restoring the UpgradeCodes association returned system1307 and was skipped. The later actually elevated attempt returned0 but retained an advertised/partial product state and executed `ProductPublishUpdate`, leaving the association missing. Raw native MSI/logs, Advertise/REINSTALL evidence and the original partial-repair results remain in their original private locations. `REVIEWED_DIAGNOSIS.json` is a category/line-number aggregate, **not a raw native log** and not a newly executed repair.

`repair-registration` now retains its exact MSI/receipt/safety, absent-association and program-version guards but ends in a fresh-label **diagnostic refusal**. It no longer constructs or invokes the unverified `REINSTALL=ALL/vomus` command. Only the explicit product and DefaultFeature are observed through read-only query APIs when that CLI is used in the future; these API boundaries were mocked in this checkpoint.

The fixed report distinguishes missing association with advertised/registered residue, unavailable observation, unknown states, identity conflict, an association changing during observation, an advertised product, consistent metadata and no registration reported. None is permission to repair; unknown is not absence. Reports whitelist fixed codes/integers/counts, omit raw exception values and declare `installerExecuted=false`, `repairAuthorized=false`, `owner1307Resolved=false`. Existing command/result/diagnosis labels cannot be overwritten.

The exact previous success conjunction—exit0, exactly the expected related ProductCode, and equal complete program-file maps—is retained as `assert_repair_success` and separately tested against failures. It is an **unconnected outcome oracle**, not proof that a recovery path exists. All current repair-registration outcomes refuse; no new native re-publication method was added. AST comparison verifies every other existing install, upgrade, uninstall, MSI identity, path, association and lifecycle function/class is unchanged. The optimized-interpreter admission guard also remains.

## Actual execution history

| Round | Result | Evidence meaning |
|---|---|---|
| `red` | 2 tests /2 FAIL /exit1 | Initial fixture forbade the Win32 path observer before it reached the intended installer boundary. Retained as a fixture-boundary failure, not claimed as a native installer attempt. |
| `red-installer-boundary` | 2 tests /2 FAIL /exit1 | The path observer is now explicitly mocked; unchanged old code reaches the forbidden `subprocess.run` sentinel for both partial and unavailable observations. No process was actually invoked. |
| Immediate post-fix checks | 2 PASS, then extended25 PASS | Actual turn-tool output only; not represented as separate retained XML or added to final totals. |
| `green-final` | **2 suites /28 PASS /0 failures/errors/skips /exit0** | Frozen final25 new test methods plus3 existing interpreter-admission regressions. Actual unittest log, result, exit, source snapshots and JUnit-compatible XML are retained. |

Subcases do not count as extra JUnit tests. The XML is emitted from the actual Python unittest result; these are not JVM/Gradle tests. No previous 99/150/179/182/2156 totals are accumulated here.

The new suite invents small non-MSI files and state snapshots. Native library/installer boundaries are forbidden or mocked; the reader test exposes only fake query functions. The3 existing regressions start Python help/import subprocesses only, including `-O`/`-OO` refusal. They never invoke msiexec. Cancellation, permission/read failure, malformed state values, identity/label conflicts, observation changes, redaction and unchanged fake files are covered. A synthetic all-matching oracle case is not native repair acceptance.

## Evidence and scope

`RAW_INDEX.json` binds19 selected raw synthetic files. Staged source blobs must match the pre-test source identities; raw file lengths/SHA must match the index. `STATIC_BOUNDARY_REVIEW.json` pins the one changed existing function and three new functions. Public selection excludes raw MSI databases/logs, SID/security descriptors, real archives, data, credentials, native protected blobs and screenshots. Known-pattern checks are not universal secret-absence proof. Historical raw files are not rewritten.

The source change invalidates any old script hash pin in the normal way; already executed one-shot helpers and retained native source snapshots remain untouched and must not be replayed or silently repinned. A future native mutation needs its own explicit action authority and validated strategy. No real registry observation, install/repair/uninstall, ACL/security change, GUI launch, build, source synchronization, merge, tag, Release or artifact upload occurred here.

The original owner/security-descriptor and effective-token reason for1307 remains unproven. No claim of antivirus causation or native rollback repair is made. This classification correction does not close MSI upgrade/rollback/occupied/downgrade/uninstall, P11, D-038 or Public Preview.

## Independent manual evidence update

`HUMAN_SCOPE_RECORD.json` records the **agreed1010 UI check only** as PASS_WITH_SCOPE. It is based on user-pasted diagnostics, user confirmation of normal close, and original coordinator inspection of user screenshots—not this thread independently inspecting pixels or rechecking processes. Version/commit, diagnostic copy and the shown top merge/backup/version areas are confirmed. No repeat launch or UI fix was performed. Network update stays NOT_CHECKED; actual import/export, installer upgrade and all window sizes are outside this manual result. The product remains the original1010/946c3791 artifact; Python helper changes do not rebuild or relabel it.
