# Phase 8 S2B-F2 — Official MP3 Sample Preview Project Acceptance

**P8-S2B-F2 QUALIFIED ENGINEERING ACCEPTED / CLOSED WITH DECLARED LIMITS — 2026-10-18，Project 独立裁定。**

本文仅登记已经完成的 Project 复审，不重新作出工程验收。接受固定安全 profile 下官方 MP3 样本的显式匿名下载、临时验证、Windows 原生试听/停止、独立缓存、取消和真实排水；不代表完整 S2B、S3 或 Fish parity。**UI-LAYOUT-001 OPEN / NEXT UX GATE**；**WIN-AUTO-001 OPEN / CAPTURE INFRASTRUCTURE BLOCKED**。

| Checkpoint | Exact SHA | Direct parent | Scope |
|---|---|---|---|
| F2 final source/test | `0b0a2f6c88555cc1eecbf1ea6370cd88582418bc` | `e3e0dcc1ee3324caf5e1f8f321b03449e716a061` | 5 files +505/-11; includes frozen F2/R1 drafts; R2 vs R1 changes only test assertions +34/-5 |
| F2 independent final evidence | `642e70776b26fc3e8bd396b785e3757f3e6c96a0` | `0b0a2f6c88555cc1eecbf1ea6370cd88582418bc` | 287 files +11098/-0; manifest covers286 other files |

原始 [README](evidence/p8-s2b-f2-r2-20261018/README.md)、[源码身份](evidence/p8-s2b-f2-r2-20261018/source-checkpoint.json)、[完整补丁](evidence/p8-s2b-f2-r2-20261018/source-commit.patch)、[manifest](evidence/p8-s2b-f2-r2-20261018/evidence-manifest.json) 分别保留。Manifest SHA-256 `f770539f46614837262df0d881c3257e05d65a4bf7c06e20f1ba364e92658e38`；源码补丁 SHA-256 `ea66a23a203d52981c6b07dbcb26cdd52a81cf00251fd71e938b3c86e1488b38`。源文件 Git blob/LF 与 raw worktree 字节身份分开记录；证据 index blobs 与发布原始字节一致。

## Scoped engineering and Windows evidence

| Actual independent execution | Result |
|---|---|
| Exact HTTP/exception-chain method | 1 suite /1 test PASS, exit0 |
| F2 Sample/Presentation | 2 suites /16 tests PASS, exit0 |
| Affected F1 Preview/Windows/Library/Settings | 6 suites /60 tests PASS, exit0 |
| Bounded Desktop non-Native | 156 suites /1515 tests;1513 reported PASS,2 SKIPPED,0 failures/errors,exit0; unchanged four exclusions |
| Exact Windows distribution | createDistributable exit0;212 files hashed before/after manual |

1/16/60 是三次独立聚焦执行，不与 bounded 相加。165份原始 XML 按阶段独立保留。Desktop test compilation 实际执行；日志中 main/sharedCore 为 UP-TO-DATE 的任务不冒充重新编译，Android 未重跑。使用 JDK17、offline、no-daemon、single worker、low priority、Kotlin in-process、原2GiB heap，没有 clean/rerun-tasks。

HTTP301/302/307/401/403/429/503各一次实际请求；503首次状态保留，interceptor进入2次、guard1次、物理发送1次、凭据头0；拒绝的 follow-up 不计为第二次实际发送。DNS/TLS/IO仍为固定脱敏异常，检查有界cause/suppressed链而不要求cause为空。来源/认证范围没有放宽；不修改共享RequestFactory。

运行包 [manifest](evidence/p8-s2b-f2-r2-20261018/distribution-manifest.json) SHA-256 `d83c7a8f0ed342a25c4a76d46bdbc03b448e6ff5b6e35b2f702eca7629b3c054`；Launcher SHA-256 `e5d45e5389e7584b9d8477efe34543de1069e6550560a3a3c53d164152958b8f`；业务JAR SHA-256 `6e4fbdb70a1df7b5708766e04703e9bd20772bedcb3ef83a4455ad9d254df721`，六个关键生产class与编译输出一致。ApplicationHome仍为 `$ROOTDIR`。

