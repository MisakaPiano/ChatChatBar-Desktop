# R6A — GEOMETRY DIAGNOSTIC HOLD / COMPILE RED PRESERVED

## OBSERVED

- 起点、tracking、fetch后GitHub live feature均为 `f4556199aee10c87977ddaa3224574af5bf14310`，直接parent `8f36e0849413300638880ccf2276a92eb0b6e989`。活动分支为 `feature/phase8-fish-audio`，最初index为空。
- `origin/desktop`及live desktop均为指定 `5ef95428421f7c693b010ac88f51d470804ab07a`。本地desktop旧引用为 `4c2af2576e2319e8041ffd2a6c5a205450f18246`，仅落后11提交、无分叉；未切换或移动它。该差异明确记录于[preflight.json](preflight.json)，未默认为本地已对齐。正式baseline仍为1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。
- 三份初始草稿原始长度/hash均匹配R5及用户给定身份；[原R5身份](baseline-source.json)、原始副本baseline-sources/和[保全核对](source-preservation.json)保留。两份生产草稿逐字节未改。
- 唯一工程改动是在Native测试的 `assertPagerLayout()` 中、第一条几何断言之前增加49行诊断。移除该插入后与R5测试文本完全一致，所有原断言、12px阈值、1100/720/360dp顺序、Shape及Robot逻辑未改。[完整增量](diagnostic-only.patch)、[诊断代码](diagnostic-source.kt)及[原始身份](diagnostic-identity.json)保留；新测试原始37838 bytes，SHA-256 `991a2c839db3dd09ca9a2d1c622dd496d57a444bee33fb51a8ad1a546fe78a3b`。
- 只调用一次[Gate命令](01-pager-geometry-single-method/command.txt)，沿用R5 JDK17、offline、no-daemon、no-parallel、single worker、low priority、in-process、2048MiB heap；只把测试选择器限定到原失败单方法，无clean/rerun-tasks/新排除。
- **exit1 / :desktopApp:compileTestKotlin RED**。外部阶段31.1470164s；生产compileKotlin UP-TO-DATE。测试未开始，实际0 suites、0 tests；这不是测试PASS或分页断言再次失败。没有新的JUnit XML，XML SHA-256为null，未拿旧R5 XML补位。
- [原始stderr](01-pager-geometry-single-method/gradle.stderr.log)记录第264行无法解析 `positionOnScreen`、`positionInWindow`，第267行无法解析 `id`。这些均是新增诊断对 `n.root` 的访问。[实际公开API签名](semantics-node-public-api.txt)证明 `SemanticsNode.getRoot()` 返回 `RootForTest`，不是 `SemanticsNode`。这是本轮诊断代码自身的类型假设错误，不是产品分页布局证据。
- [原始stdout](01-pager-geometry-single-method/gradle.stdout.log)、进程、资源与退出记录完整保留。RED后没有修正诊断源码、再次编译或运行方法；未开启其他Gate。
- 预检可用物理内存2431066112 bytes、Commit余量16710369280 bytes、pages/s0、Java进程0。运行期最低可用1532669952 bytes，最低Commit余量15577219072 bytes，最高pages/s2097，未观察到OOM；[统计](stage-summary.json)及原始resources.jsonl保留。结束后Java/javaw为0。没有杀用户进程、改pagefile或清缓存。

## UNKNOWN / NOT REACHED / NOT TESTED

分页根因分类：**UNKNOWN**。本轮没有执行到1100dp或任何分页节点；实际输入框/按钮矩形、水平间隔、重复语义节点、坐标基准差异及是否为产品缺陷，均未取得新观察。`FISH_R6A_PAGER_GEOMETRY`运行时记录数为0，见[几何状态](pagination-geometry-status.json)，不制造模拟矩形或空记录PASS。

720dp、360dp、真实拖动、滚轮、页码/Int分支全部NOT REACHED。R5输入准入PASS只是历史证据，不是本轮新PASS。完整NativeUx、Fish聚焦、bounded、distribution、Windows人工、Launcher/JVM退出0/0与根锁重获均NOT TESTED / NOT STARTED。

真实Fish GET0/TTS0、mock HTTP0、目录调用0、绑定0。fixture未启动，没有创建fixture根、窗口、音频、fake或TLS材料，也没有进入fixture I/O/关闭生命周期。[请求和进程收尾](request-and-cleanup.json)区分Gradle命令退出与未启动的应用进程。

## INFERRED / 下一轮最小范围

新增诊断的编译阻塞有直接日志/API依据；它不能替代分页根因分类。下一轮最小修复仅限 `assertPagerLayout()` 新增诊断中的三处root成员访问：使用公开parent链取得明确类型的SemanticsNode祖先，或明确取得一致的窗口屏幕原点。不得全局替换已有用于比较RootForTest身份的root用法，也不需要产品布局、阈值或输入逻辑修改。此建议未在R6A实施，须交Project另行决定续行。

## 保全和边界

三份源码草稿仍未提交、未暂存；测试草稿包含本轮49行诊断，原R5版本有完整原始副本。四份user-owned文件仅盘点路径，保持UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED；工作区并非clean。原R3/R4/R5 evidence树及工作树无改动，见[历史保全](history-preservation.json)。

本次只提交此独立evidence目录；不改CURRENT、Prompt、schema、runtime、HTTP、SecretStore、baseline或parity。WIN-AUTO-001和WIN-BRAND-001继续OPEN。无S3、G2、P10/P11、merge或release。

**R6A HOLD / COMPILE RED PRESERVED / PROJECT REVIEW PENDING**。本轮未达到可信分页几何取证目标；按编译RED停止要求保留事实，STOP交Project复审。

静态检查：文档链接、生产草稿diff-check及排除原始log/patch后的证据检查PASS。完整原始证据diff-check exit2，原始Gradle行尾空格/补丁上下文保留于raw-diff-check.log，未为消除告警改写原始证据。
