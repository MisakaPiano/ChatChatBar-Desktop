# P11-S2-G1A — Project Evidence Review handoff

2026-10-23 JST. **TEST-ONLY EVIDENCE / READY FOR INDEPENDENT REVIEW.** Control `ed8f54236cd29b822f3a8875218b3ba7043eab5f`, branch `feature/phase8-fish-audio`. Exact published source/evidence commits, full diff and raw verification are in [DELIVERY](DELIVERY.md).

Only new `P11S2CredentialTransactionBoundaryProbeTest.kt` and this independent evidence directory were added. No production src/main, Gradle, schema, Prompt, SecretStore API, restore guard/journal/root, GUI or installer changed. The preexisting protected user files/cache remain unread/unhashed/unmodified/unstaged.

## Findings

1. Real WindowsSecretStore supports isolated logical namespaces and whole single-key replacements under the tested Fake Protector/failure seams. A first pre-publication failure preserves both old blobs; a second failure can leave **A_NEW/B_OLD (`MULTI_KEY_PARTIAL`)**. A deliberately post-publication writer exception can report failure despite a complete new blob already existing. These are not real DPAPI/power-loss proofs.
2. Existing model/entity policy really does restore its previous key when entity persistence fails; this protection is preserved. If that rollback write itself fails, an explicit consistency exception remains and old data/new key can coexist. No durable whole-root recovery follows from per-entity rollback.
3. Registered data journal recovery does not coordinate independent key writes. Controlled key-first, data-first, row-exception and injected-cancellation sequences produce **`OBSERVED_NONATOMIC_SEQUENCE / CROSS_DOMAIN_ATOMICITY_NOT_PROVEN`**. Actual StartupGate recovers its registered data rows before a synthetic business callback but supplies no key transaction participant. No secret path was submitted to the journal and no production credential importer was simulated as already implemented.
4. Sixteen independently generated official145 v1 wire inputs cover plain/password modes and absent/preferences/Model/legacy+singleton Embedding/AppSettings credentials. S0 authenticates and reports trusted categories, but formal Archive still refuses all fourteen credential-bearing inputs with `CREDENTIAL_APPROVAL_REQUIRED`; two credential-free inputs yield closable Prepared. Same-source correct/wrong password is separately tested, with the original negative input preserved. Source and target fake secret bytes are unchanged throughout inspection.
5. Logical IDs/categories prevent tested filename aliasing, but do not establish provider/account/source-archive identity. Same-name different-ID models are not mapped; same-ID changed endpoint still gets that ID's key. Legacy vs singleton embedding must be mapped explicitly. CommunitySession has no current protected DesktopCredentialKey target.

See [SECRETSTORE_TRANSACTION_GAP_MATRIX](SECRETSTORE_TRANSACTION_GAP_MATRIX.md), [SOURCE_AUDIT](SOURCE_AUDIT.md), [TEST_EXECUTION](TEST_EXECUTION.md), and original [raw evidence](raw/). Final counts/exit are those in `raw/green/results.json` / `final-verified-exit.txt`: **12 suites /112 tests /0 failures/errors/skips /exit0**. Initial and expanded executions remain separate, never accumulated. No functional RED was required or fabricated. Historical RED/XML is preserved.

## Product policy and remaining gates

CURRENT158 approves normal **protected direct import of compatible user-owned keys**, not blanket credential-presence rejection or default secret stripping. Current production refusal is therefore an unresolved implementation gap. This task deliberately does **not** remove it before a verified mapping/sink/unified transaction replaces it. Ordinary shareable Desktop export still excludes SecretStore; encrypted credential-inclusive export remains a separately approved feature.

Project G1B needs to freeze source/target identity mapping, protected old/new secret state and publication outcomes, unified data/secret durable intent and startup recovery, cancellation/rollback failure behavior, and scope/root/old-binary protection. Real DPAPI/profile/Portable, cross-domain child crash/power loss and official Android/runtime user acceptance remain NOT_TESTED.

**Stop: P11-S2-G1A Project Evidence Review Gate.** No S2-G2 production, S3–S6, C1/C2 advanced UX, task activation, upstream/baseline change, desktop merge or release. P11/D-038/Post-P8/Public Preview HOLD remains unchanged.
