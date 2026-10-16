# P9-S2B-G1B Project Acceptance

日期：2026-10-17。Project 独立裁定：**G1B CLOSED / QUALIFIED ENGINEERING ACCEPTED**。本文件仅登记已经作出的限定工程验收，不重新实施、测试或作出工程裁定，不授权 G2、生产 Journal/Writer/Recovery。

## Verified checkpoints and evidence identities

| Checkpoint | Exact commit | Direct parent |
| --- | --- | --- |
| G1B-A 源码及测试 | `a8990b34073647169e7e1a62b2d0e969323cddf6` | `90182962fd1a37954c193c4cd7d5215123f0d126` |
| G1B-A 原始验证证据 | `5294cf4502d91f810f8ad6b2b6f7d2ae2eeccd03` | `a8990b34073647169e7e1a62b2d0e969323cddf6` |
| G1B-B 实包证据 | `4649ef96680337dea073bb26f810f57397c1cd57` | `5294cf4502d91f810f8ad6b2b6f7d2ae2eeccd03` |
| G1B-BV 人工可视签署证据 | `d7aa4b68ae75b2ec68ed8d5517c57348597aa973` | `4649ef96680337dea073bb26f810f57397c1cd57` |

以上为单父线性链。源码提交仅七文件，**+558/-13**：六份 Kotlin 文件（`DesktopDataDirectory.kt`、`DesktopProcessLifecycle.kt`、`DesktopStartupBlockedPresenter.kt`、`DesktopStartupGate.kt`、`Main.kt`、`DesktopStartupGateTest.kt`）及对应 Feature Map Skill 维护。后三次提交只提供独立证据，不改变该生产源码。

Project 已独立审查真实 GitHub 源码、原始 JUnit XML、证据文件身份、实包进程记录、合成数据清单及人工签署。证据入口：

| Evidence directory under docs/desktop/evidence/ | Files / manifest entries | Manifest SHA-256 |
| --- | --- | --- |
| `p9-s2b-g1b-a-startup-ingress-20261016/` | 50 / 49 | `f18e24bf3f8a381a6790dc9585cf90ed8c082798e630579b17db0c9d08eecee5` |
| `p9-s2b-g1b-b-packaged-smoke-20261016/` | 208 / 207 | `f57e50eb84712b50c2b0140b5f0ffd168c63d38d0e6e073bace221bc40b7295c` |
| `p9-s2b-g1b-bv-window-text-20261017/` | 72 / 71 | `4d6449aa46f441a2ed014341ca4e21317d5baaf6a4e171a1c4db2c62120f8818` |

各 manifest 不包含自身。Windows 工作树字节与 Git blob 按对应身份记录区分；公开包中的原始字节和脱敏派生件按 provenance 区分，不把派生件冒充未经处理的原件。私有路径原件和完整 runtime image 留在本地，公开记录的独立复核边界继续有效。

## Reviewed JVM validation and preserved RED

既有 Temurin JDK17、Gradle 9.1.0、offline、no-daemon、单 worker、low priority、Kotlin in-process 和原 2 GB heap。最终聚焦结果：

| JUnit suite | Tests | Reported PASS | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| DesktopApplicationLifecycleTest | 39 | 39 | 0 | 0 | 0 |
| DesktopDataDirectoryTest | 13 | 13 | 0 | 0 | 0 |
| DesktopDataRootOwnershipTest | 14 | 14 | 0 | 0 | 0 |
| DesktopIndexStartupPreflightTest | 19 | 18 | 0 | 0 | 1 |
| DesktopPortableRootTest | 16 | 16 | 0 | 0 | 0 |
| DesktopProcessLifecycleTest | 15 | 15 | 0 | 0 | 0 |
| DesktopStartupGateTest | 20 | 20 | 0 | 0 | 0 |
| **Total: 7 suites** | **136** | **135** | **0** | **0** | **1** |

Desktop main/test 编译曾实际执行成功；最终运行 exit 0，main 编译对同一生产输入为 UP-TO-DATE，test 编译及 test 实际执行。SharedCore 前置任务 UP-TO-DATE，不声称本次新编译 SharedCore；Android/full regression 未运行。七份首轮 RED XML 与七份最终 XML 分别保留。

