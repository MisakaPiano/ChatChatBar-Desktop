# Release checklist

**CURRENT phase-vs-release boundary (2026-10-12, post-merge):** Phase 7 engineering milestone is **CLOSED / QUALIFIED ACCEPTED / INTEGRATED** into GitHub `desktop` by user-authorized FF-only (`5dfc6a50edcab01e8e866fa34611898a5e364a09`), with exact evidence in `92_PHASE7_POST_MERGE_CLOSEOUT.md` and limitations in `91`. **Public release/installer readiness remains NOT APPROVED**; each release checklist item below retains its existing independent evidence requirements. Historic Native RED, untested Group 15 and privacy K, incomplete live Provider capability, temporary clipboard-file OS cleanup, 1.4.1 upstream baseline and unvalidated newer source remain disclosure/release-risk obligations. Neither this merge nor P7 qualified acceptance silently fulfills the public release checklist. No Phase 8/upstream sync was started.

**HISTORICAL pre-merge phase-vs-release boundary (2026-10-12):** Phase 7 is **QUALIFIED ENGINEERING ACCEPTED / MERGE-ELIGIBLE / NOT MERGED** under `91_PHASE7_FINAL_PROJECT_ACCEPTANCE.md`; the final non-Native bounded automated gate is documented in `16`, APNG Windows receiver 3/3 PASS in `89`. This **does not** grant public release readiness: untested Group 15, K/other partial user checks, historical Native/synthetic RED, incomplete live NovelAI provider matrix, temporary clipboard storage limitations, selected upstream 1.4.1-only compatibility with limited post-baseline image exceptions, and latest observed `c9071842` drift must be assessed at their relevant release gate. Prior P7 package/manual-pending language below is **historical evidence at its original date**, not the current milestone status. Do not sign release checklist items from milestone acceptance alone.

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
