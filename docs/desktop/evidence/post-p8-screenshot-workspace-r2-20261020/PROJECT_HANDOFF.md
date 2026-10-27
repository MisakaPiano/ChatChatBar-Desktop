# Post-P8 Screenshot Workspace R2 — 限定工程交接

**工作区实现：ENGINEERING PASS / WINDOWS USER ACCEPTANCE PENDING。历史截图 Close 异常：UNKNOWN。Post-P8 HOLD 保留。**

## 1. 检查点、范围与提交

- 初始 branch：`feature/phase8-fish-audio`；HEAD `22a8612b9076880ff6d2520068947cd23ea7c029`，与远程一致。上一轮生产 `60cc44d0159528d2e6824fcaf88ee384964da307` 到该 HEAD 无 app/skill 源码漂移。
- 远程 desktop 为 `5ef95428421f7c693b010ac88f51d470804ab07a`；本地同名旧引用仍为 `4c2af2576e2319e8041ffd2a6c5a205450f18246`，与上一轮已登记情况一致，未更新/checkout/合并。正式基线仍为 ChatBar1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。
- 新源码 checkpoint：`3ece1366b142210f2db7c61a9c479cd156534b69`，直接 parent 为 `22a8612b9076880ff6d2520068947cd23ea7c029`。本独立证据提交 SHA 由 Git 和最终回复给出，不写伪造的自引用。
- 10 文件 +751/-117：5 个 Desktop 生产 Kotlin、4 个直接相关测试、1 个 image-runtime skill；真实 diff 见 `raw/source.patch`，逐源文件测试时身份见 `raw/tested-source-identity.json`。
- 生产范围：`DesktopChatScreenshotHost.kt`、`DesktopChatScreenshotSelection.kt`、`DesktopChatScreenshotRenderer.kt`；新增 `DesktopScreenshotWindow.kt`、`DesktopScreenshotPreview.kt`。
- 未改 Main、DesktopWindowChrome、WindowsWindowChrome、平台文件选择服务、Fish/音频维护实现、官方 Prompt、Package、Entity/schema、sharedCore、Android、D-044、User工具栏、Deck、Seek、CURRENT、parity、baseline 或 P11。无发布、合并 desktop、真实迁移/恢复、实际用户数据/凭据读取。
- 四份 user-owned 文件仅核对原路径 untracked 状态，未读取、哈希、修改或暂存。原人工/可靠性证据、旧 R3 根和包均未覆盖或清理。

## 2. 实际窗口及关闭归属

本轮是明确授权的独立窗口 UX 实现，**不是用结构变化证明历史故障已找到根因**。

核对本机锁定依赖 Compose Desktop **1.10.3** 的实际 JAR/API（`raw/compose-public-swing-dialog-api.txt`、`compose-api-identity.json`）。当前公开 API 为 `androidx.compose.ui.awt.SwingDialog` 与 `ComposeDialog(java.awt.Window, ModalityType, GraphicsConfiguration)`；旧低层 DialogWindow 是其弃用别名。第一次编译同时发现 Kotlin 的 `LocalWindow` 是 internal，不能按 javap 表面 public 直接使用；此编译 RED 保留，最终没有使用 internal/reflection 或猜前台窗口。

真实源码调用链（前缀 `app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/`）：

