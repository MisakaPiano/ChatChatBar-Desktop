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
- Current P7 selective official image forward ports (60 / 14) are independently validated in 68; this does not validate the full current provider model/sampler matrix. Upscale's isolated Curated request parameter is not Studio model support. Live generation remains 1/8; Enhance/Upscale evidence is fake/local only. Use the new current-upstream isolated package and pending manual checklist 66, not historical P7 packages.
- installer/uninstaller/update path tested
- direct upstream-author authorization record retained/confirmed before public release packaging；repository standard LICENSE metadata status documented separately
