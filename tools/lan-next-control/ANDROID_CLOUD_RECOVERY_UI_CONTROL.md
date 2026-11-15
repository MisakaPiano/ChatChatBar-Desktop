# Android 原生云恢复 UI 控制：安装布局最小重绑

状态：新安装布局最小整合变体，可由 root/唯一 owner 整合；原13文件冻结稿完全保留。不是已运行证据。native 编译 0、native 方法执行 0、JVM/Gradle/SDK/ADB/模拟器/服务器执行 0、二进制下载 0。纯 Python/FS/mock 检查 12 项通过。首次纯检查仅因 Windows 默认 GBK 读取 UTF-8 中文 fixture 失败，已显式 UTF-8 修正，原结果留 `原冻结目录的 pure-guard-results-r01.json`；不属于产品或 Android 实测失败。

固定生产对象为 `db25f8c61fdf237449286b13812a622c9a9d2120`，原冻结控制对象为 `ca9c2455cf172aeecedcf3d4b9a85e1d706b2b8a`，本变体安装基线为 `203c8efaf473f9b424b0403e5bcc4aa4da6d89f7`。本目录之外没有写入；生产/控制 checkout、index、canonical 总账及207未改。没有 request、CI、APK/原件下载或公开上传。固定输入与历史继承分开记录于 `android-cloud-recovery-ui-fixed-inputs.json`，准确源码行见 `android-cloud-recovery-ui-source-callchain.json`。

## 将补齐的独立范围

同一个 `com.example.chatbar.lansyncnext` / preview85 / API35 / JDK17 测试环境执行两个真实 instrumentation 方法，每个独立保留原始报告并交给原 strict parser：

1. `realCloudRecoveryUiRestoresOfflineLatestConversationAndSavesKeystoreConnection`：独立源根使用真实 AndroidKeystore 别名、真实 JsonFileStorage/ChatRepository、Enrollment 和普通 Coordinator 自动源发表；显式启用一条 HTTPS 路线，普通 CloudRuntime 向官方 rclone 密文上传并取得逐对象回读证据。源 owner 全关闭，再移动原根离线并核完整哈希清单。接收端实际 LanSyncActivity → CloudRecovery 表单输入合成 URL/用户名/密码/恢复码 → 读取/核对 → 实际 Activity 重建 → 所有输入为空 → 再次输入并核同一 recipient → 默认保存连接为 On → 实际同意复选框和确认按钮 → 原生数据恢复、根/组/新日常设备、原成员、author0、4条消息/回复备选与选中版本/最后回复/131113B 文档、受保护连接保存。
2. `coldProcessReopensSavedRouteAndOrdinaryAndroidHooksUploadNewRevision`：原 runner 中间只 force-stop 同包，不 clear、uninstall 或重装；第二个真实 PID 正常启动实际 Activity，读回原身份、ledger、author/native origin/head、会话、文档与同一受保护路线。冷阶段不注入密码/恢复码/source/package 等恢复输入。随后普通 JsonFileStorage 提交一个小卡片修订，由 Android 已有 lifecycle/service/coordinator/sourceChanges/cloud 调度上传；不点 Sync，不直调 Runtime.run/Node.upload，不把本地 pending/notice 算送达。新版本和真实云端 chunk 回读收据必须成立，原聊天消息哈希保持。

源是同一instrumentation进程中的独立合成根/owner，源platform只提供本地互斥与no-op reload，源附近网络显式关闭；不表示另一真实手机后台或独立source进程。接收端才走实际Android app/FGS/data gate。

两个阶段共用同一组原生接收资料；初始四消息＋131113B文档属于本独立小资料范围。它不重复或代替历史 LAN 1.344GB、其他 source cloud/file GB、Android 文件恢复、Windows 云恢复或 API35 FGS/HOME 测试，也不声明商业网盘、物理手机、GB/速度/费用、人验或另一台设备已收到新 revision。restore-only 本草案未执行，后续应在独立干净接收组覆盖；不得由默认保存组代签。第二方法只证明普通新 revision 自动上传与云回读，另一 peer 的自动下载/原生落盘仍待补。

## 精确信任链及尚未成立的前提

固定源码事实：`LanSyncGroupCloudRecoveryOwner.kt:40–45` 公共构造使用 `WebDavCloudClient.create()`、HTTPS_ONLY；`WebDavCloudClient.kt:9–10` 经 `ProxyAwareClient.builder()` 构建新的 OkHttp；`ProxyAwareClient.kt:47` 每次 `OkHttpClient.Builder()`，没有应用级 SSL 客户端缓存或 Android SSL 工厂 override。依赖为 OkHttp4.12.0。这里不是 HttpsURLConnection；桌面 `javax.net.ssl.trustStore` 结果不能挪作 Android 结果。

