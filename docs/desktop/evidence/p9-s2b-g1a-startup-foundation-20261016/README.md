# G1A EVIDENCE PUBLISHED / SOURCE UNVALIDATED / BUILD BLOCKED / PROJECT STATIC REVIEW PENDING

## G1A-E1 evidence-only handoff and metadata correction

本次仅交接证据，七份未经编译的源码草稿不暂存、不提交、不修改；没有运行Gradle、测试或G1B。下文原始验证快照中的“未提交/未push”是当时的历史状态；本次仅证据目录通过独立提交交给Project静态审查，不代表源码编译或工程验收。

Project批准窄修正 `01-interrupted-stage.json` 的派生字段 `approximateSecondsFromLogCreation`：原值 `32506.6433509` 秒改为 `106.6433509` 秒，差额 `32400` 秒。依据仅为原有UTC时间戳 `2026-10-15T20:49:51.3008984Z` 与 `2026-10-15T20:51:37.9442493Z`，用DateTimeOffset相减并按ticks换算秒；原时间戳不变。这仍是日志创建至停止观测的间隔，不是精确Gradle耗时。错误原因尚未最终证明，不能仅凭9小时时差断言时区混算已被证实。

原中断JSON已逐字节保留为 `01-interrupted-stage.pre-correction.json`，1831 bytes，原SHA-256为 `dfc3531a65059a688f9bb130868dbbc2736a2aa9242a5b930d03efabaaf521b0`。修正只替换该数字token，不改其他JSON内容、Gradle日志或资源样本；前后hash及计算依据见 `G1A-E1-correction-record.json`。

`raw-copy-verification.json`的中断JSON条目改指历史副本，六份原始副本hash/长度保持不变，旧清单亦原样保留为 `raw-copy-verification.pre-correction.json`；build目录原件未修改。其余派生文件未发现错误duration数字引用。新清单覆盖13项文件，另有manifest自身，共14份证据文件；不再声称旧10/10。补丁仍为55,314 bytes，SHA-256 `22ea98b74c82043a52c0cfd9be590bde78b37ce82a1117bae441068b22874f68`，与即时Git diff逐字节一致。

## Original validation snapshot — historical, not rerun by E1

2026-10-16。**UNCOMMITTED SOURCE DRAFT / VALIDATION BLOCKED — MEMORY PRESSURE。** 尚未满足经验证源码提交门槛，不是G1A工程完成或Project acceptance。未创建源码/证据commit，未push，不开始G1B。

## Control and scope

原始local/tracking/live feature均为 `a03fbb997de75084cda1a836663042ee183156b5`；origin/desktop为 `5ef95428421f7c693b010ac88f51d470804ab07a`，起始index/worktree clean。baseline1.4.1、其他分支和已有G0源码/测试及验收证据均未改动。

当前仅七份Kotlin草稿 **+629/-71**：DesktopDataDirectory、DesktopPortableRoot、Main及DataDirectory/PortableRoot/ApplicationLifecycle/ProcessLifecycle四份测试。没有新增生产文件。`complete-working-tree-g1a.patch`包含全部七份源码修改，排除本证据目录自身；`source-manifest.json`记录各文件实际SHA-256、字节数、父blob及原始工作树Git object。不要将LF/CRLF差异当作相同字节。

## Source strategy — NOT COMPILED OR TESTED

- 单一root-authority选择函数保留CLI → Portable → bootstrap → default优先级；新selectRoot返回Selected / Failed / PortablePendingActivation。只读选择不执行probe、不创建bootstrap/marker/UserData。旧resolveRoot继续原有legacy activation，不提前切换Main启动顺序。
- Pending持有ApplicationHome、marker、UserData的只读Windows文件身份和必要元数据。显式activatePortable要求已有ownership，重新解析并比较身份，在probe后再次复核；失败不回退。保留现有write/flush/delete和retained-probe分类。G0不被调用或修改；此Portable局部身份快照不是第二套root-authority resolver。
- initialize移入生命周期try内，之后仍按既有close链排水；原初始化/取消异常保持primary，close异常按顺序suppressed。未确认的close异常以无cause的私有suppressed标记供既有ownership retention检测，保留原Throwable身份，避免自抑制。未改TaskRuntime → Draft → Backup → Coordinator → Migration顺序或重试机制。
- 草稿测试包含选择阶段的文件列表/合成文件SHA-256/creation与mtime对照、默认/custom/无效bootstrap、Portable优先级/失效/搬移、owned激活与probe故障、身份替换、取消与抑制顺序、真实Container在备份scheduler启动后注入preset读取失败，以及CompletableDeferred控制真实TaskRuntime清理写入和root锁保留。预设故障通过注入assetReader抛错，不读取preset/seed产品资产。

