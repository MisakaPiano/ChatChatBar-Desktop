# Release checklist

## Public Preview Gate（P11 后）

状态：**APPROVED POLICY / NOT YET EXECUTED**；详细控制：D-037 / `92_PUBLIC_PREVIEW_POLICY.md`。

- P11 SaveSlot / Session Lifecycle CLOSED / ACCEPTED
- Preview candidate 基于 live `desktop`，记录 Desktop version、validated upstream baseline、commit 与 schema/Package versions
- latest upstream drift 已重新做 release-impact assessment；未验证 drift 不被静默宣称兼容
- clean install PASS；Preview N → N+1 manual/installer upgrade 保留 data root 与已提交业务数据
- automatic/manual backup + snapshot + restore 使用真实 persisted data 验证；失败路径不静默覆盖当前有效数据
- corrupt/read-error/partial-write evidence 保留，不能 default-overwrite；migration/root-switch 继续满足 source retention / safety snapshot / restart boundary
- crash/restart、cancel/network/interrupted task 不伪造 durable success；已提交 session/message/media 重启可读
- `.cbsave` automated contract PASS + Desktop → Android → continue → Android export → Desktop import → continue 真机 round-trip PASS
- Character/FormatCard/WorldBook 既有跨端 evidence 未被 Preview 改坏
- secrets/API keys/tokens 不进入 ordinary backup/export/log/support artifact
- 最小 support diagnostics 可定位版本/commit/OS/错误；默认不包含 Prompt/chat body；完整 request-log surface 仍可 P15 PENDING
- installer/manual upgrade path 可供普通 tester 使用；完整 automatic updater、file association、tray/notification 不要求在 Preview Gate 完成
- release notes 明确 P12–P17 尚未完成范围、known issues、数据目录/备份/恢复路径以及 Preview ≠ 1.0
- D-016 作者直接授权记录在 public Preview packaging 前 retained/confirmed；repository LICENSE metadata 状态单独记录
- 用户显式批准 Public Preview 发布

**Gate PASS 后的数据承诺：** persisted user data 视为 production-valued；后续普通 Preview/Beta 升级不得以测试版为理由要求常规清档。

---

## 1.0 / Final Release Gate

**CURRENT phase-vs-release boundary (2026-10-12):** Phase 7 is **CLOSED / INTEGRATED / QUALIFIED ENGINEERING ACCEPTED** on live `desktop @ 5dfc6a50edcab01e8e866fa34611898a5e364a09`; this still does not grant Public Preview or 1.0 readiness. P7 final qualified engineering evidence remains governed by `91_PHASE7_FINAL_PROJECT_ACCEPTANCE.md`; the final non-Native bounded automated gate is documented in `16`, and APNG Windows receiver 3/3 PASS in `89`. The first public Preview is separately governed by D-037 / `92_PUBLIC_PREVIEW_POLICY.md` after P11. This **does not** grant public release readiness: untested Group 15, K/other partial user checks, historical Native/synthetic RED, incomplete live NovelAI provider matrix, temporary clipboard storage limitations, selected upstream 1.4.1-only compatibility with limited post-baseline image exceptions, and latest observed `c9071842` drift must be assessed at their relevant release gate. Prior P7 package/manual-pending language below is **historical evidence at its original date**, not the current milestone status. Do not sign release checklist items from milestone acceptance alone.

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
