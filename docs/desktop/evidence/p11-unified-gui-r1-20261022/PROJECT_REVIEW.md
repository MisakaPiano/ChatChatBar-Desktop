# P8–P11 统一 GUI 验收 R1：工程修复评审

日期：2026-10-22（Asia/Tokyo）。状态：工程回归通过，提交 Project Review；新的 Windows GUI 验收未启动。

## 控制点与范围

- 分支：`feature/phase8-fish-audio`；修复基点：`ac34ac4881e96c5a8cb68a0a9961578283dd5862`。
- 上一次冻结生产：`70b5c30c91439e79728bf224c1a44d35b25cf733`。旧冻结目录、旧 USER FAIL/RED 和用户原话全部保留。
- 原始验收交接：`../p11-unified-windows-gui-20261022/PROJECT_HANDOFF.md`。本报告不修改历史判定。
- 本轮仅 Desktop 生产代码、定向测试及相关技能/证据。没有修改官方 Prompt、Package/Entity/schema、正式 upstream baseline；没有重做 P9/P10/P11。
- 测试只使用独立临时合成根、内存凭据和本地假传输；没有真实 Provider、付费 TTS、Android 数据或真实用户根。原 GUI 合成根也没有被复用或修改。
- 没有启动 CCB GUI，没有合并 `desktop`、安装应用或发布 Public Preview。P11、Preview Gate、Post-P8 HOLD 原状态不提升。

## 已交付

| 项目 | 修复及证据 |
|---|---|
| BLOCKER-01 带录音截图入口 | 正式 `VoiceMessageRepository`、锚点和 `DesktopVoiceAudioStore` 持久化合成 MP3 后，原校验明确失败为“截图录音已变化”。修复移除截图 API 的录音比较参数；校验会话、版本、消息存在性和相对顺序。图片字节 hash 及保存前资源复核继续保留。 |
| 截图选择/预览/导出 | 实际 Host 用带持久化录音的会话打开选择和独立预览；无录音路径也通过。带/不带录音渲染 PNG 字节完全相同；音频 block 被拒绝；新文件 PNG 写入和字节读取通过；正式音频记录和文件仍存在且可校验。关闭、返回、Escape 和子窗口回调仅关闭自身的已有回归通过。 |
| 安全诊断 | 入口/预览/导出失败显示 `OPEN_*`、`PREVIEW_*`、`EXPORT_VERIFY_WRITE` 阶段；不输出异常载荷、聊天正文、Prompt 或凭据。原 GUI catch 没有保留具体异常，不能追溯声称已取得原次 GUI 的异常；本轮确认的是同调用合同下持久化夹具的确定性失败与修复。 |
| BLOCKER-02 User 右键 | 真实 User 消息仅生成复制、编辑、删除、截图四项，均指向当前整条消息；截图传给聊天截图 Host，而非系统截图。删除仍走现有确认及所有权/版本流程。User 底部仍仅复制/编辑，未改工具栏。Assistant 原回复/本段/整条 12 项及各自资格判断保留。测试对真实气泡发送右键事件并检查回调目标。 |
| BLOCKER-03 窄窗 | 新 `DesktopChatHeader` 仅约束聊天头部：标题/模型说明单行、禁止换行、超宽省略。可用宽度小于 480dp 时操作区另起一行，保留按钮尺寸和标题可读空间；普通宽度仍同一行。不全局修改 `PrimaryHeading`/`StatusText`。实际 360×800 和 1100×800 Compose 面板测量及图像通过，聊天和输入区保持可达。 |
| UI A 标准播放图标 | 录音条使用已有 Lucide Play/Pause；固定底部播放器右侧播放时 Pause、暂停时 Stop。保留原暂停、续播和关闭回调；状态切换尺寸不变。 |
| UI B 真实播放进度 | 正式播放循环在原互斥锁下读取 `DesktopAudioOutput.positionMs()`，暂停位置保持；续播继续读取。进度来自真实位置/持久化时长，不再使用脉冲字符。位置不可用时明确提示，不伪造百分比；恢复读取后清除该错误。没有 Seek。原生 Windows 静音 MP3 测试确认暂停位置 161ms、恢复后 180ms。 |
| UI C 录音条宽度 | 使用对应气泡表面实测宽度，最小 200dp 并受父级可用宽度约束。短消息完整容纳图标、时长、进度及操作入口，长消息按内容宽度。实际 User/Assistant 排列、短长对话、360/1100 宽度，以及 280/360/1100 控件视口均覆盖。 |
| UI D 管理分区 | 备份和更新面板使用 18sp 半粗体标题、上方 16dp/下方 4dp 分区间距及 heading 语义。实际面板布局测试通过；未修改备份、恢复或检查更新数据/网络行为。 |

