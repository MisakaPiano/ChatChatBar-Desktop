# R6I-A — Human Physical Mouse Control 准备证据

**R6I MANUAL FIXTURE READY / USER ACTION REQUIRED**

仅完成准备和一次编译。**R6I-B 未启动，人工输入 NOT_EXECUTED，用户未签署。** 没有启动合成窗口、loopback服务或Robot测试，没有新的Win32输入记录。编译PASS不等于物理拖动PASS。

## 固定身份与范围

- HEAD / tracking / live：`8f9a96a9432a350f0d049db3d01613a8da88b6b5`。
- Parent：`06945846e970fd1ea608a3c2daf5ded71393298b`。
- Branch：`feature/phase8-fish-audio`。
- `origin/desktop`：`5ef95428421f7c693b010ac88f51d470804ab07a`；本地旧 `desktop` 保持 `4c2af2576e2319e8041ffd2a6c5a205450f18246`，没有更新或切换。
- 正式validated upstream仍为ChatBar1.4.1；baseline/parity不改。
- 两份生产草稿SHA-256保持任务预期。NativeUxTest起点 `933d8e8f6e20ce83fe5d61e66f57f925b6f133b090b4d75ba776a873c5f26bb8`；准备后 **70707 bytes**，SHA-256：`8327487396a25a5fdf39463a7846e447378a1cab2726f8dd87a0c69c2be23706`。
- 唯一工程增量位于NativeUxTest：**+211/-4**。新增显式main、human-only状态/观测和受默认null保护的Fixture手动模式；未编辑生产Presentation/Window、Gradle正式配置或其余源码。

[起点身份](initial-identities.json)、[准备后身份](prepared-source-identities.json)、[精确增量](manual-runner-only.patch)、[范围/skill复查](delta-and-scope.json)。baseline-sources与prepared-sources保留原始字节副本。

## 唯一实际执行 Gate

[原始命令](01-compile-only/command.txt)、[stdout](01-compile-only/gradle.stdout.log)、[stderr](01-compile-only/gradle.stderr.log)、[退出/时间](01-compile-only/stage.json)。

执行 `:desktopApp:compileTestKotlin`，加载build目录内的隔离init脚本用于验证配置；JDK17、offline、no-daemon、no-parallel、single-worker、low-priority、Kotlin in-process、原2048MiB heap。

**PASS / exit0，51.9576901秒**；实际执行compileTestKotlin。任务图不包含`:desktopApp:test`或`:desktopApp:runR6IHumanMouse`，没有运行JUnit、原Robot方法或手动main。未生成本轮JUnit XML；没有用历史XML填充。日志中resource accessor生成任务的SKIPPED不是JUnit测试SKIPPED。

[编译及发现审计](compile-and-discovery-audit.json)、[编译前静态审计](precompile-static-audit.json)、[javap原文](compiled-test-javap.txt)：编译后仍有**4个org.junit.Test注解**，另有独立public static main。原四个JUnit方法所在完整源码后缀逐字节相同；原nativeHitTest、Robot驱动、分页断言及dispose段也逐字节相同。默认Fixture的human=null分支保留原路径；这属于源码与字节码静态核对，**没有新跑四项JUnit证明行为PASS**。

## 已准备的手动入口（未执行）

[临时init脚本](manual-runner.init.gradle)仅注册独立JavaExec任务，不挂接test/check/bounded；main另有专用参数和系统属性门禁。必须显式指定`-Pr6iHumanStart=true`及专用任务，才能在获用户开始指令后启动。正式build.gradle.kts未修改。

[R6I-B启动命令候选](B-command-NOT-EXECUTED.txt) **仅保存，未执行**。启动前应重新核对三文件哈希、Git和资源。禁止未经用户明确开始就运行该命令。

设计范围：1100dp的原Fixture/ComposeWindow/Shape、20条合成音色、total120。窗口标题明确写“R6I 手动物理鼠标 · 合成音色库（非正式 CCB 数据）”。不使用原ready()的toFront/requestFocus，不调用click/key/text/drag/wheel测试驱动。Fixture保留原Robot构造属性，但手动调用路径不调用任何Robot输入方法；没有SendInput、PostMessage、合成AWT事件、语义滚动或直接修改LazyListState。

手动专用控制目录固定在本轮build/manual-control下，目前**尚未创建**。显式启动后以CREATE_NEW的started.lock阻止意外第二次运行；等待启用最多120秒，等待一次手势最多120秒。正常取消可以关闭该合成音色库、按Escape或由操作者写入cancel.request；不会合成Release。如果用户仍物理按住鼠标，finally只读等待最多10秒供用户释放，再按原Fixture路径排水。