1. `Main.kt:99` 原有 `LocalDesktopFishParentWindow provides window` 发布真正 WindowScope 的主 ComposeWindow；`DesktopScreenshotWindow.kt:25` 复用此既有精确身份，不修改其提供者，也不改变 Fish 行为。
2. `DesktopScreenshotWindow.kt:35–57` 使用公开 SwingDialog 创建明确 owner 的 ComposeDialog，MODELESS、可 resize、标准原生装饰。初始尺寸为主窗口约85%，最小可操作尺寸320×360并受屏幕工作区上限约束；位置/尺寸钳制到该 monitor 去除任务栏后的区域。未知/不可用 owner 显示明确失败，不创建无主窗口。
3. 子窗口 `windowClosing`（自身 X）和子窗口 Esc 仅调用传入的 workspace close callback；内部「关闭」「返回聊天」也同样进入 `DesktopChatScreenshotHost.kt:59` 的局部 `workspace=null;error=null`。
4. 退出工作区移除该 composition，取消其 renderer/export coroutine scope，经 SwingDialog 的 dispose 只销毁此 ComposeDialog；没有全局 requestClose、exitApplication、Main chrome command 或根锁接口传入。
5. 真正主窗口仍是未改的 `Main.kt:74–86`：记录 CLOSE_REQUESTED，经 root-switch close guard 决定 exitApplication，原 AppContainer/ownership 排水不变。窗口 Chrome 的原 Win32 WM_CLOSE/SC_CLOSE/非客户区命中链未修改。
6. PNG 保存仍走 `controller.imagePicker` 注入的原 WindowsNativeFilePicker / WindowsCommonItemDialog 服务，owner 仍由原 `DesktopDialogOwner` 取得 Main 的 top-level HWND。没有为截图另建 Swing chooser 或重绑全局 picker owner。

组件测试替换的只是 native shell，验证内部 Close、返回聊天、Esc 和 shell-close callback 关闭工作区后宿主仍可操作、没有调用独立主关闭 callback；然后独立主关闭 callback 仍可调用。**这不是实际 WM_CLOSE、HWND、Windows焦点/文件选择器/根锁或真实 Main-X 的运行证据。** 相邻 app lifecycle/root-switch/chrome contract 回归通过，但没有签署真实设备窗口安全 PASS。

### 历史 Close 异常：UNKNOWN

原用户明确点截图内部文字「关闭」，原包实际进入 Main CLOSE_REQUESTED、Launcher/JVM0/0。该用户失败记录和原始日志保留；此前没有 press/release 坐标、目标 HWND、WM_CLOSE来源、owner/focus 时序足以证明根因。旧 Popup/native chrome 候选边界仍只是候选。本轮没有修改 Main/Chrome、吞全局 WM_CLOSE、固定延迟或强制退出，也没有运行原生 GUI 来复现；不能宣称历史故障已被实机验证解决。

## 3. 对话树与选择语义

- `DesktopChatScreenshotSelection.kt:96` 只建立展示分组：按 repository 返回时间线原顺序，User 作为上下文，其后 Assistant 回复作为完整可选块；无前置 User 的内容有明确历史组，连续 User 各自保留，连续 Assistant/系统消息不重排、不伪造 source-turn。
- 回复头采用折叠行及整条勾选状态，标题为「完整回复 n」，不冒用 AI 第一句。User 上下文显示实际发言；图片上下文有数量提示。每条消息可独立展开/折叠。
- 展开集合与 block 勾选集合独立；折叠不清除选择。顶部全部展开、全部折叠、全选、全不选、反选均可用。
- 片段、整条、多选继续调用原 `roleplayScreenshotBlockIds`、toggle/clean/ordered helpers，没有第二套 block ID、消息版本或语音选择规则。未修改 shared/Android authority。
- 每条消息明确显示录音项数量，展开后录音项带「录音 · 角色名」标签；顶部单列已选录音数。整条选择包含哪些录音可见，不悄悄删除或增补已选内容。
- 历史15项由用户手动勾选仍为事实，不改写成自动误选。原 `USER_FEEDBACK_VERBATIM.md` 保留，未重写。

## 4. 预览、内存与导出

