# Screenshot Workspace R2 / R2-R1 — Windows 人工验收交接

**整体未获用户接受，Post-P8 HOLD 保持。此提交仅新增证据，没有修复生产代码，也不签署 P8 闭环、Preview Gate 通过或发布许可。**

## 用户要求先于局部测试成绩

用户明确拒绝继续以零散边界验证代替主要 UI 改善的交付。原话和截图完整保存在 `phase2-user-checks/USER_FEEDBACK.jsonl` 及各单项记录，不得用局部 USER PASS 概括整体接受。

> 我不能接受每次验收就测试这么点东西
>
> 我的人工验收必须是我核心关切的地方。
>
> 我可以允许出bug，但是我要求的东西没有达成就让我验收是不允许的
>
> 不是我核心关关切的地方，可以灯实际使用的时候再修，但是我关切的地方没实现就让我测试，完全是在浪费手机
>
> 我要求的主要UI改善根本不是这些边边角角的东西，而事到如今R8的close依然遥遥无期。测了半天都是一些我觉得可以pending的东西

上文保留用户原文及笔误。交付门槛是：**先实现用户的核心要求，再让用户验收；非核心存疑问题可登记 pending。工程检查和边界覆盖应由工程侧完成，不能反复占用用户时间，或替代完整功能交付。**

### 下一轮任务的最新用户指令

> 要求下一轮任务直接推进到P11收尾。不完成不可以结束。存疑部分等我对p11统一验收再修改。已经失败过不可靠的方法不可以用于验收测试，测不到不可以当做失败，先推进到preview版本gate

下一轮按用户的直接安排连续实施，目标是 P11 收尾及 Preview Gate，不以等待 Project 审核为门禁，避免再拆成一轮一个微小边界修补、每轮都要求人工点击的小任务。用户验收集中在 P11，重点是已实现的核心使用链路。不能验证的项目使用 NOT TESTED / INCONCLUSIVE，不能仅因工具测不到就判定产品失败；明确观察到的产品失败和历史 RED 仍保留。已证明不可靠的方法不得再次作为验收依据。

该指令是下一轮任务的交付与验收要求，不表示本轮已完成 P11，也不自动通过数据安全、兼容性、Preview 或发布门禁。本轮不修改 CURRENT、parity、生产源码及历史结论。

## 本轮核心不符合项

1. **所有录音必须排除在聊天截图之外。** 不只是默认不勾选：录音条、播放按钮、音色名称、时长及录音操作均不得进入截图。当前选择列表仍能选录音，预览已出现录音条，记 USER FAIL。手工取消录音后获得正确文字截图不代表此问题已修复。
2. **PNG 预览本身必须独立弹窗。** 当前只有整个截图工作区是独立 Dialog，预览仍嵌在右侧，用户明确不能接受。用户要求复用已有图片预览，补长图纵向滚动，保留放大后的横向访问、缩放，以及“适应宽度”“原比例 / 100%”按钮。当前布局记 USER FAIL。
3. **回复必须有文字，录音只能附属于文字。** 可以有文字无录音，不可以有录音无文字。人为准备的 `shot-a-voice-only` 违反此规则，该用例 INVALID / BLOCKED，不再作为应支持的功能。没有证据说明生产流程自行产生过这种回复；不得把无效合成数据当作生产领域缺陷已经复现。

此前 R2-R1 合同要求支持“纯录音回复截图”，与本次用户明确规则冲突。请修订下一轮合同，保留原工程 RED/GREEN 和原材料，不删除或改写。PDF、Word、Seek 等原先单列的后续功能不能被这次反馈自动写成已实现。

主要 UI 主线还包括此前用户提出的三行菜单首行等宽居中及窄窗边界、User 工具栏仅复制/编辑并右对齐、语音 Deck 左状态/中固定播放器/右音色且同一行、录音操作改为右侧省略号/右键浮窗。请结合已有 `USER_FEEDBACK_VERBATIM.md` 和 145 合同核对状态、按完整使用链路组织下一轮交付。本轮没有重新验收或实现这些其他 UI 项，不能冒充全部已完成。

## 身份与范围

