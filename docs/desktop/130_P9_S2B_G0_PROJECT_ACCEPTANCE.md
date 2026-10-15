# P9-S2B-G0 Project Acceptance

日期：2026-10-16。Project 独立裁定：**G0 CLOSED / QUALIFIED ENGINEERING ACCEPTED**。本文件仅登记已作出的限定工程验收，不重新实现、测试或批准生产启动接入。

## Verified checkpoints and identities

| Checkpoint | Exact commit | Direct parent |
| --- | --- | --- |
| 起点 | `8820462db2a968b10ef59488985edef21d025973` | 本轮验收基准 |
| A：G0实施合同 | `0147960fb408ebec9c23174688421a5a4232f260` | `8820462db2a968b10ef59488985edef21d025973` |
| B：源码及测试 | `1123c13f9f0826282b7b75537e82eda9732ee73c` | `0147960fb408ebec9c23174688421a5a4232f260` |
| C：原始证据 | `31852dbd7230b836f8195cfb68932f3e9b33a711` | `1123c13f9f0826282b7b75537e82eda9732ee73c` |

Project 已核对三次提交为真实单父线性链；B仅三文件 **+500/-1**，C为独立证据提交。证据目录：`evidence/p9-s2b-g0-preflight-20261016/`。35项证据文件的SHA-256及字节数均由Project独立核验；`evidence-manifest.json`自身SHA-256为 `85fd24c22641ae976977434f6bffc6398ea067939b8204cfdef12de570995dc9`。

`source-manifest.json`四份身份均成立：

| File | Git blob | Recorded working-tree bytes |
| --- | --- | ---: |
| `DesktopIndexStartupPreflight.kt`（Desktop main） | `03febf66c0022a22aec41a30b3d903e4d90a634c` | 13,536 |
| `DesktopIndexStartupPreflightTest.kt`（Desktop test） | `570483e6f9eb7da17af560a6ab1914bae8a56a14` | 17,506 |
| `.agents/skills/chatbar-feature-map/SKILL.md` | `043ef1059afc375e713c0cf7cdec8ee076083f3f` | 15,276 |
| `129_P9_S2B_G0_STARTUP_PREFLIGHT_CONTRACT.md` | `0fb17cd57f52e0843d9075eb5bd72e8b2d42d9f3` | 4,614 |

Feature Map Skill的Windows CRLF工作树字节不同于Git LF blob；Project经换行重建确认15,276 bytes及声明SHA-256完全一致，不将原始工作树hash与规范化blob混为一谈。四份完整SHA-256保留在原始manifest中。

## Reviewed validation and retained RED

最终真实JUnit XML：**6 suites / 86 tests，85 PASS、1 SKIPPED、0 failures/errors**。Stage03 Desktop main/test实际编译PASS，Stage04限定ownership、数据根路径、Portable及启动生命周期聚焦回归通过。SharedCore为UP-TO-DATE，不称本轮重新编译通过。

Stage01 test compile RED、Stage02 **19 tests / 15 failures** RED均由Project独立确认并原样保留；不把后续绿色覆盖到旧失败。最终零写入记录40项：38项文件与目录快照相同，2项明确平台限制；原始事件与派生汇总一致。

G0真实symlink夹具不可用，对应1项JUnit SKIPPED。既有ownership/Portable测试另有四次symlink fixture早退，不包含在该1项skipped中，也不能算作相应平台分支测试通过。

**Tests rerun: NO — reused Project-reviewed G0 evidence**。本轮只做docs-only静态检查；原始证据README、XML、日志、候选状态和历史RED均不改写。

## Qualified engineering scope

接受的是独立、**无生产调用入口**的元数据预检器：复用已有root ownership，不取第二把锁，不依赖业务Container/Repository，不读取事务payload、Entity正文、文档或Secrets，不写文件、不恢复或发布索引。路径/身份/ownership复核、受限目录遍历、checked预算、脱敏结果、fail-closed错误分类及协程取消/目录句柄释放获得限定验收。

`NO_DOMAIN`仅代表候选域 `entities/document_index_transactions/` 在本次可信检查中缺失，**绝不代表**旧`vector_chunks`、`.replace-*`、`.backup-*`或整个用户数据根安全，不提供永久readiness。域存在时，包括空目录或completed/marker，不能返回READY/COMMITTED；没有生产Journal schema注册或协议解释。

Windows只读`FILE_ID_INFO`机制已核对：JDK17 Windows NIO fileKey为null时，使用只读属性句柄取得卷/文件身份，检查reparse并可靠关闭；不是放弃身份校验。真实junction及部分内部/外部硬链接路径已执行，但不构成全部链接/竞争安全证明。

## Unverified boundaries and next authorization

仍未证明真实symlink路径、持锁期间实际root replacement、完整Windows TOCTOU、域外硬链接不存在性、真实ACL全覆盖及断电耐久性。root类型/身份竞争的受控seam不能冒充真实OS替换。资源数据仅为采样，不能称绝对内存峰值或用户前台响应通过；阻塞文件I/O没有物理绝对超时保证。

正式EXE、GUI、打包、Android、全量回归、生产数据及收费Embedding API均未执行。G1/G2、Main启动调用、生产Writer、真实恢复、迁移、索引持久化、多代收据及Flow/cache屏障继续等待独立授权。`127`仍为生产集成候选合同，`128`仍为尚未实施的启动门禁设计；本次G0验收不批准它们的生产实施。

本裁定仅在上述范围取代129及候选证据中的NOT ACCEPTED/PROJECT REVIEW PENDING；原文本保留原时点历史。完整P9、F4、P8 Fish、P10/P11及Public Preview均不提升；`13_FEATURE_PARITY.md`不提升为EXACT/EQUIVALENT。正式validated baseline仍为ChatChatBar1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`，不改`10_UPSTREAM_BASELINE.json`、Prompt或D-036/D-037/D-038/D-040状态，不合并desktop或同步upstream。

本轮交付状态：**G0 ACCEPTANCE DOCUMENTED / PROJECT DOC DIFF REVIEW PENDING**。工程验收为Project已作出的决定，本文档diff仍交回Project独立复审，不自动开始后续任务。
