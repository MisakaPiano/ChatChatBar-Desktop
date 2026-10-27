# R6D — NATIVEUX LIMITED GATE PASS / PROJECT REVIEW PENDING

## 控制点与唯一测试修复

起点、tracking及fetch后GitHub live feature均为 `b366d3ceb019b0f78ea7ab4d3c3f19508a8389fc`，parent `46d9f89691cafd1fbb69b5e7cd3481602bafa10c`；活动分支 `feature/phase8-fish-audio`，原index为空。origin/live desktop仍为 `5ef95428421f7c693b010ac88f51d470804ab07a`，本地desktop旧引用保持 `4c2af2576e2319e8041ffd2a6c5a205450f18246`，没有切换或更新。正式1.4.1 baseline未改。[preflight.json](preflight.json)记录三份冻结草稿长度/hash与用户未跟踪文件路径盘点，全部匹配，无未知修改。

唯一工程修改为NativeUx测试 `assertPagerLayout()` 中360dp的原 `else` 分支，**+56/-1**：[完整测试修复diff](narrow-wrap-fix.patch)、[实际测试源码](tested-source.kt)、[源码身份](tested-source-identity.json)、[保全核对](source-preservation.json)。测试原始46006 bytes，SHA-256 `fdddf82225d120777c96fd8127f1584f4139c2767e5bbf0436c12db31783d238`。

把新分支换回原单行断言即可逐字恢复R6C测试文本；R6C完整外框测量、直接配对Row/FlowRow父对象身份、12px、垂直重叠、client containment和1100/720dp同排要求不变。其他全部源码、断言、16步真实输入、wheel、分页与Int逻辑保持原样。两份生产草稿原字节未改，三份Kotlin均未暂存或提交。

## 新360dp判据及实测行分配

测量对象是已经验证的FlowRow四个直接子布局项：previous、caption、输入/跳转的不可分配对Row、next。用实际LayoutInfo引用确认它们是彼此不同且共享同一直接父对象的项目，尺寸须等于实际完整布局尺寸，全部外框须在client内。配对Row公开coordinates必须覆盖整个布局项，且必须完整包含输入和跳转控件。

按实际top排序，以每组所有项目的**正公共垂直交集**归组，而非要求top相等。归组必须唯一；至少两组；各组实际外包矩形之间不得垂直重叠。没有指定哪一个控件必须换行，没有硬编码节点ID、坐标、宽度、行高或分组答案；不可靠的几何和歧义会明确FAIL。

[真实行分组](narrow-row-groups.json)和[完整原生system-out](native-system-out.txt)显示360dp在第1页和第2页均得到：

| 行 | 实际成员 | 外包矩形（屏幕x/y/w/h） |
| --- | --- | --- |
| 1 | input-and-jump、previous、caption | (68,654,316,58) |
| 2 | next | (68,716,76,32) |

第一组公共垂直区间为[675,691)，第二组为[716,748)，均为正长度；组外包之间实际相隔4px。这个分组是观测结果，不是测试中固定要求next换行。输入外框仍为(246,654,88,58)，按钮(342,680,42,32)，间距8px、重叠32px；直接配对Row为(246,654,138,58)。[六次外框观测](outer-geometry.json)涵盖1100/720/360dp及各自第1/2页，实际间距均为8px、重叠均为32px。

## 唯一Native Gate：4/4实际PASS

仅运行一次[整类命令](01-nativeux-class/command.txt)，没有先跑单方法。JDK17、offline、no-daemon、no-parallel、single worker、low priority、Kotlin in-process、原2048MiB heap；无clean、rerun-tasks、重试、新排除或新skip。

**编译PASS；1 suite / 4 tests / 4 PASS / 0 FAIL / 0 errors / 0 JUnit SKIPPED，exit0。** 原始[JUnit XML](01-nativeux-class/junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml) SHA-256为 `758d6715b46f7cc8cb2ed28f78cdb059c2109bc15804b25b746ffce217a58203`。原stdout/stderr、进程身份及资源原记录完整保留。

| 实际JUnit方法 | 结果 | 秒 |
| --- | --- | --- |
| Native滚动条、wheel与分页 | PASS | 18.042 |
| 下载/生成/长Windows播放中的Stop | PASS | 11.099 |
| 宽窄窗口/有无输入焦点的Escape层级 | PASS | 13.785 |
| resize、文本/目标保留与cache kind分离 | PASS | 2.715 |

