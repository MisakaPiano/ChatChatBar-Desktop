# P9-S2B-G1A Project Acceptance

日期：2026-10-16。Project 独立裁定：**G1A CLOSED / QUALIFIED ENGINEERING ACCEPTED**。本文件仅登记已经作出的限定工程验收，不重新进行实现、测试或工程裁定，不批准 G1B 生产启动接入。

## Verified checkpoints and identities

| Checkpoint | Exact commit | Direct parent |
| --- | --- | --- |
| 验证起点 | `d047d48c529e28c2c0979ada80073551bcabbd0a` | 本轮验收基准 |
| 源码及测试 | `806785a11446457fdc6bf7e9a6a313e391a9f32b` | `d047d48c529e28c2c0979ada80073551bcabbd0a` |
| 独立原始证据 | `ecd80f670bd02a0519cdfdf0db93cd67216f7c36` | `806785a11446457fdc6bf7e9a6a313e391a9f32b` |

两个提交为单父线性链。源码提交仅七份 Kotlin 文件，**+1057/-85**：Desktop main 的 `DesktopDataDirectory.kt`、`DesktopPortableRoot.kt`、`Main.kt`，以及 Desktop test 的 `DesktopApplicationLifecycleTest.kt`、`DesktopDataDirectoryTest.kt`、`DesktopPortableRootTest.kt`、`DesktopProcessLifecycleTest.kt`。

独立证据目录：`docs/desktop/evidence/p9-s2b-g1a-v1-formal-20261016/`，共 **31 份文件**，其中 `evidence-manifest.json` 覆盖另外 **30 份**。Project 已独立重新计算并确认：

- **30/30** 证据 SHA-256 与字节长度 PASS。
- 七份源码 Git blob、归一化字节身份 **7/7 PASS**；Windows 工作树 SHA-256 与归一化 Git blob 分别核对，不能将 CRLF 原始字节直接混同于 LF blob。
- 完整 R2 源码补丁 SHA-256：`e18b10b53d577e21ca97b656147df668849f9ae29f5b514d5d42178e19cef4d8`。
- Evidence manifest 自身 SHA-256：`7f5865d5c73f71f1ec6dd85016c2256aef967ae0e965a3d3d6c16cd80b8c6c0a`。

完整源码身份和原始验证记录保留在该目录的 `source-commit-receipt.json`、`source-manifest-validated.json`、六份原始 JUnit XML、日志和资源记录中。验证发生于起点 HEAD 加上精确 R2 七文件草稿；源码提交与该验证身份一致，不外推到其他源码版本。

## Reviewed validation and retained history

Desktop `compileKotlin`、`compileTestKotlin`、`test` 均**实际执行并成功**。使用 Temurin JDK 17、Gradle 9.1.0、offline、no-daemon、单 worker、low priority、Kotlin in-process 和既有 2 GB heap；一次 Gradle 调用，exit code **0**，没有 clean、rerun-tasks 或重跑刷绿。

| JUnit suite | Tests | Reported PASS | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| DesktopApplicationLifecycleTest | 39 | 39 | 0 | 0 | 0 |
| DesktopProcessLifecycleTest | 15 | 15 | 0 | 0 | 0 |
| DesktopDataDirectoryTest | 13 | 13 | 0 | 0 | 0 |
| DesktopPortableRootTest | 16 | 16 | 0 | 0 | 0 |
| DesktopDataRootOwnershipTest | 14 | 14 | 0 | 0 | 0 |
| DesktopIndexStartupPreflightTest | 19 | 18 | 0 | 0 | 1 |
| **Total: 6 suites** | **116** | **115** | **0** | **0** | **1** |

1 项真实 symlink 测试为 SKIPPED。另有**四次 symlink fixture early-return**（两次 ownership、两次 Portable 夹具分支），必须独立记为 **NOT TESTED**：它们不计入那 1 项 JUnit skipped，也不能因所属 JUnit 方法报告成功而算作相应路径通过。

