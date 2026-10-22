# Existing-production identity and zero-wiring proof

Control `648014ffda7c9e50029de8474faf36e011783bad`. Only two new `internal` production files are allowed/delivered in Desktop `backup/`; no existing `src/main` file changes. Complete final Git comparison is in `DELIVERY.md`; source/test/skill-only patch is `CODE_DIFF.patch`.

- `raw/existing-production-identities.json`:21 selected boundary Git blobs and canonical byte SHA/length, plus actual workspace-byte SHA/length. Exact workspace bytes also match the preexisting G1A record for all21. Includes Archive/Codec/Policy/Mapping/Service/SecretStore/credential policies/Journal/Startup and root boundaries.
- `raw/production-projection.json`: original three complete main tree IDs; tracked diff was empty while the two new source files were untracked. A committed projection check is recorded after staging/commit: removing exactly those two additions must reproduce every original path/blob in all three trees.
- `raw/new-source-identities.json` and `raw/green/source/`: exact tested five-source bytes, Git blob IDs and SHA-256/length. `raw/red/source/` preserves the earlier RED source snapshot.
- `raw/nonwiring-callsite-audit.txt`: reference audit across all three `src/main` trees yields only the two new files for the new contract names. No call from GUI, Main, Service, Startup, SecretStore, Repository or scheduler exists. New imports are ModelTemplate, existing DesktopCredentialKey, Java URI and UUID; no filesystem, coroutine/Provider or storage writer dependency.
- No serialization annotation or schema registry entry is added. The context UUIDs/digests are in-memory supplied assertions. No root marker/manifest/journal exists for this model.
- Official/source identity records list fixed local Git objects, not a fetched/synced upstream tree. Baseline JSON is unchanged.

The whole Desktop main tree ID necessarily changes because of two new files. It must **not** be described as unchanged. Its **existing-file projection** is unchanged. The two other main trees are entirely unchanged. Documentation/skill changes do not alter any runtime entry point.

Current safety guard `CREDENTIAL_APPROVAL_REQUIRED` and S0/S1 `restoreSupported=false` remain byte-identical and are exercised by the final run. v2 cannot enter current Prepared/scheduleRestore. New plan/check results have separate unconditional false authorization properties and no conversion to Prepared.

No new context, data ownership or secret root resolver is called. New pure components cannot read an archive or write a root. Existing regression tests use their own synthetic temp roots and fake protectors as already designed; their IO is not new production wiring.
