# F4-B4 手动格式修复交互层 — 候选工程证据

**PROJECT REVIEW PENDING；不是 F4-B4、完整格式修复或 P8-S2A acceptance。**

- 源码候选：`686aea0c35ae09159a44c3b10c623a1abff44668`
- 父提交及预检 local/tracking/live origin：`fe0bba4c0bc80eeb8e61cd7bb4bbf6d19a93b784`
- 分支：`feature/phase8-fish-audio`；本地 desktop 未更新。
- 正式基线仍为 1.4.1；D-036 Prompt、D-039 恢复点保护和已验收 B3B 合同保持不变。
- 源码范围：15 文件，+658/-65（11 个 Desktop 生产文件、3 个测试文件、1 个对应 Skill）。完整逐文件统计和 Windows 文件 SHA-256 见附属 JSON/TXT。

## 实现与所有权

1. 复用现有整条消息菜单，Assistant 才有检查/修复。分段菜单中的该操作仍归属整条消息；只向 B3B 传入 session/message 身份，由 B3B 严格读取完整当前候选。
2. TaskRuntime 保存 sessionId/messageId/taskId 身份；预览与原始消息正文分区显示，不替换复制来源。旧 taskId 的 Stop 无法取消同一消息的新任务。会话切换不取消应用所有的任务，终态刷新调用既有所属会话刷新，不重新选择其他会话。
3. PrimaryChat 的活动任务准入包含格式修复；消息编辑、删除、候选选择取得短期准入 lease，防止修复在读写空隙中进入。lease 不替代存储 mutex、原子写入或关闭生命周期。
4. 修复期间生图控件禁用，原进度/历史卡保留。为此最小调整了 DesktopAssistantImageActions 的 interactionEnabled 参数；没有改变生成请求或业务回调。
5. 只对当前候选适用的 APPLIED/STOPPED 且有 originalContent 的 notice 提供恢复；重启时从 persisted notice 呈现，错误仅使用固定本地化文字。COMMIT_UNCERTAIN 独立提示重新加载核对，不自动重试/恢复。
6. 全局聊天默认设置复用 formatRepairModelId：null 跟随默认对话模型，显式失效 ID 保留；同一辅助模型列表包含 chat-hidden 模型。保存、取消、重新加载及保存期间逐字段重新基线化均沿用原实现，测试补充修复模型字段。

**生产派生安全门禁仍为 UNKNOWN → REJECTED。** 没有注入生产 VERIFIED_SAFE，也没有按关闭设置开关推断历史数据安全。隔离测试明确注入 VERIFIED_SAFE，使用同一个 DesktopAppContainer、ChatRepository 和数据根验证 UI → Controller → Repository。

## 实际验证

Windows、Temurin 17.0.20.1+1、Gradle 9.1.0、现有 2GB heap。所有任务串行；公共参数：

```text
--offline --no-daemon --no-parallel --max-workers=1 --priority=low
--console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process
```

完整参数数组、阶段开始/结束、退出码见 `gradle-executions.json`。没有 clean 或 rerun-tasks。Desktop main/test 实际执行编译成功；SharedCore main/test 为 UP-TO-DATE，不能描述为重新编译。阶段 05、06、07 的 Test 任务实际执行，没有使用 UP-TO-DATE 代替新 JUnit 证据。

| 最终范围 | Suites | Tests | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: | ---: |
| Desktop（阶段 05 + 07） | 17 | 191 | 0 | 0 | 0 |
| SharedCore F4（阶段 06 + 07） | 6 | 71 | 0 | 0 | 0 |

Desktop 新增 Native-backed **离屏** Compose 类为 1 suite / 5 tests，包含真实指针菜单、Stop、恢复、模型选择和中英文终态状态；不是 Windows 原生 launcher 或人工 UI 验收。新 Controller 集成类为 8 tests。旧 B3B 28 tests、TaskRuntime 10 tests 全部运行。

首次选择器中两个不存在的旧猜测限定名（DesktopMarkdownTextTest、data.local.entity.ChatMessageSerializationTest）没有产生 suite，未计入 PASS；阶段 07 按真实名称 DesktopMarkdownParityTest 与 data.repository.ChatMessageSerializationTest 补齐实际执行。

### RED 与中间执行

- 阶段 01：Desktop main/test 编译 PASS，退出码 0。
- 阶段 02：3 suites / 45 tests PASS，退出码 0；后续增加 taskId 防护及 UI 小调整，以阶段 05 最终源码回归为准。
- 阶段 03：**1 suite / 4 tests / 1 failure**，退出码 1，新增模型选择器定位超时。测试预期“不可用”，实际已使用字符串系统中的“已选模型不可用”；修正精确定位，不放宽匹配、不删除断言。原 XML 位于 `initial-red/`。
- 阶段 04：1 suite / 5 tests PASS，退出码 0。随后以阶段 05 的最终源码重新验证。
- 阶段 05：16 suites / 182 tests PASS，退出码 0。
- 阶段 06：5 suites / 68 tests PASS，退出码 0。
- 阶段 07：Desktop 1/9 + SharedCore 1/3 PASS，退出码 0。

历史 B3B RED/资源 STOP、历史 FrameArrived 问题及原始证据均未覆盖或删除。本轮新 RED 也原样保留。全部原始日志留在：

`app/desktopApp/build/f4-b4-validation-20261014-223659/`

## 证据身份与敏感内容检查

- `junit-desktop/`、`junit-shared/`：23 份实际最终 JUnit XML；`initial-red/`：首轮 RED XML。文件 SHA-256、逐 suite 统计见 `junit-summary.json`。
- XML 原样复制；已检查异常栈和 system-out/system-err，无真实凭据、私钥、用户消息或用户数据。仅有合成布局/滚动诊断、localhost MockWebServer 警告与定位超时栈。不上传未经检查的进程命令行或完整诊断日志。
- `source-manifest.json`：候选源码/测试/Skill 的 Windows 工作文件哈希。源码候选提交后的文件与最终验证工作区一致。
- `environment.json`、`resource-summary.json`：脱敏环境与采样统计。账户周额度起始读数 56% used，结束观测 57% used；这是共享账户读数，不能推算单任务精确扣费。

## 未完成与准入边界

- 生产 UNKNOWN 默认拒绝请求及正文/恢复写入；用户入口已呈现原因，**实际生产可用性仍受阻**。
- 后续安全证明必须核对历史消息对应 RAG 索引/source hash、长期记忆 HEAD/Archive/source-turn 引用、Moments 消费与自动生图协调。仅当前设置值不能证明不存在旧派生数据。本轮不清理、不重建这些数据。
- 保留 B3B 的物理阻塞文件 I/O 无绝对超时保证；没有重新验收 Windows shutdown。
- Android production/sharedCore production/Entity/schema/Prompt/Provider transport 未修改；Android 编译和全量回归 NOT RUN。
- Windows 人工 UI、原生 launcher smoke、完整回归、打包和实际付费 Provider：NOT RUN；历史 FrameArrived：BLOCKED/retained。
- 自动格式修复、完整 RAG/Memory/Moments 协调、后续 Fish 功能：NOT IMPLEMENTED。
- `13_FEATURE_PARITY.md`、CURRENT 和 Project acceptance 文档未变更。F4-B4 PROJECT REVIEW PENDING；P8-S2A NOT ACCEPTED。