40 条零写入记录中，**38 条文件/目录快照一致、2 条平台限制**。这些主要是 G0 测试事件，**不代表整个 Desktop 启动零写入**，也不证明旧向量、replace/backup 或整个用户数据根安全。SharedCore 前置任务为 **UP-TO-DATE**，不声明本轮重新编译验证；Android 未重跑。

单次命令运行约 **663.10 秒**（原记录 663.1015345 秒，UTC 时间差）；Gradle 自报 10 分 51 秒。64 次资源采样最低 Available RAM 约 **0.069 GiB**、最低 Commit headroom **4.480 GiB**、最高 **21,048 pages/sec**，均为采样极值，不是物理绝对峰值或用户前台响应通过证据。本次无 OOM，正常退出后的采样 Java 进程数为零。

历史资源压力 STOP、此前 RESOURCE GATE DEFERRED、E1 元数据时间差修正，以及 G1A、R1、R2 的原始草稿证据、RED、README 和当时 BLOCKED/PENDING 状态均保留，不覆盖或倒改。V1 的新结果不把旧中断/失败变成 PASS。

**Tests rerun: NO — reused Project-reviewed G1A V1 evidence**。本轮仅做三文件 docs-only 静态检查，没有重新运行 Gradle、JUnit、GUI、EXE 或打包。

## Qualified engineering scope

本次限定接受：

- Portable **read-only root selection seam**，及显式持有对应 root ownership 后的 Portable activation。
- Root authority 优先级与 no-fallback 语义：显式 override 优先；Portable authority 在 bootstrap/default 之前；无效权威选择不得悄悄回退到另一个空数据根。
- 初始化失败后仍尝试完整 container close/drain；无法确认关闭完成时保留 ownership，不把超时或异常返回视为写入已经排水。
- 禁用 Throwable suppression 时使用安全异常载体；保全原 primary、close failure 及既有 suppressed 顺序，必要包装不丢弃原异常对象。
- 有界合成测试 teardown、ownership-before-container，以及 `withRoot()` 对意外锁保留、已声明未排水和清理失败的 false-green 拒绝。

**G1A 尚未将 G0 接入 Main。** 现行生产 `Main.kt` 仍走原 `resolveRoot()`；Portable legacy activation 的启动前 write probe 尚未由 G1B 重新排序。已接受的只读 seam 和持锁 activation 不能解释为 production startup zero-write、G0 production ingress、READY/COMMITTED 或正式恢复能力。

G0 仍按 `130_P9_S2B_G0_PROJECT_ACCEPTANCE.md` 的独立无生产入口范围验收；本次不改写它的历史事实。当前状态明确区分：**G0 已验收、G1A 已验收、G1B 尚未实施、G2 未授权**。`127` 仍为生产集成候选合同，`128` 的生产启动门禁接入仍待独立授权；不得据此自动开始 G1B/G2。

## Unverified boundaries and next authorization

仍未证明真实 symlink 路径、持锁期间真实 root replacement、完整 Windows TOCTOU、阻塞 I/O 的物理绝对超时、真实 Windows GUI/EXE 生命周期、打包及全量回归。域外硬链接不存在性、完整 ACL 与断电耐久性也不因本次限定验收成立。

生产 Journal/Writer/Recovery、真实用户数据迁移及跨端恢复仍未验收。G1B/G2、生产 Writer、完整 P9 与 Public Preview 继续 **PENDING / BLOCKED**，需要各自独立批准；P8 Fish、P10/P11 和其他阶段不提升状态。

正式 validated upstream baseline 仍为 ChatChatBar **1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**。不修改 Prompt、Package、Entity/schema、D-036/D-037/D-038/D-040、Feature Parity 分类、roadmap 或 compatibility map，不合并 desktop、不同步 upstream。

本文件只登记 Project 已作出的工程决定；文档 diff 仍须由 Project 独立复核，未经该复核不开始 G1B。

**G1A ACCEPTANCE DOCUMENTED / PROJECT DOC DIFF REVIEW PENDING**
