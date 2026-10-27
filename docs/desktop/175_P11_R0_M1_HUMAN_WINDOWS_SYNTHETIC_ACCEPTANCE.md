# 175 — P11-R0-M1 Real Windows Synthetic Console Acceptance

**2026-10-23 JST — USER-DIRECT HUMAN WINDOWS SYNTHETIC CHECK: PASS WITH SCOPE.** This is a Project record of user-observed behavior in the existing chat, **not** a new Codex test execution, real user archive authorization, or a release gate.

## Exact authority and evidence boundary

- Feature HEAD before this docs-only registration: `f9c18f6a3424da763dac4c5682767c33f79f26e6` on `feature/phase8-fish-audio`; prior M1R1 production-tool correction/test source `379dc49825079163fc395f72c4d634b4cf34c398`.
- M1R1 delivery and raw artifacts: [handoff](evidence/p11-r0-m1r1-20261023/PROJECT_HANDOFF.md), [DELIVERY](evidence/p11-r0-m1r1-20261023/DELIVERY.md), [raw evidence index](evidence/p11-r0-m1r1-20261023/raw/index.json).
- Earlier [173 R0 qualified tooling acceptance](173_P11_REAL_ARCHIVE_READONLY_R0_PROJECT_ACCEPTANCE.md) remains valid for its synthetic/tooling scope. M1R1 corrected the actual prepare-only Gradle dependency configuration failure; independently examined Project result: initial prepare RED exit1, prepare-before GREEN exit0, seven final JUnit XML=117 PASS/0 failures/errors/skips and Gradle exit0, prepare-after GREEN exit0, PowerShell launcher plain0/no-console2/inherited-options2, source snapshots and 19/19 selected SHA/length samples. No new Project/Codex test is claimed by this registration.
- **Human evidence is user-reported screenshots in this ChatGPT Project conversation, not files exported into Git**. It shows a real interactive **Windows PowerShell** invocation of the reviewed R0 launcher against the single generated **synthetic** code84/v1 encrypted file. Screenshot contains a local machine path, so the original image is not re-uploaded or committed as public evidence. Host/terminal provenance is user-provided; Project did not independently remote-control Windows or read a private file.
- Exact selected synthetic build path (not real user data): `H:\ChatChatBar-Desktop\app\desktopApp\build\readonly-r0\m1r1\SYNTHETIC-code84-encrypted.cbbackup`. Existing producer `M1SyntheticFixture` and [synthetic fixture identity](evidence/p11-r0-m1r1-20261023/raw/fixture/identity.json) declare source size 1048 bytes, synthetic SHA `63069e451fa2f2d652593f07c5a281f3b6aa2908cf7f8fa2066d2c4ac56e2908`, version1/code84, and **public dummy** password `FAKE_G2B_PASSWORD_NOT_REAL`. The second screenshot itself does not independently prove the file hash; these identities derive from existing Codex evidence.

## Two real Windows attempts — preserve both, do not relabel

| Step | USER-DIRECT observation | Verdict |
| --- | --- | --- |
| Attempt 1 | A real `Archive password (hidden):` prompt appeared; screenshot shows no echoed password; `R0=READ_ONLY; S0=REJECTED_AUTHENTICATION; S1=NOT_RUN; G2B=NOT_RUN` | **Expected fail-closed on an unsuccessful authentication; first attempt is NOT a successful decrypt.** Source of input mismatch not independently established. |
| Attempt 2 | Same launcher and synthetic selected file; real hidden-password prompt, no visible echoed password; `R0=READ_ONLY; S0=FORMAT_READABLE; ENVELOPE=1; MANIFEST=1; APP_CODE=84; ENCRYPTED=true; S1=NOT_APPLICABLE; G2B=COMPLETE_KNOWN_SUBSET; G2B_RECORDS=3; SOURCE_BYTES=UNCHANGED; RESTORE_SUPPORTED=false; FULL_PORTABILITY_ACCEPTED=false`. Shell prompt returned. | **PASS WITH SCOPE**: interactive Windows console password collection without visually echoing characters and complete bounded S0/S1/G2B source inspection for this synthetic file. |

Both captures were user-supplied; no automatic test harness observed the actual human typed/pasted characters. Screenshot 2 does not show an explicit `$LASTEXITCODE`, so independently observed exit-code claim is limited to shell returning and terminal result messages, **not** an asserted measured exit0. The user's later explanation indicates a long test password was manually typed and could instead be pasted; this is a human-operability lesson, not a programmatic credential trace.

## Password input usability / privacy

- For this **public dummy** password, pasting into the hidden `System.console().readPassword()` Windows PowerShell prompt is an appropriate and easier input method; pasted characters must remain hidden. Prior instructions should have mentioned it.
- **Real secrets** have different custody concerns: clipboard history, sync, terminal/remote capture and other processes can expose clipboard contents. Do not instruct users to paste real passwords without disclosing that risk. Prefer local protected input/password-manager entry under the user's explicit control; no passwords in CLI arguments, env vars, files, logs, Git or chats.
- Neither screenshot proves secure memory erasure, arbitrary terminal compatibility, Windows privilege isolation, JVM/OS forensics or absolute TOCTOU resistance.

## Remaining authority and Preview critical path

- **R0-M1 HUMAN WINDOWS SYNTHETIC TERMINAL CHECK: CLOSED / PASS WITH SCOPE.** A further repetition or M1 authentication diagnostic Codex task is unnecessary for this user-observed result; the unsubmitted diagnostic draft should not be executed.
- **Real user .cbbackup: NOT READ / NOT AUTHORIZED.** Future action requires the user to designate **one** local file and give explicit single-invocation offline read-only approval. Never infer that from the successful synthetic test. R0 remains size/version bounded; S0 readability is not restore eligibility.
- **G1B cross-root safe data + credentials transaction remains OPEN, G2C NOT AUTHORIZED**, current `CREDENTIAL_APPROVAL_REQUIRED` remains, real Windows DPAPI/profile portability and Android↔Desktop full-app restore still not accepted.
- Formal validated upstream baseline ChatBar **1.4.1** remains. **P11 PENDING / D-038 BLOCKED / Post-P8 HOLD / Public Preview HOLD** remain. Do not promote feature parity, merge `desktop`, synchronize upstream, mutate production/restoration, or claim a Preview release.
- User execution preference for next work: **Codex-first** for automation, builds, synthetic checks, evidence and local command-line work; ask the user for a physical Windows action only when it cannot safely be automated. Project independently audits Git evidence. No real-data operation is implied.

**STOP: R0-M1 HUMAN WINDOWS SYNTHETIC PASS REGISTERED / REAL FILE READ NOT AUTHORIZED / PREVIEW HOLD.**
