# Release checklist

更新：2026-10-25 JST。依据[权限边界](USER_AUTHORITY_AND_PROJECT_REVIEW_BOUNDARY.md)。清单记录工程/验收证据，不授予 Project 新的前置批准权，不自动扩张 Codex 的数据写入、合并或发布权限。

## Public Preview：与 P11 连续完成

| 项目 | 核验内容 |
| --- | --- |
| A | 原子持久化、备份/snapshot/恢复、corrupt/read-error 原文保留、取消/中断/重启、迁移 source retention；失败不伪成功 |
| B | 可安装 Windows 构建；clean install、N→N+1 保留 data root/业务数据；D-041 应用内可信版本发现；真实启动/关闭/进程退出与恢复路径 |
| C | 受支持 .cbsave/legacy contract；Desktop→Android→继续→导出→Desktop→继续及媒体/RAG/Memory 的实际证据 |
| D | 本地凭据按已有授权使用、Windows OS 保护和正确绑定；普通分享导出/支持包不泄露密钥；真实数据和凭据不进 GitHub |
| E | Desktop version/commit、validated baseline、OS/runtime/error identity、数据目录和实际可用恢复步骤 |
| F | release-impact drift、真实兼容范围和 known issues、对应权利依据与用户实际已有发布授权 |
| G | .cbbackup 官方格式/实际输入版本分开核验，受支持全应用数据/资源保全；Desktop 往返/重启及声明的 Android 方向；错误密码/损坏/取消/空间不足/失败/回滚不丢原件或静默部分成功 |

详细技术核验见 [93](93_PUBLIC_PREVIEW_POLICY.md)、[95](95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md)、[105](105_DESKTOP_SHUTDOWN_PROCESS_LIFETIME_GATE.md)。Character/FormatCard/WorldBook 既有互通不能被本次修改改坏；正式 baseline 和声明的 schema/Package/request 语义必须真实。

P11 和 A–G 同批实现，不等 Project CLOSED/ACCEPTED 才开始 Gate。有效证据复用，按改动影响补测；必要真人步骤由 Codex 直接交用户。不把未审计/未签字 0/7 当全局 STOP，不把记录 PASS 当实际完成。普通缺陷、未覆盖环境和未来功能可如实登记后续；已复现的数据丢失/泄露或核心路径失败在同一任务中修复。

最终合并/公开发布按用户实际已有授权与真实产物执行，Project 只指出具体缺陷，不能凭清单新增审批。诊断与修复本身不自动授权原件改写、真实根/凭据写入、自动合并或发布；已有许可不重复索取。

## 隐私与发布说明

真实用户数据、API Key/Token/密码、.cbsave/.cbbackup/.cbback 及解包内容不得进入 GitHub，含历史、PR/Issue、附件、Actions Secrets/日志/cache/artifact/Release；只提交代码、脱敏结果和人工合成样本。诊断默认不含 Prompt/chat body。

release notes 记录实际 commit、validated baseline、兼容范围、已知问题、未完成 P12–P17 能力和数据目录/备份/恢复步骤。Preview ≠ 1.0；从首次公开 Preview 起，用户持久化数据按有价值数据维护，普通升级不要求常规清空。

## 1.0 目标（不前置到 Preview）

最终范围继续覆盖 Android/shared/Desktop 相关回归、Prompt/WorldBook/SaveSlot parity、流式/取消/Session 重启、安装卸载升级、数据迁移恢复、对应公开发行权利依据，以及完整声明范围内的功能矩阵。NovelAI 等外部 Provider 当前能力与官方逻辑 parity 分开验证，不能用共享逻辑证明今天的服务端参数全兼容。

历史清单及阶段验收保存在 Git；只对各自原范围成立，不自动满足未来公开发布声明，也不自动撤销已有开发授权。
