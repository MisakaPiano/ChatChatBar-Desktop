# Post-P8 Manual HOLD / Reliability R1 — Project 独立复审交接

日期：2026-10-20 JST。结论：**语音重复点击修复已完成工程验证；截图关闭问题 UNKNOWN，停止该项生产修复；整体 P8 HOLD 不变。新包仅构建冻结，未启动。**

## 1. 检查点与实际范围

- 初始 feature HEAD：`c4098e08bec84372911a5a8bc38c2cfa72f98978`，parent `67e8b63cd43842866a9fd30d4bc9a1ce45263a03`；branch `feature/phase8-fish-audio`。
- 初始冻结生产源码：`694a8bdd6e48bd6696d2eeb59f141935c67163b0`。原人工记录和用户原话不修改：`../post-p8-windows-manual-20261020/handoff/PROJECT_HANDOFF.md`、`USER_FEEDBACK_VERBATIM.md` 及原始矩阵/日志。
- 本轮生产/测试/skill checkpoint：`60cc44d0159528d2e6824fcaf88ee384964da307`，直接 parent 为 `c4098e08bec84372911a5a8bc38c2cfa72f98978`。此证据提交 SHA 由最终 Git 记录给出，不伪造自身 SHA。
- 远程 `refs/heads/desktop` 为合同所列 `5ef95428421f7c693b010ac88f51d470804ab07a`；本地同名分支仍为 `4c2af2576e2319e8041ffd2a6c5a205450f18246`（reflog最后更新2026-10-12）。本轮未更新、checkout或合并任一desktop引用。正式上游 ChatBar 1.4.1 为 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。最终只读核验见 `ref-verification.json`；此句更正首份新证据 `9ef7578a801f0d0ed4b2766d9f2f5c4c179164f5` 中未区分local/remote的表述，不更改原始记录或源码。
- 实际修改：3 个 Desktop Kotlin 生产文件、2 个 Desktop 测试文件、1 个相关 skill，合计 6 文件 +248/-11；精确 diff 见 `raw/source.patch`。未改 Main、WindowChrome 或截图生产代码。
- 不修改 Prompt、Package、Entity/schema、sharedCore、Android、D-044、CURRENT、FEATURE_PARITY、baseline、P11 或已接受音频仓库协议；没有迁移、恢复、真实数据清理、真实凭据读取、付费 API 请求、合并 desktop 或发布。
- 四份 user-owned 文件仍仅保持原四个 untracked 路径，不读取、不哈希、不暂存。`raw/preflight.json` 的 `protectedPathsOnly` 还包含当时已新建的本轮测试源文件；该字段是原始状态快照，不能据此将测试源文件误称为第五份受保护用户文件。单独明确的四路径状态见 `raw/protection-final.json`。

## 2. 故障一：截图内部「关闭」后全应用退出

**分类：UNKNOWN / NO PRODUCTION REPAIR。历史 USER FAIL 保留。**

用户明确点击截图界面文字「关闭」，不是主窗口 X；原始包日志确实进入 `CLOSE_REQUESTED`，完整排水并 Launcher/JVM 0/0。不能改写为正常退出 PASS，也没有证据说用户误点。

当前真实源码链（路径前缀均为 `app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/`）：

1. `DesktopChatScreenshotHost.kt:26–66`：截图使用 Compose Popup；onDismiss、Escape 和第66行内部 `StudioAction("关闭")` 只清除 `messages/png/error`。内部 Close 没有取得全局 close/exit 回调。
2. `Main.kt:74–86`：本体 Compose Window 持有 `requestClose`，记录 `CLOSE_REQUESTED`，经 `rootSwitchController.requestWindowClose()` 决定 `exitApplication()`；真正 Window 的 `onCloseRequest` 和自定义 chrome 共用此路径。
3. `DesktopWindowChrome.kt:103–105,119`：CLOSE command 调用应用 closeRequest；native close 通过 EDT 投递。
4. `WindowsWindowChrome.kt:178–180,209,269`：拥有的 HWND 的 WM_CLOSE/SC_CLOSE、自定义标题栏关闭命中等可进入 requestNativeClose；另有 heavyweight child 非客户区命中转接。Popup 内容/焦点、AWT Window、拥有者 HWND 与自定义 chrome 是不同事件边界。
5. 文件选择器只在保存 PNG 时进入；原人工没有成功 PNG 导出。没有原始 press/release 坐标、HWND、WM_CLOSE 来源或 owner/focus 序列足以把上述两条路径关联到用户那次物理点击。

