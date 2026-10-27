# Phase 8 S2B-F1 — Free Text Preview / Windows Audio Project Acceptance

**P8-S2B-F1 QUALIFIED ENGINEERING ACCEPTED / CLOSED WITH DECLARED LIMITS — 2026-10-18, Project decision.**

本文忠实登记 Project 对 F1 两个精确提交的独立复审，不重新作出工程验收。仅接受用户主动输入文本、明确点击、`s2.1-pro-free` 临时 MP3 生成、Windows 播放/停止、独立缓存及关闭排水；不等于完整 S2B、S3、正式聊天语音、SaveSlot 音频或 Fish parity。

| Checkpoint | Exact SHA | Direct parent | Scope |
| --- | --- | --- | --- |
| F1 source/test/related Skill | `d00451352d913297a0bce758911e2e1ff5052923` | `3bc271a09377d95e78e68edd8380df96c58bc37e` | 11 files, +828/-13 |
| F1 independent evidence | `7333923a1c224301da7c00c688afaedbecb8a404` | `d00451352d913297a0bce758911e2e1ff5052923` | 342 files, +14603/-0; manifest covers 341 other files |

原始材料：[README](evidence/p8-s2b-f1-20261018/README.md)、[源码身份](evidence/p8-s2b-f1-20261018/source-checkpoint.json)、[完整源码补丁](evidence/p8-s2b-f1-20261018/source-commit.patch)、[证据 manifest](evidence/p8-s2b-f1-20261018/evidence-manifest.json)。Manifest SHA-256 为 `e37754cdaad20c2983474cfc28eabc88951cdaf91c4c5c6aba582574d6d2ef97`，源码补丁 SHA-256 为 `b9b49809863cf35f823e9bda1bcb01bd9b9fd087c1fefbce5c7bde29377b85f0`。Git blobs 与发布原始字节一致，目录级 `.gitattributes` 仅保护本轮原始字节。源码 diff-check PASS；原始日志/patch 的 CRLF/空白告警如实保留，不改写为绿色。

## Independently scoped validation

| Actual execution | Result |
| --- | --- |
| Final focused | 7 suites / 67 tests, 67 PASS, 0 failures/errors/skips, exit0 |
| Bounded Desktop | 155 suites / 1506 tests, 1504 reported PASS / 2 SKIPPED, 0 failures/errors, exit0; same four exclusions |
| Exact Windows distribution | createDistributable exit0; 212 files verified before/after manual |

两次执行不累加冒充单次验证。179 份原始 XML 包含独立 RED/PASS 执行；环境准入失败、test compile RED、stop/replay race、取消夹具 RED 和错误命令选择器保留。取消夹具根因是 MockWebServer 对 POST upload 也应用 throttle；早期 native cold-start 假设未证实。修复不弱化安全断言。SharedCore 为 UP-TO-DATE，Android 没有重新执行；四次旧 symlink early-return 仍 NOT TESTED，另有两项 JUnit SKIPPED。

[运行包 manifest](evidence/p8-s2b-f1-20261018/distribution-manifest.json)：Launcher SHA-256 `e5d45e5389e7584b9d8477efe34543de1069e6550560a3a3c53d164152958b8f`；业务 JAR `50d54cf16295be093d75356e717399b0ba076f456e5e296a1abefa875de1ec67`。业务新类与实测编译输出一致；runtime JDK/dependency cache 复用按原日志记录。

[人工表](evidence/p8-s2b-f1-20261018/MANUAL_RESULTS_CN.md) 的七个限定组 PASS，九条用户文字及一张自愿截图分别保留：结构化/自由文本入口、对应语音与停止、首次缓存明确复用、挂起生成关闭、原 W22 挂起目录查询关闭和正常退出。FREEFORM 没有独立重做播放。W22 **仅本轮隔离原生挂起目录查询关闭场景 PASS**；[138 的旧 S2A](138_PHASE8_S2A_PROJECT_ACCEPTANCE.md) 人工 15 PASS/10 NOT TESTED、旧 T2 4 PASS/1 FAIL/20 NOT TESTED 不倒改。两次挂起 POST 是用户明确重试，不是自动重发。

Launcher/JVM 实际退出 **0/0**；运行中第二进程取锁拒绝75，退出后独立重获/释放0；fake 退出0、临时 TLS 材料清理，预览 MP3/`.part` 残留0，两张合成卡字节不变。首次关窗文字后用户澄清主窗口尚在，后续正常关闭后才退出；不得以该观察间隔冒充 shutdown latency。真实生成 helper 与 packaged loopback fake 听觉是两条证据链，不冒充一次 UI 线上端到端调用。

## Live, temporary ownership and limits

F1 真实 GET0/TTS1、HTTP200、exact `s2.1-pro-free`，实际请求一次、自动重试0；只发送合成短句，使用受 OS 保护凭据，无公开凭据。用户实包人工期间真实请求增量0。官方 [免费期公告](https://fish.audio/zh-CN/blog/s2-1-pro-free-api/) 当次核对公告至 **2026-11-30**；账户账单没有独立读取。后续 live gate/发布负责人必须重新核验型号和免费资格，未知或失效时禁止静默变为收费请求；不要求当前每次已确认免费调用重复批准。当前固定模型约束不是永久免费保证。

复用现有 JNA 的 Windows MCI backend 为 **legacy**，不分发 codec、不新增 Gradle 依赖。16 MiB/clip、64 MiB/session、四租约总256 MiB、120秒、原子发布、原始 hash/文件身份及实际 HTTP/native drain 限定接受。容量拒绝可能在已授权请求发出后发生，不是服务端费用预判。未知/替换文件保留；没有正式 S3 repository。资源为采样，bounded 32 次最低 available 930639872 bytes、最低 commit headroom 15895298048 bytes、最高1350 pages/sec，不是绝对峰值。

真实设备拔除/切换、其他 Windows/codec 环境、完整 TOCTOU/ACL、阻塞 I/O 物理绝对超时及断电耐久性仍 NOT TESTED。注入 close failure 不等于真实硬件故障全覆盖。**WIN-AUTO-001 OPEN / CAPTURE INFRASTRUCTURE BLOCKED** 不因听觉/人工 PASS 关闭。

正式 baseline 仍1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。Prompt、Package/Entity schema、正式聊天/SaveSlot 语义不变，Feature Parity 不提升；完整 S2B/S3/P8、QQ、G2-B/C、Writer/Recovery/dispatch 不因本验收获批，不合并 desktop、不发布。F2 为另行明确授权的已有样本试听切片。

本登记不重跑测试、不重新验收历史全部工程。**Tests rerun for this documentation: NO — reused Project-reviewed F1 evidence.**

**F1 ACCEPTANCE DOCUMENTED / PROJECT DOC DIFF REVIEW PENDING**
