# PREVIEW-B1 / D-041 delivery

Tested correction and packaged source: `a0b0a0707142a8b9a2c7bfd4d33acb106138ae0f`.

Windows/main evidence commit: `9c5f3999acc10e99f893ed907b5dcdc92bbf2024`. Final receipt follows as evidence-only commit; no source changes after packaging.

- Actual RED:3 suites45 tests,43 PASS/2 FAIL,exit1. Final GREEN:3 suites45 PASS,0 failures/errors/skips,exit0.
- Local app-images901/902 both build exit0,217 files each, embedded distinct Desktop version/exact source commit verified, no development fallback.
- Both normal shutdown launcher exit0; all scoped runtime processes absent/root lock released. JVM child exit codes UNKNOWN; not inferred from launcher0.
- Eight original synthetic business/media files, bootstrap root selection and noncredential opaque sentinel remain byte-identical. Same-source side-by-side app-image result, not installer upgrade.
- Update-page interaction NOT TESTED: Windows screenshot capture timed out twice and accessibility exposes only an empty pane. Actual browser handoff NOT TESTED.
- Real installer/upgrade/uninstall/protected credential retention NOT TESTED; no isolated Windows SID/HKCU established. Need disposable Windows environment before those acceptance runs.

Codex Git-archive byte verification:64/64 indexed raw SHA/length entries PASS. Final tested source snapshots and packaged source identity verified by Audit-Evidence.py. CODE_DIFF.patch is the exact source/test/Skill change patch; its context whitespace is intentional and excluded from whitespace lint via a local artifact attribute. Full original evidence stays intact.

Full diff from control: https://github.com/MisakaPiano/ChatChatBar-Desktop/compare/243168e97609f45d8265a2e6ba77db09f6bfa125...9c5f3999acc10e99f893ed907b5dcdc92bbf2024

See PROJECT_HANDOFF.md for full D-041 matrix, limitations and next actions. No real credential/data/Provider/backup/Release/Tag, desktop merge, G2C, baseline or HOLD change.

STOP: PREVIEW-B1 / D-041 PROJECT CODE REVIEW GATE.
