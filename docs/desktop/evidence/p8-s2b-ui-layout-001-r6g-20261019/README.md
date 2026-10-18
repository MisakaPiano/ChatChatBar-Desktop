# P8-S2B-UI-LAYOUT-001-R6G — HOLD / NO OBSERVED MOUSE EVENT

本目录是独立R6G执行证据。日期按Asia/Tokyo 2026-10-19，原始时间保留时区。Gate A编译通过、测试RED；没有执行Gate B、源码候选提交、打包、人工验收、merge或release。

## Git与文件保护

起点 `15d35ab8a46decdf710d09d12c9b0d3b87656444`，parent `1bb342f3228c0b054a3708dc770c400aa595cd03`。开始前fetch，local/tracking/live feature一致。origin/desktop为 `5ef95428421f7c693b010ac88f51d470804ab07a`；本地旧desktop `4c2af2576e2319e8041ffd2a6c5a205450f18246`保持不动。正式validated upstream仍为1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。

[开始与实测原始身份](source-baseline-and-tested.json)、[Gate A preflight](A-preflight.json)、[POST](POST-preflight.json)、baseline-sources/和tested-sources/保留实际bytes：

| 文件 | 起点SHA-256 | 实测SHA-256 |
| --- | --- | --- |
| Presentation，17883bytes | `194d6b7fd2e270ca29eb90734303c8a7898825ecf8939cc60ab266e8d16c4917` | 相同，生产文件未改 |
| Window，4280bytes | `a003e7ad1e31a0c356df1a4291cc7eb1e4eb9fa958a0a6353ee6a77ac8f88125` | 相同，生产文件未改 |
| NativeUxTest | `fdddf82225d120777c96fd8127f1584f4139c2767e5bbf0436c12db31783d238`，46006bytes | `8beee5971355cd894b44a4743f89b6ad857c342274ae8410cfab5e99a3bbb423`，49505bytes |

本轮唯一工程修改是Native测试的[观察器增量](observer-only.patch)，+47/-3；[完整三文件草稿](full-source.patch)与[差分hash](source-diff-identity.json)保留可追踪身份。测试运行后未再改源码。三份工程草稿均未暂存/提交，两份生产草稿继续原字节保留。

四份用户个人Markdown和历史JPEG只登记路径，KEEP / UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED，未读内容或哈希。R3–R6F历史原RED/XML/manifest/人工记录tree身份保持一致，见[历史保全](history-preservation.json)。工作区不是clean，Native测试仍为未跟踪工程草稿。

## 唯一授权修正与观察范围

MouseEvent来源是`java.awt.Window`时，新helper返回该Window自身；其他组件仍调用公开`SwingUtilities.getWindowAncestor`。只有非空`child`且`target === child`的事件可进入原receivedMouse列表，owner/其他窗口/未知来源不接纳。KeyEvent分支及原输入驱动未改。

诊断字段限于事件ID、组件类型、屏幕坐标、button/mask、身份比较、owned窗口焦点、单调sequence/nanos。OTHER_IN_JVM只在实际滚动条track诊断矩形内记录分类，不读其他应用标题、输入内容或无关事件。该矩形只控制日志范围，不改变8px控件、点击区域或输入目标。

使用真实owned窗口对象的小型确定性断言在原生输入之前执行，没有派发任何合成MouseEvent：

- 旧`getWindowAncestor(child)`为null，新方法返回child自身。
- 实际Skia子组件类型为`org.jetbrains.skiko.SkiaLayer$1`，旧/新方法均归属于child。
- logical owner不归属于child；独立未挂接Canvas无窗口，不能作为owned来源。

该断言通过证明**Window自身路径的确定性观察缺口及修正有效**；它不证明R6F那次Press确实来自Window自身，不替代真实Robot输入。

三次25ms稳定采样、最多一次重定位、单Press、BUTTON1、16步Drag、单Release、wheel、scroll range/first item、1100/720/360dp、分页/Int、12px及Escape/Stop/cache原断言全部保持。没有新增输入重试、timeout、语义ScrollBy或LazyListState写入。

## Gate A — 单次新执行

实际命令在 [command.txt](01-native-method/command.txt)。JDK17 / Temurin17.0.20.1、Gradle9.1.0、offline、no-daemon、no-parallel、single worker、low priority、Kotlin in-process、原2048MiB heap不变；没有clean或rerun-tasks。

| 项目 | 本轮结果 |
| --- | --- |
| compileTestKotlin | PASS，实际编译新测试 |
| 唯一选择方法 | `DesktopFishPickerNativeUxTest.native*persistent*` |
| JUnit | 1 suite /1 test，0 PASS /1 FAIL /0 errors /0 SKIPPED |
| 方法耗时 | 4.219s |
| Gate执行耗时 / exit | 62.3695832s /1 |
| Gate B bounded | NOT STARTED，因Gate A RED不满足条件 |

[原始XML](01-native-method/junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml)为32381bytes，SHA-256 `c013ea1007a1bd44b054889bd38f85cfa133df08ea528f30b1bdaf6c990e1cf5`。[XML账本](junit-ledger.json)、[分阶段统计](stage-summary.json)、完整stdout/stderr及[失败栈](failures.json)保留原始结果。

唯一失败位于新行514（旧相同断言在470；前方新增诊断造成行号移动）：

`Exactly one physical Press must arrive at owned window expected:<1> but was:<0>`

没有放宽或删除该断言，没有重跑。编译PASS与确定性helper断言PASS不等于整个测试PASS。

## 真实事件来源与到达范围

[原始system-out](native-system-out.txt)、[输入/归属记录](awt-ownership-observations.jsonl)及[归属结论与边界](event-ownership-result.json)保留：