### R6I-B 后续操作顺序（本轮不执行）

1. 用户明确表示准备开始后，才启动一次runner。用户自行激活合成窗口，不由Codex强制置前或移动鼠标。
2. 确认窗口可用后，由操作者在隔离控制目录创建arm.request开启记录。此前激活点击不计作手势；启用后发生在列表/滚动条外的激活点击也明确标记excluded，不冒充thumb Press。
3. 用户在音色列表右侧**实际thumb**按住左键，向下拖动足够距离后释放；只做一次受控手势，不要求反复寻找命中点。
4. 获取原始AWT Press/Drag/Release、事件坐标、源组件、thumb命中、只读Win32 HWND/foreground分类、range与首项前后值；用户再单独描述实际体验。用户文字签署与自动分类分开。

事件只记录owned目标窗口内动作。对外部窗口只保留分类，不输出其标题、命令行或内容。进入手势后的失焦/原生目标不符会保留FOREGROUND_OR_OCCLUSION；探针/观察器异常或数据缺失为INCONCLUSIVE，不自动重试。最多1024个手势事件，防止无限记录。每次手势最多一次Press；PHYSICAL_INPUT_PASS要求真实Press/Drag/Release、正确目标、range增加和首项改变，并保持目录查询1、HTTP0、binding0。明确thumb未命中、正确送达仍无滚动等结果分别分类，不能仅凭用户文字或一个range值判PASS。

这些是**已编译的设计**，取消、超时、真实鼠标与Win32观察尚未执行验证；不填作运行时PASS。

## 网络、资源、收尾与保全

目录仅使用inline合成数据，音色没有sample URL。Fixture使用InMemoryDesktopSecretStore合成字符串；TTS固定loopback endpoint，样本transport也重映射loopback。手动入口不调用生成或播放，mock dispatcher拒绝所有HTTP。正式Fish服务/真实Key路径不接入。R6I-A未创建Fixture，实际synthetic catalog0、mock HTTP0、真实Fish **GET0/TTS0**。

[INITIAL preflight](INITIAL-preflight.json)、[编译前preflight](PRECOMPILE-preflight.json)、[编译后preflight](POST-preflight.json)、[资源汇总](resource-summary.json)、[逐次资源采样](01-compile-only/resources.jsonl) 保留实际记录。编译后Java/javaw和相关进程为空；没有启动人工窗口，所以fixture关闭/owner恢复/音频清理均为NOT TESTED，不用编译进程退出冒充实包验收。没有杀用户进程、改pagefile或清缓存。

[历史树保全](history-preservation.json)核对R3–R6H均未改。四份user-owned文件仅列路径，KEEP / UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED；三份工程草稿仍未暂存/未提交。没有证据提交或源码提交，HEAD不变；R6I-A证据仅保存在本机，待真实阶段完成或阻塞后按授权仅提交独立证据。

[阶段分类](phase-status.json)、[阶段账本](stage-ledger.json)、[静态验证](static-validation.json)、manifest.json保存本次准备证据成员长度与SHA-256。原始stdout/stderr不修剪，manifest排除自身；后续R6I-B必须另存原始运行文件，不能把准备阶段改写为已人工执行。

证据静态helper首次因未接受Git no-index的组合退出码3而中止，见[首次检查保全](metadata-verification-first-attempt.json)。第二次将Git的LF/CRLF转换提示也误算为源码空白错误，见[第二次检查保全](metadata-verification-second-attempt.json)；[第三次检查](metadata-verification-third-attempt.json)确认即使命令级core.autocrlf=false，仓库eol属性仍发出该通知。最终严格分离stdout空白检查与已知stderr换行通知，未知stderr仍拒绝，全部通知原样保留；未改Git全局配置。原始Gradle stdout的尾空格提示也保留原文。这些仅为build/evidence内helper修正，没有修改工程源码、重跑编译或执行GUI测试。

**STOP。** 等待用户明确允许启动R6I-B。不启动手动runner、不移动鼠标、不运行Native整类/bounded、不打包、不建源码候选、不合并/发布。历史Press0原因继续UNKNOWN；即使日后物理输入PASS，也不能撤销历史RED或自动宣布UI-LAYOUT-001关闭。WIN-AUTO-001和WIN-BRAND-001继续OPEN。