- 宽窗左树右预览；不足760dp切换「选择内容／长图预览」页签，保留功能和选择。顶部批量栏、预览触发和底部返回/导出/关闭固定；窄栏可水平到达。
- `DesktopScreenshotPreview.kt:23` 维护数值 viewport：双轴 scrollbar、普通滚轮、Shift横向滚动、Ctrl滚轮、加减、百分比、适应宽度、适应窗口、100%；方向键/PageUp/PageDown可滚动。
- 缩放1%–800%，保留鼠标锚点；按钮缩放和 resize 尽可能保留查看中心，并在边缘钳制。viewport 状态放在工作区层，不因窄宽布局或页签切换重置。
- 仅固定大小 Canvas 绘制原 PNG；缩放改变变换/数值滚动范围，不创建等比例放大的新 raster 或超大 Compose 布局。
- 保留 renderer 原宽320–1600、高最多32000、总像素800万、资源总编码32MiB等预算和 owned reader；预览再次验证 PNG 尺寸、声明与实际尺寸一致，并限制编码大小。超出拒绝，不静默缩小导出。
- 实际测试320×16000 PNG 在800%下仍渲染360×500 viewport，双轴滚动后保存的 PNG 原 bytes、320×16000尺寸不变。此为合成工程证据，不是用户图像或真人签署。
- renderer 对本次实际读取的资源路径/内容保留 SHA-256；`DesktopChatScreenshotRenderer.kt:102,124` 在预览后、保存前和选择器返回后复核消息/录音快照及资源内容。消息或资源在 chooser 期间变化时拒绝创建目标。
- 保留 owned 路径/大小校验、无覆盖目标、新建私有 `.part` 和仅清理自己未完成文件的规则。取消保存、失败及成功都有明确反馈；取消/失败保留预览，导出可重试；同名不会覆盖。
- 复核是操作边界的有限 reproof，并非跨外部任意文件写入的原子事务或全局不可变快照；没有扩大 root/schema/transaction 合同。
- PNG 是唯一实现的格式；PDF/Word未实施，也无虚假的已支持按钮。音频 Seek、整条语音重新生成与其他后续UX不在此源码范围。

## 5. 原始失败及最终回归

全部 raw stdout/stderr、命令、退出码、XML、阶段摘要保留在 `raw/`。源文件 diff-check通过。证据禁用换行转换；raw日志/patch中原有尾空格不改写，仅 raw 范围豁免空白格式检查，manifest 和 Git blob 逐字节验证。

| 阶段 | 结果 | 含义 |
|---|---|---|
| 01-workspace-red | 1 test / 1 FAIL | 未改生产的旧Popup缺少工作区批量操作 |
| 02-workspace-focused | compileKotlin RED / 0 executed tests | Kotlin internal LocalWindow 与receiver问题，原日志保留 |
| 03-workspace-focused | compileTestKotlin RED / 0 executed tests | 新测试缺少 DefaultThemeColorHsv import |
| 04-workspace-focused | 4 suites / 19 PASS | 工作区初版核心+离屏回归 |
| 05-final-focused | 12 suites / 120 tests / 119 PASS / 1 FAIL | 新滚轮夹具错误假定缩放后原点，未计入真实保存/窗口缺陷 |
| 06-final-focused | 12 suites / 121 PASS | 显式设置滚轮夹具原点，新增长PNG/高倍率验证 |
| 07-final-focused | **12 suites / 121 PASS / 0 FAIL / 0 ERROR / 0 SKIPPED** | 最终源；加短窗/保存失败反馈可达性及最小窗口尺寸 |
| 08-package | BUILD SUCCESSFUL / exit0 | 新路径 createDistributable，不运行程序 |

02/03早期收集脚本复制了上轮仍留在 test-results 的01 XML。它们作为原收集结果保留，但 `summary.json` 明确列入 `inheritedXmlExcluded`，**不算这两轮执行的测试或新的重复失败**。后续脚本只收集阶段开始后写出的XML。统计不跨阶段相加。

滚轮测试修正只设置已知初始滚动位置：zoom原本按合同保留查看中心，故初始 y 非0。保留原80px增量、锚点和倍率断言；没有放宽安全判断。05失败XML和失败时测试源码均保留。

最终12类：WorkspaceTest、WorkspaceUiTest、ChatScreenshotTest、ChatScreenshotUiTest、WindowChromeContractTest、ApplicationLifecycleTest、DataRootSwitchControllerTest、AudioMaintenanceTest、VoiceClickReliabilityTest、VoiceUxTest、VoicePauseWindowsTest、DataOperationCoordinatorTest（均 Desktop 前缀）。精确类名/每suite统计见 `raw/07-final-focused/command.json` 和 `summary.json`。

