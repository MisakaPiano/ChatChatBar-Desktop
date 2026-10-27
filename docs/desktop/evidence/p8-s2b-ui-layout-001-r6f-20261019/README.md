# P8-S2B-UI-LAYOUT-001-R6F — HOLD / NEW NATIVE INPUT RED PRESERVED

本目录为独立 R6F 工程证据，日期使用 Asia/Tokyo 2026-10-19，原始执行时间保留时区。没有修改 Kotlin、测试断言或系统安全配置；没有建立源码候选提交，没有打包、Windows人工验收、merge或release。

## Git、源码及用户文件

起点 `1bb342f3228c0b054a3708dc770c400aa595cd03`，parent `76f527418616e90ed469cabcf0b78c9530563b2f`。开始前 fetch，local/tracking/live feature一致。origin/desktop仍为 `5ef95428421f7c693b010ac88f51d470804ab07a`，本地旧desktop `4c2af2576e2319e8041ffd2a6c5a205450f18246`未更新。正式validated upstream仍为1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。

三份草稿在各次preflight和结束后保持原始bytes一致，见 [源码身份](source-identities.json)、[开始检查](GATE0-preflight.json)、[POST检查](POST-preflight.json)及tested-sources/：

| 文件 | bytes | SHA-256 |
| --- | ---: | --- |
| DesktopFishPickerPresentation.kt | 17883 | `194d6b7fd2e270ca29eb90734303c8a7898825ecf8939cc60ab266e8d16c4917` |
| DesktopFishPickerWindow.kt | 4280 | `a003e7ad1e31a0c356df1a4291cc7eb1e4eb9fa958a0a6353ee6a77ac8f88125` |
| DesktopFishPickerNativeUxTest.kt | 46006 | `fdddf82225d120777c96fd8127f1584f4139c2767e5bbf0436c12db31783d238` |

[完整冻结差分](frozen-source.patch)及[来源/hash](frozen-source-patch.json)是证据副本；两个base之间只增加R6E证据，三文件草稿差分未变。源码仍未暂存、未提交：两份tracked修改+88/-35，新增Native测试655行仍为未跟踪工程草稿。

四份user-owned个人Markdown/历史JPEG仅登记路径，KEEP / UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED；未读取内容/哈希，也未移动。不能称整个工作区clean。

## Gate 0 — 有限只读环境诊断

[完整脱敏环境](gate0-environment.json)、[系统目录交叉检查](system-directory-crosscheck.json)、[判断边界](gate0-assessment.json)记录：

- working directory为 `H:\ChatChatBar-Desktop\app`。SystemRoot/ComSpec与.NET系统目录吻合；PowerShell解析cmd.exe为Windows System32版本。JDK bin、测试工作目录、系统目录和PATH中存在的候选只发现System32与SysWOW64两份系统cmd.exe，没有发现同名遮蔽或不可信替代。
- 两份均为正常非reparse PE文件，Microsoft Catalog签名Valid。System32版本289792bytes、SHA-256 `badf4752413cb0cbdc03fb95820ca167f0cdc63b597ccdb5ef43111180e088b0`；SysWOW64版本236544bytes、SHA-256 `36fffab256a48c6fb76a4d1199193195e7707e9019414ac87572c3dbc810bc6c`。不下载、替换或复制系统可执行文件。
- ACL摘要为Windows系统principal所有者、6条规则、显式deny Execute规则0；这不等于完整证明所有安全层的有效执行权限。当前诊断进程为64-bit pwsh，administrator role未启用；没有RunAs、UAC/token变化或管理员重启。
- JDK17 / Temurin17.0.20.1、Gradle9.1.0、toolchain17、既有2048MiB heap和runner工作目录已记录。worker环境只声明配置/继承依据，没有修改worker或伪造进程内部环境探针。未输出完整PATH、全环境变量、账户信息或Secrets。
- 只查询R6E bounded时间窗内AppLocker、CodeIntegrity及Defender的限定拒绝/audit事件ID，每channel上限20。三项返回NoMatchingEventsFound。没有获得能解释error=5的事件；不能据此宣称所有安全保护从未介入。没有读取无关事件正文或修改安全机制。

只读helper第一次在布尔字面量`True`处出错，尚未进入测试。保留[原helper](gate0-attempt1.ps1)和[工具报告错误摘录](gate0-attempt1-error.json)，后者明确标注第一次完整raw stderr文件不可用，未冒充完整原始stderr。仅修正helper的布尔字面量后补齐诊断，[第二次stdout](gate0.stdout.log)、[stderr](gate0.stderr.log)及[执行记录](gate0-execution.json)保留。该helper修正不是Kotlin/测试修复，不增加Gate A调用。

未发现明确可执行文件或权限安全阻塞；这不是R6E底层拒绝根因已查明。

## 本轮两个Gate，各一次