结论：源码不能证明历史 native 事件由何处产生。Popup 尺寸、标题栏 hit-test、焦点/owner 都仅是候选边界。不能把改 Dialog 当作已证明根因，不能拦截所有 app close、固定延迟、吞关闭事件。本轮依合同停止此项生产修复。

新增 `DesktopChatScreenshotUiTest.kt:14` 的 offscreen 测试只验证语义内部 Close 后截图状态消失、宿主控件仍可用、独立模拟 close callback 未被调用；它没有真实 Main Window/native hook 或根锁，不能证明 Windows 生命周期安全。已有 application lifecycle/root-switch/chrome contract tests 通过只代表对应工程回归。真实 Windows 内部 Close、Escape/外部关闭、保持主锁、真正 X 排水/释放和无残留 JVM 的联合矩阵仍 **NOT TESTED / BLOCKED pending Project review**。新包没有自动运行。

## 3. 故障二：语音条反复点击重播／卡住

**重播路径：CONFIRMED，已修复。用户历史“卡住”的确切 native 根因：UNKNOWN，未宣称复现历史死锁。**

原链：`DesktopVoiceRows → DesktopChatVoiceRuntime.play → coordinator.playFrom → playback.playSequence → stopLocked/append/startLocked`。无条件重新建立队列可导致同一正在播放录音从零开始；初始新增回归在未修改生产代码时稳定 RED：same active voice 应暂停但未暂停。

最终链与边界：

- `DesktopChatVoicePanel.kt:37–54`：读取现有 playback StateFlow；同录音正在播放显示「暂停」，已暂停显示「继续播放」，其余显示「播放」。仍调用原 runtime.play，不维护第二套状态。
- `DesktopChatVoiceRuntime.kt:122`：既有当前 session/message version/speaker/voice 验证不变；没有新增网络调用。
- `DesktopFishGenerationCoordinator.kt:429–453`：既有自动队列互斥、手动优先权、foreground generation 与 admission 检查保留，只给手动 `playSequence` 传 `toggleCurrent = true`。自动 enqueue 不使用 toggle。
- `DesktopVoicePlayback.kt:115–132`：在原 playback mutex 中，先验证 eligibility、maintenance admission、closed。相同当前 ID 且 job active 时，playing→pauseLocked，paused→resumeLocked；native opening 尚未发布 playing 时合并点击，不 cancel/reopen。不同录音仍先原 stop/join/drain，再建队列。
- `DesktopVoicePlayback.kt:206–226`：原 pause/resume 代码抽为同一锁下 helper，真实 output.pause 返回位置；resume 调原 alias，不用新 play 冒充续播。

没有新增维护锁或修改锁顺序：既有 coordinator autoplay mutex 在进入 playback mutex 之前释放；playback mutex 内沿用 focus/native 操作。R1 的维护预约 epoch、拒绝迟到 play/resume/autoplay、native close 确认、失败保留原 normal lease、自动备份 busy/deferred 与排水重试均未改写。pause 仍保留原 alias、句柄和 normal operation；显式 Stop/维护 drain 仍须真实关闭成功才释放登记。未新增全局普通操作锁或第二套播放服务。

取消/失败/恢复：原停止路径照常取消并 join 队列；close 失败保持 fail-closed，不由 toggle 抹掉失败或假释放 lease。新回归验证独占在 close 失败后仍等待，显式恢复 stop 成功后才进入。版本变更、离开会话、维护预约后的旧 intent 不能重新打开 alias。未改变二次 Stop、跨 User 既有语音连播或缺失音频不自动生成规则。Seek 未实现。

