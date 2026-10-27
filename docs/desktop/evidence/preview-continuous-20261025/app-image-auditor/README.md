# Repeatable read-only application-image audit

This checkpoint adds only Windows packaging inspection tooling and independent synthetic Python tests. No Kotlin, Gradle configuration, installer, data-root, SecretStore, Prompt or existing native probe was changed. It did not rebuild, install or launch the application or read a user backup.

## Actual executions

|Round|Actual Python tests|Failure events|Exit|Interpretation|
|---|---:|---:|---:|---|
|app-image-auditor-20261025|17|1|1|Fixture writer normalized the intended Windows backslash name; this was not an unsafe wire fixture.|
|app-image-auditor-r2-20261025|17|1|1|Raw name was written, but the fixture assertion used the reader's normalized `filename` view.|
|app-image-auditor-wire-red-20261025|17|2|1|Independent `orig_filename` assertion proved both raw names; the inspector actually accepted backslash and NUL cases. Two failing subcases of one method, not two extra tests.|
|app-image-auditor-final-20261025|17|0|0|Validate the original ZIP central-directory name before Windows normalization. All 17 methods pass.|

These are Python unittest results, not JUnit or an additional JVM regression run. Counts are not accumulated. Each invocation's command arguments, raw output, exit and source bytes are retained in `RAW_INDEX.json` (28 files). The wire RED has source snapshots but no separate `head-before.txt`; no missing historical identity was fabricated. Final runtime source snapshots match `SOURCE_IDENTITY.json` exactly.

## Existing 1009 image, independently observed

The actual command finished exit0: **217 files / 63 JARs / 21,161 class entries**. Its complete file set, lengths and hashes match the original frozen 1009 build inventory. Identity remains `0.1.0-preview.1009`, Windows `0.1.1009`, synthetic, source `7b3e1534422dd6120e1e23304b679e03da884c55`. This is an existing image, not a newly built candidate from this tooling commit. Reports include program-file hashes only, no private archive or credential witnesses.

The audit reads every app JAR to EOF for CRC, checks the exact single build identity and complete launcher classpath, rejects known private names/companion classes and reparse paths, and observes input file metadata before/after reads. Output is an exclusive new file outside the image; failure messages contain fixed categories. Synthetic tests cover actual fixture mutation, corrupt CRC, duplicate wire entries/identity fields, malformed paths, resource bounds, incomplete classpath, known private content and no-overwrite/error redaction.

Limits apply to the program image only: 5,000 files, 256 MiB/file, 2 GiB total files/JAR expansion and 200,000 JAR entries. Official backup limits are unchanged. Class count means ZIP entries ending in `.class`, not distinct JVM classes.

## Boundaries

- No claim of universal secret detection, publisher/source attestation, signatures, malicious-writer exclusion, OS locks or recursively verified nested JARs. Continue the existing private-data and final build review.
- No new native alias or installer test was run. Existing MSI rollback/association FAIL, human Data-page check and physical Android limitations remain.
- The separate real large-root relocation continues with its frozen `98746a73` production runtime; this tooling did not start another import or change that runtime.
