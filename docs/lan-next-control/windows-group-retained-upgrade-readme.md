# Windows 设备组保留升级控制

当前运行记录见同目录 status JSON，原版输入 API 的字节快照保留在 fixed-inputs JSON。当前实际控制提交为 0fb2235d2bdc9ba9e47a1a51e96edae4608fb6ff，云端 run43675592820/job132694909493 正在检查；完整设备组保留升级尚未通过。

V5/e606 和 LAN6/a6 的实际原包及原合同请求均已取得。三个先前真实失败记录保留：各完成一个旧版 fresh Main，SEED 组件未完成，四次 VERIFY、升级与冷重开未到。最后一轮已识别到 ADD_DISABLED_REAL_PROTECTED_ROUTE/IOException；只凭该阶段不能认定保存连接、DPAPI 或资料丢失。

当前三文件变体的 35 项轻量检查已通过，真实云端结果仍待本轮原件。它增加精确子阶段、安全源码位置和主异常/清理异常区分；两类异常始终导致失败。扫描仅对当前 ownership 的精确空锁文件，在入口、命中和退出核对普通文件、无符号链接、大小为零及恰一次命中后，无需发起内容读取。其它空文件、锁文件、Temp 和资料仍全部流式扫描，I/O 错误不忽略，实际 ownership 不提前释放。此空内容判定不等于已经证明旧 run 的实际 I/O 根因。

本机只运行 Python/文件/Git 检查。两个实际包、产品源、原请求、原五次 Main、五次独立组件、四次 VERIFY 的实体/PNG 只读边界及所有保护断言保留。组件不能计为 Main 启动。未做日常安装、公开发布或真人验收。

实际升级请求由已核五份原始 JSON、artifact API 和对应 Git 字段生成，再通过原 validate_upgrade 合同；本机没有下载完整候选大包，也没有假称调用要求本地完整 bundle 的 upgrade_request。云端仍在任何 native 启动前执行原 fetch-originals 的完整 outer/inner/CRC/source-bundle/Program 核验，小 metadata 不代替该链。

## 原始冻结稿说明（历史截点）

以下保留最初冻结时的实现说明。“未派发”“尚未取得 descriptor”“只有 15 项轻验”等为当时状态，已由上述当前记录替代；旧输入摘要和原始失败仍保留。


冻结范围是原始 V5/e606 → LAN6/a6 的云端 Windows 控制器和真实 AppContainer 组件。当前只有 15 项纯 FS/mock 检查通过；Kotlin 编译、JVM/native 执行、真实组升级均为 0。没有派发请求、CI、构包或产品写入。

旧原包继续使用 `desktop_lan6_control.DELIVERED_V5` 全部既有常量，不重建、不重签。此派生稿将候选源固定为 `a6bb882d7cef9aceba3fda3b0d33329f9d687d8f`、构包控制固定为 `1da064e91a7a94c4ff16da8934b8cec4bbfdb200`；既有 helper 仍固定在 `1014be57d389d117f3adae6b0bf1812aee0bc32e`，八个依赖摘要不变。实际读取的 22 个候选 API 源文件与原 7ce 截点全部逐字相同。协调端报告 a6 源 CI 已成功并派发新构包；本稿没有再次下载其日志或包，也没有捏造未来 run/artifact/内外 ZIP descriptor。真实原件到达后仍用原合同生成实际请求。

`request.example.json` 明确不可派发。实际请求必须是现有 `desktop_control.upgrade_request` 在 `desktop_lan6_control.profile()` 内根据完整原包验证生成的原合同 JSON，放入 `.github/lan-next-desktop-group-retained-upgrade-request.json`。保留原六字段合同、原 V5 baseline、三个 timestamp 源文件绑定及空 migration 白名单，不加 nonce。控制器再次经过现有 API provenance、outer/inner bytes/SHA、CRC、完整 bundle 和原包验证；不会用小 manifest 跳过该链。

业务数据、设备组格式、配对、Owner、导入、保护封装都沿用产品实现。Python 继续调用既有五启动引擎：旧版 fresh、populated、warm-control；只替换 Program；新版 upgraded、cold-reopen。原来的窗口出现、同根锁、WM_CLOSE、进程归属/退出、PNG/实体保留、原 Program 备份、外部 DEFAULT canary 等检查均保留。没有注入新的 Native 启动器。

新增范围只有两个私有钩子：

1. 原来的七实体和 PNG seed 完成后，在同一个旧版 UserData 上运行一次旧源组件。它使用默认 `DesktopAppContainer`、真实 `DesktopDataRootOwnership`、Enrollment/Coordinator 的显式 create/recoverySaved，先把恢复码存入云端私有根的真实 DPAPI custody 并解保护核对，再确认保存。随后只观察普通初始自动发表到 author1/native1，暂停并 drain，添加一条停用 HTTPS 合成 route，使用默认 AccountStore → WindowsSecretStore 保存并读回随机合成 FishAudio 凭据。密码明文落盘扫描采用 64KiB 流式重叠读取，不为普通 Temp 文件设置业务资料大小帽。
2. 每次非 fresh 的原 Main 完整退出后，用对应旧/新固定源运行独立组件。先比较原保护文件摘要，再取得实际根所有权；在 initialize 前暂停 Coordinator，initialize 后 open 已有 profile。使用实际 Enrollment/runtime、AuthorJournal、`inspectLocal(recoverNative=false)`、cloud route rows、真实 DPAPI 解保护与默认凭据 load 核验同组、原 root/daily、author1/native1、frontier graph、停用 route 和凭据摘要。没有恢复码、导出档案或备份作为 verifier 输入。实际 AppContainer.close 完成后再次比较保护文件，未确认 teardown 时保留根锁至该测试 JVM 退出。

