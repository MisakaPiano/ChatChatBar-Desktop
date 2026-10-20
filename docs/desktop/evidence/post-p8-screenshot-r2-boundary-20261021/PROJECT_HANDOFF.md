# Post-P8 Screenshot Workspace R2 — 补充边界检查

**两个边界均 CONFIRMED；最小修复后 ENGINEERING PASS。R2 Windows 人工验收仍待进行，Post-P8 HOLD 不变。**

## 检查点与实际范围

- 初始分支 `feature/phase8-fish-audio`，HEAD `ba7b58c829fb583c0e22c100f5de8a3799406c20`，parent `3ece1366b142210f2db7c61a9c479cd156534b69`；初始 tracked/index clean。提交前远程 feature 仍为 ba7b58c，desktop 为 `5ef95428421f7c693b010ac88f51d470804ab07a`。正式 upstream 仍为 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。
- 新生产/测试提交 `c38bcf274fca2eafb81a27b10cb3fcb16ed94fbe`，直接 parent 为 ba7b58c；7 文件 +93/-12。证据提交 SHA 由最终 Git 记录给出，不写自引用。
- 生产仅改 3 个 Desktop 截图文件；新增 1 个截图边界测试文件；另 2 个现有截图测试只改合成 PNG 输出目录，避免覆盖旧 R2 证据；1 个相关 skill 同步这两个稳定边界事实。
- 真实 diff 在 `raw/source.patch`；测试时 worktree 与 Git blob 身份在 `raw/tested-source-identity.json`。四份用户文件只核对路径 untracked 状态，没有读取、哈希、修改或暂存。
- 未改 Main、Window Chrome、renderer、官方 Prompt、Package/Entity/schema、sharedCore、音频维护、其他 UI、CURRENT、parity 或 upstream。没有重做 R2、真实 API、GUI/fake 启动、真实数据迁移/恢复、合并 desktop 或发布。

## 边界一：只有录音的回复 — CONFIRMED

初始 RED 中，空文字、无图片的 Assistant 消息具有显式关联录音；官方 block helper 能生成 `voice-only::voice::v`，分组仍保留消息，toggle/clean 保留勾选，但 `orderedChatScreenshotMessages` 返回空列表。原始 XML：`raw/red-boundaries/TEST-com.example.chatbar.desktop.DesktopScreenshotBoundaryTest.xml`。

根因边界：选择树已有捕获录音 ID，**不是树隐藏了该回复**；预览前的二次筛选调用 `isSelectableForChatScreenshot` 时没有传录音 ID，因此无文字/图片的回复被丢弃。Host 再把空列表交给原 renderer，无法正常生成该选择的预览/导出。

最小修复调用链（前缀 `app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/`）：

1. `DesktopChatScreenshotHost.kt:94` 把已捕获 `snapshot.voices` 中的 ID 传给预览筛选。
2. `DesktopChatScreenshotSelection.kt:67` 的 ordered helper 接收可选 `voiceIdsByMessage`，并在 `:12` 的 selectable helper 中交给原 `roleplayScreenshotBlockIds`。
3. 保留原时间线顺序、消息归属、toggle/clean/block ID、版本与录音快照 reproof；renderer、录音结构和资源安全规则未改。没有凭空推断录音、读取音频文件或发起生成。

最终边界测试贯穿：官方录音 block → 分组 → 勾选/clean → ordered 过滤 → 真实 offscreen renderer → PNG 保存和解码。没有录音身份参数时仍不能凭选择字符串虚构一条可截图回复。修改录音名称/时长后 raster bytes 不同，证明录音对应内容确实进入图像；保存字节一致，重复保存同名目标被拒绝。合成 `recording.png` 可见 `Alice recording · 31s`，保存在 `synthetic-png/boundary-previews/`；未读取 `audio/never-read.mp3`。

## 边界二：极长 PNG 适应窗口 — CONFIRMED

初始 RED：320×25000 PNG 正好 800 万像素，在当前预算内；360×100 预览需要 0.4%，但所有缩放共用 1% 下限，实际高度 250 像素，不能完整显示。原始失败断言与 XML 均保留。

最小修复在 `DesktopScreenshotPreview.kt:37,57`：只有自动 `WINDOW` 适应模式允许低于手动下限的正倍率。具体倍率仍由 `min(viewportWidth/imageWidth, viewportHeight/imageHeight)` 计算；宽度适应、手动缩放、Ctrl+滚轮、100% 及 800% 上限不变。resize 会按当前适应模式重新计算。

最终测试用真实编码/解码的 320×25000 PNG：360×100 下为 0.4%，resize 到 280×60 后为 0.24%，双轴内容均在视口内且滚动偏移归零；随后手动极小倍率仍钳制为 1%，极大倍率仍为 800%。导出字节与原 PNG 完全一致、解码尺寸仍为 320×25000。

原 viewport-sized Canvas/变换机制和 PNG 预算未改；没有按预览倍率创建放大图片副本。相邻测试继续覆盖双轴滚动、锚点/resize、800% 预览、资源/版本变化拒绝、无覆盖、取消与失败重试。此轮是工程测试，未执行 Windows 实机点击或用户视觉验收。

## 全部测试结果与中途失败

JDK 17.0.20、offline、no-daemon、single worker、in-process Kotlin、low priority。命令、资源快照、stdout/stderr、实际 exit code、只复制本阶段新写 XML 的 provenance 均在 raw 各阶段目录。未运行 full bounded、Robot 或 Android 全套。

