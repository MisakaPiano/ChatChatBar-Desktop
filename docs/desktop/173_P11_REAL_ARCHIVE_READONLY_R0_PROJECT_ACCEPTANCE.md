# 173 — P11 Real-Archive Read-Only R0 Project Acceptance

**CURRENT PROJECT DECISION — 2026-10-23 — P11 REAL-ARCHIVE READ-ONLY R0 — QUALIFIED TOOLING ACCEPTED.**

Docs-only registration of the user's supplied independent Project review. Acceptance covers only the bounded reader proven with synthetic tests; it does not authorize reading a real user file or implementing another engineering slice.

## Authority and unchanged boundaries

- Tested tooling/source and main evidence: `ea02b0cc9974a837761c74d114c19c73a4ddea6c`.
- Delivered/start HEAD: `0265667bac010e93963fcaa740971bf2874e5c76`, branch `feature/phase8-fish-audio`.
- Remote desktop reference: `5ef95428421f7c693b010ac88f51d470804ab07a`, unchanged.
- Original [R0 evidence and handoff](evidence/p11-real-archive-readonly-r0-20261023/PROJECT_HANDOFF.md), [execution](evidence/p11-real-archive-readonly-r0-20261023/TEST_EXECUTION.md), [delivery](evidence/p11-real-archive-readonly-r0-20261023/DELIVERY.md) and [committed verification](evidence/p11-real-archive-readonly-r0-20261023/COMMITTED_VERIFICATION.json) remain historical originals, unmodified by this registration.

Six new files under `tools/desktop/interop/readonly-r0/` provide test-classpath reader/tests, opt-in build helper, launch/preparation wrappers and local-use documentation. Existing production source, formal Gradle configuration and recovery gates were not modified. The tool produces separate S0 format, S1 Moments graph and G2B known-location census reports only. FORMAT_READABLE, census completeness and graph classification do not authorize restore or establish full portability. It provides no restore, SecretStore write, user data-directory operation or directory scanning capability.

## Executed tests and independent review

| Evidence | Accepted fact |
|---|---|
| Final actual JVM run | **7 suites /117 PASS /0 failures/errors/skips /exit0** |
| Final test composition | **23 new tests +94 actually rerun existing tests**; not a cumulative historical count |
| Earlier JVM run | 7 suites/114 PASS retained separately; not added to117 |
| Actual launcher RED | PowerShell split a Java option; plain and encrypted/no-console launches exited1; real failure evidence retained |
| Corrected actual launcher | plain0 /no-console2 /inherited-options2; outer script exit0; intentional refusals are expected safe outcomes |
| Project independent verification | Read all7 final JUnit XML individually; checked17 raw SHA-256/byte-length samples and5 key test/launcher snapshots |
| Codex self-verification | 171/171 indexed raw files checked by Codex; independently attributed, not represented as Project's full-file review |

The final Kotlin remains the tested snapshot; launcher correction was separately checked by direct execution. All original RED/GREEN, XML, source snapshots and digest/length evidence remain preserved. This docs-only acceptance registration does not rerun Gradle or any launcher.

## Private-file and password conditions

**Human interactive hidden-password input: NOT TESTED. Real archives: NOT TESTED.** Synthetic injected password input and safe no-console refusal do not prove the human terminal experience. The reader uses local masked-console input and refuses when unavailable; no plaintext password-file, argument, environment or ordinary stdin fallback is accepted.

Before any real-file read, first confirm password masking with an independent **synthetic encrypted archive in a real terminal**, under an explicitly authorized local check. Only afterward obtain the user's explicit designation of one ordinary local `.cbbackup` and permission for **one offline read-only invocation**. Neither that preliminary terminal check nor real-file access is executed or newly authorized by this registration. The user retains an independent original; no source archive, password, token or private raw report is uploaded to GitHub. Follow the [tool's local instructions](../../tools/desktop/interop/readonly-r0/README.md) and the private custody boundaries in [167](167_P11_REAL_DATA_INTEROP_READONLY_PREPARATION_NOT_AUTHORIZED.md).

The selected file path **can appear in local process arguments**. Category-only output and hidden password entry are not operating-system-level complete confidentiality. Privileged monitoring, terminal recording, JVM/OS memory, paging and crash artifacts remain outside proven guarantees. If masking or file identity cannot be established, stop rather than substitute an unsafe input route.

S0 limits and version scope remain unchanged; G2B supports only its declared official145/code84/v1 subset. Other entities, media, nested SaveSlots or unknown structures may be INCOMPLETE/UNSUPPORTED without implying source corruption. This tooling acceptance is not full archive semantic validation, safe key migration, Android interoperability or durable cross-root recovery acceptance.

## Governance and stop

Formal validated upstream baseline remains **ChatBar1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**. Official1.4.5 @ `1198a13cf3008de1358b8a69aa5e40b64797c8d9` remains a selected backup-format reference, not a baseline promotion. [172 G2B acceptance](172_P11_S2_G2B_CENSUS_PROJECT_ACCEPTANCE.md) remains limited to its own slice.

**P11 PENDING / D-038 BLOCKED / Post-P8 HOLD / Public Preview HOLD / G2C NOT AUTHORIZED.** No Feature Parity EXACT/EQUIVALENT promotion, desktop merge, real-data access or next-slice implementation follows.

Registration preflight confirmed expected local/remote HEAD, no tracked changes, and no other active same-directory Codex task in the available inventory. Six unknown local attachment/handoff/cache files remain unread, unhashed, unchanged and unstaged. Skill relevance was reviewed: existing full-backup feature facts remain accurate, so no Skill edit is required or permitted here. Only173 and minimal notices in Source Map/Current State change; Kotlin, tests, raw evidence, Skill, Prompt and schema remain untouched.

**STOP: R0 PROJECT ACCEPTANCE REGISTERED / REAL-DATA READ NOT AUTHORIZED.**
