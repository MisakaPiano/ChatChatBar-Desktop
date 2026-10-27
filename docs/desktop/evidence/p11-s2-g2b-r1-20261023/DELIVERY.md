# G2B-R1 delivery receipt

Tested source/test/primary evidence SHA: **`8b05d47b30878da017925aab4f7ff10455892fb9`**. Start `40f0f3dec9b6f1448314ca25804bb9091e7751d2`. Later receipt commit adds only this file and COMMITTED_VERIFICATION; final receipt SHA is returned in the task response. No source/test edits or further execution after the tested source commit.

- [Source/test/evidence commit](https://github.com/MisakaPiano/ChatChatBar-Desktop/commit/8b05d47b30878da017925aab4f7ff10455892fb9)
- [Complete R1 source delivery diff](https://github.com/MisakaPiano/ChatChatBar-Desktop/compare/40f0f3dec9b6f1448314ca25804bb9091e7751d2...8b05d47b30878da017925aab4f7ff10455892fb9)
- [Immutable handoff](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/8b05d47b30878da017925aab4f7ff10455892fb9/docs/desktop/evidence/p11-s2-g2b-r1-20261023/PROJECT_HANDOFF.md)
- [Raw evidence](https://github.com/MisakaPiano/ChatChatBar-Desktop/tree/8b05d47b30878da017925aab4f7ff10455892fb9/docs/desktop/evidence/p11-s2-g2b-r1-20261023/raw)

Only production change: census Community expiry guard (+3/-1), requiring numeric integer token and valid Long range. Test change:41 added lines,3 new tests plus Community comparator prohibition. No fixture helper or any other executing component/Skill/old report change. CODE_DIFF.patch records the actual two-source patch. Static finding, official fixed Long declaration identity, tested source/fixture identities and all command/exits/JUnit are retained.

Actual runs, not cumulative:

| Run | Suites/tests | PASS/FAIL/errors/skips | Exit |
|---|---|---|---|
| Pre-fix quoted-string repro |1/3|2/1/0/0|1|
| Intermediate exponent repro |15/182|181/1/0/0|1|
| Final bounded |15/182|182/0/0/0|0|

Final census suite30; other152 tests actually rerun.17 wire variants are cases within3 new JUnit tests, not extra tests added to182. Historical24 RED/179 GREEN and both new RED snapshots remain unchanged. Final integer guard rejects quoted numbers, null, booleans, decimal/exponent notation, arrays/objects and overflow; omitted/zero/positive/both Long bounds pass. Invalid types never reach the target observer; Community never enters API-key comparison and remains UNMAPPED.

Postcommit verification checks **127/127 indexed raw files** by exact byte length/SHA-256 and both tested sources against Git canonical content. Complete main-tree diffs allow only the census file to differ. Raw index SHA-256: `ff57adb210de7a2cbb602d93c39f895965627c6c57a19879e22b8766639218a2`. Diagnostic scan34 logs/XML/four known synthetic literals has zero hits; explicit synthetic source/fixture files intentionally retain fake values. This is not arbitrary-input confidentiality certification.

No real data/key/password/DPAPI, restore, new persisted schema, GUI/Android/Provider or G2C. Existing restoreSupported=false/restoreAuthorized=false/CREDENTIAL_APPROVAL_REQUIRED unchanged; desktop not merged. G2B bounded-census and native-target/cross-root/Community-sink limitations remain. Formal baseline1.4.1, P11/D-038/Post-P8/Public Preview HOLD unchanged.

**Stop: P11-S2-G2B-R1 PROJECT RE-REVIEW GATE.**