- 首个1100dp窗口frame(48,48,1100,720)，client1084×681，scale1.0；失败快照active/focused=true。1100dp分页外框几何已通过，然后进入dragThumbFromTop。
- Thumb为(1120,155,8,104)，日志诊断区域为track(1120,155,8,527)。三次稳定采样和Press调用前计划/实际指针均为(1124,207)，在thumb内部；使用attempt0，没有重定位。
- 真实MouseEvent分类计数：OWNED_WINDOW_SELF **0**，OWNED_DESCENDANT **0**，诊断区域内OTHER_IN_JVM **0**。前后快照均为NO_OBSERVED_EVENT，receivedMouse0、Press0。
- 实际Press到达哪个AWT组件：**NOT OBSERVED，无法确定**。不能把匹配坐标当作事件已投递，也不能把日志范围内没有事件推断成全系统从未产生输入。
- finally执行了原Release调用和最后快照，scroll range仍0、query1、HTTP0、binding0。未到达16步Drag、wheel、页面跳转、720/360dp或unknown-total/Int边界，均为**NOT REACHED**。

**R6F历史Press0根因仍UNKNOWN。** 本次没有捕获Window-self事件，不能宣称旧盲区是R6F实际根因；也没有捕获descendant事件来证明这次真实原路径已通过。静态/确定性归属修正已验证，真实输入Gate仍RED。产品滚动条缺陷未被证明，亦未自动归为WIN-AUTO-001。

R6E error=5历史原因继续UNKNOWN，R6F junction单方法/bounded PASS保留原身份；本轮未重做进程启动诊断或junction测试。

## 历史引用与未执行项目

R6E Fish独立聚焦15/15、受影响回归82/82继续作为获准历史引用，见[原XML来源/hash](reused-focused-evidence.json)。两份生产文件未变，但Native测试存在本轮+47/-3授权修正；这些旧聚焦不是R6G新执行，不代替当前失败Native方法。

原[bounded配置](bounded.init.gradle)逐字节保留，[来源/hash](bounded-policy.json)仍只有WindowsBackgroundViewerTest、WindowsStudioEdgeDragTest、WindowsWindowChromeTest及DesktopImageWindowPresentationTest的short-tool单方法四项排除。**本轮没有执行该配置**，没有增加排除或skip来绕开RED。

NativeUx整类、bounded、junction复验、两项历史symlink SKIPPED、新Windows实包、人工Escape/Stop/拖动/听觉、新实包Launcher/JVM和正式根锁Gate全部NOT RUN / NOT TESTED。旧PASS、SKIPPED、NOT TESTED和所有历史RED不倒改。[跳过分类](skipped-classification.json)区分本轮0 SKIPPED与没有重新执行的历史项目。

## 资源、请求与清理

A前可用物理约2.26–2.33GiB、Commit余量约13.66GiB、pages/s0/37/23、Java0；H盘可用8806219776bytes。Gate中6个资源采样最低可用物理1033793536bytes（约0.963GiB）、最低Commit余量12768600064bytes、最高pages/s25307。资源压力记录保持原样，未观察OOM，不把换页压力认定为Press0根因。

[POST](POST-preflight.json)三次采样可用物理约2.29–2.33GiB、pages/s0/3113/418、Java/javaw0，无本轮Gradle/应用残留。未杀用户进程、修改pagefile、系统权限或Windows安全策略，未清缓存/历史包。

[请求分账](request-accounting.json)：真实Fish **GET0/TTS0**；本次mock HTTP0、合成catalog1、绑定0，依据原finally快照和未执行任何preview/page动作的路径。未读取真实凭据/用户数据，未启动人工fake服务。

[清理](cleanup-processes.json)：只执行首个1100 fixture，原finally返回，Press异常未被清理异常替换。owner恢复、自己窗口释放、scope/runtime drain、自己缓存无MP3/part断言保持并完成。诊断中的独立Canvas没有创建额外Window；没有派发合成Press。未进行独立事后临时目录扫描，空目录可能保留，没有广泛删除。

## 提交与停止

仅本新evidence目录允许显式暂存、commit/push，直接parent应为 `15d35ab8a46decdf710d09d12c9b0d3b87656444`。源码checkpoint helper未运行，三份工程草稿未暂存/未提交。[Skill复核](skill-review.json)不需要技能修改；[范围复核](scope-review.json)记录仅授权Native观察器修改，两份生产Kotlin及Controller/Runtime/HTTP/Store/Player/SecretStore/binding、Main、Prompt、Package/Entity/schema、CURRENT/Parity/baseline均未改。

元数据、源码副本、README链接检查见[静态结果](static-validation.json)。完整原始日志/XML/patch的diff-check空白警告保留在[原始输出](diff-check-raw.log)，不裁剪原始记录。manifest列出除自身外每个成员的原始长度/SHA-256，提交前核对staged Git blob与磁盘字节一致。

证据整理时首次inline元数据生成器出现SyntaxError，在执行前失败；随后改为明确文件脚本，仅生成元数据，没有运行新测试或再改Kotlin，见[evidence处理说明](evidence-processing-notes.json)。该错误与原始Gate A RED分开记录。

**不具备源码候选或后续Windows实包Gate条件。完成后STOP，交Project独立复审；不进入R6H。** WIN-AUTO-001、WIN-BRAND-001继续OPEN，不开始S3/G2/P10/P11、不sync upstream、不merge desktop、不release、不关闭UI-LAYOUT-001。

**P8-S2B-UI-LAYOUT-001-R6G HOLD / RAW RED PRESERVED / PROJECT REVIEW PENDING**
