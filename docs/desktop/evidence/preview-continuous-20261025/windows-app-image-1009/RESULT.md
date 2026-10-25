# Corrected native 1009 against the retained failing profile

Packaged source: `7b3e1534422dd6120e1e23304b679e03da884c55`. Identity is `0.1.0-preview.1009`, Windows `0.1.1009`, synthetic profile. Actual offline app-image build exit 0; all 217 package files are frozen by length/SHA-256. The JAR audit checks CRC, private companion/witness names, archive/protected-file suffixes and password pattern absence. This is an internal app-image, not an installer or public release.

## The original failed sample was not reset

Both corrected default native cold starts use the **same** 1008 task profile, data directory, bootstrap, home and separate protected store that produced the reported `G0_CHANGED_DURING_CHECK`. No reseeding, directory replacement or copy-to-an-empty-profile bypass was used. The original TASK_OWNER and private baseline remain unchanged. New package identity is checked independently from that retained profile's older source marker; the marker is not falsely relabeled as 1009.

Actual sequence:

1. Independent companion verification of existing synthetic card/session/message/draft, image/bootstrap and three invented Windows-protected values: exit 0.
2. First default native 1009 cold start, selected-root lock exclusion, normal main window, WM_CLOSE, complete Job drain: exit 0; launcher exit 0, maximum two owned processes, none remaining.
3. Independent verification: exit 0.
4. Second independent default native cold start and same lifetime checks: exit 0, launcher exit 0, none remaining.
5. Independent verification: exit 0.

The two native captures were of their owned application windows only; the inspected capture shows the expected invented chat and retained draft. A subsequent human screenshot also showed the normal synthetic main window. This establishes **normal main window observed**, not human acceptance of the Data/update/support-copy page.

The external fixture JAR is unchanged from the earlier synthetic native test; its test source has no diff between the old and new production sources. Its verification uses the new immutable package JARs. The existing lock-only `busy` companion used by the native probe remains the frozen prior classpath; that ownership code has no source change in this correction. No private companion is bundled in the app.

## Manual entrypoint actually exercised

The new ASCII `Open-Isolated-Preview-1009.ps1` explicitly binds the new image to the retained old synthetic profile; it verifies the original profile marker, all 217 package files, reparse paths and unchanged known bootstrap before starting. Actual Windows PowerShell 5.1 `-PreflightOnly` exits 0. No installer, elevation or ordinary user root is involved.

An additional actual Windows PowerShell invocation of that exact helper opened the normal application and printed its success marker. The first helper-specific **test harness** nevertheless returned exit 1: it incorrectly treated its short-lived PowerShell host's exit as the end of all child startup. Its original source, launch record and `mainWindowObserved=false`/two remaining owned processes are retained. This is not rewritten into a passing test.

A bounded follow-up verified the exact host→launcher→JVM process chain and normal `ChatChatBar` window, confirmed selected-root exclusion using the same synthetic `busy` companion, sent normal close, and confirmed both native processes exited. The follow-up API did **not** return native exit codes (JSON null); do not call those exit codes zero. Independent post-close data/media/bootstrap/three-key verification exits 0. The separate two default native launches above do have observed launcher exit 0 and complete Job drain.

The manual launch therefore has observed successful normal startup and close despite the erroneous initial harness assumption. The human may now use this retained entrypoint for the still-pending Data-page check. No additional automatic window or human rerun is needed solely to hide the harness failure.

The first 1009 driver also refused before application launch because it expected an old 45-second timeout literal while the retained probe actually used 40 seconds. That driver/source failure remains under `driver-red/`; the corrected driver uses a bounded 180-second startup window. The startup safety validator is unchanged by that tooling timeout.

After these executions, all 217 package files again match the original manifest. `RAW_INDEX.json` binds 58 byte-exact selected raw files. Profiles, baseline credential fingerprints, DPAPI blobs, private runtime classpaths, captures and actual user archives remain local. These native subprocess results are **not additional JUnit tests**; the preceding 3-suite/83-test result remains 81 PASS/2 SKIP independently.

## Remaining work

The separately frozen 1007 large controlled-root relocation returned `MATERIALIZATION` refusal after mapped files exceeded the existing 4 GiB expanded budget. It did not reach successful publication or the independent reopen step. Diagnosis of materialized growth proceeds separately; this startup correction and these synthetic native successes do not conceal that refusal or remove the capacity limit.

Administrator synthetic MSI repair still needs its actual human result; installed upgrade/rollback/uninstall and physical Android remain separate. No complete P11, Gate B, D-038, Preview, signing, merge or publication PASS is declared.
