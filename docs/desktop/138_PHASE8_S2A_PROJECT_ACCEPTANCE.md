# Phase 8 S2A — Desktop Fish Settings and Voice Binding Project Acceptance

**P8-S2A QUALIFIED ENGINEERING ACCEPTED / CLOSED WITH DECLARED LIMITS — 2026-10-18, Project decision.**

Project 已独立复审 R1A production/test、独立工程/人工证据及 WIN-AUTO-001 调查。本文记录已作出的限定工程裁定，不重新作出验收，不将未执行项提升为 PASS。接受范围仅为 [114 原实施合同](114_PHASE8_S2A_FISH_SETTINGS_BINDING_CODEX_CONTRACT.md) 下的 Desktop Fish 凭据、全局/会话设置、音色目录与显式角色绑定；完整 Fish 用户功能和完整 P8 仍 PENDING。

## Exact checkpoints and responsibilities

| Checkpoint | Exact commit | Direct parent | Independent responsibility |
| --- | --- | --- | --- |
| R1A production/test | `fa5d3f18eebbd6c34f607d7b52d47e24825cb411` | `c4dece60cddc1aedf673cb532b5976554690b4ea` | 3 文件 +129/-2：限定设置模块标题修复及滚动测试驱动 |
| R1A evidence | `2092806f8e4a203a1d2b0ddf7aa14549e3179dd0` | `fa5d3f18eebbd6c34f607d7b52d47e24825cb411` | 329 文件；manifest 覆盖其余 328 文件；工程验证与用户签署分开 |
| WIN-AUTO-001 investigation | `ad37c170a0bbcd421aba908be4ad01029c00ebee` | `2092806f8e4a203a1d2b0ddf7aa14549e3179dd0` | 独立调查 10 文件；manifest 覆盖其余 9 文件，不是 S2A 生产修改 |

三提交为真实单父线性关系。R1A 没有重新实现 S2A：`DesktopManagePanel.kt` 复用既有 `ManageHeading`，`DesktopFishAudioPanel.kt` 将「语音与朗读」与外观标题同级、Fish Audio 为从属说明；两份 UI 文件保留原 R1 草稿方向。`DesktopFishPresentationTest.kt` 只针对新增窄窗测试修正帧推进/滚轮驱动，验证位置变化、可见范围和实际保存/放弃操作，原五个 presentation 测试不变。原 R1 +84/-2 补丁、原始 RED 与新 +129/-2 checkpoint 分开保存，不修改 Controller、Repository、SecretStore、请求或持久化语义。

## Reviewed engineering and runtime evidence

原始材料：[R1A README](evidence/p8-s2a-t2-ui-001-r1a-20261018/README.md)、[各阶段真实命令/退出码/测试统计](evidence/p8-s2a-t2-ui-001-r1a-20261018/stage-results.json)、[源码与 Git blob 身份](evidence/p8-s2a-t2-ui-001-r1a-20261018/source-commit-manifest.json)、[完整源码补丁](evidence/p8-s2a-t2-ui-001-r1a-20261018/fixed-source.patch)。Project 的独立复审与 Codex 本地执行职责分开；本结案不重跑或重新生成这些证据。

| Separate actual run | Suites / tests | Result |
| --- | --- | --- |
| 修正后新增滚动测试 | 1 / 1 | PASS，exit0 |
| Fish/Settings focused | 5 / 54 | 54 PASS，0 failures/errors/skips，exit0 |
| Desktop bounded non-Native | 153 / 1493 | 1491 reported PASS、2 SKIPPED、0 failures/errors，exit0；原四项排除不变 |
| 新精确 Windows distribution | 非 JUnit | createDistributable exit0；212 文件身份验证 |

上述不同执行不累加冒充单次测试。首次选择器未正确引用空格导致 Gradle 将 `voice` 识别为 task，exit1、没有执行测试；误复制的旧 XML 明确不计入本次执行。随后一次限定诊断复现旧滚动驱动 5000ms timeout RED，修正后执行与历史 R1 的 5 suites/54 tests、53 PASS/1 FAIL 分开保留。原 R5 RED、T1 修复与旧 T2 问题证据不倒改。两项 symlink JUnit SKIPPED 和历史 NOT TESTED/平台限制不转为成功覆盖。

Desktop 编译与 UP-TO-DATE 按阶段原日志区分；不虚构每次均重新编译。SharedCore/Android 沿用既有未变源码证据，不冒充 R1A 重新执行。资源记录是采样值，不是绝对峰值或前台响应验收。

| Evidence identity | SHA-256 |
| --- | --- |
| [R1A evidence manifest](evidence/p8-s2a-t2-ui-001-r1a-20261018/evidence-manifest.json) | `543a12f39fec66053015cdbd69d0b5a9d5886982f49e18c40c6f41cff1fb3bcb` |
| [212-file distribution manifest](evidence/p8-s2a-t2-ui-001-r1a-20261018/distribution-manifest.json) | `4e2203ef8c5dae078082e1e6ddaa4e90596190ffe5361333cecd7d2d81fadf07` |
| Launcher | `e5d45e5389e7584b9d8477efe34543de1069e6550560a3a3c53d164152958b8f` |
| Business JAR | `a3c1a27586366c97d6d718fcf06726c6a9784cf947a3b0c0b9c2d9c36b206847` |