上述为待复审实现意图与测试源码，**不能作为运行行为PASS**。已有旧测试中“初始化失败不close”和“未确认close失败仍释放ownership”的预期依本次批准的安全行为调整，仍待实际验证。未取得真实Main/EXE、平台或并发验收。

## Actual validation attempt

仅启动一次Gradle，JDK Temurin17.0.20.1+1，现有Gradle9.1.0/2GB heap未改：

```powershell
gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 --priority=low --console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process :desktopApp:test --tests com.example.chatbar.desktop.DesktopDataDirectoryTest --tests com.example.chatbar.desktop.DesktopPortableRootTest --tests com.example.chatbar.desktop.DesktopDataRootOwnershipTest --tests com.example.chatbar.desktop.DesktopApplicationLifecycleTest --tests com.example.chatbar.desktop.DesktopProcessLifecycleTest --tests com.example.chatbar.desktop.DesktopIndexStartupPreflightTest
```

原始日志仅确认SharedCore compile/classes/jar为UP-TO-DATE；没有Desktop main/test编译完成证据，没有新JUnit执行或XML。会话收到Ctrl+C后返回1，这是被中断的runner退出码，**不是Kotlin编译错误或JUnit RED**。runner正常结束JSON未生成，另存`01-interrupted-stage.json`明确区分。耗时只以日志creation估算，不能冒充精确Gradle计时。

| Gate | Result |
| --- | --- |
| Desktop main compilation | BLOCKED / incomplete |
| Desktop test compilation | NOT RUN |
| Six requested focused classes | NOT RUN；0份新XML，不能引用旧G0的86项为本轮PASS |
| Static scope / git diff --check | PASS for retained draft |
| Source/evidence commits and push | NOT RUN；未达到验证门槛 |
| Android/full-P9/full-Desktop/EXE/GUI/package/API | NOT RUN |

## Resource STOP and evidence

开始可用779.70MiB、Commit余量6.48GiB、339 pages/s、Java0。20:50:27Z样本可用76.07MiB、Commit5.08GiB、4592 pages/s；20:51:02Z复采可用63.66MiB、Commit4.68GiB、7752 pages/s，连续危险水位构成持续严重资源风险，停止进一步重型工作。

仅向本轮exec会话发送Ctrl+C；20:51:37Z确认Java0、可用924.41MiB、Commit5.73GiB、1356 pages/s、H余量37.95GiB。没有强制结束用户进程、操作Chrome/NAI、修改分页文件或清缓存。资源为周期采样，不代表绝对峰值或前台响应验收；不中途再启动第二个Gradle或增大heap。

原始日志、资源和中断状态独立保留，未覆盖以往RED/PASS/历史证据。由于尚未进入测试，本轮没有应上传的新JUnit XML；未来验证必须在同一源码身份上实际编译并执行全部六类，保留其真实RED/skip，不能伪造XML或删减断言。

`raw-copy-verification.json`确认本地原件复制字节一致，`evidence-manifest.json`覆盖除自身以外全部本目录文件；内容仅合成源码及脱敏资源/构建输出，无Secrets或真实用户数据。G0、CURRENT、决策、正式验收文件、Android/sharedCore业务及Prompt/schema均保持原样。

下一窗口先核对七文件manifest、HEAD和空暂存区，取得可承受一次受限编译的资源条件后，继续同一六类验证；通过前不提交源码候选，不宣称G1A验收或接入G1B。
