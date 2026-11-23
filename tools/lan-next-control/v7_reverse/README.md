# 40981 Android87→Windows 独立反向控制

准备身份：`40981ae4045114d1d2a076a82b02b7c7f5275cc7`。原45093230867已实际构建/配对/Android追加，但Windows未收反向；本次固定隔离路线增量尚未编译/执行。它是全新 GHA Windows/API26 AVD 中的非交付 debug app87+test87，不使用交付 APK、801f 密钥或原86，也不证明 Android 普通粘贴/扫码/聊天发送 UI、Windows packagedMain 或真人验收。

`LanV7ReverseSyntheticWriterTest` 是唯一 Android 原生方法；在实际 `ChatBarApp` 和前台 `LanSyncActivity` 里调用真实 coordinator.beginJoin 处理 Windows 临时合成邀请，等 Windows 明确批准和原上下文实际收到后，核持久化会话仍属于原角色卡，只调用一次普通 `chatRepository.addMessage` 写入一个唯一消息和此时生成的8×8洋红/青 PNG。正常 committedRevision/前台 hook 决定作者和传送；没有手动 Sync、Node.upload、网络探针、私有业务 JSON 注入、另建组或 pause。

`DesktopV7ReverseSyntheticHostDraftTest` 是唯一 Windows 原生方法，沿已验证 live fixture 的实际 AppContainer/DPAPI、默认 Local 私网地址/认证规则、真实 TLS 身份和一次明确批准；反向方向使用下面明确的隔离路线。新尾段独立读取普通 chatRepository，要求原消息加新消息恰好两条、唯一 ID/正文/角色/附件正确，并核实际收件 PNG 的 app 边界、字节 SHA、大小、8×8尺寸及每个像素，然后才发 ACK。普通反向收件本身是新范围；不会把旧 c24→原签86 的 PASS 重记为本例结果。

两个 checkout 均取固定40981（与d737生产文件逐字同，仅QR工作流模拟器显示变化），产品文件不改。Android 仅新增此 instrumentation overlay；Windows 仅新增此 test overlay。现有桌面执行引擎、原始 JUnit 和 instrumentation 严格解析器均按 `fixed-inputs.json` 摘要复用。d737 相对 c24：AndroidLanSync/ChatBarApp/build.gradle 原样；ChatRepository 新增 durable session 检查、默认 onCommitted 参数，单参 addMessage 兼容；LanSyncActivity 的差异是 SAF 邀请文件保存 binding，不参与本例调用链。

新 workflow `.github/workflows/lan-v7-android-windows-reverse.yml` 会新建临时 debug key，仅签本 job app/test，两证书必须相同且不为交付801f；APK只安装进新建模拟器，不上传/分发。真实 app 私有 TLS 身份由其正常运行时生成，无用户身份或凭据。独立请求 `.github/lan-v7-reverse-request.json` 沿旧三字段 schema 绑定源和 Windows fixture，固定输入文件另锁定所有新控制/夹具、三个借用模块、源 blob、两条唯一原生目标和环境。

最短云命令（workflow已给全部工具配置）：

`python -B tools/lan-next-control/v7_reverse/run_reverse_cloud.py --request .github/lan-v7-reverse-request.json --android-source ../reverse-android --windows-source ../reverse-windows --evidence evidence-reverse --fixed-sha256 <workflow固定摘要>`

Android仅构建 `:app:assembleLanPreview :app:assembleLanPreviewAndroidTest`，配置 `lanSyncDeviceTests=true / lanSyncPreviewSeries=next / lanSyncPreviewCode=87`；真正用例目标分别是固定 `am instrument ... LanV7ReverseSyntheticWriterTest#appendOneMessageAndPngAfterProductionLanJoin` 与借用原 runner 的 `:desktopApp:test --tests ...DesktopV7ReverseSyntheticHostDraftTest`。每端只尝试启动一次，原始方法必须实际执行一次并通过，不接受 skip/println/仅文件存在。

控制仅输出五个固定小 JSON：`run.json`、`debug-build.json`、`android.json`、`windows.json`、`windows-junit.json`。Android实际原始输出与 Windows 原始XML在私有 job 根由固定 parser严格核验并绑定 SHA，但不上传原始全文；这是明确的独立重放范围限制。APK、keystore、私有 offer/oracle/ACK、图正文、UI树和原日志从不进入 artifact。`run.json` 分开记录两端实际结果、唯一目标启动尝试、总体结果及清理状况。

