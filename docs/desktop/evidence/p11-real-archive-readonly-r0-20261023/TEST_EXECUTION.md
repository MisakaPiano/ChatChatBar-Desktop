# Actual R0 execution — synthetic Windows/JDK17 only

Commands are preserved in Run-Synthetic.ps1 and each raw round's arguments.json/gradle.log/exit.txt. JDK identity is in jdk.log. Gradle ran offline/headless, one worker, no daemon/build cache/configuration cache/parallelism, from app/. No GUI, ADB, emulator, real archive, paid API, SecretStore or application root was used.

| Execution | Real result | Raw evidence |
|---|---|---|
| First JVM run | 7 suites /114 PASS /0 failure/error/skip; exit0; 1m29s | raw/first;20 new tool tests +94 existing tests |
| Final JVM run | 7 suites /117 PASS /0 failure/error/skip; exit0; 1m27s | raw/green;23 new tool tests +94 existing tests |
| First actual PowerShell launcher | Plain and encrypted/no-console launches failed with Java option splitting (exit1 each); inherited-options refusal exit2; outer script exit1 | raw/launcher; real RED retained, not a JUnit failure |
| Corrected actual PowerShell launcher | Plain exit0; encrypted/no-console exit2; inherited-options refusal exit2; outer script exit0; both PowerShell files parse | raw/launcher-green |

The initial JVM run was GREEN; no JVM RED was invented. The later launcher RED was discovered by invoking the real wrapper, and fixed by passing Java options through an explicitly quoted argument array. The corrected wrapper was then exercised directly. This wrapper-only correction did not change the final tested Kotlin. The final117 is not114+117 and does not include historical179/182 or launcher checks as extra JUnit tests.

The7 final suites are: Inspector18, credential plan17, census30, legacy read-only rejection1, Moments validator16, Archive12 and new tool23. Per-suite XML and raw/junit-summary.json retain exact numbers. Existing suites also cover duplicate JSON/ZIP, CRC, illegal UTF8, traversal, future/mismatched versions, bad scope, truncation, cancellation/hard child exit and v2 Prepared refusal. They do not represent new platform portability acceptance.

## R0 coverage and fact levels

| Requirement | New/inherited evidence | Result and limit |
|---|---|---|
| Exact official145/code84/v1 plain/password | Independent Official145 DTOs + pinned test-only codec invoked with version1; generated password char arrays | SYNTHETIC_RUNTIME_OBSERVED; no Inspector DTO generates inputs |
| Known synthetic credentials | NovelAI/Fish preferences; existing census six-family regression | Categories only; S1 known-credential block remains separate from S0 readable/G2B complete-known-subset |
| Password error/tag failure | New wrong-password/tag tests, inherited auth negatives | No census publication on failed validation; fixed stage output; entered char buffers cleared |
| No masked console | Injected null, real child JVM with pipes, actual wrapper invocation | REFUSED_MASKED_CONSOLE_REQUIRED_OR_CANCELLED; no stdin/env/password-file fallback |
| Human masked console | Code uses System.console.readPassword; no real interactive input executed | NOT_TESTED; future explicit local human check needed; stop if unavailable |
| File admission | Exact argument arity, absolute local drive, extension, regular file, ancestor reparse checks, missing/directory/ADS/UNC/relative rejection | SYNTHETIC_RUNTIME_OBSERVED, including real synthetic NTFS junction; not privileged-adversary proof |
| Source size | Sparse synthetic file64MiB+1 refused before payload/prompt; limits reused from Inspector | SYNTHETIC_RUNTIME_OBSERVED; no larger-archive/maximum-memory stress claim |
| Concurrent source mutation | New Windows no-share-write/delete read guard test denies a write during password input, releases afterward | SYNTHETIC_RUNTIME_OBSERVED for file write; directory replacement/privileged races NOT_TESTED |
| v2 | New readable v2/code86 case + S0 v1/v2 regression | S0 readable, census INCOMPLETE/SOURCE_VERSION, RESTORE_SUPPORTED=false |
| Character/chat/SaveSlot/media | New mixed fixture includes current known Character/Chat/SaveSlot JSON, opaque SaveSlot package bytes and image/audio bytes | S0 readable, census incomplete; **not** proof that package/image/audio bytes are decodable or restorable |
| Unknown Entity/Moments schema | New unknown family and invalid Moments payloads | S0 readable while S1 blocked and census incomplete; not mislabeled whole archive corruption |
| Final inventory failure | New bad-hash case + inherited post-auth failure tests | S0 rejected; S1/G2B not run; no early trusted census callback |
| Source/existing evidence | Each owned synthetic directory compares complete file bytes before/after; private source digest guards | Source and synthetic journal unchanged; no plaintext ZIP or data-root creation by reader |
| Prompt exception/short password/cancel | New fixed-error/short-input/prompt-cancel cases; existing Inspector/census cancellation regressions | Sanitized failure and owned buffer cleanup; new prompt-cancel occurs before handoff, not a hard-kill erasure proof |
| Reporting | Fixed line grammar + source/name/fake-key/digest exclusions; raw/redaction-audit.json | Known sentinel audit and code review only; arbitrary heap/OS confidentiality not proven |
| Restore gates | Archive/legacy rejection/plan tests and unchanged3 main trees | No official restore/SecretStore/startup/GUI wiring; CREDENTIAL_APPROVAL_REQUIRED unchanged |

## Retained identity and evidence rules

raw/first/source and raw/green/source preserve the exact Kotlin/init files used in each JVM run. The wrapper snapshots in raw/green/source predate the later launcher-only fix; **raw/launcher-green/source/Invoke-ReadOnlyTool.ps1** is the final launcher actually exercised. raw/tooling-identities.json identifies all final tooling bytes/Git-filtered blobs. No earlier snapshot has been overwritten to conceal this sequence.

Raw synthetic archives are retained under each round's synthetic directory, with a synthetic unchanged-journal sentinel. Passwords for newly generated encrypted fixtures are not persisted, printed, passed through Gradle options/environment or written into XML. Re-running generates fresh passwords/archives; retained encrypted fixture hashes prove byte identity, not recoverable password custody. Plain synthetic fixtures intentionally contain fake data and may be public. Earlier test helpers retain their pre-existing literal fake passwords; those are not the reader's credential input path.

Audit-Synthetic.py records selected source/official Git blobs, main trees, final tooling, per-file SHA/length and raw JUnit totals. It scans only owned diagnostic/report material for known synthetic sentinels, intentionally excluding fake-valued fixtures/source. No broad drive/user-root scan is performed. All prior historical RED/XML/acceptances remain unchanged.
