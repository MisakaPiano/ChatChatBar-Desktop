# P8-S2B-UI-LAYOUT-001-R6E — HOLD / RAW RED PRESERVED

本目录为新的独立执行证据。日期采用 Asia/Tokyo 2026-10-19；原始命令、日志和 XML 保留 UTC 时间。没有建立源码候选提交，没有打包或启动 Windows 人工验收，也未宣称 UI-LAYOUT-001 已验收。

## Git 与冻结身份

起点 `76f527418616e90ed469cabcf0b78c9530563b2f`，直接 parent `b366d3ceb019b0f78ea7ab4d3c3f19508a8389fc`。开始前 fetch；local、tracking、live feature 一致。远端 desktop 为 `5ef95428421f7c693b010ac88f51d470804ab07a`；本地旧 desktop `4c2af2576e2319e8041ffd2a6c5a205450f18246` 保持原样。正式 upstream baseline 仍是 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。

三个实际测试草稿从 preflight 至结束逐字节不变，见 [原始身份](source-identities.json)、[POST 检查](POST-preflight.json) 和 tested-sources/：

| 文件 | 原始 bytes | SHA-256 |
| --- | ---: | --- |
| DesktopFishPickerPresentation.kt | 17883 | `194d6b7fd2e270ca29eb90734303c8a7898825ecf8939cc60ab266e8d16c4917` |
| DesktopFishPickerWindow.kt | 4280 | `a003e7ad1e31a0c356df1a4291cc7eb1e4eb9fa958a0a6353ee6a77ac8f88125` |
| DesktopFishPickerNativeUxTest.kt | 46006 | `fdddf82225d120777c96fd8127f1584f4139c2767e5bbf0436c12db31783d238` |

完整 [冻结差分](frozen-source.patch) 与 [补丁身份](frozen-source-patch.json) 仅是草稿证据副本，不是源码提交。两份 tracked 草稿为 +88/-35；新增未跟踪 Native 测试为 655 行。本轮没有编辑任何 Kotlin，不暂存这三份工程文件。

[静态检查](static-validation.json)：元数据、README、执行脚本及源码副本的 diff-check 通过，Markdown 链接目标存在。完整原始证据 diff-check 的 exit2 仅记录日志/XML/补丁内原有空白警告，详见 [原始检查输出](diff-check-raw.log)；没有为消除警告裁剪原始证据。manifest 对每个成员保留原始长度与 SHA-256（排除 manifest 自身），提交前另行逐一核对 staged Git blob 与磁盘原始字节一致。

## 本轮新执行结果

三个 Gate 各执行一次、严格顺序；每个后续 Gate 前都有独立资源和身份检查。没有 clean、rerun-tasks、失败重试或新排除。

| Gate | suites / tests | 本轮实际结果 | exit |
| --- | --- | --- | ---: |
| A — Fish Presentation/Layout/Window | 3 / 15 | 15 PASS，0 FAIL / errors / SKIPPED | 0 |
| B — F1/F2、Settings、Studio 八类 | 8 / 82 | 82 PASS，0 FAIL / errors / SKIPPED | 0 |
| C — 原 bounded Desktop | 159 / 1526 | 1523 PASS，1 FAIL，0 errors，2 历史 SKIPPED | 1 |

[分阶段统计](stage-summary.json) 独立核算各次新 XML，不能相加当成不同测试覆盖。170 份原始 XML 的长度、SHA-256、每个方法结果在 [JUnit ledger](junit-ledger.json)；每个阶段目录含 command.txt、完整 stdout/stderr、进程身份、资源采样和 stage.json。只复制本次阶段开始之后新生成的 XML，没有使用旧 XML 冒充本轮执行。

JDK17 / Temurin 17.0.20.1、Gradle9.1.0、offline、no-daemon、no-parallel、single worker、low priority、Kotlin in-process、原 2048MiB heap 保持不变。Gradle 的资源生成任务 SKIPPED 与 JUnit SKIPPED 分开，不混算测试结果。

