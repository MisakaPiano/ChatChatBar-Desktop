# P8 S3–S7 engineering candidate / Project review pending

日期：2026-10-19（JST）。授权来源为本轮「P8 Remaining Implementation Mega Task」，连续实施 S3–S6 与 S7 自动验证、打包及人工准备。本报告不是 Project 验收、full bounded GREEN、Fish parity、Public Preview 或 release acceptance。

**状态：P8 S7 WINDOWS PACKAGE READY / USER ACTION REQUIRED。** 本轮没有启动最终实包 GUI 或人工 fake 服务，没有提前签署听觉、实包退出或根锁排他 PASS。源码最终冻结于 `008343bb26d2eb76c23cae9b98132af828a9f447`。

## 1. 工程与证据身份

唯一分支 `feature/phase8-fish-audio`；起点 `6968a4975fcd3da0c0a6618a600b013cc94c923c`，其 parent 为 `dcfd61d52f0ef5851d2396ac8ef01fc11f1ffa39`。正式 upstream 仍为 ChatBar 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`；只读观察到 master `979cb505958dffb1b6d64a8de657434f198ffa96`，未同步或提升 baseline。`origin/desktop` 保持 `5ef95428421f7c693b010ac88f51d470804ab07a`；本地旧 desktop 不切换、不更新。

| 切片 | 源码完整 SHA | 直接 parent | 差分 | 独立证据 SHA |
|---|---|---|---|---|
| S3 | `8775d1ddc0174e40738ba24a74cacaad659d001d` | `6968a4975fcd3da0c0a6618a600b013cc94c923c` | 7 文件 +348/-6 | `75ae07afa235cc0b186cbbf805a0d7667bbdb3a8` |
| S3 skill | `87e50f255f8cae2c6860327e190dd802aca43bc6` | `8775d1ddc0174e40738ba24a74cacaad659d001d` | 1 文件 +6/-1 | 原始日志另补存 `5b7e633526fc5ebd5dfa290fea09fae91d90f124` |
| S4 | `db7c4e079149b365d548446ab9f8eebfef14e13c` | `5b7e633526fc5ebd5dfa290fea09fae91d90f124` | 17 文件 +1851/-213 | `623a9b6227472c9c7a34d8f88dbc2fb544c9648d` |
| S5 | `4e659a40faa8a797b426ba65248c5d9cc31ecaed` | `623a9b6227472c9c7a34d8f88dbc2fb544c9648d` | 10 文件 +550/-16 | `af524d64bca1a0378e5b82ab92e70c1e99372a51` |
| S6 | `d0153c5a1098708673bfa64ee7f2a835e39ffc0e` | `af524d64bca1a0378e5b82ab92e70c1e99372a51` | 5 文件 +236 | `6646ef7259e0edc36a00ec89c8d446fe0d7e4972` |
| S7 safety | `549837c191230a4b55ee30dd0a68fb4f89d080f6` | `6646ef7259e0edc36a00ec89c8d446fe0d7e4972` | 18 文件 +209/-23 | 本次 S7 证据 |
| S7 lifecycle | `79ba7bb1509dcc0747239d6e2c23a0daf45c0aff` | `549837c191230a4b55ee30dd0a68fb4f89d080f6` | 7 文件 +110/-15 | 本次 S7 证据 |
| S7 queue/intent | `008343bb26d2eb76c23cae9b98132af828a9f447` | `79ba7bb1509dcc0747239d6e2c23a0daf45c0aff` | 8 文件 +142/-44 | 本次 S7 证据 |

完整线性父链、各证据提交 parent、逐文件差分及最终源码原始长度/SHA/Git blob 见 [commit-ledger](evidence/p8-s7-20261019/commit-ledger.json)、[源码身份](evidence/p8-s7-20261019/final-source-identities.json) 与 [完整源码 diff](evidence/p8-s7-20261019/all-source.patch)。本报告与 S7 证据独立提交于最终源码之后；其提交身份以 Git 为准，不能在自身提交中自引用 SHA。

## 2. 官方复用与 Windows 适配

| 组件 | 已实施 | 边界 |
|---|---|---|
| Entity / anchors / versions | 复用既有 shared Entity、Generation/Anchor/Version policy；官方 VoiceMessageRepository 移至 shared | 不更改 Entity、Package、schema 或聊天消息音频字段 |
| 仓库 | Android 默认读策略不变；Desktop 严格完整读取、持久化回读、版本和同锚点多录音 | 索引未知、损坏、相对路径歧义均保全；没有启动 orphan sweep |
| 正式音频 | `audio/generated/<session>/<uuid>.mp3`，流式 `.part`、长度/MP3/时长验证、原子发布、独立 ownership receipt | 仅已确认 own 且无引用的文件可删；不递归清空目录 |
| Windows 输出 | 复用 MCI，正式/preview 两个端口共用一个输出 owner | MCI legacy、设备/解码器与物理 I/O deadline 限制保留 |
| 标签/翻译 | 官方严格协议和原 Prompt 字节移至 shared；Android gateway 保留 task identity | 非法 JSON、缺 ID、重复、未知字段或截断不能降级为原文 TTS |
| 生成协调器 | 官方目标/锚点/确认/部分成功/替换/排序逻辑的 Desktop 平台适配 | 全进程正式及 preview TTS 共享 5 许可，许可保持至 body 真正排水 |
| 传输 | 复用 shared TTS request factory；固定 endpoint、一 shot body、一 exchange guard | 无 redirect/retry/authenticator；503 follow-up 被挡时保留可信首次状态 |
| 聊天 UI | 显式单段/整条、旁白选择、语音条、进度/取消、播放/停止、追加/替换、保存/删除 | 不重建已验收 picker；助手回复完成本身不发 TTS |
| P11 | v8 JSONL/media、legacy 1–7、ID remap、媒体 staging/回滚 port | 仅音频资源适配，不是整个 SaveSlot/backup 事务 |
| Windows QQ | **BLOCKED**；提供明确 MP3 保存 | Android accessibility 不可直接复用；导出不冒充 QQ 已发送 |

正式音频使用 **16 MiB/clip、30 分钟** 的结构/native admission；preview 保留已验收 **16 MiB/clip、64 MiB/session、120 秒**，二者 ownership/cache 不混用。P11 staging 当前 64 MiB/lease、10,000 metadata 限制须由 P11 显式接续，不能绕过预算部分提交。

## 3. 可用行为及验证边界

| 功能 | 工程证据 | 本轮最终实包人工 |
|---|---|---|
| 持久化、无 Key 播放、重新加载旧录音 | persistence / safety mock PASS；重新实例化仓库模拟重启 | NOT TESTED：实际退出重开 |
| 角色唯一匹配、reference、旁白、全局/会话听书 | shared policy / generation PASS | NOT TESTED |
| 单段/整条、补齐已有锚点、同锚点历史 | coordinator / runtime PASS | NOT TESTED |
| 新录音与替换区分 | 追加新 ID；替换成功持久化后释放旧 native alias 并删 own 文件；失败保留旧录音 PASS | NOT TESTED |
| 翻译/标签、AI 文本变化确认 | Golden / strict gateway / coordinator PASS | NOT TESTED：真实模型质量与实际弹层 |
| 并发 5、部分成功、取消排水 | loopback 与受控输出 PASS；部分失败不自动播放 | NOT TESTED：真实网络/设备 |
| 前台自动队列、手动优先、离开会话 | click token、mutex admission、foreground drain PASS | NOT TESTED：最终包听觉 |
| 消息编辑/删除/版本切换、显式录音删除 | shared anchors/version、runtime、受影响 chat/image 回归 PASS | NOT TESTED |
| 保存 MP3 | 显式 own 文件验证和导出路径测试 PASS | NOT TESTED：原生保存窗口 |
| P11 v8 / legacy / rollback | 3 项 audio-only handoff PASS | NOT TESTED：全归档与跨端往返 |
| QQ native voice | BLOCKED，未验证授权等位接口 | NOT TESTED；未发送任何联系人消息 |

队列 admission 在播放 mutex 内再次核验前台代次；UI 点击时捕获 token，离开后返回也不能把迟到点击认作新的 autoplay 意图。切会话的输出停止串行排水；播放持有数据操作 lease 至 stop/close。替换与队列释放串行化。既有聊天图片清理仍使用自身 normal/exclusive 作用域，避免外层 normal lease 阻挡维护清理。

正式 cleanup 重新读取严格完整引用清单，使用绝对规范化 Path 身份（Windows 大小写等价）；相对 legacy 路径、未知实体、异常提交均不构成删文件依据。测试的 restart 是仓库重新实例化，不冒充真实 Launcher 重启或断电耐久性。ownership receipt/hash/identity 减少误删风险，但没有证明可以消除所有外部 TOCTOU、硬链接或物理设备故障。

## 4. 测试账本

原始命令、exit、XML、stdout/stderr、资源采样与逐 suite SHA 见 [S7 test ledger](evidence/p8-s7-20261019/test-ledger.json)。各轮存在重叠，不能把下表相加成唯一测试总数。Gradle 任务 NO-SOURCE/SKIPPED 与 JUnit skipped 另行区分。

| 轮次 | 实际结果 |
|---|---|
| S3-01～04 | persistence 5 PASS；时间戳 fixture 编译 RED、文件枚举顺序 RED 保留；修复后 shared 32 / Desktop 14 PASS |
| S4-01～03 | 两次 mock 请求等待 RED 保留；修正 QueueDispatcher 的 POST admission fixture 后 shared 54 / Desktop 12 PASS |
| S5-01～04 | compile PASS；Windows fileKey null RED 保留；复用已有 native identity 后 6 suites / 63 PASS |
| S6-01 | 3 suites / 11 PASS |
| S7-01 | SharedCore 134 suites / 892 PASS；Desktop 6 suites / 23 PASS |
| S7-02 | shared 54、Desktop 33、Android 8 suites / 30 PASS；Android compile PASS |
| S7-03 | Desktop 9 suites / 37 PASS |
| S7-04 bounded | **166 suites / 1551 tests：1547 PASS、2 FAIL、2 SKIPPED，exit 1** |
| S7-05 | 修正数据作用域与排水后，13 suites / 129 PASS |
| S7-06 | 强制重新编译：Desktop 15 suites / 142 PASS；shared 2 suites / 22 PASS |
| S7-07 | 旧源码 79ba7bb1 实包与 SharedCore 892 PASS；包保留，已被最终包取代 |
| S7-08 | queue 修复聚焦 15 suites / 144 PASS |
| S7-09 | 最终源码内容：Desktop 15 suites / **146 PASS**；shared voice 7 suites / 34 PASS，另存 XML |
| S7-10 | **命令解析 RED，exit 1，0 tests**：任务数组被子 PowerShell 当成一个 task 字符串；未进入编译 |
| S7-11 | 正确参数直接调用 runner；最终精确源码 SharedCore **134 suites / 892 PASS**，distribution BUILD SUCCESSFUL，exit 0 |

bounded 的两项 FAIL 分别是：

1. `DesktopImageClipboardFilesTest.junction target is never inspected changed or cleaned`：启动 `cmd.exe` 的 CreateProcess error=5，原始环境相关失败保留，未排除或宣称修复。
2. `DesktopImageClosureR1GifTest.edit picker...`：本轮语音 mutation 外层 normal operation lease 与既有 image exclusive cleanup 嵌套引入的真实回归，已修复；后续该类 5/5 及受影响 chat/data/lifecycle 回归 PASS。原始 bounded FAIL 不改写。

仅执行一次完整 bounded，沿用原四项排除：WindowsBackgroundViewerTest、WindowsStudioEdgeDragTest、WindowsWindowChromeTest、DesktopImageWindowPresentationTest 的既有 short-workspace 方法；不增加新排除。该轮 FishPicker NativeUx **4/4 PASS**，只说明该次自动化实际通过，不解释历史 R6F/R6G Press0。最终 queue/intent 源码没有再运行 full bounded，不能宣称最终源码 full bounded GREEN。

R6D/R6E 历史 Native PASS、R6E cmd RED、R6F/R6G RED（原因 UNKNOWN）、R6I/R6J INCONCLUSIVE、两项旧 symlink SKIPPED/NOT TESTED 均保留。此前 UI-LAYOUT-001 的限定 Project 接受不被撤销，也不被拿来签署本次聊天语音包。

Android consumer 在 S7-02 验证（30/30），之后没有修改 Android consumer 源码；最后 shared repository 新增清理证明及 transport 可选显式 close API，故以此前消费者验证加最终 shared 全量为证据，不冒充最终源码重新跑了 Android。官方 Prompt 字节迁移和最终 serialized messages Golden PASS，未修改原有 Prompt 文字；Entity、schema、shared RequestFactory、validated baseline、CURRENT/parity 未变，见 [合同差分检查](evidence/p8-s7-20261019/unchanged-contract-check.json)。

## 5. 最终 Windows 包与隔离准备

最终包路径：`H:\ChatChatBar-Desktop\app\desktopApp\build\p8-remaining-20261019\windows-final-gate\runtime-image`。

212 文件 manifest、Launcher 与两个业务 JAR 的完整 SHA-256/长度/关键 class 身份见 [最终 package identity](evidence/p8-s7-20261019/windows-preparation/package-identity.json) 与 [全文件 manifest](evidence/p8-s7-20261019/windows-preparation/distribution-manifest.json)。Manifest SHA-256 为 `cd15d79587869d864ea4777aecef7a557086aa95d6838146c8f7bf83f652a7f7`；Desktop JAR 为 `cc0c9117f10926d36693060e3b8b18304dba9ef6974c66acf8c10b1a19ca5fc3`，shared JAR 为 `ec4604e1bbe0fe1226137e4cbb880f9af6d31aa5beb084655aa03fe983b02c3a`。旧 79ba7bb1 的 212 文件包、manifest 与构建日志另存 superseded-package；不能将旧包当作新源码包。

最终辅助工具只做离线 compile/selfcheck、创建新的 disposable 合成根、保存合成 DPAPI Key 及独立根锁获取/释放；这些 helper 已退出，不是实包运行中排他或 Launcher/JVM 退出验收。没有启动 fake listener、TLS 证书生成或 GUI。准备目录中的合成卡、聊天、模型配置与真实用户数据分开；无真实 Key 或聊天读取。

启动器需要显式 `--start-p8-s7-manual-after-user-ready` 且 single-use lock；启动前重新验证完整 212 文件、支持工具、synthetic JSON 和 DEFAULT bootstrap。fake 只在 loopback 终止固定域名的 HTTPS CONNECT，不向外转发；只接受无效的合成凭据及明确免费 model。复用的 canned MP3 不能证明真实 TTS 输出或翻译质量。程序由用户正常关闭，不因操作捕获结束自动关窗。实际退出确认后才排水 fake、清临时 TLS；正式生成录音按 ownership 保留，不能误清为 preview cache。

人工验收、实包 Launcher/JVM exit、运行中根锁拒绝、退出后重获、文件句柄、缓存/TLS/fake 的运行后清理，当前全部 **NOT TESTED**；只有离线 helper 获取/释放根锁 PASS。下次受控启动必须重新核对本报告对应包和隔离根身份，不能直接使用旧运行目录状态。

## 6. 请求、资源与费用

真实 Fish **GET0 / TTS0**；收费辅助 LLM0；QQ 发送0。本轮未选择/读取安全 live credential，也没有完成当前免费资格的独立有效复核，因此按合同跳过 live，不用旧免费公告冒充新资格。正式与 preview 均 fail closed：仅 `s2.1-pro-free`，收费/失效 ID 不 fallback，2026-11-30 后需再核验。Git fetch/只读 upstream 和文档考证不属于 Fish 请求。

mock 测试含多轮 loopback 请求及显式取消，用例断言请求次数、最多 5 并发、503 不重发和零非法请求；XML/源码逐例可追溯，未把它们伪装成外部调用。人工 fake 当前0次，因为还未启动。后续 manual 将单独记录 catalog/sample/canned TTS/auxiliary 数量。

各 Gate 可用内存、Commit 余量、pages/s、Java PID 及原始过程见 resource ledger。S7 最低可用物理内存采样为 325,849,088 bytes（旧包构建），最终包构建最低 495,792,128 bytes；各轮最低 Commit 余量不少于 8,702,558,208 bytes。存在明显物理内存压力，无观察到 OOM；不把压力直接归因为断言失败。没有杀用户进程、改 pagefile 或清历史缓存。最终构建和 helper 完成后的实际进程清单另存证据；没有启动最终应用，不能由 helper 退出推导实包0/0。

## 7. 集中人工验收安排（等待用户准备）

每次只发 1–3 个动作，等用户报告再继续，不要求一次完成整表。

| 组 | 合成场景与预期 | 当前 |
|---|---|---|
| W1 | 新包/合成会话 A；显式单段及整条生成、不同绑定、旁白选择；能听到 fixture、停止有效，完成进度可见 | NOT TESTED |
| W2 | 同锚点再生成一条保留旧录音；编辑标签并明确替换；独立播放/保存/删除 | NOT TESTED |
| W3 | 听书/语言设置、标签变文确认或拒绝、版本切换和消息编辑；不改变原聊天文本，失败保留旧音频 | NOT TESTED |
| W4 | 批量等待取消、会话切换、picker 与正式输出互斥；没有迟到自动播放或恢复已删除记录 | NOT TESTED |
| W5 | 用户正常关窗；核实实际 Launcher/JVM exit、根锁排他/重获、fake 与 preview/TLS 清理；明确同根重开后旧正式录音可播 | NOT TESTED |

W1–W5 是最终验收计划，不是已执行的 PASS。真实 Fish 服务、全设备故障、长音频物理超时、完整 P11 跨端和 QQ 仍须独立证据；合成回放只验证 UI/请求/持久化/输出路径。是否授权重开及后续实际状态需用户确认；不为等用户而自动驻留 GUI。

## 8. 交接与保全

[P11 音频接口与 QQ 审计](142_PHASE8_VOICE_P11_HANDOFF.md) 是 S6 交接候选，未进入 P11 全备份、P9 Writer/Recovery 或后续 Phase。WIN-AUTO-001 OPEN、WIN-BRAND-001 OPEN/DEFERRED；官方 sample.text 最多两行的历史 UX 需求没有顺便改动。

四份 user-owned 文件始终 KEEP / UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED，仅核对路径状态，不读取内容或计算其哈希。初始及历史 R3–R7、S3–S6 evidence 未覆盖；所有原始 RED/XML 保留。未 reset/rebase/stash/clean/force，未更新 desktop/master/sync，未提升 parity，未发布。

阶段结果：S3–S6 工程候选已提交；S7 自动验证与最终包准备完成，保留 declared limits；等待用户集中 Windows 验收与 Project 独立复审。**不自行宣布 P8 EXACT/EQUIVALENT/CLOSED。**
