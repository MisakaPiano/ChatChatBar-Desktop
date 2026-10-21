# P8–P11 统一 Windows GUI 验收交接

日期：2026-10-22（Asia/Tokyo）。本轮单次 GUI 已正常结束，不自动重启。

控制提交 `37c67c724fe21107d7eaff0306439481c6f2bf07`；冻结生产 `70b5c30c91439e79728bf224c1a44d35b25cf733`。正式 upstream baseline 仍为 ChatBar 1.4.1。没有改生产代码、Prompt、schema、技能、基线或 parity，没有安装、发布或调用真实 Provider。

## 总结

用户完成了聊天/语音、存档创建/复制/恢复/删除、资源重绑定、记忆及文档展示、加密完整备份导出/检查/取消和主窗口关闭。截图入口实际失败，独立预览与 PNG 导出因此未执行。User 右键菜单及极窄布局也有明确失败。改进建议与功能失败分开记录。

Post-P8 HOLD、P11 ACCEPTED、Public Preview Gate 均不由本报告解除。下一轮应先处理已确认核心问题，再以新授权集中验收；不重复已通过普通项目。完整恢复第二次 GUI、安装升级、Android 真机、真实模型质量均未测。

## 精确运行身份与技术结果

- 包：`app/desktopApp/build/p11-review-fixes-20261021/windows-frozen-70b5c30c/runtime-image`。启动前及退出后 **217/217** 路径、大小、SHA-256 一致，额外/缺失/不符均0。manifest SHA-256：`841924831a065c080e502860bdd4631a9d919975438414b8b474b16ed4add71c`。
- 隔离根：准备目录 `p11-unified-acceptance-prep-20261021/environments/manual-ready/app-data`；LOCALAPPDATA 使用该目录下 `profile/Local`，USERPROFILE/user.home 使用 `synthetic-home`。合成受保护凭据只验证前缀/可读性，值未输出、未哈希、未提交。
- 一次性许可消费一次；Launcher PID11932，JVM 所在子进程 PID23172，两者都为冻结目录的 ChatChatBarDesktop.exe。fake/监护 PID22044。只绑定127.0.0.1:18793，无转发客户端。
- 运行中独立 JVM 锁探测拒绝 ROOT_BUSY；退出后独立 JVM 获锁并释放，exit0。最终本轮应用/fake 进程及端口监听均为空。
- **Launcher exit0；JVM 子进程已退出，但退出码未取得**：PowerShell记录器输出null，原始记录保留，不补写0。不能声称Launcher/JVM均0/0。
- Windows窗口可见且响应，用户确认看见。Computer Use图像捕获首次FrameArrived超时，后续文本观察为空，未作为产品失败；用户截图为视觉证据。不强杀、不重启。
- 启动前可用物理内存1971292KiB、提交余量7605388KiB、H盘41776021504bytes；运行快照1071508KiB/6553132KiB。运行中另外两个Java经分类属于Gradle，不含测试根/CCB Main，未干预。
- fake最终仅GET /health 1次及POST /v1/chat/completions（stream=true）1次，无转发。无真实API操作；未做系统级全流量抓包，不能将日志计数说成网络审计。
- 备份检查暂存从193文件降为0；无手工清理。源归档、合成根及历史恢复日志保留。未做全系统native handle枚举；进程退出与锁释放是本轮证据边界。

## 人工矩阵

USER PASS仅用于用户明确口头签署范围；只发截图的项目另列视觉证据PASS，不擅自扩展主观签署。

