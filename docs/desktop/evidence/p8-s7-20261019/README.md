# P8 S7 exact package / engineering evidence

Final source: `008343bb26d2eb76c23cae9b98132af828a9f447`, parent `79ba7bb1509dcc0747239d6e2c23a0daf45c0aff`. All checkpoint parents/stats are in `commit-ledger.json`. The evidence/report commit follows the final source and is identified by Git, not a self-referential manifest.

OBSERVED: final Desktop focused 15 suites / 146 PASS; shared voice focused 7 suites / 34 PASS; final source SharedCore full 134 suites / 892 PASS; exact distribution build exit 0 and 212 file hashes verified. Offline synthetic support compile/selfcheck/seed/independent lock acquisition and release passed. No final application or manual fake listener was launched.

FAIL retained: S7-04 bounded exit 1, 1551 tests = 1547 PASS / 2 FAIL / 2 SKIPPED. The introduced GIF cleanup scope regression was fixed and its class plus affected regression passed later. The independent cmd.exe CreateProcess error 5 remains unresolved. S7-10 command-selector parsing exit 1 executed zero tests; its raw command/stdout/stderr are retained separately from corrected S7-11. There is no final-source full bounded GREEN.

S7-04 NativeUx 4/4 PASS is one actual run, not a solution of R6 historical Robot failures. R6D/E historical PASS, R6F/G RED/UNKNOWN, R6I/J INCONCLUSIVE, historical symlink SKIPPED and NOT TESTED remain unchanged. Prior S3/S4/S5 RED/log/XML evidence is preserved in its original committed directories.

NOT TESTED: final package listening, live Fish, actual Launcher/JVM exit/restart, running-app root exclusion/reacquisition, physical-device fault matrix, manual file handles and post-GUI fake/TLS cleanup. Helper exit/acquire/release is not app lifecycle acceptance. QQ native sending is BLOCKED, export is not delivery. Whole SaveSlot/P11 and full backup are outside this adapter slice.

Real Fish GET0/TTS0; paid auxiliary calls0; QQ sends0. Mock network was loopback only, synthetic data/credentials; tests assert per-scenario request and binding counts. No artificial global mock total is claimed. Manual fake increment0 because not started. Git/docs read-only requests are separate from Fish.

`windows-preparation` contains validation tool source, commands/results and manifests only; runtime binaries, synthetic DPAPI data and user data are excluded. `superseded-package` records the old 79ba7bb1 package as history; its supervisor is disabled. New package identity is in `windows-preparation/package-identity.json`. The original `.log` bytes are copied to `.txt`; `.gitattributes` preserves raw bytes. `manifest.json` hashes every evidence member except itself, avoiding self-reference. Report 143 is outside this manifest and separately hashed in `report-identity.json`.

INFERRED limits: legacy MCI and filesystem ownership checks cannot establish universal device reliability, physical I/O deadlines or total absence of TOCTOU. No sweeping audio cleanup was added. Resources include physical memory pressure; no OOM observed, no user process killed or caches/pagefile altered.

Protection: four user-owned files remain untracked/unread/unmodified/unstaged; only path metadata inspected. No historical evidence, CURRENT, parity or baseline changed. Source checks and final preparation do not authorize merge, release or P8 closure.

State: **P8 S7 WINDOWS PACKAGE READY / USER ACTION REQUIRED / PROJECT REVIEW PENDING**.
