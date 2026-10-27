# G2-A strict read-only inspector candidate

**G2-A CODEX ENGINEERING CANDIDATE / PROJECT REVIEW PENDING** — 2026-10-17.

Frozen parent `9908d4d8a16cb14860f27356e3ce83d4bdc95fd6`; source `e362eeac82864a990febc1bb3bb8619447da3f29`. Seven new Kotlin files, **+1747/-0**. Formal validated baseline remains 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`.

No Main/G0/G1B/AppContainer/Storage/Entity/Repository/Gradle/Prompt/CURRENT/parity changes. No new dependency, production dispatch/domain creation, Writer, recovery or paid API. **Every result, including NO_DOMAIN, keeps businessAdmission=BLOCKED.** Matching root/config/writer claims are NOT YET TRUSTED; missing production proofs cannot be cleared by fixtures.

## Actual execution

Exact argument arrays, original UTC timestamps and exit codes: `*-stage.json`; full unedited logs: corresponding `.log`. All Gradle calls use offline, no-daemon, no-parallel, max-workers=1, priority=low, in-process Kotlin, no configuration cache, existing 2GB heap. No clean/rerun-tasks. Toolchain: Eclipse Adoptium **17.0.20.1**, Gradle **9.1.0**, Windows 10; see environment.json.

| Stage | Exit | Derived UTC duration seconds |
| --- | --- | --- |
| 01-native-read | 0 | 77.4495724 |
| 02-inspector | 1 | 38.2542832 |
| 03-inspector | 1 | 36.9136364 |
| 04-inspector | 1 | 43.5565664 |
| 05-inspector | 0 | 73.5925329 |
| 06-inspector | 0 | 55.9072911 |
| 07-inspector | 0 | 54.6540889 |
| 08-inspector | 0 | 119.6321985 |
| 09-focused-regression | 0 | 63.9919483 |
| 10-final-focused | 0 | 81.3036294 |
| 11-final-focused | 0 | 61.5383148 |
| 12-final-evidence | 0 | 56.3224413 |

Historical RED remains: stage02 public test methods inferred internal result return types; stage03 local `java` shadowed `java.nio`; stage04 two fresh child tests omitted kotlin-test and actually exited 1. The fixture now uses standard check, retaining rejection semantics without the extra test-runtime dependency. Those two early failed child PIDs/start times were not archived before the assertion; XML preserves exit=1 and NoClassDefFoundError. No missing PID was reconstructed. Later/final runs contain actual child identities. Stages05–11 are intermediate passing snapshots, not additional final coverage. Evidence aggregation's timestamp-parser error is also retained, with raw timestamps untouched.

Desktop main/test actually compiled in stage11 for final production bytes; stage12 main is **UP-TO-DATE**, test compile and all filtered tests actually execute. SharedCore/fixtures are UP-TO-DATE, not claimed recompiled. Android, full regression, GUI/EXE, packaging and Provider/network tests: **NOT RUN**.

## Final actual raw XML (12-junit only)

**10 suites / 179 tests: 177 reported PASS, 2 SKIPPED, 0 failures/errors.** New inspector/native/process: 3 suites / 43 tests, 42 reported PASS, 1 symlink SKIPPED. Existing startup/ownership/Portable/lifecycle: 7 suites / 136 tests, 135 reported PASS, 1 symlink SKIPPED. Historical totals are not added.

| Suite | Tests | Failures | Errors | Skips |
| --- | --- | --- | --- | --- |
| DesktopApplicationLifecycleTest | 39 | 0 | 0 | 0 |
| DesktopDataDirectoryTest | 13 | 0 | 0 | 0 |
| DesktopDataRootOwnershipTest | 14 | 0 | 0 | 0 |
| DesktopDocumentIndexReadEvidenceTest | 1 | 0 | 0 | 0 |
| DesktopDocumentIndexTransactionInspectorProcessTest | 3 | 0 | 0 | 0 |
| DesktopDocumentIndexTransactionInspectorTest | 39 | 0 | 0 | 1 |
| DesktopIndexStartupPreflightTest | 19 | 0 | 0 | 1 |
| DesktopPortableRootTest | 16 | 0 | 0 | 0 |
| DesktopProcessLifecycleTest | 15 | 0 | 0 | 0 |
| DesktopStartupGateTest | 20 | 0 | 0 | 0 |

Both skips are unavailable real symlink fixtures. Existing **four symlink fixture early-return branches remain NOT TESTED**, not extra JUnit skips or semantic PASS. Repeated runs do not add platform coverage; the legacy G0 event schema does not emit an individual row for every early return.

Real synthetic junction, external hardlink refusal, READ_DATA ACL denial/restoration and normal/refusal/cancellation child exit plus independent lock reacquisition executed. Synthetic post-close reporting failure does not prove a real Windows CloseHandle=false was reproduced.

## Traceability to 134 F01–F12

Initial method-name F prefixes are fixture labels; the authoritative mapping is below.

- F01: actual ownership; absent/empty/header-only domain, BLOCKED.
- F02/F06: first generation, second pre-existing doc and reindexing; raw byte evidence, archived predecessor graph; fork/duplicate/orphan refusals. Historical card/source are not forced equal to today's live bytes.
- F03/F05: pending missing old/head is INCOMPLETE per F03; completed archived missing predecessor is HEAD_HISTORY_INCONSISTENT per F05. This diagnostic distinction was corrected during final traceability; both reject without writing. Missing intent/header is incomplete, not a claim dependent payload is validated.
- F04/F05: sealed pending, old-card/new-vector mixture, active marker with/without HEAD is never completed business admission.
- F07/F08: strict UTF-8/JSON, escaped duplicates/ASCII case collisions, Unicode data keys, unknown Entity/control keys, missing/default existence, raw hash/whitespace drift, LF/line limits, extra vector/ID/HEAD/source/live corruption or loss.
- F09: the SAME native data handle supplies file ID/link count, bytes and post-read stamp. Actual hardlink/junction rejection, deterministic mutation and cancellation. Full Windows TOCTOU/ABA is not claimed.
- F10: packed paths/stamps/hashes/rows, checked arithmetic, exact reduced entries/bytes/double-read bounds and one-less refusal, 8230-vs-8192 calculation, arena precharge, aggregate card/doc/vector files, 1200 unrelated vectors. Compact metadata arena <=64MiB is NOT total JVM/native/decoder heap; bounded transient Entity/source/control/native/path workspaces also exist. Absolute allocation peak UNKNOWN; no deliberate OOM fixture.
- F11: real file ACL refusal, synthetic post-close reporting fault, cancellation/close/final recheck. Real CloseHandle=false, every ACL case and physical I/O deadlines remain NOT TESTED/NOT PROVEN.
- F12: final normal/refusal/cancel in fresh 128MiB child JVMs; actual PID/start/executable SHA/direct parent/exit. Three subsequent independent LOCK processes reacquire the corresponding roots. No forced termination or lock removal; watchdog is not proof I/O has drained.

## Original evidence and zero writes

source-manifest.json contains exact worktree SHA-256/length and Git blobs; 7/7 match the source commit. Source commit parent is fixed above. No accepted historical evidence was overwritten.

zero-write-events.jsonl retains historical summary-only events and **66 final full before/after inventories**. Full records contain all relative synthetic entries, sizes, byte SHA-256 and hashed Windows volume/file-ID tuple. Values: directory `DIRECTORY:identityHash`; reserved lock `OWNERSHIP:identityHash:size` (content not read); ordinary file `identityHash:size:sha256`. Root/ownership setup precedes baseline. These independent witnesses use the accepted G0 identity helper plus byte snapshots; do not confuse them with the inspector's same-read-handle check or claim ABA exclusion. Intentional concurrent fixture writers are separate from zero-write equality claims. Fixtures/evidence are not automatically deleted.

process-inventories.jsonl has **3** full before/after inventories spanning child exit and independent lock reacquisition. process-events.jsonl retains all successful historical runs; its last six events are the final 3 inspections / 3 lock checks, each exit 0/alive=false. Final child stdout originals are separate logs. Private root paths are not published.

g0-events is the unchanged legacy preflight instrumentation across repeated regressions. It does not mean all Desktop business startup is zero-write. Its platform-limit records and four known early-return branches remain distinct.

## Resources and remaining limits

12 system samples: minimum Available RAM **6.462 GiB**, minimum Commit headroom **6.693 GiB**, maximum Pages/sec **30194**. Sample extrema are not absolute peaks or build attribution (the largest paging sample had Java count zero). No OOM or disk-write failure occurred; final observed Java count zero. Absolute JVM/native peaks UNKNOWN; frontend/Chrome/NAI responsiveness NOT TESTED. Those applications were not operated/terminated. Workspace free space was sampled at 2.31GiB, no cleanup performed.

Remaining NOT TESTED/NOT PROVEN: real symlinks, real 8.3 aliases, held-root replacement, full Windows TOCTOU, all ACL paths, actual failed CloseHandle, blocking-I/O physical deadline and power-loss durability. No production authority/admission, G2-B/G2-C/Writer/recovery, complete P9 or Public Preview advancement.

All copied originals are byte-identical (raw-copy-provenance.json). Local .gitattributes disables conversion only here so committed raw XML/log hashes remain valid. Manifest covers every OTHER evidence file, including this README/attributes; its hash is reported separately. No Secrets, actual user data, JPEG bytes or private root paths included. Three historical external JPEGs remain untracked/untouched; whole worktree is not claimed clean.

G2-A CODEX ENGINEERING CANDIDATE / PROJECT REVIEW PENDING. Stop; no next phase.

Publication audit: 30 raw logs were initially omitted by the existing *.log ignore rule; they were added explicitly without modifying ignore rules. The first whitespace check flagged raw CRLF. Evidence-local cr-at-eol recognition preserves raw bytes and the remaining whitespace checks. Original diagnostic is preserved locally with hash in publication-audit.json. This changes no raw XML/log or source file.

Immutable console captures: Gradle emits a real trailing space in its daemon-status line. Raw *.log captures are Git binary data artifacts, retained byte-identical and SHA-256 verified, not whitespace-normalized. Source whitespace checks passed strictly; XML/JSON/Markdown retain text checks with CRLF recognized. Publication audit records original diagnostic hashes and the later helper UTF-8 decoding correction.