Android 实际 factory 是 `AndroidLanSync.kt:170` 的公开 CloudRecoveryOwner，带真实 `runCloudWork`/前台 gate；Coordinator 与 recovery owner 为 lazy。ChatBarApp 在 instrumentation 方法之前会执行启动路径并可能 `startGroupIfConfigured()`；此草案不改它。新 Activity 尚未 resumed 时，Android cloud gate 的 canStart 必须拒绝后台新网络 lease；这是生产静态链的预期，仍须 API35原件证实。草案不声称 test 方法安装 Provider 能回溯改变任何已构建客户端。

单 `.kt` companion 在 instrumentation 进程通过测试 Provider 只替换默认 TrustManagerFactory 选择，委托安装前的原平台 TrustManagerFactory、一个新内存 Keystore 和唯一测试公钥证书；返回原平台 X509TrustManager 实现，保持正常客户端/TLS/hostname verifier。它不改系统证书、Network Security Config、代理、应用生产源码或用户根。测试私有证书 SAN 同时含 runner `127.0.0.1` 和 emulator `10.0.2.2`；rclone 仅绑定 runner127.0.0.1。JDK17 生成私钥及原有 `RecoveryUiTlsMaterial.java` 导出都只发生在未来受限云 runner 的私有临时根。

主恢复前必须由真正的 `WebDavCloudClient.create()` 完成 HTTPS PROPFIND207，且 peer证书字节与唯一证书相同。错 hostname 反例仅给单独负向 probe 临时映射 `wrong.invalid` → 真实10.0.2.2，保留同一默认 TLS/hostname verifier并要求 SSLPeerUnverifiedException；没有 fake transport。再暂时移除本测试 Provider，由未修改的默认 client 访问真实服务器并要求 SSLHandshakeException，证明未受信证书被拒。Provider在异常/最后清理中移除，原 security provider顺序与对象身份、默认算法精确还原。不会使用 trust-all、hostname放行、Client注入或 HTTP synthetic policy来“救绿”。

准确尚缺：API35是否接受 instrumentation类加载器下的这个单证书 TrustManagerFactory Provider、是否返回Android可用的原平台TM、production默认OkHttp是否如预期选中它、冷Application先行启动是否可能构建旧TM、两个正/负TLS结果及全部真实UI/Keystore/冷启动/上传行为，当前统统未编译/未执行。若其中任一不成立，最窄阻断是 `CloudUiTestTrust.install → TrustManagerFactory.getInstance(defaultAlgorithm) → WebDavCloudClient.create → OkHttp default platform trust/client TLS`；恢复方法不得通过。需记录实际 phase/类型后由 owner 决定最小测试能力适配，不能称生产产品已坏或换内部fakeClient冒充主范围。

## 三个TLS探针的地址与到达条件

当前native0，尚不存在实际选出的端口或可达回执。未来控制器只在runner临时选取同一个端口P（1024–65535），rclone真实监听 `127.0.0.1:P`；runner readiness以 `https://127.0.0.1:P/` 做认证PROPFIND207。Android emulator的 `10.0.2.2` 是该受限runner宿主loopback别名；它是否在此runner真实可达必须由第一个Android正向probe自己证明，不能由Python readiness替代。

| 探针 | 实际请求URL模板 | 到达同一服务的方式 | 必须得到的结果 |
|---|---|---|---|
| 默认client正向 | `https://10.0.2.2:P/` | emulator宿主loopback别名→rclone `127.0.0.1:P`，测试证书SAN含这两个IP | PROPFIND207，peer DER精确等于唯一public cert |
| 错hostname负向 | `https://wrong.invalid:P/` | 仅此负向client的Dns把wrong.invalid精确映射为InetAddress(10.0.2.2)，保留相同P/真实socket/TLS和正常default hostname verifier | SSLPeerUnverifiedException；连接拒绝、DNS失败、超时或HTTP状态均不会满足此catch，不得算过 |
| 未受信cert负向 | `https://10.0.2.2:P/` | 在业务云工作尚未开始时临时移除本测试Provider，新的原始default client访问同一真实服务 | SSLHandshakeException；随后finally重装本测试Provider，最后完整恢复最初provider列表 |

负向wrong.invalid不是另一个假服务器，不改正向或业务client的Dns，不做HTTP/fake transport，也不修改系统hostname/信任。此草案没有adb reverse，没有额外provider服务进程；唯一rclone child由原bounded process owner管理，runner127.0.0.1之外不监听，server密文/私钥/log只留其私有临时根并有界终止后清理。Android侧只在隔离instrumentation进程改变Security Provider，全部owner排空后finally按原对象身份/顺序移除还原。以上是实现与判定边界，所有真实连通性/TLS结果仍未证。

## UI 与隐私护栏

