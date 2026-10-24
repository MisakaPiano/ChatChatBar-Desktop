# Original recording metadata validation

Two actual RED JUnit tests show a structurally valid, hash-consistent synthetic archive could hide incorrect recording metadata: mapping re-persisted MP3 then replaced declared length/duration, producing success.

The original duration must now be nonnegative, original byteLength strictly positive and exactly equal to the referenced file size before re-persisting. The audio publisher also receives that exact expected length. Valid clips retain the existing platform-derived duration/owned-copy behavior; no message/version/source text is rebound or changed. Input archive bytes and empty target root remain unchanged on rejection.

RED:2 tests/2 FAIL/exit1. GREEN:3 suites/26 PASS/exit0. A requested nonexistent DesktopVoiceAudioStoreTest filter yielded no suite, so it is not claimed; actual DesktopVoicePersistenceTest then ran separately:1 suite/5 PASS/exit0, covering the real store's expected length, failure/cancel and ownership behavior. The length test contains four cases, not four additional JUnit tests. No Windows audio hardware, real archive, Provider or real credentials were used.
