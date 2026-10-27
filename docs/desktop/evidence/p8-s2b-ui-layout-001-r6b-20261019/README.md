# R6B — GEOMETRY EVIDENCE COMPLETE / TEST RED PRESERVED

## Git、源码与执行

起点、tracking及fetch后GitHub live feature均为 `33cf1beeb5dfcb655e261a6fadff7610310af522`，parent `f4556199aee10c87977ddaa3224574af5bf14310`。`origin/desktop`及live仍为 `5ef95428421f7c693b010ac88f51d470804ab07a`；本地desktop旧引用保持 `4c2af2576e2319e8041ffd2a6c5a205450f18246`，没有切换或更新。正式1.4.1 baseline未改。[预检](preflight.json)记录三份冻结草稿的原始长度/hash、空index及四个user-owned路径盘点。

唯一工程增量为Native测试 `assertPagerLayout()` 新诊断的 **+4/-3**：从公开parent链取得类型明确的SemanticsNode语义根，修正两处坐标及一处ID读取，并明确日志字段名。[完整增量](diagnostic-only.patch)、[实际测试源码](diagnostic-source.kt)、[源码身份](diagnostic-identity.json)保留。三份草稿未暂存/提交；两份生产文件原始字节未改；原断言、12px、1100/720/360dp顺序、Shape和Robot逻辑完全未改，见[保全证明](source-preservation.json)。

仅执行一次[原命令](01-pager-geometry-single-method/command.txt)：JDK17、offline、no-daemon、no-parallel、单worker、low priority、in-process、既有2048MiB heap；无clean/rerun-tasks/新排除。编译PASS；**1 suite / 1 test / 0 PASS / 1 FAIL / 0 errors / 0 SKIPPED，exit1**。原相邻性断言第300行失败，调用点第522行。测试3.486s，外部阶段82.9205413s，口径不同不相加。

[原始XML](01-pager-geometry-single-method/junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml) SHA-256：`389e0cb1e0ca8633f59bab9e1c51f83c7ec5fed2b53904a19234eb7e45ce3868`。原stdout/stderr、完整stack和system-out均保留；RED后未改源码或重跑。

## OBSERVED — 首个1100dp检查点

Frame为(48,48,1100,720)，client为(56,79,1084,681)，DPI1.0、fontScale1.0，窗口活动且有焦点。断言前已flush **80条**稳定标记记录，包含5组匹配、6个候选节点及其祖先布局。[原始system-out](geometry-system-out.txt)、[原始记录JSONL](geometry-raw.jsonl)、[结构化解析](geometry-parsed.json)完整保留；原XML为最终权威。

以下矩形均为屏幕LTRB坐标，节点ID只代表本次运行，不能固化为未来测试期望。

| 控件 | 全部匹配ID | 选中ID/理由 | 实测语义屏幕矩形 | 测量对象 |
| --- | --- | --- | --- | --- |
| 上一页 | 50 | 50 / Role.Button | (372,703,448,735) | 完整按钮，disabled，OnClick存在 |
| 第1页/共6页 | 53 | 53 / 首个label匹配 | (456,711,542,727) | 页数文字 |
| 跳转页码 | 57、58 | 58 / SetText | (558,721,630,740) | **内部可编辑文字区域72×19** |
| 跳转 | 62 | 62 / Role.Button | (646,716,688,748) | **完整按钮42×32**；空输入时disabled |
| 下一页 | 64 | 64 / Role.Button | (696,703,772,735) | 完整按钮，enabled |

节点57是“跳转页码”静态label，矩形(550,690,606,709)，没有SetText。节点58具备SetText、OnClick、RequestFocus，选择它执行输入是正确的；但其语义几何并不是整个带边框输入控件。不存在两个可编辑输入框或两个跳转按钮的证据。

输入节点58：positionOnScreen=(558,721)，boundsInWindow与boundsInRoot均为LTRB(502,642,574,661)；layoutInfo.size=88×35，而coordinatesSize=72×19。按钮62：positionOnScreen=(646,716)，boundsInWindow与boundsInRoot均为(590,637,632,669)；layoutInfo.size=42×32，语义外框与点击控件相符。按钮内部文字坐标另为(654,721)、26×22，测试没有选中该内部文字。

输入框与按钮的**被选中语义矩形**：屏幕gap=16px、window gap=16px、root gap=16px，垂直重叠19px。原断言的左侧先后关系成立，但 **16 > 12** 导致RED。三种坐标计算一致，未发现本次存在屏幕/窗口/根原点混用；窗口屏幕原点为(56,79)。

