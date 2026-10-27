# P8-S2B-UI-LAYOUT-001-R5 — HOLD / RAW RED PRESERVED

本目录为独立原始证据，不是生产源码checkpoint或UI验收。Asia/Tokyo日期2026-10-19；原始执行记录保留UTC时间。

## Git、身份与保护

起点、fetch后tracking、GitHub live origin均为 `8f36e0849413300638880ccf2276a92eb0b6e989`，parent为 `3d46c6448b6748644c561ea27d3ed2b016e1b045`，分支 `feature/phase8-fish-audio`。index最初为空，无未知修改。[预检](preflight.json)及[冻结R4身份](baseline-source.json)保留。

修改前，三个原始文件的raw bytes长度、SHA-256和LF规范化SHA-256均与R4冻结记录一致。R4完整补丁SHA-256仍为 `21cca30223dd289aa87f02edcd56f32a884a43e131a936269d52e489c77495d8`。

- 第一阶段只修改Native测试；两份生产草稿不变。阶段01测试源码在[01-source](01-source/DesktopFishPickerNativeUxTest.kt)，配套三文件身份在[01-source-hashes.json](01-source-hashes.json)。
- 第一阶段GREEN后，按授权仅改Presentation分页栏，并扩充Native布局/滚轮断言。`DesktopFishPickerWindow.kt` 原始字节仍与R4一致。
- 最终三文件均保留未提交、未暂存；[final-sources](final-sources/DesktopFishPickerNativeUxTest.kt)与当前工作树逐字节一致，RED后未改源码。[源码身份](source.json)和[完整候选补丁](source.patch)可重建草稿。
- R5自身增量：Presentation +12/-12，Native测试+121/-22；完整相对HEAD候选为3文件+590/-35。完整补丁SHA-256为 `c3e26bd457c7f202f4bb3df8fbe942e398aadee7ba758215ca60bfe5d46ce264`。
- 个人Markdown和三个历史JPEG继续KEEP / UNTRACKED / USER-OWNED。未读取内容、修改、移动、暂存或提交；不能宣称整个工作区clean。未reset/rebase/force/stash/clean。

## 两次执行独立记录，不相加

| 阶段 | exit | suites / tests | reported PASS | FAIL | SKIPPED |
| --- | --- | --- | --- | --- | --- |
| 01 一次原失败方法的真实输入准入Gate | 0 | 1 / 1 | 1 | 0 | 0 |
| 02 分页草稿后的NativeUx整类 | 1 | 1 / 4 | 3 | 1 | 0 |

两阶段测试源码不同，不能合并成一次验证统计。全部command、原始stdout/stderr、进程身份、JUnit XML和资源采样分别保留。JDK17、offline、no-daemon、no-parallel、单worker、low priority、Kotlin in-process、既有2048MiB heap；没有clean、依赖升级或改变排除。

阶段01：[XML](01-native-input-admission/junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml)，SHA-256 `07ce354f11b72f46f1ac6de8cc673d8c5eac0f5d89f321b34e5ca091f4887fbe`；方法12.631s，外部阶段62.2540592s。

阶段02：[XML](02-native-class-with-pager/junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml)，SHA-256 `1426ac7649dc218c3bb60ac2ae1786f26076015f7fd45329fba21b4bbfc5b3a6`；整类28.706s，外部阶段83.4270649s。生产/测试编译均PASS；失败发生于测试断言。

## 已通过的真实输入准入

[原生事件与几何记录](native-events-and-geometry.jsonl)及[单方法结果](input-admission-result.json)明确区分计划坐标、系统真实鼠标、AWT实际按下、thumb矩形与最终滚动。

1. 沿用公开ScrollbarStyle的委托Shape观测，未改产品thumb或命中区。目录完成、range max非零、布局已放置、目标窗口活动后，才对初始零offset的thumb执行输入。
2. Robot移至真实thumb中心；在Dispatchers.IO中执行waitForIdle及三次25ms间隔的真实MouseInfo采样，EDT始终能处理原生事件。一次稳定准入后只发一次Press。最多一次重新定位被写入控制逻辑；此次两种宽度均attempt0通过，未使用重新定位。
3. 1100dp实际Frame1100×720，thumb屏幕矩形(1120,155,8,89)，计划/系统/AWT按下均为(1124,200)。720dp实际Frame720×720，thumb(740,261,8,54)，三种按下坐标均为(744,288)。DPI变换均1.0，AWT按下确实位于thumb内。
4. 每个窗口执行16步有界连续真实拖动；每步核对正确窗口实际收到带BUTTON1 mask的目标坐标，释放各一次并匹配终点。没有语义ScrollBy、直接LazyListState修改或虚假滚动注入。
5. 原来的滚动值增加断言保留，同时首个可见合成条目由1变为9。拖动完成时每个fixture目录查询仍为1，Mock HTTP0、绑定0。后续原有有效/无效页码、Enter/点击、切页归顶、未知total和Int边界分支在阶段01均完成。

