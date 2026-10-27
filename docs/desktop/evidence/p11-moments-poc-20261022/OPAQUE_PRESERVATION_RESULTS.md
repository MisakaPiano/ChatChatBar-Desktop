# POC-A：封存结果

结论：FEASIBLE_WITH_PROVEN_SCOPE。

官方 Android `BackupArchive.create` 真实创建输入；不是手工模拟源 ZIP。夹具包括 5 Post（文字、带图、私密、失败 checkpoint、历史缺失关联）、5 Task（PENDING/RUNNING/COMPLETED/SKIPPED/FAILED 各一）、1 卡/1 sender/1 session/1 settings 和 2 张程序生成 PNG。共 15 个 payload 条目、7915 字节，10 条媒体字段引用。

[官方生成输出](raw/20261022-175527-369-produce/instrumentation-output.log)；[官方原档案及原始逻辑树](raw/20261022-180236-184-pull/artifacts/)；[封存与负例 JUnit](host-run-01/junit.xml)。

封存器先验证 envelope、ZIP、inventory、每项大小/hash、条目闭包、实体 ID、关联和资源字段。完整原包存为 source.cbbackup；原始条目字节存为 generation payload；seal 保存来源、字段引用、长度与 SHA。原始归档、经验证 generation、后续合成恢复目录是三个不同对象。没有注册生产 Entity 或调用任务运行 API。

发布使用新 generation、文件 flush/fsync 和最后原子替换 current.json。失败的未发布 generation 保留为证据。这个实验指针不是 Desktop 生产恢复 journal；没有证明断电、磁盘硬件故障、并发写入或恶意本地进程的安全性。

限定准入：固定官方合成参考的顶层字段集合；已知 Moments metadata/checkpoint 嵌套字段；checkpoint imagePrompt 必须为空、characterPrompts 必须为空。唯一历史缺失关联是预先批准的合成历史记录。不是通用 schema 验证器；不接受任意孤儿或未来字段。依赖实体的任意深层未知字段、其他 checkpoint 形态、多租户冲突、全部官方类型组合尚未证明。

包上限 4 MiB、单条目 1 MiB、展开总量 8 MiB、100 个 ZIP 条目。仅明文 v1、空秘密偏好；加密档案和任何真实凭据迁移不在实验中。不能将本次通过推广为所有 1.4.5 档案或完整全应用备份兼容。

Host 的 21 项 unittest 及 7 次独立中断/恢复验证见 [失败矩阵](FAILURE_AND_RECOVERY_MATRIX.md)。原始记录与全部资源保留，没有通过删除 Task、字段、图片或改变状态使回导成功。
