# Phase 8 S2B UI-LAYOUT-001 — Project Acceptance

**P8-S2B-UI-LAYOUT-001 QUALIFIED ENGINEERING ACCEPTED / CLOSED WITH DECLARED LIMITS — 2026-10-19（JST），Project 独立裁定。**

本文件只登记 Project 对实际 GitHub 源码、R7 精确发行包、M1–M4 用户签署、原始 fake 请求、进程、数据根锁和清理证据的复审结论。接受 Studio 风格音色库的响应式布局、操作层级、Escape/Stop、常驻滚动条、页码输入跳转及限定状态保留；**不是完整 S2B/P8 Fish parity、full bounded GREEN、Public Preview 或 release acceptance**。旧候选/HOLD/OPEN 报告保留当时含义，不倒改原始证据。

## Exact source and evidence chain

活动分支 `feature/phase8-fish-audio`；登记起点为下表 R7 人工证据 HEAD。正式 upstream baseline 仍是 ChatBar **1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**。本地旧 `desktop` 引用不更新，远端 `desktop` 仍为 `5ef95428421f7c693b010ac88f51d470804ab07a`，未合并。

| Checkpoint | Full SHA | Direct parent |
|---|---|---|
| Conditional source candidate | `ec32fdbc8383853b3024b585c19db0c3b840f47f` | `044919ed9fd0c75b56b1843644012ff263ce99c3` |
| Source candidate independent evidence | `bad9f3c235dbb1a88af5e3fb6cbdf4aa7418cbd5` | `ec32fdbc8383853b3024b585c19db0c3b840f47f` |
| R7 exact package / isolated preparation evidence | `079428499fd3d4c36ef8ee2e6e60b3528d4787e4` | `bad9f3c235dbb1a88af5e3fb6cbdf4aa7418cbd5` |
| R7 independent manual evidence | `dcfd61d52f0ef5851d2396ac8ef01fc11f1ffa39` | `079428499fd3d4c36ef8ee2e6e60b3528d4787e4` |

候选严格三文件 **+787/-35**：

| File | Diff | Raw bytes / SHA-256 |
|---|---|---|
| DesktopFishPickerPresentation.kt | +64/-29 | 17883 / `194d6b7fd2e270ca29eb90734303c8a7898825ecf8939cc60ab266e8d16c4917` |
| DesktopFishPickerWindow.kt | +24/-6 | 4280 / `a003e7ad1e31a0c356df1a4291cc7eb1e4eb9fa958a0a6353ee6a77ac8f88125` |
| DesktopFishPickerNativeUxTest.kt | +699/-0 | 49505 / `8beee5971355cd894b44a4743f89b6ad857c342274ae8410cfab5e99a3bbb423` |

Project 已核对两份生产源码与 R6G 测试源码身份；R6H–R6J 临时 Win32 探针/人工 runner/握手不进入长期候选，原人工 runner 已完整保全。详见[候选登记及差分](evidence/p8-s2b-ui-layout-001-source-candidate-20261019/README.md)、[精确源码身份](evidence/p8-s2b-ui-layout-001-source-candidate-20261019/candidate-identity.json)。候选时唯一 compileTestKotlin exit0 为 **FROM-CACHE**，不冒充实际重编译或新测试 PASS。

## R7 Windows and user acceptance

准备证据：[R7 preparation](evidence/p8-s2b-ui-layout-001-r7-20261019/README.md)；独立人工证据：[R7 manual gate](evidence/p8-s2b-ui-layout-001-r7-20261019/manual-gate/README.md)。两份 manifest SHA-256 分别为 `ff6d5c7cba62c55c4dc6052ca1093374bc5f0122e6794854af214b529b1c9e73` / `97ad357724d78177ef9c708884372cd415f3b003315195dcab3de6c6892abb60`；原成员长度/hash 静态核验匹配。