新精确运行包与隔离副本为 212 文件；不是 T1 旧包。两次合成根原生运行分别确认 Launcher/JVM 实际退出 **0/0**；运行中独立 ownership 尝试 exit75（预期拒绝第二进程），退出后独立取得/释放 exit0。fake 服务正常排水并退出0，临时 TLS P12/public truststore 清理；合成数据、导出产物保留。证据见 [最终进程/锁/fake 汇总](evidence/p8-s2a-t2-ui-001-r1a-20261018/manual/final-gate-summary.json)。窗口消失、锁重获和真实进程退出不是互相替代的证明；原 closeObservedUtc 缺失及 PID 复用的观察限制不补造。**R1A live Fish GET0 / TTS0**，不将此前有限 live gate 算作本轮调用或新功能验收。

## User Windows acceptance and declared manual gaps

[新 R1A 人工表](evidence/p8-s2a-t2-ui-001-r1a-20261018/MANUAL_RESULTS_CN.md) 的完整复合项为 **15 PASS / 10 NOT TESTED**；[48 条原始用户文字记录](evidence/p8-s2a-t2-ui-001-r1a-20261018/manual/user-signatures.jsonl) 是用户操作签署及澄清，不是新截图或自动 OCR。标题同级/独立模块/窄窗滚动、两种卡片模式、显式选择与移除、保存/重开、JSON/CCB PNG/复制/新导入，以及普通设置操作的实际子项按原表保留。

[旧 T2](evidence/p8-s2a-r5-t2-manual-20261018/MANUAL_RESULTS_CN.md) **4 PASS / 1 FAIL / 20 NOT TESTED** 原样不变；旧 W02 FAIL 和用户截图保留。新 W02-R1 PASS 不倒改旧记录。两轮不合并成一轮全绿人工验收。

| New composite NOT TESTED item | 已通过或已保留的普通子项 | 自动化技术证据 / 仍未执行的人工分支 |
| --- | --- | --- |
| W04 凭据 | 合成密钥保存/替换/保留/清除/重存、重启配置状态 PASS | 失败保全引用既有凭据/绑定技术测试；人工故障注入未执行 |
| W05 模型选择 | 本轮「当前会话对话模型」保存/重启 PASS；旧 T2 四种 TTS/辅助模型证据保留 | 模型字段/null/stale 技术断言不等于重做全部人工组合；未重复部分仍未测 |
| W06 失效模型/放弃 | 旧 T2 对应子项保留；本轮自动化仍断言不可用模型保护 | 未完整重做人工分支；不因技术 PASS 改成人工 PASS |
| W07 全局合并 | 普通听书保存、离开重进及完整重启 PASS | 并发合并有自动化技术证据，人工保存竞态未执行 |
| W09 会话三态/语言 | 初始继承、逐字输入含空格语言、开启保存 PASS；关闭 draft 已观察 | 未逐一保存全部三态；字段/dirty 技术覆盖与人工执行分开 |
| W11 保存中编辑 | 已有确定性会话保存/dirty 技术证据 | 未人工制造保存挂起竞态，不凭快速点击推断通过 |
| W15 筛选/分页 | 个人/社区两页、标题/标签/语言空结果、排序及恢复普通路径 PASS | 迟到查询/旧结果保护由自动化技术证据支持，人工竞态未制造 |
| W16 错误分支 | 本机故意401、脱敏提示、旧音色不可选及恢复 PASS | 自动化目录异常覆盖另计；403/429/坏或超大响应人工未执行 |
| W17 取消/迟到结果 | 普通取消后无绑定变化 PASS | 人物/卡片切换及迟到结果有技术证据；人工受控迟到场景未执行 |
| W22 原生挂起查询关闭 | 普通窗口关闭与两轮真实退出分别 PASS | **独立未验事项：未在真实原生窗口的 fake 查询挂起时关闭音色库/主窗口**；自动化取消/排水不能替代本人工分支 |

固定 fake 每页一音色只是夹具，不代表实际目录每页一条。首次导出选错结构化卡、第二次另存、复制名称为 `(2)` 的用户澄清及观察脚本修正均保留。既有 transfer 语义下原卡编辑保留身份，新导入/复制获得新 card/person 身份并保留完整 binding snapshot；不据此提出新 schema。

## WIN-AUTO-001 remains open

[独立调查目录](evidence/desktop-ui-automation-reliability-20261018/README.md)：**OPEN / CAPTURE INFRASTRUCTURE BLOCKED，底层原因 UNKNOWN**。已有无 CCB 业务的 WinForms 最小复现；历史 Robot/Win32/PrintWindow 与 Computer Use 捕获是不同路径，UIA 读取/设置、截图和鼠标几何能力分别记录。人工 PASS 不关闭此项，也不将截图失败归为已证明的 CCB/GPU/驱动根因。本结案不修改自动化工具、系统配置或驱动。

## Formal boundaries and handoff

- 仅接受 114 下的 S2A 工程切片。完整 Fish parity、试听/生成/播放、QQ、SaveSlot audio 及完整 P8 仍 PENDING；`13_FEATURE_PARITY.md` 不提升。
- 正式 validated upstream baseline 仍为 ChatBar **1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**。Prompt、Package、Entity/schema、Provider 请求语义不变，不同步上游。
- S2B/S3 必须另行合同。下一项拟议为 P8-S2B 原生音频试听/播放的精确上游源码审计与合同冻结，须 Project 另行下发，不是当前实现授权。
- G2-B/C、生产 Writer/Recovery、G0→G2 production dispatch 继续未经授权；不合并 desktop、不发布。

**Tests rerun: NO — reused Project-reviewed engineering, runtime and user evidence.** 本轮仅四份 Markdown 的提交来源、链接、范围和 diff-check 静态检查；不运行 Gradle/JUnit/GUI/Windows 打包，不读取真实用户数据/凭据，不调用 Fish API。历史证据、原始 README 和 manifest 均不改写。

**P8-S2A ACCEPTANCE DOCUMENTED / PROJECT DOC DIFF REVIEW PENDING**
