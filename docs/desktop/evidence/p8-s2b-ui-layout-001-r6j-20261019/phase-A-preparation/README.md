# R6J-A — Human Recorder Handshake Preparation

**R6J-A HANDSHAKE READY / USER ACTION REQUIRED**

仅完成test-only记录器协议修复、编译和无窗口确定性检查。**R6J-B未授权启动；未创建人工窗口、实际run ID、HWND或真实输入记录。** 不把状态机检查PASS写成人工Gate PASS。

## 身份和范围

- HEAD / tracking / live：`e0bd3c1e48a2cf2849f6eb4e5600b84bbb39e45c`；parent：`8f9a96a9432a350f0d049db3d01613a8da88b6b5`。
- Branch：`feature/phase8-fish-audio`；`origin/desktop`：`5ef95428421f7c693b010ac88f51d470804ab07a`。本地旧desktop保持`4c2af2576e2319e8041ffd2a6c5a205450f18246`，没有更新或切换。
- 正式validated baseline仍ChatBar1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`，未提升parity。
- 两份生产草稿SHA保持预期。唯一工程编辑为NativeUxTest中的人工runner/HumanMouseControl/协议及确定性检查入口，增量**+318/-79**。
- NativeUxTest起点70707 bytes / `8327487396a25a5fdf39463a7846e447378a1cab2726f8dd87a0c69c2be23706`；准备后**86411 bytes** / `32777017563fc625d3678a9acbde3b18d35262178ea8afdbbb15aa1984ccde77`。

[原始身份](initial-identities.json)、[准备后身份](prepared-source-identities.json)、[精确diff](handshake-only.patch)、[范围/skill复查](delta-and-scope.json)。baseline-sources与prepared-sources保存三文件原始字节；这些是证据副本，三份工程草稿继续未暂存、未提交。

## 新协议

### 启用：WINDOW_VISIBLE不等于ARMED

唯一合成Fixture创建后，先开启已有AWT事件收集，再等catalog/list/thumb布局完成；立即读取真实range、first item、thumb、catalog/HTTP/binding计数、owned HWND、logical owner HWND及listener身份。range必须0；非零会保留实际值并明确输出`PREARM_BASELINE_INVALID`，不调用ScrollBy、不直接改LazyListState、不伪造基线。

只有真实基线全部有效，HumanProtocol才进入ARMED。独立armed.txt包括run ID、状态、initialRange、first、thumb、计数、owned/logical-owner HWND、recorderId、listenerRegistered、ownerDisabled和windowShowing。文件以FileChannel.force(true)完成写入后原子发布，随后输出`FISH_R6J_ARMED`。WINDOW_VISIBLE、命令启动、文件写入请求本身均不放行；旧arm.request等待流程已移除。

[操作端校验器](verify-human-handshake.py)必须读取**本次UUID run ID对应的armed.txt和同run ID ARMED日志**，确认range0、有效thumb、listener/owner及计数，再返回`dragInstructionAllowed=true`。缺文件/日志、旧run ID、不合格基线、终止状态均拒绝。Codex在真实R6J-B必须实际执行该核验并看到成功后，才能发出拖动指令；不能只凭当前源码或本次合成检查发指令。

用户标题栏激活点击会排除于滚动条手势；只记录owned目标，外部窗口只保留分类。真实Press须命中初始thumb，至少一条正确左键mask的Drag和一次Release，并有Win32/AWT证据、range增加与首项变化及计数不变量。Win32与AWT不是原子采样，Press/Release事件位置与稍后原生指针不一致时保留INCONCLUSIVE，不推断不存在的目标命中。

### 完成：持久化，再等待用户确认

Release后最多2秒观察状态，要求连续3次range/first一致。完整采集后先持久化capture-complete.txt并输出`FISH_R6J_CAPTURE_COMPLETE`，然后进入HELD，**不因捕获结束立即dispose**。

操作端读取同run ID的CAPTURE_COMPLETE文件和日志后，先询问用户是否完成、列表是否正常滚动；用户明确确认后，才写入内容为**同一run ID**的finish.request。HELD状态拒绝其他run ID的finish；ARMED状态也不接受提前finish。取消、用户主动关窗或确认超时保留真实结果，不伪造签署。

PREARM失败或内部异常会保存阶段、类型/安全stack frames、已取得数据和recorder-error记录。窗口仍可用时进入同样有界的确认等待，操作端应告知“记录器异常，勿继续操作”，而不是当产品崩溃或要求重拖。所有路径仍在finally使用原Fixture.dispose与server.close；该原dispose未改。

等待上限：窗口准备10秒、一次手势120秒、状态稳定2秒、确认120秒、物理左键释放辅助等待10秒。不合成Release，不无限驻留，不为通过而重新定位、补点或重试。实际窗口关闭/资源排水尚待R6J-B验证；本轮只证明逻辑决策和已有finally路径的静态结构。

## 实际执行结果

| 阶段 | 本轮实际结果 |
| --- | --- |
| compileTestKotlin | PASS，exit0，62.5496476秒 |
| 纯headless协议/持久化检查 | **16/16 PASS**，exit0，31.3115594秒 |
| 操作端握手校验器自检 | **11/11 PASS**，exit0 |
| 原NativeUx四项JUnit / Robot / bounded | 未执行 |
| R6J人工Window / run ID / Win32 / 用户签署 | NOT EXECUTED / NOT TESTED |

[编译命令](01-compile-only/command.txt)、[编译stdout](01-compile-only/gradle.stdout.log)、[编译stderr](01-compile-only/gradle.stderr.log)、[headless命令](02-headless-handshake/command.txt)、[headless stdout](02-headless-handshake/gradle.stdout.log)、[协议检查清单](deterministic-checks/checks.txt)、[操作端自检](operator-handshake-checks.json)、[操作端原始命令/退出](03-operator-guard-checks/stage.json)。

16项覆盖：无ARMED不能提示、非零基线保留/拒绝、listener/owner/count要求、同run文件和日志、重复arm拒绝、未arm/缺Release/不稳定不能完成、捕获后保持窗口、匹配finish、取消、超时、用户主动关闭、异常后等待及强制flush/不覆盖发布。11项覆盖缺文件、缺日志、旧ID、非零基线、无效listener/thumb/owner、已结束、仅WINDOW_VISIBLE拒绝和匹配确认通过。

**所有检查里的矩形/ID均为明确的合成逻辑夹具，不是实测HWND或thumb。** deterministic-checks下的armed.txt明确标记`SYNTHETIC_ARMED_NOT_RUNTIME`，也不在真正manual-control目录中，不能用于给用户放行。headless入口在构造测试实例/Fixture/Robot/Toolkit/Win32之前返回；进程设置java.awt.headless=true。main未注册JUnit，临时init脚本不挂接test/check/bounded。

[静态源码审计](precompile-static-audit.json)、[编译后发现审计](compiled-discovery-audit.json)、[javap原文](compiled-javap.txt)确认仍为4个org.junit.Test注解。原四项方法所在完整后缀、原nativeHitTest、Robot驱动、分页断言及dispose段均逐字节相同。未跑这些JUnit，不能称其本轮4/4 PASS。Gradle资源/配置任务SKIPPED也不是JUnit SKIPPED。

编译保留一条第1057行“冗余非空断言”警告，未使用suppress或降低检查，也未为消除警告重跑。实际任务图、原始输出SHA和资源见[阶段账本](stage-ledger.json)。没有新增JUnit XML，未复制历史XML当作新结果。

## 资源、数据和保全

[初始preflight](INITIAL-preflight.json)、[编译前](PRECOMPILE-preflight.json)、[检查前](BEFORE-CHECKS-preflight.json)、[执行后](POST-preflight.json)、[收尾资源](post-resource-summary.json)保留实际采样。

- 编译最低可用内存514813952B（约0.479GiB），Commit余量12989583360B，最高pages/s4676。存在压力但未观察OOM，不将其归因为历史输入问题；编译退出后再执行headless检查。
- headless检查最低可用内存1241960448B，Commit余量13746573312B，最高pages/s4318。Gradle仍使用原2048MiB heap，纯headless子进程max heap512MiB；未改项目全局配置。
- 执行后3次Java/javaw计数均0。未创建人工Fixture、mock服务或音频缓存；实际owner恢复/音频句柄/实包Launcher与数据根锁均NOT TESTED，不能冒充Windows实包验收。
- 真实Fish **GET0/TTS0**，mock HTTP0、synthetic catalog0；未读真实数据或Key，无系统代理/证书/注册表/pagefile改动，无用户进程终止或缓存/包删除。

[历史树保全](history-preservation.json)覆盖R3–R6I，包括原R6I-B INCONCLUSIVE、用户实际滚动签署与正常finally关窗记录；不改任何旧RED或manifest。四份用户文件只列路径，保持KEEP / UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED。两份生产草稿不变，Native测试草稿保留本轮变更，三份均未暂存/未提交。

证据当前仅保存本地，未创建源码或证据提交，HEAD保持固定检查点。[当前阶段](phase-status.json)、[协议限制](protocol-limits.json)、[静态校验](static-validation.json)与manifest.json保存成员长度/SHA-256；manifest排除自身。原始日志/补丁的空白不清洗。

**STOP。R6J-B等待用户再次明确授权。** 后续即使一次物理输入成功，也不追认无人值守Robot PASS、不撤销旧bounded RED、不加排除、不关闭UI-LAYOUT-001。WIN-AUTO-001与WIN-BRAND-001继续OPEN；不打包、合并、发布或进入S3/G2/P9/P10/P11。
