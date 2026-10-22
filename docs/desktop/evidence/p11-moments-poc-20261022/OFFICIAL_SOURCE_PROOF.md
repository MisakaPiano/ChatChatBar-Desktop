# 官方来源与执行身份

结论：FEASIBLE_WITH_PROVEN_SCOPE。这是固定源码的 debug 测试构建，不是官方签名发行 APK，也不构成真机验收。

官方格式来源：ChatBar 1.4.5 `1198a13cf3008de1358b8a69aa5e40b64797c8d9`。正式 baseline 仍是 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。本轮起点 `62b93e824333540657474d43dc63723d6e90ab24`。

从本地已固定的官方 Git 归档重新解出独立工作区；构建前、实验后对 378 个主 Kotlin/构建文件做 Git blob 身份检查。Windows CRLF 规范化后完全匹配；只增加 androidTest harness，未修改官方主文件、Prompt、调度策略或 Entity。没有读取或断言 preset/seed 内容。

- [源码身份结果](official-source-proof.json)
- [19 份重点审计文件的原始字节 SHA-256、大小及符号位置](raw/audited-source.json)
- [APK 身份与安装命令](raw/install-results.json)
- [完整离线构建原始输出](raw/20261022-173857-532-build/build-output.log)

审计入口（全部来自上述官方提交）：

| 文件 | 本轮用途 |
|---|---|
| `.agents/skills/chatbar-app-backup/SKILL.md`、`chatbar-moments/SKILL.md` | 正式归档、凭据默认行为与 Moments 数据生命周期 |
| `MomentEntities.kt`、`MomentRepository.kt` | 全字段序列化、五种任务状态、仓库实际读取 |
| `BackupArchive.kt`、`BackupJsonStream.kt`、`BackupDataRegistry.kt` | 真正生成/提取、inventory、路径 token 和 canonical target |
| `BackupEntityValidator.kt` | 官方接收能力；不能替代 POC 对未知字段和引用的严格拒绝 |
| `AppBackupService.kt:265` | `resetDeviceConfirmations` 仅重置确认，不强制关闭 Moments |
| `BackupPreparedSeal.kt`、`BackupRestoreTransaction.kt`、`AppBackupBootstrap.kt` | 隔离恢复事务与正式启动入口分析 |
| `MomentScheduler.kt:49,144` | 真实 runOnce、任务准入、模型未配置失败路径 |
| `ManageViewModel.kt:648`、`ChatBarApp.kt:543` | 设置启用及初始化均可 kick 调度器 |
| `MomentBackgroundReliability.kt`、`DeletionCoordinator.kt:91` | 确认标志用途、历史 Post 与图片保留边界 |

实际运行环境：新建 API 34 google_apis x86_64 AVD，名字 `ccb-p11-moments-poc-20261022`，serial `emulator-5584`，专属 ADB 5045。SDK/Gradle 使用已有离线材料。安装前拒绝 IPv4/IPv6 非 loopback OUTPUT；末次核查仍有首条拒绝规则。没有物理设备操作。

原始 RED 保留：首次构建受沙箱缓存锁权限限制；升级到获授权构建权限后成功。初始模拟器在构建期间启动缓慢，正常关闭后在构建完成时重新启动。首次 consume 因宿主传入路径遗漏 `output/` 而缺文件；修正实验调用后成功。均不是生产源码修复。

真实 instrumentation：produce、consume、scheduler 最终各 1 个测试通过，独立 runner 输出和真实 JUnit listener XML 在 raw 下。ADB 的退出 0 不独立作为通过依据。

技能相关性复核：本轮只增加独立实验工具和证据，没有改变现有生产技能所述行为，不更新技能为“已支持封存”。
