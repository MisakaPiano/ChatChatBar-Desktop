# R1 execution and source audit

All commands ran from `H:\ChatChatBar-Desktop\app`. Gradle cache access required the normal sandbox escalation; only the authorized headless JVM tasks were run. Standard output/error were redirected to the listed raw log, and `$LASTEXITCODE` immediately saved.

## Actual commands and results

RED command (new exact legacy oracle against the old fixture):

```powershell
.\gradlew.bat :desktopApp:test --tests '*DesktopBackupReadOnlyInspectorTest.R1 official 145 positive fixture has exact legacy fields and strict decoding' --no-configuration-cache --console=plain
```

Exit 1. `raw/red.log`, `raw/red-exit.txt`, `raw/red.xml`, `raw/red-test-source.kt.txt`. The field assertion stops at Manifest, so the captured RED itself proves the extra scope field; the old Preferences extra field is established by the old generator/source and is separately guarded by final strict-decoder negative controls.

Final command:

```powershell
.\gradlew.bat :desktopApp:test --tests '*DesktopBackupReadOnlyInspectorTest' --tests '*DesktopReadOnlyBackupRedTest' --no-configuration-cache --console=plain
```

Exit 0, BUILD SUCCESSFUL. `raw/green.log`, `raw/green-exit.txt`, `raw/final-xml/`. JUnit: Inspector 18; restore-rejection suite 1; total 19, no failures/errors/skips. The run also preserves the existing isolated child-JVM exit-73, cancellation, resource budget, immutable synthetic source/journal, authentication, malformed input and restore-blocking tests.

No full GUI/Android/release tasks were run. Prior 81-regression authority: `../p11-s0-readonly-20261022/TEST_EVIDENCE.md` and its original raw regression XML. Production trees and working source diffs are unchanged; see `raw/production-identity.json`.

## Fixed source authorities

Path for transport models: `app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupModels.kt`.

| Official revision | Transport basis |
|---|---|
| 1.4.5 `1198a13cf3008de1358b8a69aa5e40b64797c8d9` | AppBackupSummary: nine fields, no scope. BackupPreferences: five fields, no retainedPreferences. Strict JSON, defaults encoded. |
| 1.4.6 `9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d` | Modern scope and preferences omission annotations. |
| 1.4.7 `b4fa75d296680f777be095f3121ad0c4db979f17` | Same AppBackupModels Git blob as 1.4.6; checked with an empty fixed-commit diff. |

The independent wire models copy serialization declarations and defaults, renamed and stripped of nonserialization application helpers. Session-choice booleans follow `BackupExportSelection.kt` at the modern fixed revisions. DTOs are intentionally independent of production Inspector models and `backupJson`. Official source snapshots and Git blob identities are retained in raw; snapshots are text captures, while the Git blob IDs identify the exact original bytes.

The existing `Official147FixtureCodec` remains unchanged and is the pinned upstream test codec used to produce/decrypt envelopes. This is not a separate production implementation. No external source was fetched or upstream baseline updated.

## Added test oracles

1. Exact v1/code84 manifest/preferences key sets for plain/encrypted archives; strict official145-shaped decoding; unknown later-version fields rejected.
2. Actual v2/code85/86 plain/encrypted JSON omits empty sessionChoices, null sessionDefaultChoice and empty retainedPreferences. Null modern scope is omitted in version1 modern-code fixtures. Missing momentsImages decodes true; absent choices/default/preferences receive official defaults.
3. Nondefault sessionChoices, default-choice object and retainedPreferences are emitted explicitly; choice default booleans decode true, and all three boolean fields serialize in a nonnull default-choice object. Existing Inspector retainedPreferences rejection is preserved.

All previous 15 Inspector test names plus the unchanged restore-rejection test are retained. The diff changes their fixture types to independent equivalents without deleting existing safety assertions.

## Raw fixture evidence

`raw/fixtures/` contains only empty, credential-free synthetic transport positives and category-only nondefault preferences. Encrypted fixture password is the test-only literal `synthetic-password`; no real password or credential was used. The categories-only retainedPreferences JSON is a serialization sample, not an accepted Inspector positive.

Archive capture occurs inside the independent-oracle tests, after extraction by the unchanged test codec. Six archives and their Manifest/Preferences/inventory bytes are saved. Each `.fields.txt` lists actual top-level JSON keys; nested field presence is asserted by tests and visible in JSON. Inventory is empty because these authority fixtures intentionally contain no entities/media. Existing synthetic entity/media negative and semantics tests run separately.

`raw/fixture-sha256.json` records actual archive/JSON/field-list byte lengths and SHA-256. Encryption randomness and ZIP timestamps mean a later run need not reproduce archive hashes; preserved bytes identify this run. `raw/test-source-sha256.json` identifies the tested sources. `raw/evidence-sha256.json` covers other raw evidence and excludes itself.