| 阶段 | 实际测试 | PASS | FAIL | ERROR/SKIP | Gradle exit |
|---|---:|---:|---:|---:|---:|
| red-boundaries：修复前 | 2 | 0 | 2 | 0/0 | 1 |
| green-focused：补丁工具失败后的未完整修复尝试，目录名不代表通过 | 123 | 120 | 3 | 0/0 | 1 |
| final-screenshot：完整修复后截图范围 | 24 | 24 | 0 | 0/0 | 0 |
| final-adjacent：关闭/协调/语音 | 99 | 99 | 0 | 0/0 | 0 |
| final-boundary-repeatability：仅测试输出改为每次独立目录后的复核 | 2 | 2 | 0 | 0/0 | 0 |

最终为 **13 suites / 123 个不同测试 PASS**；末轮 2 项是同一边界复核，不重复算新增覆盖。全部阶段累计 250 次测试执行：245 PASS、5 FAIL；没有把初始 RED 改写为 GREEN。详细 suite 统计、失败方法与 XML SHA 在 `raw/stages-summary.json`。

- 最终截图 5 suites/24：Boundary 2、Workspace 9、Workspace UI 4、Chat Screenshot 7、Chat Screenshot UI 2。
- 最终相邻 8 suites/99：WindowChromeContract 2、ApplicationLifecycle 39、DataRootSwitchController 21、AudioMaintenance 11、VoiceClickReliability 7、VoiceUx 4、VoicePauseWindows 1、DataOperationCoordinator 14。
- 中途 123 项中，除相同两项截图 RED，另有 `DesktopAudioMaintenanceTest.migration reservation makes concurrent automatic checks defer before scheduler join` 在 `:231` 的 3000ms migration-await 超时（XML 用时约5.95s）。该尝试只应用了截图 Selection 参数修改，音频实现/测试未动。最终相邻 99 项独立执行时，原音频维护 11 项全通过，**未调大超时、未削弱断言**。超时原因仍 UNKNOWN，不能因后续 GREEN 删除 RED，也不能仅凭本次记录声称复现产品死锁或已证明资源是根因；交 Project 独立判断。
- 补丁工具曾遇到 GBK 解码错误，导致未完整修改状态下误启动上述中途测试；随后补全 UTF-8 修改。skill 写入受目录权限限制后，单独按仓库维护要求完成。工具失败说明见 `raw/patch-tool-failures.txt`，中途测试日志/XML 原样保留。
- Desktop main/test 编译实际执行，最终独立 package 编译成功；source diff-check PASS。没有原生 GUI，因此上述组件/生命周期 PASS 不能替代真实 HWND Close、主窗口存活或数据根锁实机结论。

## 新 Windows 包与旧证据保全

新包（未启动）：

`H:\ChatChatBar-Desktop\app\desktopApp\build\post-p8-screenshot-r2-boundary-20261021\windows-frozen-c38bcf27\runtime-image`

- 精确源码：`c38bcf274fca2eafb81a27b10cb3fcb16ed94fbe`；全新 `package-c38bcf27-build` 独立构建目录，`:desktopApp:createDistributable` exit0。
- **212/212 文件**：原构建目录 → 新冻结副本 → 再次全量 SHA-256，数量、相对路径、长度和内容均一致。
- `distribution-manifest.json` SHA-256：`51f91f5bcd1f9b943467f3e6ecddcebbac1e46807a8cba7ab327f8829743fa7d`。清单副本在 `package/`；完整冻结记录在 `raw/frozen-package.json`。
- 旧 `3ece1366` R2、`60cc44d0` Reliability R1、`694a8bdd` audio-maintenance R1 包各 212 文件及原 manifest 全量核验 UNCHANGED。
- 旧 R2 证据 manifest 的142项长度/SHA全部核验未变（manifest本体另计）；旧 R3/22d 包、数据目录、人工原始反馈和历史 RED/SKIPPED/INCONCLUSIVE 没有覆盖或清理。
- 四份用户文件仍为原4路径 UNTRACKED；从未读取、哈希、修改或暂存。仅提交本轮7源码/测试/skill文件与全新独立证据目录。

## 仍未验证与交接停止

- 未启动新包；Windows 原生选择树操作、极长图真实窗口显示/滚动、缩放与 resize、实际系统保存器、Close/Esc/X 后主程序存活和根锁保持，仍需 Project 审查后的独立人工 Gate。
- 历史截图内部文字 Close 导致主程序退出，根因仍 **UNKNOWN**；本次未修改 Main/Window Chrome，不宣称已解决。R2 Windows 人工验收与 Post-P8 HOLD 不变。
- 中途音频维护超时原因仍 UNKNOWN；保留原始失败并明确后续11项通过的有限证据，未借本轮扩大维护安全结论。
- Studio credential error OPEN、QQ native BLOCKED、WIN-AUTO-001/WIN-BRAND-001、完整 bounded 非绿色及全部历史限制继续保留。
- PDF/Word/Seek、Deck/User工具栏、整条语音重新生成、D-044等均未实施。本次仅完成两个截图边界的最小修复。

**提交并推送原 feature 分支后 STOP，交 Project 独立审查；不提升 P8/Preview/parity 或发布状态。**
