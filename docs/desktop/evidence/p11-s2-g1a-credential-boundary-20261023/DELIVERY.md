# G1A exact delivery receipt

2026-10-23 JST. `feature/phase8-fish-audio`.

- Verified remote/control parent: `ed8f54236cd29b822f3a8875218b3ba7043eab5f`.
- **Test-source + original evidence commit:** [`c3f6cc4deff2b0c710f908b0e85dcb036336daba`](https://github.com/MisakaPiano/ChatChatBar-Desktop/commit/c3f6cc4deff2b0c710f908b0e85dcb036336daba).
- [Complete source/evidence diff](https://github.com/MisakaPiano/ChatChatBar-Desktop/compare/ed8f54236cd29b822f3a8875218b3ba7043eab5f...c3f6cc4deff2b0c710f908b0e85dcb036336daba): one new398-line test source; all remaining additions are this task's independent evidence. The subsequent commit only adds this delivery receipt; its exact final SHA is reported with delivery.
- [Project handoff](PROJECT_HANDOFF.md), [transaction gap matrix](SECRETSTORE_TRANSACTION_GAP_MATRIX.md), [execution](TEST_EXECUTION.md), [source audit](SOURCE_AUDIT.md), [raw artifacts at exact source commit](https://github.com/MisakaPiano/ChatChatBar-Desktop/tree/c3f6cc4deff2b0c710f908b0e85dcb036336daba/docs/desktop/evidence/p11-s2-g1a-credential-boundary-20261023/raw).

**Final actual execution: 12 suites /112 tests /0 failures /0 errors /0 skipped, exit0**, including17 new cases; prior runs17 and112 are retained separately, never accumulated. No functional RED/production fix is claimed. A post-test long-destination-path copy error is preserved as a marked partial evidence copy; the same completed run was fully captured as `raw/green/` and all429 original build artifacts compared byte-for-byte. No system path setting changed.

The32 new fixture lifetimes released native root locks/coordinators and removed only their own owned temporary parents. Seventeen source archives and exact fake protected blob snapshots are retained. Diagnostic scan covered15 logs/XML and26 known synthetic literals with no values found. Fake protection is reversible test code, **not DPAPI or encryption-strength evidence**.

Post-commit verification read a Git archive and checked **1773/1773 indexed raw files** for exact length/SHA, plus byte equality of the index itself. Index SHA-256: `1b0c1f6f39b22c5ebe4213ba2d8db2ecc3564dfb02a892d1576f43a1a7697809`. It also checked21 unchanged production boundaries, three unchanged `src/main` trees,15 test/helper identities and9 pinned official source identities. Verification command in the original task workspace (which retains the test build artifacts):

```powershell
python docs/desktop/evidence/p11-s2-g1a-credential-boundary-20261023/raw/verify-evidence.py c3f6cc4deff2b0c710f908b0e85dcb036336daba
```

Observed: `SINGLE_KEY_ATOMIC` at the tested publication seams; `MULTI_KEY_PARTIAL` after second-write failure; existing single-entity rollback and explicit rollback failure; registered-row recovery does not roll back an independent secret write, so `OBSERVED_NONATOMIC_SEQUENCE / CROSS_DOMAIN_ATOMICITY_NOT_PROVEN`. Formal Archive still rejects credential-bearing sources while S0 authenticates/read-classifies them. Protected direct import is the already approved product target, not delivered production functionality. Source account/provider identity, Community session target mapping, real DPAPI/profile/Portable and true data+secret process-crash recovery remain gaps/NOT_TESTED.

Before each commit, skill relevance was reviewed: no existing production facts changed, so no skill edits are needed or authorized. No production code, restore admission, SecretStore/root/Gradle/schema/Prompt, CURRENT policy, GUI/Android/installer, real profile/backup/token, upstream baseline, desktop branch or release changed. All preexisting protected user files/cache remain untracked and untouched.

**Stop: P11-S2-G1A Project Evidence Review Gate.** Independent Project review is required. No S2-G2, S3–S6, C1/C2 advanced UX, credential-inclusive export, task activation or P11/D-038/Post-P8/Public Preview HOLD promotion is authorized by this receipt.