| Gate | 实际新执行 | 结果 | exit |
| --- | --- | --- | ---: |
| A — junction单方法 | 1 suite / 1 test | 1 PASS，0 FAIL/errors/SKIPPED；方法9.529s | 0 |
| B — 原bounded | 159 suites / 1526 tests | 1523 PASS，1 FAIL，0 errors，2历史SKIPPED | 1 |

[统计](stage-summary.json)、[160份新XML账本](junit-ledger.json)独立记录原始bytes/SHA-256及方法结果。每个stage包含唯一command.txt、完整stdout/stderr、exit、process身份和resource采样。没有测试重试、clean、rerun-tasks或放宽断言。

Gate A确实匹配原`DesktopImageClipboardFilesTest.junction*`，未mock、skip或改用替代shell。原方法在Windows执行mklink /J，随后原剪贴板复制、目标bytes不变、junction NOFOLLOW存在、目标目录count1，以及link finally移除和外层fixture清理路径均完成，没有异常。依据为未改方法无early-return路径且JUnit PASS，详见 [junction验证](junction-verification.json)和[源码摘录](junction-source-excerpt.txt)；没有独立注入文件系统探针。9.529s是完整方法耗时，不能当作单独ProcessBuilder启动耗时。

Gate B前另做[资源/身份preflight](B-preflight.json)，然后使用原[bounded脚本](bounded.init.gradle)，[原始字节来源/hash](bounded-policy.json)不变。只有原四项排除：WindowsBackgroundViewerTest、WindowsStudioEdgeDragTest、WindowsWindowChromeTest及DesktopImageWindowPresentationTest的short-tool单方法。没有第五项，不排除ClipboardFilesTest或NativeUx。

## junction复验与R6E历史RED分开

| 执行 | ClipboardFilesTest全类 | junction方法 |
| --- | --- | --- |
| R2历史 | 17/17 PASS | PASS 0.038s |
| R3历史 | 17/17 PASS | PASS 0.039s |
| R6E历史 | 16 PASS /1 FAIL | CreateProcess error=5，33.685s |
| R6F Gate A新运行 | 只执行1方法 | PASS 9.529s |
| R6F Gate B新运行 | 17/17 PASS | PASS 0.039s |

[历史来源及每份XML hash](historical-junction-references.json)分别指向原R2/R3成功XML和R6E失败XML，没有复制后重分类。测试源码Git blob三轮及当前一致：`5ffa98db6c83d7fa181bd65e35724804d496676d`。

新 [Gate A XML](01-junction-isolated/junit/TEST-com.example.chatbar.desktop.DesktopImageClipboardFilesTest.xml) SHA-256 `5ded2d40046c09a82492da3263a66af7bfe2a54ba356b545d2f3a638f577f7dd`；新 [Gate B Clipboard XML](02-bounded-desktop/junit/TEST-com.example.chatbar.desktop.DesktopImageClipboardFilesTest.xml) SHA-256 `d9a0725659fa8d83f8f7e89edb4ed53d577a0cafae6af201d4df1b5de20f6771`。

**R6E error=5根因：UNKNOWN。** 本轮两次实际PASS是复验结果，既不抹去R6E原FAIL，也不证明其底层原因。未归咎于Fish UI、内存或WIN-AUTO-001，未更改安全策略以取得PASS。

## 新Native RED — OBSERVED / 后续分支NOT REACHED

`DesktopFishPickerNativeUxTest.native persistent scrollbar drags current page and explicit page jump preserves request semantics`在`DesktopFishPickerNativeUxTest.kt:470`失败：

`Exactly one physical Press must arrive at owned window expected:<1> but was:<0>`

[原始Native XML](02-bounded-desktop/junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml) SHA-256 `839784aac5c6549119c42da46cd5c4b757f369212b6cdad96d459f4905851842`；[完整异常栈](failures.json)、[原始system-out](native-system-out.txt)、[输入/几何记录](native-input-observation.jsonl)、[已到达范围](native-first-failure-reached.json)均保留。

- 首个1100dp窗口实际frame(48,48,1100,720)，client1084×681，DPI scale1.0；失败时active/focused=true。1100dp分页外框几何检查已经通过，之后进入dragThumbFromTop。
- Thumb为(1120,155,8,104)。三次稳定采样及Press前记录均为计划坐标=真实指针坐标(1124,207)，位于Thumb内部。没有使用重新定位机会。
- 但目标owned-window观察器记录的MOUSE_PRESSED为0，原断言正确保留并FAIL。这些指针坐标不等于证明Press已送达目标控件；也不证明滚动条产品有缺陷。
- finally记录了release尝试及最后快照：range0、query1、HTTP0、binding0。断言发生在16步Drag之前；本方法Drag、滚轮、页码跳转、720/360dp及unknown-total/Int边界均 **NOT REACHED**。不能借R6D/R6E旧PASS填作本轮PASS。
- NativeUx全类本次为3 PASS /1 FAIL；Escape、Stop、resize/cache三个方法实际PASS。不是4/4 PASS，不满足源码候选条件。

