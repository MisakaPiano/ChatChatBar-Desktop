# Official 1.4.7 Entity wire correction — 2026-10-25

Base source: e41a490611ec5c82601f7fd44e830afe271be6fa; related original tests were run on the same unchanged application sources before the intervening governance-only commits. Only opt-in tooling changes here.

- RED: 1 suite / 8 tests, 3 PASS / 5 FAIL, errors/skips=0, exit1.
- GREEN: 11 suites / 108 tests, failures/errors/skips=0, exit0. Includes 8 focused wire tests and 5 historical-voice behavior reproductions, plus relevant existing regressions. No historical totals are added.
- The 5 reproduction tests demonstrate an undesirable existing shared behavior; they do not certify that rebinding is safe or fixed.
- A prior wrapper permission refusal executed zero tests; stale preexisting XML from that attempt is excluded. No Android APK or GUI was run.
- Independent synthetic wire fixtures only. Pinned field source: official 1.4.7 b4fa75d296680f777be095f3121ad0c4db979f17. Strict unknown-field/type and code84 rejection remain.
- Real source records, original hashes, archives, raw extraction and local recheck results remain PRIVATE/IGNORED and are deliberately not included.
- Production main source, formal restore admission, SecretStore and baseline are unchanged by this checkpoint.

Commands, raw Gradle output and original JUnit XML are retained per round. Private backup content and credentials were not supplied to Gradle or these tests.
