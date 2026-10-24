**用户权限边界已同步（2026-10-25 JST）。** [权限边界](USER_AUTHORITY_AND_PROJECT_REVIEW_BOUNDARY.md)与[开发原则](01_PROJECT_INSTRUCTIONS.md)记录用户指令：Project 仅做后置阶段性审查；未经用户采纳的新限制仅为建议，不撤销、收窄或暂停已有授权。只暂停真实阻断所影响的动作，其他已授权工作连续推进到 Preview。诊断与修复不自动扩张为真实数据/凭据写入、合并或发布权限；具体动作按已有用户授权执行，不重复索取。真实数据、存档和凭据不上 GitHub，包含 Actions Secrets、日志与产物。

# CCB Desktop Public Preview Policy

**CURRENT R07 USER POLICY / 2026-10-23:** [158](158_P11_S2_G1_R07_DIRECT_IMPORT_PASSWORD_AND_CREDENTIAL_POLICY.md) clarifies authenticated full `.cbbackup` including user-owned API credentials is a valid direct-import target, not categorically denied merely for key presence. Current password UI/v1 codec do not yet support full SecretStore mapping/transaction or v2 GUI restore. Data safety, no secret leakage, account mapping, cancellation/crash rollback and D-038 continue mandatory. Ordinary shareable Desktop exports exclude SecretStore by default; encrypted credential-inclusive export needs distinct approval/verification. Preview Gate remains BLOCKED.

更新时间：2026-10-12

状态：**APPROVED PROGRAM POLICY / NOT YET REACHED**

Authority: D-037。本文件定义第一个公开 Preview 的发布边界；不改变 upstream schema、Prompt 语义、parity 目标或 Phase 编号。

**CURRENT D-041 AMENDMENT (2026-10-20):** Public Preview Gate B 新增 **最低应用内更新发现能力**（手动检查、新版提醒/说明、Preview/prerelease 通道与 Desktop 版本比较、可信官方下载页入口），加 Windows 安全手动覆盖升级。**不要求完整自动下载/安装**；这项用户已批准但尚未实现，详见 `18_DECISIONS.md` D-041。P11+D-038 门禁、既有数据安全承诺和 P15 完整 updater owner 不改变。

**CURRENT D-038 AMENDMENT (2026-10-12):** Public Preview 前的 P11 还必须实现官方 1.4.5 全应用 `.cbbackup` 兼容迁移的必要功能与独立 Gate 证据，详见 `18_DECISIONS.md` D-038 与 `95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md`。原下文仅列 `.cbsave` 的内容是 D-037 历史最低要求，**不再足以单独通过 Preview Gate**。普通 Desktop 导出仍默认不得携带 Secrets；无另行批准的 encrypted credential-transfer 不得静默导入凭据。正式 validated upstream baseline 仍是 1.4.1，本政策没有自动吸收 1.4.5 代码。

## 1. 目标与顺序

CCB Desktop 应在 P17/P18 之前开始积累真实玩家反馈，但不能把测试者数据当成一次性消耗品。

```text
P7 Image / NovelAI
→ P8 Fish / Audio
→ P9 RAG
→ P10 Long-Term Memory
→ P11 SaveSlot / Session Lifecycle + A–G 工程验证（同批连续推进）
→ Public Preview
→ P12–P16 继续开发并收集真实反馈
→ P17 Public Beta hardening
→ P18 1.0 Gate
```

这是一个 milestone，**不是新的 Phase 编号**。

## 2. Entry condition

P11 与 A–G 在同一连续交付中实现和验证，不以 Project CLOSED / ACCEPTED 作为开工前提。单对话 portability contract 由 P11 实现验证，包括 selected baseline 所需 legacy SaveSlot、当前 `.cbsave`、message/session state、SaveSlot 所拥有的 image/audio media、transactional restore、session lifecycle/duplicate 与 Android ↔ Desktop cross-platform archive evidence。

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

**D-041 首版 Preview 新增必备，尚未实现：**

- 应用内手动「检查更新」入口；识别并对比 Desktop Preview/prerelease 与 stable 通道，不能把官方 Android upstream 版本当 Desktop 客户端版本；
- 可信新版本号、发布时间、更新说明、官方发行下载链接，使用默认 Windows 浏览器打开；
- 离线、无效/不可信元数据、网络错误时当前版本仍可用，不伪造已更新状态，也不自动执行未知程序；
- 版本发现与安装/覆盖升级的数据保留证据分别核验，默认不得清空实际 Preview 用户数据和受保护凭据；

- 普通用户无需 Gradle/developer tooling 即可安装；
- clean install 验证；
- Preview N → N+1 installer/manual upgrade 保留 selected data root 与 supported persisted data；
- uninstall 行为与说明不得把用户数据当作 disposable；
- 没有 full automatic updater 时，**最低应用内更新发现功能已通过的前提下** manual installer upgrade 足够通过本 gate；只有外部手动下载安装包、应用内无法发现新版不再足够。

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

Codex 按 01 / D-046 集中完成 A–G 的必要实现、验证和发布，逐项记录真实结果；不等待 Project 前置签字，不因未签字 0/7 停在 review candidate。必要人工验收直接交用户；已知问题和未覆盖环境如实披露，普通缺陷可以后续处理。已复现数据丢失、凭据泄露或已承诺核心迁移失败须修复，不能靠修改 PASS 状态规避。合并/发布按用户实际已有授权和真实产物执行，诊断与修复本身不自动扩张该权限；已有许可不重复请求，Project 不新增签字门槛。本次政策同步不是实际发布或工程 PASS。