原 R3 [bounded.init.gradle](bounded.init.gradle) 逐字节复制使用，[来源与哈希](bounded-policy.json) 证实只有原四项排除：WindowsBackgroundViewerTest、WindowsStudioEdgeDragTest、WindowsWindowChromeTest，以及 DesktopImageWindowPresentationTest 的 short-tool 单方法。没有排除 NativeUx。

## 首个且唯一的新 RED — OBSERVED

`DesktopImageClipboardFilesTest.junction target is never inspected changed or cleaned` 在 `DesktopImageClipboardFilesTest.kt:280` 失败。原始栈为 `java.io.IOException: Cannot run program "cmd.exe": CreateProcess error=5`。本地化错误文本在原 XML 内已有替换字符，保持原样，没有重写或补造错误原文。

- [失败原始 XML](03-bounded-desktop/junit/TEST-com.example.chatbar.desktop.DesktopImageClipboardFilesTest.xml)，SHA-256 `83ff87c830bb458d6d4cc3edce42a91495221f8315f82bb8af3725f407ac316c`。
- [完整失败栈](failures.json)、[观察与未知项](failure-observation.json)、[未改源码摘录](failure-source-excerpt.txt)。该测试文件未修改，Git blob 与原始工作文件身份已登记。
- 失败发生在合成 junction 夹具 `ProcessBuilder("cmd.exe", "/c", "mklink", "/J", ...).start()`，尚未到达该用例的 junction target/clipboard 安全断言。这些未到达的断言不能记为 PASS。

**INFERRED：** 这是辅助进程创建失败位置的证据，不是 Fish 分页产品布局缺陷的证据。**UNKNOWN：** error=5 的更底层原因，是否涉及安全策略、可执行文件访问或其他因素。未单独重试 cmd/mklink，未修改系统权限或策略，未将它直接归因于内存或 WIN-AUTO-001。

控制台出现新 RED 后停止扩大工作；只让已经启动的同一次 bounded 执行结束和 finally 排水，没有强杀测试 JVM。未启动任何新 Gate、修复、重跑、源码候选提交或打包。

## NativeUx 在本次 bounded 的实际结果 — PASS

[本次 Native XML](03-bounded-desktop/junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml) 是 1 suite / 4 tests / 4 PASS / 0 FAIL、errors、SKIPPED，SHA-256 `b553eb7f9d9f4207027ae561dd7d8e3ef01a3d202cb78faf8bc2a99007aa816f`。

[原始 system-out](native-system-out.txt) 与 [独立摘要](native-in-bounded.json) 记录：1100/720/360dp 均完成实际 Press、16 步 Drag、Release 和滚轮；总计 3 次 Press、48 步 Drag、3 次拖动完成及 3 次滚轮完成。360dp 两次分页行分组记录及 unknown-total/Int 边界分支均到达。完整外框 12px 上限、配对 Row/FlowRow 身份、显式 Enter/点击查询、无效输入不查询、切页归顶等原断言没有改变。

Escape 层级、Stop 可见与操作、resize/cache 三个方法也实际 PASS；这些新 Native PASS 不覆盖 bounded 的独立新 RED，也不代替未执行的实包人工视觉/听觉验收。R6D 的 4/4 继续作为历史原始结果保留。

## SKIPPED 与历史保全

[两项 SKIPPED](skipped-tests.json) 与 R3 身份完全相同，[分类核对](skipped-classification.json) 记录无新增未知 SKIPPED：

- DesktopDocumentIndexTransactionInspectorTest — `real symlink requires executable platform fixture and is refused without following`。
- DesktopIndexStartupPreflightTest — `real symbolic links in entities and domain are refused when supported`。