底层输入送达/观察原因 **UNKNOWN**。没有在R6F增加诊断手势、重试、扩大命中区、修改observer/Robot逻辑或使用语义滚动替代。没有将该新RED自动归为WIN-AUTO-001或与R4历史失败同根因。

首次控制台RED后立即停止扩大范围；仅等待已经启动的同一次bounded及finally结束，没有强杀JVM。没有运行源码checkpoint helper、建立源码候选或打包。

## 历史PASS及SKIPPED

R6E Fish聚焦3/15、受影响回归8/82对应完全相同冻结源码，[复用来源/hash账本](reused-r6e-focused-evidence.json)明确`newR6fExecution=false`。本轮未单独重跑这两组聚焦命令；相关方法在新bounded中的执行另按本轮bounded XML计数。引用的旧聚焦XML仍是历史PASS，不代替本轮Native新RED。

[本轮SKIPPED](skipped-tests.json)仍只有两项，[分类](skipped-classification.json)无新增未知SKIPPED：

- DesktopDocumentIndexTransactionInspectorTest — `real symlink requires executable platform fixture and is refused without following`。
- DesktopIndexStartupPreflightTest — `real symbolic links in entities and domain are refused when supported`。

它们保持SKIPPED，旧symlink NOT TESTED不提升。R3–R6E所有原RED/XML/manifest和人工记录tree身份未变，见[历史保全](history-preservation.json)；R2 XML也仅引用原件。Gradle资源生成任务SKIPPED不与JUnit跳过混算。

## 资源、请求和收尾

Gate A前可用物理1.07–1.25GiB、Commit余量约13GiB，pages/s1601/1502/35，Java0；Gate B前1.29–1.38GiB、Commit余量约13.6GiB，pages/s60/0/0，Java0。没有新增3/8GiB门槛，保持JDK17、offline、no-daemon、no-parallel、single worker、low priority、Kotlin in-process和原2048MiB heap。

实际执行采样：A最低可用物理615669760bytes（约0.573GiB）、最低Commit余量12807663616bytes、最高pages/s20505；B最低419123200bytes（约0.390GiB）、最低Commit余量12846755840bytes、最高pages/s1363。内存压力存在；未观察OOM，不把它推断为Press0或R6E error=5的原因。[POST](POST-preflight.json)三次采样可用物理约2.18–2.21GiB，Java/javaw0、pages/s7/0/0；没有遗留本轮Gradle/应用进程。

[请求分账](request-accounting.json)：真实Fish **GET0/TTS0**，不读真实凭据/用户数据；人工fake服务未启动。Native已PASS的Stop/resize子集确认loopback mock样本GET5/TTS POST5，共10；失败分页夹具最后快照HTTP0/query1/binding0，其后断言未到达。B中7个HTTP状态诊断各记录1次物理请求，503 guard阻止follow-up、凭据头泄漏0。没有全套集中packet计数，不能把这些子集冒充全部Mock总数。

[清理/子进程](cleanup-processes.json)保留各stage原始process记录。按实际方法及首个失败fixture路径推导Native执行18个fixture（8 Escape+8 Stop+1 resize+1 pagination），各finally进入；原Press异常没有被cleanup异常替换，没有新增cleanup RED。自己owner恢复、窗口关闭、scope/runtime drain和缓存无MP3/part断言未改动。未做独立临时目录事后全量扫描，空fixture目录可能保留，没有广泛清理。

没有杀用户进程、修改pagefile、ACL、UAC、Defender/AppLocker/WDAC、注册表或系统配置；没有在用户数据目录建junction。原方法仅操作自己的合成fixture。原生自动化输出状态不替代用户听觉签署；新实包Launcher/JVM/正式根锁Gate全部NOT STARTED / NOT TESTED。

## 证据提交与停止

仅本目录允许显式暂存、独立commit/push，直接parent应为起点 `1bb342f3228c0b054a3708dc770c400aa595cd03`。三份源码草稿未暂存/未提交；四个用户文件继续保护。[Skill复核](skill-review.json)无本轮引起的技能事实过时需要修改。[范围复核](scope-review.json)保持无Prompt、Package/Entity/schema、Main生命周期、Fish真实HTTP/runtime/player/store/SecretStore/binding、CURRENT、Parity或baseline更改。

元数据/源码副本静态diff-check、链接检查及完整原始证据的空白警告在 [静态结果](static-validation.json) 和 [原始检查输出](diff-check-raw.log)。原日志/XML/补丁的空白不为绿色检查而裁剪。manifest记录每个成员的原始bytes及SHA-256（排除自身），提交前逐项核对staged Git blob与磁盘字节一致。

**当前不具备恢复源码候选提交或Windows实包Gate条件。** 交Project复审新Native Press0证据，R6F停止；不自行启动下一轮诊断/修复。WIN-AUTO-001、WIN-BRAND-001仍OPEN。不开始S3/G2/P10/P11、不sync upstream、不merge desktop、不发布、不关闭UI-LAYOUT-001。

**P8-S2B-UI-LAYOUT-001-R6F HOLD / RAW RED PRESERVED / PROJECT REVIEW PENDING**