重建后的恢复入口必须等待 raw `!panel.busy && stage ∈ {EMPTY, JOINING}`，之后 Compose idle 才触摸。click helper只选有真实 OnClick 的 merged button并在该节点 assertEnabled/performClick，避开仅Text子节点 stale-enabled。review出现后先等 Compose effects settle；保存与同意只选真实 toggleable节点，滚动/idle、原Off、一次physicalClick、等真实On再确认，不直接改UI state或调用Owner/Coordinator.prepare/confirm/open。`onStop/onDispose` 原有清secret行为完整保留。

阶段1私有输入通过固定 run-as stdin写入 app cache，密码不在adb argv、工作流、instrumentation参数或报告里；方法读取后立即删除该输入。冷阶段只保留原app路线、public cert与本地public hash oracle；oracle内离线source路径仅用于证明未变化/未当恢复输入，不宣称原source物理0bytes。恢复码只在合成源生成并经实际表单输入，不写oracle。测试异常仅固定phase/异常类型，外层 Rule 丢弃原message/cause/UI tree；源独立Keystore别名可清理，接收默认Keystore必须跨phase保留。所有应用资料、protected tree、明文、证书私钥、恢复码、测试code、APK、UI树、截图和服务器log均不上传。

## 复用与整合点

`android_cloud_recovery_ui_control.py` 只私有加载并哈希锁住原 `android_file_recovery_ui_control.py`，复用其 `TwoPhases` / `RunFacade`；原 `group_ui_control.py` 独占overlay、原manifest/包版本、单干净emulator、安装一次、runner选择与strict `instrumentation_report.py` 不变。默认profile全局、parser namespace和stdlib subprocess不改；异常还原私有facade。rclone保留原1.76.0 Linux官方archive URL与 SHA `b00f00b3266c3764413afe599d3ec316f6f43b3e0ea8a0bc1d186d2133e46df7`，CRC后仅抽取唯一regular binary；直接复用 `real_webdav_process_owner.run_owned_server` / interruption_handlers / 有界清理。TLS readiness只从哈希锁定的desktop profile抽取原函数，不导入其Windows runner或复制新runner。

fixture的数据 builder/oracle/assertRestored部分从固定Android文件fixture逐段复用，保留原生实体/字段/资源/版本语义；没有另写业务格式、导入器、Owner、配对或mapping。新增单fixture是唯一生产checkout overlay。所有外部helper继续留控制仓原位置，贡献未复制另一套runner。

本变体只改 cloud profile 的 `PINS["android_file_recovery_ui_control.py"]`：从旧 `2431d50a5078965648d436909dc8d74997af70108727f99cb82e306eeb1e36c0` 重绑为新 `56a66b32533b023a23a4ceb8a9372e88e6c5ccb6d8c54b2fb677ddc90cbe4b85`。已经以两个固定Git对象逐字节证明旧/新被复用文件profile都9492B，唯一内容差异是其自身 `FIXTURE_SHA` 的274d…→fab907…；其6个其他cloud profile依赖全部同hash。对应pure test的fallback读取对象仅ca9→203c，source db25和本cloud fixture74a6a5…逐字节保持。未改旧file helper、默认、原file request或任何生产源码。

原文件第六真实运行失败现只保留root指出的 `checkbox-assert-off` assertDisplayed/viewport/IME几何范围；本变体不复制原件、不猜测为On清secret竞态、不盲改cloud fixture。CloudRecovery本身TLS/UI前提仍全部native0。

完整待复制文件恰为本目录 `install/` 下8个新路径，详见 `COPY_LIST.json`。四个行为/验证文件为profile、pure test、原样cloud fixture和真实相对路径 `.github/workflows/lan-next-cloud-recovery-ui-control.yml`；另4个说明文件均有唯一cloud前缀，绝不覆盖通用README/fixed-inputs/STATUS。workflow与旧draft的差异仅首行说明，执行主体逐字节同。当前未创建request，也未触发CI。原request.example没有放进install布局或patch。

`installed-rebase.patch`是以203c中均不存在这8个路径为基线的完整新增patch；`semantic-rebase.diff`另列相对原冻结贡献的最窄内容差异及共同fixture不变。整合者仅应用8文件，不复制原7个helper，不改任何既有文件。未来若control或source再次改变，须核具体diff/哈希再明确重绑，不把本次允许解释为任意接受helper变化。

工作流只允许14个原controller evidence路径；即使失败也保留原phase报告。总成功必须同时满足两个原始方法strict PASS、正常bounded控制/服务器清理/整job成功及固定源码回核。纯mock中生成的instrumentation串只测试parser/控制流，临时目录随测试删除，从未写成native证据。安装布局 pure guards 仍只证明脚本与mock，明确native0。

The first actual cloud run43657244369 failed compiling only this companion, before any emulator/native/TLS probes. The successor makes the await predicate and author-zero helper suspend functions and uses the actual OkHttp Dns interface object. All real TLS probes, UI actions, native/cold and automatic-upload assertions stay unchanged. Native success remains unobserved until new original reports.
