# Exact G0 delivery

- Control/parent: `a73ce170137ce084add3f7b59eddead0c57b469d`.
- Tested test-source + primary evidence commit: `42a7269a1e21a2ad1cf1b1fb3ece447d8b702d9a`.
- This later receipt changes only this document. Final remote SHA is supplied in the external handoff; no test or raw artifact changed after the tested commit.
- [Complete test/evidence diff](https://github.com/MisakaPiano/ChatChatBar-Desktop/compare/a73ce170137ce084add3f7b59eddead0c57b469d...42a7269a1e21a2ad1cf1b1fb3ece447d8b702d9a).
- [Participation matrix](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/42a7269a1e21a2ad1cf1b1fb3ece447d8b702d9a/docs/desktop/evidence/p11-s2-g0-root-probe-20261023/S2_PARTICIPATION_MATRIX.md).

Only a test source and this new evidence directory changed. All production src/main trees remain identical to control. Exact production/test blobs, working SHA-256/lengths, raw inventories/fixtures and XML are retained under raw/. All raw staged bytes matched no-filter Git blob hashes before commit; local attributes preserve them.

Final actual execution: Desktop9 suites/109 tests + Shared3 suites/49 tests =158 distinct current reported PASS, no JUnit failure/error/skip, exits0/0. Two shared symlink branches explicitly unavailable and two materializer branches not certified remain NOT_TESTED/INCONCLUSIVE. Earlier green runs and old S1 counts are not added. New G0 controlled child exits0→71→0 are actual observations; a surviving sentinel outside rows is not a new-domain recovery guarantee.

Skills reviewed before staging: existing Desktop backup facts remain current; temporary experimental test-only probes introduce no production feature requiring a skill update. User scope permits test/evidence only. Protected files/cache remain unstaged and unread/unhashed.

**Stop at P11-S2-G0 Project Evidence Review Gate.** R01/R02/R03/R07 decisions, S2 production implementation, S3–S6 and Public Preview are not authorized by this evidence.