## 工程验证与保留的失败

`engineering-results.json` 记录每轮命令、退出码和各套件最新结果。按套件最新结果合计 **163 tests / 0 failures / 0 errors / 0 skipped**，不是声称在单轮执行了所有 163 项。

- `05-red-blockers`：3 项失败。录音与菜单为有效产品 RED；第一版窄窗夹具尚停留在 compact 会话列表，不能当成标题缺陷的工程复现。该失败保留，后续夹具显式进入聊天并定位真实 header。
- `06-green-blockers`：3 项通过；随后扩展实际预览、PNG、资源/顺序以及声音和尺寸验证。
- `07-ui-and-screenshot`：28 项中 1 个测试查询失败。Compose 合并语义后进度语义归入可点击父级，修正测试查询；不是修改播放行为来隐藏失败。
- `08-focused-and-safety`：144 项中仅新增的省略查询失败；截图、存档、备份、根锁、正常关闭、语音安全均通过。
- `09-layout-refinement`：原生 Windows 音频暂停/续播通过；同一省略查询失败保留。
- 根因：实际依赖 Compose Desktop 1.10.3 `SkiaParagraph.isLineEllipsized(int)` 字节码固定返回 false。`raw/skia-ellipsis-bytecode.txt` 保留本地依赖证据。改用真正委托 Skia 的 `didExceedMaxLines`，并检查单行、禁换行、省略布局策略、边界和离屏图像。
- `10-final-presentation`：24/24 通过。视觉检查进一步发现 360px 同行头部只余约 24px 标题空间，随后改为窄窗两行头部。
- `11-final-layout`：18/18 通过，包含最终头部、实际短长消息录音条、管理标题、截图工作区和资源/PNG 回归。
- 早期 `01–04` 的缓存权限、夹具编译失败原始输出也保留，不当作产品 RED。

受影响安全套件包括 `DesktopVoiceSafetyTest`、`DesktopVoiceArchiveHandoffTest`、`DesktopVoiceClickReliabilityTest`、`DesktopVoiceUxTest`、`DesktopChatVoiceRuntimeTest`、`DesktopFullBackup*Test`、`DesktopSaveSlot*Test`、`DesktopDataRootOwnership*Test`、`DesktopShutdownWaitObserverTest` 和截图边界/工作区套件。没有对无关 RAG/Memory 基础操作重新安排点击。

## 证据读取

- `raw/11-final-layout/layout-previews/chat-360.png`、`chat-1100.png`：实际面板、标题省略、操作区及输入区。
- 同目录 `messages-*-short.png` / `messages-*-long.png`：正式持久化录音与实际消息排列；`data-sections.png`：两个分区标题。
- `raw/08-focused-and-safety/boundary-previews/`：截图边界图；各轮 `TEST-*.xml` 有真实尺寸和用例结果。
- 所有图像为 **离屏 Compose 工程证据，不是用户 Windows GUI PASS**。透明空余区域也不应解读为应用背景。
- `evidence-manifest.json` 精确列出 raw 文件路径、字节数、SHA-256。
- 冻结候选的源码提交、完整发行 manifest、ZIP 校验和见本目录后续 `CANDIDATE.md` / `package-verification.json`；构建不消费 GUI 启动许可。

## Project Review 后的一次集中人工复核

只有取得新的明确 GUI 启动授权后执行，使用新隔离合成根与冻结候选：

1. 带文字及正式录音的助手消息、无录音会话均进入长截图选择；User 右键截图从该消息进入并可选择其他内容。预览和 PNG 仅有文字/图片。关闭独立预览和截图窗口后主窗口仍可操作。
2. User 菜单仅四项、底部仅复制/编辑；Assistant 原三行菜单保持。短长消息、正常和窄窗下检查标题/模型省略、按钮、聊天与输入。
3. 播放/暂停/续播/停止图标和位置正确，真实进度推进/暂停，录音条宽度与内容协调、控件不挤丢；确认备份/更新标题分区。

不要求重复已明确 USER PASS 且未受本轮代码影响的存档、备份、RAG、Memory 基础操作。不将当前工程结论提升为新 GUI 验收通过。
