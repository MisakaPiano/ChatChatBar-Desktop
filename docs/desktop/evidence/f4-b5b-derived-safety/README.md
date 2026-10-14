# F4-B5B 第一批生产安全准入 — 候选工程证据

**CODEX CANDIDATE COMPLETE / PROJECT REVIEW PENDING。不是 B5B 或完整 F4 acceptance。**

- 起点 local/tracking/live origin：`67777a2214ffdb53872d3d14a65838ccf7ddca47`，起始工作区/暂存区 clean。
- 源码候选：`46598a9edec0edbef04c88184020bf16572c05aa`；父提交为上述起点。
- 源码提交 9 文件 +824/-6：8 个生产/测试 Kotlin 文件，及必要的一行 format-repair Skill 维护。逐文件统计见 source-diff-stat.tsv。
- 分支仅 `feature/phase8-fish-audio`；不更新本地 desktop/master/sync/upstream。
- 验证在父 HEAD 加精确最终工作区执行，随后冻结为源码候选。source-manifest.json 同时记录 Windows 文件 SHA-256、Git 换行规范化 SHA-256 和实际 Git blob，含全部新增源码/测试。
- 正式 validated baseline 仍为 1.4.1；D-036 Prompt、D-039、Policy、Provider、Repository CAS、Entity/schema 均未修改。

## 生产边界与写入方

1. DesktopFormatRepairDerivedSafety 使用同一 container 的 storage/coordinator/runtime。应用现有 FileLock 的只读有效性检查要求当前 root 的 ownership 仍在持有；没有新锁获取或修改 S0D Main 关闭顺序。DesktopDataRootOwnership 是优先文件列表外的直接依赖，仅增加当前持有者登记/查询，现有 acquire/close 和锁协议不变。
2. TaskRuntime 的当前 session/message 修复身份、聊天/消息修改/图像准入及 source-bearing retry checkpoint 一起检查。已读旧正文但未发布的当前图像任务不允许修复进入；修复运行中相关新图像任务也不能进入；失败后仍保留的 retry checkpoint 继续拒绝，不能靠无活动窗口推导安全。
3. 当前生产 Desktop 只有 AppContainer 创建的 JsonFileStorage。普通持久化通过其 coordinator；backup/snapshot/migration 和聊天图片发布使用现有 exclusive；迁移 restart-required 使后续准入拒绝。没有在本提交增加 RAG/Memory/Moments/Fish producer。未来新增 writer 必须进入同一协调及任务准入合同，不能直接把已有 VERIFIED_SAFE 结果当成长期通行证。
4. check 仅为模型调用前的只读预检。submit 从未持有 normal 的位置进入顶层 exclusive，重新严格扫描、核对完整会话/消息/候选及消息索引，并在同一 scope 内调用原 Repository CAS。模型流不持有 exclusive。normal→exclusive 升级或 closing/restart-required 被拒绝，不借 detached coroutine 发布。
5. 新只读 visitEntitiesStrict 接口逐文件回调，不缓存完整目录。每文件 8 MiB、JSON nesting 64，严格 UTF-8/unknown fields，拒绝旁文件、非普通文件、链接/异常祖先；Desktop 额外检查 Windows reparse attributes。紧凑 session/card/document/post identity 集合各有 10,000 条预算，ID 256 字符、document path 1024 字符预算；目标索引受单文件预算约束并使用 ID 映射，避免逐消息平方查找。
6. Memory 六表、voice/anchor、非空 SaveSlot，以及无法解释的旧记忆镜像均保守拒绝，**包括尚不能证明独立性的其他会话图/镜像**。不 recovery、不迁移、不删除、不重建，也不自动 AI repair。比“仅拒绝目标会话”更窄的开放范围是有意限制，不是已经实现 P9/P10/P11。
7. DOCUMENT 必须绑定现存 CharacterCard/customDocument ID，标准 metadata 名称/身份一致，没有 message/turn 关联；来源文件仅做元数据检查，必须为可验证的本地普通文件，不能指向 live entities/目标消息或经过 reparse/link。不读取文档正文，不证明外部文档内容仍与旧 embedding hash 完全相等。CHARACTER_SETTING、CHAT_MEMORY、来源缺失、相对/UNC/不明文档路径继续拒绝。
8. 完成的 Moments post/task 使用只读字段/类型投影（官方 serializer 尚在 Android，不复制另一套可持久化 Entity）。post 不得有 placeholder/failure/recovery checkpoint，task/post 的 session/card 关联必须相符。PENDING/RUNNING/FAILED、未知字段/关联都拒绝。历史图片及 backup subtree 不改写；测试中的 opaque backup marker 只证明字节保全，不是 archive 格式验证。已存在 SaveSlot 字节保留，但没有共享安全 decoder 的非空 slot 暂不开放。

