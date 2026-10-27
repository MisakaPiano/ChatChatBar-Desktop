# F4-B4 R1 菜单安全准入 — review checkpoint

**PROJECT REVIEW PENDING；候选提交不等于 acceptance。**

- 起点 local/tracking/live origin：`955d3bf05d039bc022e33e4b54db06cfc7c9732e`，工作区及暂存区 clean。
- 生产修复候选：`e9901a0ce7a3f764d74d3d83f0518a18e9bb8640`；7 文件，+78/-21（6 个 Desktop 生产文件及对应 Skill）。
- 测试变更：DesktopMessageFormatRepairTest +65/-2；DesktopFormatRepairPresentationTest +37/-0，随本证据单独提交。
- 验证运行于起点加完整生产/测试工作区；source-manifest.json 记录全部九个文件的 SHA-256。未将旧 23 suites / 262 tests 冒充本轮执行结果。

## 统一准入与重新核对

- `desktopRepairStateFor` 仍定位 session/message 的当前运行状态；`desktopRepairCommitBlocked` 同时用于状态栏、消息菜单及 Primary Controller 的修复/恢复入口。
- 未确认时状态栏保留警告，菜单没有 REPAIR_FORMAT/RESTORE_FORMAT，迟到的旧回调在 Controller 再次被拒绝；B3B Controller 的任务准入也拒绝未经确认的直接调用。不会发起第二次模型请求或恢复写入。
- 新的显式“重新加载并核对消息”调用 confirmCommitReload：严格持久化读取必须匹配界面所见完整 ChatMessage（正文、候选 index/IDs、notice 及其他元数据），且没有同会话活动任务。读取失败、旧快照、取消或过期核对结果不会解除保护。若界面已落后，先刷新显示，用户需再次核对实际快照。
- 核对只更新同一个内存状态的 confirmedMessage，不写 Entity/notice，不修改原 CommitUncertain 的 cause、stage、attempted 或 durable evidence。核对后仍提示“此前提交过程未完全确认”，没有宣称索引/预览事务全部成功或回滚。实际恢复资格继续由当前候选有效 APPLIED/STOPPED notice 决定，CAS 与派生安全门禁仍在原位置校验。
- 普通自动终态刷新、异常路径已有 durable evidence 都不算核对。未核对的不确定状态不会因普通历史裁剪而消失。
- 生产 UNKNOWN → REJECTED、B3B 首终态裁决、D-039、Provider transport、Prompt、sharedCore/Entity/schema 均未修改。

## 本轮实际验证

Temurin JDK17、Gradle 9.1.0、2GB heap；公共参数及每阶段退出码/时长完整见 gradle-executions.json：

```text
--offline --no-daemon --no-parallel --max-workers=1 --priority=low
--console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process
```

任务：Desktop compileKotlin、compileTestKotlin、test，仅以下四个过滤类。

| 最终 suite | Tests | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: |
| DesktopMessageFormatRepairTest | 31 | 0 | 0 | 0 |
| DesktopFormatRepairInteractionTest | 8 | 0 | 0 | 0 |
| DesktopFormatRepairPresentationTest | 6 | 0 | 0 | 0 |
| DesktopMessageActionsUxTest | 7 | 0 | 0 | 0 |
| **合计 4 suites** | **52** | **0** | **0** | **0** |

Desktop main/test 实际编译 PASS（测试编译在阶段 02 执行成功，阶段 03 UP-TO-DATE）；最终 test 任务实际执行。SharedCore 编译依赖 UP-TO-DATE；没有重新运行 SharedCore/Android 或全量回归。

新增覆盖真实 APPLIED → 第二次 COMMIT_UNCERTAIN、状态栏及真实指针菜单隐藏、过期 Controller 点击拒绝、普通刷新不确认、错误候选身份不确认、严格读回失败、41 次普通历史裁剪及显式核对后实际恢复。使用专用临时故障注入 storage；不冒充生产同根拓扑。原有同根集成、正常 APPLIED/STOPPED、无有效 notice、Stop、CAS/D-039 测试继续执行，不删弱断言。

## RED 与资源记录

- 阶段 01：编译 FAIL，smart-cast 错误；测试 NOT RUN，无本轮 JUnit。原始日志在本地保留且未改写；GitHub compile-red-summary.txt 提供错误摘录与原件 SHA-256；只修正空值访问。
- 阶段 02：编译 PASS，4 suites / 52 tests / 1 failure，存储门禁在严格读取前抛出 IOException。原 XML 保留在 junit-red。修正新核对函数的异常出口：普通读取异常返回未确认，CancellationException 继续传播，不改变 B3B 终态。
- 阶段 03：最终编译及 52 tests PASS，原 XML 位于 junit-final。逐文件哈希见 junit-summary.json；原阶段 02/03 XML 均未改写。
- 内存初始约 706 MiB，过程单次最低约 68 MiB；后续立即恢复到 169/499 MiB。也出现一次约 21,248 pages/s 样本，随后恢复，未观测到达到连续保护性 STOP 条件。结束约 2873 MiB，无剩余本轮 Java。完整脱敏采样见 resource-samples.jsonl，实际峰值 UNKNOWN（离散采样）。单 Worker、低优先级、in-process；无 OOM、未强杀任何进程。
- 所有原始日志保留本地 app/desktopApp/build/f4-b4-r1-validation-20261015。XML、首轮编译日志已检查，无真实凭据、私钥、用户资料或未脱敏请求；仅有合成故障栈与布局诊断。
- 当前共享账户周额度读数为 57% used，不能推算本任务精确消耗。

Windows 人工 UI、完整回归、打包、真实付费 API、自动格式修复：NOT RUN。Native-backed 离屏 Compose 6/6 不等于 Windows 人工验收；历史 FrameArrived 与全部旧 RED 保留。F4-B4 REVIEW PENDING / P8-S2A NOT ACCEPTED。