| Group | User result | Accepted observation / retained limit |
|---|---|---|
| M1 — Escape | PASS | 344dp 真实紧凑浮层，标签框取得焦点后第一次只收起面板，第二次才关闭 Picker；编辑器恢复。原生宽度与 owner 恢复另有只读证据。 |
| M2 — Stop | PASS | 样本/模拟生成实际可听，Stop 图标及文字可见且有效，等待下载可取消。紧凑 Stop 有用户 PASS，但停止后原生采样为1064dp；缺少播放时同步紧凑 Popup 几何采样。 |
| M3 — Scrollbar / pager | PASS | 真实拖动及滚轮、Enter 跳3页、点击跳2页、越界4拒绝、切页归顶及窄窗输入/跳转配对可用。受控区间请求确为[3,2]，无4。用户补漏的额外提交干扰了随后 input-only 无自动查询的独立验证；该技术分支保留未独立确认。 |
| M4 — State / cache / owner | PASS | 宽窄状态、R7筛选、原文本、A目标保留；首次生成缓存提示正确，样本/生成音频不串用，关闭 Picker 后编辑器正常、Alice未绑定。resize及缓存重播 HTTP增量均0。 |

[原始用户记录](evidence/p8-s2b-ui-layout-001-r7-20261019/manual-gate/manual-user-records.jsonl)、[分项结果](evidence/p8-s2b-ui-layout-001-r7-20261019/manual-gate/manual-results.json)、[实际几何](evidence/p8-s2b-ui-layout-001-r7-20261019/manual-gate/geometry-observations.json) 分开保存。**用户听觉/操作签署不是自动截图或自动化 PASS**；回复收集时间不冒充物理操作时刻。R7 未运行 Robot/JUnit/bounded/R6人工 runner。

精确包在 `bad9f3c235dbb1a88af5e3fb6cbdf4aa7418cbd5` 构建，生产源码来自上述候选。一次 JDK17 离线 `createDistributable --rerun-tasks` exit0，sharedCore/desktop compileKotlin 均实际 EXECUTED；[task ledger](evidence/p8-s2b-ui-layout-001-r7-20261019/task-execution-ledger.json) 与警告原日志保留。发行包 **212文件**、17个 Fish UI 类及嵌套类匹配新编译输出，全部成员退出前后清单一致，无缺失、额外或hash不匹配；不把未变化的通用 Launcher hash 单独作为新包身份证明。

[发行包全清单](evidence/p8-s2b-ui-layout-001-r7-20261019/distribution-manifest.json) SHA-256 `27139df358ad675df8d70831e91caf2356a9a008f68c95296a1a98d6cedd52d3`；Launcher SHA-256 `e5d45e5389e7584b9d8477efe34543de1069e6550560a3a3c53d164152958b8f`；业务 JAR `app/desktopApp-8ffbc49b88ca248b54ea8526daf3a.jar` SHA-256 `22abbc0cf0e4688743bcc2f6433624e57a1415d20adb91cdb3c2b4b148c2b82f`。

只使用独立 LOCALAPPDATA、明确 selected root、合成角色/会话与合成凭据、loopback fake；未读取真实用户数据/密钥，未改变全局代理/证书。人工 fake **catalog GET8 / sample GET3 / canned TTS POST2**；准备阶段的2/1/1另记，不混入人工计数；真实 Fish **GET0/TTS0**。[原始请求](evidence/p8-s2b-ui-layout-001-r7-20261019/manual-gate/mock-service/events.jsonl) 与[计数说明](evidence/p8-s2b-ui-layout-001-r7-20261019/manual-gate/request-accounting.json) 保留额外操作及两次挂起 body drain，不将其擅自归类为隐式重试。

Launcher/JVM 通过实际进程身份/句柄确认退出 **0/0**；运行中独立根锁获取拒绝 **75**，退出后独立重获/释放 **0**。fake 排水退出 **0**，预览 MP3/.part 残留0，临时 TLS 清理，无残留本轮应用/Java；两份合成角色卡前后hash不变。[退出/锁/清理](evidence/p8-s2b-ui-layout-001-r7-20261019/manual-gate/exit-lock-cleanup.json) 与[原始进程结果](evidence/p8-s2b-ui-layout-001-r7-20261019/manual-gate/runs/manual01/process-result.json) 保留。窗口由用户正常关闭，不因采集结束自动销毁；未提前写关闭marker，原watch字段null不倒填。合成数据、固定音频夹具和冻结包保留，不删除根锁文件。

