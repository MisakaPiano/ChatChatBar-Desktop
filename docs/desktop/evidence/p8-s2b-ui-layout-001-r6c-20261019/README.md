# R6C — OUTER MEASUREMENT OBSERVED / NEW NARROW RED / HOLD

## 控制点、唯一修改与保全

起点、tracking和fetch后GitHub live feature均为 `46d9f89691cafd1fbb69b5e7cd3481602bafa10c`；parent为 `33cf1beeb5dfcb655e261a6fadff7610310af522`。分支 `feature/phase8-fish-audio`，原index为空。origin/live desktop仍为 `5ef95428421f7c693b010ac88f51d470804ab07a`，本地desktop旧引用未移动。三份冻结草稿原始hash完全匹配，[preflight.json](preflight.json)保留长度、哈希及用户文件路径盘点结果。

仅修改NativeUx测试 `Fixture.assertPagerLayout()` 的几何测量与直接诊断，增量 **+52/-4**。输入继续由SetText功能节点定位，交互函数保持原样。两份生产草稿原字节未改；[测试增量](measurement-fix.patch)、[实际源码](tested-source.kt)、[源码身份](tested-source-identity.json)、[保全证明](source-preservation.json)完整保留。测试原始42187 bytes，SHA-256 `e0209f44e85e9326aa4f1e4fb1a111348e6f7e704898db1113a46227df667af0`。

删除/替换范围限于该方法：方法外所有代码和原断言块文本均与R6B一致，12px、宽度/执行顺序、16步拖动、wheel、分页和Int分支没有改动。所有三份Kotlin草稿仍未暂存/提交；四份user-owned文件继续KEEP / UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED，无未知修改。

## 测量机制及失败关闭条件

1. 选择SetText页码功能节点与Role.Button/OnClick完整跳转节点，并确认同一原生语义owner。
2. 通过公开LayoutInfo/parentInfo引用身份找最低共享祖先，取输入路径上直接属于该祖先的外层布局项；不使用固定节点ID、层级深度、88px、8px或已测坐标。
3. 要求按钮本身是该配对布局的直接子项；配对布局必须与上一页、页数、下一页拥有相同外层pager父对象。此拓扑检查区分输入/按钮的直接配对Row与更高层FlowRow。父级不符、环、无外层项都明确FAIL，不fallback到文字矩形。
4. 输入外层项必须已放置、attached，且公开coordinates.size与整个layoutInfo尺寸精确相等，防止再次采用padding内部坐标。输入使用该完整外层项的实际屏幕原点/尺寸。
5. 按钮采用boundsInWindow加实际语义根窗口屏幕原点，并要求其语义尺寸精确覆盖layoutInfo完整尺寸。全部viewport、间距和重叠比较使用同一屏幕坐标；没有新增容差。原相邻性上限仍12px。

所有运行时父级身份、外框、间距、重叠均在 `FISH_R6C_PAGER_OUTER` 中记录，见[外框结构化数据](outer-geometry.json)。R6B原诊断也保留，用于对照内部文字区域；其16px间隔不再作为外层控件相邻性度量。

## Gate A：一次执行，新的RED

沿用[精确命令](01-gate-a-single-method/command.txt)，JDK17、offline、no-daemon、no-parallel、单worker、low priority、Kotlin in-process、原2048MiB heap；没有clean、rerun-tasks、skip或新排除。

**编译PASS；1 suite / 1 test / 0 reported PASS / 1 FAIL / 0 errors / 0 SKIPPED，exit1。** 测试16.070s，外部阶段105.0182039s。[原始XML](01-gate-a-single-method/junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml) SHA-256为 `db85b712edcd4e27f66816738345dd8b6ca88088f05fb381010f339c7730ae3f`。

首个失败为第353行原有 `field.y > previous.y` 窄窗断言，消息 `360dp pager must wrap without clipping`，调用点第570行。[完整stack与到达范围](result.json)、原stdout/stderr、[完整原生system-out](native-system-out.txt)及[提取事件](native-geometry-events.jsonl)保留。RED后未改源码、未重跑。