这证明本轮稳定输入驱动的这次执行有效；不把R4额外移动来源或R3历史失败原因改写为已知。R3/R4原始RED继续保持其原身份，WIN-AUTO-001不被作为本次原因。

## 分页草稿与新RED

第一阶段PASS后，Presentation把分页区改为FlowRow：上一页、无weight页数标签、绑定为一个Row的输入框/跳转、下一页；只有空间不足才换行，输入和跳转保持同组。校验、显式Enter/点击查询、Int守卫和列表切页归顶逻辑未改。

新增Native断言计划覆盖1100/720/360dp：所有控件位于client内；足够宽时共行；360dp允许换行；输入和跳转紧邻；实际拖动、滚轮及分页操作仍可达。**这些计划不等于已经PASS。**

阶段02失败于 `assertPagerLayout` 第250行，调用点第472行，谓词是：

`jump.x >= field.x + field.width && jump.x - field.x - field.width <= 12`

错误为 `Page input and jump must remain adjacent, including wrapped rows`。[失败摘要](failure-summary.json)保留原始stack。此处位于第一轮1100dp、`dragThumbFromTop()`之前；不能把它称为新的拖动失败。

失败时完整分页矩形没有保留：`FISH_R5_PAGER` 输出在这些assert之后，尚未执行。现有XML不足以判定实际布局违反紧邻，还是 `positionOnScreen` 与 `boundsInRoot` 所选语义节点的观测方式存在偏差。根因UNKNOWN，不能静态猜测后放宽断言或假称产品已修复。下一轮需Project明确授权，先在断言前保留各节点实际矩形、坐标基准和父子语义，再选择最小修复。

整类另外三项Stop、Escape、resize/cache测试PASS。它们与失败方法同属已启动的一个测试阶段；新RED出现后不再派发任何后续阶段，只等待该阶段及finally清理完成。没有新skip、exclude、减弱断言或重跑至偶然GREEN。

阶段02的720/360dp布局、分页后真实拖动、滚轮、新布局下页码/Int分支未到达。单方法旧布局的PASS不冒充这些新布局分支PASS。

## STOP、资源与网络

- 四类Fish UI聚焦、F1/F2/Settings/Studio回归、bounded：NOT STARTED。原四项排除未改，但本轮未执行bounded，不能声称本轮重验了既有2项JUnit SKIPPED。
- 新Windows distribution与人工Gate均未开始。实际8px拖动易用性、宽窄分页、Escape/Stop、最大化/还原、Launcher/JVM退出及根锁排他/重获均NOT TESTED。本轮没有源码checkpoint，只有独立HOLD证据。
- 真实Fish GET0/TTS0，人工fake服务未启动；未读取真实凭据、聊天或卡包。阶段01目录为内存合成函数、HTTP0。阶段02受控Stop/缓存方法共有mock GET5/POST5，与真实请求严格分开，见[请求统计](request-accounting.json)。
- Native fixture finally没有owner恢复、窗口dispose、scope/runtime drain或mp3/part清理断言失败；结束后Java/javaw为0。这不是实包Launcher/JVM退出0/0或正式根锁重获证据。
- 运行前可用物理内存2869645312 bytes、Commit余量17011929088 bytes。阶段01最低可用1409716224、Commit余量14985154560 bytes；阶段02最低可用593559552、Commit余量13896781824 bytes，最高pages/s17490。明显内存压力如实保留，未观察到OOM；没有改pagefile、杀用户进程、清理历史包或恢复3/8GiB硬门槛。[完整统计](stage-summary.json)保留各自原始采样，未在RED后强行启动重型测试。
- 证据辅助脚本在所有文件复制完成后的最后打印环节遇到Windows默认GBK解码错误；不是Kotlin测试RED，也没有重跑测试。[工具说明](tooling-note.json)与初始脚本保留，修正只明确该最终文本读取使用UTF-8，未改变源码或XML。

## 历史与边界

[历史保全](history-preservation.json)记录UI首轮/R1/R2/R3/R4证据树及工作树均未变。旧RED、人工FAIL、SKIPPED、symlink NOT TESTED和manifest保持原状。F1/F2协议、runtime、HTTP、Store/Player、SecretStore、绑定、Prompt、Package/Entity/schema、正式upstream baseline及Feature Parity未改。

WIN-AUTO-001与WIN-BRAND-001继续OPEN，品牌项仍待独立治理登记。MCI/device、TOCTOU、物理I/O绝对超时等限制不变。不进入S3、聊天音频、SaveSlot、QQ、G2 Writer/Recovery或production dispatch，不合并desktop、不发布。

静态检查：文档相对链接有效，源码草稿及排除原始log/patch后的证据diff-check均exit0。完整原始证据diff-check为exit2，报告Gradle原始行尾空格与补丁空白上下文；[原始检查](raw-diff-check.log)保留，不为获得空白检查GREEN篡改日志或补丁。暂存blob逐项与原始磁盘字节比较，manifest成员逐项校验；仅本目录进入证据提交。

**P8-S2B-UI-LAYOUT-001-R5 HOLD / RAW RED PRESERVED**。草稿保留，停止并交Project复审；UI-LAYOUT-001未关闭。
