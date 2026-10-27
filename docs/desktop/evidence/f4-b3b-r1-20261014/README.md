# F4-B3B R1 候选提交验证证据

状态：候选源码，PROJECT REVIEW PENDING；B3B NOT ACCEPTED。

## 源码身份

- 原始 parent：5b7887af45a42c4cf32a6128c23b6b229929c3a6
- 源码候选：e1099f91b13068852eb60045c99312929695fc53
- 分支：feature/phase8-fish-audio
- 验证对象：原始 HEAD 加六文件 R1 工作区，6/6 SHA-256 前后匹配，+1025/-4。
- source-sha256.json 记录验证工作区实际字节哈希及候选 Git blob；Git 属性可能规范化换行。
- 本证据提交未再次编译或测试。

## 执行与结果

Temurin 17.0.20.1+1、Gradle 9.1.0、原有 -Xmx2048m。从 app Gradle root 串行执行。commands.json 保存每次完整参数；stage-results.json 保存开始/结束时间、退出码与耗时。

| 阶段 | 结果 | 秒 | 退出码 |
|---|---|---:|---:|
| 01-shared-main | PASS / UP-TO-DATE | 32.68 | 0 |
| 02-desktop-main | PASS / 实际执行 | 203.16 | 0 |
| 03-shared-test-compile | PASS / 实际执行 | 142.81 | 0 |
| 04-desktop-test-compile | PASS / 实际执行 | 137.24 | 0 |
| 05-desktop-controller-tests | PASS / 实际执行 | 33.99 | 0 |
| 06-desktop-task-runtime-tests | PASS / 实际执行 | 28.36 | 0 |
| 07-shared-focused-tests | PASS / 实际执行 | 33.47 | 0 |
| 08-android-compile | PASS / 实际执行 | 310.6 | 0 |

| JUnit suite | tests | failures | errors | skips | 秒 |
|---|---:|---:|---:|---:|---:|
| com.example.chatbar.desktop.DesktopMessageFormatRepairTest | 28 | 0 | 0 | 0 | 9.861 |
| com.example.chatbar.desktop.DesktopTaskRuntimeTest | 10 | 0 | 0 | 0 | 5.16 |
| com.example.chatbar.data.repository.ChatMessageSerializationTest | 3 | 0 | 0 | 0 | 0.089 |
| com.example.chatbar.data.repository.ChatRepositoryConditionalUpdateTest | 23 | 0 | 0 | 0 | 3.037 |
| com.example.chatbar.data.repository.ChatRepositoryFoundationTest | 9 | 0 | 0 | 0 | 1.406 |
| com.example.chatbar.domain.chat.FormatRepairAuxiliaryTransportTest | 20 | 0 | 0 | 0 | 4.405 |
| com.example.chatbar.domain.chat.MessageFormatRepairPolicyTest | 11 | 0 | 0 | 0 | 0.003 |

合计：7 suites / 104 tests / 104 PASS，0 failures / errors / skips。三个 test task 实际执行；JUnit 累计执行 23.961 秒。Gradle 的资源/插件 SKIPPED 不计作 JUnit skips。

验证记录窗口：2026-10-14 20:02:53–20:24:19 JST，21分26秒。账户周额度已用55%→56%，共享读数不能证明单任务精确消耗。

## 上传检查

七份实际 XML 经结构、字段和输出检查后原样复制；junit-review.json 记录 SHA-256 与 readback。无 properties 内容、无 stdout、无 testcase 异常/正文载荷。唯一 stderr 是两条 localhost MockWebServer 未收到请求警告。机器标签 DD1 与执行时间为普通 JUnit 元数据。未发现凭据、私钥、真实用户数据或聊天正文。未上传完整构建/诊断日志；本地原件保留。

## 资源与限制

resource-summary.json 是低频采样摘要，不是连续峰值。单 Worker、禁止并行、低优先级、Kotlin in-process、无 rerun-tasks/clean。最低可用144.10 MiB，最低 Commit 余量6.24 GiB，最高瞬时换页15178 pages/s；约30秒后恢复至654 MiB/2725 pages/s，未观察到规定的连续 STOP 条件。结束可用约3.20 GiB、Java进程0；未操作 Chrome/NAI。

生产派生数据 gate 默认 UNKNOWN，拒绝未经证明的历史修改；仅合成测试显式允许。可取消异常读回有界，阻塞 I/O 无物理绝对超时保证。Desktop Controller 与 TaskRuntime 拥有停止/恢复生命周期；共享 transport 继续独占八消息装配，未改变 Prompt/Policy/SSE 主路径。

全量回归、Windows 人工/GUI 验收、打包、真实收费 API：NOT RUN。此前内存预检 STOP、被中断编译及历史 RED 未改写为 PASS，也未删除。候选提交不代表 Project acceptance。
