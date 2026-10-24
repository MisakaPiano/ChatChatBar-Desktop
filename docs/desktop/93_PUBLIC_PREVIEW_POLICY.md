**执行政策更新：2026-10-25 JST / D-046。** 用户已要求 Codex 连续完成多个 phase、七项 Preview Gate 和发布，Project 改为实现后的阶段性审计。详见 [01_PROJECT_INSTRUCTIONS.md](01_PROJECT_INSTRUCTIONS.md)。下方旧 HOLD、NOT AUTHORIZED、STOP/review candidate、未签字 0/7 只按原日期解释，不能阻止本轮已授权实现/验证/集成/发布；功能状态、测试及兼容性没有因本次文档变更自动通过。真实 API/密码/.cbsave/.cbbackup/.cbback 及其内容不得上传 GitHub。

# CCB Desktop Public Preview Policy

更新时间：2026-10-12

状态：**APPROVED PROGRAM POLICY / NOT YET REACHED**

Authority: D-037。本文件定义第一个公开 Preview 的发布边界；不改变 upstream schema、Prompt 语义、parity 目标或 Phase 编号。

**CURRENT D-038 AMENDMENT (2026-10-12):** Public Preview 前的 P11 还必须实现官方 1.4.5 全应用 `.cbbackup` 兼容迁移的必要功能与独立 Gate 证据，详见 `18_DECISIONS.md` D-038 与 `95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md`。原下文仅列 `.cbsave` 的内容是 D-037 历史最低要求，**不再足以单独通过 Preview Gate**。普通 Desktop 导出仍默认不得携带 Secrets；无另行批准的 encrypted credential-transfer 不得静默导入凭据。正式 validated upstream baseline 仍是 1.4.1，本政策没有自动吸收 1.4.5 代码。

## 1. 目标与顺序

CCB Desktop 应在 P17/P18 之前开始积累真实玩家反馈，但不能把测试者数据当成一次性消耗品。

```text
P7 Image / NovelAI
→ P8 Fish / Audio
→ P9 RAG
→ P10 Long-Term Memory
→ P11 SaveSlot / Session Lifecycle
→ Public Preview Data-Safety Gate
→ Public Preview
→ P12–P16 继续开发并收集真实反馈
→ P17 Public Beta hardening
→ P18 1.0 Gate
```

这是一个 milestone，**不是新的 Phase 编号**。

## 2. Entry condition

P11 与 A–G 在同一连续交付中实现和验证，不再以 Project CLOSED / ACCEPTED 作为开工或推进前提。单对话 portability contract 必须已经由 P11 建立，包括 selected baseline 所需 legacy SaveSlot、当前 `.cbsave`、message/session state、SaveSlot 所拥有的 image/audio media、transactional restore、session lifecycle/duplicate 与 Android ↔ Desktop cross-platform archive evidence。

完整应用目录同步和 automatic multi-master merge **不是** Preview 前置；D-035 继续独立存在。**这里排除的是 live synchronization，并非完整应用 `.cbbackup` 档案迁移；后者已由 D-038 新增为 P11 和 Preview Gate 前置。**

## 3. Public Preview data promise

从第一个 Public Preview 起，persisted user data = **production-valued data**：

- 普通 Preview/Beta 升级不得要求常规清空 data directory；
- 不得为了恢复启动而静默丢弃 unknown/corrupt 用户 bytes；
- persisted schema/resource 变化必须兼容读取、安全迁移，或提供明确、用户可控、可验证的保全/恢复路径；
- migration 在适用处继续遵守 snapshot/source-retention/restart-safe 原则；
- 若候选版本不能安全保全已支持的 Preview 数据，该版本 **不可发布**，直到问题解决或存在可验证、用户可用的恢复路径；
- “仍然是 Preview”从来不是 destructive reset 的充分理由。

## 4. Public Preview A–G 工程验证清单

### A. Persistence / corruption / recovery

- atomic persisted writes 继续成立；
- Missing 与 Corrupt / ReadError 可区分；
- corrupt/read-error/partial-write 原始证据不会被 default overwrite；
- automatic backup / manual snapshot 能在 realistic persisted data 上恢复；
- restore success 返回预期数据，restore failure 不静默破坏当前有效状态；
- migration/root switch 保留 source，并遵守 mandatory snapshot / ownership / restart boundary；
- crash/process interruption/restart 不把 incomplete work 冒充 committed success。

完整 P17 Backup-Recovery Center UI 不要求提前完成，但必须有清晰 recovery procedure。

### B. Upgrade / installation safety

- 普通用户无需 Gradle/developer tooling 即可安装；
- clean install 验证；
- Preview N → N+1 installer/manual upgrade 保留 selected data root 与 supported persisted data；
- uninstall 行为与说明不得把用户数据当作 disposable；
- 没有 automatic updater 时，manual installer upgrade 足够通过本 gate。

完整 automatic updater、file associations、Open With、tray/task center、notifications 仍归 P15。

### C. Single-conversation Android ↔ Desktop portability

自动化 contract evidence 与 real-device evidence 必须分开记录。

最低真人 round-trip：

```text
Desktop session
→ export .cbsave
→ Android import
→ open and continue conversation
→ Android export
→ Desktop import
→ open and continue conversation
```

若 selected SaveSlot contract 拥有 media/RAG/Memory payload，gate 必须验证其受支持字段/资源，而不能只测纯文本 messages。

