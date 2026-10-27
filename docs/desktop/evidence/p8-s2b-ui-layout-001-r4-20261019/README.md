# P8-S2B-UI-LAYOUT-001-R4 — HOLD / RAW RED PRESERVED

这是一次有界原生滚动条诊断的独立证据，不是源码 checkpoint 或正式验收。目录日期按 Asia/Tokyo 为2026-10-19，原始日志保留UTC时间。

## Git、草稿与范围

- 起点、tracking及重新fetch后的GitHub live origin均为 `3d46c6448b6748644c561ea27d3ed2b016e1b045`；直接parent为 `55366cee0706c78f545b32ef7d75750c649dcdbd`。初始index为空，分支为 `feature/phase8-fish-audio`。
- 修改前逐项核对R3三文件raw bytes长度、SHA-256与LF规范化SHA-256，全部与冻结[source.json](baseline-source.json)一致。R3完整补丁SHA-256仍为 `a94aaf3526111ac364ba636a9e96306991688891c597e5cafa13152be9606f66`。
- 本轮只修改未跟踪的 `DesktopFishPickerNativeUxTest.kt`，诊断增量为+72/-5。两份R3生产草稿 `DesktopFishPickerPresentation.kt`、`DesktopFishPickerWindow.kt` 原始字节未改。全部三份草稿保留在工作树，不暂存、不提交。
- [完整候选补丁](source.patch)为3文件+489/-33；[本轮诊断增量](diagnostic-only-delta.patch)与[源码身份](source.json)区分R3设计和R4测试观测。完整候选补丁SHA-256为 `21cca30223dd289aa87f02edcd56f32a884a43e131a936269d52e489c77495d8`。
- 未读取、修改、移动、暂存或提交个人Markdown及三个历史JPEG的内容。它们继续KEEP / UNTRACKED / USER-OWNED，不能据此宣称整个工作区clean。无reset/rebase/force/stash/clean。

## 唯一执行结果

精确选择器及完整命令在[command.txt](01-native-scrollbar-diagnostic/command.txt)，使用JDK17、offline、no-daemon、no-parallel、单worker、low priority、Kotlin in-process和既有2048MiB heap。生产编译UP-TO-DATE，测试编译执行成功。

| 阶段 | exit | suites / tests | PASS | FAIL | SKIPPED |
| --- | --- | --- | --- | --- | --- |
| 一次原失败方法诊断 | 1 | 1 / 1 | 0 | 1 | 0 |

[原始XML](01-native-scrollbar-diagnostic/junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml)保留完整stack与system-out；SHA-256为 `454168cde5e4913d97f66cf14fdaf2c44feb432a193432199d2d238a79373647`。仍在等待原条件 `range().value() > before` 时10,000ms超时，本轮诊断源码调用点为第375行。测试方法耗时13.667s，外部阶段观察62.2910036s；统计口径不同，不相加。

没有放宽原断言、增加超时、重跑到偶然GREEN、使用语义ScrollBy或直接修改LazyListState。出现此次RED后立即停止续行；未执行整类、四类聚焦、受影响回归或bounded，未新增exclude或skip。

## 已证明的实际输入偏差

详细记录见[几何与滚动状态](native-geometry-state.jsonl)、[35条AWT鼠标事件](native-event-trace.jsonl)、[Robot计划坐标](robot-commands.jsonl)及[失败摘要](failure-summary.json)。

1. 第一轮窗口实际Frame为1100×720；client为1084×681，屏幕原点(56,79)，DPI变换1.0。窗口活动、有焦点；公开parent输入lease仍使主窗口disabled。
2. 等待目录加载完成、滚动maxValue非零、真实布局已放置、窗口活动和thumb实际绘制后才拖动。结果track屏幕矩形为(1120,155,8,487)，初始滚动0，实际thumb为8×89，位于track顶部。没有把窗口内坐标当作thumb命中。
3. Thumb尺寸由测试内委托的公开 `LocalScrollbarStyle.shape.createOutline` 记录；返回原shape的原outline，未改样式、尺寸或产品代码。位置依据本机固定Compose1.10.3的实际布局算法：零offset、非reverse时，thumb放在track顶部。[依赖身份](compose-library-identity.json)及[本地字节码读取](compose-1.10.3-scrollbar-bytecode.txt)保留。没有调用私有实现、反射、内部API绕过或永久产品诊断开关。
4. Robot计划在(1124,163)按下，位于上述thumb内部；AWT实际 `MOUSE_PRESSED` 却为(1118,162)，BUTTON1 mask1024，落在track左侧2像素以外。此前先收到(1124,163)进入事件，随后移动至(1118,162)。按住期间又出现向左上方移动的多个事件，非本次显式Robot轨迹所能解释。
5. 后续AWT确实收到向计划终点(1124,399)的拖动，接收者是本测试自己的SkiaLayer；这只能证明AWT送达自己的窗口，不能证明thumb指针处理器捕获了最初按下。Release在(1120,397)收到。
6. 按下、移动、释放及最终超时的四次观测中，语义滚动值均为0/max100，可见合成条目仍为第1–4条，首条offset0。max100是LazyList的语义范围，不能当作真实像素总高度。无隐式目录查询、TTS或绑定：目录合成函数调用1，MockWebServer物理请求0，绑定0。

