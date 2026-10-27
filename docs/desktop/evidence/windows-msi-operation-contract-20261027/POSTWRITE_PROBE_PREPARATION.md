# Synthetic post-write fault preparation

Historical preparation checkpoint. The subsequent [condition correction and full1114 identity](POSTWRITE_CONDITION_R02.md) supersede the activation/next-step text below; original raw evidence is unchanged.

P3 engineering preparation while DL1 handles the independent P1 copy. **No MSI/VM/real Registry operation executed by Codex; no native rollback acceptance.** Issued1112 and prepared1113 are unchanged.

`synthetic_write_fault.c` is a synthetic-only read-only deferred failure probe. The factory's explicit `--write-probe-build` option adds:

- Authenticated-by-build metadata: exact main-JAR file identity, source-image SHA-256, compiled probe source/hash/export identity. Both Binary streams join the same authored stream contract; all cabinet bytes remain unchanged.
- Type51 setter5001 and type1025 synchronous impersonated deferred DLL5002, after InstallFiles4000 / WriteRegistryValues5000 and before Finalize6600. No async/continue/no-impersonate/rollback/commit substitution is accepted.
- Activation only by `CCB_SYNTHETIC_FAIL_AFTER_WRITES=1 AND NOT REMOVE~="ALL"`. Ordinary profile is refused; normal synthetic builds lack the probe. No service/startup/business runtime wiring.
- Deferred callback accepts only its exact synthetic protocol/identity/path/digest shape. It reads its own synthetic version ProductCode/directory values and the expected new main-JAR bytes. File handle denies writes/reparse/multiple hardlinks; SHA is streamed within a bounded limit. It never writes data, Registry, credentials or cleanup.
- After both proofs: `CCB_SYNTHETIC_NEW_FILE_AND_REGISTRY_OBSERVED`, then `CCB_SYNTHETIC_INTENTIONAL_FAILURE_AFTER_WRITES`, then1603 to invoke standard MSI rollback. Bad/unknown evidence also fails but uses `CCB_SYNTHETIC_FAULT_EVIDENCE_UNVERIFIED`; the future native oracle must reject that as a valid fault experiment.

Actual current-source tests: **66 tests/12 suites +7 actual full default-transform tests =73 PASS**,0 failures/errors/skips,exit0. This is not accumulated with earlier runs. The66 include22 graph/active-session cases,38 existing affected regressions and6 probe cases. Probe cases inject fake Registry results, use only one tiny invented file, verify no mutation and actual PE exports. Compiler production/test exits0 under `/W4 /WX`. No genuine new-feature RED occurred; no RED was fabricated. Earlier original guard/host failures remain separately retained.

The factory pins the new main-JAR digest so old content cannot satisfy proof merely because the unchanged launcher exists. Source selection comes from the actual File/Directory/Component graph and the newly generated image. The DLL marker/property alone is insufficient; actual compile identity, stream digest, full resource/action graph and explicit sequence/condition checks are required.

Next: build the new full1114 opt-in fixture, verify both Binary streams and restricted-session compatibility, then only in the user's isolated guest run native fresh/upgrade failure cases. A1603 result is insufficient: independently verify actual NEW evidence occurred, standard rollback completed, all OLD file/registration identities returned (or absent-new state for fresh install), nonpackage data retained, source MSI unchanged and no unresolved processes/locks. Mid-file partial-copy faults, shared-component behavior, old-cache compatibility and ordinary Shortcut handling remain separate unfinished items. No Preview/P11 acceptance is implied.
