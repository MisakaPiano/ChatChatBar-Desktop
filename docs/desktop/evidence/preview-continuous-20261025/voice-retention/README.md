# Desktop historical voice identity retention

Base: cddb5945f76ea40c0d82a482a4bbf09c4c9c5123. Synthetic inputs only; no actual archive, voice transcript or credential is included.

- RED: 1 suite, 4 tests, 2 PASS / 2 FAIL, exit1.
- GREEN: 5 suites, 32 tests, failures/errors/skips=0, exit0. The requested SessionCopy glob matched no class and is not counted; actual executed suites are the retained five XML.
- Desktop strict mode preserves an explicit nonblank historical version ID and its source/audio/anchor metadata; it is not visible under current-version playback, even if text happens to match.
- Known and null legacy behavior remains; Android non-strict default still demonstrates the reported upstream issue.
- Repository reopen is executed. This checkpoint does not yet relax archive admission or claim the full dormant/raw preservation path is complete.
- Production source delta is the strict-mode branch in shared VoiceMessageRepository; public Entity/schema and Android default behavior are unchanged.

Raw command arguments, JUnit, source snapshots and unedited Gradle logs are retained. Log trailing whitespace is original output.