## 终态与取消

- APPLIED、STOPPED、RESTORED 都重新执行同一最终证明；前期扫描不能被复用。UNCHANGED/notice-only ERROR、D-039 有效旧恢复点语义保持 B3B 原样。
- 提交前拒绝不写消息、索引、preview 或派生数据。真正 Repository 结果 Success / Conflict / StorageFailure / CommitUncertain 不被库存错误抹平；不自动第二次写入、重试或回滚。
- 本轮真实故障测试发现：scope 退出的 cancellation 可以覆盖已经返回的 Repository receipt。最终 adapter 仅保留 **已经返回** 的 receipt；没有 receipt 时继续传播普通 cancellation，放弃未提交 preview。没有增加 NonCancellable 等待或改变 B3B 首终态裁决。
- 实际写消息/索引/preview 后异常，以及真正关闭取消，验证 cause、stage、attempted、durableMessage 原样保留。Repository 原 2500ms 异常读回与 cache invalidation 不变；阻塞文件 I/O 没有物理绝对超时保证，Windows shutdown 集成仍需后续验收。

## 实际命令与编译

Temurin JDK17（完整版本见 jdk-version.txt）、Gradle 9.1.0、既有 2GB heap。所有阶段从 app root 执行，共同参数：

```text
--offline --no-daemon --no-parallel --max-workers=1 --priority=low
--console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process
```

逐阶段完整参数数组、退出码、耗时与本地原始日志 SHA-256 见 gradle-executions.json；未 clean、未 rerun-tasks。

SharedCore main/test 实际编译 PASS（01、08）；Desktop main/test 多轮实际编译 PASS，最终 14 两项实际执行。Android :app:compileDebugKotlin 在 07 实际执行 PASS，08 为 UP-TO-DATE；之后只有 Desktop/测试/Skill 改动，不虚构新 Android 编译或 Android 全量测试。最终 SharedCore 测试为 08；最终 Desktop 为 14。

## 最终原始 JUnit

### Desktop — junit-14-desktop-final

| Suite | Tests | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: |
| DesktopFormatRepairDerivedSafetyTest | 15 | 0 | 0 | 0 |
| DesktopMessageFormatRepairTest | 31 | 0 | 0 | 0 |
| DesktopFormatRepairInteractionTest | 8 | 0 | 0 | 0 |
| DesktopFormatRepairPresentationTest | 6 | 0 | 0 | 0 |
| DesktopTaskRuntimeTest | 10 | 0 | 0 | 0 |
| DesktopDataOperationCoordinatorTest | 14 | 0 | 0 | 0 |
| DesktopDataRootOwnershipTest | 14 | 0 | 0 | 0 |
| DesktopMessageActionsUxTest | 7 | 0 | 0 | 0 |
| **8 suites** | **105** | **0** | **0** | **0** |

### SharedCore — junit-shared-final（08）

| Suite | Tests | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: |
| JsonFileStorageBoundedScanTest | 5 | 0 | 0 | 0 |
| JsonFileStorageStrictScanTest | 3 | 0 | 0 | 0 |
| JsonFileStorageOperationGateTest | 2 | 0 | 0 | 0 |
| JsonFileStorageSafetyTest | 9 | 0 | 0 | 0 |
| ChatRepositoryConditionalUpdateTest | 23 | 0 | 0 | 0 |
| ChatRepositoryFoundationTest | 9 | 0 | 0 | 0 |
| ChatMessageSerializationTest | 3 | 0 | 0 | 0 |
| MessageFormatRepairPolicyTest | 11 | 0 | 0 | 0 |
| **8 suites** | **65** | **0** | **0** | **0** |

