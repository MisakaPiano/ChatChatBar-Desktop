# S0 测试与证据

本轮只运行隔离合成JVM测试。没有GUI、Android、真实数据或网络Provider。全部测试命令从仓库 `app/` 执行。

## 真实结果

| 执行 | 退出码 / 结果 | 原始证据 |
|---|---|---|
| 首次普通沙箱Gradle | 1，已有Gradle cache lock写权限不足；不是功能RED | raw/red.log、red-exit.txt |
| 基线RED | 1；1个真实JUnit失败：旧decrypting拒绝官方v2；同次另有既有configuration-cache问题 | raw/red-test.log、red-test-exit.txt、red.xml、baseline-red-test.kt.txt |
| 编译修复过程1 | 1，回调参数类型错误 | raw/attempt-1.log / exit |
| 编译修复过程2 | 1，测试推断返回internal类型 | raw/attempt-2.log / exit |
| 首轮S0 | 0，新增测试通过 | raw/attempt-3.log / exit |
| 受影响回归 | 0，95测试：81项原有回归+当时14项S0 | raw/regression-1.log、regression-1-exit.txt、regression-1-junit/ |
| 扩展S0 | 0，16测试全部通过，包含新增硬退出与预算用例 | raw/s0-final-1.log、s0-final-1-exit.txt、s0-final-1-junit/ |
| 最终S0 | 0，16项全部通过；另补原始凭据字段非法null拒绝、非空值（含空白）分类断言 | raw/s0-final-2.log、s0-final-2-exit.txt、final-s0-junit/ |

最终适用结果：**81项既有回归 + 16项最终S0 = 97项不同测试，零失败。** 95与16是不同运行，不能相加宣称111个不同测试。最后一轮另补原始凭据字段类型校验，不让脱敏将非法null转换成合法空字符串；原恢复code路径在受影响回归时与最终一致。

基线测试源码和XML保留原样；当前同名测试改为断言v1恢复入口继续拒绝v2、独立只读入口能读取v2。不是删除历史失败。

## 命令

基线：

```powershell
.\gradlew.bat :desktopApp:test --tests '*DesktopReadOnlyBackupRedTest' --console=plain
```

后续避免既有configuration-cache错误，显式关闭该缓存，不改构建脚本：

```powershell
.\gradlew.bat :desktopApp:test --tests '*DesktopBackupReadOnlyInspectorTest' --tests '*DesktopReadOnlyBackupRedTest' --no-configuration-cache --console=plain
```

受影响回归：

```powershell
.\gradlew.bat :desktopApp:test --tests '*DesktopBackupReadOnlyInspectorTest' --tests '*DesktopReadOnlyBackupRedTest' --tests '*DesktopFullBackupArchiveTest' --tests '*DesktopFullBackupMappingTest' --tests '*DesktopFullBackupCommitJournalTest' --tests '*DesktopFullBackupServiceTest' --tests '*DesktopFullBackupProcessTest' --tests '*DesktopBackupCompatibilityTest' --tests '*DesktopStartupGateTest' --tests '*DesktopDataOperationCoordinatorTest' --tests '*DesktopDataRootOwnershipTest' --no-configuration-cache --console=plain
```

每次stdout/stderr完整保存在对应log，退出码由PowerShell立即读取LASTEXITCODE。`raw/results.json`按suite汇总原XML；XML为权威。没有运行assemble/redeploy/ADB。

## 覆盖与限制

- 官方测试codec生成v1/code84、85、86及v2/code85、86，分别明文/加密；校验Envelope/Manifest一致、未来版本拒绝。10个基础夹具的实际SHA/长度见 `raw/fixture-sha256-and-process.txt` 和最终JUnit system-out。加密salt/IV随机，重跑hash会变化；构造器及测试源码SHA见 `raw/tested-source-sha256.json`。
- 凭据Preferences三类与Model/Embedding/AppSettings多处分类；结果不带值、正文/Prompt，XML与日志无测试秘密哨兵。没有访问或模拟写入Windows SecretStore。
- sparse/default/legacy选择、明确选空与未选、独立v8包外层库存、Moments图缺省；未知内容显式不声明schema/图完整。v8嵌套包只验证外层bytes/inventory，测试不声称其业务结构可恢复。
- 错密码、尾部认证篡改、截断、重复ZIP精确/大小写名字、CRC中央目录错、路径越界/盘符、inventory hash错、未知metadata/已知实体字段、重复JSON字段、非法scope、外来retainedPreferences均拒绝。
- 每个合成输入/现有live journal文件在检查或失败前后做完整文件清单和内容SHA对照。对真实用户根仅遵守不访问边界，不读取/哈希真实数据来“证明安全”。
- 认证后抛错及取消后源handle可重新读取，无scratch；独立JVM在认证后 `Runtime.halt(73)`，实得退出73且alive=false，源与合成根清单/bytes不变。输入64MiB+1拒绝于INPUT阶段。既有跨进程恢复三例继续通过。

没有Android1.4.6/7实际应用导入、Windows GUI或用户人工PASS；本轮不需要也不宣称完整恢复/平台互通。新增检查接口目前不在GUI展示，不安排重复GUI R1。
