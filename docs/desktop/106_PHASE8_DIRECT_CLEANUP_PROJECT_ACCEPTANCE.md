# 106 — P8 Authorized Direct-Prune Execution: Independent Project Review

**PROJECT REVIEW OF CODEX LOCAL EXECUTION REPORT: ACCEPTED WITH PROVENANCE LIMITS / CLEANUP REPORTED COMPLETE / SOURCE NOT ALTERED — 2026-10-12 JST**

## 1. Authority and fact pattern

Latest user-authorized policy: `104_PHASE8_DIRECT_STALE_ARTIFACT_CLEANUP.md` (not the superseded I: archive `103`). User supplied Codex Windows execution report: **43 exact directories DELETED / 0 SKIPPED / 0 FAILED**, 28,585 files and 8,236,160,764 logical bytes removed. No builds, tests, packages, new processes, source/doc/git changes or P8-S1 implementation. Codex reported `feature/phase8-fish-audio @ c2b0b5f931c1adff6c5ce53194fc1dbcb340a2b5`, local/origin same, clean worktree, `git diff --check` PASS. Project separately verified current GitHub feature head and compared ALL 30 reported historical package names with the 30 literal `104` allowlist entries: **30 exact matches / 0 omitted / 0 extraneous**. The 13 A+B generated directories likewise match the precise `104` list.

**Evidence grade:** The GitHub path authorization and list parity are **PROJECT INDEPENDENT SOURCE/CONTROL REVIEW PASS**. Path deletion, Windows disk free counts, no surviving files, and preserved evidence are **CODEX LOCAL EXECUTION REPORT**, not independently inspected H: bytes. Preserve this distinction; do not call it user/manual GUI or Project-run filesystem verification.

## 2. Exact delete count reconciliation

| Group | Paths | Reported file count | Reported logical bytes | Review |
| --- | ---: | ---: | ---: | --- |
| 30 superseded isolated P6/P7 distribution roots in `104` | 30 | 8,609 | 7,494,477,617 | All literal names matched |
| A+B build-generated/cache roots in `104` | 13 | 19,976 | 741,683,147 | All literal paths matched |
| **Total** | **43** | **28,585** | **8,236,160,764 (7.6705 GiB)** | Arithmetic exact |

Reported all 43 target paths absent after deletion. **Only** these 43 were in scope, and no project source or existing public user-data root deletion was permitted. Earlier `104` theoretical total was approximately 7.67 GiB logical; this observed total is consistent.

### Protected residual artifacts

Codex reports these three full package roots remain under `app/desktopApp/build`:

1. `phase7-apng-r1-distribution` — final frozen P7 source / APNG Windows user gate; **KEEP**.
2. `phase7-b2-r3-distribution` — **KEEP**, old resident launcher→JVM may still use it.
3. `phase7-win-manual-distribution` — **KEEP**, old resident launcher→JVM may still use it.

Outside-allowlist historical gate summaries, JUnit XML/Native-HWND/ScrollBy RED, SHA-256 manifests, logs, screenshots and acceptance docs are reported KEEP; `.git`, user data/Portable roots, actual snapshots/backups, SecretStore, WiX and `build/libs` also reported KEEP. This retains **historical test/evidence authority**, but the 30 deleted old runnable binaries are now intentionally **not directly locally rerunnable** from their old exact paths. A committed source SHA and archived hash report do not recreate the exact old binary bytes by magic. The accepted historical P7 engineering/user results remain recorded and must not be relabeled PASS/FAIL based on local housekeeping.

## 3. Free-space anomaly is not attributable to the 43 paths

Codex reported:

- **Immediately before deletion H: free:** 28,305,424,384 bytes (~26.36 GiB).
- **Immediately after deletion H: free:** 41,825,812,480 bytes (~38.95 GiB).
- **Measured available-space delta:** 13,520,388,096 bytes (~12.5918 GiB).
- **Difference between measured delta and deleted logical bytes:** 5,284,227,332 bytes (~4.9213 GiB), **UNEXPLAINED**, not automatically attributed to this cleanup.
- An even earlier Codex read-only disk survey had reported **616,079,360 bytes (~587.54 MiB) free**; by the time of this cleanup's preflight, reported free space had already increased by **27,689,345,024 bytes (~25.7877 GiB)**. This earlier change occurred **before** the 43-path deletion and also is **UNEXPLAINED**.

Potential reasons (not diagnoses) include other concurrent filesystem operations, NTFS allocation accounting or environmental change. No tool evidence establishes the cause. Do not claim 12.59 GiB or the earlier ~25.79 GiB as recovered specifically by this task. Short targeted follow-up disk free/volume identity checks can run in parallel with P8-S0D if needed, without any renewed filesystem cleanup or full-disk scans.

## 4. P8 implication and next owner

**Project local-cleanup review: PASS with source-vs-local evidence qualification.** The H: available-space report (~38.95 GiB) now exceeds the provisional >=5 GiB build headroom target. This makes the next target **P8-S0D — Desktop Shutdown/True Process Lifetime** under `105_DESKTOP_SHUTDOWN_PROCESS_LIFETIME_GATE.md`, rather than repeated scans/cleans. Codex must still verify live H: space and worktree clean before real build.

The two historical B2/WIN launcher/JVM pairs (previously observed 34912→4492 and 20904→27568) reportedly **remain alive**. **DO NOT** kill them automatically, delete their executable paths or data-root ownership locks, or treat 4-second zero CPU/IO / Win32 WaitReason as proof that persistence has safely drained. The precise old-instance shutdown stage remains UNRESOLVED. The new-build source defect cannot retroactively make those old PIDs exit.

Scope for S0D: bounded sanitized phase evidence, a narrow safe normal-success final JVM-exit mechanism only after Compose, app container, picker and ownership close, or a fix for an earlier proven wait; clear unsafe-shutdown state; repeat real packaged Windows close with launcher+JVM gone, data-root lock reacquisition and persisted data intact. Project must review actual diff and tests before S0D acceptance and subsequent Fish S1.

**CURRENT: 43-PATH CLEANUP REPORTED COMPLETE / 0 PATH DISCREPANCY / 0 GIT CODE CHANGES / H: HEADROOM AVAILABLE AS REPORTED / FREE-SPACE DELTA ANOMALY OPEN / P8-S0D NEXT / P8-S1 NOT STARTED.**
