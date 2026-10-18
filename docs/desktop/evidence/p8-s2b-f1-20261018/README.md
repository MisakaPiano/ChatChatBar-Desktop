# P8-S2B-F1 — Free TTS preview / Windows output candidate

Status: **P8-S2B-F1 ENGINEERING CANDIDATE / PROJECT REVIEW PENDING**.

## Frozen identity

- Start/parent: `3bc271a09377d95e78e68edd8380df96c58bc37e`.
- Source/test/related Skill checkpoint: `d00451352d913297a0bce758911e2e1ff5052923` (single parent above), 11 files, +828/-13.
- Formal upstream remains 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`.
- Canonical Git patch: [source-commit.patch](source-commit.patch); Git blob/raw-normalized byte identities: [source-checkpoint.json](source-checkpoint.json).
- `final-source.patch` is the earlier precommit working diff with explicitly assembled new-file sections; the canonical committed patch is authoritative. Stage 02 `initial-source.patch` contains tracked changes only; its manifest records early new-file hashes, but early full new-file contents were not separately frozen. Do not infer full reconstructability of every RED draft.

## Delivered boundary

Explicit nonblank 1–120-code-point text and a generate click are required. Only exact `s2.1-pro-free` is allowed, using shared `FishAudioRequestFactory.tts`; paid/stale models reject without substitution. First successful clip per reference per picker is reused with explicit UI notice. No catalog sample URL download, binding mutation, chat voice Entity, Prompt, SaveSlot, sharedCore or Android source change.

App-owned preview scope drains actual HTTP callback/body/file work, native output and recorded temporary files before app/root release. Separate OS-temp cache: 16 MiB/clip, 64 MiB/session, at most four outstanding leases (256 MiB aggregate), 16 recorded files/session, MP3 frame structure and 120-second limit, atomic publish without non-atomic fallback, cached SHA-256 and file identity checks. Unknown/replaced files are retained, not recursively deleted. File-ID helper is reused without changing G0. Complete hostile Windows TOCTOU/ACL proof is not claimed.

MCI/winmm through existing JNA is a replaceable legacy local-file backend, not a second formal audio repository. No new Gradle or codec dependency and no OS decoder binary redistributed. This machine's observed 30 module hashes match installed System32 bytes and have valid Microsoft signatures (one company string is Fraunhofer). Original `backend-modules.json` used a case-sensitive path-prefix check, making `systemDirectory` unreliable; it is preserved, and separate byte revalidation does not rewrite that historical field.

Microsoft documents MCI as legacy and recommends MediaPlayer when possible: https://learn.microsoft.com/en-us/windows/win32/multimedia/mci . Native call and close results: https://learn.microsoft.com/en-us/previous-versions/dd757161(v=vs.85) and https://learn.microsoft.com/en-us/windows/win32/multimedia/close . Codec/device availability on other Windows installations is not guaranteed. Unconfirmed native release retains ownership/files; no physical absolute deadline or force-release promise.

## Actual validation — do not add repeated runs together

| Stage | Actual result |
|---|---|
| 01 | ENVIRONMENT BLOCKED before compilation: wrapper cache lock and CIM denied. Observer failed; exit code not recovered. No JUnit. |
| 02 | Desktop main compiled; test compile RED due new optional picker argument/old positional test call. Exit 1; no JUnit. |
| 03 | 3 suites / 17 tests, 15 PASS / 2 FAIL, exit 1. Stop/replay admission race plus cancellation fixture readiness. |
| 04 | 7 suites / 65 tests, 64 PASS / 1 FAIL, exit 1. Cancellation fixture still wrong. |
| 05 | Diagnostic command split spaced method name; Gradle task lookup RED, exit 1; no JUnit. |
| 06 | 7 suites / 65 tests PASS, exit 0. |
| 07 frozen final source | 7 suites / 67 tests PASS, exit 0. |
| 08 bounded | 155 suites / 1506 tests: 1504 reported PASS, 2 SKIPPED, 0 failures/errors, exit 0. |
| 09 distribution | createDistributable exit 0; runtime image task reused unchanged JDK/dependency cache, current business JAR contains matching new compiled classes. |

JDK17, offline Gradle, no-daemon, no-parallel, one worker, low priority, Kotlin in-process, existing 2 GiB heap. No clean/rerun-tasks/installers. The same four bounded exclusions are in [bounded.init.gradle](bounded.init.gradle); no new exclusions. Existing four symlink fixture early returns remain NOT TESTED, separate from the two JUnit SKIPPED entries. SharedCore was UP-TO-DATE; Android was not rerun.

Original logs/exit records and 179 XML files across separate RED/PASS runs are preserved. XML has no profile-path redaction and is byte-identical to the local originals; [raw-publication-ledger.json](raw-publication-ledger.json) identifies any profile redactions in other logs. Complete originals remain under the local gitignored build gate. New source/final runs must not be confused with older S2A evidence.

The cancellation root cause was MockWebServer `throttleBody` also throttling POST upload, before a complete request could be recorded. The earlier cold-native-init hypothesis was not established. The corrected fixture delays only response body and proves `.part` readiness before cancel. Dependency bytecode excerpt and hash are retained. Stop/replay state is now cleared only after job unregister. Two native-setup helper rejections were old fixture-directory whitelist mistakes, preserved under manual-setup-attempt01/02; no production package was replaced during manual testing.

## Package / live / manual evidence

- 212 files; full [distribution-manifest.json](distribution-manifest.json), matching before/after manual.
- New output classes match current compiled classes; launcher and business JAR hashes are in that manifest.
- The package retains existing `chatbar.desktop.applicationHome=$ROOTDIR` configuration; no production routing/trust hook was added.
- Live gate: GET 0 / TTS 1, explicit free model, HTTP 200, 50,572-byte synthetic MP3. Physical request headers counted once. Official free status was rechecked before call (currently advertised through 2026-11-30): https://fish.audio/zh-CN/blog/s2-1-pro-free-api/ . Account billing was not independently read; no account-balance claim.
- User approved official public example `7f92f8afb8ec43bf81429cc1c9199cb1`, independently referenced in Fish's public example source and public voice page (see live-basis.json). Only synthetic public text sent; no chat/card/root exported.
- [MANUAL_RESULTS_CN.md](MANUAL_RESULTS_CN.md): seven bounded manual groups PASS, with nine original user text replies and one voluntary screenshot. Real generation helper, mock HTTP and real packaged hearing are separate evidence categories.
- Packaged Launcher 13756 / JVM 36640: identity, parentage and retained process handles recorded; final exit 0/0. Independent root check while live rejected (75), after exit reacquired/released (0). Stop allowed exclusive read of the clip. Synthetic cards unchanged; post-close audio files zero. Fake exit 0 and temporary TLS cleanup confirmed.
- Initial close report was clarified by user: main window still existed. Raw thread observation is preserved; no shutdown-latency acceptance and no concealed process-lifetime failure.

## Resource and privacy scope

Sampling, not absolute peaks: per-stage counts/extrema are in [stage-summary.json](stage-summary.json). Bounded: 32 samples, min available 930,639,872 bytes, min commit headroom 15,895,298,048 bytes, max 1,350 pages/sec. No OOM observed. No user processes stopped or system settings changed.

Runtime image and isolated data/credentials stay local and are excluded. Published audio is the one newly generated synthetic test clip, with provenance/hash; not a user's audio. Protected personal Markdown and three JPEGs were not read, edited, staged or committed. Whole worktree is therefore not clean even if tracked/index are clean.

Final static rechecks are recorded in [final-verification.json](final-verification.json). The [credential-publication-scan.json](credential-publication-scan.json) scans evidence bytes against the existing typed OS-protected Fish credential in memory without printing it; its checked-file count describes the then-current snapshot, including the previous scan result and manifest. The final copied result replaces that previous result; the subsequently regenerated [evidence-manifest.json](evidence-manifest.json) contains only file names, byte lengths and hashes. The checker source is retained; no credential value or private TLS material is published. Raw non-UTF-8 logs retain their original encoding. This directory's scoped `.gitattributes` disables text normalization only for these new evidence files so published Git blobs preserve their exact raw bytes.

Source checkpoint `git diff --check` passes. Evidence-wide whitespace checking returns 2: default checking flags preserved CRLF as trailing whitespace; even with `cr-at-eol`, original process/Gradle output, the thread snapshot and unified-patch context contain trailing spaces. These bytes are deliberately not rewritten to manufacture a green whitespace result. [git-static-check.json](git-static-check.json) records the separate checks and affected raw artifact counts; scope and byte-identity checks pass.

## Remaining limits

Physical device loss/default-device changes, other Windows editions/codec stacks, complete TOCTOU, blocking native I/O absolute timeout and power-loss durability are NOT TESTED. Native close failure is injected/retention-tested; it is not proof every hardware failure can be forcibly drained. Full-memory PCM decode is not implemented: bounded frame validation plus native open/duration/play results are the delivered checks.

The cache size/file budget is enforced while accepting/writing a response; a quota refusal can occur after a user-authorized request has already been sent. No claim of server-side quota/fee preflight. Keyless cached playback is not newly opened through the picker. FREEFORM entry is manually verified, but its playback was not separately repeated.

WIN-AUTO-001 remains OPEN; this task did not fix capture infrastructure. No ordinary sample CDN support, paid TTS, formal S3/chat autoplay, SaveSlot audio, G2 Writer/Recovery/dispatch, baseline/parity update, desktop merge or release. Project acceptance is pending.
