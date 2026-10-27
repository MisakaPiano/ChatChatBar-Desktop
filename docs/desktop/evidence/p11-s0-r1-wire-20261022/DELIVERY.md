# R1 exact source delivery

- Base / unchanged production commit: `b531ff0263ab70857c716730049771e8a103fd51`.
- Tested R1 source commit: `06d09959ef7aada304cd64069bcfd355011abc66`.
- Inspector test Git blob: `a8be5d122da37cbf096127e33f464ea7412270d5`.
- Independent official wire fixture Git blob: `e1f3e5404c83e917dee5fa77ea5d143dd7b89241`.
- Final delivery includes a subsequent evidence-only commit to preserve raw CRLF bytes exactly and add this receipt. No test or production source changed after the 19-test successful run. The final remote SHA is reported in the accompanying handoff.

Working test source byte SHA-256 values are recorded in `raw/test-source-sha256.json`; Git source blobs use the repository's normal line-ending normalization. Raw evidence has local `.gitattributes` disabling text conversion, so its committed bytes match the saved SHA-256 values. The initial R1 commit's raw text was normalized during staging; the final evidence-only correction restores the original captured bytes without editing historical evidence or test results. Use the final delivery revision for raw-evidence hashes.

Reviewed skill: `.agents/skills/chatbar-desktop-full-backup/SKILL.md`. Its feature behavior and entry-point statements remain valid; no skill change is needed. The evidence-only receipt does not change this assessment.

Review links:

- [R1 tested source commit](https://github.com/MisakaPiano/ChatChatBar-Desktop/commit/06d09959ef7aada304cd64069bcfd355011abc66)
- [Feature handoff](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/feature/phase8-fish-audio/docs/desktop/evidence/p11-s0-r1-wire-20261022/PROJECT_HANDOFF.md)

Result: **19 current JVM tests PASS; 81 prior regression results reused under unchanged production identity. P11-S0 R1 Project Review Gate only.** No merge, release, baseline change, S1–S6 implementation or HOLD removal is authorized by this receipt.
