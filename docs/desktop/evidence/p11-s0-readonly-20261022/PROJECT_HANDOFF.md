# P11-S0 Project Code Review 交接

2026-10-22。起点 `4c8cbafe6708ef8e84521daa122f6fb7d9d736e6`，feature/phase8-fish-audio。提交SHA见本文件所在提交与交付消息；本轮仅S0实施。

推送前远端并行新增 `adcdd99cfb6f3583902e5ab013185d1a8393072e`，仅修改13_FEATURE_PARITY/17_ROADMAP/18_DECISIONS的延期设计与Suite参考。已保留并将S0提交重放于其后，没有同步官方上游或移植更新器。本轮精确diff为该父提交到本文件提交；原起点到最终提交还包含这份独立文档提交。重放前后S0源码/test的Git内容相同，不需重复功能测试；测试时工作文件SHA保留原记录（checkout行尾转换可能改变工作文件字节）。

## 改动

- 原AppBackupCodec复用密码和流式认证实现；原decrypting入口仍只允许v1。新独立入口允许1/2并识别Envelope身份；头部增加严格UTF-8/重复key检查，不改变有效v1密码合同。
- 新BackupInspectionModels是固定官方transport DTO/选择选项，不是持久化Entity。
- 新DesktopBackupReadOnlyInspector与memory-only ZIP32目录验证：完整认证、CRC/inventory/hash、身份/版本/scope校验、已知凭据类别报告。结果类型不含Prepared或schedule能力。
- 三份测试源码（含固定官方测试codec）、本证据目录及备份skill更新。原Service、Archive、恢复准入、SecretStore、Prompt、Entity、CURRENT/baseline及历史证据均未修改。

## 真实可用与不可用

v1/code84–86可在新API只读检查；v2/code85/86要求合法scope，可明文/加密检查。v1/code84只提示必须重新进入原完整prepare路径；v1/code85/86、所有v2、外来非空凭据及未理解类型均不获恢复许可。现有service照旧拒绝v2。

**FORMAT_READABLE不代表RESTORE_SUPPORTED，更不代表FULL_PORTABILITY_ACCEPTED。** 所有新结果的restoreSupported和fullPortabilityAccepted均false。Moments/未知实体只有未解释计数和潜在秘密提示；不创建封存、不激活Task。

实际限制：源文件/解密ZIP各64MiB，累计展开128MiB，ZIP32；超限/ZIP64拒绝。资源图、依赖闭包、嵌套SaveSlot内容不在S0验证范围，相应标志false。没有GUI接线。详见 [可读范围与安全](READABILITY_AND_SAFETY.md)。

## 证据

已保存真实基线RED、编译修复记录及最终JUnit。81项原有备份/恢复/根锁/启动回归与16项最终S0全部通过；隔离子JVM硬退出实得73，无新数据文件残留。完整命令、退出码与限制见 [测试证据](TEST_EVIDENCE.md)。固定来源、测试codec等价复核及追加勘误见 [来源审计](SOURCE_AUDIT_AND_ERRATA.md)。

官方backupJson明确ignoreUnknownKeys=false；旧草案相关推论在此追加纠正，历史原件不改。独立BackupEntityValidator.entityJson=true与该事实不矛盾。

四份既有用户文件保持KEEP / UNREAD / UNHASHED / UNMODIFIED / UNSTAGED；没有真实档案/根/凭据读取，没有GUI/Android/安装/真实Provider。未跟踪旧__pycache__不纳入交付。

## 审查重点与停止点

请独立审查只读结果与Prepared类型隔离、所有不支持项是否充分标示、内存预算/ZIP32边界、已知与未知schema区别，以及有效v1回归证据。不要将record计数或外层哈希误签为资源依赖闭包。

停止于 **S0 Project Code Review Gate**。S1–S6未授权，不修改D-038目标/正式baseline，不提升P11、不解除Post-P8 HOLD、不合并desktop、不启动Public Preview Gate。
