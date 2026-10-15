# P9-S2B-G0 — CODEX CANDIDATE / PROJECT REVIEW PENDING

2026-10-16，Windows synthetic-only validation。不是生产 Startup Gate 验收，不是事务恢复、P9 或 Public Preview 完成声明。

## Identity and checkpoints

- 起点：`8820462db2a968b10ef59488985edef21d025973`，local/tracking/live origin 一致、index/worktree clean；其他已观察任务 idle，无已观察并发仓库写入。
- A 合同：`0147960fb408ebec9c23174688421a5a4232f260`，父提交为起点。
- B 源码候选：`1123c13f9f0826282b7b75537e82eda9732ee73c`，父提交为 A；3 files +500/-1。
- C 为本目录独立 docs-only 提交，父提交为 B；完整 SHA 由包含本文件的 Git commit 和最终交付提供，避免自引用伪哈希。
- B 推送后源码工作区与暂存区 clean；C 推送后的最终状态另由交付时 Git/live origin 检查确认。
- 本地 desktop 保持 `4c2af2576e2319e8041ffd2a6c5a205450f18246`；origin/desktop 保持 `5ef95428421f7c693b010ac88f51d470804ab07a`；master/sync 见 `protected-refs.json`。无分支切换、合并、upstream fetch/sync、强推或历史改写。
- 正式 baseline 1.4.1、D-036/D-039/D-040 及现有验收状态均不改变。

源码是 `DesktopIndexStartupPreflight.kt`（234 lines）、`DesktopIndexStartupPreflightTest.kt`（265 lines）；Skill 仅补一个定位句。`source-manifest.json` 保存原始工作树 SHA-256/长度、Git blob 和未过滤工作树 Git object。LF/CRLF 不假定相同：测试实际源码字节以该 manifest 为准，提交后未改 Kotlin。

## Scope and platform identity

仅依赖 root Path、已有 `DesktopDataRootOwnership.isHeld`、NIO 元数据/目录、现有 JNA Windows API 和协程取消。**无生产调用入口**；未改 Main、Container、TaskRuntime、coordinator、Storage、Reader、Preparation、Android、Entity、Prompt 或生产安全门禁。

候选位置仅是内部常量 `entities/document_index_transactions/`，尚非正式 schema。NO_DOMAIN 仅证明此次观察中候选域缺失；不认证旧向量、replace/backup 残留或整个 root。存在域始终阻断，空目录、completed/commit.marker 均不解析、不视为 READY。

实际 JDK17 Windows BasicFileAttributes.fileKey() 返回 null（`metadata-diagnostic.log`），因此使用现有 JNA `GetFileInformationByHandleEx(FileIdInfo)` 获取卷序列号和 128-bit ID；同时读 FileBasicInfo 检查类型/reparse。CreateFile 仅 FILE_READ_ATTRIBUTES + OPEN_EXISTING + OPEN_REPARSE_POINT/BACKUP_SEMANTICS，共享 read/write/delete，在 finally CloseHandle；不读正文、不创建文件或第二把 ownership 锁。身份不可得仍 fail-closed。原始 NIO NOFOLLOW/type/time/size 及 Windows reparse 检查保留。

预算为累计256项、候选深度4、名称255字符/累计8192、累计路径32768、祖先64级、普通文件 metadata size 总和64MiB；checked arithmetic，逐层 DirectoryStream.use。并非官方 journal 大小限制。只读属性调用也可能受 OS I/O 延迟影响，不声称物理绝对超时。

## Actual commands and executions

既有 Temurin JDK17，Gradle9.1.0，tracked `org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8` 未改。每次从 app root 串行调用 `gradlew.bat`；`run-stage.ps1` 保留实际参数数组和原始输出。

公共参数（四次均相同）：

```powershell
--offline --no-daemon --no-parallel --max-workers=1 --priority=low --console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process
```

01/02/03 均为 `:desktopApp:test --tests com.example.chatbar.desktop.DesktopIndexStartupPreflightTest`。04 为同一 test task 加以下6个精确 `--tests` class；完整参数、时间、退出码见每阶段 JSON。

| Stage | Exit | Wall seconds | Actual outcome |
| --- | ---: | ---: | --- |
| 01-g0 | 1 | 170.240 | main compile执行PASS；test compile RED：模拟DirectoryStream需MutableIterator，无JUnit执行 |
| 02-g0-iterator-fix | 1 | 154.512 | test compile执行PASS；19 tests / 15 failures / 0 errors / 0 skips；原始RED保留 |
| 03-g0-windows-identity | 0 | 83.490 | main/test compile均实际执行PASS；1 suite / 19 tests / 0 failures/errors / 1 skip（18 passed） |
| 04-path-ownership-lifecycle | 0 | 29.150 | test task实际执行，main/test compile UP-TO-DATE；6 suites / 86 tests / 0 failures/errors / 1 skip |

不使用 clean/rerun。SharedCore main/test fixtures 为 UP-TO-DATE，不称新编译PASS；Android、全量P9/P11/F4、打包、正式GUI/EXE、网络API未运行。

| Final suite | Tests | Failures | Errors | Skipped | Seconds |
| --- | ---: | ---: | ---: | ---: | ---: |
| DesktopIndexStartupPreflightTest | 19 | 0 | 0 | 1 | 1.919 |
| DesktopDataRootOwnershipTest | 14 | 0 | 0 | 0 | .152 |
| DesktopDataDirectoryTest | 9 | 0 | 0 | 0 | .134 |
| DesktopPortableRootTest | 12 | 0 | 0 | 0 | .162 |
| DesktopApplicationLifecycleTest | 25 | 0 | 0 | 0 | 2.741 |
| DesktopProcessLifecycleTest | 7 | 0 | 0 | 0 | .211 |

