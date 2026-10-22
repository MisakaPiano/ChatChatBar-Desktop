# G1A actual execution and evidence limits

2026-10-23 JST / Windows / Temurin JDK17.0.20.1+1. Control `ed8f54236cd29b822f3a8875218b3ba7043eab5f`. Gradle was run from `app/`, offline, one worker, no configuration cache, single-use daemon, with `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`.

## Runs (never accumulated)

| Run | Actual scope | Raw authority |
|---|---|---|
| Initial | 1 suite /17 tests /0 failures/errors/skips /exit0; 1m59s | `run-probe.ps1`, `probe-run.log`, `probe-exit.txt`, `initial/` XML/source/records |
| Expanded source-backed wire oracle | 12 suites /112 tests /0 failures/errors/skips /exit0; 1m41s | `run-final.ps1`, `final-run.log`, `final-exit.txt`, `final/` XML/source/records |
| Final verified source | **12 suites /112 tests /0 failures/errors/skips /exit0**, all17 new tests re-executed after strengthening same-input password validation/original-input retention; 2m41s | `run-final-verified.ps1`, `final-verified-run.log`, `final-verified-exit.txt`, **`green/`** XML/source/records |

Current totals are read from `raw/green/results.json`; only that final invocation counts. Initial17 and preceding112 are not added to it; previous G0R88/G0158 are not reused as new tests. There was no production feature fix and no functional failing run: **RED=NOT_APPLICABLE**. Partial secret updates and credential refusal are asserted as current observations, not fabricated permanent CI failures or production fixes. Historical RED/XML elsewhere are untouched.

Post-test evidence copying initially failed for several long protected-blob destination filenames under `raw/final-verified/` (Win32 path length). Its explicitly marked partial copy remains preserved, never used as complete final authority. Original build outputs were unchanged; copying the same completed run into the shorter `raw/green/` succeeded. Full source/fixture/inventory byte identity is then checked against the retained artifacts; no system path settings were changed and no tests were rerun for this copy-only repair.

The first source used a token-shaped Community Preferences String for category probing. Source audit identified that a real official CommunitySession String must contain its own JSON object. The expanded run uses the fixed official six-field shape and official version/source-root-shaped metadata; the initial fixture is retained and not advertised as an official domain-shaped CommunitySession positive. The final verification improvement additionally authenticates the exact wrong-password input with the correct password first, then preserves that original encrypted fixture for independent review.

## Exact invocation

Scripts preserve full environment setup, working directory and immediate LASTEXITCODE recording. Final command:

```powershell
.\gradlew.bat :desktopApp:test --tests '*P11S2CredentialTransactionBoundaryProbeTest' --tests '*WindowsSecretStoreTest' --tests '*WindowsSecretRootResolverTest' --tests '*DesktopSecureSettingsRepositoryTest' --tests '*DesktopSecureModelRepositoryTest' --tests '*DesktopSecureEmbeddingRepositoryTest' --tests '*DesktopFullBackupArchiveTest' --tests '*DesktopBackupReadOnlyInspectorTest' --tests '*DesktopFullBackupServiceTest' --tests '*DesktopFullBackupCommitJournalTest' --tests '*DesktopFullBackupProcessTest' --tests '*DesktopStartupGateTest' --offline --no-configuration-cache --console=plain --no-daemon --max-workers=1
```

Initial command selects only `*P11S2CredentialTransactionBoundaryProbeTest` with the same remaining flags. Stdout/stderr are combined unedited; raw attributes disable text normalization/whitespace rewriting. `jdk-version.log` identifies the actual JDK. `WindowsDpapiSecretProtectorTest` is intentionally excluded: no real DPAPI operation is allowed.

## Selected final suites

