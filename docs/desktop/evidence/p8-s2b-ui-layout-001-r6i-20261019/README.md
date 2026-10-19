# P8-S2B-UI-LAYOUT-001-R6I — Human Physical Mouse Differential

**R6I-B INCONCLUSIVE / MANUAL CONTROL ARMING FAILED / PROJECT REVIEW PENDING。** 仅启动一次手动合成窗口，用户确认一次真实拖动使列表正常滚动；记录器在启用前的零滚动基线检查中抛出异常，随后主动收尾关窗。未重试、未修源码，不能将本轮签作完整PHYSICAL_INPUT_PASS。

## 用户实际体验与突然关窗

用户文字签署原文：**“已拖动一次，列表正常向下滚动”**。之后用户询问：**“但是我刚拖动完整个窗口就已经被关上了，这是我不小心点到了，还是窗口崩了”**。

关窗由runner的异常收尾触发：原始日志先记录`IllegalStateException`，随后finally调用fixture.dispose，最终输出`cleanup=true`；main因结果不是PASS而退出1。没有证据需要用“用户误点关闭”解释这一过程，也没有原生窗口/JVM崩溃记录。产品窗口崩溃不能由测试runner的受控异常推导。

[完整用户文字与提问](user-signatures.json)与原始运行数据分开保存。runner原输出中的`humanSignoff=NOT_SIGNED`原样保留，因为用户回复发生在输出之后；新的用户文字签署仅记在独立记录，不倒改原始文件。

## 精确身份与执行范围

- 起点/本证据提交直接parent：`8f9a96a9432a350f0d049db3d01613a8da88b6b5`；其parent：`06945846e970fd1ea608a3c2daf5ded71393298b`。
- 唯一分支`feature/phase8-fish-audio`；执行前后local/tracking/live均为起点。
- `origin/desktop`保持`5ef95428421f7c693b010ac88f51d470804ab07a`；本地旧desktop保持`4c2af2576e2319e8041ffd2a6c5a205450f18246`。正式validated baseline仍ChatBar1.4.1，未同步或提升parity。
- 两份生产草稿与冻结SHA一致；NativeUxTest保持R6I-A准备后的70707 bytes / `8327487396a25a5fdf39463a7846e447378a1cab2726f8dd87a0c69c2be23706`。**R6I-B没有工程源码修改**。
- R6I-A唯一测试文件增量+211/-4及四个JUnit方法/原驱动/分页断言的字节不变审计保留；原工程草稿均未暂存、未提交。

[R6I-A原始准备报告](phase-A-preparation/README.md)、[A阶段原manifest](phase-A-preparation/manifest.json)、[工程身份](prepared-source-identities.json)、[源增量](manual-runner-only.patch)、[B前preflight](BEFORE-B-preflight.json)、[B后preflight](AFTER-B-preflight.json)。phase-A-preparation完整复制A阶段全部成员，并用原manifest重新核验，不将A阶段NOT_EXECUTED报告倒改为人工PASS。

## 唯一手动 Gate 与原始结果

| 项目 | 实际结果 |
| --- | --- |
| R6I-A编译 | PASS / exit0，历史准备结果 |
| R6I-B启动 | 一个JavaExec，一个1100dp合成Fixture；51.9723062秒，exit1 |
| 窗口 | Frame(48,48,1100,720)，client1084×681，scale1.0 |
| 手动runner分类 | INCONCLUSIVE；armed=false |
| 用户体验 | 确认一次真实拖动，列表正常向下滚动 |
| Robot / JUnit / bounded | 均未执行；没有新的JUnit XML |
| 再启动/重试/源码修复 | 0 / 0 / 0 |

[原始命令](02-human-physical/command.txt)、[stdout原始字节](02-human-physical/gradle.stdout.log)、[stderr/外层失败栈](02-human-physical/gradle.stderr.log)、[退出码及时间](02-human-physical/stage.json)、[执行账本与SHA](execution-ledger.json)。原始stdout严格CP936解码成功，非UTF-8；另存[UTF-8派生文本](human-stdout-decoded-cp936.txt)，原字节不转码、不修剪。

任务图仅手动JavaExec及其class/resource依赖，无`:desktopApp:test`。`compileTestKotlin`在B阶段为UP-TO-DATE；Gradle配置/资源任务SKIPPED不是JUnit SKIPPED。main/手动调用路径不调用Robot.mouseMove/Press/Release/Wheel，也未使用SendInput、PostMessage、合成AWT事件、语义滚动或直接改LazyListState。

## 实际输入与缺失证据

原始AWT监听器捕获**两条MOUSE_DRAGGED(506)**，均来自`org.jetbrains.skiko.SkiaLayer$1`，`OWNED_DESCENDANT`、`targetOwned=true`、`mask=1024`（左键DOWN掩码）：

