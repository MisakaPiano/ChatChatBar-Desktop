# F4-B3B R1 Project 限定工程验收

裁定日期：2026-10-14。

状态：**F4-B3B R1 QUALIFIED ENGINEERING ACCEPTED**。这是无新增 UI 的停止、错误与恢复原文运行时的限定工程验收，不是完整格式修复用户功能验收，也不授权后续阶段或合并 desktop。

## 审查对象与责任

Project 已独立审查 GitHub 上两个真实提交及七份原始 JUnit XML：

- 已验收源码：`e1099f91b13068852eb60045c99312929695fc53`。
- 验证证据：`c78bc8c350a0afd66ecae3c56ac20e27a824908e`。
- 源码 parent：`5b7887af45a42c4cf32a6128c23b6b229929c3a6`。
- 工作分支：`feature/phase8-fish-audio`；审查时 `origin/desktop` 为 `5ef95428421f7c693b010ac88f51d470804ab07a`。
- R1 源码范围：六份 Kotlin 文件，**+1025/-4**。验证在 parent 加完整六文件 R1 工作区上执行，源码 SHA-256 与候选提交对应；不是对其他源码版本结果的外推。

源码文件为 Desktop 的 `DesktopMessageFormatRepairController.kt`、`DesktopTaskRuntime.kt`、`DesktopAppContainer.kt`、`DesktopMessageFormatRepairTest.kt`，及 sharedCore 的 `ChatRepository.kt`、`ChatRepositoryConditionalUpdateTest.kt`。

Codex 执行编译与合成/localhost 自动化测试；Project 审查真实源码及执行证据，不声称 Project 自行运行了本地测试，也不代签 Windows 人工验收。本轮结案只有文档变更，不重新编译或测试。

## 已核对验证证据

[证据目录](evidence/f4-b3b-r1-20261014/README.md)包含完整实际 Gradle 参数、退出码、耗时、七份经敏感内容检查的原始 JUnit XML、资源摘要及源码哈希。其候选时的 `PROJECT REVIEW PENDING / B3B NOT ACCEPTED` 是当时历史状态，**保持原样**；本文件记录之后的 Project 裁定，不覆盖旧证据。

八阶段退出码全部为 **0**：

| 阶段 | 结果 |
|---|---|
| SharedCore 主源码编译 | PASS，UP-TO-DATE，未冒充重新执行 |
| Desktop 主源码编译 | PASS，实际执行 |
| SharedCore 测试源码编译 | PASS，实际执行 |
| Desktop 测试源码编译 | PASS，实际执行 |
| Desktop Controller 测试 | PASS，实际执行 |
| Desktop TaskRuntime 测试 | PASS，实际执行 |
| SharedCore 五类聚焦测试 | PASS，实际执行 |
| Android 受影响编译 | PASS，实际执行 |

| JUnit suite | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| DesktopMessageFormatRepairTest | 28 | 0 | 0 | 0 |
| DesktopTaskRuntimeTest | 10 | 0 | 0 | 0 |
| ChatRepositoryConditionalUpdateTest | 23 | 0 | 0 | 0 |
| ChatRepositoryFoundationTest | 9 | 0 | 0 | 0 |
| ChatMessageSerializationTest | 3 | 0 | 0 | 0 |
| MessageFormatRepairPolicyTest | 11 | 0 | 0 | 0 |
| FormatRepairAuxiliaryTransportTest | 20 | 0 | 0 | 0 |
| **合计 7 suites** | **104 PASS** | **0** | **0** | **0** |

验证使用 Temurin JDK17、Gradle 9.1.0、现有 2GB heap、offline、单 Worker、禁止并行、低优先级及 Kotlin in-process。采用隔离临时数据、合成流和 MockWebServer localhost；没有真实付费 Provider 调用。资源记录为低频采样，不代表物理绝对峰值；此前内存 STOP、被中断编译和历史 RED 仍保留，未被改写为 PASS。

## 限定验收范围

- Stop、Error、恢复原文及首终态裁决；用户 Stop 与关闭/普通生命周期取消区分，流式预览不作为普通错误正文持久化。
- D-039 策略 B：失败及失败重试保留仍适用于当前候选的 APPLIED/STOPPED 有效恢复点；无适用旧恢复点时通过 notice-only 条件提交 ERROR notice。
- notice-only CAS 与完整快照、ASSISTANT 角色及候选身份保护；保留基础 content、非目标 alternatives 和其他元数据。Conflict、StorageFailure、CommitUncertain 分别处理，不自动重试或假定回滚。
- 同会话聊天/修复双向准入、重复任务限制、跨会话隔离及 TaskRuntime 生命周期；覆盖写消息、写索引和异常读回期间取消的合成证据。
- 原文恢复与持久化重开检查。共享 Policy、Prompt authority、八消息装配与辅助传输合同保持既有权威。

## 保留限制与未验收范围

1. **生产派生数据安全门禁仍默认 UNKNOWN → REJECTED**。合成测试中显式 VERIFIED_SAFE 的准入不代表生产 RAG、Memory、Moments 或图像派生协调已经完整实现。
2. 可取消的异常读回等待已验证有界；**阻塞文件 I/O 无物理绝对超时保证**，现有安全关闭及数据目录所有权边界继续有效。
3. **Windows GUI 人工验收、全量回归、打包、实际付费 Provider：NOT RUN**。此前真实 Windows/UI 限制不因本次纯工程测试关闭。
4. **F4 UI、自动修复、完整用户可用性：PENDING**；不授权自动进入这些阶段。
5. `13_FEATURE_PARITY.md` 中完整格式修复继续 **PENDING**，不得提升为 EXACT/EQUIVALENT。
6. Fish S2A、S2B–S7、P9/P10、P11、Public Preview Gate 不因本次验收获得新的完成声明。
7. 正式 validated upstream baseline 仍为 **ChatChatBar 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**；D-036 已批准 Prompt 例外保持不变，不进行 general upstream sync。

此限定工程裁定来自 Project 独立审查。后续用户入口、派生协调、整体验收及集成仍需单独授权与证据。
