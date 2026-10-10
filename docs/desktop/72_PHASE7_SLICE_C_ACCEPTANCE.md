# Phase 7 Slice C — Studio Preview Workspace acceptance

**SLICE C — USER ACCEPTED / FROZEN / NOT MERGED.**

- Frozen production SHA: `849ca384344edae0a9effb4bcf169ac3532427e1` on `feature/phase7-slice-c-studio-preview`.
- This acceptance record is a subsequent docs-only commit. Its final feature HEAD is recorded separately in the Git handoff; it does not change the frozen production SHA.

## Frozen behavior

- Scheme A: full-height right preview, default approximately 49% split, draggable divider, 116dp vertical thumbnail rail, preview focus and narrow-window right-side expansion.
- C-03: Tokens, Undo and Redo share the fixed footer row with Generate/Stop; left-side controls and right-side generation controls retain their existing callbacks.
- C-06: maximized outer-edge dragging no longer causes clipping; resizing works after restore.
- C-07: the noninteractive blank area at the right preview's top supports native window dragging, including standard restore-and-move from maximized state.

## Acceptance ownership and evidence

| Decision or check | Owner | Result |
| --- | --- | --- |
| Independent GitHub production-code review | ChatGPT Project | PASS |
| Final Windows manual acceptance, including C-03, C-06 and C-07 | User | PASS |
| Final strict Desktop regression, JDK 17 | Codex local execution | 125 suites / 1094 tests / 0 failures / 0 errors / 0 skips |
| Exact-SHA package file comparison | Codex local execution | 210/210 SHA-256 PASS |
| Fresh isolated Windows launch smoke | Codex local execution | PASS; normal WM_CLOSE; launcher/application exit codes 0/0 |

Automated results above are Codex local reports, not tests personally executed by Project. The user supplied the final Windows acceptance decision. No real NovelAI API was called during this Slice C verification.

Local evidence directory: `app/desktopApp/build/phase7-slice-c-r2-caption-gate/`.

Final accepted EXE: `app/desktopApp/build/phase7-slice-c-r2-caption-distribution/ChatChatBarDesktop.exe`.

Logs and distributables are gitignored local evidence and are not committed with this record. This docs-only closeout does not rerun tests or rebuild the package.

## Freeze and phase boundaries

- Slice A, B1 and C are separately frozen. B1 and C remain on different branches and have not been converged or merged.
- B1 remains at `feature/phase7-image-novelai @ 30c5703e944146cbfc2fe617374b69e7e2ad8119`. Its `71_PHASE7_SLICE_B1_ACCEPTANCE.md` exists on that branch. Its absence from this older C branch history does not delete, overwrite or revoke B1 acceptance.
- Slice A acceptance remains governed by `69_PHASE7_SLICE_A_ACCEPTANCE.md`.
- B2 chat-image task Stop controls, D Viewer navigation and P7-WIN-01/02/03 image auxiliary-window issues remain unfinished. Main-window C-06 acceptance does not close auxiliary-window P7-WIN-01.
- Unconfirmed Phase-7-wide items in `66_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md` remain unchecked. This local slice acceptance is not Phase 7 acceptance.
- `desktop` remains at `b3ecd41267906526e7b603972f7388e59c90648d`. No feature integration, merge, rebase, cherry-pick or upstream synchronization is authorized by this record.
- Formal validated baseline remains CCB 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`; no 1.4.4 promotion or sync is performed.
- Prompt, Package, Entity, runtime semantics and user data are unchanged. This acceptance record does not revise historical NovelAI live-request accounting.

**PHASE 7 IN PROGRESS / NOT ACCEPTED / NOT MERGED.**
