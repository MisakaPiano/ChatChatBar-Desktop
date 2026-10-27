# P8 S7 R3 — manual acceptance and controlled persistence restart

Date: 2026-10-20 JST. Status: ENGINEERING EVIDENCE / PROJECT REVIEW PENDING. This is not P8 closure, release acceptance, or full bounded GREEN.

## Identity and scope

- Base/evidence parent: `e51d19cc3aed13ced5ea503f44d1d7e96f6de48c`.
- Frozen product source: `008343bb26d2eb76c23cae9b98132af828a9f447`.
- Exact distribution: 212 files, checked before initial start, before authorized restart and after exit. Distribution-manifest SHA-256 `cd15d79587869d864ea4777aecef7a557086aa95d6838146c8f7bf83f652a7f7`.
- Original R3 isolated root retained; no reseed, reset or initial-fixture restoration. Current five durable records and audio were admitted with a new post-user snapshot, not the initial seed manifest.
- One initial R3 start and one separately user-authorized W5 persistence restart. Distinct exclusive create-once locks, new fake directory `mock-service-restart01`; initial lock and fake logs retained. No further restart.
- No product/Gradle/Prompt/schema/CURRENT/parity/baseline changes. No Robot, JUnit, bounded or package rebuild performed in this manual phase. No real user data or credentials used.

## User observations — distinct from technical evidence

Chronological user replies are in `user-chronology.jsonl`; screenshots are voluntary user screenshots, not automated GUI PASS.

| Scope | Actual result |
|---|---|
| W1 entry | USER PASS: controls visible, no credential error. |
| W1 narrator prerequisite | Initial attempt without narrator visibly FAILED; user explicitly selected Alice, then succeeded. Retained screenshots, not retroactively PASS. |
| W1 single segment | USER PASS: recording appeared, audible, stop effective. User confirmed extra explicit generation/retry; multiple requests are not proved automatic duplication. |
| W1 whole missing targets | USER PASS: narration and Bob filled, existing Alice recordings retained, stop effective. |
| W2 additional take | USER PASS; old records retained and one independent take added. |
| W2 replacement | Initial response unconfirmed; user could not find completion feedback. Later confirmed playable replacement and other recordings retained; admitted multiple targets/confirmations. Isolated single-click/single-target behavior NOT independently established. |
| W2 export | USER PASS; exported Bob MP3 hash matched source. |
| W2 explicit delete | USER PASS; only selected Bob recording removed, text/other recordings/export retained. |
| W3 settings | USER PASS: audiobook override false and English saved. This does not certify every audiobook mode behavior. |
| W3 auxiliary language/tag path | USER PASS: new Bob recording, original Chinese text unchanged. Fixed fake auxiliary text, not live translation/tag quality acceptance. Rewritten-text confirmation branch NOT TESTED. |
| W3 version isolation | USER PASS: alternate version did not show original voices; return restored original voices. |
| W4 cancel | USER PASS: hanging synthesis canceled, no new recording, UI usable. Fake socket drain observed. |
| W4 session leave | USER PASS: playback stopped on B, no A voices in B, A voices retained on return. |
| W5 first close | User explicitly closed main X normally without error. |
| W5 restart playback | USER PASS: existing recordings present, audible local playback and stop effective. |
| W5 restart settings | USER PASS: false/English persisted. |
| W5 restart versions | USER PASS: version isolation and return preserved original recordings. |
| W5 second close | User explicitly closed main X normally without error. |

## Technical observations

Both actual packaged runs: Launcher/JVM exit **0/0**, live independent lock probe **75**, post-exit independent reacquisition **0**, fake exit **0** and TLS cleanup. Native process handles retained by watcher; fixture-only exit is not being substituted for packaged evidence. Final scoped process query found zero owned app/fake/supervisor processes. Five final formal records/audio hashes retained; all five files opened read-only with FileShare.None then disposed after restart. Synthetic cards unchanged within restart.

Initial run: fake **13 TTS POST + 4 auxiliary POST**, including explicit extra user requests and one intentionally hanging/canceled request. Restart: **0 endpoint requests**; absent fake events file is recorded, not a missing known event log. Fake helper only creates it upon endpoint events; ready/cleanup and process logs exist. Aggregate real Fish GET **0**, real TTS **0**, paid auxiliary **0**, QQ send **0**.

No `.part` or preview MP3 remains. Formal audio and intentional export remain. First-exit metadata lists JNA DLL/marker files in temp, so complete temporary-directory emptiness is not claimed. Both fake private TLS/trust materials removed.

See `W5-first-exit-verification.json`, `W5-restart-final-verification.json`, exclusive handle probes, `first-run/` and `restart01/` raw logs. Read-only Git network query initially failed in sandbox proxy context; escalated ls-remote succeeded with expected HEAD before restart. No product failure is inferred from this tool-access issue. Optional read of restart fake events reported absent path; verified zero endpoint events, cleanup succeeded.

## OPEN UX findings / deferred requirements

See `DEFERRED_CHAT_MENU_VOICE_UX.md`: persistent saved/deleted notices, unclear replacement completion feedback, per-message action ownership, icon/text contextual popup, segment/whole screenshot gap, single-row generation Deck and conditional right-side 角色音色 dropdown, floating resumable playback, and cross-reply manual continuous playback gap. These are user requests/observations pending Project scoping, not implemented features. The user clarified generate voice is one action, not separate generate/voice buttons.

## Historical limits retained

- R1 fake-directory preparation failure and R2 unsafe plaintext synthetic credential refusal / Studio acknowledgment-to-exit remain original failures, not normal-close PASS.
- STUDIO-CREDENTIAL-ERROR-PATH-001 remains OPEN; Studio not exercised in R3.
- Historical full bounded was 166 suites /1551 tests:1547 PASS,2 FAIL,2 SKIPPED; later GIF scope fix/focused results do not create a final full bounded GREEN. cmd.exe access-denied remains unresolved.
- R6D/R6E historical Native 4/4 used earlier test identities; R6F/R6G Robot input RED/UNKNOWN and R6I/R6J INCONCLUSIVE remain unchanged.
- WIN-AUTO-001 and WIN-BRAND-001 remain OPEN. Historical symlink SKIPPED/NOT TESTED retained.
- Device failures, live service quality, all integer/confirmation/error branches and full audiobook/cross-reply playback are not certified by these manual signatures.

## Protection and handoff

Four user-owned untracked paths are UNREAD / UNMODIFIED / UNSTAGED; only status/path metadata inspected. Historical evidence unchanged. No skill edit needed: this evidence/fixture-only work changes no implemented feature facts. Only this new evidence directory is staged/committed. Stop after independent evidence push and hand back to Project; no merge, release, parity upgrade or next-phase implementation.

`manifest.json` records raw bytes and SHA-256 for every member except itself. Commit SHA is delivered in the handoff (not self-embedded in this commit). Direct parent is the exact base above.
