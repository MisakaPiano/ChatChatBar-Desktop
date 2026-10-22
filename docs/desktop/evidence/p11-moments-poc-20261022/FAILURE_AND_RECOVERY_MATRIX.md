# POC-D：失败与恢复矩阵

结论：FEASIBLE_WITH_PROVEN_SCOPE。20 个负例均按预期类别拒绝；另有 1 个正例，真实 unittest XML 共 21 项。7 个中断子进程 exit=71，7 个独立恢复子进程 exit=0。中断项是 subprocess assertions/JSON 证据，不冒充额外 JUnit 测试。

[负例明细](host-run-01/negative-results.json)；[原始测试日志](raw/host-run-01-output.log)；[JUnit](host-run-01/junit.xml)；[中断阶段、命令和退出码](host-run-01/interruptions.json)。全部合成坏档案及未发布 generation 保留在 host-run-01 下。

| 负例 | 明确拒绝类别 |
|---|---|
| 重复 Post / Task | ID_DUPLICATE（两例） |
| JSON 重复 key / ZIP 重复路径 | JSON_DUPLICATE_KEY / ZIP_DUPLICATE |
| 缺图片 / 错 SHA | RESOURCE_MISSING / RESOURCE_HASH |
| 悬空 postId / 关联不匹配 | POSTID_DANGLING / POSTID_MISMATCH |
| 非批准历史孤儿 | ASSOCIATION_ORPHAN |
| 未知顶层 / metadata 嵌套字段 / 未来 enum | ENTITY_FIELDS / NESTED_FIELDS / TASK_ENUM |
| 绝对路径 / content URI / 远程引用 | RESOURCE_EXTERNAL（三例） |
| traversal | PATH_UNSAFE |
| 截断 ZIP / 坏 magic | ZIP_CORRUPT / ENVELOPE |
| 单资源 / 输入包超限 | RESOURCE_LIMIT / ARCHIVE_LIMIT |

每个负例验证输入原字节及旧 current 指针保持不变。中断点：preserve-start、preserve-copy、preserve-seal、preserve-before-publish、restore-copy、restore-verified、export-before-publish。每次先建立旧有效封存与旧输出，在子进程注入硬退出；确认旧封存可验证、旧输出未变、源未变；另起进程从旧有效 generation 重新导出并对照。

这些测试覆盖逻辑发布前进程中断；不覆盖断电持久化、原子发布后崩溃、磁盘损坏/满盘、多个 writer、符号链接/本地攻击者竞争。现有生产恢复事务没有因此自动支持新封存域。

普通诊断只输出测试名、类别、文件位置及计数；合成正文仅存在明确标为合成的档案/逻辑数据材料内。没有真实聊天或秘密。

环境/工具 RED 未覆盖：构建权限失败、缺少传回包、引用遍历次序误报、Android canonical 路径假设错误，原始日志均保留并在来源及回导报告说明。`raw/cleanup-final.json` 是沙箱拒绝进程查询后的无效结果，不可作为清理证明；有效复核是 [cleanup-final-verified.json](raw/cleanup-final-verified.json)，进程列表为空。首次关闭后短暂出现模拟器自己的退出辅助进程，最终已退出；没有强制结束未知程序。

正常 emu kill 与专属 ADB 停止命令成功；模拟器本体退出码因原进程句柄不可用记 UNKNOWN。没有长期 fake 服务；测试内 loopback socket 已随 use 块关闭。保留所有有恢复价值的合成材料，未清理用户文件。
