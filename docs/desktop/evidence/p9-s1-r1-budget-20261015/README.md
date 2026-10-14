# P9-S1 R1 — preallocation budget verification

**CODEX CANDIDATE / PROJECT REVIEW PENDING**（2026-10-15）。本次仅修复异常来源标签引发的分块计划内存放大，不宣布 P9-S1 acceptance 或完整 P9/F4 parity。

## Exact source / commit split

- 起点 local/tracking/live feature：`e7a8ce5a3f0394eaea3a3e1240333a7294f27b8e`，工作区及暂存区 clean。
- 独立生产源码候选：`f3284c52b8327aa6eeca3a8d8cf6d7377d415d63`；只改 DesktopDocumentRagReader.kt，+70/-1。
- 本证据提交同时包含 DesktopDocumentRagReaderTest.kt 新增三项确定性测试，+73/-0，保留全部原 15 项断言。验证实际运行在起点加这两份工作区修改上；生产源码先冻结，新测试随本次独立证据提交冻结，没有将先前测试外推到其他代码。
- 两份源码的实际 SHA-256 和 canonical expected Git blob 见 `source-manifest.json`；不可变共享 ChunkingEngine、VectorSearchEngine、B5B gate、request planner 的 blob 对照见 `unchanged-authorities.json`。
- B5B 结案、CURRENT、D-040、Fish、P10/P11、正式 upstream baseline 1.4.1、Prompt、schema 与 Android RAG 均未改动。Skill 复核：既有 feature-map 的 bounded read-only 描述仍适用，无需修改。

## Admission limits and rationale

所有检查都在调用共享 `ChunkingEngine.chunkDocument` 之前完成；超限返回 **Unavailable(DOCUMENT)**，不截断、不写文件、不删除或重建索引。

| Limit | Bound / rationale |
| --- | --- |
| DocumentInfo.fileName | 256 UTF-16 chars；防止超长合法 metadata 字符串被复制进每个来源标签。先于文档读取和 plan 分配检查 |
| 单个标题 | 128 UTF-16 chars；按官方 Markdown / <=40-char plain-heading 识别规则核对，阻断超长标题路径复制 |
| 最终 source label | 1024 UTF-16 chars；累计最多六级标题、` > ` 和 file label，检查长度而不生成标签字符串 |
| 标题事件 / 非空分节 | 分别最多 2048；空标题事件也计数，阻断大量短分节或空标题路径分配 |
| 声明的 ragChunkCount | 1–2048；Int.MAX_VALUE 等异常值先拒绝，不能作为可信内存预算 |
| 保守 chunk 数估计 | 总计 <=2048；支持已有 1200-chunk 正常用例 |
| 带标签输出估计 | <=2 Mi UTF-16 chars，约 4 MiB 文本 payload；约束标签重复放大，不是整个 JVM heap 绝对上限 |

预检用 `lineSequence` 和最多六个已限长标题，不创建完整 section body、分块字符串或计划。只估算准入，不产生第二套分块结果。官方识别标题的行为（含普通标题、层级路径和无正文时的 fallback）保持；实际文本/metadata/排序仍交给原共享算法。

设非空分节 body 字符数保守上界为 B：B<=700 时最多一块；否则使用 `2*floor(B/700)+2` 上界。共享 greedy merge 中任何相邻最终块长度和至少 700；trim/split/join 不增加来源 body 字符总量，故该上界保守。Overlap 至多 70 chars，每块预算为 `labelLength+775`（700 body +70 overlap +5 来源前缀/换行）。每个计数、累加和乘积使用 Long；输入仍先受原 1 MiB 文档限制，metadata Int 不参与未检查的乘法。

估计可能拒绝实际块数较少的合法长文档：这是首阶段安全准入范围收缩，不声称改进完整检索兼容性，也不修改/截断来源。共享解析中原有中间列表/对象有由输入和事件上限导出的有限开销，未声称物理 heap 只有 4 MiB 或不可取消阻塞 I/O 有绝对超时保证。

## Deterministic tests

新增三项测试：

1. `oversized names headings labels and sections are refused before shared plan allocation`：4096-char 合法逻辑文件名、129-char 标题、六级标题与256-char文件名导致标签越界、2049 个短 Markdown/plain 分节。全部在 plan 调用前拒绝。
2. `claimed chunk count and estimated output amplification have preallocation budgets`：声明 2049/Int.MAX_VALUE chunks、720000-char body（声明仍为1）、六级长标签+600000-char body。验证实际估计不能被 metadata 低报绕过；输入有限，无 OOM 探针。
3. `budget admission preserves normal heading hierarchy and existing chunk source parity`：1200 个合法分节通过预算、真正调用共享算法一次，topK/threshold/稳定排序与共享 search 一致。

测试 seam 只观察共享 plan 生成前的调用计数，不替换或 mock 分块器；异常用例计数始终为0。每次拒绝前后所有 CharacterCard、owned document、VectorChunk 载荷 SHA-256 完全一致；ownership lock 是保留基础设施，不作为可读取 payload。原来的来源验证、取消、topK、零值/溢出、严格扫描、1200文件和模型序列化断言继续运行。

## Actual verification

Temurin JDK17 / Gradle9.1.0 / 原2GB heap。完整命令参数、退出码、时间见 `gradle/*.json`，原始输出见 `.log`：

```text
--offline --no-daemon --no-parallel --max-workers=1 --priority=low
--console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process
```

| Stage | Actual result |
| --- | --- |
| 01 reader | Desktop main/test 实际编译 PASS；读取器1 suite /18 tests PASS，exit0 |
| 02 regression | 4 suites /38 tests PASS，exit0；编译依赖 UP-TO-DATE，test 实际执行 |

最终四类：DesktopDocumentRagReaderTest **18**、DesktopFormatRepairDerivedSafetyTest **15**、DesktopMainChatRequestAssemblerIntegrationTest **1**、DesktopRealChatRuntimeTest **4**。全部 failures/errors/skips 为0。SharedCore编译依赖 UP-TO-DATE；SharedCore测试、Android编译/测试、原P9全范围回归、Windows人工、打包 **NOT RUN**，不引用旧68/125为本轮结果。

本轮无新增编译或测试 RED；原 P9-S1 两轮编译RED、两轮测试RED、最终PASS以及B5B历史证据全部原样保留，不覆盖。五份实际 XML 和两份原始日志经敏感内容检查，均为 synthetic/localhost 证据；`junit-manifest.json` / `log-manifest.json` 保存每份统计及 SHA-256。Scoped raw XML/log attributes 保全 CRLF 和原始空白，源码/Markdown仍按正常 diff-check 检查；提交前独立 Git blob readback 必须等于原始证据哈希。

## Resources / remaining limits

预检可用约2470.6 MiB，Commit余量约11.85 GiB，H:约38.00 GiB，无Java进程；最低采样可用约1123.4 MiB，Commit余量约10.40 GiB，Pages/sec观察最高310。单一Gradle build，未OOM、未达到持续严重保护条件；总资源峰值UNKNOWN，未高频监控，不操作Chrome/NAI。详见脱敏 `resources.jsonl`。

**PROJECT ACCEPTANCE PENDING。** 不改变原只读 owned-document 子集、F4-B5B门禁或P9后续索引/注入范围；Windows真机和大库总体性能仍待后续独立验收。