本次实测的“计划位置正确，但实际按下未命中thumb”已经确认。坐标额外移动由谁引起仍UNKNOWN，不能武断归因于用户、系统、Robot或捕获设施。现有记录没有产品滚动条缺陷的证明，也没有足够信息判定旧R3失败的根因。R3历史依然UNKNOWN；本次RED不能自动归入WIN-AUTO-001。

由于新相关RED触发STOP，未继续调整真实拖动驱动。下一轮如Project批准，可围绕实际接收按下坐标、可信thumb内部位置、连续有界拖动及事件送达验证修正测试；不得通过扩大thumb产品命中区、假滚动或删除断言掩盖输入问题。

## 未到达的验证与收尾

- 720dp、滚轮、后续页码输入／Enter／点击跳转、未知总数及Int边界分支：本次均NOT REACHED。原断言仍在完整草稿中，未删除。旧执行结果保持各自历史身份，不冒充本轮PASS。
- 新Windows distribution、用户人工拖动／Escape／Stop／最大化／还原、Launcher/JVM退出、运行中根锁排他与退出后独立重获：NOT TESTED / NOT STARTED。没有构建新包或启动人工实例。
- 本次原生fixture的finally未出现清理断言失败：恢复自身parent输入、dispose自身窗口、scope/runtime drain，并断言自身临时根无mp3/part。测试命令exit1；结束后Java/javaw进程数0。这不等于实包Launcher/JVM退出0/0或正式根锁重获。
- 真实Fish GET0 / TTS0；仅有合成目录函数，MockWebServer HTTP0。人工fake服务未启动，临时TLS未创建，无真实凭据、聊天或用户卡包读取。
- 运行前可用物理内存2767945728 bytes、Commit余量17623212032 bytes；六次采样最低可用物理内存1226350592 bytes、最低Commit余量15572062208 bytes，最高pages/s3215。未观察到OOM；未调整pagefile、终止用户进程或清理历史数据。完整[资源统计](stage-summary.json)及原始resources.jsonl保留。

## 历史、后续要求与限制

[历史保全](history-preservation.json)记录F2/R1/R2以及UI首轮/R1/R2/R3证据树身份与工作树无改动。旧RED、人工FAIL、两项既有JUnit SKIPPED、旧symlink NOT TESTED及所有原始manifest均未改写。本轮仅一个失败方法，不能说既有SKIPPED被重新验证。

用户新增布局要求已登记于[deferred-pagination-layout.json](deferred-pagination-layout.json)：宽度允许时，页码输入及“跳转”紧接“第n页/共m页”放在同一行；确有空间不足时才换行。状态RECORDED / IMPLEMENTATION PENDING，本轮RED后未追加产品修改。

静态核对：文档相对链接均有效，源码草稿diff-check和排除原始log/patch后的证据diff-check均exit0。完整原始证据diff-check为exit2，只报告Gradle原始行尾空格和补丁空白上下文；[原始检查记录](raw-diff-check.log)保留，不为获得空白检查GREEN篡改原始证据。提交前逐个比较暂存blob与磁盘原始字节，并复核manifest全部成员。

WIN-AUTO-001继续OPEN，WIN-BRAND-001继续独立后续治理待办。MCI/device、平台TOCTOU、物理I/O绝对超时等限制不变。未修改Prompt、Package/Entity/schema、baseline或parity；未进入S3、聊天音频、SaveSlot、QQ、G2 Writer/Recovery或production dispatch；不合并desktop、不发布。

状态：**P8-S2B-UI-LAYOUT-001-R4 HOLD / RAW RED PRESERVED**。UI-LAYOUT-001仍OPEN，交Project复审后停止。
