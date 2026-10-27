# P8-S2B-UI-LAYOUT-001-R3 — HOLD / RAW RED PRESERVED

本轮目录日期按 Asia/Tokyo 为2026-10-19；原始日志保留各自UTC时间。这里是独立原始工程证据，不是正式验收、生产源码checkpoint或UI结案。

## Git和范围

- 起点：`55366cee0706c78f545b32ef7d75750c649dcdbd`；parent／现有R2源码：`934a349c51eeb51c1d7b7b12799b48594cb50358`。开始时local、tracking、GitHub live一致。
- R2报告中的“未push”继续保持其历史原文；没有倒改旧证据。R1/R2历史RED、人工FAIL、NOT TESTED、SKIPPED及UNKNOWN均保留。
- 当前三份草稿未提交：`DesktopFishPickerPresentation.kt`、`DesktopFishPickerWindow.kt`、新增`DesktopFishPickerNativeUxTest.kt`。完整[可重建补丁](source.patch)为3文件+422/-33，base为上述55366cee。
- 补丁SHA-256：`a94aaf3526111ac364ba636a9e96306991688891c597e5cafa13152be9606f66`。原字节、规范化SHA-256及base Git blob在[source.json](source.json)。`final-sources/`逐字节保留实际聚焦及bounded实测版本；没有在RED后改源码。
- Project原范围为Escape优先级和Stop可见性。用户随后明确追加：“输入页码直接跳转，很多多页时候的常驻可拖动滚动条。可以像一般的多页网页一样设计。”因此草稿也增加显式页码输入／Enter或按钮跳转、已知总页数校验和固定结果滚动条；未改controller或请求协议。
- Main、成熟Studio控件、Fish transport/runtime/player/store、SecretStore、CharacterInfo、Prompt、Package/Entity/schema、parity和正式1.4.1 baseline均未修改。

## 原始执行结果：各次独立，不相加

| 阶段 | 退出码 | XML实测结果 |
| --- | --- | --- |
| 01 一次有界原生基线观察 | 0 | 1 suite／1 probe完成；这是诊断探针成功完成，不是旧Escape/Stop行为通过验收 |
| 02 初次修复后原生Gate | 0 | 1 suite／4 tests PASS |
| 03 最终草稿四类Fish UI／原生聚焦 | 0 | 4 suites／19 tests PASS |
| 04 F1/F2、Settings、成熟Studio回归 | 0 | 8 suites／82 tests PASS |
| 05 bounded原四项排除 | 1 | 159 suites／1526 tests：1523 reported PASS、1 FAIL、2 SKIPPED、0 errors |

全部[分阶段身份与统计](stage-summary.json)对应各自command.txt、完整stdout/stderr、XML、进程身份及resources.jsonl；共173份XML。JDK17、offline、no-daemon、no-parallel、单worker、low priority、Kotlin in-process及原2048MiB heap。没有clean、网络依赖下载或无依据全量重跑。

原四项排除保存在[bounded.init.gradle](bounded.init.gradle)，没有新增排除、skip或弱化断言。两个JUnit SKIPPED仍为既有symlink夹具，具体名称在[closing-state.json](closing-state.json)。旧symlink NOT TESTED和真实平台限制没有提升。bounded名称沿用旧过滤集合，其中包含本轮明确授权的合成原生窗口测试，不能把它描述成全部离屏测试。

## 新RED与STOP

失败用例：`DesktopFishPickerNativeUxTest.native persistent scrollbar drags current page and explicit page jump preserves request semantics`。

[原始XML](05-bounded-desktop/junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml)记录10,000ms TimeoutCancellationException，调用点为`DesktopFishPickerNativeUxTest.kt:309`，等待条件是原生拖动后`range().value() > before`。对应[失败摘要](failure-summary.json)保留完整stack、XML SHA-256和源码行号。

该方法先前在阶段02和03通过；bounded中第一轮拖动后未确认滚动位置增加，没有到达后续页码输入、720宽度及unknown-total边界分支。源码第一轮要求1100dp，但失败拖动当时没有单独输出actual window／thumb几何及pointer capture；不能补造这些观察。已执行的两个“目标坐标位于该合成窗口bounds内”断言不等于证明命中了thumb或获得了拖动捕获。