| 项目 | 结果 | 证据/限制 |
|---|---|---|
| User下方仅复制/编辑并右对齐，助手菜单普通宽度，播放暂停原位继续 | USER PASS | 用户后续明确澄清“给我的测试项目都通过了”；不扩展至极窄场景 |
| User右键菜单 | USER FAIL | 仍出现助手三行菜单/生成等无关操作；期望仅复制、编辑、删除、截图（进入长截图模式） |
| 极窄标题/说明及聊天布局 | USER FAIL | 逐字换行撑高头部，聊天不可见；应单行省略 |
| 录音…/右键共享浮窗 | USER PASS | 不行内撑开聊天 |
| fake流式回复、复制；User编辑保存 | USER PASS | 初次HTTP保护拦截属于准备遗漏；允许本地HTTP后1次请求完成，旧阻塞保留 |
| 截图工作区打开 | USER FAIL | 无法读取截图内容；后续预览/PNG/子窗口关闭BLOCKED/NOT TESTED |
| HEAD、旧版参考、文档正文 | 视觉证据 PASS | 合成持久化内容；长期记忆关闭、文档PENDING；真实生成/检索NOT TESTED |
| 存档创建 | 视觉证据 PASS | 6消息、1图片、4录音；原图和录音选中 |
| 复制会话 | 视觉证据 PASS / 技术部分PASS | 副本2出现；原6消息/4录音记录hash未变；会话JSON hash有变化，未做完整字段归因，不称全根字节不变 |
| 存档恢复 | 视觉证据 PASS | 恢复测试消失，123/回复保留；恢复前保全新增 |
| 指定存档删除与媒体保留 | USER PASS | 用户明确pass，截图显示删除完成及已有录音播放；未删除恢复证据 |
| 角色卡/世界书重绑定 | 视觉证据及持久化核对 PASS | 副本2绑定预设MujicaMyGO及worldbook；保全新增；不代表新角色对话质量 |
| 加密cbbackup导出与检查 | 文件/视觉证据 PASS | 实际文件存在，应用检查192文件/10682KiB；未安排恢复 |
| 取消恢复、主窗口关闭 | 用户确认关闭 / 技术PASS有边界 | 摘要撤销、数据未替换；Launcher0，JVM码未知，锁重获、scratch0 |

## 截图入口诊断边界

`DesktopChatScreenshotHost.kt:46–48` 为排除录音构造空的非null voices map，传入 `verifyScreenshotMessages`。`DesktopPrimaryChatController.kt:208` 对非null map仍比较实际placements与期望空列表。有录音会话存在必然拒绝路径。**源码矛盾CONFIRMED；本次实际异常的精确归因POTENTIAL**，入口catch丢弃Exception，日志无堆栈。本轮未改代码或注入诊断，也未删除音频绕过失败。

建议后续在独立修复点让“截图不包含录音”与“是否执行录音身份校验”语义一致，同时保留消息/版本/图片身份验证，增加带录音会话打开截图的回归。本轮不是修复授权，不据此宣布解决。

## 用户意见与纠正

完整原话见各feedback JSON；此前误分类保留并附用户更正，不覆盖历史。

1. 标准播放/暂停与音频状态图标；底部播放器播放时右侧为暂停，暂停后才为停止。用户明确这是改进，不是功能FAIL。
2. 语音条内部播放进度；通常与对应气泡同宽，短气泡时优先完整显示控件，最大宽度时才用当前铺宽方案。进度显示不自动包含Seek。
3. User右键仅复制/编辑/删除/截图，和气泡下复制/编辑工具栏区分。
4. 标题及模型说明窄窗单行省略，不逐字换行。
5. “整应用备份与恢复”“版本与更新”标题太小，需要字号/字重/间距明确分区，登记改进意见。
6. 副本2出现恢复测试曾引起独立性疑问，用户明确“我很确信是我手动加的，因为之前测试的时候我一开始编辑错对话了”。疑点解除，不记跨会话污染。

## 准备问题，不伪装成产品缺陷

- loopback HTTP环境未提前打开正常HTTP端点许可，首次请求被客户端正确拦截，fake未收请求。用户经正常设置开启后成功。未绕过保护/未修改正在运行的数据文件。
- 指定exports/cbbackup原本存在，但synthetic-home/Desktop不存在，系统保存器报位置不可用。用户将新ChatBar.cbbackup保存到旧验收exports/png；保留原件，将新归档以CREATE_NEW复制到本轮目录，hash相同，补建空隔离Desktop。未覆盖历史文件。
- 新包保存路径：`app/desktopApp/build/p11-unified-acceptance-prep-20261021/exports/cbbackup/gui-unified-20261022.cbbackup`。10311321bytes；SHA-256 `0c038bfc9a76bd0209aa7e088ef2f4f9dcdacd17bb9f4c9d26cdcad00ce69799`。检查192条是归档解密条目数，不是发行包文件数。
- 证据收集一次PowerShell管道语法错误，未执行文件操作；修正后成功。记录器JVM退出码null如上；不补跑GUI获取绿灯。

## 保全与后续

raw含原截图、反馈、授权前状态、PID/锁/fake/退出/清理结果和最终合成根515文件身份清单；不提交合成聊天正文归档、受保护凭据或真实文件。原始合成根/旧包/旧RED/546与141项工程证据不修改。四份受保护用户文件仍只检查路径状态，不读/哈希/暂存。

没有运行新测试或重打包。技能审查：本轮只增验收证据，现有技能未因实现变化失效，无需修改。只提交本目录并推送现有feature分支。实际第二次启动、完整恢复、安装升级仍需独立授权。
