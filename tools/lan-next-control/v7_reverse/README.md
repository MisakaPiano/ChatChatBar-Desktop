# 40981 Android87→Windows 独立反向控制

准备身份：`40981ae4045114d1d2a076a82b02b7c7f5275cc7`。本控制尚未编译/执行；仅静态验证。它是全新 GHA Windows/API26 AVD 中的非交付 debug app87+test87，不使用交付 APK、801f 密钥或原86，也不证明 Android 普通粘贴/扫码/聊天发送 UI、Windows packagedMain 或真人验收。

`LanV7ReverseSyntheticWriterTest` 是唯一 Android 原生方法；在实际 `ChatBarApp` 和前台 `LanSyncActivity` 里调用真实 coordinator.beginJoin 处理 Windows 临时合成邀请，等 Windows 明确批准和原上下文实际收到后，核持久化会话仍属于原角色卡，只调用一次普通 `chatRepository.addMessage` 写入一个唯一消息和此时生成的8×8洋红/青 PNG。正常 committedRevision/前台 hook 决定作者和传送；没有手动 Sync、Node.upload、网络探针、私有业务 JSON 注入、另建组或 pause。

`DesktopV7ReverseSyntheticHostDraftTest` 是唯一 Windows 原生方法，沿已验证 live fixture 的实际 AppContainer/DPAPI、默认 Local 私网路线、真实 TLS 身份和一次明确批准。新尾段独立读取普通 chatRepository，要求原消息加新消息恰好两条、唯一 ID/正文/角色/附件正确，并核实际收件 PNG 的 app 边界、字节 SHA、大小、8×8尺寸及每个像素，然后才发 ACK。普通反向收件本身是新范围；不会把旧 c24→原签86 的 PASS 重记为本例结果。

两个 checkout 均取固定40981（与d737生产文件逐字同，仅QR工作流模拟器显示变化），产品文件不改。Android 仅新增此 instrumentation overlay；Windows 仅新增此 test overlay。现有桌面执行引擎、原始 JUnit 和 instrumentation 严格解析器均按 `fixed-inputs.json` 摘要复用。d737 相对 c24：AndroidLanSync/ChatBarApp/build.gradle 原样；ChatRepository 新增 durable session 检查、默认 onCommitted 参数，单参 addMessage 兼容；LanSyncActivity 的差异是 SAF 邀请文件保存 binding，不参与本例调用链。

新 workflow `.github/workflows/lan-v7-android-windows-reverse.yml` 会新建临时 debug key，仅签本 job app/test，两证书必须相同且不为交付801f；APK只安装进新建模拟器，不上传/分发。真实 app 私有 TLS 身份由其正常运行时生成，无用户身份或凭据。独立请求 `.github/lan-v7-reverse-request.json` 沿旧三字段 schema 绑定源和 Windows fixture，固定输入文件另锁定所有新控制/夹具、三个借用模块、源 blob、两条唯一原生目标和环境。

最短云命令（workflow已给全部工具配置）：

`python -B tools/lan-next-control/v7_reverse/run_reverse_cloud.py --request .github/lan-v7-reverse-request.json --android-source ../reverse-android --windows-source ../reverse-windows --evidence evidence-reverse --fixed-sha256 <workflow固定摘要>`

Android仅构建 `:app:assembleLanPreview :app:assembleLanPreviewAndroidTest`，配置 `lanSyncDeviceTests=true / lanSyncPreviewSeries=next / lanSyncPreviewCode=87`；真正用例目标分别是固定 `am instrument ... LanV7ReverseSyntheticWriterTest#appendOneMessageAndPngAfterProductionLanJoin` 与借用原 runner 的 `:desktopApp:test --tests ...DesktopV7ReverseSyntheticHostDraftTest`。每端只尝试启动一次，原始方法必须实际执行一次并通过，不接受 skip/println/仅文件存在。

控制仅输出五个固定小 JSON：`run.json`、`debug-build.json`、`android.json`、`windows.json`、`windows-junit.json`。Android实际原始输出与 Windows 原始XML在私有 job 根由固定 parser严格核验并绑定 SHA，但不上传原始全文；这是明确的独立重放范围限制。APK、keystore、私有 offer/oracle/ACK、图正文、UI树和原日志从不进入 artifact。`run.json` 分开记录两端实际结果、唯一目标启动尝试、总体结果及清理状况。

整个原生树外层1500s先于借用 Windows runner1800s截止；只终止本次 Popen 取得的 owned PID树。模拟器先正常 `emu kill`，超时才结束其 owned PID树。最后仅删本 job 新私有根内的 debug key，保留其他私有中间件至 runner退役；固定上传路径与其分离。清理失败如实单独记，不能伪造目标结果。没有本机执行、源写入、交付或新人工 Gate。

45088228317 的三个小原件只证明实际配对/批准及 Windows 到达反向等待，未区分 oracle 和消息两门。已确认控制缺陷是父控制在 Windows 失败后立即 taskkill Android 控制树，导致 Android finally 结果缺失；没有据此确认产品反向失败原因。诊断增量保持同40981/原180s/全部正文、唯一性和PNG断言：Android app每秒由独立诊断协程原子写入仅阶段闭集、nullable布尔与有界计数的私有 progress，记录初始会话/消息/图片、durable会话、append尝试/提交回调/返回、本地新ID和oracle写出；callback只设AtomicBoolean，不进行文件或业务调用；诊断协程只读该原子值和现成StateFlow，故repo提交后的停滞仍可观察，结束时cancelAndJoin。Python控制定期原子更新同一个安全 android.json，terminal=false明确不是最终原生成功，未知不补零。对端失败先以私有固定stop请求给Android控制最多45s收尾，再按原边界强制终止；父控制保留最先失败，不用后续停止原因覆盖。

Windows仍以原 `oracle存在 && 普通repository含新ID` 为唯一等待条件，诊断另读普通会话、普通与只读消息计数、新ID计数及两门状态，最后8个样本只含闭集/布尔/有界数；不调用reload/Sync/inspect。`reverseStep`区分等待、oracle核验、仓库核验、PNG核验、ACK写出。读失败为nullable/READ_ERROR，不能当空仓库。所有观测均不能代替实际两方法通过、真实接收断言或严格原件解析。