| 请求宽度 | 外框、12px、viewport | 实际拖动/滚轮 | 已知total分页操作 | 整体状态 |
| --- | --- | --- | --- | --- |
| 1100dp | 两次测量gap8、重叠32，PASS | 单次Press、16步Drag、Release、wheel完成，range增加且首项变化 | 无效页码无请求，Enter到3页、点击到2页、归顶和计数断言完成 | 此宽度分支完成，不是独立JUnit test |
| 720dp | 两次测量gap8、重叠32，PASS | 同上实际完成 | 同上完成 | 此宽度分支完成，不是独立JUnit test |
| 360dp | 初始完整外框gap8、重叠32、shared pair及viewport检查通过 | NOT REACHED | NOT REACHED | 原窄窗top关系断言FAIL |

未知total及Int边界fixture位于循环之后，本次NOT REACHED。不能把R5历史PASS算成本次PASS；JUnit整体仍只有一个FAIL。

## 首个失败的实际360dp几何

Frame=(48,48,360,720)，client=(56,79,344,681)，DPI1.0。以下为屏幕x/y/width/height：

| 项目 | 实测完整控件矩形 |
| --- | --- |
| 上一页 | (68,667,76,32) |
| 页数文字 | (152,675,86,16) |
| 输入外层布局项 | (246,654,88,58) |
| 跳转完整按钮 | (342,680,42,32) |
| 下一页 | (68,716,76,32) |

输入与按钮的直接父对象身份一致，间距8px、重叠32px；全部矩形均已通过client.contains检查。共享FlowRow实测320×94px，原点(68,654)。输入配对项在首行，“下一页”在第二行，所以**发生了实际换行**，并非从FAIL消息推断没有换行或已有裁剪。

原窄窗谓词具体计算为 `654 > 667`，结果false。它要求输入框外层项顶部低于上一页顶部；输入外层项包括label与输入区域，其上边缘可以高于同一flow行中垂直居中的上一页按钮。当前新记录与[窄窗失败几何](narrow-failure-geometry.json)交Project判断该谓词是否忠实于“合理换行”的产品合同；本轮未修改它，也不把未到达的窄窗操作判为PASS。

下一轮最小建议：先复审窄窗换行判据，应否检验实际分页项的行分配/至少一个操作项位于下一行，而不是强制指定输入框相对上一页的top关系；继续保留完整viewport、输入/跳转紧邻、实际操作与全部边界验证。此建议未实施，未进入R6D。

## Gate B与资源、请求、收尾

Gate A为RED，故 **Gate B NOT STARTED**，没有运行NativeUx整类，也未启动Gate B资源preflight。后续全聚焦/bounded恢复条件不满足；这些门槛仍待Project复审后另行授权。没有打包、Windows人工或新实包进程/正式根锁验收。

Gate A前可用物理内存1363816448 bytes、Commit余量15015374848 bytes、pages/s11、Java0。运行中最低可用245637120 bytes（约0.229GiB），最低Commit余量13216829440 bytes，最高pages/s30454。存在明显压力；未观察到OOM，未把内存压力自动归因为确定性几何谓词RED。[分阶段统计](stage-summary.json)和原始resources.jsonl完整保留。Gate A后可用1751789568 bytes、Commit余量15462477824 bytes、pages/s0、Java/javaw0。无杀用户进程、清缓存/历史包或改pagefile。

真实Fish GET0/TTS0；mock HTTP0、绑定0；内存合成目录调用7次（1100及720各[1,3,2]，360初始1）。实际完成拖动2次、Drag步骤32、wheel完成2次。fixture finally没有owner恢复、窗口dispose、scope/runtime drain或mp3/part清理断言失败；未生成音频，未启动人工fake或TLS。Native fixture收尾不冒充实包Launcher/JVM退出0/0或正式根锁重获，见[请求与清理](requests-cleanup.json)。

R3–R6B所有历史证据树/工作树均未改，见[保全记录](history-preservation.json)。两份生产草稿、Prompt、Entity/schema、runtime/HTTP/SecretStore、CURRENT、parity和正式baseline未改；WIN-AUTO-001/WIN-BRAND-001继续OPEN。无S3、G2、P10/P11、merge或release。

**R6C HOLD / NEW NARROW RED PRESERVED / PROJECT REVIEW PENDING**。只提交本目录独立证据，三份Kotlin草稿继续未提交。STOP交Project独立复审，不自动进入其他Gate。

静态检查：文档链接、生产草稿diff-check及排除原始log/patch后的证据检查PASS；完整原始证据diff-check exit2。原始日志和补丁空白告警保留于raw-diff-check.log，不为检查GREEN改写原始证据。暂存blob与磁盘原字节逐项核对，manifest逐项校验。
