# P8-S2B-UI-LAYOUT-001-R2：自动化 GREEN / Windows 人工 HOLD

本目录为独立工程和人工原始证据，不是正式验收或新生产合同。UI-LAYOUT-001、完整 S2B 仍未关闭。

## 精确身份

- 起点／live 基线：`f073749a6f57490516e7c2b1df516c5e6c53d13f`；parent `7363c8e692bce23e2cddfa9564de34829c19f890`。
- 本地源码／测试 checkpoint：`934a349c51eeb51c1d7b7b12799b48594cb50358`；parent 为起点。9文件 +780/-101，包含原八份布局／owner草稿和特批的 StudioAction 可选辅助名称参数。见 [source-commit.json](source-commit.json)、[source-staged.patch](source-staged.patch)、[final-source.json](final-source.json) 与 [r2-vs-r1.patch](r2-vs-r1.patch)。
- 开始前八份 worktree 原字节逐项匹配 R1 manifest。冻结原 patch SHA-256：`b6147bef47293bd496b268e9ea8674814cae4c3f290ed35362f1311f55615429`。
- 正式 upstream仍1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`；未同步、未改baseline、parity或Prompt。
- 源码 checkpoint 在自动化全绿后、Windows人工前本地建立；人工发现异常后未再改源码。由于普通 push 会同时带出该源码，现不擅自以“仅证据”名义推送；见 [GIT_DISPOSITION.md](GIT_DISPOSITION.md)。

## 分阶段原始结果（不相加）

| 阶段 | 原始退出码 | XML结果 |
| --- | --- | --- |
| 01 一次有界诊断，保留既有失败断言 | 1 | 3 suites／5 tests，5 FAIL；重现原四项语义与一项最大化观察失败。不是 R1 完整3/14结果的重分类。 |
| 02 修复后单方法及共享控件回归 | 0 | 3 suites／6 tests，全PASS |
| 03 原三类 Fish UI／Layout／Window | 0 | 3 suites／15 tests，全PASS |
| 04 F1/F2、设置、成熟Studio回归 | 0 | 8 suites／82 tests，全PASS |
| 05 bounded Desktop | 0 | 158 suites／1522 tests：1520 reported PASS、2 SKIPPED、0 failure/error |
| 06 一次 createDistributable | 0 | 构建成功，不是JUnit执行 |

每个阶段有独立 [command／日志／XML／资源身份](stage-summary.json)，没有覆盖前次 XML。JDK17、offline、no-daemon、no-parallel、single worker、low priority、in-process Kotlin，原2048MiB heap。原四项排除保存在 [bounded.init.gradle](bounded.init.gradle)，未增加排除或skip。两个JUnit SKIPPED均为既有 symlink 夹具，未改为PASS；旧symlink NOT TESTED、RED及UNKNOWN继续保留。

验证助手首次 javac 命令未显式指定编码，Windows默认编码解析UTF-8中文源失败（exit1）；[原日志](helper-compile.stderr.log)保留。只为命令加 `-encoding UTF-8` 后exit0，同一助手源码SHA不变。此为验证命令失败，不能写成产品Kotlin编译RED，也不能省略。

资源：各阶段独立原始采样见resources.jsonl。测试阶段最低可用物理内存512319488 bytes（修复后方法阶段），最低Commit余量15854481408 bytes；bounded最低907378688 bytes、Commit余量16297852928 bytes。重型bounded前可用2241392640 bytes、Commit余量18198179840 bytes、Java进程0。未观察到OOM，无用户进程终止、pagefile调整、清理缓存或恢复3/8GiB硬门槛。

## 语义与原生几何

[DIAGNOSIS.md](DIAGNOSIS.md)及阶段01／02 XML system-out保留节点ID、父子关系、语义属性／名称、Role、Disabled、OnClick、RequestFocus和bounds。Fish外层仅名称的Box与真实Button分离已直接重现。修复只让真实StudioAction拥有完整名称；所有旧调用默认label不变，成熟Studio测试14/14另有回归。

原最大化观察在子窗口WINDOW_OPENED之前就发起请求，初始原生identity为null。测试现在等待真实显示，再同时验证AWT最大化状态、工作区几何、内容增长、还原原尺寸、实例数与owner身份/输入隔离。没有延长原15秒超时，没有只检查WindowState枚举，也没有去掉两个维度增长断言。

原生 fixture：600x500 → bounds(-8,-8,1936,1056)，AWT extendedState6；内容560x437→1896x993；显示器1920x1080、work area1920x1040、scale1；还原600x500／state0／内容560x437。自动化原生事实与用户实际最大化签署分别记录，不冒充截图。

## Windows包与人工结果

- 新包212文件，完整 [distribution-manifest.json](distribution-manifest.json) SHA-256：`1366681596bc857c0f388863566f6305ebb0525abeb3e163cc1fc1ce1f6cd0e7`。
- Launcher SHA-256：`e5d45e5389e7584b9d8477efe34543de1069e6550560a3a3c53d164152958b8f`。
- 业务JAR `app/desktopApp-691f8328f558b727b5ed83977c950b6.jar` SHA-256：`241be16f0ddccee4307b6f3d6871e84f589b7207912b6e95d855a6082dcd2892`。55个所改生产文件对应类及嵌套类逐字节匹配编译输出。启动前再次核对全部212成员，退出后再核对。
- 独立 LOCALAPPDATA、合成卡与会话、合成DPAPI凭据、loopback fake、进程级TLS/代理；无系统证书／注册表／全局代理修改。源码、验证助手和发行包职责分开；不提交运行包、音频、凭据、私钥或合成存储。
- [MANUAL_RESULTS_CN.md](MANUAL_RESULTS_CN.md)：计划复合项3 PASS／1 FAIL／5 NOT TESTED，8条原始用户文字记录。Escape用户FAIL保留，面板形态UNKNOWN。另有“完全没看到停止按钮”的可见性反馈，未证实根因、不静默修复。
- Launcher/JVM实际退出0/0；运行中锁拒绝75，退出后独立重获并释放0。fake退出0并排水，仅本轮TLS材料删除；预览MP3／part残留0；两卡hash不变。见 [runs/manual01/process-result.json](runs/manual01/process-result.json)、[active-lock.result.json](active-lock.result.json)、[post-exit-lock.result.json](post-exit-lock.result.json)、[mock-final.json](mock-final.json)、[card-preservation.json](card-preservation.json)。
- 真实Fish GET0／TTS0。fake记录catalog GET2、sample GET1、POST0；不能把实际GET日志替代听觉／停止签署。

## 保留边界

历史R1原3 suites/14 tests（9PASS、5FAIL）及首轮compile RED完整保留；本轮不修改历史证据、manifest或验收。WIN-AUTO-001 OPEN；底层捕获根因未在本轮修复。WIN-BRAND-001继续OPEN／IMPLEMENTATION PENDING，引用R1的deferred记录，下一次安全治理checkpoint登记；本轮不是治理扩项。MCI legacy、设备兼容、TOCTOU、物理阻塞I/O绝对超时、耐久性与旧未测项无提升。

没有更改HTTP认证、sample来源、redirect/follow-up守卫、TTS请求序列化、cache identity、runtime/Store/player、CharacterInfo、Prompt、Package/Entity/schema、G2或正式音频仓库。未进入S3、聊天音频、SaveSlot、QQ、Writer/Recovery/dispatch；未合并desktop或发布。

用户自放个人Markdown和三张历史JPEG仍未跟踪、未读取内容、未修改、未暂存。只能称tracked源码和index无其他改动，不能称整个工作区clean。

结论：`P8-S2B-UI-LAYOUT-001-R2 HOLD / WINDOWS MANUAL FAIL PRESERVED / PROJECT REVIEW PENDING`。后续需要Project裁定Escape复现条件、停止控件可见性修复及本地checkpoint的发布处置。