整个原生树外层1500s先于借用 Windows runner1800s截止；只终止本次 Popen 取得的 owned PID树。模拟器先正常 `emu kill`，超时才结束其 owned PID树。最后仅删本 job 新私有根内的 debug key，保留其他私有中间件至 runner退役；固定上传路径与其分离。清理失败如实单独记，不能伪造目标结果。没有本机执行、源写入、交付或新人工 Gate。

45088228317 的三个小原件只证明实际配对/批准及 Windows 到达反向等待，未区分 oracle 和消息两门。已确认控制缺陷是父控制在 Windows 失败后立即 taskkill Android 控制树，导致 Android finally 结果缺失；没有据此确认产品反向失败原因。诊断增量保持同40981/原180s/全部正文、唯一性和PNG断言：Android app每秒由独立诊断协程原子写入仅阶段闭集、nullable布尔与有界计数的私有 progress，记录初始会话/消息/图片、durable会话、append尝试/提交回调/返回、本地新ID和oracle写出；callback只设AtomicBoolean，不进行文件或业务调用；诊断协程只读该原子值和现成StateFlow，故repo提交后的停滞仍可观察，结束时cancelAndJoin。Python控制定期原子更新同一个安全 android.json，terminal=false明确不是最终原生成功，未知不补零。对端失败先以私有固定stop请求给Android控制最多45s收尾，再按原边界强制终止；父控制保留最先失败，不用后续停止原因覆盖。

Windows仍以原 `oracle存在 && 普通repository含新ID` 为唯一等待条件，诊断另读普通会话、普通与只读消息计数、新ID计数及两门状态，最后8个样本只含闭集/布尔/有界数；不调用reload/Sync/inspect。`reverseStep`区分等待、oracle核验、仓库核验、PNG核验、ACK写出。读失败为nullable/READ_ERROR，不能当空仓库。所有观测均不能代替实际两方法通过、真实接收断言或严格原件解析。


固定隔离反向路线增量（源仍40981）：旧2970B/SHA256 `49739fabdc9c8e6deaf9ed11b812c6192ee7dcf94fa4eccb1b1890f714c84257` 原件4CRC通过；只能证明初始消息/图收齐、普通repo appendCommitted+Returned、唯一新ID和oracle，未证明Android新版本已签名发表，也未证明Windows可反向到达AVD。无旁证不得将旧失败断言为NAT根因。

新增路由不改产品：Android测试relay只绑定loopback随机端口，向原普通listener转发时先绑定该AVD唯一实际RFC1918地址，生产Local.accepts不变，TLS流量不解密/终止。仅本次serial的`adb forward --no-rebind tcp:0 tcp:<relay>`连接此relay，实际分配端口及三列绑定核对后才把私有route传给Windows；finally只移除这一条forward。Windows测试relay绑定原邀请选择的真实私网地址，透明转至该forward。原Windows discovery实例仅关闭并替换成同真实类的search-only实例；UDP39238只由一个闭集合成hint responder占有，且只回应同runner所选私网来源、已批准device+同group+fresh nonce，返回固定relay端口。普通Coordinator仍执行原find→production Local真实固定指纹mTLS→discover/prefetch/普通native apply。未调用手动Sync、node.discover/upload/apply或私有业务数据导入。

两个relay分别最多4条并发、5s TCP连接、15s读、每方向每连接32MiB限制；关闭时关所有owned socket并在总3s窗口收线程。只有本次云端合成Android87/Windows单例，无DD1/真机/原86交付或签名材料、系统网络/防火墙/信任修改。上报`isolatedRouteMode=ADB_FORWARD_TRANSPARENT_TLS`，UDP/真实WiFi仍false；这不是一般网络发现或跨真实机器覆盖。

Android另按1秒被动采样现成Coordinator.view，只认append前不存在、作者为此actualaccess.device、conversation新版本已admitted且plan为UNCHANGED/currentHash精确匹配的ref，再核其native frontier与同metadata descriptor的已存在local receipt；不调用inspect/prepare/recover/commit/upload。ACK后仅做一次最终被动采样，无新等待或补同步。未知保持null/读取不可用，不能以repo提交代替自动发表。Windows原180s两门及精确两消息/唯一ID/正文/PNG大小、SHA、8×8每像素断言全部保留；公开只增有限count/Boolean，不上传route/group/device/端口/原文。源blob和全部控制pin随本增量更新，借用3个runner/parser逐字不变。

两个透明relay在accept后先严格核incoming.inetAddress等于各自精确bind地址：Android为127.0.0.1，Windows为本次邀请选定的实际本机RFC1918。其它来源立即关闭，发生在并发slot、accepted计数和transfer之前，不能转入Android或计作路线成功。UDP原packet.address==host保持。此源地址前提仍待云端实际验证，不改系统地址、路由或防火墙。