## Declared limits and follow-up owners

1. R6D/R6E Native历史 **4/4 PASS** 使用较早测试源码 SHA-256 `fdddf82225d120777c96fd8127f1584f4139c2767e5bbf0436c12db31783d238`；不能替代候选 R6G 版本的实际结果，也不与R7人工计数合并。
2. R6E 整体 bounded 仍有独立 Clipboard `cmd.exe` RED；R6F/R6G Robot Press0 RED，历史原因 **UNKNOWN**，未被R7修复或撤销。
3. R6I/R6J 人工采集 **INCONCLUSIVE** 原样保留，用户实际滚动报告与不完整记录分开；R7不倒改它们。
4. 两项历史 symlink **SKIPPED** 与旧 **NOT TESTED** 保持。候选没有本轮 **full bounded GREEN**，R7也未重跑它。
5. M3 input-only技术分支未独立确认；M2紧凑Stop缺同步原生几何。只读观察脚本算术错误/修正及受额外提交影响的计数断言已保全，不写成产品/JUnit新PASS或抹去异常。
6. 等待生成的独立取消、真实Fish线上服务、完整设备故障、完整整型边界/未知总数、R7新FREEFORM/最大化重验等未测分支不因本UI验收完成。F1/F2既有 MCI legacy、设备/codec兼容、TOCTOU、物理绝对超时等限制仍适用。
7. 官方样本 `sample.text` 目前最多两行/ellipsis；缺少文本时不补造。R7夹具是说明文字，不是音频逐字稿。**完整试听文本对照列为后续 UX 评估**；没有完整展开逐字稿、自动转写或同步字幕能力声明，本轮不实现。
8. **WIN-AUTO-001 OPEN**：独立捕获基础设施问题，人工PASS不能关闭，也不能自动作为R6 Robot RED的根因。
9. **WIN-BRAND-001 OPEN / DEFERRED**：后续系统诊断/错误JFrame复用 DesktopBrandResources 官方Logo、原生MessageBox单独评估，需真实Windows实包人工验证。与P7-WIN-02不同，不套用其PASS；不改现有诊断、安全关闭、锁/重试/生命周期语义，本轮不实施。

## Current scope and next authority

| Slice / milestone | Current status |
|---|---|
| UI-LAYOUT-001 | **CLOSED / QUALIFIED ENGINEERING ACCEPTED**, with the above declared limits |
| S2B-F1 / S2B-F2 | 各自 **QUALIFIED ENGINEERING ACCEPTED / CLOSED WITH DECLARED LIMITS** 不变，见[139](139_PHASE8_S2B_F1_PROJECT_ACCEPTANCE.md)/[140](140_PHASE8_S2B_F2_PROJECT_ACCEPTANCE.md) |
| 完整 P8 Fish parity / 完整S2B | **PENDING**；不提升13中功能行 |
| P8-S3正式音频仓库及后续语音链路 | **PENDING / 独立合同待冻结**，无自动实施授权 |
| P9/P10/P11/Public Preview/release | 状态不因本轮升级 |

本登记只取代本UI切片旧OPEN/NEXT/HOLD的当前状态；原始证据保留当时裁定。无 desktop merge、upstream sync、发布、S3/G2生产Writer/Recovery或后续Phase实施授权。Prompt、Package/Entity/schema、Secrets、baseline和历史数据不变。

四份user-owned文件继续 **KEEP / UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED**。本轮只作五份Markdown范围、链接、提交来源/状态一致性与 `git diff --check` 静态检查；Fish Skill已复核，文档登记没有使其运行语义说明过时，不改Skill。

**Tests rerun: NO.** 未运行 Gradle/JUnit/Robot/bounded/Windows GUI，未重新打包或调用Fish。下一项由Project另行冻结正式Fish音频仓库的上游依据与Codex合同。

**UI-LAYOUT-001 ACCEPTANCE DOCUMENTED / PROJECT DOC DIFF REVIEW PENDING — STOP。**
