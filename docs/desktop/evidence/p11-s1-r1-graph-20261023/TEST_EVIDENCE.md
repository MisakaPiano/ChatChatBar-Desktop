# S1 R1 execution evidence — 2026-10-23 JST

Control: `3fc458135b68c5c03623d027bc3c1c5c0c06b594`, after safe feature fast-forward. JDK17 Temurin17.0.20.1, Windows. All tests are synthetic headless JVM tests; commands run from repository app/. No user archive/root/credential, protected user file, GUI, Android/ADB, Provider, upstream sync or installation.

Actual RED: **3 tests / 3 assertion failures / exit1**. Actual final GREEN: **8 suites / 95 tests / 0 failures / 0 errors / 0 skipped / exit0**. S1 expanded10→16; all other suite counts remain18+1+12+10+4+14+20. Original89 plus six new tests are executed in one current run. S1 child exit73/alive=false; tested source hashes verified unchanged after execution.

RED command, before production edits:

```powershell
.\gradlew.bat :desktopApp:test --tests '*MomentArchiveReadOnlyValidatorTest.R1*' --no-configuration-cache --console=plain
```

Raw authority: raw/red.log, red-exit.txt, red.xml, red-test-source.kt.txt and red-source-identity.json. Unknown-family inputs have valid independent official wire DTO metadata/inventory/hash and nested synthetic future fields. Tests assert S0 still reads and counts uninterpreted records, print only fixed state/code/encryption categories, and then assert S1 must block. The sender test asserts v1 missing CARD and SENDER must both be MISSING. Pre-fix observed states are recorded in XML system-out; no future field text is logged.

GREEN full affected command:

```powershell
.\gradlew.bat :desktopApp:test --tests '*MomentArchiveReadOnlyValidatorTest' --tests '*DesktopBackupReadOnlyInspectorTest' --tests '*DesktopReadOnlyBackupRedTest' --tests '*DesktopFullBackupArchiveTest' --tests '*DesktopFullBackupServiceTest' --tests '*DesktopFullBackupCommitJournalTest' --tests '*DesktopDataRootOwnershipTest' --tests '*DesktopStartupGateTest' --no-configuration-cache --console=plain
```

Each command redirects stdout/stderr to its own log and immediately records LASTEXITCODE. Final result authority: raw/green.log, green-exit.txt, final-xml/ and results.json. No previous result is added to the current run count.

Preserved tests retain original names and bodies. The added cases cover unknown extra/unknown-only families in v1/code84 and v2/code85/86, each plain/encrypted, known strict non-Moments types, v1 missing sender/card, v2 count-only ambiguity and explicit zero-selection, known/absent/conflicting sender identity, and legacy snapshot-only classification. The existing 89 tests retain media, authentication, integrity, cancellation, hard-exit and restore denial authority.

raw/fixtures/ retains actual R1 archive bytes, with lengths/SHA-256 in fixture-sha256.json. Encrypted inputs use the existing synthetic password, never real secrets. Original test isolation checks source and existing synthetic journal file inventory/SHA unchanged and no new files after each read. Extra fields cannot run tasks because the API has no runtime/writable capability; all denial flags remain false. Original production budgets and authentication path are unchanged.

Raw files are preserved byte-for-byte through local attributes. source-identity.json records current source lengths/SHA-256 and normalized Git blobs; boundary-identity.json and test-preserved-delta.json document untouched production chain and all preserved methods. evidence-sha256.json hashes other raw evidence and excludes itself. Source working line endings may differ after checkout; normalized Git blobs are also supplied.

Limit: S1 graph classification only. No full portability, credential-free arbitrary text, persistent seal, restore, scheduler or Task activation. S0 unknown-family format readability remains supported. v2 count-only UNKNOWN_SELECTION is deliberately unresolved, not evidence of a missing or unselected specific ID. OMITTED_BY_SCOPE continues to describe source policy, not proof an original image existed.

Credential evidence is compositional: unchanged S0 tests check model/embedding/settings/preferences category detection, the unchanged S1 knownCredentials guard blocks those categories, and the preserved S1 known-key test exercises that guard with AppSettings. This R1 does not add a new direct S1 matrix for each credential category. It makes no new credential-migration claim.
