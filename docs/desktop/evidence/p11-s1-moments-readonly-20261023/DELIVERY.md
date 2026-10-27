# Exact S1 source delivery

- Control/parent: `3ec2a3239a296713b79303aff7e1aa41928d8e53`.
- Tested implementation + primary evidence commit: `45da3251f74c85c29e36233ef70000f24f67e630`.
- Subsequent delivery receipt commit changes this document only; no tested source or raw artifact changes. Final branch SHA is provided in the external handoff.
- Full implementation diff: [control → source](https://github.com/MisakaPiano/ChatChatBar-Desktop/compare/3ec2a3239a296713b79303aff7e1aa41928d8e53...45da3251f74c85c29e36233ef70000f24f67e630).
- Source-bound Project handoff: [PROJECT_HANDOFF](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/45da3251f74c85c29e36233ef70000f24f67e630/docs/desktop/evidence/p11-s1-moments-readonly-20261023/PROJECT_HANDOFF.md).

Tested source working-byte SHA-256 and normalized Git blob values are in raw/source-identity.json. All tested files were verified unchanged after the final run. Every staged raw file was compared with its no-filter Git hash before committing; raw/evidence-sha256.json and raw/fixture-sha256.json retain actual SHA-256/lengths. Raw attributes disable newline conversion, preserving exact original log/XML/archive bytes.

Final authority: 8 suites, 89 tests, zero failures/errors/skips, exit0. S1 child exit73/alive=false. Earlier compilation errors and functional failures remain separate, unedited evidence. No historical test counts are added to this run.

Backup skill reviewed and updated for the new separate S1 API; no other skill's behavior changed. The existing S0 result still does not certify a resource graph. New S1 reports never authorize restoration, full portability, persistence or Task execution. No protected user file or unrelated cache is staged.

**Delivery stops at P11-S1 Project Code Review Gate. No S2 work, acceptance promotion, official upstream baseline update, desktop merge or release.**