截图类共22测试；其余为关闭/数据协调/音频相邻回归。包含R1原11音频维护测试及7同录音点击测试；未改这些生产实现。MCI单测使用合成静音临时音频，不等于用户听觉PASS。没有 full bounded、Robot、Android全套、原生新工作区或真实数据迁移/恢复验收。

所有Gradle调用使用JDK17、offline、no-daemon、single worker、no-parallel、low priority、in-process Kotlin和既有内存设置。未并行构建或结束用户进程。编译中可用内存较低及page activity原始记录保留；无OOM/崩溃。最终可用物理内存2060392KiB，H盘8350953472bytes，未发现java/javaw/CCB应用进程。没有启动手工fake或GUI数据根；仅单测内合成fixture/mock。真实 LLM/NovelAI/Fish 请求均0。

## 6. 新包身份及旧包保全

- 源：`3ece1366b142210f2db7c61a9c479cd156534b69`。
- 独立构建目录：`H:\ChatChatBar-Desktop\app\desktopApp\build\post-p8-screenshot-workspace-r2-20261020\package-3ece1366-build`，由gitignored init脚本指定，未改产品Gradle。
- 冻结根：`H:\ChatChatBar-Desktop\app\desktopApp\build\post-p8-screenshot-workspace-r2-20261020\windows-frozen-3ece1366\runtime-image`。
- **212/212 文件**：构建输出→新副本→再次哈希一致，无缺失/多余；清单为 `package/distribution-manifest.json`。
- manifest SHA-256：`822797ac24fa6211bf4c764c82c29d746c9e8e22730888d768877e9708cc9af7`。
- 旧60cc44d0及694a8bdd包各212文件重新核验不变；两旧manifest分别仍为 `f912b60179d7b31debafb363b221175210b597aac84a97beb3d79f11f824c10f`、`d98691a663275c99aeee2318514db1e528e28c74a71349c232e235cc7f076ed1`。
- 旧22d0433/R3包、R3根、one-use guard及所有旧RED证据未写入或清理。旧截图renderer单测输出已改到本轮独立目录，避免覆盖旧合成预览。
- **新包NOT LAUNCHED**。无GUI种子、启动guard消耗、手工fake或用户视觉/听觉签署。raw中的预览仅是合成offscreen工程图。

## 7. Project复审后仍需的 Windows 人工清单

以下全部 **NOT TESTED / USER CONFIRMATION REQUIRED**；不能由本工程结果签USER PASS：

1. 在新隔离根及新one-use guard下，观察独立子窗口owner、标题栏、前后台焦点、初始屏内尺寸、宽/窄/短窗resize和最小尺寸行为。
2. 分别用内部关闭、返回聊天、Esc、子窗口自身X结束工作区；每次主程序仍可操作、根锁仍持有且不触发Main CLOSE_REQUESTED；正常主窗口X仍完整排水、Launcher/JVM真实退出、锁释放、无重复关闭或残留JVM。原生事件/owner时序需真实证据。
3. User上下文、无前置User及连续User/Assistant历史、整条/片段/多选、录音勾选、折叠不丢选择及所有批量操作；检查键盘Tab/Enter/Space与窄栏可达。
4. 长PNG双轴滚动、Ctrl滚轮、倍率按钮、fit/100%、缩放锚点和resize位置；浅/深主题、宽/窄窗口、头像/背景/现有图片/录音显示与原顺序。
5. 原Windows保存选择器前台与owner行为；取消、同名失败、换新名重试、清晰成功提示；实际PNG用Windows查看器打开，核对原像素、内容、未覆盖文件及无遗留`.part`。
6. 新包重启持久化或其他API/生成验收不由本工程轮自动执行；须按Project后续限定合同与用户许可安排。

保留原manual HOLD、历史RED/SKIPPED/INCONCLUSIVE、Studio credential error OPEN、QQ native sending BLOCKED、WIN-AUTO-001/WIN-BRAND-001及完整bounded非绿记录。没有P8闭环、完整parity、Public Preview、发布或自动进入集中GUI Gate的结论。

仅提交本轮新增独立证据并推送原feature后STOP，交当前ChatGPT Project独立复审。