- 屏幕(1142,501)，事件nanos167589155993500；
- 屏幕(1141,504)，事件nanos167589176530700。

每条事件有R6G和R4两种日志表示，但仅是**两个事件**，不能重复计为四条。它们发生在runner异常之后、原finally收尾期间，是部分真实输入证据。

手动记录器自身未armed，所以其Press/Drag/Release计数均0；这不代表用户没有物理输入。**缺失Press、Release、初始thumb命中和Win32 HWND/foreground/hit-test/按钮状态采样**。AWT active/focused为true不能补写成Win32前台身份PASS。

标为beforeRange的采样实际已是**1545.0**，首个可见项为**Page 1 synthetic voice 4**；这是迟到的基线，不能将其伪装成完整“0→1545”的前后测量。afterRange和afterFirst均未取得。[原始事件派生](human-events-derived.jsonl)、[观察/根因边界](observation-and-cause.json)完整记录这些限制。

因此正式分类保持**INCONCLUSIVE**：既不能判PHYSICAL_INPUT_PASS，也不能判正确命中后的产品PHYSICAL_INPUT_FAILURE、MANUAL_HIT_MISS或遮挡。历史R6F/R6G Press0原因仍UNKNOWN。

## 记录器失败机制与流程责任

[冻结源码摘录](arming-source-excerpt.kt)显示：armHumanMouse第437–438行读取range/首项；第439行要求range==0；成功后才在第441行置armed=true并输出FISH_R6I_ARMED。本轮range1545使该前置条件必定为false，实际没有ARMED或Win32记录。内部catch仅输出异常类型，没有内部完整栈；不能伪造不存在的栈。

控制文件原始时间：[ready](manual-control/ready.txt)为03:45:11.220022Z，arm文件内容为03:45:31.7955551Z，result文件mtime为03:45:32.228584Z。文件mtime计算ready到arm约20.581秒，arm到result约0.427秒；内容时间与文件mtime分别保留。没有Press/Release时间，不能重建用户具体哪一毫秒开始操作。

**操作者在写arm.request后，未等到ARMED确认，就向用户宣称“已开始记录”。** 这是本次记录流程缺口，不能归咎于用户，也不能按源码外观合理就认定实际握手成功。[时间线](control-file-timeline.json)和[后续最小方案建议](project-next-step-proposal.json)交Project评审；本轮不实施修正、不再开窗。建议下一轮若获授权，应让记录启用有明确确认，再通知拖动，保留真实基线与全部目标/事件/滚动断言。

## 请求、资源及收尾

- 合成catalog1、mock HTTP0、binding0；真实Fish **GET0/TTS0**。未访问真实数据、Key或聊天。一次内部loopback MockWebServer随Fixture关闭，没有独立fake进程。
- 5次B阶段资源采样：最低可用物理内存1414156288B（约1.317GiB），最低Commit余量12803915776B（约11.925GiB），最高pages/s5830；未见OOM或资源异常终止。不能将这些采样直接归因于输入问题。
- 原始cleanup=true，未见cleanup/server-close错误。原dispose路径完成runtime drain、scope取消join、owner恢复断言、窗口dispose、AWT监听器移除及owned临时根无.mp3/.part断言，server.close在输出结果前返回。
- B后3次资源采样Java/javaw均0，相关构建进程为空。未强杀用户进程、改pagefile或清缓存。没有生成/下载/播放音频，所以独立音频句柄/实包Launcher/根锁仍NOT TESTED，不能用Fixture退出冒充。

[资源与清理](human-resource-and-cleanup.json)、[原始资源采样](02-human-physical/resources.jsonl)、[原始控制结果](manual-control/result.txt)。

## 保全与交接

[历史证据树](history-preservation.json)及四份user-owned文件保持：KEEP / UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED；用户文件只枚举路径，不读内容或hash。R3–R6H旧RED/PASS/manifest均不修改。R6I-A证据静态helper的三次退出保全也继续保留，不倒改为编译或GUI失败。

仅将本新R6I evidence目录显式暂存、提交、正常push；无工程源码checkpoint。manifest.json覆盖所有原始和派生证据成员的长度/SHA-256，排除自身；A原manifest另在phase-A-preparation保留。元数据/源码副本静态检查与原始日志空白提示分开，原字节保持。

**STOP / PROJECT REVIEW PENDING。** 不重试本轮手势，不修生产或测试源码，不启动Robot、Native整类、bounded、打包、下一Gate、desktop merge或release。WIN-AUTO-001与WIN-BRAND-001继续OPEN，不宣布UI-LAYOUT-001关闭。
