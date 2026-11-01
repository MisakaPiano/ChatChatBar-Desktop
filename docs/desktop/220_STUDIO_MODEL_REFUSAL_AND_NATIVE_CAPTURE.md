# Studio 原始模型列表拒绝恢复与本机捕获检查

2026-11-01；SF12 原始模型路径补修、SF14 独立尝试。公开／日常保持1224；源码、离屏检查、原生捕获与真人验收分开。

## 原始故障与账户子项的区别

144及 `evidence/p8-s7-manual-r2-20261020/{README.md,diagnosis.json,app-stderr.txt}` 记录：合成模型JSON含非空apiKey，`DesktopModelCredentialPersistencePolicy.hydrate` 正确拒绝；Chat显示错误后切Studio，LaunchedEffect/AWT异常弹窗，用户确认后观察到退出。旧记录并未独立追清异常弹窗到关闭请求的全部因果，不在这里补写成已证实原因。

219及d44a1f85的29项仅处理后来的命名账户identity／冻结调用异常，不覆盖原始路径。本次查到 `produceState` 调用 `availableModels` → resolver → model repository hydrate，没有保护原始拒绝。新的合成明文模型在独立临时根复现相同 `UnsafeDesktopPlaintextCredentialException`；旧R2原件、真实根和凭据均未改。

## 改动与检查

模型读取返回显式 Loading／Available／Unavailable 结果；失败不伪装成正常空列表，也不借用preset／旧Key来掩盖原拒绝。固定类别区分明文凭据拒绝、安全存储不可读及其他读取失败，不保存原Throwable／message／路径或Key，取消传播。成功模型列表仍清除运行时apiKey后供UI选择。

Studio显示专用“模型列表读取失败”，与NovelAI账户失败分开。关闭小×只清提示，保留固定行内错误及再次查看入口；可明确重试、进入现有模型设置或数据备份恢复页面。另有账户编辑器或账户恢复弹窗时让其先处理，不抢草稿。模型设置／数据页面复用PrimaryShell原导航，不自动改文件或执行恢复。

| 检查 | 结果／边界 |
| --- | --- |
| model recovery RED r01 | 新合成模型经真实repository拒绝，controller.availableModels未捕获抛出；1项实际FAIL保留。该轮初始误用旧r03输出路径，已在测试输出前备存原29项，RED输出独立保留后按字节恢复原报告，另有routing-note，不覆盖失败。 |
| model recovery r02 | 标准Gradle五类30项新执行全PASS（Recovery5、GenerateGate15、Workspace4、SecureRepository5、Presentation1）；原JSON字节／安全槽保留，Scene关闭／失败重试／两个导航回调、任务RUNNING与gateOPEN成立。回调计数不代表目标页面恢复全链或OS根锁验收。 |
| r02视觉发现 | 1100可用；360第三个“备份与恢复”被挤成竖排文字，不能以断言通过忽略。已改为FlowRow，新增窄宽单行按钮文字几何检查和外部合成修复后的实际Scene重试；r03结果另记。 |
| model recovery r03 | Recovery五项中四过、一FAIL：新增检查取到带合并Text语义的按钮节点，以高度小于24推断单行，首个正常按钮已失败；BootstrapButton自身最小高度48，不能将该节点当字形行盒。失败保留；未放宽高度阈值，而改为GetTextLayoutResult实际lineCount，并保留边界检查。FlowRow视觉／重试成功的最终结果另记r04。 |
| model recovery r04 | 标准Gradle40秒SUCCESS，Recovery五项新执行全PASS，0fail/error/skip。实际TextLayoutResult的三个按钮均单行、边界在窗内；360按钮整块换行已目视核对，1100修复后Scene点击重试恢复且固定错误消失。不是重新执行r02全30项，更不是完整CI／原生窗口验收。 |

证据在 `I:/CCB-Desktop-SmallFixes-20261028/credential-model-recovery-*`。真正StartupGate与模型hydrate的原拒绝保持；Scene处理错误后再次直接repository.initialize仍拒绝，直至测试自行执行明确的合成外部修复。产品弹窗本身不清JSON、不写Key。

## SF14：本机捕获仍未通过

只启动既有测试源码 `DesktopFinalProductFixture`，独立临时profile／内存凭据／假Designer与本地图像，无真实服务。唯一目标标题 `Phase 7 · Local fake AI Design · No network`、返回窗口ID1572894、JDK17 java.exe。Computer Use首次实际截图返回 `FrameArrived timed out`；重新枚举唯一窗口、激活后第二次返回 `window capture timed out`。共两次后停止，不继续重试、不用旧坐标。

没有取得截图，所以没有精确坐标／像素证明；不称自动捕获修复，也不回填旧Robot原因。仅定向Alt+F4关闭本测试窗口，Gradle正常exit0、无fixture进程残留；未操作其他用户窗口。日志与回执：`sf14-native-capture-r01.log`／`sf14-native-capture-r01-result.json`。

本步留待正常候选交付时人工核对，**当前尚无本批可交用户的冻结候选入口**。交付时须补准确版本／实际入口；最短步骤是打开该独立候选的Studio，显示本地合成图片与窗口标题，截取完整窗口（包含顶栏、编辑区、预览与边缘），保存至当批 `I:/CCB-Desktop-SmallFixes-20261028/` 指定验收目录；核对截图窗口身份、范围和画面与屏幕一致。普通人工截图只证明该次界面表现，不代表自动捕获接口、SF12原生进程或OS根锁通过。该人工步骤不挂起其他已授权开发。