这证明 **single-conversation portability**，不宣称 whole-app live sync 或 multi-master merge。

### D. Secrets / privacy boundary

- API keys、NovelAI/Fish tokens、OAuth secrets 和 SecretStore values 不进入 ordinary SaveSlot/full-data export、可分享 backup artifact、log/support bundle，除非另行批准专门的 encrypted credential-transfer feature；
- support diagnostics 默认只含 version/commit/environment/error metadata，不含 Prompt/chat body；
- 任何包含 conversation/Prompt 内容的 diagnostics 必须显式 opt-in 并单独披露。

### E. Minimum supportability

Public tester 至少能提供：

- Desktop version / commit；
- validated upstream baseline；
- 诊断所需 OS/runtime identity；
- redacted error/crash evidence；
- 找到 data root、创建 backup、restore/recover 的明确步骤。

完整 P15 request-log/diagnostics surface 不要求提前完成，但不能只剩一句“坏了”而没有可用证据。

### F. Release truth / compatibility

每次 Public Preview 前：

- 重新检查 latest upstream drift 并分类 release impact；
- 不自动 sync，不自动 baseline promotion；
- 与 Preview 相关的 security/data-loss/external-API break 必须解决，否则阻塞发布；
- release notes 写明 exact Desktop commit 与 validated upstream baseline；
- 明确披露 P12–P17 未完成能力和重要 known limitations；
- public packaging 前 retained/confirmed D-016 direct upstream-author authorization evidence。

### G. Official 1.4.5 full-app archive portability (D-038 additional blocker)

本小节是 D-037 A–F **新增、且必须独立通过的 G gate**，不是替代 C 的单会话 `.cbsave`。

- P11 首先按官方 `v1.4.5 @ 1198a13c` 的 `.cbbackup` envelope + authenticated encryption + resource manifest/inventory + portable path mapping + transactional prepare/restore/restart/rollback 合同实现安全兼容；不直接复制 Android 原始数据目录，也不在 Desktop 临时创建无权威 schema。详见 `95`。
- **真人/实机迁移门禁**：Android 1.4.5 → Desktop full archive → reopen/use（用户数据、图片、语音、记忆、草稿及受支持的资源可保留）；Desktop → Desktop export/import/restart；支持的 Desktop → Android 1.4.5 导入也需有兼容证据。尚未实现的 P12–P17 专属域，不能无提示丢弃；须可验证保留或在提交前明确拒绝。
- 正常分享的 Desktop `.cbbackup` 默认排除凭据；密码保护格式可读取不等于已获准搬运 Secrets。对含 Fish/NovelAI 等凭据的 Android 备份，用户必须获得明确处理说明，绝不默认装入 Windows SecretStore；专用加密凭据迁移须另获批准并通过安全验证。
- 错密码/损坏/取消/磁盘不足/中断、未知字段或资源缺失必须拒绝伪成功，并保持原数据 root、snapshots 和原 archive 可恢复；数据丢失或 silent partial restore 是 **release blocker**。
- P17 完整 Backup-Recovery Center、P15 完整 updater 和 D-035 live multi-master sync 不因本 gate 自动提前完成。P18 的 1.0 full parity gate 继续独立存在。

## 5. First Preview 可保留 PENDING 的范围

P12 AI Authoring / Repair、P13 Moments、P14 Community、P15 完整 OS integration/updater/diagnostics/tray、P16 upstream automation、P17 完整 onboarding/Backup-Recovery Center/broad hardening 均可继续 PENDING。

这些缺口必须在 release notes 可见，不能伪装成已完成 parity。

## 6. Feedback ownership

Preview 开始后：

- Public Preview 成为真实 UX/performance/environment edge cases 的主要来源；
- Program Control 将反馈分类为 blocker、parity defect、data-safety defect、performance/environment issue 或 later-phase enhancement；
- P12–P16 不需要等待所有 Preview UX 请求即可继续；
- data-loss/security/destructive migration/broken supported SaveSlot portability 优先级高于 feature schedule；
- P17 使用累积 Preview evidence 做 Public Beta hardening。

## 7. P17 / P18 边界

P17 Public Beta hardening 比本 gate 更广，继续拥有 corrupted data、cancellation、long tasks、high DPI、IME、large cards/worldbooks/SaveSlots/images、proxy/VPN、Windows version matrix 与完整 Backup-Recovery Center/onboarding。

P18 仍是最终 full parity / release gate。Public Preview approval 本身不会把任何 PENDING row 变成 EXACT/EQUIVALENT。

## 8. Evidence discipline

必须区分 Project GitHub/source review、Codex automated test/build/package evidence、用户 real-Windows evidence、Android real-device evidence、external tester report。不得互相冒充。

## 9. Release naming

本政策不冻结具体版本号。未来可使用 `0.x Public Preview`、`Preview 1` 或另行批准的命名，但不得暗示已达到 1.0 parity。

## 10. Exit condition

Codex 按 01 / D-046 集中完成 A–G 的必要实现、验证和发布，逐项记录真实结果；不等待 Project 前置签字，不因未签字 0/7 停在 review candidate。必要人工验收直接交用户；已知问题和未覆盖环境如实披露，普通缺陷可以后续处理。已复现数据丢失、凭据泄露或已承诺核心迁移失败须修复，不能靠修改 PASS 状态规避。使用用户已有发布授权，不重复请求同一许可；本次政策提交本身不是实际发布或工程 PASS。
