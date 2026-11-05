# Desktop 生成并发与源提交保护

2026-11-05。用户授权 Desktop 与 CCB 本体分别修复、分别验证；本体由原上游对话独立执行并准备 PR，本文件不代表本体实现或验收。公开1242/dc/签24为密码流程独立交付；本并发后继尚无公开许可，不将新并发修复混记为1242内容。

## 已核对的锁定范围

| 正在运行 | 新请求 | 1241实际行为 | 本后继范围 |
| --- | --- | --- | --- |
| 同会话回复或生图 | 完成的旧消息语音 | textBusy将任意RUNNING任务当文本，拒绝 | 改为精确消息/版本目标准入 |
| 语音 | 普通新回复 | 没有语音全局排除 | 保持；同目标重生成仍按原drain |
| 回复 | 完成旧消息生图 | 可准入；不可变expected与最终eligible复核 | 保持；最终短提交仍可能等待维护排他 |
| 任意朋友圈生成 | 已存图片版本选择 | 全局busy禁用 | 可选择；完成时按latest原子追加并保留当前选择 |
| 朋友圈网络阶段 | 同会话回复/生图 | withMessageMutation持续占会话修改准入 | 改为独立worker/creative child/normal数据lease，发布复核session/card |
| 生图 | 另一NOVELAI任务 | TaskRuntime全局单任务限制 | 尚未改变；不能由此文声称两个图像请求已并行 |
| 任意普通生成 | 数据迁移/恢复等维护 | normal工作登记与exclusive维护相斥 | 保留；normal登记彼此可并发，不是网络总互斥 |

上表是具体调用链与本后继改动范围，未执行的并发路径不能仅凭源码写成通过。声音播放单输出与TTS合成5槽是不同资源。

## 目标与提交

入口保留会话/消息/版本/anchor快照；REAL_CHAT重生成在调度前即登记原目标。旧完成消息可与其他目标的回复、生图和朋友圈同时工作；正在修改的同目标、格式修复及其不确定提交仍拒绝。

ChatRepository新增短时withLockedMessageSnapshot，与本实例的append/update/delete/整会话退役共用原mutex，只包本地durable读回、版本/anchor复核及语音save。网络、外层voice.mutation及cancelAndJoin不放进此锁。显式缺失版本或anchor不再回退旧快照/相邻片段；无anchor的整条录音也核对消息仍在。原语音save异常的durable正向确认与严格引用清理继续保留，未知不是可删除证明。这不是跨进程文件事务保证。

朋友圈允许“只改变已存版本选择”；正文、人物/卡/会话身份、完整候选集合变化仍拒绝过期生成。追加使用latest，已有选择保持；无图首次生成的原选择规则保持。worker发布前检查原session/card仍关联，单个Stop保留原worker和批次身份。

## 必要合成验证

新增实际回复SSE、图像worker、朋友圈worker与旧消息TTS请求同时保持的测试，逐一取消并检查其余owner继续；全部为本地回环或人工generator，无真实Provider费用。另测调度前重生成目标保护、TTS网络之后正文/消息/会话/版本变化拒绝（整条与片段两路）、源短锁与修改/删除/退役串行、图片生成保持时选择与追加/重开、真实候选集合/正文/删除拒绝及busy头像控件边界。复用原voice安全/不确定提交与控制器测试。

R02/42040209012原八类58方法全部PASS，0FAIL/ERROR/SKIP：ChatVoice9、FishGeneration13、MomentImageVersionsPanel2、MomentImageVersions2、MomentsController4、MomentsPanel1、TaskRuntime22、VoiceSafety5。原artifact13421470505/392264B/SHA432d068619a1ae80931a5ba95acfb701b0faa499610f9142af5fe30098c30983与逐方法XML已核；420暗/1100亮控件图已审。原R01因两个新增fixture漏必填negativePrompt而测试未编译，test-only5f补齐，失败保留。不宣称绝对无竞态、真实Provider并发或用户验收，也不把密码53项证据当并发证据。

## 完整与普通包后续验证

冻结应用5f7c8aef85668658feec99131af04cfc1b27e038已进入完整160/42042181005。首轮Windows主任务下载Gradle时Connection reset，Linux下载Kotlin编译器时HTTP504，两者均未执行主tests；后置检查仍按实际结果保留，已在完整收尾后请求同源失败job第二轮，不将下载错误写为源码测试FAIL或PASS。

普通私有1243 R46/42042617523已构建；原native报告显示1225基线、升级/重开正常退出、忙拒绝pointer和manifest不变，以及冷四Unicode路径FIFO投递保全分别成功。但cold投递后读取Job子进程PEB/CWD的ReadProcessMemory拒绝，原报告仅PROCESS_MEMORY_FAILED、无法证明良性退出竞态，独立ZIP步骤未执行，整包未ready。控制094b36ce/R47/42046772785只为该拒绝补pid/Win32/退出码观察，严格拒绝保持，并恢复R46原两artifact做必要精确字节复验，不重建应用。新的成功不能将R46原失败改为通过。