XML合计45.641s，外部阶段104.1183146s；这两个口径不相加。四方法中的循环场景不另算独立JUnit测试，历史各轮PASS也不相加。

## Native行为和边界确实执行

- 1100/720dp完整同排检查PASS；360dp真实双行分组PASS。三种宽度都实际完成一次稳定按下、16步连续真实Drag、Release和wheel，scroll range增加、first visible item变化；共48步，实际准入重新定位0次。没有语义ScrollBy或直接LazyListState替代输入。
- 三种宽度均执行0/-1/越界7/超长页码不发查询，Enter跳至3页、点击跳至2页、切页归顶0、queriedPages=[1,3,2]及无HTTP/绑定不变量。
- unknown-total独立fixture已实际执行：允许显式Int.MAX_VALUE，Next禁用；2147483648不产生新查询，没有伪造last page。[行为到达证据](behavior-coverage.json)保留三种宽度完成快照、真实拖动/wheel快照及unknown-total/Int最终快照。
- Escape、Stop、resize/cache是本轮另外三个实际执行的测试，不是引用旧PASS。Stop包括真实Windows MCI状态及mouse/keyboard操作；这仍不替代用户实际听觉签署。

本轮无新RED、SKIPPED或新未知测试异常，所有计划分支均到达。历史R3/R4输入异常来源UNKNOWN、旧RED、symlink NOT TESTED及旧bounded的JUnit SKIPPED均不改写；本轮0 SKIPPED不等于重新验证它们。

## 资源、请求与finally收尾

运行前可用物理内存1493897216 bytes、Commit余量15061413888 bytes、pages/s0、Java0。10次运行采样中最低可用448393216 bytes（约0.418GiB）、最低Commit余量13357993984 bytes，最高pages/s23442：仍有明显压力，未观察到OOM。结束后可用2102050816 bytes、Commit余量15602814976 bytes、pages/s39、Java/javaw0。[统计](stage-summary.json)和原始resources.jsonl完整保留；没有杀用户进程、改pagefile、清缓存/旧包或增加3/8GiB硬门槛。

真实Fish GET0/TTS0。Native mock为sample GET5、TTS POST5，共10个HTTP；分页/Escape HTTP0，目录均为内存合成函数。全类目录函数调用28，其中分页11；绑定0。[请求及清理口径](requests-cleanup.json)依据已完成循环及每fixture requestCount/type断言推导，不冒充独立全局抓包。

21个合成fixture的finally均完成：owner恢复、窗口dispose、scope/runtime drain和无自有mp3/part残留断言全部通过；结束后无Java进程。空的自有临时fixture目录不被冒称全部删除。没有启动人工fake或distribution。Native fixture收尾不替代实包Launcher/JVM退出0/0、正式root lock重获或用户听觉。

## 保全、后续条件与STOP

[R3–R6C历史证据树](history-preservation.json)及工作树保持原状。四份user-owned文件仅盘点路径，保持KEEP / UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED；三份Kotlin草稿未提交，整个工作区并非clean。只有本目录进入证据提交。

NativeUx限定前置条件现已通过，可交Project判断是否恢复Fish全聚焦、bounded和Windows人工Gate；**本轮没有启动任何一个后续Gate**。下轮仍须独立授权及资源preflight，不能以本轮4/4冒充后续通过、正式源码提交或UI-LAYOUT-001最终验收。

不改CURRENT、Prompt、Package/Entity/schema、runtime/HTTP/SecretStore、parity、正式baseline或用户数据；不进行upstream sync、desktop merge、release或S3/G2/P10/P11实施。WIN-AUTO-001/WIN-BRAND-001继续OPEN，MCI设备兼容、TOCTOU和物理I/O绝对期限等既有限制不变。

**R6D NATIVEUX LIMITED GATE PASS / PROJECT REVIEW PENDING**。STOP交当前ChatGPT Project独立复审，不自动进入R6E或其他工作。

静态检查：文档链接、生产草稿diff-check、排除原始log/patch后的证据检查PASS。完整原始证据diff-check exit2，原始空白告警保留于raw-diff-check.log；不为检查GREEN改写日志或补丁。暂存blob逐项与磁盘原字节比较，manifest逐项校验。
