# P11-S2-G0 — Project Evidence Review handoff

2026-10-23 JST. **TEST-ONLY PROBE COMPLETE / PROJECT EVIDENCE REVIEW REQUIRED**. Control/parent a73ce170137ce084add3f7b59eddead0c57b469d, feature/phase8-fish-audio. Safe fast-forward preserved Project CURRENT151–153 and all untracked protected files/cache. No production src/main, schema, Prompt, restore, SecretStore, root/GC/backup/Scheduler or build file changed.

## Findings

- **Raw preservation observed:** actual manual snapshot/restore and real automatic-backup scheduler include the ordinary sentinel; real migration service copies it and its safety snapshot, commits an isolated temporary bootstrap and retains source bytes. These generic byte-copy capabilities do not validate a Moments seal, privacy policy, quota or future downgrade contract.
- **Explicit refusal observed:** FullBackupService refuses an unknown top-level root entry with UNCLASSIFIED_ROOT_ENTRY. Archive refuses an unknown synthetic Entity before Prepared; S0 still reads/counts it, S1 blocks. Journal refuses a top-level sentinel path. No production admission was relaxed.
- **Silent omission observed:** a deliberately synthetic hidden subtree inside images is skipped while FullBackup export succeeds. Source bytes survive, but the inspected archive lacks them. This is a placement-specific counterexample and is not a recommended storage scheme.
- **GC gap observed:** an independent sentinel blob survives candidate filtering; a live image referenced only by its external manifest is deleted because live cleanup scans entities, not the sentinel domain. Future ownership/GC cannot borrow this behavior as protection for shared resources.
- **Recovery limit observed:** owned child0→71→0 restores the registered row after interrupted apply while sentinel stays unchanged outside journal rows. This is not proof of new-domain transactional recovery. Cancel-before-install leaves only destination ownership infrastructure and retains source bytes.
- **Admission/downgrade limit:** current Startup/Portable admit the sentinel under a real cooperative root lock. No older executable or installer was run; old-version write prevention remains NOT_TESTED/BLOCKED. Marker-only protection is not established.

The full per-entry file/method/blob, expected contract, runtime observation, adapter need, legacy concern and safety effect is in **S2_PARTICIPATION_MATRIX.md**. It separates STATIC_SOURCE_PROVEN, SYNTHETIC_RUNTIME_OBSERVED, CURRENT_UNSUPPORTED, NEEDS_ADAPTER, NOT_TESTED and BLOCKED. No ordinary behavior was changed to make a probe pass.

## Actual tests and identity

One new test file: P11S2RootParticipationProbeTest.kt, twelve independent synthetic cases. Existing sources are unchanged. Final Desktop: **9 suites/109 tests**, SharedCore: **3 suites/49 tests**; **158 distinct reported PASS, zero failure/error/skip** across two final commands, exits0/0. Earlier nine-probe and109-test exploratory GREENs are retained and not added; old S1 95 is not added. There was no functional or compilation RED; NOT_APPLICABLE is recorded rather than manufacturing a failure.

Two SharedCore symlink branches explicitly report unavailable; two MigrationMaterializer symlink cases lack executed-branch proof. Their actual platform coverage is NOT_TESTED/INCONCLUSIVE despite zero JUnit skips. No GUI/user, real Android, old installer, power-loss, full pending-deletion queue or post-authority downgrade trial is claimed.

Source-bound identities: raw/production-tree-identity.json (three unchanged production trees), raw/production-source-identities.json (24 directly related production files), raw/test-source-identities.json (final probe and reused wire/codec/child). All provide exact Git identities; file receipts also include working-byte SHA-256/length. raw/desktop-xml and shared-xml, command logs/exits and result JSON are authoritative. Raw probe-records contain complete logical root inventories before/intermediate/after, actual sentinel/archive fixtures and hashes. Held ownership locks are length-only while held and hashed after release; all ordinary payloads are hashed. No source/fixture digest is inferred from a name.

The implementation/source commit and final receipt are in DELIVERY.md and the external handoff. Full diff is control SHA to final delivery; only test-source + this new evidence directory are permitted. Production and historical evidence remain unchanged. Skill review found no stale production behavior and no skill update is permitted/needed for a temporary experimental probe.

## Safety and next gate

All new probe roots are individually created temporary synthetic parents with explicit cleanup bounds; no ambient data root/bootstrap or SecretStore resolution is used. New probes have no app container, real credentials/provider, Moments repository/task, or GUI. Only known test-created JVMs can be terminated; actual child exits are retained. Protected user files/attachments remain KEEP/UNREAD/UNHASHED/UNMODIFIED/UNSTAGED; old experiment cache remains unstaged. No official upstream fetch, baseline change, desktop merge or publishing.

Required future adapters include explicit domain classification and coverage, sealed resource ownership/GC, complete restore rows/journal phases, version/downgrade admission and approved quota/retention/privacy handling. R01/R02/R03/R07 remain user product decisions. The absence of these adapters is not fixed in G0.

**Stop: P11-S2-G0 Project Evidence Review Gate.** No production preservation domain, S2-G1/G2 implementation, S3–S6 or Public Preview is authorized. P11/D-038/Post-P8 HOLD/Preview and formal baseline remain unchanged.
