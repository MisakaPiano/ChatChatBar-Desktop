# SaveSlot historical recording → whole-backup continuity

Actual synthetic RED: independent JVM import/restore/reopen/export succeeded, then a new complete-root check failed with `RECORDING_VERSION_UNRESOLVED_WITHOUT_SOURCE_CUSTODY`. New voice IDs created by session restore lacked whole-backup provenance. The checker was not relaxed.

`DesktopSlotHistoricalVoiceCustody` retains the exact immutable original package and explicit restored voice bindings in a distinct index kind under the existing historical-voice domain. Session journal publication is append-only for these exact hash-named paths; old custody cannot be overwritten or deleted through that path admission. The original includes raw source records and independent audio bytes. Validation checks exact index fields/file set, source length/hash and strict package structure, original owner/version, target bindings and source media hash. Whole-root validation still requires a matching live MP3 hash/length and rejects arbitrary unknown versions. Library-slot or live-audio deletion does not remove original custody. No official Entity, Prompt, Android restore policy, SecretStore or source authentication changes.

The restored graph plus original-package custody retains its existing combined 256 MiB preparation limit and session-journal space checks. Over-budget input refuses with current session/source intact. Legacy zero length is an undeclared value; its original stays exact while actual decoded media remains bounded. Positive declared lengths remain exact. v8 audio is resolved by its official resource identity, not a guessed `.mp3` filename extension.

| Actual round | Suites | Tests | Failures/errors/skips | Exit |
|---|---:|---:|---|---:|
| backup-graph RED |1|1|1 / 0 / 0|1|
| first fix |4|31|0 / 0 / 0|0|
| lifecycle/cancel extension |5|37|0 / 0 / 0|0|
| delivery extension |4|40|1 / 0 / 0|1|
| legacy zero-length RED |1|1|1 / 0 / 0|1|
| final affected regression |8|73|0 / 0 / 0|0|

The 40-test failure was an independent legacy fixture's incorrect resource-map key; correcting it to the official full stored path exposed the separate new custody helper's undeclared-zero rejection. Both actual failures remain. One final JUnit method contains eight schema/extension variants; these are not eight extra JUnit tests. The first 5-suite request also contained a mismatched Mapping test filter; only the five XML suites actually executed are reported. The final eight-suite run uses the correct package and includes Mapping/Service/Startup/Journal/SaveSlot/MergeRoot regressions. Counts are per run, never cumulative.

Actual child JVM tests cover retention outside current-version playback, complete-root admission, changed version/body/source package refusal, unexpected file refusal, original library deletion, modern whole-backup export/authenticated restaging/remapping, byte-exact returned custody, live audio deletion and cancellation before index publication. Test-before-source snapshots, raw JUnit, exact commands and exit codes are retained. No GUI, physical Android, Provider or real user data was used by these tests.

Native app-image and authorized real-source aggregate observations are separate at `../windows-app-image-1004/`; their test counts are not added here. Windows Installer rollback remains unresolved. No P11/Preview PASS or release claim.