语义parent链为输入58→42→11→9→8→5，按钮62→42→11→9→8→5。合并后的语义链省略了无语义的布局容器，不能仅靠该链判断它们不在同一个Row。

实际公开LayoutInfo链补全关系：输入58→Column56(88×58)→Box55(88×58)→**Row54(138×58)**→**FlowRow48(756×58)**；按钮62→**同一Row54**→**同一FlowRow48**。节点身份记录证明共享的是同一实际布局对象。

88dp输入容器的实际屏幕原点(550,690)、尺寸88×58，右边缘638；完整跳转按钮左边缘646，所以**容器到按钮的实测gap=8px**。配对Row原点(550,690)、尺寸138×58，等于88+8+42；FlowRow原点(372,690)、可用尺寸756×58。五个分页项在本次1100dp位于同一逻辑行，不是宽窗口被强制拆为两行；这不等于窄窗或完整操作Gate已PASS。

## 根因分类与派生几何

分类：**PROVEN_TEST_OBSERVATION**，范围仅为本次1100dp相邻性RED。[根因结构化记录](root-cause.json)区分直接观察和派生值。

测试对比了输入框内部文字区与按钮完整边框，测量对象层级不同。功能节点没有误匹配，坐标原点也一致，但它不适合直接验证外层控件8px间距。

既有[DesktopFishField源码片段](DesktopFishField-source-excerpt.txt)明确使用fillMaxWidth和对称8dp padding；[Git blob/原始hash](field-source-identity.json)证明片段来自未改的当前源码。结合实际内部72×19、完整layoutInfo 88×35与容器位置，可**派生**带边框输入区屏幕LTRB为(550,713,638,748)，与按钮间隔8px、垂直重叠32px。该外框是由运行时观测和未改padding定义还原，不冒充直接打印的外层coordinator矩形；直接观察到的88dp容器间距本身已为8px。

未证明产品分页布局违反相邻性要求，也未证明完整产品布局已合格。下一轮最小范围仅限 `assertPagerLayout()` 的测量辅助：用同基准的88dp输入容器/真实外框和按钮外框验证12px；SetText节点继续用于编辑和键盘操作。保持全部阈值、宽度、顺序及操作断言，不改生产FlowRow或控件。**本轮未实施该修复，不进入R6C。**

## NOT REACHED / NOT TESTED、资源与保全

- 720/360dp、拖动、滚轮、页码Enter/点击、未知total及Int边界均NOT REACHED；失败在首个几何断言组、Robot之前。R5输入准入PASS仅为历史结果，本轮未重跑。NativeUx整类、其他聚焦、bounded、distribution和Windows人工均NOT TESTED / NOT STARTED。
- 真实Fish GET0/TTS0、mock HTTP0、绑定0；仅一次内存合成目录调用。fixture finally没有owner恢复、窗口dispose、scope/runtime排水或mp3/part清理断言失败，未产生试听音频。结束后Java/javaw为0；这不冒充实包Launcher/JVM退出0/0或正式根锁重获。
- 预检可用物理内存1470492672 bytes、Commit余量15617155072 bytes；运行期最低可用375930880 bytes（约0.350GiB）、最低Commit余量14337585152 bytes，最高pages/s8161。存在明显内存压力，未观察到OOM；未将它归因为本次16px/12px RED。完整[统计](stage-summary.json)及原采样保留。未杀用户进程、清缓存或改pagefile。
- 事后证据解析脚本初版有一个未闭合括号，Python在执行前报SyntaxError；仅修正该证据工具，未触碰Kotlin或重跑Gate。[工具说明](tooling-note.json)及原脚本保留。
- 三份Kotlin草稿继续未提交/未暂存；四份user-owned文件保持UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED。[R3–R6A历史证据树](history-preservation.json)均未改。没有改CURRENT、Prompt、schema、runtime、HTTP、SecretStore、baseline或parity；WIN-AUTO-001/WIN-BRAND-001继续OPEN。无S3/G2/P9/P10/P11后续实施、merge或release。

**R6B GEOMETRY EVIDENCE COMPLETE / TEST RED PRESERVED / PROJECT REVIEW PENDING**。已取得故障来源证据，工程Gate仍HOLD；STOP交Project独立复审。

静态检查：文档链接、生产草稿diff-check及排除原始log/patch后的证据检查PASS；完整原始证据检查exit2。raw-diff-check.log保留原始空白告警，不为检查GREEN改写日志或补丁。manifest逐项校验，暂存blob与原始磁盘字节逐项一致，只有新证据目录进入提交。
