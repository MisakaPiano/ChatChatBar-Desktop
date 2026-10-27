# Finite executed test matrix

JDK17; offline headless Windows JVM; Gradle in `app/`, no daemon/build cache/configuration cache, one worker, bounded heaps. Exact arguments and logs are in each raw round. No GUI, ADB, actual SecretStore or Provider call.

| Round | Suites / tests | PASS / FAIL / ERROR / SKIP | Exit | Meaning |
|---|---:|---:|---|---|
| central-red |1/1|0/1/0/0|wrapper1; Gradle exit not captured|Original supplemental scanner accepts wrong central name; actual failure retained. PowerShell stderr handling ended wrapper early; do not invent Gradle exit |
| format-green |2/15|15/0/0/0|0|First strict scanner coverage |
| process-first |1/3|1/2/0/0|1|Session monotonic-order oracle omission |
| process-diagnostic |1/3|1/2/0/0|1|Same failure localized with safe step codes |
| process-green |1/3|3/0/0/0|0|Oracle corrected against existing repository behavior |
| final-focused |10/110|110/0/0/0|0|Intermediate only: source edited during build; not exact final-source evidence |
| final-frozen |10/111|111/0/0/0|0|Frozen pre-resource-identity-fix checkpoint |
| resource-id-red |1/1|0/1/0/0|1|Official extension-free resource identity counterexample |
| source-names-red |1/2|0/2/0/0|1|Logical colon name and repeated-resource/reference-count counterexamples |
| compatibility-final |11/113|113/0/0/0|0|Corrected names/resource semantics and process comparisons |
| diagnostic-final |11/113|113/0/0/0|0|Final tool: fixed refusal categories/limits, no changed admission standard |
| nested-classification-final |11/113|113/0/0/0|0|Final submitted tool: distinguish nested type/count/media/version refusal without raw values or relaxed validation |

Four prepare-only rounds (`prepare-tool`, `prepare-diagnostic`, `prepare-name-diagnostic`, `prepare-compatible`) each exit0, **zero tests**. They are not JUnit passes. Historical R0, T0/T1, G2A/B and181 totals are not added.

Final113 =27 new LR2 tests +86 existing focused regressions:

| Suite | Count |
|---|---:|
| LargeArchiveAdversarialTest |1|
| LargeArchiveValidationTest |18|
| Lr2ResourceIdentityTest |2|
| SlotProcessProbeTest |3|
| CaOwnershipPreflightTest |3|
| DesktopBackupReadOnlyInspectorTest |18|
| MomentArchiveReadOnlyValidatorTest |16|
| DesktopSaveSlotInspectorTest |23|
| DesktopSaveSlotServiceTest |15|
| DesktopSessionCommitJournalTest |4|
| DesktopUpdateCheckerTest |10|

Coverage: independent code84/v1 and code86/v2 plain/password; fixed fake credentials; wrong password/late tag; central name/offset/CRC/length/count/trailer; ZIP64/split/symlink/duplicate/path/flags; split limits/ratio/cancel/close; duplicate JSON/invalid UTF-8/unknown Entity; manifest/inventory/scope/preferences; nested v8 invalid counts/resource names; missing owner/media;65MiB media with unchanged S0 budget. C-A checks exact raw bytes/three separate image edges/copies/insufficient capacity; process checks nonempty synthetic RAG, images, current Memory, independent reopen twice, post-commit halt71, target tamper refusal. Existing service regression includes its duplicate/rebind behavior; no claim that the real source was duplicated or rebound into a user root.

NOT TESTED: Windows reboot/power loss, mid-commit hard exit in the new probe, native GUI/installer/DPAPI, physical Android, all-domain semantic closure, arbitrary future schema, true cross-key transaction, full voice-anchor/derived Memory hash equivalence. The real source has zero RAG chunks, so its RAG comparison is empty-source evidence only.