它们仍是 SKIPPED，旧 symlink NOT TESTED 不提升为 PASS。R3–R6D 的原 RED、XML、manifest、用户人工 FAIL/NOT TESTED 均未改动，[历史目录 tree 身份](history-preservation.json) 保持一致。R3 历史拖动失败和额外鼠标移动的 UNKNOWN 不因本轮 Native PASS 倒改。

## 资源、请求与清理

开始 A/B/C 的 [preflight](A-preflight.json)、[B preflight](B-preflight.json)、[C preflight](C-preflight.json) 均观察到 Java/javaw 0。C 前可用物理约 1.95–1.97GiB、Commit 余量约14.7GiB、pages/s 三点均0、磁盘可用约14.14GiB，因此未新增固定 RAM 门槛而启动一次 Gate C。

各阶段最低可用物理内存：A `1083830272`、B `1326866432`、C `576487424` bytes（C 约0.537GiB）。最低 Commit 余量分别 `14194327552`、`13917409280`、`13484969984` bytes。最高 pages/s 分别21870、412、1749。原始采样保留：A 有单点高换页，未观察持续严重换页或 OOM；不能据此把 CreateProcess error=5 推断为资源根因。

[POST](POST-preflight.json) 三次采样可用物理约2.00–2.04GiB、pages/s0、Java/javaw0；无本轮 Gradle/应用残留。没有杀用户进程、清缓存/旧发行包或调整 pagefile。

真实 Fish **GET0 / TTS0**，未读取真实 API Key、聊天、卡包或用户数据根，没有启动人工 fake 服务。[请求分账](request-accounting.json) 明确区分：Native 在 bounded 中的 mock 样本 GET5 / TTS POST5（仅 loopback），合成 catalog28次、绑定0；B/C 每次七个状态诊断均记录各1次物理请求，503 为2次 interceptor 进入、1次 guard 阻止、服务端1次请求，凭据头泄漏0。其他既有 Mock 测试保留逐场景断言，但没有全套集中计数器，不能把子集计数冒充全部请求总数。

Native21个 fixture 的 finally 均通过 owner 恢复、自己窗口释放、scope/runtime drain、自己缓存无 MP3/part 断言。[清理/子进程记录](cleanup-processes.json) 和原始各阶段 process JSONL 分开保留。空夹具目录可能保留，没有做广泛临时目录删除；物理 I/O 绝对截止时间未获证明。

**NOT TESTED / NOT STARTED：** 新 Windows distribution、人工拖动/分页/Escape/Stop/听觉签署、新实包 Launcher/JVM退出和正式数据根锁 Gate。独立合成 AUDIO/LOCK 子 JVM 的成功记录不冒充新发行包证据。

## 保护、提交和后续

四个 user-owned 未跟踪文件仅登记路径，保持 KEEP / UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED；没有读取内容或哈希。两份生产草稿仍 tracked 修改，NativeUx仍未跟踪工程草稿，三份均未暂存/提交；不能称整个工作区 clean。

提交前进行了 [Skill 范围复核](skill-review.json)，无本轮导致的技能事实过时需要修改。[范围复核](scope-review.json) 记录没有修改 Main、Fish controller/runtime/HTTP/player/store、SecretStore、binding、Prompt、Package/Entity/schema、CURRENT、Feature Parity 或正式 baseline。没有 S3/G2/P9/P10/P11、上游同步、desktop merge 或发布。

仅本目录 evidence-only 提交获授权；其直接 parent 应为起点 `76f527418616e90ed469cabcf0b78c9530563b2f`。源码候选未建立，后续 Windows 打包 Gate 条件未满足。需 Project 独立评估本次辅助进程创建 RED，再另行授权诊断或续行；本轮 STOP。

WIN-AUTO-001、WIN-BRAND-001 继续 OPEN。本轮没有截图自动化、驱动或品牌窗口修改，未登记 CURRENT/Roadmap。

**P8-S2B-UI-LAYOUT-001-R6E HOLD / RAW RED PRESERVED / PROJECT REVIEW PENDING**
