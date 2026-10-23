# 168 — P11-S2-G2A Typed Credential and Inert Intent: Project Acceptance

**CURRENT PROJECT DECISION — 2026-10-23 JST — P11-S2-G2A CLOSED / QUALIFIED ENGINEERING ACCEPTED.**

This docs-only registration records Project's independent Code Review decision supplied by the user. It accepts only the bounded internal, nonwired engineering slice authorized by165/166. It does not authorize further implementation or accept production data migration.

## Frozen Git identity

- Starting control: `648014ffda7c9e50029de8474faf36e011783bad`.
- Tested source/test/primary evidence: [c44a241c6df1dc4461f5409b0fc14c2f7a2968b7](https://github.com/MisakaPiano/ChatChatBar-Desktop/commit/c44a241c6df1dc4461f5409b0fc14c2f7a2968b7). This is one source/test/main-evidence commit from the control.
- Final delivery: [075b86c8443c7f1aa26801c6d75eb8daf54368c2](https://github.com/MisakaPiano/ChatChatBar-Desktop/commit/075b86c8443c7f1aa26801c6d75eb8daf54368c2). The two subsequent commits, `942152621142bd6535acad4fc183d95ae5be9357` and `075b86c8`, change evidence documents only.
- Remote `desktop`: `5ef95428421f7c693b010ac88f51d470804ab07a`; no merge or branch movement is authorized here.
- [Full reviewed delivery diff](https://github.com/MisakaPiano/ChatChatBar-Desktop/compare/648014ffda7c9e50029de8474faf36e011783bad...075b86c8443c7f1aa26801c6d75eb8daf54368c2).

## Accepted scope and independent evidence

Only two internal, unwired Kotlin production components and three test classes were added. `DesktopCredentialImportPlan` classifies typed credential metadata and explicit conflicts; `RecoverableIntentContract` validates pure candidate intent invariants. The full-backup Skill received one feature-specific documentation update consistent with AGENTS maintenance. Existing `src/main` files were not modified; existing-file projection contains759 unchanged files. The Desktop tree gains two files, so its whole tree is not claimed unchanged.

| Evidence | Recorded result and attribution |
|---|---|
| Initial environment refusal | Zero tests executed; exit1; preserved separately from functional test results. |
| First executed new-suite run |40 tests:39 PASS/1 FAIL,0 errors/skips,exit1. Fixture lacked Archive work directory; historical RED and source snapshot retained. |
| Final bounded execution |13 suites/150 tests,0 failures/errors/skips,exit0. New G2A42 plus actually rerun existing108; no accumulation with previous runs. |
| Project source review | Five source Git blobs match tested snapshots. |
| Project JUnit review | All13 original XML files individually checked, total150 tests. **Project did not rerun Gradle.** |
| Project raw-byte review |15 original evidence artifacts independently recomputed SHA-256/byte length, all matched. |
| Codex self-verification |262 indexed raw artifacts checked by Codex. **Project's15-item review is not a claim of full262-item independent verification.** |

Authoritative originals remain under [p11-s2-g2a-20261023](evidence/p11-s2-g2a-20261023/DELIVERY.md): handoff, mapping/intent matrix, nonwiring proof, commands/exits, RED/GREEN JUnit, source/fixture identities and diagnostics audit. No original evidence is rewritten by this acceptance.

The actual selected official1.4.5 version declaration is **`app/app/build.gradle.kts`**, not the outer `app/build.gradle.kts`. The delivery [provenance addendum](evidence/p11-s2-g2a-20261023/SOURCE_VERSION_PROVENANCE_ADDENDUM.md) corrects the earlier path attribution and preserves the original raw record. Selected official source remains `1198a13cf3008de1358b8a69aa5e40b64797c8d9`; it is not a general upstream sync.

## Limits and stop boundary

- Trusted production decoder and target observer do not exist; metadata assertions are not authenticated runtime capabilities.
- Pure intent validation is not a durable journal, cross-root transaction or implemented ALL_OLD/ALL_NEW recovery. Existing restore guards remain unchanged.
- Community protected sink, cross-root transactions, real DPAPI/Portable, old-binary write prevention, real Android/Windows data migration and Moments seal remain incomplete. Synthetic model checks cannot substitute for these capabilities or platform acceptance.
- This acceptance promotes only **G2A's qualified engineering status**. No Feature Parity item becomes EXACT/EQUIVALENT; full P11 and D-038 remain unresolved, with Post-P8 and Public Preview HOLD unchanged.
- Formal validated upstream baseline remains **ChatBar1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**.

This registration changes documentation only: no Kotlin/tests/raw evidence/Prompt/schema/business behavior changes, no test rerun, no real user data, no desktop merge. **Stop after docs-only commit/push. G2B/G2C and real-data processing require separate authorization.**
