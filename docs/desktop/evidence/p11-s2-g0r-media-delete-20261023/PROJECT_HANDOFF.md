# P11-S2-G0R — Project Evidence Review handoff

2026-10-23 JST. **READY FOR INDEPENDENT PROJECT EVIDENCE REVIEW ONLY.** Base/control: `e0923fc625052c1454663d429d1520b794c022bf`, branch `feature/phase8-fish-audio`. Exact delivered commits and full GitHub compare are recorded in [DELIVERY](DELIVERY.md).

The only source addition is `P11S2OwnedMediaSafetyProbeTest.kt`; all other additions are in this new evidence directory. No production `src/main`, root/GC/backup/restore/SecretStore, schema/entity, Prompt, Gradle, scheduler or installer changed. Preexisting parallel/untracked files were left unread and unstaged.

**Actual final execution: 10 suites / 88 tests PASS, exit0, no failures/errors/JUnit skips.** Twelve new headless synthetic probes plus the selected existing resource, GC, backup/snapshot, S0 and S1 suites were executed in one final invocation. Initial12 are retained but not accumulated. A corrected evidence-script working-directory error occurred before the initial JVM run; it is separately preserved and not claimed as product RED. All historical RED/XML remain unchanged.

## Findings for Project

1. Live message references protect the candidate until all live references are removed. External-only manifests do not: the referenced live file is actually deleted while the manifest survives. This is `S2_DOMAIN_UNPROTECTED / OBSERVED_UNSAFE_CURRENT_BEHAVIOR`, not a fixed defect.
2. Distinct physically copied test bytes survive live deletion, direct primitive unlink and an exception following an earlier unlink. This proves a bounded file-copy capability only; no production seal, atomic publication, root migration, recovery or long-term retention is implemented.
3. Normal registered media are included in authenticated v1 exports; unknown top-level copy namespaces explicitly reject; hidden media are omitted despite successful archive export. Independent ownership therefore also requires a registered, complete backup contract, not just a new/hidden directory.
4. Desktop cleanup's repeated invocation, pre-exclusive cancellation and partial unlink failure are runtime-observed. **The real Android persistent pending-deletion chain is NOT_TESTED / NEEDS_ADAPTER.** Official145/current-fork owners were source-audited; Desktop has no consumer/injectable equivalent in this allowed source set. No fake queue substitutes for that missing runtime evidence.

Review [S2_G0R_MEDIA_OWNERSHIP_MATRIX](S2_G0R_MEDIA_OWNERSHIP_MATRIX.md), [SOURCE_AUDIT](SOURCE_AUDIT.md), [TEST_EXECUTION](TEST_EXECUTION.md) and the original [raw evidence](raw/). The index identifies exact bytes/length/SHA for logs, XML, per-stage inventories, fixtures and sources. Three unchanged production subtree identities and selected boundary blobs are separately recorded.

## Required remaining gates

- Pending-deletion end-to-end proof needs a separately approved genuine platform seam/test environment; no claim about actual Android queue resource protection is made.
- Symlink/reparse/hostile hardlink behavior, old-executable downgrade safety, power loss, GUI/Android acceptance and complete seal/backup/recovery transaction are untested here.
- G1 product decisions155 on C-A, schema/quotas/retention, source archive secrecy and old-binary isolation remain pending. S2-G1/G2 production, S3–S6, C1/C2 and task activation remain unauthorized.
- P11 is not signed CLOSED/ACCEPTED. D-038 remains blocked; Post-P8 HOLD and Public Preview A–G remain unchanged; formal baseline stays1.4.1; no merge into desktop or release.

**Stop: P11-S2-G0R Project Evidence Review Gate.** No next-slice execution is implied by this delivery.