| Suite | Tests |
|---|---:|
| P11S2CredentialTransactionBoundaryProbeTest | 17 |
| WindowsSecretStoreTest | 7 |
| WindowsSecretRootResolverTest | 2 |
| DesktopSecureSettingsRepositoryTest | 6 |
| DesktopSecureModelRepositoryTest | 5 |
| DesktopSecureEmbeddingRepositoryTest | 8 |
| DesktopFullBackupArchiveTest | 12 |
| DesktopBackupReadOnlyInspectorTest | 18 |
| DesktopFullBackupServiceTest | 10 |
| DesktopFullBackupCommitJournalTest | 4 |
| DesktopFullBackupProcessTest | 3 |
| DesktopStartupGateTest | 20 |
| **Total distinct final cases** | **112** |

Existing Settings regression uses an in-memory test store; Model/Embedding regressions use explicit temp WindowsSecretStore with a test protector. The **new** boundary evidence always uses actual WindowsSecretStore with explicit Fake Protector, never a replacement SecretStore. Existing independent ProcessTest covers registered data rows only; its printed real child exit codes (including intentional71) are in final XML. That unchanged suite retains its own test-created temporary journals/logs for diagnostics by design. G1A neither enumerates/sweeps those directories nor claims they are the new32 probe fixture parents.

## New fixture/ownership evidence

The17 new JUnit cases include a16-input wire matrix (eight credential categories/absence variants × two encryption modes), resulting in **32 distinct new fixture lifetimes**. Each creates its own explicit `p11-s2-g1a-*` temporary parent, owned data root, sibling fake secret root, staging and inspection workspace. Every lifetime captures `created`, operation-specific before/intermediate/after and `released-final`, then reacquires/releases the lock and removes only its validated fresh temporary parent. Coordinator closure and cleanup receipts are preserved. Held native lock is deliberately length-only; released lock is hashed.

`raw/green/probe-records/` contains:

- complete relative path inventories and per-file length/SHA for all source/data/secret/journal/scratch state at captured stages;
- exact fake protected blob snapshots at each stage, allowing first/second-write old/new byte comparisons without printing logical secret values;
- sixteen actual v1 source wire fixtures and the additional original encrypted wrong-password fixture;
- outcome/fault-stage and cleanup receipts. Reinstantiating store/journal is labelled object reopen, not OS/process identity switching.

The Fake Protector is deterministic/reversible and only enables testing the real persistence class. No claim of cryptographic strength, actual DPAPI user binding or portable ciphertext follows. Test source/raw fixtures intentionally contain unmistakable fake inputs. Diagnostic logs/XML must not contain their values; `raw/diagnostic-secrecy.json` records an automated scan of new fake values/passwords and fake literals from the selected existing regression sources. Source/fixture bytes are excluded from that diagnostic scan because they are deliberately synthetic inputs.

## Identity and strict bounds

`raw/production-tree-identities.json`: all three production `src/main` trees match the control, with empty working diff. `production-source-identities.json`:21 selected boundaries, exact control Git blobs/raw SHA/length and workspace identity. `test-source-identities.json`:15 new/reused test/corpus/codec identities. `official-source-identities.json`:nine pinned official sources/skill, exact Git bytes and immutable URLs. `raw/evidence-sha256.json` covers every other raw artifact, excluding itself; committed Git bytes are independently rechecked before delivery.

All new probes supply roots by constructor, never call WindowsSecretStore.create or consult real LOCALAPPDATA/USERPROFILE. Existing resolver tests pass explicit synthetic environment maps. No protected user file, real backup, token, real SecretStore, GUI, Android, installer, provider or upstream synchronization was used. No production patch, schema, Prompt, export/import guard or journal behavior was modified.

**NOT_TESTED:** real DPAPI, OS identity/profile switch, Portable key transfer, native move/permission failure and fallback, hard crash/power loss with secret participants, arbitrary leaked secrets in unknown content, full official Android export/import, real user acceptance. Byte-buffer wiping is bounded to observed owned save arrays; immutable strings and native heaps are not proven erased. Injected post-publication writer failure and injected CancellationException are explicit fault models, not physical crash observations.

**Stop at P11-S2-G1A Project Evidence Review Gate.** User-approved protected direct import remains the product target. G1A does not implement it, approve S2-G2, C1/C2 advanced flows, credential-inclusive export, P11/D-038 closure, Public Preview or desktop merge.
