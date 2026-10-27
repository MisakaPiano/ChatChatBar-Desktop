# G2B exact delivery receipt

**Stop: P11-S2-G2B Project Code Review Gate. Independent Project acceptance required.**

| Commit role | Exact SHA |
|---|---|
| Reviewed starting control | `0db537d37e309550833c1e3693e0b4d0284e897b` |
| Separate governance171 / user's bounded engineering authority | `5a7b9919cd561e4a0c39bd8a8999495406643bf5` |
| Tested source/test/primary raw evidence | `36a414596beeb850fbded9efa1386351fc83c927` |

This later receipt adds only DELIVERY and COMMITTED_VERIFICATION; the final receipt SHA is returned in the task response. No source/test changes or additional execution follow the tested source commit.

- [Governance commit](https://github.com/MisakaPiano/ChatChatBar-Desktop/commit/5a7b9919cd561e4a0c39bd8a8999495406643bf5)
- [Source/test/evidence commit](https://github.com/MisakaPiano/ChatChatBar-Desktop/commit/36a414596beeb850fbded9efa1386351fc83c927)
- [Full delivery diff through tested source](https://github.com/MisakaPiano/ChatChatBar-Desktop/compare/0db537d37e309550833c1e3693e0b4d0284e897b...36a414596beeb850fbded9efa1386351fc83c927)
- [Immutable handoff](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/36a414596beeb850fbded9efa1386351fc83c927/docs/desktop/evidence/p11-s2-g2b-20261023/PROJECT_HANDOFF.md)
- [Raw evidence](https://github.com/MisakaPiano/ChatChatBar-Desktop/tree/36a414596beeb850fbded9efa1386351fc83c927/docs/desktop/evidence/p11-s2-g2b-20261023/raw)

Only existing runtime change is the opt-in Inspector handoff: +23/-1 lines. Three new internal production files, one new27-test class and one independent wire helper were added, plus the feature Skill update. CODE_DIFF.patch contains all seven source/test/Skill paths. Other executing components are unchanged; native data roots, keys, restore and Provider/Task behavior are not wired to this API.

Final actual JDK17 headless/offline execution: **15 suites/179 tests,179 PASS,0 failures/errors/skips,exit0**,1m50s. First actual new-suite RED:24 tests,23 PASS/1 FAIL,0 errors/skips,exit1,1m35s. Community session erroneously reached a test API-key comparator; final implementation never sends Community to that comparator and keeps UNMAPPED. Historical RED/source/XML retained. Old G2A150 is not accumulated into179; existing152 were actually rerun alongside new27.

Postcommit archive proof: **164/164 indexed raw artifacts** preserve exact SHA-256/length. Six tested source files match canonical Git content; raw snapshots preserve actual tested workspace bytes. Inspector CRLF→Git LF is explicitly represented in the dual identities, not falsely asserted identical raw bytes. All other existing main-file blobs are preserved: original three trees contain246+246+269=761 files; only Inspector differs, so760 remain unchanged, with exactly three new Desktop internal additions. COMMITTED_VERIFICATION records the exact source commit and projections.

Raw index SHA-256: `9b44ca40fa6e77f2f3179091cd0e38e5fd8ca4cb468ec86b34b905f9f1be6ef1`. Fixed official145 source/test fixture/Git blob identities, diagnostic audit and raw results are included. The selected official default code84/version declaration is app/app/build.gradle.kts. No upstream synchronization or baseline change.

Complete census is limited to known145 credential locations in Preferences and the four covered Entity families, with default/full supported field tests. Unknown/other Entity families, media/packages, opaque custom parameters, future fields/schema and unverified identity remain incomplete/rejected; no target access on incomplete results. Most complete real whole-app backups are therefore **not covered** by this narrow census. Target observations are synthetic declarations, not real OS locks/profile identity/CAS. No real archive/key/password/DPAPI/root was accessed; no live key write, durable intent, Community sink, native observer, G2C, full migration, P11/D-038/Preview completion or desktop merge.

P11/D-038/Post-P8/Public Preview HOLD remains. Do not use this receipt as authorization for the next slice.