## 4. 原始 RED → GREEN，统计不相加

所有阶段原始 Gradle stdout/stderr、命令、退出码、XML、测试源/patch 均在 `raw/`；没有覆盖初始失败。

证据目录禁用文本换行转换，逐文件比较工作区与 Git blob 字节；raw日志自带的尾空格及patch上下文空格原样保留，仅raw范围豁免空白格式检查。非raw文档按CRLF-aware检查，生产源码独立普通diff-check通过。

| 阶段 | 实际结果 | 说明 |
|---|---|---|
| 01-initial-voice-red | 1 test / 1 FAIL | 未改生产，证实同一 active 录音点击未暂停 |
| 02-click-and-screenshot-focused | 9 PASS / 0 FAIL | 7 新语音 + 2 截图 offscreen |
| 03-affected-regression | 186 tests / 185 PASS / 1 FAIL | 新 fixture 假设 native opens=2，实际3；原始 XML 保留 |
| 04-fixed-fixture-focused | 7 PASS / 0 FAIL | 只修夹具播种时的合法 autoplay 干扰，不改生产或安全断言 |
| 05-final-affected-regression | **19 suites / 186 PASS / 0 FAIL / 0 ERROR / 0 SKIPPED** | 同19类最终重跑；包含原11项维护安全测试 |

阶段03原因：为构造第二条录音时会话已 foreground，新生成录音按既有策略合法自动播放，污染“两次显式手动点击”的打开次数。修夹具时先 foreground(null).join，准备额外录音后恢复 foreground，新增 `native.opens==0` 前置断言；仍严格断言后续两次点击只打开两次，没有放宽 expected、timeout、安全规则或隐藏失败。

新7语音回归使用真实 `DesktopDataOperationCoordinator` 和 `DesktopAudioFocus`、模拟 native output，Job/Channel/Deferred/flow 确定同步，不以 sleep 证明无竞态：

1. 同录音 active→pause→resume 保持4321ms及同alias，网络请求不增。
2. 40次快速点击20暂停/20续播，仅1open；stop后真实独占可取得。
3. signal控制 native opening期间20次点击合并，只有1open。
4. 暂停后消息版本变更排水，stale click不能重新打开。
5. 换录音先释放旧alias，离开会话后延迟intent拒绝，网络不增。
6. maintenance reservation 拒绝同录音resume和autoplay，无额外open。
7. native close失败保留normal登记，真实exclusive等待，显式stop恢复后准入。

范围：Voice UX/safety/persistence/Windows pause/runtime/coordinator；数据协调、snapshot、backup scheduler/runtime、migration/root-switch、app lifecycle；截图UI/core与chrome source contract。完整类名和每suite统计见最终 `command.json`/`summary.json`。Windows pause测试真实调用MCI和合成静音临时文件，**不替代用户听觉和新包GUI验收**。未运行 full bounded、Robot、Android全套、真实迁移/恢复或Windows新窗口。

JDK17，offline、no-daemon、single worker、no-parallel、low priority、in-process Kotlin，保留既有资源限制。compileKotlin/compileTestKotlin通过，源码 diff-check通过。打包 createDistributable通过（46s），无需再次测试未改的源码。最终资源/进程快照 `raw/resources-final.json`：可用物理内存2042052KiB、H盘可用9153712128bytes，未见java/javaw/CCB应用进程；未结束用户其他进程、调整缓存或pagefile。

新7夹具只删除自身验证前缀的临时合成目录，未清扫其他目录；旧测试fixture按既有行为保留其小型临时测试数据。没有本轮GUI根、fake进程、一次性GUI启动guard或用户数据操作。JUnit使用本机mock server；其全suite总请求数不臆造；新增点击测试以基线断言播放增加请求为0。真实 Fish/NovelAI/LLM 均0。

## 5. 新 Windows 包身份（NOT LAUNCHED）

