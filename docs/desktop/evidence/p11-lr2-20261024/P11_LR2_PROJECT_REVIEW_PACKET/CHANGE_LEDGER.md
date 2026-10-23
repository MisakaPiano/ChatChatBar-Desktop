# LR2 change ledger — Project review candidate

Start `33789eba37eda8952a806671bcfd76eb95cead1c`, feature branch only. Remote desktop `5ef95428421f7c693b010ac88f51d470804ab07a` unchanged. Earlier181 production corrections are not reimplemented.

All new Kotlin is under `tools/desktop/interop/lr2/src`, injected only into the test classpath. No application `src/main`, formal Gradle, official Prompt, Entity/schema, SecretStore, Startup, full-backup restore admission or G2C changed.

1. Added bounded forward ZIP32 cross-check and opt-in full-backup semantic harness. Original supplemental scanner accepted a deliberately inconsistent central name: actual central-red1 FAIL retained. New reader refuses that archive and authenticates EOF before results.
2. Reused current Codec, strict official transport DTOs, registered serializers and S1. Added explicit unsupported/unknown reports; no full-restore admission. Independent code84/v1 fixtures do not emit modern-only fields.
3. Added inner v8 claims, original logical namespace and resource-ID coverage. Actual new-tool defects were corrected against pinned/shared official code: colon-containing logical IDs are not materialized Windows names; resource IDs exclude extension; imageCount counts references, including repeats. Separate synthetic RED files remain.
4. Added separate-JVM SaveSlot restore/reopen, file witness, source equality, supported message/session/RAG/Memory/media/voice comparison and synthetic post-commit halt/tamper checks. Initial synthetic session oracle failed because it omitted the existing monotonic next-turn rule; fixed test expectation, not production. Real resource lookup failure was corrected and the SAME retained isolated root subsequently verified.
5. Added C-A test-only raw byte/three-image-edge/copy-capacity preflight, G1B candidate design audit and external physical/platform checklist. No persistence contract implemented or selected.
6. Re-ran update checker as a regression. No native GUI/installer/DPAPI evidence claimed; no duplicate package build of unchanged production source merely to create a new count.

Diagnostics were refined to fixed stage/limit codes after a later real full-backup refusal. No raw exception text, source name or value is emitted. Failures remain failures and no safety limit was increased to admit the real source.

Skill review: full-backup and SaveSlot skills were read. Their production ownership/limits and test-entry facts remain accurate; this opt-in engineering harness is documented in its local README, so no Skill rewrite is required. Unknown attachments/handoff/cache files were not staged, altered or cleaned.

See FINITE_TEST_MATRIX for per-run results and source-provenance caveats; REAL_ARCHIVE_PRIVATE_READ_RECEIPT for private outcomes; ORIGINAL_EVIDENCE_INDEX for byte/hash identity. Final commit identities belong to PROJECT_HANDOFF/delivery receipt; no Project acceptance is asserted here.
