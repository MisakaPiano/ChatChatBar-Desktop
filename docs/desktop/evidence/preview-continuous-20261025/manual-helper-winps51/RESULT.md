# Windows PowerShell manual-entrypoint correction

2026-10-25. Source/application HEAD at this investigation: `ad4ed1018805dd68a74d1b9b30622a6922f683a3`; packaged 1008 production source remains `2f75a4d3d4e5557d7bd513f2e9368a4ed5e8d1f7`.

The user reported `TerminatorExpectedAtEndOfString` at line 42 of the 1006 helper in Windows PowerShell. This was initially **USER-DIRECT**, then independently reproduced by the actual Windows PowerShell **5.1.19041.6456** parser against the unchanged file. Earlier PowerShell 7 preflight evidence is retained and did not prove compatibility with Windows PowerShell 5.1.

## Actual failures and correction

1. `round1/1006-before-parse-*`: actual parser exit 1. UTF-8 without BOM Chinese output was misdecoded and broke quoting. Replace helper status/error literals with ASCII; no business Prompt or application text changed.
2. After that correction, `round1/1006-preflight-*`: exit 1, `ISOLATED_PACKAGE_INVENTORY_MISMATCH`. In this shell, `@(Get-Content ... | ConvertFrom-Json)` wrapped the JSON array into one pipeline item. Direct assignment retains the real 217-entry array. No inventory/count/hash check was removed.
3. `r2` and `r3`: real preflight exit 1 because `Get-FileHash` was unavailable in those Python-launched Windows PowerShell processes; removing inherited PSModulePath did not resolve it. These failures remain verbatim. The final helpers use the framework SHA-256 implementation over an opened file stream, disposing both hash algorithm and stream. They compare against the same independently frozen SHA-256/length manifests, rather than skipping authentication.
4. `r4`: 1008 and administrator-repair original helpers also independently failed native parsing; original bytes and parser results are preserved. The same bounded fixes were applied to all three known task-owned helpers.

## Final actual results

| Explicit helper | Native parse | Native `-PreflightOnly` | Scope actually checked |
|---|---|---|---|
| 1006 | exit 0 | exit 0 | Exact owned image/source, all 217 files, length/SHA, reparse ancestors and task profile directories |
| 1008 | exit 0 | exit 0 | Same checks for independently frozen 1008 image |
| Administrator synthetic repair | exit 0 | exit 0 | Exact reviewed probe and synthetic MSI bytes/product identity; reports `installerExecuted=false`, `administratorToken=false` |

These are native subprocess checks, **not JUnit tests**. No Gradle/JVM suite was rerun for this helper-only correction. `r4/RESULT.json` binds the final helper bytes; `RAW_INDEX.json` binds 90 byte-exact raw files, including intermediate failures and all four executed driver sources. Existing historical raw evidence is unchanged. The three corresponding private helper files now contain the reviewed final bytes. No installer, elevation, cleanup, credential access or application launch occurred in this regression.

## Subsequent human result — not hidden by preflight

After the helper correction, the user actually started 1008 and reported the application dialog `CCB STARTUP_BLOCKED G0_CHANGED_DURING_CHECK`. This is **USER-DIRECT startup FAIL**, not visual acceptance. Preflight only certifies the script/package checks; it cannot certify application startup. The prior automated native launch was successful on its recorded execution and does not invalidate this later failure. Startup diagnosis continues separately on the same task-owned synthetic profile, preserving its files. Do not tell the user this profile is ready merely because these preflights passed.

Human administrator repair is still awaiting an actual execution result; “I will execute” is not PASS. Human Data/update/support-copy visual acceptance, MSI upgrade/rollback/uninstall, physical Android and complete Preview remain unproven by this record. No original source archives or private reports are copied into this public evidence.