- 源码：`60cc44d0159528d2e6824fcaf88ee384964da307`。
- 新独立构建路径：`H:\ChatChatBar-Desktop\app\desktopApp\build\post-p8-manual-reliability-r1-20261020\package-60cc44d0-build`；只由独立 Gradle init 指定，未改产品Gradle配置。
- 冻结：`H:\ChatChatBar-Desktop\app\desktopApp\build\post-p8-manual-reliability-r1-20261020\windows-frozen-60cc44d0\runtime-image`。
- manifest SHA-256：`f912b60179d7b31debafb363b221175210b597aac84a97beb3d79f11f824c10f`。原始 manifest 见 `package/distribution-manifest.json`。
- **212/212** 源输出→复制→再哈希一致，无多余/缺失；新包未启动、未播种、无人工签署。
- 旧 `windows-frozen-694a8bdd` manifest SHA仍为 `d98691a663275c99aeee2318514db1e528e28c74a71349c232e235cc7f076ed1`，212个文件逐项复核不变。旧22d0433、R3数据/包和历史RED未写入、未覆盖或删除。

## 6. 用户意见和下一阶段范围保全

用户完整原话继续以原 `../post-p8-windows-manual-20261020/handoff/USER_FEEDBACK_VERBATIM.md` 为准，未重写。既有15项截图选择是用户手动选择，不是自动误选。历史 C1一度确认目标正确，与其后C2对录音条、选择/预览/保存UI及关闭行为的否定反馈同时保留，不能用早先PASS覆盖后续FAIL。

后续明确要求（本轮不实施）：

- User工具栏右对齐、只有复制/编辑；头像问题保留用户对「你」fallback的澄清，不武断认定漏头像配置。
- 三行图文菜单；第一行两操作等宽居中；窄窗不得越出主窗口；右键与…同入口。
- Deck左生成状态与生成停止、中固定播放器、右角色音色，严格同一行且同样式不覆盖；左状态可省略，右名称优先完整，不足才省略到极限「旁白」，下拉项按最长宽度统一；收起文本只显示角色名。
- 语音条只保留播放/时长/动态播放标识，右侧…与右键共用操作浮窗，取消行内冗长展开；拖动Seek独立合同，本轮只修同录音暂停续播。
- 整条语音“重新生成”、历史录音保留/替换规则另行冻结，不借本轮改写。
- 截图按User发言关联完整Assistant块分组，不以AI第一句为标题；每块独立展开/折叠，顶部全部展开/折叠、全选/全不选/反选，明确音频条选择。
- 新增独立截图窗口合同完整见 `raw/deferred-screenshot-window-contract.md`：明确父窗口、可resize、默认80–90%且屏幕内、左树右预览、双轴滚动和Ctrl滚轮缩放/fit/100%、尽量保持查看位置、上下固定操作栏、关闭不退app、不释放root lock、PNG身份/预算/安全保存不变。PDF/Word后续格式，仅允许预留，不假称支持。

## 7. 复审结论与尚需实机项目

本轮只移除一项已证实的重播机制；不能宣称两个严重阻塞均关闭，更不关闭 P8 HOLD。源码语音修复可交Project工程复审；截图退出阻塞保留UNKNOWN。若下一轮要定位真实截图Close，需要Project批准窄化的native事件/owner/focus证据合同，不能由offscreen结果直接宣布安全进入完整Gate。

Project复审后，新的隔离根和one-use guard下，由用户实际确认：同录音暂停/原位续播、快速点击、换音频、切会话/版本、真实主窗口X关闭；截图内部Close/Escape/外部关闭仍主程序存活并持锁、真正X仍完整排水释放锁、无残留JVM。没有授权前不启动新包，不自行签USER PASS。

保留历史 RED / SKIPPED / INCONCLUSIVE、完整bounded非绿、Studio credential error OPEN、QQ native sending BLOCKED、WIN-AUTO-001/WIN-BRAND-001。D-044仅登记未实施。原R1真实自动备份/迁移并发设备矩阵仍未签署。没有发布、Public Preview、完整parity或P8闭环许可。

完成独立证据提交并普通推送原feature后STOP，交当前ChatGPT Project独立复审。