[七项人工签署](evidence/p8-s2b-f2-r2-20261018/MANUAL_RESULTS_CN.md) 接受：STRUCTURED入口、官方样本实际可听/停止、切换、生成/样本缓存分离、挂起下载关闭及未绑定、FREEFORM入口、正常关闭。文字签署与两张用户自愿截图独立保存，不冒充自动截图。缓存检查中用户先点到对照卡、随后澄清并准确核对官方卡，原观察不删除，不伪装成一次即通过。无理由重复F1全套人工验收未发生。

Launcher/JVM通过路径、创建时间、父子关系和保留进程句柄确认实际退出 **0/0**；运行中独立取锁拒绝75，退出后独立重获/释放0。停止后3份缓存音频独占打开成功；挂起body只发128字节，客户端EOF约0.645秒后观察到，picker关闭后MP3/part0。fake退出0，临时TLS材料清理；两张合成卡hash不变。关窗签署后的marker晚于实际退出，不据此声称关闭延迟。

## Live qualification and declared limits

真实匿名官方样本 **GET1/TTS0**、HTTP200、单次物理发送、无凭据头/自动重试；另有一次官方公开HTML来源读取，与样本GET分开计数。只接受已批准精确 `https://platform.r2.fish.audio` origin及固定无签名MP3路径，不接受未知CDN、WAV、签名URL、userinfo、异常端口、redirect或降级，不扩大Bearer范围。公开音频405420字节，SHA-256 `c730e4c467f8dcf465acca9e426ea6349137e3302ff65e648f66dc2a63e3301f`，仅留本地；含其他签名能力的原HTML不公开。未读取真实SecretStore/账户账单。live helper下载验证与实包loopback fake人工听觉是两条证据链，不冒充GUI直接线上端到端。

复用F1 MCI/store/runtime；16MiB/clip、64MiB/session、四租约、120秒、sample与generated不同cache身份不变。MCI legacy、设备/codec兼容、完整Windows TOCTOU/ACL、阻塞I/O物理绝对超时、断电耐久性、proxy远端DNS不可本地证明等限制保留。50次资源采样最低available约1.3456GiB、commit余量约15.3418GiB、最高5114 pages/sec，为采样极值，不是绝对峰值；未见OOM。

首轮F2 RED `ade2680b97397814b4f0686c2d2766655d37be63`、R1诊断/断言RED `e3e0dcc1ee3324caf5e1f8f321b03449e716a061` 与全部原manifest/日志/XML保留；两项JUnit SKIPPED与四次旧symlink early-return NOT TESTED分开。原始日志/补丁空白告警不修剪；source/metadata diff-check PASS。历史F1/S2A人工状态不倒改。真实设备故障、完整GUI/真实symlink/完整TOCTOU等未测分支不因本轮绿色升级。

## Next UX gate and authority

[UI-LAYOUT-001源码对照](evidence/p8-s2b-f2-r2-20261018/UI-LAYOUT-001_SOURCE_COMPARISON.md) 与用户截图确认布局臃肿；**OPEN / NEXT UX GATE**，待独立Studio风格响应式布局、原生最大化/还原及用户确认，不能因F2工程接受自动关闭。WIN-AUTO-001继续OPEN，人工听觉不修复捕获基础设施。

正式baseline仍1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`；Feature Parity不提升，Prompt、Package/Entity/schema、S3正式仓库、聊天语音、SaveSlot/QQ、G2 Writer/Recovery/dispatch均不获此验收授权，不合并desktop、不发布。F1免费期/后续资格再核验责任沿用[139](139_PHASE8_S2B_F1_PROJECT_ACCEPTANCE.md)，本次TTS0不构成重新核验。

**Tests rerun for this documentation: NO — only Project-reviewed evidence registered.**

**F2 ACCEPTANCE DOCUMENTED / PROJECT DOC DIFF REVIEW PENDING**