首轮 exit 1：136 tests、132 reported PASS、3 failures、0 errors、1 skipped。夹具对持锁文件另开内容读取通道，并且部分调用漏注入 headless presenter，导致合成阻断提示等待关闭；已保留线程观察、正常关闭记录、原日志和源码身份。限定修正只调整测试对 ownership 基础设施的观察方式并补齐 headless 注入，保留行为断言，生产输入不变。不得把首轮 RED 改为 PASS 或称作实包 GUI 验收。

**四次 symlink fixture early-return 仍为 NOT TESTED**，与上述 **1 项 JUnit SKIPPED** 分开；不能计为额外 skipped 或成功覆盖。40 条 G0 零写入事件为 38 条快照一致和 2 条平台限制，不代表整个业务启动零写入。

## Packaged B1–B6 qualified scope

现有精确源码 Windows app image：212 个普通文件，第二次完整读回 212/212；原包及四份隔离副本基础文件的后续核对为 212/212 和 848/848。BV 启动前对其使用的三份副本又读回 636/636。既有 packageVersion 1.3.49 仅为打包元数据，不代表更改 upstream baseline。Launcher SHA-256 为 `e5d45e5389e7584b9d8477efe34543de1069e6550560a3a3c53d164152958b8f`；业务 JAR SHA-256 为 `423f1e87cb083fd30544c6c93d820b19bb335f2371d25e355d64a6f2ba9402d3`，完整资源身份见 package manifest。

| Original G1B-B scenario | 限定验收事实 |
| --- | --- |
| B1 普通启动/退出 | 四次 launch/close，后三次完整 0/0，含同一合成根重开；首次 JVM 退出码 UNKNOWN 保留。确认原生业务窗口、进程消失及锁重获，不签署 Compose 内容或完整 GUI。 |
| B2 空/未知候选域 | 两种隔离根均阻断、2/2 退出，不构造业务窗口或产生初始化写入；候选内容保全，仅 ownership 基础设施新增，锁可重获。正文可视缺口由后续 BV-B2 用户签署补齐。 |
| B3 Portable 正常启动 | 两次 0/0、实际 UserData 归属、探针 create/delete 且无残留，无默认根 fallback，同根重开。 |
| B4 Portable 首次 G0 阻断 | 原运行 **B4-01 的 2/2**、零观察到的 probe/零 watcher errors、无业务/fallback 写入、数据保全及锁重获。此 2/2 不属于 BV-B4。正文由后续 BV-B4 用户签署补齐。 |
| B5 无效 authority | 无效 marker、损坏 bootstrap 均固定分类拒绝且原运行 2/2，无 ownership/probe/business/default fallback；夹具字节保全。两种 ROOT 正文分别由 BV 签署补齐。 |
| B6 同根双实例 | 第二实例沿用 AlreadyInUse，未误报为 G0 拒绝；第一实例继续运行/持锁，锁身份及数据清单不变；正常关闭及新进程同根重开成立。 |

原 B1–B6 共 14 次 Launcher 启动、28 个进程代际均确认消失；首轮 JVM code UNKNOWN 不倒改。进程以路径、父子关系和创建时间识别，不能单凭 PID、窗口消失或 MainWindowHandle=0 推断退出。合成 child LOCALAPPDATA、独立 Portable copy、锁探测与文件/目录大小/哈希清单的范围保持；正常业务初始化写盘和 ownership 基础设施不混算成 G0 写入。

## BV manual visual signatures and retained limitations

| BV run | 用户确认的实际诊断码及固定中英文正文/Close | 本轮退出证据 |
| --- | --- | --- |
| BV-B2 | `G0_DOMAIN_PRESENT_UNSUPPORTED`，用户目视签署并正常关闭 | Launcher/JVM 2/2，独立确认消失、数据不变及锁重获 |
| BV-B4 | `G0_DOMAIN_PRESENT_UNSUPPORTED`，用户目视签署并正常关闭 | 两个确切进程已消失，数据不变、锁重获；**退出码 UNKNOWN / UNKNOWN** |
| BV-B5-marker | `ROOT_PORTABLE_RESOLUTION_FAILED`，用户目视签署并正常关闭 | 2/2；UserData、隔离 LOCALAPPDATA、无效 marker 不变 |
| BV-B5-bootstrap | `ROOT_BOOTSTRAP_LOAD_FAILED`，用户目视签署并正常关闭 | 2/2；损坏 bootstrap 及环境清单不变 |