根因UNKNOWN。现有证据不能区分产品滚动条缺陷、命中／焦点、滚动范围就绪、指针捕获或测试时序；不把“聚焦能过”当作只是环境问题，也不通过延长超时、重试、删掉新增分页、排除失败用例或改弱断言取得GREEN。

在首次控制台RED出现后停止扩大Gate，等待该受控阶段及finally清理结束。未重跑失败方法，未继续打包、未启动Windows人工实例。没有创建新源码checkpoint；仅提交本目录证据，三个源码／测试草稿继续原样保留。

## 已取得但不能替代完整Gate的事实

详情见[DIAGNOSIS_CN.md](DIAGNOSIS_CN.md)、[原生事件记录](native-event-trace.jsonl)和[几何／状态记录](native-geometry-state.jsonl)，以及原XML中的完整system-out。

- 修复前1100／720请求尺寸均观察到Window级Escape先直接关闭Picker；窄屏确有Popup而其处理未优先执行。原始带临时诊断的生产／测试源码在baseline-sources/；最终生产草稿不含这些println。
- 长合成MP3实际Windows MCI播放与runtime playing一致，未观察到需要扩改runtime的状态错误。原Stop存在于底部；窄屏Popup打开后属于下层root，不能仅凭节点存在称顶层可操作。
- 草稿改为每个Picker自己的Escape协调器：按下时只关闭面板或Picker之一，KeyUp复位，抑制同一次按键重复关闭。宽屏内联与窄屏Popup共用此优先级，不使用全局键盘拦截。
- 草稿将带现有Stop图标和“停止预览”文字的按钮放到顶部；紧凑Popup打开时只在Popup顶层保留一个Stop。原生用例核对真实client viewport、语义root层级、mouse／keyboard操作、实际MCI停止及状态恢复。
- Escape、Stop、宽窄切换与生成／sample缓存身份区分这三个新用例在阶段02、03及本次bounded中均PASS。它们不能覆盖或抹去同类中的滚动条RED，更不能替代用户听觉／界面签署。
- 本轮新Windows distribution、人工Escape／Stop、人工分页／拖动、Launcher/JVM退出及新包根锁重获：全部NOT TESTED／NOT STARTED。没有拿R2旧包冒充R3。

## 资源、网络及清理

重型bounded前采样可用物理内存2440228864 bytes、Commit余量18086297600 bytes、pages/s0、Java进程0。各阶段最低值和完整采样在stage-summary.json及各resources.jsonl；未观察到OOM。没有修改pagefile、终止用户进程或恢复旧3/8GiB内存硬门槛。

真实Fish GET0／TTS0；没有读取真实Key、聊天或卡包。新人工fake服务未启动，其请求为0。测试中的MockWebServer请求单独记在[request-accounting.json](request-accounting.json)：新Native fixture口径由已完成的server.requestCount断言核定，不能冒充全部既有Mock测试总数。其他测试的每场景物理请求断言和HTTP日志仍完整保留，未伪造全套集中网络计数。

合成native fixtures在finally恢复各自owner、关闭自己的窗口、停止／排水自己的音频和网络，并断言其缓存无MP3／part；没有清理断言RED。整个测试阶段后Java/javaw进程0。既有独立音频／锁子JVM记录在各阶段原始JSONL。它们不是未启动的新发行包Launcher退出证据，也不证明物理阻塞I/O绝对超时或断电耐久性。

## 后续边界

Project需要先审查该滚动条RED，并授权一次有界定位（实际thumb/track、range readiness、输入命中、焦点／捕获及viewport事件），再决定如何续行；本轮不擅自执行该诊断或修复。

WIN-AUTO-001保持OPEN，本轮没有使用FrameArrived或修复截图基础设施；不能把本次原生拖动超时自动归为同一根因。WIN-BRAND-001仍是R1已有deferred事项，下次安全docs-only治理checkpoint登记，本轮不改品牌窗口或CURRENT/Roadmap。

不进入S3、正式聊天音频、SaveSlot、QQ、G2 Writer/Recovery/dispatch；不提升parity，不合并desktop，不发布，不宣布UI-LAYOUT-001或S2B CLOSED。

用户自放个人Markdown及三张历史JPEG未读取内容、未修改、未移动、未暂存。除四个受保护未跟踪文件外，新增NativeUxTest仍是工程草稿；不能称工作区clean。