最终JUnit名义85 passed/1 skipped；**不能把平台分支当作全部验证**：G0实际symlink创建抛FileSystemException，未取得更细Win32原因，NOT TESTED/1 JUnit skip；既有ownership两个用例及Portable两段夹具各输出SYMLINK_FIXTURE_UNAVAILABLE（4次），其早返并未计入JUnit skip。保留原始system-out，不改旧测试。真实junction在entities/domain均执行，真实硬链接内部重复ID/外部链接场景均执行。

## RED and bounded fixes

1. 01：模拟枚举 `listOf(...).iterator()` → `mutableListOf(...).iterator()`，匹配Java DirectoryStream接口；未修改断言。
2. 02：fileKey在此Windows JDK为null，导致正常场景提前UNSAFE_PATH；取消测试因此未抵达挂起点并触发30s守卫。添加上述只读原生ID证据，未把null当作有效身份。
3. 02：持有ownership的合成root重命名出现AccessDeniedException。未释放/绕过锁来造绿；root类型/身份漂移改用明确元数据seam，真实root替换 NOT TESTED。实体/候选域的真实junction继续验证。
4. 原始 RED XML `red-02-junit/`、初始编译日志及其阶段 JSON 均保全；03单类绿色XML和04最终6份XML各自独立，未改写RED为PASS。此前所有P9/P11/F4历史证据未动。

## Coverage and zero writes

| Required category | Evidence in G0 tests |
| --- | --- |
| 旧root/多种legacy布局 | empty、document、损坏vector/backup合成布局，无目标域均NO_DOMAIN，不读取/认证这些内容 |
| 无entities/重复检查 | 不创建entities；同实例重复检查，之后新增域立即阻断 |
| 空域/未知协议 | empty及active/pending/completed/heads/history + invalid intent/marker均阻断 |
| ownership缺失/释放 | 未取得、正常持有、释放后、复核时释放分别验证，不获取第二把锁 |
| 错误类型 | entities/domain真实普通文件；root类型变化为显式元数据seam |
| link/reparse | entities/domain真实junction；内部/外部真实硬链接；symlink未测试，见限制 |
| 名称/case | Windows非法/保留/8.3名称策略；case碰撞为明确枚举seam，非真实NTFS大小写敏感目录 |
| 有界预算 | entries/depth/name/path/size拒绝；Long.MAX_VALUE伪metadata不分配大文件 |
| 权限/错误 | 属性及枚举AccessDenied seam；固定结果不泄露异常文本，不等同真实ACL修改验证 |
| 删除/出现/身份漂移 | 目标真实出现/删除；root身份复核seam，无旧结果缓存 |
| 取消/句柄 | CompletableDeferred在已开DirectoryStream内挂起；取消传播、stream归零、ownership保持、可重新检查 |
| 假completed成功 | 从不返回READY/COMMITTED，只返回unsupported或更严格拒绝 |

每个夹具先取得现有ownership，再记录合成树；ownership不存在的专门场景单独记录。快照比较文件列表、目录creation/mtime和所有合成普通文件SHA-256，锁只记OWNERSHIP metadata；不读锁正文、不把锁创建算成G0写入。外部目标同属专用合成夹具，链接不递归跟随。

最终阶段40个观测行：38次正常/拒绝/故障/取消/竞态零写入对照全部一致，另2行明确平台限制。见 `zero-write-final-summary.json`；原始append-only事件及前阶段副本均保留。出现/删除由测试actor执行，比较actor动作后的基线；类型/身份seam不实际改磁盘。清理仅测试自身临时根，先校验绝对父路径、NOFOLLOW/reparse再逐项删除，不触及历史产物或真实用户数据。

## Resources and remaining limits

开始可用物理内存约476MiB、Commit余量7.18GiB、Java0；12次周期采样最小可用246.18MiB、最小Commit余量5.68GiB、最大单次Pages/sec13445。后续样本回落，没有连续观察到严重临界组合或OOM；并非连续监控、不能证明峰值或前台响应。最大采样3个Java（wrapper+single-use低优先级Gradle+test JVM），聚合WS1064.27MiB、Private1649.12MiB；真实绝对峰值UNKNOWN。结束Java0、可用1557.82MiB、Commit余量7.32GiB、H余量37.95GiB。未操作Chrome/NAI/用户进程。

不提供外部硬链接不存在证明（结果始终false）；存在域仍阻断。观察与复核不替代Windows完整handle-relative TOCTOU方案，不能防止检查返回后的变化；root真实替换、symlink和细粒度ACL未取得实际OS验证。G0不具备长期readiness、不接Main、不读取事务payload、不恢复/发布索引、不改变Storage/Reader/B5B。G1、正式protocol/head、多writer交接及真实启动仍需Project独立授权。

`raw-copy-verification.json`逐字节复制前后SHA-256一致；目录`.gitattributes`保留原始XML/log/jsonl字节，不做换行修正。`evidence-manifest.json`覆盖除自身以外全部证据；敏感检查见 `sensitivity-review.json`，只上传本轮合成测试输出及脱敏资源，不含用户资料/凭据。

首次证据staged diff-check仅报告原始run-stage.ps1末尾空行（见static-check-red.txt）；原件不改写，仅对此原始证据脚本标注blank-at-eof例外后重新检查。该例外不适用于生产/测试Kotlin；源码检查点已独立diff-check PASS。

**P9-S2B-G0 — CODEX CANDIDATE / PROJECT REVIEW PENDING。**
