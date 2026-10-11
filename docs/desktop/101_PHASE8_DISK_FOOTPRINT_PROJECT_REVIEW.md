# 101 — Phase 8 Full Local Disk Footprint and Historical Package Retention Review

**PROJECT READ-ONLY EVIDENCE REVIEW PASS / CAPACITY CAUSE ESTABLISHED / NO CLEANUP OR PROCESS TERMINATION AUTHORIZED BY THIS REVIEW**

Review date: **2026-10-12 JST**. Evidence: user-supplied Codex local read-only inventory of `H:\ChatChatBar-Desktop` following `100_DESKTOP_RESIDUAL_PROCESS_PROJECT_REVIEW.md`. Project cross-checked current GitHub P7 historical package evidence in `38_PHASE7_FINAL_REVIEW.md`, `43_PHASE7_R2_MANUAL_ACCEPTANCE.md`, `44_PHASE7_R2_REVIEW.md`, `71_PHASE7_SLICE_B1_ACCEPTANCE.md`, `78_PHASE7_WIN_ACCEPTANCE.md`, `85_PHASE7_FINAL_ONE_SESSION_PACKAGE_HANDOFF.md`, and `92_PHASE7_POST_MERGE_CLOSEOUT.md`. Project **cannot independently inspect the user's H: filesystem or OS process state**; local size/identity/CPU/I/O statements remain Codex-local evidence, not Project-executed checks.

## 1. Full-project distribution of reported logical bytes

- **9,569,861,448 bytes ≈ 8.91 GiB / 9.57 decimal GB**, 48,167 counted files; the scan conservatively excluded **52 protected/skipped directories** including real application data, profiles, backup/SecretStore, and link targets. It is a **partial but high-coverage inventory**, not a complete allocated disk usage report.
- `H:` free **616,079,360 bytes ≈ 587.54 MiB** at the later audit; no measured space has been reclaimed.
- **33 historical full distribution directories ≈ 7.58 GiB**, approximately **85.10%** of scanned bytes, dominant cause. `app/desktopApp/build` altogether ≈ **8,066.83 MiB**. Source/docs/.git are not the primary disk-pressure source; `.git` ≈ 216.25 MiB and source docs ≈ 11.33 MiB.
- Existing user-approved A+B **13 exact build/cache paths ≈ 707.32 logical MiB** remain `EXECUTION BLOCKED` by the current process-safety gate (see `96`, `98`, `99`, `100`). This number is reported potential, **not released disk space**.
- Build-local gate/evidence/reports/test-results ≈ 77.88 MiB reported; historical JUnit XML, Native/ScrollBy RED and SHA-256 manifests are **KEEP**. Protected real user/profile data not scanned, so these figures cannot be added to a supposed complete total.

## 2. High-yield candidate clusters, not deletion authorization

| Candidate | Reported logical size | Project evidence review / action |
| --- | ---: | --- |
| `app/desktopApp/build/phase7-final-distribution` | **507.29 MiB / 413 files** | Historical pre-R2 source `f8ca261`, `38` explicit replacement; **targeted internal payload/build-output split needed** before cold archive or cleanup |
| `app/desktopApp/build/phase7-r2-distribution` | **507.68 MiB / 413 files** | Historical pre-amendment R2 build/source `b88d408`; later R1 package superseded it (`44`) |
| `app/desktopApp/build/phase7-r2-reaudit-distribution` | **507.92 MiB / 413 files** | Historical pre-R1 re-audit package/source `1843dac`, superseded by `phase7-r2-r1-distribution` (`43`/`44`); retain gate records |
| **Three older ~507 MiB directories** | **1,522.89 MiB** | Highest priority **structural review, NOT delete**. Distinguish actual `compose/binaries/main/app/ChatChatBarDesktop` package from isolated Gradle build intermediates within same root. Estimate potential excess only after measured subpath classification. |
| Five B1 `phase7-b1-r2-{2,3,4,5,6}-distribution` | **1,031.15 MiB** | **HOLD**, distinct historical builds (R2-5/R2-6 user failures in `71`), exact packaged JARs not proven identical. Could be future off-volume cold-archive candidates with complete provenance, **not** safe to delete wholesale now. |
| Other accepted A/B1 R2-7/C/B1+C/B2/D/WIN/W3/APNG/one-session packages | mixed ≈206 MiB each | **KEEP pending exact archive planning**. The specific B2-R3 and WIN packages currently have reported running JVMs; do not access, move, compress, or delete their directories. |
| WiX local tool cache, `.git`, `desktopApp/build/libs`, real/synthetic live-data, actual user roots and SecretStore | individually classified in `96`/`98` | **HOLD or PROTECTED**, never an automatic disk-cleanup target. |