四份签署是**用户回复的明确转录，不是自动截图、OCR 或签名图像**。Computer Use 枚举初试、重试及重置后重试超时；原 B 还有 FrameArrived/window capture 失败，均保留。BV 的八个进程最终均确认消失，没有强杀、删锁或清理根。

**BV-B2/B4 历史 180 秒窗口观察未确认、600 秒观察器超时全部保留**；B4 追加的有界观察在用户关闭确认前结束。不能声称窗口在规定时限内出现，也不能把包含人工等待的时长当启动延迟通过证据。后续签署、进程消失和锁重获不倒改超时，超时本身也不证明关闭后的 I/O 已排水或失败。BV-B4 的 UNKNOWN 不能借用独立原 B4-01 的 2/2。

JSON 日期转换导致的辅助身份拒绝、修正后的原 UTC 字符串比对、观察器 JVM 字段与独立新鲜模块快照的差异、早读尚不存在的结果、脚本准备错误及超时后未采集完整 stdout/stderr 的限制均按原记录保留。资源数值是采样，不证明绝对峰值或前台响应通过。历史候选 README、HOLD、RED、阶段性 NOT RUN 状态和三个外部未跟踪 JPEG 均不修改；本验收文件记录后来作出的 Project 决定，不覆盖当时证据。

## Accepted behavior and boundaries

本次接受真实 Main 的只读启动准入：read-only root selection → 原 ownership → 容器/Settings/Secrets/后台业务之前的 G0。普通 root 必须 NO_DOMAIN；Portable pending 的 provisional identity 只供获取 ownership，首次 G0 在 probe 前，持同一 ownership activation 后再作一次全新 G0，第二次 NO_DOMAIN 后才继续原业务。优先级/no-fallback、AlreadyInUse、固定脱敏阻断提示、提示失败仍拒绝，以及安全 STARTUP_BLOCKED 与真正 unsafe shutdown 的分流均在限定范围内接受；不弱化 G1A 的 close/drain 与 ownership retention。

NO_DOMAIN 仅指本次可信元数据检查中候选事务域缺失，不表示旧 vector_chunks、replace/backup 或整个 root 安全，也不是 READY/COMMITTED。候选域存在（包括空目录、未知/完成态标记）仍拒绝，不解析事务 payload，不创建或注册生产 Journal，不发布索引、不恢复数据。

**仍未验收：Copy diagnostic、完整 GUI、启动延迟、真实 symlink、持锁真实 root replacement、完整 Windows TOCTOU、域外硬链接不存在性、完整 ACL、阻塞 I/O 物理绝对超时、断电耐久性、全量回归、真实用户迁移及跨端恢复、实际收费 Provider/Embedding。** 安装程序/Release/Public Preview 也不在本次范围。

G0/G1A 既有验收及其当时无生产接入的历史范围保持；后续只读生产接入以本 G1B 结案为准。`127`/`128` 不因此获得广义生产事务实施授权。**G2、生产 Journal/Writer/Recovery、完整 P9 与 Public Preview 继续 PENDING / BLOCKED**，不得自动进入下一阶段或合并 desktop。P8 Fish、P10/P11、D-036/D-037/D-038/D-040、Feature Parity 分类及其他既有决定不提升状态。

正式 validated upstream baseline 仍为 ChatChatBar **1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**。Prompt、Package、Entity/schema、baseline 和历史证据不变。

**Tests rerun: NO — reused Project-reviewed G1B evidence**。本轮只做指定三文档的静态检查；不运行 Gradle、JUnit、GUI、EXE 或打包。文档 diff 仍交 Project 独立复核。

**G1B ACCEPTANCE DOCUMENTED / PROJECT DOC DIFF REVIEW PENDING**