四次 VERIFY 外层新增严格只读边界：复用原 legacy 的 entities inventory 与其实际 seed 返回 PNG 的 SHA，要求组件前后完全相同。初始化/关闭可能恢复已经授权的 WAL；如果组件补写了 Main 留下的数据，控制器会失败，不能再让后面的原保留检查遮蔽该差异。SEED 不受此边界限制。新增摘要只在私有内存比较，公开结果仅给四个阶段和 Boolean；既有第 11 项纯检查补了 verifier 伪修复实体必须失败的负向场景，总数仍为 15。这是证据归因修正，不是已复现产品数据丢失。

这五次组件执行分别由既有 Desktop group runner 产生一个真实、单方法 JUnit report，逐个严格解析。相同源码的后续执行只移除已经按原始 SHA 对照保留副本的那一个旧 XML。它不修改既有 runner 源文件/默认值；全部 helper 在私有 module 对象中加载，原 profile 的 finally 和全局 import 名称会恢复。

组件 classpath 分别从干净 e606/a6 的隔离 worktree 编译，只添加一个专用测试文件。两个原始独立包均不重建。组件是独立的 AppContainer/DPAPI 检查，不能计成打包 Main 的启动、主窗口内的设备组 UI 操作、人工验收或 NSIS 安装验证。

独立包启动脚本实际把 LOCALAPPDATA、APPDATA、TEMP、TMP 全部切到 `independent/UserData`。组件使用同样四项值，而不是旧引擎给启动脚本的外部环境。业务根为 `UserData/Local/ChatChatBarDesktop`；LanSync 和 Credentials 是其 Local 下的同级根，全部保留在 UserData 中。原外部 DEFAULT canary 检查不需放宽。组件显式 CLI root 只是实际同根组件入口，不声称验证了 Main 的选根 UI。

暂停是进程内状态。原 Main 每次按正常产品启动，可以恢复普通后台循环；没有持久 pause 字段，也没有持久 pause 的 PASS。此小资料范围不含 GB、远程端送达、联网调用计数、旧组重配、取消/数据根迁移/用户数据备份恢复、安装器或真人像素验收。旧版监听器可启动、默认 bundled assets 初始化、实际冷开保留，都仍待云端执行；若真实前提失败，控制器失败，不注入 fake network/secret store/assets 来通过。

成功输出严格十个小 JSON（每个小于 64KiB）。前三项沿用原控制器的 source-request、API provenance 和 input-byte 回执；第四项是原五启动引擎增加真实组关联后的结果；第五项是验证描述；其余五项是 fixture 实际生成并按原字节保留的合成公开字段 JSON。验证描述绑定每个原组件 JSON 的 bytes/SHA、fixture/source/control 及其私有原 JUnit 的 bytes/SHA/单方法计数。

原 JUnit、Gradle log、恢复码 custody、只含摘要的私有 oracle、保护文件目录、应用包和源码 worktree 均不上传。JUnit 只在云端私有根保留，不声称下载者拿到了原 XML。输出目录与所有 bundle/worktree/custody 分开；上传前检查路径 allowlist、普通文件、JSON 和字节预算，workflow 逐一列出十条路径，没有 glob。失败时可以保留安全的部分小 JSON；目录隔离检查失败则不上传。新建私有云端根随临时 runner 结束回收，不把非必要清理当成全目标 Gate，也不触碰此前被自动审批拒绝的两个 I 盘目录。

静态复核已修正 sharedCore internal `SyncDeviceSignatures.decode` 的跨模块访问：现在只对已由实际 Owner 验证的公开证书字节求摘要。没有复制签名验证或业务格式。完整 blob 来源见同前缀 fixed-inputs JSON。纯检查中的窗口/包对象是明确 mock；本机 Python 3.10 只对 mock 小文件 SHA 适配了云端 Python 3.13 的 file_digest；该适配不进入控制器。长 scratch 路径只在纯测试新建目录使用 Windows extended pathname，不改本机 Git 或系统设置。

最小运行复制清单是新 helper、一个 fixture 和一个新 workflow，并为这些新路径添加精确 LF 规则；另三个唯一前缀文档供 review/溯源。没有覆盖已有通用 README、STATUS、固定输入记录或旧 helper。完整复制路径和 SHA 见外层 COPY_LIST.json；补丁新增这六个文件，另只向 `.gitattributes` 追加新路径 LF 规则，既有规则和默认值不变。Windows 默认行尾转换会改变 fixture 字节，不能省略其精确路径 LF 规则；应用补丁时可用单次 `git -c core.autocrlf=false -c core.eol=lf apply ...`，或直接复制冻结原字节后追加规则。隔离核验没有改本机全局 Git 配置。