**Important Project source discovery:** `44_PHASE7_R2_REVIEW.md` documents a Gradle init-script override that sets **`:desktopApp`'s `buildDirectory` itself to `build/phase7-r2-distribution`** and a parallel override for the re-audit package. Thus a directory named `*-distribution` may include **both generated Gradle outputs and a packaged runtime**, not merely the final ~206 MiB Windows app. `38` gives the final pre-R2 package under `phase7-final-distribution/compose/binaries/main/app/ChatChatBarDesktop/` and records a separate isolated build directory. This makes the three ~507 MiB groups especially promising for *selective staged archival of the app payload and differentiated treatment of build intermediates*, **but no exact safe reclaim number is proven yet**. Do not delete their `compose/` subtree or historical launch/gate evidence until provenance is preserved.

The five existing **210-entry** manifests (APNG, final-one-session, D, WIN, W3 metadata) reportedly have **different Desktop JAR hashes**; the existence of identical launchers or similar Java runtimes does not prove entire package duplication. Any deduplication/archival must protect per-version JAR identity, exact-source hashes and immutable manifests; no hardlink/symlink substitution or in-place change to evidence packages without a separate plan.

## 3. Additional idle-process findings, NOT evidence of safe shutdown

- Independently user-supplied repeat process identity: historical B2 `4492` and WIN `27568` still present with matching creation times and executable provenance. No current PID reuse indication.
- Windows threads: B2 JVM 77/77 `Wait` (UserRequest 63, EventPairLow 2, Unknown 12), WIN JVM 85/85 `Wait` (UserRequest 69, EventPairLow 3, Unknown 13). The OS `Wait` state is **not** a captured Java stack and cannot identify `runBlocking`, Compose EDT, data coordinator or backup as blocker.
- Bounded 4s samples of per-process read/write transfer counters were both **0 delta** (B2 read 477,021,971/write 255,512,547; WIN read 922,471,495/write 580,507,576). No CPU activity was found in preceding 4s sample. These data support *idle/quiescent at those instants*, **not safe ownership release**.
- Java `jcmd Thread.print -l` failed Access denied earlier. Additional CIM inspection is denied; no Java wait stack, WCT, or definitive shutdown phase was obtained. **Shutdown root cause remains UNRESOLVED**, `100` controlling. Do not force-stop those processes or remove data-root locks merely because they are idle.

## 4. Next lowest-cost read-only action — focused archival feasibility, not another 9 GiB scan

Ask Codex to perform **one targeted metadata-only analysis** of the three 507 MiB paths (all direct children of `app/desktopApp/build`), each split by immediate child subtree, especially `compose/binaries/main/app/ChatChatBarDesktop` versus `classes`, `kotlin`, `generated`, `resources`, `tmp`, packaging temp, and `compose` other files. Report file counts, bytes, whether the acceptance EXE and both source-specific JARs remain in the expected payload, and pre-existing hashes/evidence references. **No bytes deleted, touched, compressed or moved.**

Also report other available fixed volumes and **free space only** (no private labels/data), enough to assess off-H archive feasibility. If user approves an archive destination later, retain original complete historical artifacts plus manifests or a verifiably lossless archive with restore test, SHA-256/inventory and accepted evidence. Do not archive to the same nearly full H: drive and assume space was reclaimed.

Focus on identifying distinct safe categories **(1) generated intermediary output not part of runtime, (2) immutable historical runtime and hashes, (3) gate/evidence and snapshots, (4) unknown/protected**. A later Project review may propose a precise, newly user-approved deletion/archival allowlist and realistic reclaimed bytes. **The prior user authorization covers only the 13 exact A+B paths; it never covers any of the 33 historical distribution roots.**

## 5. S1 and release boundary

`P8-S0` and `P8-S0C` review statuses remain PASS. `P8-S1` is the approved narrow official 1.4.1 JVM-neutral Fish core extraction *in principle*, but large Gradle tests/package should not start with only ~588 MiB free. Official 1.4.5 full-app `.cbbackup` portability is separately required under D-038/P11/Public Preview Gate, unaffected by this housekeeping. P7 stays CLOSED/QUALIFIED; historical RED/evidence is preserved; no upstream sync.

**CURRENT: FULL DISK STRUCTURE EXPLAINED / CLEANUP OR PACKAGING ACTION = NONE / PROCESS CAUSE UNKNOWN / TARGETED 507-MIB INTERNAL AUDIT NEXT.**
