# Exact test/source/commit ledger

| Checkpoint | Identity / scope |
|---|---|
| Authorized start |06e5d8638d6fe89babc1f855d96e2ac1253258c9|
| LR5-A source audit / proposal |c093aabf0d4971c4baac9c2cd8ec08837d11216d; no tests/production schema claimed|
| Tested LR5-B source + XML/state artifacts |06a09dc5dffee04de91d9e87787d65a0fc36aa39; new test-only tools; existing app tree untouched|
| Final receipt |subsequent packet + four original logs excluded by generic *.log rule; exact final SHA in reply; no tested Kotlin edits|

JDK17 at runner-pinned Temurin17.0.20. Actual version output raw/jdk17-version.log. Offline/headless, one Gradle invocation/worker, Gradle1024MiB/test384MiB/child96MiB; explicit2 LR5 test class filters. Test-source Kotlin compilation also compiles unchanged existing test files but does not execute them; final XML proves only2 new suites. No GUI, Android or whole old-regression rerun warranted by unchanged app source.

Actual repository-root commands (complete Gradle argv captured in each round arguments.json):
- `& tools/desktop/interop/lr5/Run-Jvm.ps1 -Round first-protocol`
- `& tools/desktop/interop/lr5/Run-Jvm.ps1 -Round native-reader`
- `& tools/desktop/interop/lr5/Run-Jvm.ps1 -Round protocol-green`
- `python tools/desktop/interop/lr5/Audit-Evidence.py` metadata/index + bounded final synthetic fixture snapshots, exit0; not a JUnit execution.

| Round | suites/tests | PASS/FAIL/ERROR/SKIP | Exit | Actual child records |
|---|---:|---:|---:|---:|
| first-protocol |2/25|1/24/0/0|1|0; BasicFileAttributes.fileKey null caused fail-closed fixture preparation|
| native-reader |2/25|23/2/0/0|1|131;130 captured exits +1 early HOLD cleanup exit NOT_RECORDED|
| protocol-green |2/29|29/0/0/0|0|144/144 exits captured:0×47,2×45,3×2,71×50|

Actual protocol RED: malformed pre-publication receipt was overwritten and ALL_NEW returned. Fix validates existing receipt identity/choice/full claimed set BEFORE recovery mutation; invalid receipt or reverted participant now remains BLOCKED. Separate harness RED: Windows byte-locked empty control file could not be read by inventory. Fixed inventory explicitly marks lock-control bytes NOT_READ while retaining all payload/source hash assertions; final actual noncooperator write still succeeds and is subsequently blocked. No assertion softened to claim locks fence old writers. Earlier HOLD exit was not captured; its code is not guessed/backfilled.

Four added final JUnit methods cover receipt/reverted OLD, unknown WAL/alternate authority, independent exact copies and oversize input. Internal stage/case loops are not extra JUnit methods; final is29 (27 journal +2 wire), never25+25+29. Source snapshots are created BEFORE each run, original failed sources remain. Final2 Kotlin, runner/init/README and unchanged native dependency match protocol-green pre-run bytes; final Git blobs can be verified with RAW_INDEX. Existing source dependency identities separate current logical Git blob from working-byte SHA when line-ending policy applies.

Two non-test audit issues: an early staged-index read while git add was still running saw only prior files; it was discarded and repeated after completion. Byte-preserving CRLF was initially flagged as trailing whitespace; only local .gitattributes cr-at-eol recognition was adjusted, preserving tested bytes and real whitespace checks. Generic *.log ignore omitted four original logs from LR5-B commit; final receipt explicitly adds all four originals, without rewriting prior commits/evidence. These are not additional Gradle RED/GREEN executions.

Final static/self-check is Codex-attributed, not Project review. No actual native DPAPI, physical crash/reboot/power-loss, old executable, full credential absence/tombstones or multiple-transaction generation chain proved.
