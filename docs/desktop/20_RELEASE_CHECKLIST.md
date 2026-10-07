# Release checklist

- baseline version/commit/schema verified
- master remains clean upstream mirror
- release based on desktop
- Android compile regression passes
- Desktop/shared tests pass
- clean install + upgrade tested
- data migration snapshot / backup / restore tested
- Character JSON and CCB PNG roundtrip Android <-> Desktop
- Prompt parity
- WorldBook parity
- SaveSlot Android <-> Desktop
- streaming/cancel/session restart
- parity rows for claimed scope are resolved
- no credentials in Git/logs/artifacts
- NovelAI provider compatibility state explicitly recorded against `49_NOVELAI_PROVIDER_CAPABILITY_DRIFT_AUDIT.md` and `14_UPSTREAM_COMPAT.md`; formal CCB parity alone does not validate today's model/sampler API matrix. A proven provider rejection of an allowed combination reopens a blocking shared/upstream compatibility defect (owner/reopen rules: `55_PHASE7_DEFERRED_OWNER_MAP.md`).
- installer/uninstaller/update path tested
- direct upstream-author authorization record retained/confirmed before public release packaging；repository standard LICENSE metadata status documented separately