- 起始、本轮 GUI 期间及提交前 HEAD：`7cc135b971e0dd509f2f6f387b0ff7cfe2828f9f`，远程 feature 同值。
- 分支：`feature/phase8-fish-audio`。
- 冻结生产：`c38bcf274fca2eafb81a27b10cb3fcb16ed94fbe`；正式 upstream 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8` 未变。
- 包：`app/desktopApp/build/post-p8-screenshot-r2-boundary-20261021/windows-frozen-c38bcf27/runtime-image`。
- Manifest SHA-256：`51f91f5bcd1f9b943467f3e6ecddcebbac1e46807a8cba7ab327f8829743fa7d`。启动前和收尾 **212/212** 路径、大小、SHA-256 一致，无缺失、多余或不符；没有重建或覆盖。
- 独立根：`app/desktopApp/build/post-p8-screenshot-windows-manual-20261021/environments/post-p8-profile/ChatChatBarDesktop`。
- fixture：`screenshot-r2-d2a4a6e4-05e4-4f1f-b0e6-8d26fc6ce35a`；3 会话、14 消息、5 条合成正式音频。
- GUI 启动一次，无重启、自动重试、强杀或真实迁移；真实 API 0，fake provider 请求事件 0。
- 四份用户文件仍 KEEP / UNREAD / UNHASHED / UNMODIFIED / UNSTAGED，仅查路径状态。旧包、旧根、历史 RED/BLOCKED 和原始反馈保留。

## 人工与技术结果分开

完整矩阵：`phase2-final/manual-matrix.json`；逐次原话、附件及解释边界：`phase2-user-checks/`。

| 检查 | 用户结果 | 技术证据及边界 |
|---|---|---|
| 内部关闭、返回聊天、Esc、子窗口 X | 四项 USER PASS | 每次主 HWND 30607992 存活、Launcher/JVM 存活，无 Main CLOSE_REQUESTED；独立根锁每次 exit75。截图 Dialog 的 owner 为主 HWND。 |
| 分组、批量选择、折叠及选择保持 | USER PASS | 用户反馈；异常历史、单条折叠后来由用户统一报告通过，不伪造自动操作。 |
| 窗口调整、焦点、窄窗、键盘 | USER PASS | 用户统一报告；不推断未报告的具体最小尺寸、缩放位置误差。 |
| 双向滚动、Ctrl+滚轮、适应宽度、100%、800% | USER PASS | 用户统一报告。适应窗口附件显示4%；低于1%及0%标签没有实际确认。 |
| 文字片段预览 | USER PASS | 1项文字/0录音，文字、头像、背景正确；手动排除录音不修复核心问题1。 |
| PNG 取消后保留、重试、新文件导出和查看器 | USER PASS | segment PNG 640×126，完整 PNG 解码和 CRC 校验。 |
| 已有合成文件拒绝覆盖 | USER PASS | 用户确认 Windows 替换后，CCB 拒绝；sentinel 原始大小和 SHA-256 不变。 |
| 长 PNG 实际导出及查看器 | 后来 USER PASS | 最初“不想测”记录保留；随后用户自行补测并提交文件。实际640×10924，完整解码成功。 |
| 图片及跨消息选择 | 附件观察，不补签 USER PASS | 附件显示 User 图片、User文字及助手旁白“柔和的灯光”，顺序与勾选一致；并非指引中的 Alice 文字，没有单独明确签署。用户已要求结束，不再补测。 |
| 所有录音排除 | USER FAIL | 已观察录音进入预览。 |
| 预览本身独立窗口 | USER FAIL | 当前右侧内嵌设计；用户明确拒绝。 |
| 最终主窗口正常关闭 | 用户反馈“关了” | JVM exit0有独立证据；Launcher消失，但退出码未留存，见下节。 |

实际导出 PNG（准备阶段离屏图片不算人工导出）：

- `segment.png`，640×126，6694 bytes，SHA-256 `155670b1bfdf08b5fbf016c9d369ce78a4c6f13bf71dee0ea1bd7bd025b9d2a9`。
- `ChatBar_截图验收_C_·_长图边界_20261021_035815.png`，640×10924，2421530 bytes，SHA-256 `e4364344dacd0beb0694e5348fd937223156cdd0258ebeadb1ad735bb515edbb`。
- 合成 `keep-existing.png` 保留3567 bytes，SHA-256 `a4c0c504342ece37158ce5ef137f32db579b51eec241d8a6b4bc8e93f7fb85c9`。

## 退出监测异常与安全收尾

**不能报告 Launcher/JVM 0/0。** `runtime-watch.ps1` 的 Get-CimInstance 在 `2026-10-21 03:46:29 JST` 出现系统“内存资源不足”错误，外部 watcher 退出，之后的流重定向及 Launcher 查询句柄未保留，`process-result.json` 没有生成。原始 stderr 原样保留，技术项为 INCONCLUSIVE，不把工具失败写成产品关闭失败或产品 OOM。

失效 watcher 方案不得直接复用于下一轮验收依据。原始脚本、BLOCKED 状态均不改写。需要工程侧采用已验证、不会因一次枚举错误丢掉退出码/关闭日志的观察方式，并先证明可靠性；不要再让用户为诊断脚本反复点击。

补充证据与边界：

- 独立原生观察器保留真实 JVM 句柄，记录 **JVM22576 exit0**，UTC19:03:01.7027157，即 JST2026-10-21 04:03:01.7027157。Launcher38024后续查询不存在；其退出码 UNKNOWN。
- 主窗口最终 CLOSE_REQUESTED trace 在 watcher 失效后不可用；不能以旧 stderr 未出现新行来声称正常关闭链日志完整。早于故障的四种截图关闭证据仍有效。
- 故障后部分状态查询读取的是最后一条旧 process snapshot，时间戳18:46:24Z。它们不得作为当时实时进程证据；独立资源 sampler 及窗口 observer 继续采样，最终 CIM 重新核实全部已知进程消失。
- 独立真实 root ownership helper 在退出后 **exit0**，确认根锁可重新取得并释放；没有删除 `.ccb-desktop.lock`。
- 5条正式 MP3、对应所有权 receipt、图片和角色卡与初始媒体清单一致。5条 MP3 均可 FileShare.None 独占读取，未留占用。导出文件保留。
- supervisor 因 watcher 故障进入保留 fake 的防护分支。主应用退出及根锁重获后，验证自有父子进程、supervisor 创建的唯一 stdin 管道，再用原 readLine 协议发送换行，fake 正常排水 **exit0**，没有 Kill。
- 管道只读端归属核验曾因同步读而等待；发送正常退出输入后返回。原诊断和恢复记录均保留，不隐去中间状态，不重复启动服务。
- fake 自己报告 serverDrained/privateKeyDeleted/trustStoreDeleted。仅在所有进程退出、独占打开确认后，按精确路径清理2个本轮 JNA 解包临时文件；没有递归删除或触及正式音频。无 `.part` 残留。
- 最终 CCB、fake、supervisor、watcher 和两个被动 observer 均无残留；根锁已释放。`phase2-final/raw/FINAL_TECHNICAL_SUMMARY.json` 及各原始文件支持上述结论。

以前的资源保守 BLOCKED 报告保留，用户调整资源策略的授权和实际成功启动也保留。此实际 watcher 错误不证明任何先前人为内存阈值就是最低要求。

## 连续推进说明（不作为 Project 审核门禁）

请把本次用户意见放在下一轮任务的主目标和人工验收入口条件中，而不是只添加到尾部 pending 清单。核心 UI 未达成时，不要再次安排用户长时间验证边界项目。

已有独立图片预览复用入口经只读核实：`DesktopImageViewer.kt` 的 `DesktopImageViewer`、`DesktopTransientImagePreview`，使用 `DesktopImageToolWindow` / `DesktopImageZoomSurface`，聊天图片、背景和 Studio 已有调用。应优先复用并补长图滚动与要求的按钮，同时保留截图自己的消息版本、资源、尺寸内存预算及无覆盖导出约束；不能直接换用另一套导出而丢掉这些保证。

保留所有历史 RED、SKIPPED、INCONCLUSIVE、Studio credential error OPEN、QQ native BLOCKED、WIN-AUTO-001/WIN-BRAND-001 与 bounded 非绿色记录。历史截图 Close 根因仍 UNKNOWN，本轮四种关闭通过不反推根因已找到。

**本轮完成证据提交和报告，直接交用户。按用户最新指令，后续不等待 Project 审核；由 Codex 按用户直接安排连续推进 P11 收尾和 Preview Gate，自查、修复，再汇报。非严重问题不得作为反复停下或追加零散用户验收的理由。本报告不冒充 P11 实现已经完成。**

## 用户最新覆盖指令：取消 Project 审核停止点

> 完成报告后我会先安排你直接推进，不需要project审核。把谴责给project让它去反思自己的问题。然后你推进并且完成然后自己检查p11的完工没问题之后再回报我。有问题自己修改到通过为止。没有严重问题不可以停下来。

这是对先前“交 Project 独立复审后再继续”的明确覆盖。报告后依用户直接安排推进，不要求 Project 再次下发权限。执行侧应连续实现、工程自查和定位修复，非严重存疑项登记至集中验收，不能用新一轮微小测试作为结束。严重数据安全风险和确实无法继续的阻塞如实上报；不能把测不到写为失败或通过。

**转交 Project 的严厉批评：**当前任务拆分和优先级偏离了用户的主要 UI 要求，投入大量人工验收时间却只验证少量边界与局部功能；核心体验未实现便反复要求用户验收，消耗用户时间、拖延整体收尾。请反思并纠正以零散工程 GREEN 代替产品交付、把用户当作日常边界测试执行者的安排。后续交付以核心使用链路及 P11 完工为目标，次要问题允许 pending。
