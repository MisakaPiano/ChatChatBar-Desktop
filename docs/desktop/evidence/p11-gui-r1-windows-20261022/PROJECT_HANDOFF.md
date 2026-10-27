# GUI R1 Windows 集中复核交接

日期：2026-10-22（Asia/Tokyo）。本次一次启动已完成并正常关闭，提交 Project 独立复审。P11、Preview Gate、Post-P8 HOLD 不提升。

## 身份及执行边界

- 控制 HEAD：`80cdaa9e15191ecb5c4ff20db8682b687e9a6ec6`；分支 `feature/phase8-fish-audio`。
- 冻结生产：`d95501f87899be47d07537132793afc90b9f4acc`。
- 冻结目录：`app/desktopApp/build/p11-gui-r1-20261022/windows-frozen-d95501f8/runtime-image`。
- 发行 manifest SHA-256：`4732d1dd9f4de95f6af654b120c210b19405a9e83b887ff937e6ff826dfe6b91`。启动前及收尾均验证 217/217 文件，无缺失或额外文件。
- 新隔离根：`app/desktopApp/build/p11-gui-r1-windows-20261022/app-data`。从已验证的冻结 `materials/manual-ready.cbbackup` 经正式映射、恢复 journal、图关系校验准备；未复用已被旧 GUI 修改的根。
- LOCALAPPDATA、APPDATA、USERPROFILE、user.home 和临时目录均指向本轮环境；预建 Desktop 和 PNG 目录。正式设置 `allowCleartextModelApi=true`；唯一模型地址为 loopback，模型凭据为空。
- 一次授权只消费一次。无安装、重启、真实数据或凭据访问、真实 Provider 请求、源码修改、合并及公开发布。
- 四份既有用户文件保持 KEEP / UNREAD / UNHASHED / UNMODIFIED / UNSTAGED；只暂存本目录。旧 FAIL、RED、JUnit 和历史报告均未修改。

## 判定矩阵

| 项目 | 本轮判定与证据范围 |
|---|---|
| 启动 | USER PASS，原话“启动成功了”；唯一主窗口、进程与根锁另有技术证据。 |
| A 带录音截图、独立预览、PNG 导出并打开 | USER PASS，feedback-004 明确“通过”；消息图片和文字均在导出图中，无录音条。 |
| A 无录音长图与滚动缩放 | 技术 PASS，feedback-006 展示 22% 全图、131% 适应宽度以及第 100 段底部。用户没有单独文字 PASS，不能冒称。 |
| A 子窗口关闭不退出主程序 | 技术 PASS，后续回到 A 操作菜单并再次打开截图工作区；主程序持续存活至统一收尾。 |
| B User 菜单及操作目标 | 技术 PASS，feedback-007 真实 User 菜单仅复制/编辑/删除/截图，底部仅复制/编辑右对齐；截图工作区预选该用户消息 1 项，其他消息可选。用户未对此单独给文字 PASS。 |
| Assistant 三行菜单、C 窄窗和恢复 | USER PASS，feedback-008 明确“通过”；标题/模型说明单行省略、操作区另起一行、聊天输入可见、恢复普通宽度。 |
| D/E 图标、暂停续播、真实进度 | USER PASS，feedback-009 明确“通过”；播放/暂停/续播截图支持状态变化，动态进度停止与原位续播以用户操作确认作为依据。 |
| F 短回复录音条宽度及控件 | USER PASS，feedback-011“都满意”；普通与窄窗图中控件完整且不无条件铺满。 |
| F 长回复带录音宽度 | 用户明确接受；本轮 Windows GUI 实际覆盖 NOT TESTED。已有工程测试不冒充人工测试。 |
| G 管理标题分区 | USER PASS，feedback-010 图像及 feedback-011“都满意”。 |
| 安全收尾 | 技术 PASS，Launcher/JVM 宿主真实退出码均为 0，根锁释放、fake 停止、备份暂存为空。 |

本轮没有新增用户明确的 USER FAIL。窄窗 Assistant 菜单右侧标签部分截断可见；操作可达性未另行点击验证，记 INCONCLUSIVE。源码每行支持横向滚动；不得因此把截图中的截断描述为完全不存在，也不覆盖用户通过结论。

## 独立改进建议

1. 保存路径不能复制；用户进一步明确希望路径可直接点击，打开刚保存的图片。优先记录此实际需求，不仅是打开目录。本候选未实现该改进。
2. 极窄窗口下菜单能否悬浮至应用窗口外；用户说明不强求。当前实现限制在主窗口范围，后续评估独立弹出层的多屏、缩放、焦点及关闭行为。本轮只登记。

PDF/Word 导出仍为历史登记的后续需求，本候选只有 PNG；没有为本次验收增加这两种格式。

## 收尾及证据

通过 computer-use 技能的定向 Alt+F4 正常关闭唯一冻结主窗口，没有强杀。Launcher PID 33424、JVM 宿主 PID 14548 均获得真实退出码 0。JVM 运行于同名 `ChatChatBarDesktop.exe` 子进程；初始监视器只匹配 java.exe 因而生成 UNKNOWN，另一个在退出前保留原生句柄的监视器获得真实 0。两份原始结果同时保留，不能删除早期 UNKNOWN。

fake PID 28648 正常停止，仅收到 `/health` 一次，没有 chat/TTS/转发请求。进程及监听检查为空；独立 JVM 根锁探针可取得并释放。备份暂存 0 项，8 条合成录音文件仍存在；原冻结合成备份 hash 未变，恢复 journal 保留在隔离根。

`raw/` 保存用户原话、全部本轮提供的截图、实际导出 PNG、准备脚本及安全记录；`evidence-manifest.json` 提供精确文件 hash。`shutdown-verification.json` 记录 PNG 尺寸、hash 和收尾结论。`SESSION.md` 保留逐次范围说明。

准备辅助脚本遇到 Python 版本 API、Java 泛型、默认编码错误，已修正；不是产品 GUI FAIL，未消耗额外启动。编译错误原始日志保留。准备资源检查发现其他项目的测试进程，未干预；约 40 GB 磁盘及约 2 GB 空闲物理内存未被任意阈值误判为阻塞。

技能相关性复核：本次仅新增验收证据，没有改变稳定实现或工作流，现有技能无需修改。没有重复安排此前未受影响的存档、备份、RAG、Memory 基础操作。

下一步由 Project 对以上限定范围独立复审，不自动签署 P11 ACCEPTED 或解除任何 HOLD。
