# Phase 7 Slice A — Default Model Visibility acceptance

**P7 Slice A — Default Model Visibility: ACCEPTED / FROZEN.**

- Frozen production SHA: `06aaecb978a51ac8c22ab5be09bf963e2bb082bd` on `feature/phase7-image-novelai`.
- Implementation: `4cb090ba07487b3b9ca81fd7d85fd6866eee0b29`.
- R1 repair: `caa88b9d0c6b469f1e18fcbc95333d638e065184`.
- Visual Polish R2: `06aaecb978a51ac8c22ab5be09bf963e2bb082bd`.

## Acceptance ownership and evidence

| Decision or check | Owner | Result |
|---|---|---|
| Independent GitHub code review | Project | PASS |
| Functional manual acceptance | User | 5/5 PASS |
| R2 visual manual acceptance | User | 3/3 PASS: dual-default labels, dynamic theme color, narrow-window layout and English display confirmed |
| Desktop full `:desktopApp:test --rerun` | Codex local execution on frozen production source | 120 suites / 1074 tests / 0 failures / 0 errors / 0 skips |
| R2 exact-SHA distributable, EXE/Desktop/sharedCore hash comparison, isolated Windows launch | Codex local execution and report | PASS; main window appeared, normal `WM_CLOSE`, launcher/application exit 0/0, stdout/stderr 0/0 bytes |

Package and launch gate: `app/desktopApp/build/phase7-slice-a-r2-gate/gate-summary.json` (gitignored local evidence). The independent package is `app/desktopApp/build/phase7-slice-a-r2-distribution/`. The Desktop production JAR, not the launcher hash alone, identifies the newly packaged business source. These local checks do not substitute for the Project's GitHub review or the user's manual acceptance.

Slice A is frozen and should not be reopened as routine follow-up UI work. The official model-row shortcut for directly setting the default image model remains a separate parity item requiring classification; it is not part of this acceptance.

**Phase 7 remains NOT ACCEPTED / NOT MERGED.** The eight Slice A manual checks do not constitute Phase-7-wide acceptance. The formal validated upstream baseline remains **CCB 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**. This acceptance did not verify or change the NovelAI live-generation count; any historical count requires independent reconciliation and must not be inferred from these checks.
