# Phase 7 B2 — Chat Image Process Card Acceptance

**B2 ACCEPTED / FROZEN / NOT MERGED TO DESKTOP**

## Frozen source and document identity

- Branch: `feature/phase7-b2-chat-image-process-card`.
- Frozen production SHA: `dde3b8f71286d8ed2aeffaf3e3a08c2a21aca75f`.
- Acceptance date: 2026-10-11.
- This record is a subsequent docs-only closeout. Its final HEAD is reported separately in the Git delivery; it is not the frozen production SHA and changes no production or test source.
- A, B1, C and B1+C integration acceptance records `69`, `71`, `72` and `74` remain unchanged. Their historical remaining-scope statements describe their respective closeout dates; this record controls the later B2 acceptance.

## Evidence and responsibility

The following already completed evidence is recorded under Project's acceptance instruction. No tests or packaging are repeated for this docs-only closeout.

| Authority | Evidence | Result |
| --- | --- | --- |
| Project independent GitHub review | B2 production implementation and R1/R2/R3 review at the frozen source SHA | CODE REVIEW PASS |
| Codex local automated verification | Native isolated: 2 suites / 2 tests | PASS |
| Codex local automated verification | Strict Desktop full: 129 suites / 1134 tests / 0 failures / 0 errors / 0 skips | PASS |
| Codex local automated verification | JDK 17 compile and diff-check | PASS |
| Codex local package verification | Exact-source complete payload: 210/210 SHA-256 comparisons | PASS |
| Codex local Windows launch smoke | Fresh isolated profile, visible main window, normal WM_CLOSE, launcher/application exit 0/0, stdout/stderr 0/0 bytes, zero residual processes | PASS |

Project reviewed GitHub source; it did not execute the Codex local tests. Those automated checks do not replace user manual acceptance. Codex validation and packaging made no real AI/NovelAI request. This record does not infer whether the user's manual acceptance used real API calls and does not revise historical NovelAI usage accounting.

Earlier RED Native gates remain historical failures, including the R2 strict run with 129 suites / 1132 tests / 1 failure in `WindowsStudioEdgeDragTest.maximized Studio outer edges remain stationary and restore remains resizable`, with foreground/Robot input diagnostics. They are not retroactively relabeled PASS or dismissed as flaky. The later controlled R3 isolation and exact-source strict full gate passed; original failure XML and diagnosis remain local evidence.

## User Windows manual acceptance and frozen behavior

The user explicitly confirmed the following on 2026-10-11. These are user-performed Windows checks, not Codex- or Project-executed tests.

| Check | Accepted behavior | Result |
| --- | --- | --- |
| B2-01 | Compact Generate Image and Image Requirements actions | PASS |
| B2-02 | Source Assistant message shows a default-expanded process card with actual cumulative design output | PASS |
| B2-03 | Internal scrolling and expansion/collapse do not stop the task | PASS |
| B2-04 | Square Stop icon is outside the process card, on its right and bottom-aligned in both expanded and collapsed states | PASS |
| B2-05 | Stop cancels only the source-owned image task, preserving unrelated chat work and saved images | PASS |
| B2-06 | Prompt design transitions to NovelAI generation in the same process card | PASS |
| B2-07 | Correct terminal states, retry and dismiss | PASS |

**USER MANUAL ACCEPTANCE: 7/7 PASS.** The approved UX contract remains in `73_PHASE7_B2_UX_CONTRACT.md`. Process output remains transient, uses genuine Designer output and does not invent model reasoning or become persisted ChatMessage content. Bounded progress processing, credential masking, genuine stage recognition and terminal flush retain the reviewed R1/R2/R3 behavior. This freeze changes no Prompt, request semantics, task ownership, cancellation authority, image saving or persistence.

## Exact-source local artifacts

- Test gate: `app/desktopApp/build/phase7-b2-r3-validation-gate/gate-summary.json`.
- Package gate: `app/desktopApp/build/phase7-b2-r3-package-gate/gate-summary.json`.
- Accepted Windows executable: `app/desktopApp/build/phase7-b2-r3-distribution/ChatChatBarDesktop.exe`.
- Application JAR: `app/desktopApp-4c2f1f7729c93c69b0135241643f6771.jar` within that distribution.
- Application JAR SHA-256: `C0D9205D4B663BD20A721501A0EA0430CCAC16BDAEAB0F25F8A0CD5673BDDD5F`.
- EXE SHA-256: `E5D45E5389E7584B9D8477EFE34543DE1069E6550560A3A3C53D164152958B8F`.

The gate directories retain commands, Java version, actual JUnit XML/statistics, logs, native diagnosis, complete manifests and launch evidence. Test-gate provenance is separate from package-gate provenance. These are gitignored local artifacts, not files committed or uploaded by this closeout. Their original review/acceptance-pending status describes when they were produced; this document records the subsequent review and user acceptance decision.

## Remaining Phase 7 boundary

**PHASE 7 IN PROGRESS / NOT ACCEPTED / NOT MERGED TO DESKTOP.** B2 is independently accepted and frozen; it does not sign the whole Phase 7 checklist or authorize the next slice.

- D Viewer previous/next navigation remains OPEN.
- P7-WIN-01 auxiliary-window maximize/restore remains OPEN.
- P7-WIN-02 auxiliary-window CCB icon remains OPEN.
- P7-WIN-03 auxiliary-window initial-size/content visibility remains OPEN.
- All other unconfirmed Phase-7-wide manual checklist items remain unsigned. `66_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md` is unchanged.
- Formal validated upstream baseline remains CCB 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`. No upstream synchronization or baseline promotion.
- No merge to `desktop`, protected-branch movement, historical NovelAI count revision or changes to Prompt, Package, Entity, runtime or data are part of this closeout.

This docs-only commit is submitted for independent Project review of the GitHub diff.