原始 XML、每 suite duration/文件 SHA-256 见 junit-summary.json。两模块共 16 suites / 170 tests 为不同限定阶段的最终集合，不能累加历史复测次数来膨胀数量。test 任务实际执行，未以缓存或 UP-TO-DATE 代替新运行。

新增覆盖生产组装/真实 ownership 与 CAS/reopen；完整 candidate 与资源保全；六 Memory 表/voice/anchor/slots 拒绝；损坏、未知字段、孤儿/矛盾身份、未知目录/旁文件；真实 Windows junction 不跟随；同 scope 的 normal writer 互斥；旧图像任务/重试 checkpoint；流期间新增数据；Stop/恢复/unchanged/error/取消；600 个合成 1024 维 embedding 文件的逐条扫描；实际 write/index/preview 故障及取消 receipt 保全。

故障两项使用同 root/coordinator 的专用装饰 storage/repository，以便注入操作点，**不冒充生产单 storage 拓扑**；其余正常生产组装测试使用 container 原有单 storage/repository。故障点在严格确认消息已经写入后触发，使用 CompletableDeferred/awaitCancellation，不固定 sleep。

## RED 历史与资源

- 01：测试编译错误，三处错误使用不存在的 stop 方法；改为现有 requestUserStop。原始日志本地保留，compile-red-summary.txt 有摘录。
- 02：12 tests / 6 failures。03、04 各 1 test / 1 failure 诊断。预检可通过，但 ModelRepository 准备后创建的两个已知模型目录被首版 inventory 拒绝；按实际 authority 分类修正，未知目录仍拒绝。原 XML 在 junit-02/03/04-red。
- 05、06、08、09：各自旧绿色结果保留本地；junit-desktop-final 是 **09 的历史 102-test checkpoint**，不是当前最终统计，未覆盖它。
- 07：共享 7 suites / 61 tests PASS；序列化选择器包名错误导致该类未匹配，不把它计作已执行。08 已改正为 data.repository.ChatMessageSerializationTest，并真实执行，另有新增 nesting test，最终 8/65。
- 10：8 suites / 104 tests / 2 failures。故障 fixture 初次把读索引准备当作写入；未称其为实际写后产品故障。11 修正实际挂起点后 14 tests / 1 failure，确认真实已写入后的 cancellation receipt 丢失；12 修复新 adapter 后 104 tests PASS。原 XML 在 junit-10/11-red；原 cause/stage 测试未删除或弱化。
- 13、14：分别加入来源关联保护和无法解释的其他会话镜像拒绝；最终 14 的 105 tests PASS。此前所有历史 RED/既有 gate 不覆盖、不清理。
- 本轮 15 个离散资源样本：可用物理内存最低 **400.34 MiB**，Commit 余量最低 **8.80 GiB**，Pages/sec 单次最高 **4830**；结束 **2786.99 MiB**、本轮 Java 已退出。真实瞬时内存峰值 UNKNOWN，不能由离散采样保证上限。10 的编译阶段明显变慢，但未达到连续严重保护性 STOP 条件，没有 OOM、强杀或浏览器操作。低优先级 daemon 在样本中为 BelowNormal；单 worker/in-process 参数均实际使用。
- 构建较慢时的线程诊断在执行前发现所核对 PID 已退出，因此 **没有执行 jcmd attach/dump**。未操作历史 B2/WIN 或用户进程。

本地所有完整日志/阶段 XML/patch：`app/desktopApp/build/f4-b5b-validation-20261015/`。GitHub 仅上传经检查的合成 JUnit、精简摘录、参数/哈希和脱敏资源数据，不上传未审完整日志。未发现真实 Key、私钥、Cookie、用户聊天或归档内容；所有夹具均临时合成、模型流或 localhost，不是真实付费 Provider。

**B5B PROJECT REVIEW PENDING。FULL REGRESSION / WINDOWS MANUAL / PACKAGING / REAL PAID API：NOT RUN。** 离屏 Compose 成功不等于真实 Windows 人工验收；历史 FrameArrived 保留。自动 F4、已有派生数据协调、P8 Fish S2A、P9/P10、P11、Public Preview 状态均不提升；不合并 desktop。
