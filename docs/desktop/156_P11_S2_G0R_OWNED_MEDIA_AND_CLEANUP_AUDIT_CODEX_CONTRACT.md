# 156 — P11-S2-G0R Owned Media / Pending Deletion Safety Probe — Codex Contract

**2026-10-23 JST — TEST-ONLY CONTRACT READY / NO PRODUCTION PERSISTENCE OR GC PATCH AUTHORIZED.**

## 0. 目标、来源与固定边界

[154 G0 Project Review](154_P11_S2_G0_ROOT_BACKUP_RECOVERY_PROJECT_EVIDENCE_ACCEPTANCE.md) 发现：`DesktopOwnedImageCleanup.deleteUnreferenced()` 无法保护独立封存 manifest 所引用的共享 `images/` 资源；普通 snapshot 会保留 sentinel，而隐藏媒体子路径在 FullBackup 中成功导出但被遗漏。[155 G1 policy package](155_P11_S2_G1_DECISION_PACKAGE_AND_PORTABILITY_SAFETY.md) 推荐初版独立 copy-on-seal，但生产实现仍未授权。本轮 Codex 只做**headless 纯测试 G0R**，拿到候选资源在更完整 live 删除/清理链中的风险证据和未来 adapter 必要接口。不能因为测试观察到丢文件就顺手修改生产。

- Repo `MisakaPiano/ChatChatBar-Desktop`，分支 `feature/phase8-fish-audio`；G0 已测试源码 `42a7269a1e21a2ad1cf1b1fb3ece447d8b702d9a`、最终交付 `cbdfaa85ffc24de707504512450a8e135c82adbd`；Project 后续 docs-only 提交请以**远端实查 HEAD** 为新控制点。先核对本地未提交/未跟踪内容；只能安全 FF，不能 reset/force/覆盖现有工作。
- 先读取 00/10/21、相关 Desktop Skills、13/14、[151 S1 acceptance](151_P11_S1_MOMENTS_READONLY_GRAPH_PROJECT_ACCEPTANCE.md)、[152 S2 readiness](152_P11_S2_PERSISTENT_PRESERVATION_READINESS_AND_DECISIONS.md)、[154](154_P11_S2_G0_ROOT_BACKUP_RECOVERY_PROJECT_EVIDENCE_ACCEPTANCE.md)、[155](155_P11_S2_G1_DECISION_PACKAGE_AND_PORTABILITY_SAFETY.md)；再只读源码 `DesktopOwnedImageCleanup`、`DesktopCharacterResourceStore`、现有 pending-deletions/删除队列实际持久 owner（按小范围源码定位）、`DesktopFullBackupMapping.inventory`、`DesktopDataOperationCoordinator`、当前根安全/快照相关测试。官方 Moments 删除/保留规则仅按选定官方固定源码阅读；不改任何官方 Prompt。

## 1. 唯一允许的变化

- 仅 `app/desktopApp/src/test/**`、必要的 sharedCore `src/test/**` 与**新独立证据目录** `docs/desktop/evidence/p11-s2-g0r-media-delete-YYYYMMDD/`；不得修改任何 src/main、正式域/schema、Model/Entity/Prompt、GC/DeletionCoordinator/Root/Startup、快照/全备/restore、资源索引、Gradle/installer 或原历史证据。
- 使用为本任务新建的、可证明 ownership 的 tmp root + 6 字节级 fake 媒体和合成 record/manifest；不读取用户根、任何真实 `.cbbackup`/`.cbsave`、受保护文件、Token、SecretStore，不运行 GUI、Android/ADB、真实 Provider/网络或旧 binary。
- 不能要求系统开启 Developer Mode / 管理员 symlink 权限；若 symlink/reparse 夹具不允许，记录真实 `NOT_TESTED`，不得用 JUnit GREEN 伪称链接攻击路径覆盖。

## 2. 要补齐的最小工程矩阵

| 场景 | 必须测到或诚实声明 | 判定 |
|---|---|---|
| **A live-only GC** | 已有 live Entity 正常引用媒体时不误删；引用清除后允许在现合同下删除候选；变更前后完整 bytes/hash/inventory | `LIVE_OWNER_PROTECTED` / `CANDIDATE_DELETED`，仅现有语义 |
| **B 外部-only 引用** | 未登记 sentinel manifest 引用的图片作为 cleanup candidate 时被删除；sentinel blob 本身不作为 live candidate；同一任务记录实际存活/丢失，不仅按日志 | `S2_DOMAIN_UNPROTECTED` 为当前事实，非“新增 bug 修复完成” |
| **C pending-deletions** | 找到真实持久队列/协调所有者后，在隔离 temp root 中复现 queued → processed、重复/中断/取消；如果无法在纯 test-only + fake root 安全触达，就写 `NOT_TESTED / NEEDS_ADAPTER`，不得改主源码 | 量化新域引用是否参与 queue fence；只接受真实源码调用链，不用新模拟版替代正式队列 |
| **D sealed owned copy 候选（仅测试夹具）** | 构造 independent bytes 物理副本位于 synthetic test-only namespace；live image 被合法删除后验证独立副本 hash 不变，证明“copy-on-seal 资源独立”这一有限文件能力，不宣称生产 seal/GC 已实现 | `ISOLATED_COPY_SURVIVES` 是测试能力证据，不是 durable schema/transaction gate PASS |
| **E FullBackup 可枚举性边界** | 复用 G0 已有 unknown top-level explicit reject 与 `images/.hidden` omission；可加一例正常 registered media 参与证明。禁止为过测在隐藏根加入生产白名单 | `REJECTED` / `SKIPPED` / `KNOWN_INCLUDED`，不得把 SKIPPED 标记为全量成功 |
| **F 生命周期/保护范围** | 所有实测调用后新合成源 root/目标/临时目录和 ownership 状态的前后清单、SHA、真实退出码；历史 S0/S1/GC 关键安全断言保持 | 只为本任务 owner 清理临时物，旧库的未知文件不动 |

任何不能由当前公开或项目实际源码证明的行为都写 `NOT_TESTED`；不要新建持久化 seal DTO 当成正式 Entity；不会因独立副本存活证明完整 ResourceGraph/Secret/Backup/Restore。

## 3. 执行、证据与回交

- 仅 Windows/JDK17 headless JVM，新增专用 G0R test class 以及确实涉及的 `DesktopCharacterResourceStoreTest`、现有 GC/delete/backup snapshot suites；记录原始完整命令、Gradle/JUnit、退出码、真测试数及 skipped/平台分支未触达。**不要再次重跑完整 158 项 G0 作为必需条件**，除非实际改动影响其源代码（本任务不允许）。
- 若观察到原本未覆盖的数据丢失风险，用测试结果 `OBSERVED_UNSAFE_CURRENT_BEHAVIOR` 分类，留下独立原始证据；不把它改为长期失败的正式 CI gate，也不假装通过生产修复。每项至少有 source/fixture SHA+长度、命令、XML、单步前后 path inventory、精确源码 blob、source/fixture URL、failure stage 与 `S2_G0R_MEDIA_OWNERSHIP_MATRIX.md`。
- 所有合成 tmp/root 访问应由显式构造参数绑定，不能获取 ambient appDataRoot；只可终止自己创建的测试子进程。不得使用真实用户媒体或同步/发布生产版本；不修改 baseline1.4.1，不合并 `desktop`。

完成后只推送**test-source + 自己的新 raw evidence**到原 feature，提供提交 SHA、完整 diff、明确结论与 remaining gaps，并停在 **P11-S2-G0R Project Evidence Review Gate**。**没有 S2-G1 产品批准或 S2-G2 生产持久化授权；不执行 S3–S6、C1/C2 或 Task 激活。**
